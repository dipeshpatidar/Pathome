import test from 'node:test';
import assert from 'node:assert/strict';
import {
  QUICK_REFINE_BHK_OPTIONS,
  QUICK_REFINE_FURNISHING,
  QUICK_REFINE_PROPERTY_TYPES,
  QUICK_REFINE_CHIP_REMOVE_TARGET_PX,
  QUICK_REFINE_RENT_SLIDER_MAX_POSITION,
  applyQuickRefineRentSliderChanges,
  clearQuickRefineFilters,
  quickRefineRentBounds,
  quickRefineRentToSliderPosition,
  quickRefineSliderPositionToRent,
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

test('each supported furnishing state has truthful selection, count, active chip, removal, and toggle behavior', () => {
  const expected = [
    ['UNFURNISHED', 'Unfurnished'],
    ['SEMI_FURNISHED', 'Semi-furnished'],
    ['FULLY_FURNISHED', 'Fully furnished'],
    ['FURNISHED', 'Any furnished']
  ];

  for (const [value, label] of expected) {
    const filters = { ...baseFilters, furnishing: value };
    assert.equal(quickRefineOptionIsSelected(filters, 'furnishing', value), true);
    assert.equal(quickRefineActiveCount(filters), 1);
    assert.deepEqual(quickRefineChipLabels(filters), [{ dimension: 'furnishing', label }]);
    const removed = updateQuickRefineFilter(filters, 'furnishing', value);
    assert.equal(removed.furnishing, undefined);
    assert.equal(quickRefineActiveCount(removed), 0);
  }

  const replaced = updateQuickRefineFilter({ ...baseFilters, furnishing: 'FURNISHED' },
    'furnishing', 'FULLY_FURNISHED');
  assert.equal(replaced.furnishing, 'FULLY_FURNISHED');
  assert.equal(quickRefineOptionIsSelected(replaced, 'furnishing', 'FURNISHED'), false);
  assert.equal(quickRefineOptionIsSelected(replaced, 'furnishing', 'FULLY_FURNISHED'), true);
});

test('mobile active-filter remove control uses the 44px minimum hit target', () => {
  assert.equal(QUICK_REFINE_CHIP_REMOVE_TARGET_PX, 44);
});

test('rent slider uses the accepted query domain rather than loaded or empty result pages', () => {
  const emptyResultBounds = quickRefineRentBounds(baseFilters);
  assert.deepEqual(emptyResultBounds, { min: 0, max: 10_000_500, step: 500 });
  // A loaded page containing only ₹30k–₹50k cards is not an input to this control domain.
  assert.deepEqual(quickRefineRentBounds(baseFilters), emptyResultBounds);

  const lowerRentSearch = { ...baseFilters, maxRent: 20_000 };
  const lowerRentBounds = quickRefineRentBounds(lowerRentSearch);
  const selected = updateQuickRefineRentSlider(lowerRentSearch, lowerRentBounds,
    quickRefineRentValues(lowerRentSearch, lowerRentBounds), 'max', 20_000);
  assert.equal(selected.filters.maxRent, 20_000);
  assert.deepEqual(quickRefineRentBounds(baseFilters), emptyResultBounds,
    'zero discovery results keep the same broad query domain');
});

test('rent slider preserves selected accepted values and gives its rightmost stop open-ended semantics', () => {
  const selectedFilters = { ...baseFilters, minRent: 9_999_999, maxRent: 10_000_000 };
  const bounds = quickRefineRentBounds(selectedFilters);
  assert.deepEqual(quickRefineRentValues(selectedFilters, bounds), { min: 9_999_999, max: 10_000_000 });

  const openEnded = updateQuickRefineRentSlider(baseFilters, bounds,
    { min: 0, max: bounds.max }, 'max', bounds.max);
  assert.equal(openEnded.values.max, bounds.max);
  assert.equal(openEnded.filters.maxRent, undefined);

  const lowerCeiling = updateQuickRefineRentSlider(baseFilters, bounds,
    { min: 0, max: bounds.max }, 'max', 20_000);
  assert.equal(lowerCeiling.filters.maxRent, 20_000);
  assert.ok(lowerCeiling.values.min <= lowerCeiling.values.max);

  const lowRentPosition = quickRefineRentToSliderPosition(20_000, bounds, 'max');
  assert.ok(lowRentPosition > 40 && lowRentPosition < 50,
    'common rents keep useful thumb travel instead of collapsing near the track origin');
  assert.ok(Math.abs(quickRefineSliderPositionToRent(lowRentPosition, bounds, 'max') - 20_000) <= 500);
  assert.equal(quickRefineSliderPositionToRent(0, bounds, 'max'), 500,
    'the maximum thumb starts at the backend-valid positive rent minimum');
  assert.equal(quickRefineSliderPositionToRent(QUICK_REFINE_RENT_SLIDER_MAX_POSITION, bounds, 'max'), bounds.max);
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
    'UNFURNISHED', 'SEMI_FURNISHED', 'FULLY_FURNISHED', 'FURNISHED'
  ]);
  assert.deepEqual(QUICK_REFINE_FURNISHING.map(option => option.label), [
    'Unfurnished', 'Semi-furnished', 'Fully furnished', 'Any furnished'
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
