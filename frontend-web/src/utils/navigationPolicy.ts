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
