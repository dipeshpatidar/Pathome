import type { UserRole } from '../types';

/**
 * Strict parsing of the login/registration response at the authentication boundary.
 *
 * The User id is the authoritative identity every ownership, draft, favourite, visit and Operations
 * scope is keyed by, so a malformed response must fail closed rather than be coerced into a number.
 * JavaScript numeric coercion is exactly the hole this module closes: `Number(true)` is `1`,
 * `Number([1])` is `1`, `Number('')` is `0` and `Number(' 1e3 ')` is `1000`, so any conversion that
 * runs before the type is proven can sign a visitor in as whichever real account owns that number.
 *
 * The supported server contract (see `AuthResponse`) issues `userId` as a JSON number. A string id is
 * accepted only when its entire text is a canonical positive integer - no sign, no leading zero, no
 * decimal point, no exponent, no whitespace, no separators - and only when the converted value is a
 * positive safe integer. Everything else - booleans, arrays, objects, null, missing values, empty or
 * whitespace-only strings, zero, negatives, decimals, exponent forms and unsafe integers - is
 * rejected without any conversion and without persisting anything.
 *
 * Nothing here invents an identity or a session: a rejected response leaves the client
 * unauthenticated, so no protected read can start from it.
 */

/** User-facing copy, unchanged from the previous inline handling. */
export const AUTH_SESSION_MISSING_MESSAGE =
  'Sign-in succeeded but did not return a secure session. Please try again.';
export const AUTH_IDENTITY_INVALID_MESSAGE =
  'Sign-in could not be verified because the response was incomplete. Please try again.';

/** The single storage key that establishes an authenticated session. */
export const AUTH_TOKEN_STORAGE_KEY = 'pathome_auth_token';

/** Why an authentication response could not establish a session. */
export type AuthenticationRejection = 'missing-session' | 'malformed-identity';

export const authenticationRejectionMessage = (reason: AuthenticationRejection): string =>
  reason === 'missing-session' ? AUTH_SESSION_MISSING_MESSAGE : AUTH_IDENTITY_INVALID_MESSAGE;

/**
 * Canonical positive decimal integer: the only textual form a server-issued id may take. Anchored and
 * complete, so leading zeros, signs, decimals, exponents and embedded whitespace can never match.
 */
const POSITIVE_INTEGER_TEXT = /^[1-9][0-9]*$/;

/**
 * The authenticated User id, or null when the value cannot prove one.
 *
 * The permitted JSON type is checked *before* any conversion, and the accepted numeric range is the
 * positive safe-integer range, so no boolean, array, object, null, missing value, empty or
 * whitespace-only string, zero, negative, decimal, exponent form or unsafe integer can pass.
 */
export function readAuthenticatedUserId(raw: unknown): number | null {
  if (typeof raw === 'number') {
    return Number.isSafeInteger(raw) && raw > 0 ? raw : null;
  }
  // A text id is accepted only when its complete text is a canonical positive integer; the
  // conversion happens only after that shape is proven.
  if (typeof raw === 'string') {
    if (!POSITIVE_INTEGER_TEXT.test(raw)) return null;
    const converted = Number(raw);
    return Number.isSafeInteger(converted) && converted > 0 ? converted : null;
  }
  return null;
}

/** The validated fields of one authentication response. */
export interface AuthenticatedIdentity {
  token: string;
  userId: number;
  email: string;
  fullName: string;
  role: UserRole | string;
  hasLessorProfile: boolean;
  employeeRoleType?: string;
}

export type AuthenticationOutcome =
  | ({ ok: true } & AuthenticatedIdentity)
  | { ok: false; reason: AuthenticationRejection };

/**
 * Validates an authentication response and, only when every required field is valid, persists the
 * session token. A rejected response writes nothing at all, so the client stays unauthenticated and
 * no account-scoped request can be authorised from it.
 */
export function establishAuthenticatedSession(
  raw: unknown,
  fallback: { email: string; fullName: string }
): AuthenticationOutcome {
  // An array passes `typeof === 'object'`; it is not a response envelope and is never accepted.
  if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) {
    return { ok: false, reason: 'missing-session' };
  }
  const response = raw as Record<string, unknown>;

  const token = response.token;
  if (typeof token !== 'string' || token.length === 0) {
    return { ok: false, reason: 'missing-session' };
  }

  const userId = readAuthenticatedUserId(response.userId);
  if (userId === null) {
    return { ok: false, reason: 'malformed-identity' };
  }

  // A session is only usable while its role is stated: without it the persisted identity could never
  // be restored - `readPersistedUser` requires the role - and the token would outlive a usable account.
  const role = response.role;
  if (typeof role !== 'string' || role.trim().length === 0) {
    return { ok: false, reason: 'malformed-identity' };
  }

  const email = typeof response.email === 'string' && response.email.length > 0
    ? response.email
    : fallback.email;
  const fullName = typeof response.fullName === 'string' && response.fullName.length > 0
    ? response.fullName
    : fallback.fullName || 'User';

  try {
    localStorage.setItem(AUTH_TOKEN_STORAGE_KEY, token);
  } catch {
    // Storage that cannot hold the session cannot establish one.
    return { ok: false, reason: 'missing-session' };
  }

  return {
    ok: true,
    token,
    userId,
    email,
    fullName,
    role,
    hasLessorProfile: response.hasLessorProfile === true,
    employeeRoleType: typeof response.employeeRoleType === 'string' ? response.employeeRoleType : undefined
  };
}
