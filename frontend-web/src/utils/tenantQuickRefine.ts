import {
  FURNISHING_LABELS,
  MAX_SUPPORTED_RENT,
  discoverySearchKey,
  formatRentDisplay,
  resetFiltersForSearchClear
} from './rentalSearch.ts';
import type { RentalFurnishing, RentalPropertyType, RentalSearchFilters } from './rentalSearch.ts';
import { tenantPropertyTypeLabel } from './tenantPropertyTypeLabel.ts';

export const QUICK_REFINE_BHK_OPTIONS = [
  { label: '1 RK', value: '1 RK' },
  { label: '1 BHK', value: '1 BHK' },
  { label: '2 BHK', value: '2 BHK' },
  { label: '3 BHK', value: '3 BHK' },
  { label: '4 BHK', value: '4 BHK' }
] as const;

export const QUICK_REFINE_PROPERTY_TYPES: readonly { label: string; value: RentalPropertyType }[] = [
  { label: tenantPropertyTypeLabel('FLAT')!, value: 'FLAT' },
  { label: tenantPropertyTypeLabel('HOUSE')!, value: 'HOUSE' },
  { label: tenantPropertyTypeLabel('PENTHOUSE')!, value: 'PENTHOUSE' },
  { label: tenantPropertyTypeLabel('STUDIO')!, value: 'STUDIO' },
  { label: tenantPropertyTypeLabel('SERVICED_APARTMENT')!, value: 'SERVICED_APARTMENT' }
];

export const QUICK_REFINE_FURNISHING: readonly { label: string; value: RentalFurnishing }[] = [
  { label: FURNISHING_LABELS.UNFURNISHED, value: 'UNFURNISHED' },
  { label: FURNISHING_LABELS.SEMI_FURNISHED, value: 'SEMI_FURNISHED' },
  { label: FURNISHING_LABELS.FULLY_FURNISHED, value: 'FULLY_FURNISHED' },
  { label: 'Any furnished', value: 'FURNISHED' }
];

export type QuickRefineDimension = 'bhk' | 'propertyType' | 'furnishing' | 'budget';

export type QuickRefineRentThumb = 'min' | 'max';
export interface QuickRefineRentBounds { min: number; max: number; step: number; }
export interface QuickRefineRentValues { min: number; max: number; }

const QUICK_REFINE_RENT_STEP = 500;
export const QUICK_REFINE_RENT_SLIDER_MAX_POSITION = 1_001;
const QUICK_REFINE_RENT_NUMERIC_MAX_POSITION = QUICK_REFINE_RENT_SLIDER_MAX_POSITION - 1;

/** Uses the accepted search-query domain, independent of the currently loaded result page. */
export const quickRefineRentBounds = (filters: RentalSearchFilters): QuickRefineRentBounds => {
  const selectedHigh = Math.max(filters.minRent ?? 0, filters.maxRent ?? 0);
  const selectedDomainMax = selectedHigh > 0
    ? Math.ceil(selectedHigh / QUICK_REFINE_RENT_STEP) * QUICK_REFINE_RENT_STEP + QUICK_REFINE_RENT_STEP
    : 0;
  return {
    min: 0,
    // Keep a separate rightmost “Any” stop after the highest accepted numeric rent.
    max: Math.max(MAX_SUPPORTED_RENT + QUICK_REFINE_RENT_STEP, selectedDomainMax),
    step: QUICK_REFINE_RENT_STEP
  };
};

export const QUICK_REFINE_CHIP_REMOVE_TARGET_PX = 44;

export const quickRefineRentToSliderPosition = (
  rent: number,
  bounds: QuickRefineRentBounds,
  thumb: QuickRefineRentThumb
): number => {
  if (thumb === 'max' && rent >= bounds.max) return QUICK_REFINE_RENT_SLIDER_MAX_POSITION;
  const numericMax = Math.max(bounds.step, bounds.max - bounds.step);
  const ratio = Math.max(0, Math.min(1, rent / numericMax));
  return Math.round(Math.sqrt(ratio) * QUICK_REFINE_RENT_NUMERIC_MAX_POSITION);
};

export const quickRefineSliderPositionToRent = (
  position: number,
  bounds: QuickRefineRentBounds,
  thumb: QuickRefineRentThumb
): number => {
  if (thumb === 'max' && position >= QUICK_REFINE_RENT_SLIDER_MAX_POSITION) return bounds.max;
  const numericPosition = Math.max(0, Math.min(QUICK_REFINE_RENT_NUMERIC_MAX_POSITION, position));
  const numericMax = Math.max(bounds.step, bounds.max - bounds.step);
  const rent = numericMax * (numericPosition / QUICK_REFINE_RENT_NUMERIC_MAX_POSITION) ** 2;
  const steppedRent = Math.round(rent / bounds.step) * bounds.step;
  return thumb === 'max' ? Math.max(bounds.step, steppedRent) : steppedRent;
};

export const quickRefineRentValues = (
  filters: RentalSearchFilters,
  bounds: QuickRefineRentBounds
): QuickRefineRentValues => {
  const rangeMax = Math.max(bounds.max, filters.minRent ?? 0, filters.maxRent ?? 0);
  const min = Math.min(rangeMax, Math.max(bounds.min, filters.minRent ?? bounds.min));
  const max = Math.min(rangeMax, Math.max(bounds.min, filters.maxRent ?? rangeMax));
  return min <= max ? { min, max } : { min: max, max };
};

export const updateQuickRefineRentSlider = (
  filters: RentalSearchFilters,
  bounds: QuickRefineRentBounds,
  values: QuickRefineRentValues,
  thumb: QuickRefineRentThumb,
  rawValue: number
): { filters: RentalSearchFilters; values: QuickRefineRentValues } => {
  if (bounds.max <= bounds.min) return { filters, values: { min: bounds.min, max: bounds.max } };
  const current = quickRefineRentValues(filters, bounds);
  const snapped = Math.round(rawValue / bounds.step) * bounds.step;
  const thumbMax = thumb === 'min' ? bounds.max - bounds.step : bounds.max;
  const bounded = Math.max(bounds.min, Math.min(thumbMax, snapped));
  const nextValues = thumb === 'min'
    ? { min: Math.min(bounded, values.max), max: values.max }
    : { min: values.min, max: Math.max(bounded, values.min) };
  const activeValues = {
    min: Number.isFinite(nextValues.min) ? nextValues.min : current.min,
    max: Number.isFinite(nextValues.max) ? nextValues.max : current.max
  };

  return {
    values: activeValues,
    filters: {
      ...filters,
      ...(thumb === 'min' ? { minRent: activeValues.min <= bounds.min ? undefined : activeValues.min } : {}),
      ...(thumb === 'max' ? { maxRent: activeValues.max >= bounds.max ? undefined : activeValues.max } : {})
    }
  };
};

export const applyQuickRefineRentSliderChanges = (
  filters: RentalSearchFilters,
  bounds: QuickRefineRentBounds,
  values: QuickRefineRentValues,
  changedThumbs: readonly QuickRefineRentThumb[]
): RentalSearchFilters => {
  let next = filters;
  if (changedThumbs.includes('min')) {
    next = updateQuickRefineRentSlider(next, bounds, values, 'min', values.min).filters;
  }
  if (changedThumbs.includes('max')) {
    next = updateQuickRefineRentSlider(next, bounds, values, 'max', values.max).filters;
  }
  return next;
};

const normalizeBhk = (value: string | undefined): string => value?.replace(/\s+/g, '').toUpperCase() || '';

export const quickRefineActiveCount = (filters: RentalSearchFilters): number =>
  Number(QUICK_REFINE_BHK_OPTIONS.some(option => normalizeBhk(option.value) === normalizeBhk(filters.bhk))) +
  Number(QUICK_REFINE_PROPERTY_TYPES.some(option => option.value === filters.propertyType)) +
  Number(QUICK_REFINE_FURNISHING.some(option => option.value === filters.furnishing)) +
  Number(Boolean(filters.minRent || filters.maxRent));

export const quickRefineOptionIsSelected = (
  filters: RentalSearchFilters,
  dimension: Exclude<QuickRefineDimension, 'budget'>,
  value: string
): boolean => dimension === 'bhk'
  ? normalizeBhk(filters.bhk) === normalizeBhk(value)
  : filters[dimension] === value;

export const updateQuickRefineFilter = <
  K extends Exclude<QuickRefineDimension, 'budget'>
>(
  filters: RentalSearchFilters,
  dimension: K,
  value: NonNullable<RentalSearchFilters[K]>
): RentalSearchFilters => {
  const isSelected = dimension === 'bhk'
    ? normalizeBhk(filters.bhk) === normalizeBhk(String(value))
    : filters[dimension] === value;
  return { ...filters, [dimension]: isSelected ? undefined : value };
};

export const clearQuickRefineFilters = (filters: RentalSearchFilters, currentCity?: string): RentalSearchFilters =>
  resetFiltersForSearchClear(filters.city || currentCity);

export const quickRefineChipLabels = (filters: RentalSearchFilters): {
  dimension: QuickRefineDimension;
  label: string;
}[] => {
  const chips: { dimension: QuickRefineDimension; label: string }[] = [];
  if (filters.bhk) {
    const knownBhk = QUICK_REFINE_BHK_OPTIONS.find(option => normalizeBhk(option.value) === normalizeBhk(filters.bhk));
    if (knownBhk) chips.push({ dimension: 'bhk', label: knownBhk.label });
  }
  if (filters.propertyType) {
    const propertyType = QUICK_REFINE_PROPERTY_TYPES.find(option => option.value === filters.propertyType);
    if (propertyType) chips.push({ dimension: 'propertyType', label: propertyType.label });
  }
  if (filters.furnishing) {
    const furnishing = QUICK_REFINE_FURNISHING.find(option => option.value === filters.furnishing);
    if (furnishing) chips.push({ dimension: 'furnishing', label: furnishing.label });
  }
  if (filters.minRent || filters.maxRent) {
    const rangeLabel = filters.minRent && filters.maxRent
      ? `₹${formatRentDisplay(filters.minRent)}–₹${formatRentDisplay(filters.maxRent)}`
      : filters.minRent
        ? `From ₹${formatRentDisplay(filters.minRent)}`
        : `Up to ₹${formatRentDisplay(filters.maxRent!)}`;
    chips.push({ dimension: 'budget', label: rangeLabel });
  }
  return chips;
};

export const quickRefineResultSummary = (
  filters: RentalSearchFilters,
  discoveryState: 'LOADING' | 'READY' | 'ERROR',
  discoveryLoadedKey: string | null,
  homesOnCurrentPage: number
): string => {
  const currentResults = discoveryLoadedKey === discoverySearchKey(filters);
  if (discoveryState === 'ERROR') return 'Homes couldn’t be refreshed.';
  if (discoveryState !== 'READY' || !currentResults) return 'Updating available homes…';
  if (!homesOnCurrentPage) return 'No homes match this search.';
  return `${homesOnCurrentPage} ${homesOnCurrentPage === 1 ? 'home' : 'homes'} loaded`;
};
