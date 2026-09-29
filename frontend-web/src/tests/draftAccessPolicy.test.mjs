import test from 'node:test';
import assert from 'node:assert/strict';
import { getGuestResumableDraftCount, readResumableDraftCount } from '../utils/draftAccessPolicy.ts';

test('guest badge counts only the current browser draft confirmed as resumable by the server', () => {
  assert.equal(getGuestResumableDraftCount('guest-1', { draftId: 'guest-1', status: 'DRAFT' }), 1);
  assert.equal(getGuestResumableDraftCount('guest-1', { draftId: 'guest-2', status: 'DRAFT' }), 0);
  assert.equal(getGuestResumableDraftCount('guest-1', { draftId: 'guest-1', status: 'SUBMITTED' }), 0);
  assert.equal(getGuestResumableDraftCount('guest-1', null), 0);
  assert.equal(getGuestResumableDraftCount(null, { draftId: 'guest-1', status: 'DRAFT' }), 0);
});

test('authenticated draft badge accepts only a valid server total count', () => {
  assert.equal(readResumableDraftCount(0), 0);
  assert.equal(readResumableDraftCount(24), 24);
  assert.equal(readResumableDraftCount(undefined), null);
  assert.equal(readResumableDraftCount(-1), null);
  assert.equal(readResumableDraftCount(1.5), null);
});
