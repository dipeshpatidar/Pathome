const UUID_V4 =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

/**
 * Creates the exact UUID attached to one logical mutation.
 *
 * A timestamp-derived fallback can collide across tabs and cannot safely identify an interrupted
 * write. When the browser has no cryptographically strong UUID source, callers must stop before
 * dispatch and explain that the action was not started.
 */
export const createOperationId = (source: Crypto | undefined = globalThis.crypto): string => {
  try {
    if (!source) throw new Error('Browser security services are unavailable.');
    if (typeof source.randomUUID === 'function') {
      const value = source.randomUUID();
      if (UUID_V4.test(value)) return value;
    }
    const bytes = source.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('');
    const value =
      `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-`
      + `${hex.slice(16, 20)}-${hex.slice(20)}`;
    if (UUID_V4.test(value)) return value;
  } catch {
    // The caller receives one actionable refusal and does not dispatch.
  }
  throw new Error('A safe request reference could not be created. Nothing was sent.');
};

export const isOperationId = (value: unknown): value is string =>
  typeof value === 'string' && UUID_V4.test(value);
