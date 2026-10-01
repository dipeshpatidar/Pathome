import test from 'node:test';
import assert from 'node:assert/strict';
import {
  applyFavoriteLookupFailure,
  applyFavoriteLookupSuccess,
  applyFavoriteMutation,
  fetchFavoriteIdsInBatches,
  isFavoriteLookupReadyFor,
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

test('known-home mutations preserve incremental lookup recovery until a retry resolves new IDs', async () => {
  const knownState = {
    identityKey: 'tenant-a', status: 'ready', lookupError: false,
    propertyIds: new Set([1, 2]), favoriteIds: new Set([2])
  };
  const allIds = [1, 2, 3, 4];
  const unresolvedBeforeFailure = unresolvedFavoriteIds(allIds, knownState.propertyIds);
  assert.deepEqual(unresolvedBeforeFailure, [3, 4]);

  await assert.rejects(fetchFavoriteIdsInBatches(unresolvedBeforeFailure, async () => {
    throw new Error('request failed');
  }));
  let state = applyFavoriteLookupFailure(knownState);

  assert.equal(state.lookupError, true);
  assert.equal(isFavoriteLookupReadyFor(state, 'tenant-a', 1), true);
  assert.equal(isFavoriteLookupReadyFor(state, 'tenant-a', 3), false);
  assert.deepEqual(unresolvedFavoriteIds(allIds, state.propertyIds), [3, 4]);

  state = applyFavoriteMutation(state, 1, true);
  assert.equal(state.favoriteIds.has(1), true);
  assert.equal(state.favoriteIds.has(2), true);
  assert.equal(state.lookupError, true);
  assert.equal(isFavoriteLookupReadyFor(state, 'tenant-a', 3), false);
  assert.deepEqual(unresolvedFavoriteIds(allIds, state.propertyIds), [3, 4]);

  const retryIds = unresolvedFavoriteIds(allIds, state.propertyIds);
  const retryResults = await fetchFavoriteIdsInBatches(retryIds, async batch => batch.filter(id => id === 3));
  state = applyFavoriteLookupSuccess(state, retryIds, retryResults);

  assert.equal(state.lookupError, false);
  assert.deepEqual(unresolvedFavoriteIds(allIds, state.propertyIds), []);
  assert.equal(isFavoriteLookupReadyFor(state, 'tenant-a', 3), true);
  assert.equal(isFavoriteLookupReadyFor(state, 'tenant-a', 4), true);
  assert.deepEqual([...state.favoriteIds].sort((a, b) => a - b), [1, 2, 3]);
});

test('a normal successful Favorite mutation does not create a lookup error', () => {
  const state = applyFavoriteMutation({
    identityKey: 'tenant-a', status: 'ready', lookupError: false,
    propertyIds: new Set([1]), favoriteIds: new Set()
  }, 1, true);

  assert.equal(state.lookupError, false);
  assert.deepEqual([...state.favoriteIds], [1]);
});
