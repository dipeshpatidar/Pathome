import { isOperationsSessionActive, type OperationsSession } from './operationsSession';
import { isOperationId } from './operationsOperationId';

export type SharePrepareMode = 'COPY_LINKS' | 'MANUAL_BUSINESS_HANDOFF';

/**
 * The minimum information needed to ask the server whether one property-message preparation was
 * recorded. It deliberately excludes phone numbers, message text, links, tokens and names.
 */
export interface PendingSharePrepareOperation {
  operationId: string;
  leadId: number;
  expectedLeadVersion: number;
  shortlistId: number;
  expectedShortlistVersion: number;
  contactPreferenceRevision: number;
  mode: SharePrepareMode;
  savedAtEpochMs: number;
}

const PREFIX = 'pathome_oe_share_prepare_v1:';
const PENDING_TTL_MS = 24 * 60 * 60 * 1000;
const MAX_PENDING = 8;
let bound = false;

const key = (session: OperationsSession, leadId: number): string =>
  `${PREFIX}${session.identity}:${leadId}`;

const isPositiveInteger = (value: unknown): value is number =>
  Number.isSafeInteger(value) && Number(value) > 0;

const isNonNegativeInteger = (value: unknown): value is number =>
  Number.isSafeInteger(value) && Number(value) >= 0;

const validMode = (value: unknown): value is SharePrepareMode =>
  value === 'COPY_LINKS' || value === 'MANUAL_BUSINESS_HANDOFF';

const removeSafely = (storageKey: string): void => {
  try {
    sessionStorage.removeItem(storageKey);
  } catch {
    // No protected fallback exists; the caller will require a fresh explicit preparation.
  }
};

const clearExpired = (now = Date.now()): void => {
  try {
    for (let index = sessionStorage.length - 1; index >= 0; index -= 1) {
      const storageKey = sessionStorage.key(index);
      if (!storageKey?.startsWith(PREFIX)) continue;
      const raw = sessionStorage.getItem(storageKey);
      if (!raw) continue;
      try {
        const value = JSON.parse(raw) as Partial<PendingSharePrepareOperation>;
        if (!Number.isSafeInteger(value.savedAtEpochMs)
          || now - Number(value.savedAtEpochMs) > PENDING_TTL_MS
          || Number(value.savedAtEpochMs) > now + 60_000) {
          sessionStorage.removeItem(storageKey);
        }
      } catch {
        sessionStorage.removeItem(storageKey);
      }
    }
  } catch {
    // Storage refusal means this session has no recoverable pending preparation.
  }
};

export const clearPendingSharePreparations = (): void => {
  try {
    for (let index = sessionStorage.length - 1; index >= 0; index -= 1) {
      const storageKey = sessionStorage.key(index);
      if (storageKey?.startsWith(PREFIX)) sessionStorage.removeItem(storageKey);
    }
  } catch {
    // The authentication boundary removes access even when browser storage refuses deletion.
  }
};

const bind = (): void => {
  if (bound || typeof window === 'undefined') return;
  bound = true;
  window.addEventListener('pathome_auth_changed', clearPendingSharePreparations);
  window.addEventListener('pathome_session_expired', clearPendingSharePreparations);
  window.addEventListener('storage', (event) => {
    if (event.key === null
      || ['pathome_auth_token', 'pathome_user', 'pathome_role'].includes(event.key)) {
      clearPendingSharePreparations();
    }
  });
};

export const readPendingSharePreparation = (
  session: OperationsSession,
  leadId: number,
  now = Date.now()
): PendingSharePrepareOperation | null => {
  bind();
  if (!isOperationsSessionActive(session) || !isPositiveInteger(leadId)) return null;
  clearExpired(now);
  let raw: string | null;
  try {
    raw = sessionStorage.getItem(key(session, leadId));
  } catch {
    throw new Error('The earlier property message could not be checked in this browser.');
  }
  if (!raw) return null;
  try {
    const value = JSON.parse(raw) as Partial<PendingSharePrepareOperation>;
    if (!isOperationId(value.operationId)
      || value.leadId !== leadId
      || !isPositiveInteger(value.expectedLeadVersion)
      || !isPositiveInteger(value.shortlistId)
      || !isNonNegativeInteger(value.expectedShortlistVersion)
      || !isPositiveInteger(value.contactPreferenceRevision)
      || !validMode(value.mode)
      || !Number.isSafeInteger(value.savedAtEpochMs)) {
      throw new Error('invalid');
    }
    return value as PendingSharePrepareOperation;
  } catch {
    removeSafely(key(session, leadId));
    throw new Error('The earlier property message could not be checked in this browser.');
  }
};

export const preservePendingSharePreparation = (
  session: OperationsSession,
  operation: Omit<PendingSharePrepareOperation, 'savedAtEpochMs'>,
  now = Date.now()
): PendingSharePrepareOperation => {
  bind();
  if (!isOperationsSessionActive(session)) {
    throw new Error('Your session ended before the property message was prepared.');
  }
  if (!isOperationId(operation.operationId)
    || !isPositiveInteger(operation.leadId)
    || !isPositiveInteger(operation.expectedLeadVersion)
    || !isPositiveInteger(operation.shortlistId)
    || !isNonNegativeInteger(operation.expectedShortlistVersion)
    || !isPositiveInteger(operation.contactPreferenceRevision)
    || !validMode(operation.mode)) {
    throw new Error('The property message request is invalid. Nothing was sent.');
  }
  const existing = readPendingSharePreparation(session, operation.leadId, now);
  if (existing) {
    const sameRequest = existing.operationId === operation.operationId
      && existing.expectedLeadVersion === operation.expectedLeadVersion
      && existing.shortlistId === operation.shortlistId
      && existing.expectedShortlistVersion === operation.expectedShortlistVersion
      && existing.contactPreferenceRevision === operation.contactPreferenceRevision
      && existing.mode === operation.mode;
    if (!sameRequest) {
      throw new Error(
        'Another property message is still being checked for this Lead. Check it before preparing another.'
      );
    }
    return existing;
  }
  clearExpired(now);
  let pendingKeys = 0;
  try {
    for (let index = 0; index < sessionStorage.length; index += 1) {
      if (sessionStorage.key(index)?.startsWith(PREFIX)) pendingKeys += 1;
    }
    if (pendingKeys >= MAX_PENDING) {
      throw new Error(
        'Too many earlier property messages are awaiting a check in this browser.'
      );
    }
    const pending: PendingSharePrepareOperation = { ...operation, savedAtEpochMs: now };
    sessionStorage.setItem(key(session, operation.leadId), JSON.stringify(pending));
    if (!isOperationsSessionActive(session)) {
      removeSafely(key(session, operation.leadId));
      throw new Error('Your session changed before the property message was prepared.');
    }
    return pending;
  } catch (cause) {
    if (cause instanceof Error && cause.message.includes('awaiting a check')) throw cause;
    if (cause instanceof Error && cause.message.includes('session changed')) throw cause;
    throw new Error(
      'This browser could not keep the property message request. Nothing was sent.'
    );
  }
};

export const forgetPendingSharePreparation = (
  session: OperationsSession,
  leadId: number,
  operationId: string
): void => {
  if (!isOperationsSessionActive(session)) return;
  try {
    const existing = readPendingSharePreparation(session, leadId);
    if (existing?.operationId === operationId) {
      sessionStorage.removeItem(key(session, leadId));
    }
  } catch {
    // A malformed entry carries no usable operation identity and remains fail-closed.
  }
};
