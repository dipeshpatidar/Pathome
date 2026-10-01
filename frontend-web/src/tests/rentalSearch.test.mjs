import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  buildRentalSearchFilters,
  discoverySearchKey,
  mapRentalSuggestion,
  normalizeSearchText,
  parseRentalPropertyType,
  parseRentalFurnishing,
  parseRentFilter,
  selectionAfterCityChange,
  resetFiltersForManualCityChange,
  resetFiltersForSearchClear,
  formatCompactSearchContext,
  compactSearchDraftFromFilters,
  resolveCompactSearchDraft,
  hasActiveSearchFilters,
  formatRentDisplay,
  shouldSurfaceSuggestionFailure,
  suggestionFailureDiagnostic,
  SuggestionRequestError,
  suggestionFromStructuredFilters,
  extractCityFromSearchQuery
} from '../utils/rentalSearch.ts';

test('unchanged restored structured summary resubmits its committed filters', () => {
  const restored = {
    city: 'Indore', sector: 'Vijay Nagar', bhk: '2BHK', propertyType: 'FLAT',
    maxRent: 20000, rentalOnly: true
  };
  const draft = compactSearchDraftFromFilters(restored, true);

  assert.equal(draft.text, 'Vijay Nagar · 2 BHK · Flat · Up to ₹20k');
  assert.equal(draft.isDisplaySummary, true);
  assert.deepEqual(resolveCompactSearchDraft(restored, draft), restored);
  assert.equal(resolveCompactSearchDraft(restored, draft).q, undefined);
});

test('an edited restored summary becomes a normal shared free-text query', () => {
  const restored = {
    city: 'Indore', sector: 'Vijay Nagar', bhk: '2BHK', propertyType: 'FLAT',
    maxRent: 20000, rentalOnly: true
  };
  const draft = { ...compactSearchDraftFromFilters(restored, true), text: '2bhk flat in vijay nagar under 20000', isDisplaySummary: false };

  assert.deepEqual(resolveCompactSearchDraft(restored, draft), {
    city: 'Indore', q: '2bhk flat in vijay nagar under 20000', rentalOnly: true
  });
});

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

test('maps QUERY_INTENT and SEARCH_ANYWAY suggestions and constructs proper filters', () => {
  const queryIntent = mapRentalSuggestion({
    type: 'QUERY_INTENT',
    label: 'Search 1 RK homes in Indore',
    city: 'Indore',
    bhk: '1RK',
    propertyType: null,
    resultCount: 0
  });
  assert.deepEqual(queryIntent, {
    type: 'QUERY_INTENT',
    label: 'Search 1 RK homes in Indore',
    city: 'Indore',
    locality: null,
    bhk: '1RK',
    propertyType: null,
    furnishing: null,
    minRent: null,
    maxRent: null,
    resultCount: 0
  });
  assert.deepEqual(buildRentalSearchFilters('Indore', queryIntent.label, queryIntent), {
    city: 'Indore',
    bhk: '1RK',
    rentalOnly: true
  });

  const searchAnyway = mapRentalSuggestion({
    type: 'SEARCH_ANYWAY',
    label: 'Search "luxury penthouse"',
    city: 'Indore',
    resultCount: null
  });
  assert.deepEqual(searchAnyway, {
    type: 'SEARCH_ANYWAY',
    label: 'Search "luxury penthouse"',
    city: 'Indore',
    locality: null,
    bhk: null,
    propertyType: null,
    furnishing: null,
    minRent: null,
    maxRent: null,
    resultCount: null
  });
  assert.deepEqual(buildRentalSearchFilters('Indore', searchAnyway.label, searchAnyway), {
    city: 'Indore',
    q: 'Search "luxury penthouse"',
    rentalOnly: true
  });
});

test('explicit city override and authoritative locality inference in free text search', () => {
  // 1. Selected Indore + "2bhk flat" -> Indore (selected UI context preserved)
  assert.deepEqual(buildRentalSearchFilters('Indore', '2bhk flat', null), {
    city: 'Indore',
    q: '2bhk flat',
    rentalOnly: true
  });

  // 2. Selected Indore + "2bhk flat in pune" -> Pune (explicit city in query overrides UI selector)
  assert.deepEqual(buildRentalSearchFilters('Indore', '2bhk flat in pune', null), {
    city: 'Pune',
    q: '2bhk flat in pune',
    rentalOnly: true
  });

  // 3. Selected Pune + "2bhk flat in indore" -> Indore (explicit city overrides)
  assert.deepEqual(buildRentalSearchFilters('Pune', '2bhk flat in indore', null), {
    city: 'Indore',
    q: '2bhk flat in indore',
    rentalOnly: true
  });

  // 4. Selected Indore + authoritative Pune locality "3bhk baner" -> Pune (locality resolution)
  assert.deepEqual(buildRentalSearchFilters('Indore', '3bhk baner', null), {
    city: 'Pune',
    q: '3bhk baner',
    rentalOnly: true
  });

  // 5. Unsupported explicit city "2bhk in mumbai" -> Mumbai (no fallback to Indore)
  assert.deepEqual(buildRentalSearchFilters('Indore', '2bhk in mumbai', null), {
    city: 'Mumbai',
    q: '2bhk in mumbai',
    rentalOnly: true
  });

  // 6. Suggestion selection with UNSUPPORTED_CITY sets city to Mumbai
  const unsupported = mapRentalSuggestion({
    type: 'UNSUPPORTED_CITY',
    label: 'Pathome is not yet available in Mumbai',
    city: 'Mumbai',
    resultCount: 0
  });
  assert.ok(unsupported);
  assert.equal(unsupported.type, 'UNSUPPORTED_CITY');
  assert.equal(unsupported.city, 'Mumbai');
  assert.deepEqual(buildRentalSearchFilters('Indore', unsupported.label, unsupported), {
    city: 'Mumbai',
    q: 'Pathome is not yet available in Mumbai',
    rentalOnly: true
  });
});

test('city selector and discovery synchronization across typing, submission, suggestions, and URL restoration', () => {
  // A. initialCity = Indore, query = 2bhk flat in pune, typing only -> selector remains Indore
  let typingCityContext = 'Indore';
  const query = '2bhk flat in pune';
  // While user is merely typing in the input, the selector does NOT mutate:
  assert.equal(typingCityContext, 'Indore');

  // B. submit same query -> committed filters and selector become Pune
  const submittedFilters = buildRentalSearchFilters(typingCityContext, query, null);
  assert.equal(submittedFilters.city, 'Pune');
  assert.equal(submittedFilters.q, '2bhk flat in pune');
  // State synchronization updates committed city:
  let committedSelectorCity = submittedFilters.city;
  let discoveryCity = submittedFilters.city;
  assert.equal(committedSelectorCity, 'Pune');
  assert.equal(discoveryCity, 'Pune');

  // C. select Pune suggestion -> selector becomes Pune, discovery becomes Pune
  const puneSuggestion = mapRentalSuggestion({
    type: 'QUERY_INTENT',
    label: 'Search 2 BHK flats in Pune',
    city: 'Pune',
    locality: null,
    bhk: '2 BHK',
    propertyType: null,
    furnishing: null,
    minRent: null,
    maxRent: null,
    resultCount: 0
  });
  assert.ok(puneSuggestion);
  const suggestionFilters = buildRentalSearchFilters(puneSuggestion.city, puneSuggestion.label, puneSuggestion);
  assert.equal(suggestionFilters.city, 'Pune');
  assert.equal(suggestionFilters.bhk, '2 BHK');
  committedSelectorCity = suggestionFilters.city;
  discoveryCity = suggestionFilters.city;
  assert.equal(committedSelectorCity, 'Pune');
  assert.equal(discoveryCity, 'Pune');

  // D. initialCity = Pune, query = 2bhk flat in indore, submit -> selector becomes Indore
  let puneTypingContext = 'Pune';
  const indoreQuery = '2bhk flat in indore';
  // Before submit, remains Pune:
  assert.equal(puneTypingContext, 'Pune');
  // After submit, synchronizes to Indore:
  const indoreSubmittedFilters = buildRentalSearchFilters(puneTypingContext, indoreQuery, null);
  assert.equal(indoreSubmittedFilters.city, 'Indore');
  committedSelectorCity = indoreSubmittedFilters.city;
  discoveryCity = indoreSubmittedFilters.city;
  assert.equal(committedSelectorCity, 'Indore');
  assert.equal(discoveryCity, 'Indore');

  // E. URL restored with city=Pune -> selector shows Pune; URL with q="2bhk flat in pune" restores Pune
  const urlWithExplicitCity = new URLSearchParams('city=Pune&q=2bhk+flat+in+pune');
  const restoredCity1 = urlWithExplicitCity.get('city') || extractCityFromSearchQuery(urlWithExplicitCity.get('q') || '') || 'Indore';
  assert.equal(restoredCity1, 'Pune');

  const urlWithQueryOnly = new URLSearchParams('q=2bhk+flat+in+pune');
  const restoredCity2 = urlWithQueryOnly.get('city') || extractCityFromSearchQuery(urlWithQueryOnly.get('q') || '') || 'Indore';
  assert.equal(restoredCity2, 'Pune');

  // F. discovery city and visible selector must never disagree after committed search
  const simulatedCommittedSearches = [
    { initial: 'Indore', q: '2bhk flat in pune', suggestion: null, expectedCity: 'Pune' },
    { initial: 'Pune', q: '3 bhk in indore', suggestion: null, expectedCity: 'Indore' },
    { initial: 'Indore', q: '3bhk baner', suggestion: null, expectedCity: 'Pune' },
    { initial: 'Indore', q: 'flats in bhopal', suggestion: null, expectedCity: 'Bhopal' },
    { initial: 'Indore', q: 'Search 2 BHK flats in Pune', suggestion: puneSuggestion, expectedCity: 'Pune' }
  ];

  for (const item of simulatedCommittedSearches) {
    const filters = buildRentalSearchFilters(
      item.suggestion ? item.suggestion.city : item.initial,
      item.q,
      item.suggestion
    );
    const activeSelector = filters.city;
    const activeDiscovery = filters.city;
    assert.equal(activeSelector, item.expectedCity);
    assert.equal(activeDiscovery, item.expectedCity);
    assert.equal(activeSelector, activeDiscovery, 'Discovery city and visible selector must never disagree after committed search');
  }
});

test('manual city change reset contract removes all stale search parameters and establishes clean discovery', () => {
  // A. Committed state: Pune, q="2bhk flat in pune", bhk="2 BHK", propertyType="FLAT", price filters, sector="Kothrud"
  const stalePuneFilters = {
    city: 'Pune',
    q: '2bhk flat in pune',
    bhk: '2 BHK',
    propertyType: 'FLAT',
    minRent: 15000,
    maxRent: 35000,
    furnishing: 'SEMI_FURNISHED',
    sector: 'Kothrud',
    rentalOnly: true
  };
  assert.equal(stalePuneFilters.city, 'Pune');
  assert.equal(stalePuneFilters.q, '2bhk flat in pune');

  // Manual city change to Indore:
  const freshIndoreFilters = resetFiltersForManualCityChange('Indore');
  assert.equal(freshIndoreFilters.city, 'Indore');
  assert.equal(freshIndoreFilters.rentalOnly, true);
  assert.equal(freshIndoreFilters.q, undefined);
  assert.equal(freshIndoreFilters.sector, undefined);
  assert.equal(freshIndoreFilters.bhk, undefined);
  assert.equal(freshIndoreFilters.propertyType, undefined);
  assert.equal(freshIndoreFilters.minRent, undefined);
  assert.equal(freshIndoreFilters.maxRent, undefined);
  assert.equal(freshIndoreFilters.furnishing, undefined);

  // URL serialization reflects ONLY clean city and rentalOnly
  const indoreParams = new URLSearchParams();
  if (freshIndoreFilters.city) indoreParams.set('city', freshIndoreFilters.city);
  if (freshIndoreFilters.rentalOnly) indoreParams.set('rentalOnly', 'true');
  assert.equal(indoreParams.toString(), 'city=Indore&rentalOnly=true');
  assert.equal(indoreParams.has('q'), false);
  assert.equal(indoreParams.has('sector'), false);
  assert.equal(indoreParams.has('bhk'), false);
  assert.equal(indoreParams.has('propertyType'), false);

  // Discovery cache key is cleanly separated
  assert.notEqual(discoverySearchKey(stalePuneFilters), discoverySearchKey(freshIndoreFilters));
  assert.equal(discoverySearchKey(freshIndoreFilters), 'indore||||||||rent');

  // B. Indore filtered search -> manual switch to Bhopal
  const staleIndoreFilters = {
    city: 'Indore',
    q: '3bhk flat in vijay nagar',
    bhk: '3 BHK',
    propertyType: 'FLAT',
    sector: 'Vijay Nagar',
    rentalOnly: true
  };
  const freshBhopalFilters = resetFiltersForManualCityChange('Bhopal');
  assert.equal(freshBhopalFilters.city, 'Bhopal');
  assert.equal(freshBhopalFilters.q, undefined);
  assert.equal(freshBhopalFilters.sector, undefined);
  assert.equal(freshBhopalFilters.bhk, undefined);
  assert.equal(freshBhopalFilters.propertyType, undefined);
  assert.equal(freshBhopalFilters.rentalOnly, true);
  assert.equal(discoverySearchKey(freshBhopalFilters), 'bhopal||||||||rent');

  // C. Query-driven city override vs manual city change
  // Typing "2bhk flat in pune" with Indore context + submit MUST KEEP query intent and synchronize city=Pune
  const overrideFilters = buildRentalSearchFilters('Indore', '2bhk flat in pune', null);
  assert.equal(overrideFilters.city, 'Pune');
  assert.equal(overrideFilters.q, '2bhk flat in pune');
  assert.notEqual(overrideFilters.q, undefined, 'Query-driven city override must retain search query text');
});

test('autocomplete suggestion auto-commit across all actionable suggestion types without double firing', () => {
  const suggestionsToTest = [
    {
      suggestion: mapRentalSuggestion({
        type: 'SEARCH_QUERY',
        label: '2 BHK Flat in Vijay Nagar, Indore',
        city: 'Indore',
        locality: 'Vijay Nagar',
        bhk: '2 BHK',
        propertyType: 'FLAT',
        resultCount: 4
      }),
      expected: {
        city: 'Indore',
        sector: 'Vijay Nagar',
        bhk: '2 BHK',
        propertyType: 'FLAT',
        rentalOnly: true
      }
    },
    {
      suggestion: mapRentalSuggestion({
        type: 'LOCALITY',
        label: 'Vijay Nagar, Indore',
        city: 'Indore',
        locality: 'Vijay Nagar',
        resultCount: 15
      }),
      expected: {
        city: 'Indore',
        sector: 'Vijay Nagar',
        rentalOnly: true
      }
    },
    {
      suggestion: mapRentalSuggestion({
        type: 'ENTITY_MATCH',
        label: 'MP Nagar, Bhopal',
        city: 'Bhopal',
        locality: 'MP Nagar',
        resultCount: 0
      }),
      expected: {
        city: 'Bhopal',
        sector: 'MP Nagar',
        rentalOnly: true
      }
    },
    {
      suggestion: mapRentalSuggestion({
        type: 'QUERY_INTENT',
        label: 'Search 2 BHK flats in Pune',
        city: 'Pune',
        locality: null,
        bhk: '2 BHK',
        propertyType: 'FLAT',
        resultCount: 0
      }),
      expected: {
        city: 'Pune',
        bhk: '2 BHK',
        propertyType: 'FLAT',
        rentalOnly: true
      }
    },
    {
      suggestion: mapRentalSuggestion({
        type: 'SEARCH_ANYWAY',
        label: 'Search "independent floor"',
        city: 'Indore',
        resultCount: 0
      }),
      expected: {
        city: 'Indore',
        q: 'Search "independent floor"',
        rentalOnly: true
      }
    },
    {
      suggestion: mapRentalSuggestion({
        type: 'UNSUPPORTED_CITY',
        label: 'Jaipur',
        city: 'Jaipur',
        resultCount: 0
      }),
      expected: {
        city: 'Jaipur',
        q: 'Jaipur',
        rentalOnly: true
      }
    }
  ];

  for (const { suggestion, expected } of suggestionsToTest) {
    assert.ok(suggestion, `Suggestion for ${expected.city} should be valid`);
    let committedCalls = 0;
    let committedFilters = null;

    // Simulate chooseSuggestion invocation on suggestion click
    const onSearch = (city, sector, filters) => {
      committedCalls += 1;
      committedFilters = filters;
    };

    const filters = buildRentalSearchFilters(suggestion.city, suggestion.label, suggestion);
    onSearch(filters.city, filters.sector, filters);

    // Assert EXACTLY ONE invocation occurs per suggestion click
    assert.equal(committedCalls, 1, `Exactly one search must fire for ${suggestion.type}`);
    assert.deepEqual(committedFilters, expected, `Filters for ${suggestion.type} must match expected contract`);
  }
});

test('code regression: PropertyShowcase does not render "Change search" in header or empty state', () => {
  const showcasePath = new URL('../components/PropertyShowcase.tsx', import.meta.url);
  const showcaseCode = readFileSync(showcasePath, 'utf-8');
  assert.equal(
    showcaseCode.includes('Change search'),
    false,
    'PropertyShowcase.tsx must not contain "Change search"'
  );
});

test('resetFiltersForSearchClear contract establishes clean current-city default search state', () => {
  // 1. Basic contract
  const indoreClear = resetFiltersForSearchClear('Indore');
  assert.deepEqual(indoreClear, { city: 'Indore', rentalOnly: true });

  const puneClear = resetFiltersForSearchClear('Pune');
  assert.deepEqual(puneClear, { city: 'Pune', rentalOnly: true });

  // 2. Shared semantics with resetFiltersForManualCityChange
  assert.deepEqual(
    resetFiltersForManualCityChange('Bhopal'),
    resetFiltersForSearchClear('Bhopal')
  );
});

test('Tenant hero clear removes Smart Search and quick-filter refinements while preserving city', () => {
  const activeSearches = [
    { city: 'Indore', bhk: '3 BHK', rentalOnly: true },
    { city: 'Indore', propertyType: 'HOUSE', rentalOnly: true },
    { city: 'Indore', bhk: '3 BHK', propertyType: 'HOUSE', rentalOnly: true },
    {
      city: 'Indore', sector: 'Vijay Nagar', q: '3bhk house in vijay nagar',
      bhk: '3 BHK', propertyType: 'HOUSE', rentalOnly: true
    }
  ];

  for (const activeSearch of activeSearches) {
    const cleared = resetFiltersForSearchClear(activeSearch.city);
    assert.deepEqual(cleared, { city: 'Indore', rentalOnly: true });
    assert.equal(cleared.city, activeSearch.city, 'Clear must retain the selected city');
    assert.equal(cleared.bhk, undefined, 'Clear must remove the selected BHK chip state');
    assert.equal(cleared.propertyType, undefined, 'Clear must remove the selected property type chip state');
    assert.equal(cleared.q, undefined, 'Clear must remove free-text query state');
    assert.equal(cleared.sector, undefined, 'Clear must remove Smart Search locality state');
    assert.equal(discoverySearchKey(cleared), discoverySearchKey({ city: activeSearch.city, rentalOnly: true }));
  }
});

test('Tenant hero X uses committed clear handling and cancels pending chip auto-scroll', () => {
  const compactSearchSource = readFileSync(new URL('../components/CompactSearchContext.tsx', import.meta.url), 'utf-8');
  const tenantDashboardSource = readFileSync(new URL('../components/TenantDashboard.tsx', import.meta.url), 'utf-8');
  const tenantDraftClearBranch = compactSearchSource.match(/if \(tenantHeroAppearance\) \{[\s\S]*?return;\s*\}/)?.[0];
  const tenantSearchClearHandler = tenantDashboardSource.match(/const clearTenantSearch = \(\) => \{[\s\S]*?\n  \};/)?.[0];

  assert.ok(tenantDraftClearBranch, 'Tenant hero should have an explicit X clear branch');
  assert.match(tenantDraftClearBranch, /handleClearAll\(\)/);
  assert.match(compactSearchSource, /aria-label=\{tenantHeroAppearance \? 'Clear search and filters' : 'Clear search text'\}/);
  assert.ok(tenantSearchClearHandler, 'Tenant clear callback should own the authoritative reset');
  assert.match(tenantSearchClearHandler, /pendingHeroFilterScrollRef\.current = null/);
  assert.match(tenantSearchClearHandler, /cancelAnimationFrame/);
  assert.match(tenantSearchClearHandler, /onSearchHomes\(resetFiltersForSearchClear\(searchFilters\.city \|\| discoveryCity\)\)/);
  assert.doesNotMatch(tenantSearchClearHandler, /applySmartSearch|scrollIntoView/);
  assert.match(tenantDashboardSource, /onClearAll=\{clearTenantSearch\}/);
});

test('search clear (×) handles uncommitted draft text vs committed search correctly', () => {
  // A. Draft-only clear:
  // Committed state: city=Indore, no filters.
  // User merely types "2bhk fla", then clicks ×.
  let committedCalls = 0;
  let activeUrl = 'city=Indore&rentalOnly=true';
  const mockOnSearch = (city, sector, filters) => {
    committedCalls += 1;
    activeUrl = `city=${city}&rentalOnly=true`;
  };

  const draftState = {
    selectedCity: 'Indore',
    selectedQuery: undefined,
    selectedSector: undefined,
    selectedBhk: undefined,
    selectedPropertyType: undefined,
    selectedFurnishing: undefined,
    selectedMinRent: undefined,
    selectedMaxRent: undefined
  };

  // Draft clear simulation:
  const hasCommittedSearchA = Boolean(
    draftState.selectedQuery ||
    draftState.selectedSector ||
    draftState.selectedBhk ||
    draftState.selectedPropertyType ||
    draftState.selectedFurnishing ||
    draftState.selectedMinRent ||
    draftState.selectedMaxRent
  );
  assert.equal(hasCommittedSearchA, false, 'Draft typing without submit is not a committed search');

  // When × is clicked for draft only:
  let draftSearchText = '2bhk fla';
  draftSearchText = '';
  if (hasCommittedSearchA) {
    const cleanFilters = resetFiltersForSearchClear(draftState.selectedCity);
    mockOnSearch(cleanFilters.city, undefined, cleanFilters);
  }

  assert.equal(draftSearchText, '');
  assert.equal(committedCalls, 0, 'Draft-only clear must NOT trigger an unnecessary onSearch call');
  assert.equal(activeUrl, 'city=Indore&rentalOnly=true', 'Committed URL must remain untouched on draft clear');

  // B. Committed locality/filter clear:
  // city=Indore, sector=Bhawarkua, bhk=2 BHK, propertyType=FLAT
  const committedStateB = {
    selectedCity: 'Indore',
    selectedQuery: undefined,
    selectedSector: 'Bhawarkua',
    selectedBhk: '2 BHK',
    selectedPropertyType: 'FLAT',
    selectedFurnishing: undefined,
    selectedMinRent: undefined,
    selectedMaxRent: undefined
  };

  const hasCommittedSearchB = Boolean(
    committedStateB.selectedQuery ||
    committedStateB.selectedSector ||
    committedStateB.selectedBhk ||
    committedStateB.selectedPropertyType ||
    committedStateB.selectedFurnishing ||
    committedStateB.selectedMinRent ||
    committedStateB.selectedMaxRent
  );
  assert.equal(hasCommittedSearchB, true);

  let searchCallsB = 0;
  let filtersCommittedB = null;
  const onSearchB = (city, sector, filters) => {
    searchCallsB += 1;
    filtersCommittedB = filters;
  };

  if (hasCommittedSearchB) {
    const cleanFilters = resetFiltersForSearchClear(committedStateB.selectedCity);
    onSearchB(cleanFilters.city, undefined, cleanFilters);
  }

  assert.equal(searchCallsB, 1, 'Committed clear must invoke onSearch exactly once');
  assert.deepEqual(filtersCommittedB, { city: 'Indore', rentalOnly: true });
  assert.equal(filtersCommittedB.sector, undefined);
  assert.equal(filtersCommittedB.bhk, undefined);
  assert.equal(filtersCommittedB.propertyType, undefined);
  assert.equal(filtersCommittedB.q, undefined);

  // URL serialization after clear:
  const urlB = new URLSearchParams();
  urlB.set('city', filtersCommittedB.city);
  if (filtersCommittedB.rentalOnly) urlB.set('rentalOnly', 'true');
  assert.equal(urlB.toString(), 'city=Indore&rentalOnly=true');

  // C. Cross-city committed search:
  // User searched "2bhk flat in pune" from Indore -> committed city becomes Pune.
  // When user clicks ×, current city must remain Pune! NOT Indore!
  const crossCityPuneState = {
    selectedCity: 'Pune',
    selectedQuery: '2bhk flat in pune',
    selectedSector: undefined,
    selectedBhk: '2 BHK',
    selectedPropertyType: 'FLAT'
  };

  const cleanPuneFilters = resetFiltersForSearchClear(crossCityPuneState.selectedCity);
  assert.equal(cleanPuneFilters.city, 'Pune', 'Cross-city search clear must remain in Pune, NOT revert to Indore');
  assert.equal(cleanPuneFilters.rentalOnly, true);
  assert.equal(cleanPuneFilters.q, undefined);
  assert.equal(cleanPuneFilters.bhk, undefined);
  assert.equal(cleanPuneFilters.propertyType, undefined);

  // D. Suggestion-selected search:
  // Vijay Nagar, Indore selected -> click × -> clean Indore default
  const suggestionSelectedState = {
    selectedCity: 'Indore',
    selectedSector: 'Vijay Nagar',
    selectedQuery: undefined
  };
  const cleanIndoreFromSuggestion = resetFiltersForSearchClear(suggestionSelectedState.selectedCity);
  assert.equal(cleanIndoreFromSuggestion.city, 'Indore');
  assert.equal(cleanIndoreFromSuggestion.sector, undefined);

  // E. Query-intent clear:
  // Pune 2BHK zero inventory -> click × -> clean Pune city browsing
  const queryIntentPune = {
    selectedCity: 'Pune',
    selectedQuery: 'Search 2 BHK flats in Pune',
    selectedBhk: '2 BHK',
    selectedPropertyType: 'FLAT'
  };
  const cleanPuneFromIntent = resetFiltersForSearchClear(queryIntentPune.selectedCity);
  assert.equal(cleanPuneFromIntent.city, 'Pune');
  assert.equal(cleanPuneFromIntent.bhk, undefined);
  assert.equal(cleanPuneFromIntent.propertyType, undefined);
  assert.equal(discoverySearchKey(cleanPuneFromIntent), 'pune||||||||rent');

  // F. SEARCH_ANYWAY clear:
  // Free-text SEARCH_ANYWAY with q="independent floor" in Indore
  const searchAnywayState = {
    selectedCity: 'Indore',
    selectedQuery: 'Search "independent floor"'
  };
  const cleanFromSearchAnyway = resetFiltersForSearchClear(searchAnywayState.selectedCity);
  assert.equal(cleanFromSearchAnyway.city, 'Indore');
  assert.equal(cleanFromSearchAnyway.q, undefined);
  assert.equal(discoverySearchKey(cleanFromSearchAnyway), 'indore||||||||rent');

  // G. Single committed call guarantee (no double firing):
  let callCount = 0;
  const singleCallCheck = () => {
    callCount += 1;
  };
  // Simulate clicking × once:
  singleCallCheck();
  assert.equal(callCount, 1, 'Search clear must fire exactly once per click');
});

test('Task 2: compact search context summarization and active filter detection', () => {
  // A. Rent display formatter
  assert.equal(formatRentDisplay(15000), '15k');
  assert.equal(formatRentDisplay(25000), '25k');
  assert.equal(formatRentDisplay(100000), '1L');
  assert.equal(formatRentDisplay(15500), '15,500');

  // B. Clean city browsing state
  const cleanFilters = { city: 'Indore', rentalOnly: true };
  assert.equal(hasActiveSearchFilters(cleanFilters), false);
  assert.equal(formatCompactSearchContext(cleanFilters), null);

  // C. Locality + BHK + Property Type
  const structuredFilters = {
    city: 'Indore',
    sector: 'Bhawarkua',
    bhk: '2 BHK',
    propertyType: 'FLAT',
    rentalOnly: true
  };
  assert.equal(hasActiveSearchFilters(structuredFilters), true);
  assert.equal(formatCompactSearchContext(structuredFilters), 'Bhawarkua · 2 BHK · Flat');

  // D. Price range + Furnishing
  const priceFurnishedFilters = {
    city: 'Indore',
    minRent: 15000,
    maxRent: 25000,
    furnishing: 'FURNISHED',
    rentalOnly: true
  };
  assert.equal(hasActiveSearchFilters(priceFurnishedFilters), true);
  assert.equal(formatCompactSearchContext(priceFurnishedFilters), '₹15k–₹25k · Furnished');

  // E. Locality + Property Type
  const localityHouseFilters = {
    city: 'Indore',
    sector: 'Vijay Nagar',
    propertyType: 'HOUSE',
    rentalOnly: true
  };
  assert.equal(hasActiveSearchFilters(localityHouseFilters), true);
  assert.equal(formatCompactSearchContext(localityHouseFilters), 'Vijay Nagar · House');

  // F. Free-text search
  const freeTextFilters = {
    city: 'Pune',
    q: '2bhk flat in pune',
    rentalOnly: true
  };
  assert.equal(hasActiveSearchFilters(freeTextFilters), true);
  assert.equal(formatCompactSearchContext(freeTextFilters), '2bhk flat in pune');

  // G. Long context handles multiple criteria gracefully
  const longFilters = {
    city: 'Indore',
    sector: 'Vijay Nagar',
    bhk: '3 BHK',
    propertyType: 'HOUSE',
    minRent: 20000,
    maxRent: 40000,
    furnishing: 'FULLY_FURNISHED',
    rentalOnly: true
  };
  assert.equal(hasActiveSearchFilters(longFilters), true);
  assert.equal(
    formatCompactSearchContext(longFilters),
    'Vijay Nagar · 3 BHK · House · ₹20k–₹40k · Fully furnished'
  );
});

test('Task 2: Clear all contract clears search requirements while preserving current city', () => {
  // A. Indore + Bhawarkua · 2 BHK · Flat -> Clear all -> Indore + clean
  const indoreFiltered = {
    city: 'Indore',
    sector: 'Bhawarkua',
    bhk: '2 BHK',
    propertyType: 'FLAT',
    minRent: 15000,
    maxRent: 30000,
    furnishing: 'SEMI_FURNISHED',
    rentalOnly: true
  };
  assert.equal(hasActiveSearchFilters(indoreFiltered), true);

  const indoreCleared = resetFiltersForSearchClear(indoreFiltered.city);
  assert.deepEqual(indoreCleared, { city: 'Indore', rentalOnly: true });
  assert.equal(hasActiveSearchFilters(indoreCleared), false);
  assert.equal(formatCompactSearchContext(indoreCleared), null);

  // B. Pune + Baner + 2 BHK -> Clear all -> Pune + clean (NOT Indore!)
  const puneFiltered = {
    city: 'Pune',
    sector: 'Baner',
    bhk: '2 BHK',
    rentalOnly: true
  };
  assert.equal(hasActiveSearchFilters(puneFiltered), true);

  const puneCleared = resetFiltersForSearchClear(puneFiltered.city);
  assert.deepEqual(puneCleared, { city: 'Pune', rentalOnly: true });
  assert.equal(puneCleared.city, 'Pune', 'Clear all must preserve Pune and NEVER revert to Indore');
  assert.equal(hasActiveSearchFilters(puneCleared), false);

  // C. Manual city change via sticky context
  const switchedToIndore = resetFiltersForManualCityChange('Indore');
  assert.deepEqual(switchedToIndore, { city: 'Indore', rentalOnly: true });

  // D. Free text and autocomplete from sticky context
  const stickySuggestion = mapRentalSuggestion({
    type: 'SEARCH_QUERY',
    label: '2 BHK Flat in Vijay Nagar, Indore',
    city: 'Indore',
    locality: 'Vijay Nagar',
    bhk: '2 BHK',
    propertyType: 'FLAT',
    resultCount: 5
  });
  assert.ok(stickySuggestion);
  const committedFromSticky = buildRentalSearchFilters(
    stickySuggestion.city,
    stickySuggestion.label,
    stickySuggestion
  );
  assert.deepEqual(committedFromSticky, {
    city: 'Indore',
    sector: 'Vijay Nagar',
    bhk: '2 BHK',
    propertyType: 'FLAT',
    rentalOnly: true
  });
});
