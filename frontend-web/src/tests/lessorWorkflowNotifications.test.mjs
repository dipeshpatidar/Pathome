import test from 'node:test';
import assert from 'node:assert/strict';
import {
  activateNotificationItem,
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

test('4. CHANGES_REQUIRED notification routes to My Properties and shows "Review changes"', () => {
  const notif = {
    actionType: 'REVIEW_CHANGES',
    actionTarget: '/lessor/listings/105',
    listingId: 105
  };
  assert.equal(resolveNotificationActionLabel(notif), 'Review changes');
  assert.equal(resolveNotificationActionTarget(notif), '/lessor/listings/105');
  assert.notEqual(resolveNotificationActionTarget(notif), '/lessor/new');
});

test('5. Published workflow notification retains the owner context', () => {
  const notif = {
    actionType: 'VIEW_PROPERTY',
    actionTarget: '/lessor/listings/202',
    listingId: 202
  };
  assert.equal(resolveNotificationActionLabel(notif), 'View property');
  assert.equal(resolveNotificationActionTarget(notif), '/lessor/listings/202');
  assert.notEqual(resolveNotificationActionTarget(notif), '/lessor/new');
});

test('6. Action target resolution uses the owner detail route when a valid listing ID exists', () => {
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

test('the real submitted notification payload resolves to its authenticated owner detail URL', () => {
  const notification = {
    id: 'db-19',
    read: false,
    title: 'Property submitted',
    message: '2BHK flat in Press Complex Behind Dainik Bhaskar Press was submitted for review.',
    category: 'PROPERTY',
    targetRole: 'LANDLORD',
    eventKey: 'PROPERTY_SUBMITTED:100:0',
    actionType: 'VIEW_PROPERTY',
    actionTarget: '/lessor/listings/100',
    listingId: 100
  };
  assert.equal(resolveNotificationActionTarget(notification), '/lessor/listings/100');
});

test('legacy submitted notification overrides a stale public URL using structured workflow metadata', () => {
  assert.equal(resolveNotificationActionTarget({
    actionType: 'VIEW_PROPERTY', actionTarget: '/property/100', listingId: 100,
    eventKey: 'PROPERTY_SUBMITTED:100:0', targetRole: 'LANDLORD'
  }), '/lessor/listings/100');
  assert.equal(resolveNotificationActionTarget({ eventKey: 'PROPERTY_SUBMITTED:missing:0' }), '/lessor');
  assert.equal(resolveNotificationActionTarget({
    actionType: 'VIEW_PROPERTY', actionTarget: '/lessor/listings/100'
  }), '/lessor/listings/100');
});

test('pending and published workflow notifications use My Properties, while public actions stay public', () => {
  assert.equal(resolveNotificationActionTarget({
    actionType: 'REVIEW_CHANGES', actionTarget: '/property/315', listingId: 315,
    eventKey: 'CHANGES_REQUIRED:315:0'
  }), '/lessor/listings/315');
  assert.equal(resolveNotificationActionTarget({
    actionType: 'VIEW_PROPERTY', actionTarget: '/lessor/listings/316', listingId: 316,
    eventKey: 'PROPERTY_PUBLISHED:316:0'
  }), '/lessor/listings/316');
  assert.equal(resolveNotificationActionTarget({
    actionType: 'OPEN_PUBLIC_PROPERTY', actionTarget: '/property/317', listingId: 317
  }), '/property/317');
});

test('notification action marks the real legacy item read, closes drawer, then opens owner detail', async () => {
  const calls = [];
  await activateNotificationItem({
    id: 'db-19', read: false, title: 'Property submitted', message: 'Submitted for review.',
    category: 'PROPERTY', targetRole: 'LANDLORD', eventKey: 'PROPERTY_SUBMITTED:100:0',
    actionType: 'VIEW_PROPERTY', actionTarget: '/lessor/listings/100', listingId: 100
  }, {
    markAsRead: async id => calls.push(`read:${id}`),
    closeDrawer: () => calls.push('close'),
    navigate: target => calls.push(`navigate:${target}`)
  });
  assert.deepEqual(calls, ['read:db-19', 'close', 'navigate:/lessor/listings/100']);
});

test('already-read notification does not repeat the read request before navigation', async () => {
  const calls = [];
  await activateNotificationItem({
    id: 'db-319', read: true, title: 'Property published', message: 'Now visible to renters.',
    category: 'PROPERTY', targetRole: 'LANDLORD', eventKey: 'PROPERTY_PUBLISHED:319:0',
    actionType: 'VIEW_PROPERTY', listingId: 319
  }, {
    markAsRead: async id => calls.push(`read:${id}`),
    closeDrawer: () => calls.push('close'),
    navigate: target => calls.push(`navigate:${target}`)
  });
  assert.deepEqual(calls, ['close', 'navigate:/lessor/listings/319']);
});

test('malformed owner workflow resources still fall back to My Properties and public targets remain valid', () => {
  assert.equal(resolveNotificationActionTarget({
    actionType: 'VIEW_PROPERTY', actionTarget: '//outside.example', listingId: Number.NaN,
    eventKey: 'PROPERTY_SUBMITTED:bad:0', targetRole: 'LANDLORD'
  }), '/lessor');
  assert.equal(resolveNotificationActionTarget({
    actionType: 'OPEN_PUBLIC_PROPERTY', actionTarget: '/property/318'
  }), '/property/318');
});
