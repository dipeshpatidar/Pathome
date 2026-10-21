import { ApiRequestError } from '../services/apiError.ts';
import type { OperationsSession } from './operationsSession.ts';
import {
  OperationsSessionChangedError,
  captureOperationsSession,
  currentOperationsSessionGeneration,
  invalidateOperationsSession as invalidateSharedSession,
  isOperationsSessionActive,
  isOperationsSessionChangedError,
  sameOperationsCredential,
  subscribeOperationsSession
} from './operationsSession.ts';

export type UploaderSession = OperationsSession;
export { isOperationsSessionChangedError as isUploaderSessionChangedError };

/**
 * Uploader-local lifetime shared with workers that outlive a React callback. The signal is retired
 * by the owning uploader operation; the predicate additionally covers hook mount and epoch state.
 */
export interface UploaderOperationLifetime {
  readonly signal: AbortSignal;
  readonly isActive: () => boolean;
}

export class UploaderOperationRetiredError extends Error {
  constructor() {
    super('The uploader operation was retired.');
    this.name = 'UploaderOperationRetiredError';
  }
}

export const isUploaderOperationRetiredError = (cause: unknown): boolean =>
  cause instanceof UploaderOperationRetiredError;

export const captureUploaderSession = (): UploaderSession | null => captureOperationsSession();

export const isUploaderSessionActive = (session: UploaderSession | null | undefined): boolean =>
  isOperationsSessionActive(session);

/**
 * Distinguishes a denial for the credential still stored in this browser from a late denial for a
 * credential that has already been replaced. A current 401 invalidates the shared generation before
 * the caller receives the error, so active-only checks cannot perform the required local withdrawal.
 */
export const matchesUploaderStoredCredential = (
  session: UploaderSession | null | undefined
): boolean => {
  if (!session) return false;
  const stored = captureUploaderSession();
  return Boolean(
    stored
    && stored.userId === session.userId
    && stored.role === session.role
    && stored.token === session.token
  );
};

/**
 * An operation that already captured a session must continue with that exact account, token and
 * generation. A fresh operation fails closed when no authenticated identity can be established.
 */
export const resolveUploaderSession = (
  captured?: UploaderSession | null
): UploaderSession => {
  const session = captured ?? captureUploaderSession();
  if (!session) {
    throw new ApiRequestError('Your session has ended. Please sign in again to continue.', 401);
  }
  if (!isUploaderSessionActive(session)) {
    throw new OperationsSessionChangedError();
  }
  return session;
};

export const assertUploaderSessionActive = (session: UploaderSession | null | undefined): void => {
  if (!session || !isUploaderSessionActive(session)) {
    throw new OperationsSessionChangedError();
  }
};

export const subscribeUploaderSession = subscribeOperationsSession;

export const invalidateUploaderSession = invalidateSharedSession;

export {
  currentOperationsSessionGeneration,
  sameOperationsCredential
};
