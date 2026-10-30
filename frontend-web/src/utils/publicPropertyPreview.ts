import type { Property } from '../types';

export const PUBLIC_PREVIEW_TIMEOUT_MS = 10_000;

export class PublicPreviewTimeoutError extends Error {
  constructor() {
    super('The public property preview timed out.');
    this.name = 'PublicPreviewTimeoutError';
  }
}

type FetchPublicProperty = (propertyId: number, signal: AbortSignal) => Promise<Property>;

interface PreviewTimer {
  schedule: (callback: () => void, milliseconds: number) => ReturnType<typeof setTimeout>;
  cancel: (timer: ReturnType<typeof setTimeout>) => void;
}

interface PreviewLoadOptions {
  timeoutMs?: number;
  externalSignal?: AbortSignal;
  timers?: PreviewTimer;
}

const defaultTimers: PreviewTimer = {
  schedule: (callback, milliseconds) => setTimeout(callback, milliseconds),
  cancel: (timer) => clearTimeout(timer)
};

const abortError = (): Error => {
  if (typeof DOMException !== 'undefined') {
    return new DOMException('The public property preview was cancelled.', 'AbortError');
  }
  const error = new Error('The public property preview was cancelled.');
  error.name = 'AbortError';
  return error;
};

export const formatPublicRent = (value: unknown): string | null => {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) return null;
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    minimumFractionDigits: 0,
    maximumFractionDigits: 2
  }).format(value);
};

export const formatPublicArea = (value: unknown): string | null => {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) return null;
  return `${new Intl.NumberFormat('en-IN', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 2
  }).format(value)} sq ft`;
};

/**
 * Loads one preview attempt. A timeout aborts the transport but rejects the returned promise
 * explicitly, so an abort-ignoring transport cannot leave the card loading indefinitely.
 */
export const loadPublicPropertyPreview = (
  propertyId: number,
  fetchProperty: FetchPublicProperty,
  options: PreviewLoadOptions = {}
): Promise<Property> => {
  const timeoutMs = options.timeoutMs ?? PUBLIC_PREVIEW_TIMEOUT_MS;
  const timers = options.timers ?? defaultTimers;
  const externalSignal = options.externalSignal;
  const attemptController = new AbortController();
  let settled = false;
  let timer: ReturnType<typeof setTimeout> | null = null;

  return new Promise<Property>((resolve, reject) => {
    const cleanup = () => {
      if (timer !== null) timers.cancel(timer);
      externalSignal?.removeEventListener('abort', onExternalAbort);
    };
    const finish = (result: Property | Error) => {
      if (settled) return;
      settled = true;
      cleanup();
      if (result instanceof Error) reject(result);
      else resolve(result);
    };
    const onExternalAbort = () => {
      if (settled) return;
      settled = true;
      cleanup();
      attemptController.abort();
      reject(abortError());
    };

    if (propertyId <= 0) {
      finish(new Error('A positive public property identifier is required.'));
      return;
    }
    if (externalSignal?.aborted) {
      onExternalAbort();
      return;
    }

    externalSignal?.addEventListener('abort', onExternalAbort, { once: true });
    timer = timers.schedule(() => {
      if (settled) return;
      settled = true;
      cleanup();
      attemptController.abort();
      reject(new PublicPreviewTimeoutError());
    }, timeoutMs);

    Promise.resolve()
      .then(() => fetchProperty(propertyId, attemptController.signal))
      .then(
        (result) => {
          if (settled) return;
          if (!result || result.id !== propertyId) {
            finish(new Error('Public property identity mismatch'));
            return;
          }
          finish(result);
        },
        (error) => finish(error instanceof Error ? error : new Error('Unable to load public property'))
      );
  });
};
