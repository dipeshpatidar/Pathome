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
  type: 'CITY' | 'LOCALITY' | 'SEARCH_QUERY' | 'QUERY_INTENT' | 'SEARCH_ANYWAY' | 'UNSUPPORTED_CITY' | 'ENTITY_MATCH';
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

const KNOWN_METRO_CITIES: Record<string, string> = {
  indore: 'Indore', bhopal: 'Bhopal', pune: 'Pune', mumbai: 'Mumbai',
  delhi: 'Delhi', bangalore: 'Bangalore', bengaluru: 'Bangalore', hyderabad: 'Hyderabad',
  chennai: 'Chennai', kolkata: 'Kolkata', ahmedabad: 'Ahmedabad', jaipur: 'Jaipur',
  surat: 'Surat', lucknow: 'Lucknow', chandigarh: 'Chandigarh', goa: 'Goa',
  dewas: 'Dewas', ujjain: 'Ujjain', gwalior: 'Gwalior', jabalpur: 'Jabalpur',
  noida: 'Noida', gurgaon: 'Gurgaon'
};

const CANONICAL_LOCALITY_CITIES: Record<string, string> = {
  'baner': 'Pune', 'wakad': 'Pune', 'hinjewadi': 'Pune', 'kharadi': 'Pune', 'viman nagar': 'Pune',
  'mp nagar': 'Bhopal', 'arera colony': 'Bhopal', 'kolar road': 'Bhopal', 'hoshangabad road': 'Bhopal',
  'vijay nagar': 'Indore', 'nanda nagar': 'Indore', 'bhawarkua': 'Indore', 'nipania': 'Indore'
};

export const extractCityFromSearchQuery = (text: string): string | undefined => {
  if (!text) return undefined;
  const query = text.toLowerCase();
  const connMatch = /(?:^|\b)(?:in|at|of)\s+([a-z]+)(?!\s+(?:road|naka|gate|bypass|highway|circle|square))\b/i.exec(query);
  if (connMatch) {
    const candidate = connMatch[1].toLowerCase();
    if (KNOWN_METRO_CITIES[candidate]) {
      return KNOWN_METRO_CITIES[candidate];
    }
  }
  for (const [loc, city] of Object.entries(CANONICAL_LOCALITY_CITIES)) {
    const locRegex = new RegExp(`(?:^|\\b)${loc}(?:\\b|$)`, 'i');
    if (locRegex.test(query)) {
      return city;
    }
  }
  for (const [cityKey, cityName] of Object.entries(KNOWN_METRO_CITIES)) {
    const cityRegex = new RegExp(`(?:^|\\b)${cityKey}(?!\\s+(?:road|naka|gate|bypass|highway|circle|square))\\b`, 'i');
    if (cityRegex.test(query)) {
      const withoutCity = query.replace(cityRegex, ' ').replace(/\s+/g, ' ').trim();
      if (withoutCity.length > 0) {
        return cityName;
      }
    }
  }
  return undefined;
};

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
  const validTypes = ['CITY', 'LOCALITY', 'SEARCH_QUERY', 'QUERY_INTENT', 'SEARCH_ANYWAY', 'UNSUPPORTED_CITY', 'ENTITY_MATCH'];
  if (!validTypes.includes(item.type as string)) return null;
  if (typeof item.label !== 'string' || !item.label.trim() || typeof item.city !== 'string' || !item.city.trim()) return null;
  if (item.type !== 'CITY' && item.type !== 'QUERY_INTENT' && item.type !== 'SEARCH_ANYWAY' && item.type !== 'UNSUPPORTED_CITY' && item.type !== 'ENTITY_MATCH' && (typeof item.locality !== 'string' || !item.locality.trim())) return null;
  const propertyType = parseRentalPropertyType(typeof item.propertyType === 'string' ? item.propertyType : null) || null;
  const furnishing = parseRentalFurnishing(typeof item.furnishing === 'string' ? item.furnishing : null) || null;
  return {
    type: item.type as RentalSuggestion['type'],
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
  if (selection && (query === selection.label || selection.type === 'QUERY_INTENT' || selection.type === 'SEARCH_ANYWAY' || selection.type === 'UNSUPPORTED_CITY' || selection.type === 'ENTITY_MATCH')) {
    if (selection.type === 'SEARCH_ANYWAY') {
      return { city: selection.city || city?.trim() || undefined, q: query || undefined, rentalOnly: true };
    }
    if (selection.type === 'UNSUPPORTED_CITY') {
      return { city: selection.city, q: query || undefined, rentalOnly: true };
    }
    return {
      city: selection.city,
      ...(selection.locality ? { sector: selection.locality } : {}),
      ...(selection.bhk ? { bhk: selection.bhk } : {}),
      ...(selection.propertyType ? { propertyType: selection.propertyType } : {}),
      ...(selection.furnishing ? { furnishing: selection.furnishing } : {}),
      ...(selection.minRent ? { minRent: selection.minRent } : {}),
      ...(selection.maxRent ? { maxRent: selection.maxRent } : {}),
      rentalOnly: true
    };
  }
  const inferredCity = extractCityFromSearchQuery(query);
  const effectiveCity = inferredCity || city?.trim() || undefined;
  return { city: effectiveCity, q: query || undefined, rentalOnly: true };
};

export const selectionAfterCityChange = (selection: RentalSuggestion | null, city: string): RentalSuggestion | null =>
  selection?.city.toLocaleLowerCase() === city.trim().toLocaleLowerCase() ? selection : null;

export const discoverySearchKey = (filters: RentalSearchFilters): string =>
  [filters.city, filters.sector, filters.q, filters.bhk, filters.propertyType, filters.furnishing,
    filters.minRent, filters.maxRent, filters.rentalOnly ? 'rent' : 'all']
    .map((value) => normalizeSearchText(String(value || '')).toLocaleLowerCase()).join('|');
