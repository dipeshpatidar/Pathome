import test from 'node:test';
import assert from 'node:assert/strict';
import {
  shouldCollapseTenantSearch,
  tenantSearchBeaconSide,
  tenantSearchCollisionBand,
  tenantSearchCollisionReached,
  tenantSearchMorphPlan
} from '../utils/tenantSearchMorph.ts';

test('discovery collision band is derived from the floating Search and rail geometry', () => {
  assert.deepEqual(tenantSearchCollisionBand(142, 900), { top: 154, bottom: 166 });
  assert.deepEqual(tenantSearchCollisionBand(780, 844), { top: 792, bottom: 804 });
  assert.deepEqual(tenantSearchCollisionBand(842, 844), { top: 843, bottom: 844 });
});

test('fast scroll that skips the observer band still reaches the geometry collision line', () => {
  const band = tenantSearchCollisionBand(142, 900);
  const sentinelSamples = [190, 20]; // Above, then already past the line; no intermediate sample.
  assert.equal(tenantSearchCollisionReached(sentinelSamples[0], band), false);
  assert.equal(tenantSearchCollisionReached(sentinelSamples[1], band), true);
  assert.equal(shouldCollapseTenantSearch('discovery', {
    mode: 'sticky', heroVisible: false, engaged: false,
    discoveryCollision: tenantSearchCollisionReached(sentinelSamples[1], band), manualOpenGrace: false
  }), true);
});

test('discovery collision and inactivity request the same eligible Sticky-to-Beacon handoff', () => {
  const eligible = {
    mode: 'sticky', heroVisible: false, engaged: false,
    discoveryCollision: true, manualOpenGrace: false
  };
  assert.equal(shouldCollapseTenantSearch('discovery', eligible), true);
  assert.equal(shouldCollapseTenantSearch('inactivity', { ...eligible, discoveryCollision: false }), true);
});

test('manual Beacon reopen grace and active Search interaction suppress collision collapse', () => {
  const collision = {
    mode: 'sticky', heroVisible: false, engaged: false,
    discoveryCollision: true, manualOpenGrace: false
  };
  assert.equal(shouldCollapseTenantSearch('discovery', { ...collision, manualOpenGrace: true }), false);
  assert.equal(shouldCollapseTenantSearch('discovery', { ...collision, engaged: true }), false);
  assert.equal(shouldCollapseTenantSearch('inactivity', { ...collision, engaged: true }), false);
  assert.equal(shouldCollapseTenantSearch('inactivity', { ...collision, manualOpenGrace: true }), true);
});

test('Hero return has priority over collision and never travels the Beacon across the page', () => {
  for (const mode of ['sticky', 'beacon']) {
    const plan = tenantSearchMorphPlan(mode, 'hero', false, 390);
    assert.equal(plan.travels, false);
    assert.equal(plan.durationMs, 0);
  }
  assert.equal(shouldCollapseTenantSearch('discovery', {
    mode: 'sticky', heroVisible: true, engaged: false,
    discoveryCollision: true, manualOpenGrace: false
  }), false);
});

test('sticky search collapses toward the existing responsive Beacon side', () => {
  const desktop = tenantSearchMorphPlan('sticky', 'beacon', false, 1280);
  const mobile = tenantSearchMorphPlan('sticky', 'beacon', false, 390);
  assert.deepEqual([desktop.direction, desktop.durationMs, desktop.beaconSide, desktop.travels], ['collapse', 500, 'right', true]);
  assert.deepEqual([mobile.direction, mobile.durationMs, mobile.beaconSide, mobile.travels], ['crossfade', 160, 'left', false]);
  assert.equal(tenantSearchBeaconSide(1024), 'right');
  assert.equal(tenantSearchBeaconSide(768), 'left');
});

test('mobile Beacon reopens without a geometric clone, while desktop retains its Search-only morph', () => {
  assert.deepEqual(tenantSearchMorphPlan('beacon', 'sticky', false, 390), {
    direction: 'crossfade', durationMs: 160, beaconSide: 'left', travels: false
  });
  assert.deepEqual(tenantSearchMorphPlan('beacon', 'sticky', false, 1280), {
    direction: 'expand', durationMs: 400, beaconSide: 'right', travels: true
  });
});

test('returning to Hero never starts a cross-screen morph', () => {
  for (const from of ['sticky', 'beacon']) {
    assert.deepEqual(tenantSearchMorphPlan(from, 'hero', false, 1280), {
      direction: 'none', durationMs: 0, beaconSide: 'right', travels: false
    });
  }
});

test('reduced motion uses a brief handoff without travel', () => {
  for (const [from, to] of [['sticky', 'beacon'], ['beacon', 'sticky']]) {
    const plan = tenantSearchMorphPlan(from, to, true, 390);
    assert.equal(plan.direction, 'crossfade');
    assert.equal(plan.durationMs, 100);
    assert.equal(plan.travels, false);
  }
});
