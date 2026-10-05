export type TenantFavoritesSnapshot = {
  identityKey: string | null;
  status: 'loading' | 'ready' | 'error';
  propertyIds: ReadonlySet<number>;
};

export type SavedPropertiesPage = {
  properties: Array<{ id: number }>;
  hasMore: boolean;
};

export type TenantFavoriteChangedDetail = {
  identityKey: string;
  propertyId: number;
  saved: boolean;
};

export const TENANT_FAVORITE_CHANGED_EVENT = 'pathome_tenant_favorite_changed';
export const FAVORITE_COUNT_PAGE_SIZE = 24;

export const emptyTenantFavoritesSnapshot: TenantFavoritesSnapshot = {
  identityKey: null,
  status: 'loading',
  propertyIds: new Set()
};

export async function loadAllSavedPropertyIds(
  fetchPage: (page: number, signal: AbortSignal) => Promise<SavedPropertiesPage>,
  signal: AbortSignal
): Promise<Set<number>> {
  const propertyIds = new Set<number>();
  let page = 0;
  while (true) {
    if (signal.aborted) throw signal.reason ?? new DOMException('Request aborted', 'AbortError');
    const result = await fetchPage(page, signal);
    if (signal.aborted) throw signal.reason ?? new DOMException('Request aborted', 'AbortError');
    for (const property of result.properties) {
      if (Number.isSafeInteger(property.id) && property.id > 0) propertyIds.add(property.id);
    }
    if (!result.hasMore) return propertyIds;
    page += 1;
  }
}

export function applyTenantFavoriteChanged(
  snapshot: TenantFavoritesSnapshot,
  identityKey: string,
  propertyId: number,
  saved: boolean
): TenantFavoritesSnapshot {
  if (snapshot.identityKey !== identityKey || snapshot.status !== 'ready'
    || !Number.isSafeInteger(propertyId) || propertyId <= 0) return snapshot;
  const propertyIds = new Set(snapshot.propertyIds);
  if (saved) propertyIds.add(propertyId);
  else propertyIds.delete(propertyId);
  return { ...snapshot, propertyIds };
}

export function applyFavoriteChanges(
  propertyIds: Set<number>,
  changes: ReadonlyMap<number, boolean>
): Set<number> {
  const next = new Set(propertyIds);
  for (const [propertyId, saved] of changes) {
    if (!Number.isSafeInteger(propertyId) || propertyId <= 0) continue;
    if (saved) next.add(propertyId);
    else next.delete(propertyId);
  }
  return next;
}

export function notifyTenantFavoriteChanged(detail: TenantFavoriteChangedDetail): void {
  window.dispatchEvent(new CustomEvent<TenantFavoriteChangedDetail>(TENANT_FAVORITE_CHANGED_EVENT, { detail }));
}
