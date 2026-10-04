import test from 'node:test';
import assert from 'node:assert/strict';
import {
  calculateUnreadCount,
  resolveNotificationActionLabel,
  resolveNotificationActionTarget
} from '../utils/notificationPolicy.ts';

test('notification read state counts only unread items', () => {
  assert.equal(calculateUnreadCount([]), 0);
  assert.equal(calculateUnreadCount([{ read: true }, { read: false }, { read: false }]), 2);
});

test('a visit eventKey alone is not parsed into an invented session deep-link', () => {
  assert.equal(resolveNotificationActionTarget({
    eventKey: 'VISIT_SESSION_RESCHEDULED:812:v7:44',
    targetRole: 'TENANT'
  }), '/');
});

test('explicit safe action targets remain intact and external targets fall back safely', () => {
  assert.equal(resolveNotificationActionTarget({ actionTarget: '/tenant?section=visits' }), '/tenant?section=visits');
  assert.equal(resolveNotificationActionTarget({ actionTarget: '//evil.example/path' }), '/');
  assert.equal(resolveNotificationActionTarget({ actionTarget: 'https://evil.example/path' }), '/');
});

test('review-change notification keeps its truthful action label', () => {
  assert.equal(resolveNotificationActionLabel({ actionType: 'REVIEW_CHANGES' }), 'Review changes');
});

test.todo('visit notification action label is truthful when no session action target is available');
test.todo('notification target-role presentation is verified for tenant visit events without weakening server identity binding');
