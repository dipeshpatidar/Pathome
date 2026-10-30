import { isOperationsSessionActive, type OperationsSession } from './operationsSession';
import { isOperationId } from './operationsOperationId';

export type RecoverableCommandKind =
  | 'LEAD_CAPTURE'
  | 'LEAD_SHORTLIST_CREATE'
  | 'LEAD_SHARE_EVENT';

export type RecoverableShareEventType =
  | 'CLIPBOARD_COPIED'
  | 'HANDOFF_ATTEMPTED'
  | 'OPERATOR_REPORTED_MANUAL_SEND'
  | 'HANDOFF_CANCELLED';

interface RecoveryBase {
  version: 1;
  kind: RecoverableCommandKind;
  operationId: string;
  savedAtEpochMs: number;
}

export interface PendingCaptureCommand extends RecoveryBase {
  kind: 'LEAD_CAPTURE';
}

export interface PendingShortlistCreationCommand extends RecoveryBase {
  kind: 'LEAD_SHORTLIST_CREATE';
  leadId: number;
}

export interface PendingShareEventCommand extends RecoveryBase {
  kind: 'LEAD_SHARE_EVENT';
  leadId: number;
  shareId: string;
  eventType: RecoverableShareEventType;
}

export type PendingRecoverableCommand =
  | PendingCaptureCommand
  | PendingShortlistCreationCommand
  | PendingShareEventCommand;

export type CommandRecoveryBlockReason =
  | 'EXPIRED'
  | 'CORRUPT'
  | 'INVALID_TIME'
  | 'STORAGE_UNAVAILABLE'
  | 'SESSION_CHANGED';

export type PendingCommandRead<T extends PendingRecoverableCommand> =
  | { state: 'ABSENT' }
  | { state: 'PENDING'; command: T }
  | { state: 'BLOCKED'; reason: CommandRecoveryBlockReason };

export type CommandRecoveryErrorCode =
  | 'STORAGE_UNAVAILABLE'
  | 'CAPACITY'
  | 'CONFLICT'
  | 'SESSION_CHANGED'
  | 'INVALID_INPUT'
  | 'RECOVERY_BLOCKED';

export class CommandRecoveryError extends Error {
  readonly code: CommandRecoveryErrorCode;
  readonly reason: CommandRecoveryBlockReason | null;

  constructor(
    code: CommandRecoveryErrorCode,
    message: string,
    reason: CommandRecoveryBlockReason | null = null
  ) {
    super(message);
    this.name = 'CommandRecoveryError';
    this.code = code;
    this.reason = reason;
  }
}

const PREFIX = 'pathome_oe_command_recovery_v1:';
const PENDING_TTL_MS = 24 * 60 * 60 * 1000;
const FUTURE_TOLERANCE_MS = 60_000;
const MAX_PENDING = 12;
const SHARE_ID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const SHARE_EVENT_TYPES = new Set<RecoverableShareEventType>([
  'CLIPBOARD_COPIED',
  'HANDOFF_ATTEMPTED',
  'OPERATOR_REPORTED_MANUAL_SEND',
  'HANDOFF_CANCELLED'
]);
let bound = false;

const positiveInteger = (value: unknown): value is number =>
  Number.isSafeInteger(value) && Number(value) > 0;

const captureKey = (session: OperationsSession): string =>
  `${PREFIX}${session.identity}:capture`;

const shortlistKey = (session: OperationsSession, leadId: number): string =>
  `${PREFIX}${session.identity}:shortlist-create:${leadId}`;

const shareEventKey = (session: OperationsSession, leadId: number): string =>
  `${PREFIX}${session.identity}:share-event:${leadId}`;

const removeSafely = (storageKey: string): boolean => {
  try {
    sessionStorage.removeItem(storageKey);
    return sessionStorage.getItem(storageKey) === null;
  } catch {
    return false;
  }
};

const bindSessionBoundary = (): void => {
  if (bound || typeof window === 'undefined') return;
  bound = true;
  window.addEventListener('pathome_auth_changed', clearPendingCommandRecovery);
  window.addEventListener('pathome_session_expired', clearPendingCommandRecovery);
  window.addEventListener('storage', (event) => {
    if (event.key === null
      || ['pathome_auth_token', 'pathome_user', 'pathome_role'].includes(event.key)) {
      clearPendingCommandRecovery();
    }
  });
};

export const clearPendingCommandRecovery = (): void => {
  try {
    for (let index = sessionStorage.length - 1; index >= 0; index -= 1) {
      const storageKey = sessionStorage.key(index);
      if (storageKey?.startsWith(PREFIX)) sessionStorage.removeItem(storageKey);
    }
  } catch {
    // The authentication boundary still prevents another account from acting.
  }
};

type StoredClassification =
  | { state: 'PARSED'; value: unknown }
  | { state: 'BLOCKED'; reason: CommandRecoveryBlockReason };

const classifyStored = (raw: string, now: number): StoredClassification => {
  let value: unknown;
  try {
    value = JSON.parse(raw) as unknown;
  } catch {
    return { state: 'BLOCKED', reason: 'CORRUPT' };
  }
  if (!value || typeof value !== 'object') {
    return { state: 'BLOCKED', reason: 'CORRUPT' };
  }
  const savedAt = (value as Partial<RecoveryBase>).savedAtEpochMs;
  if (!Number.isSafeInteger(savedAt)) {
    return { state: 'BLOCKED', reason: 'CORRUPT' };
  }
  if (Number(savedAt) > now + FUTURE_TOLERANCE_MS) {
    return { state: 'BLOCKED', reason: 'INVALID_TIME' };
  }
  if (now - Number(savedAt) > PENDING_TTL_MS) {
    return { state: 'BLOCKED', reason: 'EXPIRED' };
  }
  return { state: 'PARSED', value };
};

const readStored = <T extends PendingRecoverableCommand>(
  session: OperationsSession,
  storageKey: string,
  now: number,
  isCommand: (value: unknown) => value is T
): PendingCommandRead<T> => {
  bindSessionBoundary();
  if (!isOperationsSessionActive(session)) {
    return { state: 'BLOCKED', reason: 'SESSION_CHANGED' };
  }
  let raw: string | null;
  try {
    raw = sessionStorage.getItem(storageKey);
  } catch {
    return { state: 'BLOCKED', reason: 'STORAGE_UNAVAILABLE' };
  }
  if (raw === null) return { state: 'ABSENT' };
  const stored = classifyStored(raw, now);
  if (stored.state === 'BLOCKED') return stored;
  return isCommand(stored.value)
    ? { state: 'PENDING', command: stored.value }
    : { state: 'BLOCKED', reason: 'CORRUPT' };
};

const validBase = <T extends RecoveryBase>(
  value: unknown,
  kind: RecoverableCommandKind
): value is T => {
  if (!value || typeof value !== 'object') return false;
  const record = value as Partial<T>;
  return record.version === 1
    && record.kind === kind
    && isOperationId(record.operationId)
    && Number.isSafeInteger(record.savedAtEpochMs);
};

export const readPendingCapture = (
  session: OperationsSession,
  now = Date.now()
): PendingCommandRead<PendingCaptureCommand> =>
  readStored(
    session,
    captureKey(session),
    now,
    (value): value is PendingCaptureCommand =>
      validBase<PendingCaptureCommand>(value, 'LEAD_CAPTURE')
  );

export const readPendingShortlistCreation = (
  session: OperationsSession,
  leadId: number,
  now = Date.now()
): PendingCommandRead<PendingShortlistCreationCommand> => {
  if (!positiveInteger(leadId)) return { state: 'ABSENT' };
  return readStored(
    session,
    shortlistKey(session, leadId),
    now,
    (value): value is PendingShortlistCreationCommand =>
      validBase<PendingShortlistCreationCommand>(value, 'LEAD_SHORTLIST_CREATE')
      && value.leadId === leadId
  );
};

export const readPendingShareEvent = (
  session: OperationsSession,
  leadId: number,
  now = Date.now()
): PendingCommandRead<PendingShareEventCommand> => {
  if (!positiveInteger(leadId)) return { state: 'ABSENT' };
  return readStored(
    session,
    shareEventKey(session, leadId),
    now,
    (value): value is PendingShareEventCommand =>
      validBase<PendingShareEventCommand>(value, 'LEAD_SHARE_EVENT')
      && value.leadId === leadId
      && SHARE_ID.test(value.shareId)
      && SHARE_EVENT_TYPES.has(value.eventType)
  );
};

const sameCommand = <T extends PendingRecoverableCommand>(left: T, right: T): boolean => {
  const { savedAtEpochMs: _leftSavedAt, ...leftMetadata } = left;
  const { savedAtEpochMs: _rightSavedAt, ...rightMetadata } = right;
  return JSON.stringify(leftMetadata) === JSON.stringify(rightMetadata);
};

const blockedError = (
  reason: CommandRecoveryBlockReason,
  message: string
): CommandRecoveryError =>
  new CommandRecoveryError('RECOVERY_BLOCKED', message, reason);

const persist = <T extends PendingRecoverableCommand>(
  session: OperationsSession,
  storageKey: string,
  pending: T,
  isCommand: (value: unknown) => value is T,
  conflictMessage: string,
  writeFailureMessage: string,
  now: number
): T => {
  bindSessionBoundary();
  if (!isOperationsSessionActive(session)) {
    throw new CommandRecoveryError(
      'SESSION_CHANGED',
      'Your session changed before the command was saved.'
    );
  }
  let raw: string | null;
  try {
    raw = sessionStorage.getItem(storageKey);
  } catch {
    throw new CommandRecoveryError('STORAGE_UNAVAILABLE', writeFailureMessage);
  }
  if (raw !== null) {
    const stored = classifyStored(raw, now);
    if (stored.state === 'BLOCKED') {
      throw blockedError(
        stored.reason,
        'An earlier command remains unconfirmed and must be resolved before creating another command.'
      );
    }
    if (!isCommand(stored.value)) {
      throw blockedError(
        'CORRUPT',
        'An earlier command remains unconfirmed and must be resolved before creating another command.'
      );
    }
    if (sameCommand(stored.value, pending)) return stored.value;
    throw new CommandRecoveryError('CONFLICT', conflictMessage);
  }

  let pendingCount: number;
  try {
    pendingCount = 0;
    for (let index = 0; index < sessionStorage.length; index += 1) {
      if (sessionStorage.key(index)?.startsWith(PREFIX)) pendingCount += 1;
    }
  } catch {
    throw new CommandRecoveryError('STORAGE_UNAVAILABLE', writeFailureMessage);
  }
  if (pendingCount >= MAX_PENDING) {
    throw new CommandRecoveryError(
      'CAPACITY',
      'Too many earlier commands are awaiting confirmation in this browser.'
    );
  }
  try {
    sessionStorage.setItem(storageKey, JSON.stringify(pending));
    if (!isOperationsSessionActive(session)) {
      removeSafely(storageKey);
      throw new CommandRecoveryError(
        'SESSION_CHANGED',
        'Your session changed before the command was saved.'
      );
    }
    return pending;
  } catch (cause) {
    if (cause instanceof CommandRecoveryError) throw cause;
    throw new CommandRecoveryError('STORAGE_UNAVAILABLE', writeFailureMessage);
  }
};

export const preservePendingCapture = (
  session: OperationsSession,
  operationId: string,
  now = Date.now()
): PendingCaptureCommand => {
  if (!isOperationId(operationId)) {
    throw new CommandRecoveryError(
      'INVALID_INPUT',
      'The enquiry command reference is invalid.'
    );
  }
  return persist(
    session,
    captureKey(session),
    {
      version: 1,
      kind: 'LEAD_CAPTURE',
      operationId,
      savedAtEpochMs: now
    },
    (value): value is PendingCaptureCommand =>
      validBase<PendingCaptureCommand>(value, 'LEAD_CAPTURE'),
    'Another enquiry command is still being checked. Check it before creating a second enquiry.',
    'This browser could not keep the enquiry command. Nothing was sent.',
    now
  );
};

export const preservePendingShortlistCreation = (
  session: OperationsSession,
  operation: { operationId: string; leadId: number },
  now = Date.now()
): PendingShortlistCreationCommand => {
  if (!isOperationId(operation.operationId) || !positiveInteger(operation.leadId)) {
    throw new CommandRecoveryError(
      'INVALID_INPUT',
      'The shortlist command is invalid. Nothing was sent.'
    );
  }
  return persist(
    session,
    shortlistKey(session, operation.leadId),
    {
      version: 1,
      kind: 'LEAD_SHORTLIST_CREATE',
      operationId: operation.operationId,
      leadId: operation.leadId,
      savedAtEpochMs: now
    },
    (value): value is PendingShortlistCreationCommand =>
      validBase<PendingShortlistCreationCommand>(value, 'LEAD_SHORTLIST_CREATE')
      && value.leadId === operation.leadId,
    'Another shortlist command is still being checked for this Lead.',
    'This browser could not keep the shortlist command. Nothing was sent.',
    now
  );
};

export const preservePendingShareEvent = (
  session: OperationsSession,
  operation: {
    operationId: string;
    leadId: number;
    shareId: string;
    eventType: RecoverableShareEventType;
  },
  now = Date.now()
): PendingShareEventCommand => {
  if (!isOperationId(operation.operationId)
    || !positiveInteger(operation.leadId)
    || !SHARE_ID.test(operation.shareId)
    || !SHARE_EVENT_TYPES.has(operation.eventType)) {
    throw new CommandRecoveryError(
      'INVALID_INPUT',
      'The share action reference is invalid. Nothing was recorded.'
    );
  }
  return persist(
    session,
    shareEventKey(session, operation.leadId),
    {
      version: 1,
      kind: 'LEAD_SHARE_EVENT',
      operationId: operation.operationId,
      leadId: operation.leadId,
      shareId: operation.shareId,
      eventType: operation.eventType,
      savedAtEpochMs: now
    },
    (value): value is PendingShareEventCommand =>
      validBase<PendingShareEventCommand>(value, 'LEAD_SHARE_EVENT')
      && value.leadId === operation.leadId
      && SHARE_ID.test(value.shareId)
      && SHARE_EVENT_TYPES.has(value.eventType),
    'Another share action is still being checked for this Lead.',
    'This browser could not keep the share action reference. Nothing was recorded.',
    now
  );
};

export const forgetPendingCapture = (
  session: OperationsSession,
  operationId: string
): boolean => {
  if (!isOperationsSessionActive(session)) return false;
  const storageKey = captureKey(session);
  try {
    const pending = readPendingCapture(session);
    if (pending.state === 'ABSENT') return true;
    return pending.state === 'PENDING'
      && pending.command.operationId === operationId
      && removeSafely(storageKey);
  } catch {
    return false;
  }
};

export const forgetPendingShortlistCreation = (
  session: OperationsSession,
  leadId: number,
  operationId: string
): boolean => {
  if (!isOperationsSessionActive(session)) return false;
  const storageKey = shortlistKey(session, leadId);
  try {
    const pending = readPendingShortlistCreation(session, leadId);
    if (pending.state === 'ABSENT') return true;
    return pending.state === 'PENDING'
      && pending.command.operationId === operationId
      && removeSafely(storageKey);
  } catch {
    return false;
  }
};

export const forgetPendingShareEvent = (
  session: OperationsSession,
  leadId: number,
  operationId: string
): boolean => {
  if (!isOperationsSessionActive(session)) return false;
  const storageKey = shareEventKey(session, leadId);
  try {
    const pending = readPendingShareEvent(session, leadId);
    if (pending.state === 'ABSENT') return true;
    return pending.state === 'PENDING'
      && pending.command.operationId === operationId
      && removeSafely(storageKey);
  } catch {
    return false;
  }
};

export const commandStatusIsUncertain = (status: number | undefined): boolean =>
  status === undefined
  || status >= 500
  || (status >= 200 && status < 300);
