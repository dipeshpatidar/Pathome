export class ApiRequestError extends Error {
  public readonly status?: number;
  public readonly details?: string;
  constructor(
    message: string,
    status?: number,
    details?: string
  ) {
    super(message);
    this.name = 'ApiRequestError';
    this.status = status;
    this.details = details;
  }
}

interface ApiErrorPayload {
  error?: string;
  message?: string;
}

const messageForStatus = (status: number, fallback: string): string => {
  if (status === 401) return 'Your session has ended. Please sign in again to continue.';
  if (status === 403) return 'You do not have permission to complete this action.';
  if (status === 404) return 'The requested property could not be found.';
  if (status === 413) return 'The selected file is too large. Choose a smaller file and try again.';
  if (status >= 500) return 'We could not complete this request right now. Please try again shortly.';
  return fallback;
};

export const notifySessionExpired = (): void => {
  if (typeof window !== 'undefined' && typeof window.dispatchEvent === 'function') {
    window.dispatchEvent(new CustomEvent('pathome_session_expired'));
  }
};

export const createApiRequestError = async (response: Response, fallback: string, guestAccess = false): Promise<ApiRequestError> => {
  let payload: ApiErrorPayload | null = null;

  try {
    payload = await response.json() as ApiErrorPayload;
  } catch {
    // Some security and proxy responses do not contain a JSON body.
  }

  if (response.status === 401 && !guestAccess) {
    notifySessionExpired();
  }

  const message = guestAccess && response.status === 401
    ? 'Guest access has expired. Start a new property listing.'
    : messageForStatus(response.status, payload?.message || fallback);
  const details = response.status < 500 && payload?.message && payload.message !== message
    ? payload.message
    : undefined;
  return new ApiRequestError(message, response.status, details);
};

export const getErrorMessage = (error: unknown, fallback: string): string =>
  error instanceof Error && error.message.trim() ? error.message : fallback;

export const getErrorDetails = (error: unknown): string | undefined =>
  error instanceof ApiRequestError ? error.details : undefined;
