import type { RoomTag } from '../types';

// Presentation only: persisted tags and media ordering remain unchanged.
const MEDIA_TAG_LABELS: Record<RoomTag, string | null> = {
  GENERAL: null,
  LIVING_ROOM: 'Living Room',
  MASTER_BEDROOM: 'Master Bedroom',
  BEDROOM: 'Bedroom',
  KITCHEN: 'Kitchen',
  BATHROOM: 'Bathroom',
  BALCONY: 'Balcony',
  EXTERIOR: 'Exterior',
  AMENITIES: 'Amenities',
  FLOOR_PLAN: 'Floor Plan'
};

/**
 * Lucide icon names for each room tag.
 * These correspond to named exports from 'lucide-react'.
 * Unknown tags fall back to the 'Image' icon.
 */
export type MediaTagIconName =
  | 'Sofa' | 'BedDouble' | 'Bath' | 'UtensilsCrossed' | 'Building2'
  | 'Wind' | 'Dumbbell' | 'LayoutDashboard' | 'Image';

const MEDIA_TAG_ICONS: Record<RoomTag, MediaTagIconName> = {
  GENERAL: 'Image',
  LIVING_ROOM: 'Sofa',
  MASTER_BEDROOM: 'BedDouble',
  BEDROOM: 'BedDouble',
  KITCHEN: 'UtensilsCrossed',
  BATHROOM: 'Bath',
  BALCONY: 'Wind',
  EXTERIOR: 'Building2',
  AMENITIES: 'Dumbbell',
  FLOOR_PLAN: 'LayoutDashboard'
};

export const getMediaTagLabel = (value: unknown): string | null =>
  typeof value === 'string' && Object.prototype.hasOwnProperty.call(MEDIA_TAG_LABELS, value)
    ? MEDIA_TAG_LABELS[value as RoomTag]
    : null;

/** Returns the Lucide icon name for the given room tag; falls back to 'Image'. */
export const getMediaTagIcon = (value: unknown): MediaTagIconName => {
  if (typeof value === 'string' && Object.prototype.hasOwnProperty.call(MEDIA_TAG_ICONS, value)) {
    return MEDIA_TAG_ICONS[value as RoomTag];
  }
  return 'Image';
};

/** Index the first appearance of each real area without changing gallery order. */
export const getTaggedAreas = (media: ReadonlyArray<{ tagLabel: string | null }>): Array<[string, number]> => {
  const firstByLabel = new Map<string, number>();
  media.forEach((entry, index) => {
    if (entry.tagLabel && !firstByLabel.has(entry.tagLabel)) firstByLabel.set(entry.tagLabel, index);
  });
  return [...firstByLabel.entries()];
};

