import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { actionableDraftsNewestFirst } from '../utils/landingDrafts.ts';
import { clearLandingCriteria, landingAppliedCriteria, removeLandingCriterion } from '../utils/landingSearchState.ts';

const landing = readFileSync(new URL('../components/LandingV0.tsx', import.meta.url), 'utf8');
const home = readFileSync(new URL('../components/Home.tsx', import.meta.url), 'utf8');
const detail = readFileSync(new URL('../components/PublicPropertyDetail.tsx', import.meta.url), 'utf8');
const portfolio = readFileSync(new URL('../components/LessorPortfolio.tsx', import.meta.url), 'utf8');
const quickView = readFileSync(new URL('../components/TenantDashboard.tsx', import.meta.url), 'utf8');

test('approved owner navigation and Pathome footer labels use real targets only', () => {
  assert.match(landing, /href="#list-property">For owners<\/a>/);
  assert.match(landing, /<section id="list-property"/);
  assert.match(landing, /<h2>Pathome<\/h2>/);
  for (const label of ['Help &amp; support', 'Privacy', 'Terms']) assert.ok(landing.includes(`<span>${label}</span>`));
  assert.doesNotMatch(landing, /pathome\.vercel\.app\/tenant/);
  assert.doesNotMatch(quickView, /getActiveTenantVisitCount/);
});

const draft = (id, updatedAt, status = 'DRAFT') => ({ draftId: id, updatedAt, status });

test('owned actionable drafts use authoritative update ordering, excluding submitted work', () => {
  const sorted = actionableDraftsNewestFirst([
    draft('old', '2026-01-01T00:00:00Z'), draft('submitted', '2026-12-01T00:00:00Z', 'SUBMITTED'),
    draft('new', '2026-02-01T00:00:00Z')
  ]);
  assert.deepEqual(sorted.map(item => item.draftId), ['new', 'old']);
  assert.deepEqual(actionableDraftsNewestFirst([]), []);
  assert.match(portfolio, /actionableDraftsNewestFirst\(drafts\)/);
  assert.match(home, /actionableDraftsNewestFirst\(page\.items\)\[0\]/);
});

test('applied criteria remove independently and clear all while preserving city', () => {
  const discovery = readFileSync(new URL('../components/LandingV0.tsx', import.meta.url), 'utf8');
  assert.match(discovery, /<span className="lp-chip lp-chip-static lp-chip-search">Search: \{landingState\.searchLabel \|\| 'Selected requirement'\}<\/span>/);
  assert.doesNotMatch(discovery, /Remove applied search|removeSearch/);
  assert.match(discovery, /<button type="button" className="lp-chip lp-chip-clear" onClick=\{clear\}>/);
  const filters = { city: 'Indore', q: 'near station', sector: 'Vijay Nagar', bhk: '2BHK', propertyType: 'FLAT', minRent: 10000, maxRent: 20000, rentalOnly: true };
  assert.deepEqual(landingAppliedCriteria(filters).map(item => item.key), ['q', 'sector', 'bhk', 'propertyType', 'budget']);
  const withoutBudget = removeLandingCriterion(filters, 'budget');
  assert.equal(withoutBudget.minRent, undefined);
  assert.equal(withoutBudget.maxRent, undefined);
  assert.equal(withoutBudget.bhk, '2BHK');
  const cleared = clearLandingCriteria('Indore');
  assert.equal(cleared.city, 'Indore');
  assert.deepEqual(landingAppliedCriteria(cleared), []);
});

test('landing editing controls commit through explicit submit or Apply', () => {
  assert.match(landing, /onChange=\{e => setHomeType\(/);
  assert.match(landing, /onSubmit=\{event => \{ event\.preventDefault\(\); execute\(\); \}\}/);
  assert.match(landing, /onClick=\{applyFilters\}>Show homes/);
  assert.match(landing, /setSelected\(item\); setQuery\(item\.label\); setDraftCity\(item\.city\); setSuggestionsOpen\(false\);\s*execute\(item\.label, item, item\.city\)/);
  assert.match(landing, /onClick=\{resetExplicit\}>Reset/);
  assert.match(landing, /const count = landingAppliedCriteria\(landingState\.explicit\)\.length/);
});

test('landing owns no duplicate preview and hides noncanonical locality catalogue', () => {
  assert.match(home, /<TenantPropertyQuickView key=\{landingQuickViewId\}/);
  assert.match(quickView, /export const TenantPropertyQuickView/);
  assert.doesNotMatch(landing, /Explore by locality/);
  assert.doesNotMatch(landing, /landingLocalities\(/);
  assert.doesNotMatch(detail, /<ChevronLeft className="h-5 w-5"/);
  assert.doesNotMatch(detail, /<ChevronRight className="h-5 w-5"/);
});

test('landing saved state is owner scoped and offers retry when persistence lookup fails', () => {
  assert.match(home, /tenantFavoritesSnapshot\.identityKey === tenantFavoriteSession\?\.key/);
  assert.match(home, /setLandingFavoritePending\(new Set\(\)\);\s*setLandingQuickViewId\(null\);/);
  assert.match(landing, /favoriteError && <p className="lp-favorite-error" role="alert">/);
  assert.match(landing, /onClick=\{onRetryFavorites\}>Try again/);
});
