import type { StaffSnapshot } from '../services/backofficePropertyService.ts';

export type BackofficeSectionKey = 'basics' | 'commercial' | 'details' | 'address' | 'media';

export const BACKOFFICE_SECTION_KEYS: readonly BackofficeSectionKey[] = [
  'basics',
  'commercial',
  'details',
  'address',
  'media'
];

const SECTION_FIELDS: Record<BackofficeSectionKey, readonly (keyof StaffSnapshot)[]> = {
  basics: ['title', 'description', 'bhkCount', 'propertyType'],
  commercial: [
    'monthlyRent',
    'securityDeposit',
    'availableFrom',
    'maintenanceCharge',
    'brokerageAmount',
    'brokerageDays',
    'securityDepositMonths'
  ],
  details: [
    'totalAreaSqFt',
    'floorNumber',
    'totalFloors',
    'furnishingStatus',
    'amenities',
    'bathroomCount',
    'vastuFacing',
    'bachelorAllowed',
    'preferredTenant'
  ],
  address: ['address', 'landmark', 'colony', 'pincode'],
  media: ['media']
};

export const editorSectionValues = (
  snapshot: StaffSnapshot,
  section: BackofficeSectionKey
): Record<string, unknown> => Object.fromEntries(
  SECTION_FIELDS[section].map(field => [field, snapshot[field]])
);

export const editorSectionMatches = (
  snapshot: StaffSnapshot,
  section: BackofficeSectionKey,
  expected: Record<string, unknown>
): boolean => JSON.stringify(editorSectionValues(snapshot, section)) === JSON.stringify(expected);

export const isEditorSectionDirty = (
  draft: StaffSnapshot,
  saved: StaffSnapshot,
  section: BackofficeSectionKey
): boolean => !editorSectionMatches(draft, section, editorSectionValues(saved, section));

export const dirtyEditorSections = (
  draft: StaffSnapshot,
  saved: StaffSnapshot
): BackofficeSectionKey[] => BACKOFFICE_SECTION_KEYS.filter(
  section => isEditorSectionDirty(draft, saved, section)
);

export const applySavedEditorSection = (
  current: StaffSnapshot,
  saved: StaffSnapshot,
  section: BackofficeSectionKey
): StaffSnapshot => {
  const next = { ...current } as Record<keyof StaffSnapshot, unknown>;
  SECTION_FIELDS[section].forEach(field => {
    next[field] = saved[field];
  });
  return next as StaffSnapshot;
};

/** Commits recovered media to its stable identity, independent of its current display position. */
export const applyRecoveredMedia = (
  snapshot: StaffSnapshot,
  mediaId: string,
  recovered: Pick<StaffSnapshot['media'][number], 'url' | 'uploadStatus'>
): StaffSnapshot => ({
  ...snapshot,
  media: snapshot.media.map(item => item.mediaId === mediaId
    ? { ...item, url: recovered.url, uploadStatus: recovered.uploadStatus }
    : item)
});
