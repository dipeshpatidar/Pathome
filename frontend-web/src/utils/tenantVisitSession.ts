export interface TenantVisitSession {
  key: string;
  userId: number;
}

/** A visit response belongs to the current authenticated browser session only. */
export function readTenantVisitSession(expectedUserId: number): TenantVisitSession | null {
  try {
    const token = localStorage.getItem('pathome_auth_token');
    const storedUser = localStorage.getItem('pathome_user');
    if (!token || !storedUser) return null;
    const userId = JSON.parse(storedUser)?.id;
    if (!Number.isSafeInteger(userId) || userId <= 0 || userId !== expectedUserId) return null;
    return { key: `${userId}:${token}`, userId };
  } catch {
    return null;
  }
}

export function isCurrentTenantVisitSession(session: TenantVisitSession): boolean {
  return readTenantVisitSession(session.userId)?.key === session.key;
}

export function belongsToTenantVisitSession(session: TenantVisitSession, responseUserId: number): boolean {
  return isCurrentTenantVisitSession(session) && responseUserId === session.userId;
}
