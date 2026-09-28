import test from 'node:test';
import assert from 'node:assert/strict';
import {
  readNotificationIdentity, visibleNotificationHistory, isCurrentNotificationRequest
} from '../utils/notificationSession.ts';

test('switching from user A to user B hides A history and unread items immediately', () => {
  const a = '1:token-a';
  const b = '2:token-b';
  const history = [{ id: 'a-notification', read: false }];
  assert.equal(visibleNotificationHistory(history, a, a).length, 1);
  assert.deepEqual(visibleNotificationHistory(history, a, b), []);
  assert.equal(visibleNotificationHistory(history, a, b).filter(item => !item.read).length, 0);
});

test('a stale user A response cannot be accepted after identity or generation changes', () => {
  assert.equal(isCurrentNotificationRequest('1:token-a', 1, '2:token-b', 2), false);
  assert.equal(isCurrentNotificationRequest('1:token-a', 1, '1:token-a', 2), false);
  assert.equal(isCurrentNotificationRequest('2:token-b', 2, '2:token-b', 2), true);
});

test('logout removes history and unread count even before state cleanup renders', () => {
  const history = [{ id: 'a-notification', read: false }];
  assert.deepEqual(visibleNotificationHistory(history, '1:token-a', null), []);
  assert.equal(visibleNotificationHistory(history, '1:token-a', null).filter(item => !item.read).length, 0);
});

test('identity comes from authenticated user and token together', () => {
  const values = new Map([
    ['pathome_auth_token', 'token-a'], ['pathome_user', JSON.stringify({ id: 1 })]
  ]);
  globalThis.localStorage = { getItem: key => values.get(key) ?? null };
  assert.equal(readNotificationIdentity(), '1:token-a');
  values.set('pathome_user', JSON.stringify({ id: 2 }));
  assert.equal(readNotificationIdentity(), '2:token-a');
  values.delete('pathome_auth_token');
  assert.equal(readNotificationIdentity(), null);
  delete globalThis.localStorage;
});
