import test from 'node:test';
import assert from 'node:assert/strict';
import {
  activateNotificationItem,
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

test('tenant visit notification routes to My Visits without inventing a session deep-link', () => {
  const item = { category: 'VISIT_SESSION', targetRole: 'TENANT', eventKey: 'VISIT_SESSION_RESCHEDULED:812:v7:44' };
  assert.equal(resolveNotificationActionLabel(item), 'View My Visits');
  assert.equal(resolveNotificationActionTarget(item), '/tenant#visit-history');
  assert.equal(resolveNotificationActionTarget({ ...item, eventKey: 'arbitrary' }), '/tenant#visit-history');
});

test('server-derived tenant visit target keeps the recipient route after role metadata removal', async () => {
  const item = { id: 'db-12', read: false, title: 'Visit updated', message: 'Review your visit', category: 'VISIT_SESSION', actionTarget: '/tenant#visit-history' };
  assert.equal(resolveNotificationActionLabel(item), 'View My Visits');
  assert.equal(resolveNotificationActionTarget(item), '/tenant#visit-history');
  const calls = [];
  await activateNotificationItem(item, {
    markAsRead: async id => { calls.push(`read:${id}`); },
    closeDrawer: () => { calls.push('close'); },
    navigate: target => { calls.push(`navigate:${target}`); }
  });
  assert.deepEqual(calls, ['read:db-12', 'close', 'navigate:/tenant#visit-history']);
});

test('tenant visit presentation is not applied to another recipient role', () => {
  const item = { category: 'VISIT_SESSION', targetRole: 'GE' };
  assert.equal(resolveNotificationActionLabel(item), 'View update');
  assert.notEqual(resolveNotificationActionTarget(item), '/tenant#visit-history');
});

test('tenant visit with no deep-link metadata marks read and opens My Visits', async () => {
  const calls = [];
  await activateNotificationItem({ id: 'n1', read: false, title: 'Visit update', message: 'Review your visit', category: 'VISIT_SESSION', targetRole: 'TENANT' }, {
    markAsRead: async id => { calls.push(`read:${id}`); },
    closeDrawer: () => { calls.push('close'); },
    navigate: target => { calls.push(`navigate:${target}`); }
  });
  assert.deepEqual(calls, ['read:n1', 'close', 'navigate:/tenant#visit-history']);
});

test('another role visit eventKey alone marks read without inventing a destination', async () => {
  const calls = [];
  await activateNotificationItem({ id: 'n2', read: false, title: 'Field update', message: 'Visit changed', category: 'VISIT_SESSION', targetRole: 'GE', eventKey: 'VISIT_SESSION_RESCHEDULED:812:v7:44' }, {
    markAsRead: async id => { calls.push(`read:${id}`); },
    closeDrawer: () => { calls.push('close'); },
    navigate: target => { calls.push(`navigate:${target}`); }
  });
  assert.deepEqual(calls, ['read:n2']);
});
