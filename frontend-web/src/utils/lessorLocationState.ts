import type { LessorLocation } from '../services/lessorDraftService';
import type { LessorLocalityOption } from '../services/lessorLocationService';

export function changeLocationCity(location: LessorLocation, city: string): LessorLocation {
  return { ...location, city, localityInput: '', canonicalLocalityId: null,
    resolutionType: null, provider: null, providerPlaceId: null, selectionToken: null };
}

export function changeLocalityText(location: LessorLocation, localityInput: string): LessorLocation {
  return { ...location, localityInput, canonicalLocalityId: null,
    resolutionType: null, provider: null, providerPlaceId: null, selectionToken: null };
}

export function chooseLocalityOption(location: LessorLocation, option: LessorLocalityOption): LessorLocation | null {
  if (option.city !== location.city || option.match === 'different_city') return null;
  if (option.match === 'external') {
    if (!option.selectionToken || !option.provider || !option.providerPlaceId) return null;
    return { ...location, canonicalLocalityId: null, localityInput: option.name,
      resolutionType: 'EXTERNAL_RESOLVED', provider: option.provider,
      providerPlaceId: option.providerPlaceId, selectionToken: option.selectionToken };
  }
  if (option.id === null) return null;
  return { ...location, canonicalLocalityId: option.id, localityInput: option.name,
    resolutionType: 'CANONICAL', provider: null, providerPlaceId: null, selectionToken: null };
}

export function useLocalityForReview(location: LessorLocation): LessorLocation {
  return { ...location, localityInput: location.localityInput.trim(), canonicalLocalityId: null,
    resolutionType: 'MANUAL_PENDING', provider: null, providerPlaceId: null, selectionToken: null };
}

export function locationValidationError(location: LessorLocation, supportedCities: string[]): string | null {
  if (!supportedCities.includes(location.city)) return 'Choose a city currently supported by Pathome.';
  if (!location.localityInput.trim() || !location.canonicalLocalityId &&
      !['EXTERNAL_RESOLVED', 'MANUAL_PENDING'].includes(location.resolutionType || ''))
    return 'Choose a locality from the suggestions, or continue with this locality for review.';
  if (!location.address.trim()) return "Add the property's street address.";
  return null;
}
