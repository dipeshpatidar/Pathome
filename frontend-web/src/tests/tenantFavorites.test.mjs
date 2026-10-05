import test from 'node:test';
import assert from 'node:assert/strict';
import {
  applyFavoriteChanges,
  applyTenantFavoriteChanged,
  loadAllSavedPropertyIds
} from '../utils/tenantFavorites.ts';

test('shell favorites loader paginates the authenticated saved-properties source and deduplicates IDs', async () => {
  const requests = [];
  const ids = await loadAllSavedPropertyIds(async (page, signal) => {
    requests.push({ page, aborted: signal.aborted });
    return page === 0
      ? { properties: [{ id: 7 }, { id: 8 }, { id: 8 }, { id: 0 }], hasMore: true }
      : { properties: [{ id: 9 }, { id: -1 }], hasMore: false };
  }, new AbortController().signal);

  assert.deepEqual(requests, [{ page: 0, aborted: false }, { page: 1, aborted: false }]);
  assert.deepEqual([...ids], [7, 8, 9]);
});

test('shell favorites loader stops between pages when the authenticated session is aborted', async () => {
  const controller = new AbortController();
  let calls = 0;
  await assert.rejects(loadAllSavedPropertyIds(async () => {
    calls += 1;
    controller.abort();
    return { properties: [{ id: 5 }], hasMore: true };
  }, controller.signal), { name: 'AbortError' });
  assert.equal(calls, 1);
});

test('favorite events update only the matching authenticated snapshot and queued changes are applied once', () => {
  const snapshot = { identityKey: 'tenant:4:session-a', status: 'ready', propertyIds: new Set([11, 12]) };
  const stale = applyTenantFavoriteChanged(snapshot, 'tenant:4:session-b', 13, true);
  assert.equal(stale, snapshot);
  assert.equal(applyTenantFavoriteChanged({ ...snapshot, status: 'loading' }, 'tenant:4:session-a', 13, true).propertyIds.has(13), false);

  const saved = applyTenantFavoriteChanged(snapshot, 'tenant:4:session-a', 13, true);
  const removed = applyTenantFavoriteChanged(saved, 'tenant:4:session-a', 11, false);
  assert.deepEqual([...removed.propertyIds], [12, 13]);

  const duringLoad = applyFavoriteChanges(new Set([11, 12]), new Map([[11, false], [14, true]]));
  assert.deepEqual([...duringLoad], [12, 14]);
});
