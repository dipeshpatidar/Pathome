import test from 'node:test';
import assert from 'node:assert/strict';
import { shouldRenderTenantMobileDock, tenantMobileDockBadges, tenantMobileDockTarget } from '../utils/tenantMobileDock.ts';

test('mobile dock stays out of the Hero and modal layers, then becomes eligible after the Hero', () => {
  assert.equal(shouldRenderTenantMobileDock(true, false, false), false);
  assert.equal(shouldRenderTenantMobileDock(false, false, false), true);
  assert.equal(shouldRenderTenantMobileDock(false, true, false), false);
  assert.equal(shouldRenderTenantMobileDock(false, false, true), false);
});

test('mobile dock filter badge derives only from shared active search filters', () => {
  assert.deepEqual(tenantMobileDockBadges({ city: 'Indore', rentalOnly: true }), {
    filterCount: null, visitCount: null
  });
  assert.deepEqual(tenantMobileDockBadges({
    city: 'Indore', bhk: '2 BHK', propertyType: 'FLAT', rentalOnly: true
  }), { filterCount: 2, visitCount: null });
});

test('mobile dock shows a Visit badge only for an authoritative positive active-request count', () => {
  for (const count of [undefined, null, 0, -1, 1.5, Number.NaN, Number.POSITIVE_INFINITY]) {
    assert.equal(tenantMobileDockBadges({ city: 'Indore' }, count).visitCount, null);
  }
  assert.equal(tenantMobileDockBadges({ city: 'Indore' }, 3).visitCount, 3);
});

test('mobile dock actions target existing Tenant sections or open the existing filter sheet', () => {
  assert.equal(tenantMobileDockTarget('home'), 'tenant-home-search');
  assert.equal(tenantMobileDockTarget('filters'), null);
  assert.equal(tenantMobileDockTarget('visits'), 'visit-history-title');
  assert.equal(tenantMobileDockTarget('saved'), 'saved-homes-title');
});
