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
  eventKey?: string;
  listingId?: number;
  targetRole?: string;
  category?: string;
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
  eventKey?: string;
  targetRole?: string;
}): string {
  const listingId = item.listingId;
  const hasListingId = typeof listingId === 'number' && Number.isSafeInteger(listingId) && listingId > 0;
  const isLessorPropertyAction = item.actionType === 'VIEW_PROPERTY' || item.actionType === 'REVIEW_CHANGES';
  const workflowEvent = item.eventKey?.split(':', 1)[0] ?? '';

  // Owner workflow notifications open the authenticated owner detail route. Never route
  // these events to public detail: pending listings are not publicly available.
  if (isLessorPropertyAction || isLessorWorkflowEvent(workflowEvent)) {
    if (hasListingId) return `/lessor/listings/${listingId}`;
    const ownerTarget = item.actionTarget?.match(/^\/lessor\/listings\/([1-9][0-9]*)$/);
    if (ownerTarget) {
      const targetId = Number(ownerTarget[1]);
      if (Number.isSafeInteger(targetId) && targetId > 0) return `/lessor/listings/${targetId}`;
    }
    return '/lessor';
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
  if (!item.actionTarget && !item.actionType && !item.eventKey) return;
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
