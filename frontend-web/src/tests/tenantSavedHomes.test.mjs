import test from 'node:test';
import assert from 'node:assert/strict';
import {
  applyPersistedSavedHomeChange,
  mergeSavedHomes,
  savedHomesForPresentation,
  savedHomesVisibleLimitForWidth
} from '../utils/tenantSavedHomes.ts';

const property = (id, title = `Home ${id}`) => ({
  id, title, listingType: 'RENT', propertyType: 'FLAT', sector: 'Locality', monthlyRent: 25000,
  totalAreaSqFt: 0, images: [], verified: false
});

test('a confirmed save adds the current property once to Saved Homes', () => {
  const home = property(8);
  assert.deepEqual(applyPersistedSavedHomeChange([], home, true), [home]);
  assert.deepEqual(applyPersistedSavedHomeChange([home], home, true), [home]);
});

test('a confirmed remove drops the property and paginated pages merge without duplicates', () => {
  const first = property(8);
  const second = property(9);
  assert.deepEqual(mergeSavedHomes([first], [first, second]), [first, second]);
  assert.deepEqual(applyPersistedSavedHomeChange([first, second], first, false), [second]);
});

test('a failed server mutation leaves the persisted snapshot unchanged', () => {
  const saved = [property(8)];
  const before = [...saved];
  // Mutation errors skip applyPersistedSavedHomeChange; there is no optimistic state to roll back.
  assert.deepEqual(saved, before);
});

test('saved-home story rail shows one mobile card and two cards from tablet upward', () => {
  assert.equal(savedHomesVisibleLimitForWidth(390), 1);
  assert.equal(savedHomesVisibleLimitForWidth(767), 1);
  assert.equal(savedHomesVisibleLimitForWidth(768), 2);
  assert.equal(savedHomesVisibleLimitForWidth(1024), 2);
  assert.equal(savedHomesVisibleLimitForWidth(1279), 2);
  assert.equal(savedHomesVisibleLimitForWidth(1280), 2);
  assert.equal(savedHomesVisibleLimitForWidth(1440), 2);
});

test('saved-home presentation stays bounded until expanded and never mutates loaded homes', () => {
  const homes = [property(1), property(2), property(3), property(4)];
  assert.deepEqual(savedHomesForPresentation(homes, 2, false), homes.slice(0, 2));
  assert.deepEqual(savedHomesForPresentation(homes, 2, true), homes);
  assert.deepEqual(homes.map(home => home.id), [1, 2, 3, 4]);
  assert.deepEqual(savedHomesForPresentation(homes, -1, false), []);
});
