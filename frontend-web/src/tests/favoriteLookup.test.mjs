import test from 'node:test';
import assert from 'node:assert/strict';
import {
  fetchFavoriteIdsInBatches,
  mergeFavoriteLookupState,
  unresolvedFavoriteIds
} from '../utils/favoriteLookup.ts';

test('favorite lookup keeps a full 100-ID batch in one request', async () => {
  const calls = [];
  const ids = Array.from({ length: 100 }, (_, index) => index + 1);
  const result = await fetchFavoriteIdsInBatches(ids, async batch => {
    calls.push(batch);
    return [batch[0], batch.at(-1)];
  });

  assert.equal(calls.length, 1);
  assert.equal(calls[0].length, 100);
  assert.deepEqual(result, [1, 100]);
});

test('101+ favorite IDs are split into bounded batches and combined once', async () => {
  const calls = [];
  const ids = Array.from({ length: 251 }, (_, index) => index + 1);
  const result = await fetchFavoriteIdsInBatches(ids, async batch => {
    calls.push(batch);
    return batch.filter(id => id % 2 === 0);
  });

  assert.deepEqual(calls.map(batch => batch.length), [100, 100, 51]);
  assert.ok(calls.every(batch => batch.length <= 100));
  assert.equal(result.length, 125);
  assert.equal(new Set(result).size, 125);
});

test('incremental lookup excludes already resolved and duplicate property IDs', () => {
  assert.deepEqual(unresolvedFavoriteIds([1, 2, 2, 3, 4, 4], new Set([1, 3])), [2, 4]);
});

test('favorite results merge without dropping previously resolved homes', () => {
  const merged = mergeFavoriteLookupState(
    new Set([1, 2]), new Set([2]), [3, 4, 4], [4]
  );

  assert.deepEqual([...merged.resolvedIds].sort((a, b) => a - b), [1, 2, 3, 4]);
  assert.deepEqual([...merged.favoriteIds], [2, 4]);
});

test('a failed added batch leaves the caller’s previous Favorite state intact', async () => {
  const resolvedIds = new Set([1, 2]);
  const favoriteIds = new Set([2]);

  await assert.rejects(fetchFavoriteIdsInBatches([3, 4], async () => {
    throw new Error('request failed');
  }));

  assert.deepEqual([...resolvedIds], [1, 2]);
  assert.deepEqual([...favoriteIds], [2]);
});
