/**
 * Session-continuity boundary for the Pathome Operations (OE) workspace.
 *
 * A logical OE operation (a claim, a release, a queue or detail read) always captures the
 * authenticated identity it started under: account id, role, bearer credential and the invalidation
 * generation in force at that moment. Every request of that operation re-uses the captured
 * credential and refuses to run once the session is no longer the signed-in one, so one account's
 * data can never be combined with another account's token after an `await`.
 *
 * "No longer the signed-in one" is decided by comparing the captured credential itself in memory
 * (account id, role and bearer token) together with the invalidation generation — never by comparing
 * a credential digest, which can collide and cannot see a silently rotated token.
 *
 * The workspace is embedded in a client that authenticates through `localStorage` plus three window
 * events (`pathome_auth_changed` on sign-in/sign-out, `pathome_session_expired` on a 401, and the
 * cross-tab `storage` event), so invalidation hooks into those existing signals instead of adding a
 * second source of truth for authentication.
 *
 * The credential itself is never rendered, logged or written back to storage. The comparison
 * identity uses a non-reversible fingerprint so even the identity held in UI state (for example on
 * a notice) cannot leak a token. That fingerprint is a label for display scoping only: whether an
 * operation may still act is decided by comparing the captured credential itself, in memory.
 */

export interface OperationsSession {
  /** Bearer credential captured for this operation. Used for the Authorization header only. */
  token: string;
  userId: number;
  role: string;
  /** Account + role + credential fingerprint. A non-secret label for UI scoping and dedupe only. */
  identity: string;
  /** Invalidation generation this session was captured under. */
  generation: number;
}

/**
 * Thrown instead of letting a stale-identity response reach the UI. Callers treat it as
 * "another identity owns the workspace now": nothing is committed and nothing is announced.
 */
export class OperationsSessionChangedError extends Error {
  constructor() {
    super('The signed-in session changed while this operation was in progress.');
    this.name = 'OperationsSessionChangedError';
  }
}

export const isOperationsSessionChangedError = (cause: unknown): boolean =>
  cause instanceof OperationsSessionChangedError;

const TOKEN_KEY = 'pathome_auth_token';
const USER_KEY = 'pathome_user';
const ROLE_KEY = 'pathome_role';
const INVALIDATING_EVENTS = ['pathome_auth_changed', 'pathome_session_expired'] as const;
const INVALIDATING_STORAGE_KEYS = new Set([TOKEN_KEY, USER_KEY, ROLE_KEY]);

let generation = 0;
let domBound = false;
const listeners = new Set<() => void>();

const readItem = (key: string): string | null => {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
};

/** Non-reversible 32-bit fingerprint (FNV-1a). Keeps the raw credential out of UI state. */
const fingerprint = (value: string): string => {
  let hash = 0x811c9dc5;
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash.toString(16).padStart(8, '0');
};

const bindDomEvents = (): void => {
  if (domBound || typeof window === 'undefined' || typeof window.addEventListener !== 'function') return;
  domBound = true;
  INVALIDATING_EVENTS.forEach((eventName) => {
    window.addEventListener(eventName, () => invalidateOperationsSession());
  });
  window.addEventListener('storage', (event: StorageEvent) => {
    // A cleared storage area means the session is gone; a session key change means it was replaced.
    if (event.key === null || INVALIDATING_STORAGE_KEYS.has(event.key)) invalidateOperationsSession();
  });
};

/** The signed-in browser session, or null when no account can be established. */
export const readOperationsSession = (): OperationsSession | null => {
  const token = readItem(TOKEN_KEY);
  if (!token) return null;
  let userId = 0;
  let role = '';
  try {
    const parsed = JSON.parse(readItem(USER_KEY) ?? 'null') as { id?: unknown; role?: unknown } | null;
    if (typeof parsed?.id === 'number' && Number.isSafeInteger(parsed.id) && parsed.id > 0) userId = parsed.id;
    if (typeof parsed?.role === 'string') role = parsed.role;
  } catch {
    // An unreadable identity cannot establish a session.
  }
  if (userId === 0) return null;
  if (!role) role = readItem(ROLE_KEY) ?? '';
  return { token, userId, role, identity: `${userId}|${role}|${fingerprint(token)}`, generation };
};

export const captureOperationsSession = (): OperationsSession | null => readOperationsSession();

export const currentOperationsSessionGeneration = (): number => generation;

/**
 * Exact, in-memory comparison of one captured session against another.
 *
 * This is the security decision, and it deliberately does *not* use `identity`: a 32-bit digest can
 * collide, and a credential replaced underneath an operation can produce an equal digest. Comparing
 * the captured credential, the account identity and the invalidation generation means a replaced or
 * rotated token deactivates old work even when no auth event ever fired.
 */
export const sameOperationsCredential = (
  a: OperationsSession | null | undefined,
  b: OperationsSession | null | undefined
): boolean =>
  Boolean(
    a
    && b
    && a.userId === b.userId
    && a.role === b.role
    && a.token === b.token
    && a.generation === b.generation
  );

/** True while the captured credential is still the exact signed-in one and has not been invalidated. */
export const isOperationsSessionActive = (session: OperationsSession | null | undefined): boolean => {
  if (!session || session.token.length === 0 || session.generation !== generation) return false;
  const current = readOperationsSession();
  return current !== null && sameOperationsCredential(current, session);
};

/**
 * Invalidate every captured session. Pending work is cancelled and its responses are dropped, so a
 * revoked or replaced session can never re-populate the workspace.
 */
export const invalidateOperationsSession = (): void => {
  generation += 1;
  listeners.forEach((listener) => {
    try {
      listener();
    } catch {
      // One faulty listener must not stop invalidation of the rest.
    }
  });
};

/**
 * Notifies the caller whenever the signed-in identity changes or the session is invalidated, so it
 * can abort pending requests, clear protected data and re-verify authority from scratch.
 */
export const subscribeOperationsSession = (listener: () => void): (() => void) => {
  bindDomEvents();
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};
