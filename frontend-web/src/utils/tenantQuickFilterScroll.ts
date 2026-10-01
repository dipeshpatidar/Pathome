export type TenantDiscoveryState = 'LOADING' | 'READY' | 'ERROR';

export interface PendingTenantHeroFilterScroll {
  searchKey: string;
  fromLocationKey: string;
}

export type TenantHeroFilterScrollResolution = 'idle' | 'waiting' | 'discard' | 'scroll';

export const queueTenantHeroFilterScroll = (
  _current: PendingTenantHeroFilterScroll | null,
  searchKey: string,
  fromLocationKey: string
): PendingTenantHeroFilterScroll => ({ searchKey, fromLocationKey });

export const resolveTenantHeroFilterScroll = (
  pending: PendingTenantHeroFilterScroll | null,
  activeSearchKey: string,
  loadedSearchKey: string | null,
  discoveryState: TenantDiscoveryState,
  currentLocationKey: string
): TenantHeroFilterScrollResolution => {
  if (!pending) return 'idle';
  if (currentLocationKey !== pending.fromLocationKey && activeSearchKey !== pending.searchKey) return 'discard';
  if (activeSearchKey !== pending.searchKey || loadedSearchKey !== pending.searchKey || discoveryState === 'LOADING') {
    return 'waiting';
  }
  return 'scroll';
};
