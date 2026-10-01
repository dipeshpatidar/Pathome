import test from 'node:test';
import assert from 'node:assert/strict';
import {
  QUICK_REFINE_BHK_OPTIONS,
  QUICK_REFINE_FURNISHING,
  QUICK_REFINE_PROPERTY_TYPES,
  applyQuickRefineRentSliderChanges,
  clearQuickRefineFilters,
  deriveQuickRefineRentBounds,
  mergeQuickRefineRentBounds,
  quickRefineRentValues,
  quickRefineActiveCount,
  quickRefineChipLabels,
  quickRefineOptionIsSelected,
  quickRefineResultSummary,
  updateQuickRefineFilter,
  updateQuickRefineRentSlider
} from '../utils/tenantQuickRefine.ts';
import { discoverySearchKey } from '../utils/rentalSearch.ts';

const baseFilters = { city: 'Indore', rentalOnly: true };

test('shared search state determines Quick Refine selection and active chips', () => {
  const filters = {
    ...baseFilters,
    bhk: '2BHK',
    propertyType: 'FLAT',
    furnishing: 'SEMI_FURNISHED',
    maxRent: 20000,
    sector: 'Vijay Nagar'
  };

  assert.equal(quickRefineOptionIsSelected(filters, 'bhk', '2 BHK'), true);
  assert.equal(quickRefineOptionIsSelected(filters, 'propertyType', 'FLAT'), true);
  assert.equal(quickRefineOptionIsSelected(filters, 'furnishing', 'SEMI_FURNISHED'), true);
  assert.deepEqual(quickRefineChipLabels(filters), [
    { dimension: 'bhk', label: '2 BHK' },
    { dimension: 'propertyType', label: 'Flat' },
    { dimension: 'furnishing', label: 'Semi-furnished' },
    { dimension: 'budget', label: 'Up to ₹20k' }
  ]);
  assert.equal(quickRefineActiveCount(filters), 4);
});

test('Quick Refine BHK changes use shared state and stay equivalent to Hero chip state', () => {
  const threeBhk = updateQuickRefineFilter({ ...baseFilters, bhk: '2BHK' }, 'bhk', '3 BHK');
  assert.equal(threeBhk.bhk, '3 BHK');
  assert.equal(quickRefineOptionIsSelected(threeBhk, 'bhk', '3 BHK'), true);
  assert.equal(quickRefineOptionIsSelected(threeBhk, 'bhk', '2 BHK'), false);

  const cleared = updateQuickRefineFilter(threeBhk, 'bhk', '3 BHK');
  assert.equal(cleared.bhk, undefined);
});

test('property type and furnishing refinements update the single shared selection', () => {
  const withType = updateQuickRefineFilter(baseFilters, 'propertyType', 'SERVICED_APARTMENT');
  assert.equal(withType.propertyType, 'SERVICED_APARTMENT');
  assert.equal(quickRefineOptionIsSelected(withType, 'propertyType', 'SERVICED_APARTMENT'), true);
  const withFurnishing = updateQuickRefineFilter(withType, 'furnishing', 'UNFURNISHED');
  assert.equal(withFurnishing.propertyType, 'SERVICED_APARTMENT');
  assert.equal(withFurnishing.furnishing, 'UNFURNISHED');
});

test('rent slider derives a useful display ceiling from loaded rents and preserves it as results narrow', () => {
  const observed = deriveQuickRefineRentBounds([15000, 17500, 79500, -1, Number.NaN], baseFilters);
  assert.deepEqual(observed, { min: 15000, max: 80000, step: 500 });
  assert.deepEqual(mergeQuickRefineRentBounds(observed, deriveQuickRefineRentBounds([25000], baseFilters)), observed);
  assert.deepEqual(deriveQuickRefineRentBounds([], { ...baseFilters, minRent: 15000 }), {
    min: 15000, max: 20000, step: 500
  });
});

test('dual-thumb slider converts to shared min/max filters and never allows min rent above max rent', () => {
  const bounds = { min: 0, max: 80000, step: 500 };
  const initial = { ...baseFilters, minRent: 15000, maxRent: 60000 };
  const values = quickRefineRentValues(initial, bounds);
  assert.deepEqual(values, { min: 15000, max: 60000 });

  const raisedMin = updateQuickRefineRentSlider(initial, bounds, values, 'min', 70000);
  assert.deepEqual(raisedMin.values, { min: 60000, max: 60000 });
  assert.equal(raisedMin.filters.minRent, 60000);
  assert.equal(raisedMin.filters.maxRent, 60000);
  assert.ok(raisedMin.filters.minRent <= raisedMin.filters.maxRent);

  const loweredMax = updateQuickRefineRentSlider(initial, bounds, values, 'max', 10000);
  assert.deepEqual(loweredMax.values, { min: 15000, max: 15000 });
  assert.equal(loweredMax.filters.minRent, 15000);
  assert.equal(loweredMax.filters.maxRent, 15000);

  const applied = applyQuickRefineRentSliderChanges(baseFilters, bounds, { min: 25000, max: 55000 }, ['min', 'max']);
  assert.equal(applied.minRent, 25000);
  assert.equal(applied.maxRent, 55000);
  assert.deepEqual(quickRefineRentValues({ ...baseFilters, minRent: 50000, maxRent: 20000 }, bounds), {
    min: 20000, max: 20000
  });
});

test('Clear all preserves City and existing clear behavior removes locality and parsed query state', () => {
  const cleared = clearQuickRefineFilters({
    city: 'Indore', sector: 'Vijay Nagar', q: '2bhk flat in Vijay Nagar', bhk: '2 BHK',
    propertyType: 'FLAT', maxRent: 20000, rentalOnly: true
  });
  assert.deepEqual(cleared, { city: 'Indore', rentalOnly: true });
  assert.deepEqual(clearQuickRefineFilters({ q: 'Flat', rentalOnly: true }, 'Bhopal'), {
    city: 'Bhopal', rentalOnly: true
  });
});

test('refinement changes create a new discovery key so existing pagination resets', () => {
  const before = discoverySearchKey({ ...baseFilters, bhk: '2 BHK' });
  const changed = updateQuickRefineFilter({ ...baseFilters, bhk: '2 BHK' }, 'bhk', '3 BHK');
  assert.notEqual(discoverySearchKey(changed), before);
});

test('only supported exact BHK, rental property types, and furnishing values are exposed', () => {
  assert.deepEqual(QUICK_REFINE_BHK_OPTIONS.map(option => option.value), ['1 RK', '1 BHK', '2 BHK', '3 BHK', '4 BHK']);
  assert.equal(QUICK_REFINE_BHK_OPTIONS.some(option => option.label === '4+'), false);
  assert.deepEqual(QUICK_REFINE_PROPERTY_TYPES.map(option => option.value), [
    'FLAT', 'HOUSE', 'PENTHOUSE', 'STUDIO', 'SERVICED_APARTMENT'
  ]);
  assert.deepEqual(QUICK_REFINE_FURNISHING.map(option => option.value), [
    'UNFURNISHED', 'SEMI_FURNISHED', 'FURNISHED'
  ]);
  assert.equal(quickRefineActiveCount({ ...baseFilters, bhk: '4+', propertyType: 'FLAT' }), 1);
});

test('available-home summary reports only homes loaded for the current authoritative search', () => {
  const filters = { ...baseFilters, propertyType: 'FLAT' };
  const key = discoverySearchKey(filters);
  assert.equal(quickRefineResultSummary(filters, 'READY', key, 6), '6 homes loaded');
  assert.equal(quickRefineResultSummary(filters, 'READY', key, 1), '1 home loaded');
  assert.equal(quickRefineResultSummary(filters, 'READY', 'stale-search-key', 6), 'Updating available homes…');
  assert.equal(quickRefineResultSummary(filters, 'LOADING', key, 6), 'Updating available homes…');
  assert.equal(quickRefineResultSummary(filters, 'ERROR', key, 6), 'Homes couldn’t be refreshed.');
  assert.equal(quickRefineResultSummary(filters, 'READY', key, 0), 'No homes match this search.');
});
