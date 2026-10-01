/**
 * Pure policy and helper functions for notification drawer, badge counts, deep-links, and truthfulness.
 */

export const NOTIFICATION_EMPTY_STATE = {
  title: "You're all caught up.",
  message: "Updates about your properties will appear here."
} as const;

export const NOTIFICATION_ERROR_STATE = {
  message: "We couldn't load your notifications.",
  retryLabel: "Retry"
} as const;

export const NOTIFICATION_MARK_READ_ERROR = "We couldn't update this notification. Try again.";

export interface NotificationItemLike {
  id: string;
  read: boolean;
  title: string;
  message: string;
  actionType?: string;
  actionTarget?: string;
  listingId?: number;
  revisionId?: string;
}

export function calculateUnreadCount(items: Array<{ read: boolean }>): number {
  if (!Array.isArray(items)) return 0;
  return items.filter(item => !item.read).length;
}

export function resolveNotificationActionLabel(item: { actionType?: string }): string {
  if (item.actionType === 'REVIEW_CHANGES') {
    return 'Review changes';
  }
  return 'View property';
}

export function resolveNotificationActionTarget(item: {
  actionTarget?: string;
  listingId?: number;
  actionType?: string;
}): string {
  const listingId = item.listingId;
  const hasListingId = typeof listingId === 'number' && Number.isSafeInteger(listingId) && listingId > 0;
  const isLessorPropertyAction = item.actionType === 'VIEW_PROPERTY' || item.actionType === 'REVIEW_CHANGES';

  // Lessor workflow notifications can outlive older clients whose persisted actionTarget
  // pointed at the public route. The authoritative owner route handles pending and live
  // listings without making unpublished properties public.
  if (isLessorPropertyAction) {
    if (hasListingId) return `/lessor/listings/${listingId}`;
    const existingOwnerTarget = /^\/lessor\/listings\/[1-9]\d*$/.test(item.actionTarget ?? '');
    return existingOwnerTarget ? item.actionTarget! : '/lessor';
  }

  if (item.actionTarget && item.actionTarget.startsWith('/') && !item.actionTarget.startsWith('//')
      && !item.actionTarget.includes('\\')) {
    return item.actionTarget;
  }
  if (hasListingId) return `/lessor/listings/${listingId}`;
  return '/';
}

export async function activateNotificationItem(
  item: NotificationItemLike,
  actions: {
    markAsRead: (id: string) => Promise<void>;
    closeDrawer: () => void;
    navigate: (target: string) => void;
  }
): Promise<void> {
  if (!item.read) await actions.markAsRead(item.id);
  if (!item.actionTarget && !item.actionType) return;
  actions.closeDrawer();
  actions.navigate(resolveNotificationActionTarget(item));
}

export function isLessorWorkflowEvent(type: string): boolean {
  const allowed = [
    'PROPERTY_SUBMITTED',
    'REVIEW_STARTED',
    'CHANGES_REQUIRED',
    'PROPERTY_PUBLISHED',
    'REVISION_SUBMITTED',
    'REVISION_UNDER_REVIEW',
    'REVISION_CHANGES_REQUIRED',
    'REVISION_PUBLISHED',
    'PROPERTY_PAUSED_BY_OPERATIONS',
    'PROPERTY_ARCHIVED_BY_OPERATIONS'
  ];
  return allowed.includes(type);
}
