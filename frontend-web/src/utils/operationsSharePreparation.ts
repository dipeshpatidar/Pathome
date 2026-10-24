import { ApiRequestError } from '../services/apiError';

const NO_WRITE_CONFIGURATION_REJECTIONS = new Set([
  'A canonical public property origin is not configured',
  'The configured public property origin is invalid'
]);

export type SharePrepareFailure =
  | { kind: 'conflict' }
  | { kind: 'definitive-rejection'; message: string }
  | { kind: 'uncertain'; message: string }
  | { kind: 'rejected'; message: string };

/**
 * A preparation 503 is a proven no-write rejection only when the trusted application supplies one of
 * its two existing public-link configuration errors. A bodyless proxy 503, timeout, lost response or
 * malformed successful reply remains uncertain because persistence may still be in flight.
 */
export const classifySharePrepareFailure = (cause: unknown): SharePrepareFailure => {
  const status = cause instanceof ApiRequestError ? cause.status : undefined;
  if (status === 409) return { kind: 'conflict' };
  if (status === 503
      && cause instanceof ApiRequestError
      && cause.details
      && NO_WRITE_CONFIGURATION_REJECTIONS.has(cause.details)) {
    return {
      kind: 'definitive-rejection',
      message: 'The customer property links are not available yet, so this message was not saved. '
        + 'No message was sent. Try again after the property-link setup is fixed.'
    };
  }
  if (status === undefined || status >= 500 || (status >= 200 && status < 300)) {
    return {
      kind: 'uncertain',
      message: 'We couldn’t confirm that the property message is ready. '
        + 'No message has been sent to the customer.'
    };
  }
  const message = cause instanceof ApiRequestError
    ? cause.details || cause.message
    : cause instanceof Error && cause.message
      ? cause.message
      : 'The property message could not be prepared. No message has been sent to the customer.';
  return {
    kind: 'rejected',
    message
  };
};
