export interface PropertyCurrentChanged {
  listingId: number;
  reason: 'PUBLISHED' | 'PAUSED' | 'ARCHIVED';
}

const EVENT = 'pathome_property_current_changed';
const STORAGE_KEY = 'pathome_property_current_changed_at';
const CHANNEL = 'pathome-property-current';
const SOURCE = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;

const valid = (value: unknown): value is PropertyCurrentChanged => {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Partial<PropertyCurrentChanged>;
  return typeof candidate.listingId === 'number'
    && Number.isSafeInteger(candidate.listingId)
    && candidate.listingId > 0
    && (candidate.reason === 'PUBLISHED' || candidate.reason === 'PAUSED'
      || candidate.reason === 'ARCHIVED');
};

/** Publishes one current-property change to this tab and other same-origin tabs. */
export const emitPropertyCurrentChanged = (detail: PropertyCurrentChanged): void => {
  if (!valid(detail)) return;
  const envelope = { ...detail, sourceId: SOURCE };
  window.dispatchEvent(new CustomEvent(EVENT, { detail: envelope }));
  try {
    if (typeof BroadcastChannel === 'function') {
      const channel = new BroadcastChannel(CHANNEL);
      channel.postMessage(envelope);
      channel.close();
    }
    // The timestamp is an invalidation hint only; it never carries customer data or authority.
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ ...envelope, changedAt: Date.now() }));
  } catch {
    // Same-tab listeners already received the event. Cross-tab delivery is best effort.
  }
};

export const subscribePropertyCurrentChanged = (
  listener: (detail: PropertyCurrentChanged) => void
): (() => void) => {
  const onEvent = (event: Event) => {
    const detail = (event as CustomEvent<unknown>).detail;
    if (valid(detail)) listener(detail);
  };
  let channel: BroadcastChannel | null = null;
  try {
    if (typeof BroadcastChannel === 'function') {
      channel = new BroadcastChannel(CHANNEL);
      channel.onmessage = (event) => {
        if (valid(event.data) && (event.data as { sourceId?: string }).sourceId !== SOURCE) {
          listener(event.data);
        }
      };
    }
  } catch {
    channel = null;
  }
  const onStorage = (event: StorageEvent) => {
    if (event.key !== STORAGE_KEY || !event.newValue) return;
    try {
      const parsed = JSON.parse(event.newValue) as unknown;
      if (valid(parsed) && (parsed as { sourceId?: string }).sourceId !== SOURCE) listener(parsed);
    } catch {
      // Ignore a malformed or unrelated storage update.
    }
  };
  window.addEventListener(EVENT, onEvent);
  window.addEventListener('storage', onStorage);
  return () => {
    window.removeEventListener(EVENT, onEvent);
    window.removeEventListener('storage', onStorage);
    channel?.close();
  };
};

