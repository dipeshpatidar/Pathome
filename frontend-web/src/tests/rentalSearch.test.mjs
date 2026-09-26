import test from 'node:test';
import assert from 'node:assert/strict';
import {
  buildRentalSearchFilters,
  discoverySearchKey,
  mapRentalSuggestion,
  normalizeSearchText,
  parseRentalPropertyType,
  parseRentalFurnishing,
  parseRentFilter,
  selectionAfterCityChange,
  shouldSurfaceSuggestionFailure,
  suggestionFailureDiagnostic,
  SuggestionRequestError,
  suggestionFromStructuredFilters
} from '../utils/rentalSearch.ts';

test('maps only public structured suggestion fields with an actual location', () => {
  const mapped = mapRentalSuggestion({
    type: 'SEARCH_QUERY', label: '4 BHK in Vijay Nagar, Indore', city: 'Indore',
    locality: 'Vijay Nagar', bhk: '4BHK', resultCount: 3, ownerPhone: 'private'
  });
  assert.deepEqual(mapped, {
    type: 'SEARCH_QUERY', label: '4 BHK in Vijay Nagar, Indore', city: 'Indore',
    locality: 'Vijay Nagar', bhk: '4BHK', propertyType: null, furnishing: null,
    minRent: null, maxRent: null, resultCount: 3
  });
  assert.equal(mapRentalSuggestion({ type: 'LOCALITY', label: 'Invalid', city: 'Indore' }), null);
  assert.equal(mapRentalSuggestion({ type: 'LANDMARK', label: 'Invented', city: 'Indore' }), null);
});

test('selected suggestion submits structured city, locality and BHK', () => {
  const selection = mapRentalSuggestion({
    type: 'SEARCH_QUERY', label: '4 BHK in Vijay Nagar, Indore', city: 'Indore',
    locality: 'Vijay Nagar', bhk: '4BHK', resultCount: 3
  });
  assert.deepEqual(buildRentalSearchFilters('Indore', selection.label, selection), {
    city: 'Indore', sector: 'Vijay Nagar', bhk: '4BHK', rentalOnly: true
  });
  assert.deepEqual(buildRentalSearchFilters('Indore', '4 BHK in Nipania', selection), {
    city: 'Indore', q: '4 BHK in Nipania', rentalOnly: true
  });
  const restored = suggestionFromStructuredFilters('Indore', 'Vijay Nagar', '4BHK');
  assert.deepEqual(buildRentalSearchFilters('Indore', restored.label, restored), {
    city: 'Indore', sector: 'Vijay Nagar', bhk: '4BHK', rentalOnly: true
  });
  assert.equal(restored.resultCount, null);
  assert.equal(restored.propertyType, null);
});

test('changing city invalidates an incompatible selection and free text remains searchable', () => {
  const selection = mapRentalSuggestion({
    type: 'LOCALITY', label: 'Vijay Nagar, Indore', city: 'Indore', locality: 'Vijay Nagar', resultCount: 2
  });
  assert.equal(selectionAfterCityChange(selection, 'Pune'), null);
  assert.equal(selectionAfterCityChange(selection, 'indore'), selection);
  assert.deepEqual(buildRentalSearchFilters('Pune', '  2   bhk in Baner ', null), {
    city: 'Pune', q: '2 bhk in Baner', rentalOnly: true
  });
  assert.equal(normalizeSearchText('  vijay   nagar '), 'vijay nagar');
});

test('discovery state key includes query, BHK and rental mode for restoration and pagination', () => {
  const base = { city: 'Indore', sector: 'Vijay Nagar', rentalOnly: false };
  assert.notEqual(discoverySearchKey(base), discoverySearchKey({ ...base, bhk: '4BHK', rentalOnly: true }));
  assert.notEqual(discoverySearchKey(base), discoverySearchKey({ ...base, q: '3 bhk' }));
  assert.equal(discoverySearchKey({ ...base, city: 'INDORE' }), discoverySearchKey(base));
  assert.notEqual(discoverySearchKey({ ...base, propertyType: 'FLAT' }), discoverySearchKey(base));
  assert.notEqual(discoverySearchKey({ ...base, maxRent: 25000 }), discoverySearchKey(base));
  assert.notEqual(discoverySearchKey({ ...base, furnishing: 'SEMI_FURNISHED' }), discoverySearchKey(base));
});

test('structured property type survives suggestion selection and URL restoration', () => {
  const selected = mapRentalSuggestion({
    type: 'SEARCH_QUERY', label: '2 BHK Flat in Vijay Nagar, Indore', city: 'Indore',
    locality: 'Vijay Nagar', bhk: '2BHK', propertyType: 'FLAT', resultCount: 2
  });
  assert.deepEqual(buildRentalSearchFilters('Indore', selected.label, selected), {
    city: 'Indore', sector: 'Vijay Nagar', bhk: '2BHK', propertyType: 'FLAT', rentalOnly: true
  });
  assert.deepEqual(buildRentalSearchFilters('Indore', '2bhk flat vijay nagar', selected), {
    city: 'Indore', q: '2bhk flat vijay nagar', rentalOnly: true
  });
  const restored = suggestionFromStructuredFilters('Indore', 'Vijay Nagar', '2BHK', 'FLAT');
  assert.equal(restored.label, '2 BHK Flat in Vijay Nagar, Indore');
  assert.equal(restored.propertyType, 'FLAT');
  assert.equal(suggestionFromStructuredFilters('Indore', 'Vijay Nagar', undefined, 'FLAT').label,
    'Flat in Vijay Nagar, Indore');
  assert.equal(parseRentalPropertyType('FLAT'), 'FLAT');
  assert.equal(parseRentalPropertyType('__proto__'), undefined);
});

test('furnishing and rent are structured separately from public display text', () => {
  const selected = mapRentalSuggestion({
    type: 'SEARCH_QUERY', label: '2 BHK Flat, Semi-furnished in Vijay Nagar, Indore · up to ₹25,000',
    city: 'Indore', locality: 'Vijay Nagar', bhk: '2BHK', propertyType: 'FLAT',
    furnishing: 'SEMI_FURNISHED', minRent: null, maxRent: 25000, resultCount: 2
  });
  assert.deepEqual(buildRentalSearchFilters('Indore', selected.label, selected), {
    city: 'Indore', sector: 'Vijay Nagar', bhk: '2BHK', propertyType: 'FLAT',
    furnishing: 'SEMI_FURNISHED', maxRent: 25000, rentalOnly: true
  });
  const restored = suggestionFromStructuredFilters('Indore', 'Vijay Nagar', '2BHK', 'FLAT',
    'SEMI_FURNISHED', undefined, 25000);
  assert.equal(restored.label, selected.label);
  assert.equal(parseRentalFurnishing('SEMI_FURNISHED'), 'SEMI_FURNISHED');
  assert.equal(parseRentFilter('25000'), 25000);
  assert.equal(parseRentFilter('78abc'), undefined);
});

test('aborted and stale suggestion failures are silent; real errors have safe diagnostics', () => {
  const aborted = new Error('aborted');
  aborted.name = 'AbortError';
  assert.equal(shouldSurfaceSuggestionFailure(aborted, false, true), false);
  assert.equal(shouldSurfaceSuggestionFailure(new SuggestionRequestError('network'), true, true), false);
  assert.equal(shouldSurfaceSuggestionFailure(new SuggestionRequestError('http', 503), false, false), false);
  assert.equal(shouldSurfaceSuggestionFailure(new SuggestionRequestError('http', 503), false, true), true);
  assert.deepEqual(suggestionFailureDiagnostic(new SuggestionRequestError('http', 503), 6), {
    endpoint: '/api/v1/properties/search-suggestions', category: 'http', status: 503, queryLength: 6
  });
  assert.equal(suggestionFailureDiagnostic(new Error('secret'), 5).category, 'unexpected');
});
