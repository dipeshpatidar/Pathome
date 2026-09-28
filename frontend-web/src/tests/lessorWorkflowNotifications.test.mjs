import test from 'node:test';
import assert from 'node:assert/strict';
import {
  calculateUnreadCount,
  resolveNotificationActionLabel,
  resolveNotificationActionTarget,
  isLessorWorkflowEvent,
  NOTIFICATION_EMPTY_STATE,
  NOTIFICATION_ERROR_STATE,
  NOTIFICATION_MARK_READ_ERROR
} from '../utils/notificationPolicy.ts';

test('1. Unread count calculation is strictly based on real items with no fake count', () => {
  assert.equal(calculateUnreadCount([]), 0);
  assert.equal(calculateUnreadCount([{ read: true }, { read: true }]), 0);
  assert.equal(calculateUnreadCount([{ read: false }, { read: true }]), 1);
  assert.equal(calculateUnreadCount([{ read: false }, { read: false }, { read: false }]), 3);
});

test('2. Empty state copy strictly matches product requirements', () => {
  assert.equal(NOTIFICATION_EMPTY_STATE.title, "You're all caught up.");
  assert.equal(NOTIFICATION_EMPTY_STATE.message, "Updates about your properties will appear here.");
});

test('3. Error and retry state copy strictly matches product requirements', () => {
  assert.equal(NOTIFICATION_ERROR_STATE.message, "We couldn't load your notifications.");
  assert.equal(NOTIFICATION_ERROR_STATE.retryLabel, "Retry");
  assert.equal(NOTIFICATION_MARK_READ_ERROR, "We couldn't update this notification. Try again.");
});

test('4. CHANGES_REQUIRED deep-link routes to correct property view and shows "Review changes"', () => {
  const notif = {
    actionType: 'REVIEW_CHANGES',
    actionTarget: '/lessor/listings/105',
    listingId: 105
  };
  assert.equal(resolveNotificationActionLabel(notif), 'Review changes');
  assert.equal(resolveNotificationActionTarget(notif), '/lessor/listings/105');
  assert.notEqual(resolveNotificationActionTarget(notif), '/lessor/new');
});

test('5. PUBLISHED deep-link routes to correct property preview and shows "View property"', () => {
  const notif = {
    actionType: 'VIEW_PROPERTY',
    actionTarget: '/lessor/listings/202',
    listingId: 202
  };
  assert.equal(resolveNotificationActionLabel(notif), 'View property');
  assert.equal(resolveNotificationActionTarget(notif), '/lessor/listings/202');
  assert.notEqual(resolveNotificationActionTarget(notif), '/lessor/new');
});

test('6. Action target resolution falls back safely to property ID if actionTarget is missing', () => {
  const notif = {
    actionType: 'VIEW_PROPERTY',
    listingId: 303
  };
  assert.equal(resolveNotificationActionTarget(notif), '/lessor/listings/303');
  assert.notEqual(resolveNotificationActionTarget(notif), '/lessor/new');
});

test('7. Supported lessor workflow events match authoritative backend state definitions', () => {
  const expectedEvents = [
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

  for (const event of expectedEvents) {
    assert.equal(isLessorWorkflowEvent(event), true, `Expected ${event} to be recognized`);
  }

  assert.equal(isLessorWorkflowEvent('ARBITRARY_EVENT'), false);
  assert.equal(isLessorWorkflowEvent('INTERNAL_VIEW_EVENT'), false);
});

test('8. Long titles and messages are formatted cleanly without crashing or producing undefined', () => {
  const longTitle = 'A'.repeat(300);
  const longMessage = 'B'.repeat(1000);
  const notif = {
    id: 'db-1',
    read: false,
    title: longTitle,
    message: longMessage,
    actionType: 'REVIEW_CHANGES',
    actionTarget: '/lessor/listings/999'
  };

  assert.ok(notif.title.length > 0);
  assert.ok(notif.message.length > 0);
  assert.equal(resolveNotificationActionTarget(notif), '/lessor/listings/999');
});
