export class ApiRequestError extends Error {
  constructor(
    message: string,
    public readonly status?: number,
    public readonly details?: string
  ) {
    super(message);
    this.name = 'ApiRequestError';
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

export const createApiRequestError = async (response: Response, fallback: string): Promise<ApiRequestError> => {
  let payload: ApiErrorPayload | null = null;

  try {
    payload = await response.json() as ApiErrorPayload;
  } catch {
    // Some security and proxy responses do not contain a JSON body.
  }

  const message = messageForStatus(response.status, payload?.message || fallback);
  const details = payload?.message && payload.message !== message ? payload.message : undefined;
  return new ApiRequestError(message, response.status, details);
};

export const getErrorMessage = (error: unknown, fallback: string): string =>
  error instanceof Error && error.message.trim() ? error.message : fallback;

export const getErrorDetails = (error: unknown): string | undefined =>
  error instanceof ApiRequestError ? error.details : undefined;
