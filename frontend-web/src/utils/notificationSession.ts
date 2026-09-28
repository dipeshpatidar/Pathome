export const readNotificationIdentity = (): string | null => {
  try {
    const token = localStorage.getItem('pathome_auth_token');
    const user = localStorage.getItem('pathome_user');
    if (!token || !user) return null;
    const userId = JSON.parse(user)?.id;
    return userId == null ? null : `${userId}:${token}`;
  } catch {
    return null;
  }
};

export const visibleNotificationHistory = <T,>(
  history: T[], loadedIdentity: string | null, currentIdentity: string | null
): T[] => loadedIdentity !== null && loadedIdentity === (currentIdentity ?? 'guest') ? history : [];

export const isCurrentNotificationRequest = (
  requestIdentity: string, requestGeneration: number,
  currentIdentity: string | null, currentGeneration: number
): boolean => requestIdentity === currentIdentity && requestGeneration === currentGeneration;
