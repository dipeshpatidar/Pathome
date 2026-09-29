export type WorkspaceContext =
  | 'PUBLIC'
  | 'TENANT'
  | 'LESSOR'
  | 'ADMIN'
  | 'SUB_ADMIN';

export interface LogoDestination {
  path: string;
  label: string;
  isAvailable: boolean;
}

export type AppHeaderOwner = 'GLOBAL' | 'LESSOR_ONBOARDING';

export function normalizeRoutePathname(pathname: string): string {
  const path = pathname || '/';
  return path.length > 1 ? path.replace(/\/+$/, '') : path;
}

export function isLessorWorkspaceRoute(pathname: string): boolean {
  const path = normalizeRoutePathname(pathname);
  return path === '/lessor' || path.startsWith('/lessor/');
}

/** The app shell and onboarding screen use the same route classification, so only one owns a header. */
export function resolveAppHeaderOwner(pathname: string): AppHeaderOwner {
  const path = normalizeRoutePathname(pathname);
  return path === '/lessor/new' || /^\/lessor\/drafts\/[^/]+$/.test(path)
    ? 'LESSOR_ONBOARDING'
    : 'GLOBAL';
}

export interface LessorReturnContext {
  guest: boolean;
  tenant: boolean;
  activeLessor: boolean;
}

/** Preserves a known internal origin and applies safe capability-aware fallbacks to direct links. */
export function resolveLessorExitPath(origin: unknown, context: LessorReturnContext): string {
  const fallback = context.guest ? '/' : context.activeLessor ? '/lessor' : '/tenant';
  if (typeof origin !== 'string' || !origin.startsWith('/') || origin.startsWith('//') || origin.includes('\\')) {
    return fallback;
  }

  let url: URL;
  try {
    url = new URL(origin, 'https://pathome.invalid');
  } catch {
    return fallback;
  }
  if (url.origin !== 'https://pathome.invalid') return fallback;

  if (url.pathname === '/') return '/';
  if (url.pathname === '/tenant') {
    const allowed = new URLSearchParams();
    for (const key of ['city', 'q', 'sector', 'bhk', 'propertyType', 'furnishing', 'minRent', 'maxRent', 'rentalOnly']) {
      for (const value of url.searchParams.getAll(key)) allowed.append(key, value);
    }
    const query = allowed.toString();
    return query ? `/tenant?${query}` : '/tenant';
  }
  if (/^\/property\/\d+$/.test(url.pathname)) return url.pathname;
  if (url.pathname === '/lessor') {
    if (url.searchParams.get('view') === 'drafts') return '/lessor?view=drafts';
    if (context.activeLessor || context.guest) return '/lessor';
    return context.tenant ? '/lessor?view=drafts' : fallback;
  }
  return fallback;
}

/**
 * Determines the current workspace context from the URL pathname and current user role.
 * Navigation is based on current workspace context, not simply the user's highest role.
 *
 * Example:
 * A user with TENANT + LESSOR capability browsing rental homes (/) has context TENANT -> logo routes to /
 * When inside the lessor workspace (/lessor), context is LESSOR -> logo routes to /lessor
 */
export function resolveWorkspaceContext(pathname: string, role?: string | null): WorkspaceContext {
  const path = (pathname || '/').toLowerCase();

  if (path === '/lessor' || path.startsWith('/lessor/')) {
    return 'LESSOR';
  }
  if (path === '/admin' || path.startsWith('/admin/')) {
    if (role === 'SUB_ADMIN' || role === 'MANAGER') {
      return 'SUB_ADMIN';
    }
    return 'ADMIN';
  }

  // When on public or tenant rental-browsing routes (/, /tenant, /property/:id, etc.)
  if (role && role !== 'GUEST') {
    return 'TENANT';
  }
  return 'PUBLIC';
}

/**
 * Resolves the authoritative Pathome logo destination based on the current workspace context.
 *
 * Rules:
 * - PUBLIC / GUEST: / (main marketplace landing)
 * - TENANT / RENTAL-BROWSING: / (main marketplace landing)
 * - LESSOR WORKSPACE: /lessor (Lessor Home / My Properties)
 * - ADMIN WORKSPACE: /admin (Admin dashboard/home)
 * - SUB_ADMIN WORKSPACE: /admin (authorized Sub-admin/Admin workspace home)
 */
export function resolveLogoDestination(context: WorkspaceContext): LogoDestination {
  switch (context) {
    case 'LESSOR':
      return { path: '/lessor', label: 'Return to Lessor workspace', isAvailable: true };
    case 'ADMIN':
      return { path: '/admin', label: 'Return to Admin dashboard', isAvailable: true };
    case 'SUB_ADMIN':
      return { path: '/admin', label: 'Return to Admin workspace', isAvailable: true };
    case 'TENANT':
    case 'PUBLIC':
    default:
      return { path: '/', label: 'Return to Pathome home', isAvailable: true };
  }
}

/**
 * Authoritative capability check for displaying "My Properties" in navigation.
 * "My Properties" appears ONLY when the authenticated User has an actual
 * active lessor capability reported by the backend.
 *
 * Rules:
 * - GUEST -> false
 * - TENANT-ONLY USER -> false
 * - ACTIVE LESSOR USER -> true
 * - TENANT + LESSOR USER -> true
 * - Auth method independent: NEVER keys on email or password login method.
 */
export function shouldShowMyProperties(role?: string | null, hasLessorCapability?: boolean | null | 'error'): boolean {
  if (!role || role === 'GUEST') return false;
  return hasLessorCapability === true;
}

/**
 * Authoritative capability check for displaying "List your property" conversion action.
 * Tenant-only users may still see "List your property" as a conversion/onboarding action.
 */
export function shouldShowListYourProperty(role?: string | null, hasLessorCapability?: boolean | null | 'error'): boolean {
  if (!role || role === 'GUEST') return true;
  // An active lessor sees "My Properties" instead.
  return hasLessorCapability === false;
}

/**
 * Resolves the primary destination for lessor navigation actions.
 */
export function resolveLessorNavigationDestination(hasLessorCapability?: boolean): string {
  return hasLessorCapability ? '/lessor' : '/lessor/new';
}

/**
 * Resolves workspace state when user directly visits `/lessor`.
 * A tenant-only user visiting `/lessor` directly must see the "List your property"
 * self-service entry state ('inactive'), NOT another user's portfolio or a fake profile.
 */
export function resolveDirectLessorRouteState(
  isAuthenticated: boolean,
  hasLessorCapability?: boolean
): 'guest' | 'inactive' | 'active' {
  if (!isAuthenticated) return 'guest';
  if (hasLessorCapability) return 'active';
  return 'inactive';
}
