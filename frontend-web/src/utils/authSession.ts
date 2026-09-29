import type { UserProfile, UserRole } from '../types';

export function readPersistedUser(): UserProfile | null {
  try {
    const token = localStorage.getItem('pathome_auth_token');
    const role = localStorage.getItem('pathome_role') as UserRole | null;
    const storedUser = localStorage.getItem('pathome_user');
    if (!token && !role && !storedUser) return null;
    if (!token || !role || !storedUser) {
      clearPersistedUser();
      return null;
    }
    const user = JSON.parse(storedUser) as UserProfile;
    if (Number.isSafeInteger(user?.id) && user.id > 0 &&
        user.role === role && role !== 'GUEST') return user;
    clearPersistedUser();
    return null;
  } catch {
    try { clearPersistedUser(); } catch { /* unavailable storage cannot establish a session */ }
    return null;
  }
}

export function clearPersistedUser(): void {
  let userId: number | null = null;
  try {
    const parsed = JSON.parse(localStorage.getItem('pathome_user') || 'null');
    if (Number.isSafeInteger(parsed?.id) && parsed.id > 0) userId = parsed.id;
  } catch { /* A malformed identity cannot identify an owned buffer. */ }
  // Removing the token first invalidates pending account-scoped responses.
  localStorage.removeItem('pathome_auth_token');
  if (userId !== null) {
    const prefix = `pathome_lessor_unsynced_${userId}_`;
    for (let index = localStorage.length - 1; index >= 0; index -= 1) {
      const key = localStorage.key(index);
      if (key?.startsWith(prefix)) localStorage.removeItem(key);
    }
  }
  localStorage.removeItem('pathome_user');
  localStorage.removeItem('pathome_role');
  localStorage.removeItem('pathome_active_admin_tab');
}
