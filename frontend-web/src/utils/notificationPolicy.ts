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
  if (item.actionTarget && item.actionTarget.startsWith('/')) {
    return item.actionTarget;
  }
  if (item.listingId) {
    return `/lessor/listings/${item.listingId}`;
  }
  return '/lessor';
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
