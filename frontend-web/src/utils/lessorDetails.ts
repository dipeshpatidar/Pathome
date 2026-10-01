import type { LessorDetails } from '../services/lessorDraftService';

export const EMPTY_LESSOR_DETAILS: LessorDetails = {
  availableFrom: null,
  furnishingStatus: '',
  totalAreaSqFt: null,
  floorNumber: null,
  totalFloors: null,
  amenities: '',
  description: ''
};

export const LESSOR_FURNISHING_OPTIONS = [
  { value: 'UNFURNISHED', label: 'Unfurnished' },
  { value: 'SEMI_FURNISHED', label: 'Semi-furnished' },
  { value: 'FULLY_FURNISHED', label: 'Fully furnished' }
] as const;

/** Older drafts may omit newer optional keys or contain null strings. */
export function restoreLessorDetails(saved: Partial<LessorDetails> | null | undefined,
  pending?: Partial<LessorDetails> | null): LessorDetails {
  const value = { ...(saved ?? {}), ...(pending ?? {}) };
  return {
    availableFrom: value.availableFrom ?? null,
    furnishingStatus: value.furnishingStatus ?? '',
    totalAreaSqFt: value.totalAreaSqFt ?? null,
    floorNumber: value.floorNumber ?? null,
    totalFloors: value.totalFloors ?? null,
    amenities: value.amenities ?? '',
    description: value.description ?? ''
  };
}

/** Local calendar date, matching what a native date input presents to its user. */
export function availableNowDate(now: Date = new Date()): string {
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}
