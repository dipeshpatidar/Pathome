import type { LessorDraft, LessorDraftSummary } from '../services/lessorDraftService';

/** Owned API results only; keep actionable drafts ordered by authoritative update time. */
export function actionableDraftsNewestFirst(drafts: LessorDraftSummary[]): LessorDraftSummary[] {
  return drafts.filter(draft => draft.status === 'DRAFT').sort((a, b) => {
    const aTime = Date.parse(a.updatedAt);
    const bTime = Date.parse(b.updatedAt);
    return (Number.isFinite(bTime) ? bTime : 0) - (Number.isFinite(aTime) ? aTime : 0);
  });
}

export function guestDraftSummary(draft: LessorDraft): LessorDraftSummary {
  const labels = { FLAT: 'Flat', HOUSE: 'House', STUDIO: 'Studio', PENTHOUSE: 'Penthouse', SERVICED_APARTMENT: 'Serviced apartment' };
  const basics = draft.data.basics;
  return { draftId: draft.draftId, title: [basics.bhkCount, labels[basics.propertyType]].filter(Boolean).join(' ') || 'Property listing',
    status: draft.status, completionPercent: draft.completionPercent, updatedAt: draft.updatedAt, propertyType: basics.propertyType,
    bhkCount: basics.bhkCount, city: draft.data.location?.city || null, locality: draft.data.location?.localityInput || null,
    monthlyRent: draft.data.pricing?.monthlyRent ?? null, coverUrl: null };
}
