import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { emptyLandingPart, landingAppliedCriteria, landingEffectiveFilters, landingStateFromUrl } from '../utils/landingSearchState.ts';
import { guestDraftSummary, actionableDraftsNewestFirst } from '../utils/landingDrafts.ts';

const landing = readFileSync(new URL('../components/LandingV0.tsx', import.meta.url), 'utf8');
const home = readFileSync(new URL('../components/Home.tsx', import.meta.url), 'utf8');
const portfolio = readFileSync(new URL('../components/LessorPortfolio.tsx', import.meta.url), 'utf8');
const styles = readFileSync(new URL('../landingV0.css', import.meta.url), 'utf8');

test('parsed Smart Search remains search-owned across URL refresh and explicit reset', () => {
  const search = { city: 'Indore', sector: 'Vijay Nagar', bhk: '2BHK', propertyType: 'FLAT', maxRent: 20000, rentalOnly: true };
  const state = { search, explicit: emptyLandingPart('Indore'), searchLabel: '2 bhk flat in Vijay Nagar under 20000' };
  const params = new URLSearchParams({ lpSearch: JSON.stringify(search), lpFilters: JSON.stringify(state.explicit), lpSearchLabel: state.searchLabel });
  const restored = landingStateFromUrl(params, search, 'Indore');
  assert.deepEqual(landingAppliedCriteria(restored.explicit), []);
  assert.deepEqual(landingEffectiveFilters(restored, 'Indore'), search);
  const withFilter = { ...restored, explicit: { city: 'Indore', rentalOnly: true, furnishing: 'FURNISHED' } };
  assert.equal(landingAppliedCriteria(withFilter.explicit).length, 1);
  assert.equal(landingEffectiveFilters({ ...withFilter, explicit: emptyLandingPart('Indore') }, 'Indore').bhk, '2BHK');
  assert.match(landing, /const count = landingAppliedCriteria\(landingState\.explicit\)\.length/);
  assert.match(landing, /onClick=\{applyFilters\}>Show homes/);
  assert.match(landing, /onClick=\{resetExplicit\}>Reset/);
  assert.match(landing, /execute\(item\.label, item, item\.city\)/);
  assert.match(landing, /aria-label="Clear search and filters" onClick=\{clearAllDiscovery\}/);
  assert.match(home, /query\.set\('lpSearch', JSON\.stringify\(nextLandingState\.search\)\)/);
});


test('landing Search X clears all discovery state immediately and keeps focus styling restrained', () => {
  assert.match(landing, /const clearAllDiscovery = \(\) => \{[\s\S]*?setQuery\(''\); setSelected\(null\); setSuggestionsOpen\(false\)/);
  assert.match(landing, /setDraftFilters\(\{ bhk: '', minRent: undefined, maxRent: undefined, locality: '', furnishing: '' \}\);[\s\S]*?setFilterType\(''\); setHomeType\(''\)/);
  assert.match(landing, /const clear = resetFiltersForSearchClear\(draftCity\);[\s\S]*?onSearch\(draftCity, undefined, clear, \{ search: clear, explicit: emptyLandingPart\(draftCity\), searchLabel: '' \}\)/);
  assert.match(home, /navigate\(`\/\?\$\{query\.toString\(\)\}#homes`\)/);
  assert.match(styles, /\.lp-search-query:focus-within \{ box-shadow: none; \}/);
  assert.match(styles, /\.lp-search-query:has\(input:focus-visible\) \{ outline: 2px solid/);
  assert.doesNotMatch(styles, /\.lp-search-query:focus-within \{[^}]*background\s*:/);
});

test('guest and owned draft continuation select real actionable records', () => {
  const draft = { draftId: 'guest-123', status: 'DRAFT', completionPercent: 88, updatedAt: '2026-10-05T00:00:00Z', data: { basics: { bhkCount: '2', propertyType: 'FLAT' }, location: { city: 'Indore', localityInput: 'Vijay Nagar' }, pricing: { monthlyRent: 18000 } } };
  const summary = guestDraftSummary(draft);
  assert.equal(summary.draftId, 'guest-123'); assert.equal(summary.completionPercent, 88);
  assert.equal(summary.title, '2 Flat'); assert.equal(summary.monthlyRent, 18000);
  assert.deepEqual(actionableDraftsNewestFirst([{ draftId: 'a', updatedAt: '2026-01-01', status: 'DRAFT' }, { draftId: 'b', updatedAt: '2026-02-01', status: 'DRAFT' }]).map(x => x.draftId), ['b', 'a']);
  assert.match(home, /resumeGuest\(\)/); assert.match(home, /draft\?\.status === 'DRAFT' \? 1 : 0/); assert.match(home, /guestDraftSummary\(draft!\)/);
  assert.match(landing, /props\.latestDraft && props\.onOpenDraft/);
  assert.match(portfolio, /pathome-draft-card/);
});
