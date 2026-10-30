import type { RemovalRequestView } from '../services/propertyRemovalService.ts';

export type LessorRemovalState = 'none' | 'pending' | 'withdrawn' | 'rejected' | 'approved';

const REMOVAL_ELIGIBLE_LISTING_STATES = new Set([
  'SUBMITTED',
  'UNDER_REVIEW',
  'CHANGES_REQUIRED',
  'PUBLISHED',
  'PAUSED'
]);

export const latestLessorRemoval = (
  history: readonly RemovalRequestView[]
): RemovalRequestView | null =>
  history.find(item => item.requestStatus === 'PENDING') ?? history[0] ?? null;

export const lessorRemovalState = (
  latest: RemovalRequestView | null
): LessorRemovalState => {
  if (!latest) return 'none';
  return latest.requestStatus.toLowerCase() as Exclude<LessorRemovalState, 'none'>;
};

export const showLessorListingChanges = (
  listingStatus: string,
  removalState: LessorRemovalState
): boolean => listingStatus !== 'ARCHIVED' && removalState !== 'approved';

export const canRequestLessorRemoval = (
  historyState: 'loading' | 'ready' | 'error',
  listingStatus: string,
  removalState: LessorRemovalState
): boolean => historyState === 'ready'
  && REMOVAL_ELIGIBLE_LISTING_STATES.has(listingStatus)
  && ['none', 'withdrawn', 'rejected'].includes(removalState);
