import type { LessorDraft } from '../services/lessorDraftService';

/** Guest resume requires both the current browser's draft id and its server-verified guest proof. */
export function getGuestResumableDraftCount(localDraftId: string | null, serverDraft: Pick<LessorDraft, 'draftId' | 'status'> | null): number {
  return localDraftId && serverDraft?.draftId === localDraftId && serverDraft.status === 'DRAFT' ? 1 : 0;
}

/** The server count is already scoped to the authenticated owner and DRAFT status. */
export function readResumableDraftCount(totalCount: unknown): number | null {
  return typeof totalCount === 'number' && Number.isSafeInteger(totalCount) && totalCount >= 0
    ? totalCount
    : null;
}
