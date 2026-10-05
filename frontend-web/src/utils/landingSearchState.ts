import { PROPERTY_TYPE_LABELS, resetFiltersForSearchClear, type RentalSearchFilters } from './rentalSearch.ts';

export type LandingCriterionKey = 'q' | 'sector' | 'bhk' | 'propertyType' | 'furnishing' | 'budget';
export type LandingCriterion = { key: LandingCriterionKey; label: string };
export type LandingState = { search: RentalSearchFilters; explicit: RentalSearchFilters; searchLabel: string };
const empty = (city: string): RentalSearchFilters => ({ city, rentalOnly: true });
const allowed = ['sector', 'q', 'bhk', 'propertyType', 'furnishing', 'minRent', 'maxRent'] as const;
const parsePart = (raw: string | null, city: string): RentalSearchFilters | null => {
  if (!raw) return null;
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
    const record = parsed as Record<string, unknown>;
    const result = empty(city);
    for (const key of allowed) {
      const value = record[key];
      if (typeof value === 'string' && !['minRent', 'maxRent'].includes(key) && value.length <= 200) (result as unknown as Record<string, unknown>)[key] = value;
      if (typeof value === 'number' && ['minRent', 'maxRent'].includes(key) && Number.isSafeInteger(value) && value > 0 && value <= 10_000_000) (result as unknown as Record<string, unknown>)[key] = value;
    }
    return result;
  } catch { return null; }
};
export const landingStateFromUrl = (params: URLSearchParams, effective: RentalSearchFilters, city: string): LandingState => {
  const search = parsePart(params.get('lpSearch'), city);
  const explicit = parsePart(params.get('lpFilters'), city);
  if (search && explicit) {
    delete explicit.q; // Query text has no Filter-panel control.
    return { search, explicit, searchLabel: (params.get('lpSearchLabel') || '').slice(0, 200) };
  }
  // Older links have no provenance. Preserve their query as Search, never silently claim an explicit Filter selection.
  return { search: { ...effective, city, rentalOnly: true }, explicit: empty(city), searchLabel: effective.q || effective.sector || '' };
};
export const landingEffectiveFilters = (state: LandingState, city: string): RentalSearchFilters => ({
  ...state.search, ...Object.fromEntries(Object.entries(state.explicit).filter(([key, value]) => key !== 'city' && key !== 'rentalOnly' && value !== undefined)), city, rentalOnly: true
});
export const landingAppliedCriteria = (filters: RentalSearchFilters): LandingCriterion[] => {
  const result: LandingCriterion[] = [];
  if (filters.q?.trim()) result.push({ key: 'q', label: filters.q.trim() });
  if (filters.sector?.trim()) result.push({ key: 'sector', label: filters.sector.trim() });
  if (filters.bhk) result.push({ key: 'bhk', label: filters.bhk.replace(/^(\d+)\s*(BHK|RK)$/i, '$1 $2') });
  if (filters.propertyType) result.push({ key: 'propertyType', label: PROPERTY_TYPE_LABELS[filters.propertyType] });
  if (filters.furnishing) result.push({ key: 'furnishing', label: filters.furnishing.replace(/_/g, ' ') });
  if (filters.minRent || filters.maxRent) {
    const amount = (value: number) => `₹${value.toLocaleString('en-IN')}`;
    const label = filters.minRent && filters.maxRent ? `${amount(filters.minRent)}–${amount(filters.maxRent)}`
      : filters.maxRent ? `Under ${amount(filters.maxRent)}` : `From ${amount(filters.minRent!)}`;
    result.push({ key: 'budget', label });
  }
  return result;
};
export const removeLandingCriterion = (filters: RentalSearchFilters, key: LandingCriterionKey): RentalSearchFilters => {
  const next = { ...filters };
  if (key === 'budget') { delete next.minRent; delete next.maxRent; } else delete next[key];
  return next;
};
export const clearLandingCriteria = (city: string): RentalSearchFilters => resetFiltersForSearchClear(city);
export const emptyLandingPart = empty;
