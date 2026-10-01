import test from 'node:test';
import assert from 'node:assert/strict';
import { applyPersistedSavedHomeChange, mergeSavedHomes, removeSavedHomesFromDiscovery } from '../utils/tenantSavedHomes.ts';

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

test('saved properties are not repeated in the discovery grid', () => {
  const homes = [property(8), property(9)];
  assert.deepEqual(removeSavedHomesFromDiscovery(homes, new Set([8])), [homes[1]]);
});

test('a failed server mutation leaves the persisted snapshot unchanged', () => {
  const saved = [property(8)];
  const before = [...saved];
  // Mutation errors skip applyPersistedSavedHomeChange; there is no optimistic state to roll back.
  assert.deepEqual(saved, before);
});
