type MediaRecord = Record<string, unknown>;

export interface MediaChangeSummary {
  hasChanges: boolean;
  added: number;
  removed: number;
  changed: number;
  reordered: boolean;
  coverChanged: boolean;
  text: string;
  resources: MediaResourceDifference[];
}

export interface MediaFieldDifference {
  field: 'url' | 'mediaType' | 'caption' | 'roomTag' | 'cover' | 'sortOrder';
  label: string;
  before: unknown;
  after: unknown;
}

export interface MediaResourceDifference {
  identity: string;
  label: string;
  kind: 'ADDED' | 'REMOVED' | 'UPDATED';
  changes: MediaFieldDifference[];
}

const records = (value: unknown): MediaRecord[] =>
  Array.isArray(value) ? value.filter((item): item is MediaRecord =>
    item !== null && typeof item === 'object' && !Array.isArray(item)) : [];

const resourceIdentity = (item: MediaRecord, index: number): string => {
  const stable = item.sourceMediaAssetId ?? item.resourceId;
  if (stable !== null && stable !== undefined && String(stable).trim()) return `resource:${String(stable).trim()}`;
  const url = typeof item.url === 'string' ? item.url.trim() : '';
  return url ? `url:${url}` : `position:${index}`;
};

const comparable = (item: MediaRecord): string => JSON.stringify({
  url: item.url ?? null,
  mediaType: item.mediaType ?? null,
  roomTag: item.roomTag ?? null,
  caption: item.caption ?? null,
  sortOrder: item.sortOrder ?? null,
  cover: Boolean(item.cover ?? item.primaryCover)
});

const MEDIA_FIELDS: ReadonlyArray<{
  field: MediaFieldDifference['field'];
  label: string;
  value: (item: MediaRecord) => unknown;
}> = [
  { field: 'url', label: 'Resource', value: item => item.url ?? null },
  { field: 'mediaType', label: 'Media type', value: item => item.mediaType ?? null },
  { field: 'caption', label: 'Caption', value: item => item.caption ?? null },
  { field: 'roomTag', label: 'Room', value: item => item.roomTag ?? null },
  { field: 'cover', label: 'Cover', value: item => Boolean(item.cover ?? item.primaryCover) },
  { field: 'sortOrder', label: 'Display order', value: item => item.sortOrder ?? null }
];

const ROOM_TAG_LABELS: Readonly<Record<string, string>> = {
  GENERAL: 'General',
  LIVING_ROOM: 'Living room',
  MASTER_BEDROOM: 'Master bedroom',
  BEDROOM: 'Bedroom',
  KITCHEN: 'Kitchen',
  BATHROOM: 'Bathroom',
  BALCONY: 'Balcony',
  EXTERIOR: 'Exterior',
  AMENITIES: 'Amenities',
  FLOOR_PLAN: 'Floor plan'
};

const MEDIA_TYPE_LABELS: Readonly<Record<string, string>> = {
  IMAGE: 'Image',
  VIDEO: 'Video',
  VIDEO_WALKTHROUGH: 'Video walkthrough'
};

/**
 * Formats audit evidence by field, never by the arbitrary stored value. Captions, resource URLs and
 * other human-entered identifiers remain byte-for-byte unchanged; only recognized enums are labelled.
 */
export const formatActivityEvidenceValue = (field: string, value: unknown): string => {
  if (value === null || value === undefined || value === '') return 'None';
  if (typeof value === 'boolean') return value ? 'Yes' : 'No';
  if (typeof value === 'number') return String(value);
  const text = String(value);
  if (field === 'roomTag') return ROOM_TAG_LABELS[text] ?? text;
  if (field === 'mediaType') return MEDIA_TYPE_LABELS[text] ?? text;
  return text;
};

const sameValue = (before: unknown, after: unknown): boolean =>
  JSON.stringify(before ?? null) === JSON.stringify(after ?? null);

const resourceLabel = (item: MediaRecord, index: number): string => {
  const stable = item.sourceMediaAssetId ?? item.resourceId;
  if (stable !== null && stable !== undefined && String(stable).trim()) {
    return `Media resource #${String(stable).trim()}`;
  }
  return `Media ${index + 1}`;
};

export const describeMediaChange = (
  beforeValue: unknown,
  afterValue: unknown
): MediaResourceDifference[] => {
  const before = records(beforeValue);
  const after = records(afterValue);
  const beforeById = new Map(before.map((item, index) => [resourceIdentity(item, index), item]));
  const afterById = new Map(after.map((item, index) => [resourceIdentity(item, index), item]));
  const differences: MediaResourceDifference[] = [];

  after.forEach((item, index) => {
    const identity = resourceIdentity(item, index);
    const prior = beforeById.get(identity);
    if (!prior) {
      differences.push({
        identity,
        label: resourceLabel(item, index),
        kind: 'ADDED',
        changes: MEDIA_FIELDS.map(field => ({
          field: field.field,
          label: field.label,
          before: null,
          after: field.value(item)
        }))
      });
      return;
    }
    const changes = MEDIA_FIELDS.flatMap(field => {
      const oldValue = field.value(prior);
      const newValue = field.value(item);
      return sameValue(oldValue, newValue) ? [] : [{ field: field.field, label: field.label,
        before: oldValue, after: newValue }];
    });
    if (changes.length > 0) {
      differences.push({
        identity,
        label: resourceLabel(item, index),
        kind: 'UPDATED',
        changes
      });
    }
  });

  before.forEach((item, index) => {
    const identity = resourceIdentity(item, index);
    if (afterById.has(identity)) return;
    differences.push({
      identity,
      label: resourceLabel(item, index),
      kind: 'REMOVED',
      changes: MEDIA_FIELDS.map(field => ({
        field: field.field,
        label: field.label,
        before: field.value(item),
        after: null
      }))
    });
  });
  return differences;
};

export const summarizeMediaChange = (beforeValue: unknown, afterValue: unknown): MediaChangeSummary => {
  const before = records(beforeValue);
  const after = records(afterValue);
  const beforeById = new Map(before.map((item, index) => [resourceIdentity(item, index), item]));
  const afterById = new Map(after.map((item, index) => [resourceIdentity(item, index), item]));
  const added = [...afterById.keys()].filter(key => !beforeById.has(key)).length;
  const removed = [...beforeById.keys()].filter(key => !afterById.has(key)).length;
  const changed = [...beforeById.entries()]
    .filter(([key, item]) => afterById.has(key) && comparable(item) !== comparable(afterById.get(key)!))
    .length;
  const reordered = beforeById.size === afterById.size
    && [...beforeById.keys()].every(key => afterById.has(key))
    && ([...beforeById.keys()].join('|') !== [...afterById.keys()].join('|')
      || before.map(item => String(item.sortOrder ?? '')).join('|')
        !== after.map(item => String(item.sortOrder ?? '')).join('|'));
  const coverChanged = before.filter(item => Boolean(item.cover ?? item.primaryCover))
    .map((item, index) => resourceIdentity(item, index)).join('|')
    !== after.filter(item => Boolean(item.cover ?? item.primaryCover))
      .map((item, index) => resourceIdentity(item, index)).join('|');
  const parts = [
    added ? `${added} added` : '',
    removed ? `${removed} removed` : '',
    changed ? `${changed} updated` : '',
    reordered ? 'order changed' : '',
    coverChanged ? 'cover changed' : ''
  ].filter(Boolean);
  return {
    hasChanges: parts.length > 0,
    added,
    removed,
    changed,
    reordered,
    coverChanged,
    text: parts.length ? `Media: ${parts.join(', ')}.` : 'Media content is unchanged.',
    resources: describeMediaChange(beforeValue, afterValue)
  };
};
