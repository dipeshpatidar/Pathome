export type RentalPropertyType = 'FLAT' | 'HOUSE' | 'PENTHOUSE' | 'STUDIO' | 'SERVICED_APARTMENT';
export type RentalFurnishing = 'FURNISHED' | 'FULLY_FURNISHED' | 'SEMI_FURNISHED' | 'UNFURNISHED';

const PROPERTY_TYPE_LABELS: Record<RentalPropertyType, string> = {
  FLAT: 'Flat', HOUSE: 'House', PENTHOUSE: 'Penthouse',
  STUDIO: 'Studio', SERVICED_APARTMENT: 'Serviced Apartment'
};
const FURNISHING_LABELS: Record<RentalFurnishing, string> = {
  FURNISHED: 'Furnished', FULLY_FURNISHED: 'Fully furnished',
  SEMI_FURNISHED: 'Semi-furnished', UNFURNISHED: 'Unfurnished'
};

export class SuggestionRequestError extends Error {
  readonly category: 'network' | 'http' | 'json' | 'contract';
  readonly status?: number;
  constructor(category: 'network' | 'http' | 'json' | 'contract', status?: number) {
    super('Suggestions are unavailable');
    this.name = 'SuggestionRequestError';
    this.category = category;
    this.status = status;
  }
}

export const shouldSurfaceSuggestionFailure = (error: unknown, aborted: boolean, current: boolean): boolean =>
  !aborted && current && !(error instanceof Error && error.name === 'AbortError');

export const suggestionFailureDiagnostic = (error: unknown, queryLength: number) => ({
  endpoint: '/api/v1/properties/search-suggestions',
  category: error instanceof SuggestionRequestError ? error.category : 'unexpected',
  status: error instanceof SuggestionRequestError ? error.status : undefined,
  queryLength
});

export interface RentalSuggestion {
  type: 'CITY' | 'LOCALITY' | 'SEARCH_QUERY';
  label: string;
  city: string;
  locality: string | null;
  bhk: string | null;
  propertyType: RentalPropertyType | null;
  furnishing: RentalFurnishing | null;
  minRent: number | null;
  maxRent: number | null;
  resultCount: number | null;
}

export interface RentalSearchFilters {
  city?: string;
  sector?: string;
  q?: string;
  bhk?: string;
  propertyType?: RentalPropertyType;
  furnishing?: RentalFurnishing;
  minRent?: number;
  maxRent?: number;
  rentalOnly: boolean;
}

export const normalizeSearchText = (text: string): string => text.trim().replace(/\s+/g, ' ');

export const parseRentalPropertyType = (value: string | null | undefined): RentalPropertyType | undefined => {
  if (!value) return undefined;
  return Object.prototype.hasOwnProperty.call(PROPERTY_TYPE_LABELS, value)
    ? value as RentalPropertyType : undefined;
};

export const parseRentalFurnishing = (value: string | null | undefined): RentalFurnishing | undefined => {
  if (!value) return undefined;
  return Object.prototype.hasOwnProperty.call(FURNISHING_LABELS, value)
    ? value as RentalFurnishing : undefined;
};

export const parseRentFilter = (value: string | null | undefined): number | undefined => {
  if (!value || !/^\d+$/.test(value)) return undefined;
  const amount = Number(value);
  return Number.isSafeInteger(amount) && amount > 0 && amount <= 10_000_000 ? amount : undefined;
};

const rentalSuggestionLabel = (city: string, sector: string, bhk?: string,
  propertyType?: RentalPropertyType, furnishing?: RentalFurnishing,
  minRent?: number, maxRent?: number): string => {
  const prefix = [bhk?.replace(/^(\d+)\s*(BHK|RK)$/i, '$1 $2'),
    propertyType ? PROPERTY_TYPE_LABELS[propertyType] : undefined].filter(Boolean).join(' ');
  const description = [prefix, furnishing ? FURNISHING_LABELS[furnishing] : undefined].filter(Boolean).join(', ');
  const rent = maxRent ? ` · ${minRent ? `₹${minRent.toLocaleString('en-IN')}–₹${maxRent.toLocaleString('en-IN')}`
    : `up to ₹${maxRent.toLocaleString('en-IN')}`}` : '';
  return `${description ? `${description} in ` : ''}${sector}, ${city}${rent}`;
};

export const mapRentalSuggestion = (value: unknown): RentalSuggestion | null => {
  if (!value || typeof value !== 'object') return null;
  const item = value as Record<string, unknown>;
  if (item.type !== 'CITY' && item.type !== 'LOCALITY' && item.type !== 'SEARCH_QUERY') return null;
  if (typeof item.label !== 'string' || !item.label.trim() || typeof item.city !== 'string' || !item.city.trim()) return null;
  if (item.type !== 'CITY' && (typeof item.locality !== 'string' || !item.locality.trim())) return null;
  const propertyType = parseRentalPropertyType(typeof item.propertyType === 'string' ? item.propertyType : null) || null;
  const furnishing = parseRentalFurnishing(typeof item.furnishing === 'string' ? item.furnishing : null) || null;
  return {
    type: item.type,
    label: item.label,
    city: item.city,
    locality: typeof item.locality === 'string' ? item.locality : null,
    bhk: typeof item.bhk === 'string' && item.bhk.trim() ? item.bhk : null,
    propertyType,
    furnishing,
    minRent: typeof item.minRent === 'number' && Number.isFinite(item.minRent) ? item.minRent : null,
    maxRent: typeof item.maxRent === 'number' && Number.isFinite(item.maxRent) ? item.maxRent : null,
    resultCount: typeof item.resultCount === 'number' && Number.isFinite(item.resultCount) ? item.resultCount : null
  };
};

export const suggestionFromStructuredFilters = (
  city: string | undefined,
  sector: string | undefined,
  bhk: string | undefined,
  propertyType?: RentalPropertyType,
  furnishing?: RentalFurnishing,
  minRent?: number,
  maxRent?: number
): RentalSuggestion | null => {
  if (!city || !sector) return null;
  const label = rentalSuggestionLabel(city, sector, bhk, propertyType, furnishing, minRent, maxRent);
  return {
    type: bhk || propertyType || furnishing || minRent || maxRent ? 'SEARCH_QUERY' : 'LOCALITY', label, city, locality: sector,
    bhk: bhk || null, propertyType: propertyType || null, furnishing: furnishing || null,
    minRent: minRent || null, maxRent: maxRent || null, resultCount: null
  };
};

/** Structured selection wins only while its display text has not been edited. */
export const buildRentalSearchFilters = (
  city: string | undefined,
  text: string,
  selection: RentalSuggestion | null
): RentalSearchFilters => {
  const query = normalizeSearchText(text);
  if (selection && query === selection.label) {
    return {
      city: selection.city,
      sector: selection.locality || undefined,
      bhk: selection.bhk || undefined,
      ...(selection.propertyType ? { propertyType: selection.propertyType } : {}),
      ...(selection.furnishing ? { furnishing: selection.furnishing } : {}),
      ...(selection.minRent ? { minRent: selection.minRent } : {}),
      ...(selection.maxRent ? { maxRent: selection.maxRent } : {}),
      rentalOnly: true
    };
  }
  return { city: city?.trim() || undefined, q: query || undefined, rentalOnly: true };
};

export const selectionAfterCityChange = (selection: RentalSuggestion | null, city: string): RentalSuggestion | null =>
  selection?.city.toLocaleLowerCase() === city.trim().toLocaleLowerCase() ? selection : null;

export const discoverySearchKey = (filters: RentalSearchFilters): string =>
  [filters.city, filters.sector, filters.q, filters.bhk, filters.propertyType, filters.furnishing,
    filters.minRent, filters.maxRent, filters.rentalOnly ? 'rent' : 'all']
    .map((value) => normalizeSearchText(String(value || '')).toLocaleLowerCase()).join('|');
