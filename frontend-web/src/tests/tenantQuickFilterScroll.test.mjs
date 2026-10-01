import test from 'node:test';
import assert from 'node:assert/strict';
import { queueTenantHeroFilterScroll, resolveTenantHeroFilterScroll } from '../utils/tenantQuickFilterScroll.ts';

test('restored search state does not request an automatic results scroll', () => {
  assert.equal(resolveTenantHeroFilterScroll(null, 'city=Indore', 'city=Indore', 'READY', 'route-a'), 'idle');
});

test('waits until the matching discovery request has settled', () => {
  const pending = queueTenantHeroFilterScroll(null, 'city=Indore&bhk=2+BHK', 'route-a');
  assert.equal(resolveTenantHeroFilterScroll(pending, pending.searchKey, 'city=Indore', 'READY', 'route-b'), 'waiting');
  assert.equal(resolveTenantHeroFilterScroll(pending, pending.searchKey, pending.searchKey, 'LOADING', 'route-b'), 'waiting');
  assert.equal(resolveTenantHeroFilterScroll(pending, pending.searchKey, pending.searchKey, 'READY', 'route-b'), 'scroll');
  assert.equal(resolveTenantHeroFilterScroll(pending, pending.searchKey, pending.searchKey, 'ERROR', 'route-b'), 'scroll');
});

test('rapid hero filter changes replace the pending target and ignore stale results', () => {
  const first = queueTenantHeroFilterScroll(null, 'city=Indore&bhk=2+BHK', 'route-a');
  const final = queueTenantHeroFilterScroll(first, 'city=Indore&propertyType=FLAT', 'route-b');
  assert.equal(final.searchKey, 'city=Indore&propertyType=FLAT');
  assert.equal(resolveTenantHeroFilterScroll(final, final.searchKey, 'city=Indore&bhk=2+BHK', 'READY', 'route-c'), 'waiting');
  assert.equal(resolveTenantHeroFilterScroll(final, final.searchKey, final.searchKey, 'READY', 'route-c'), 'scroll');
  assert.equal(resolveTenantHeroFilterScroll(null, final.searchKey, final.searchKey, 'READY', 'route-c'), 'idle');
});

test('browser back discards an unfinished filter scroll request', () => {
  const pending = queueTenantHeroFilterScroll(null, 'city=Indore&bhk=3+BHK', 'route-a');
  assert.equal(resolveTenantHeroFilterScroll(pending, 'city=Indore', 'city=Indore', 'READY', 'route-back'), 'discard');
});
