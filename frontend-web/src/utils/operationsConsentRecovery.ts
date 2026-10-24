import { isOperationsSessionActive, type OperationsSession } from './operationsSession';

export interface PendingConsentOperation {
  operationId: string;
  leadVersion: number;
  preferenceRevision: number;
}

const PREFIX = 'pathome_oe_consent_v1:';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
let bound = false;

const key = (session: OperationsSession, leadId: number) =>
  `${PREFIX}${session.identity}:${leadId}`;

const bind = (): void => {
  if (bound || typeof window === 'undefined') return;
  bound = true;
  window.addEventListener('pathome_auth_changed', clearPendingConsentOperations);
  window.addEventListener('pathome_session_expired', clearPendingConsentOperations);
  window.addEventListener('storage', (event) => {
    if (event.key === null
      || ['pathome_auth_token', 'pathome_user', 'pathome_role'].includes(event.key)) {
      clearPendingConsentOperations();
    }
  });
};

/** Clears only references to unconfirmed commands. No consent or destination content is stored. */
export const clearPendingConsentOperations = (): void => {
  try {
    for (let index = sessionStorage.length - 1; index >= 0; index -= 1) {
      const entry = sessionStorage.key(index);
      if (entry?.startsWith(PREFIX)) sessionStorage.removeItem(entry);
    }
  } catch {
    // Storage refusal leaves no protected fallback and therefore no recoverable command.
  }
};

export const readPendingConsentOperation = (
  session: OperationsSession,
  leadId: number
): PendingConsentOperation | null => {
  bind();
  if (!isOperationsSessionActive(session)) return null;
  try {
    const raw = sessionStorage.getItem(key(session, leadId));
    if (!raw) return null;
    const value = JSON.parse(raw) as Partial<PendingConsentOperation>;
    if (!UUID.test(value.operationId ?? '')
      || !Number.isSafeInteger(value.leadVersion)
      || !Number.isSafeInteger(value.preferenceRevision)
      || Number(value.leadVersion) <= 0
      || Number(value.preferenceRevision) < 0) {
      throw new Error('The pending consent check could not be read.');
    }
    return value as PendingConsentOperation;
  } catch (cause) {
    if (cause instanceof Error && cause.message === 'The pending consent check could not be read.') {
      throw cause;
    }
    throw new Error('The pending consent check could not be read.');
  }
};

export const preservePendingConsentOperation = (
  session: OperationsSession,
  leadId: number,
  operation: PendingConsentOperation
): PendingConsentOperation => {
  bind();
  if (!isOperationsSessionActive(session)) throw new Error('The signed-in session changed.');
  if (!UUID.test(operation.operationId)) throw new Error('The consent operation ID is invalid.');
  const existing = readPendingConsentOperation(session, leadId);
  if (existing) {
    if (existing.operationId !== operation.operationId) {
      throw new Error('A previous consent command is still being checked.');
    }
    return existing;
  }
  sessionStorage.setItem(key(session, leadId), JSON.stringify(operation));
  return operation;
};

export const forgetPendingConsentOperation = (
  session: OperationsSession,
  leadId: number,
  operationId: string
): void => {
  if (!isOperationsSessionActive(session)) return;
  try {
    if (readPendingConsentOperation(session, leadId)?.operationId === operationId) {
      sessionStorage.removeItem(key(session, leadId));
    }
  } catch {
    // A malformed or inaccessible entry is cleared by the session lifecycle boundary.
  }
};

export const consentReceiptRevision = (resultReference: string | null): number | null => {
  if (!resultReference) return null;
  const match = /^preference:\d{1,18}:revision:(\d{1,18}):leadVersion:\d{1,18}$/.exec(
    resultReference
  );
  if (!match) return null;
  const revision = Number(match[1]);
  return Number.isSafeInteger(revision) && revision > 0 ? revision : null;
};
