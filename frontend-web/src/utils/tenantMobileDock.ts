import { quickRefineActiveCount } from './tenantQuickRefine.ts';
import type { RentalSearchFilters } from './rentalSearch.ts';

export interface TenantMobileDockBadges {
  filterCount: number | null;
  visitCount: number | null;
  savedCount: number | null;
}

export function shouldRenderTenantMobileDock(
  heroVisible: boolean,
  quickRefineOpen: boolean,
  quickViewOpen: boolean
): boolean {
  return !heroVisible && !quickRefineOpen && !quickViewOpen;
}

export function tenantMobileDockBadges(
  filters: RentalSearchFilters,
  authoritativeActiveVisitCount?: number | null,
  authoritativeSavedCount?: number | null
): TenantMobileDockBadges {
  const filtersCount = quickRefineActiveCount(filters);
  const visitsCount = Number.isSafeInteger(authoritativeActiveVisitCount) && (authoritativeActiveVisitCount ?? 0) > 0
    ? authoritativeActiveVisitCount as number
    : null;
  const savedCount = Number.isSafeInteger(authoritativeSavedCount) && (authoritativeSavedCount ?? 0) > 0
    ? authoritativeSavedCount as number
    : null;
  return {
    filterCount: filtersCount > 0 ? filtersCount : null,
    visitCount: visitsCount,
    savedCount: savedCount
  };
}

export type TenantMobileDockItem = 'home' | 'filters' | 'visits' | 'saved';

export function tenantMobileDockTarget(item: TenantMobileDockItem): string | null {
  switch (item) {
    case 'home': return 'tenant-home-search';
    case 'filters': return null;
    case 'visits': return 'tenant-visits-page-title';
    case 'saved': return 'saved-homes-title';
  }
}
