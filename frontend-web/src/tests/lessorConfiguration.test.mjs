import test from 'node:test';
import assert from 'node:assert/strict';
import { exactBhk, bhkChoice, pricingReady } from '../utils/lessorConfiguration.ts';

test('1 RK remains distinct and 4+ persists only an exact count', () => {
  assert.equal(exactBhk('1RK'), '1RK');
  assert.equal(exactBhk('1BHK'), '1BHK');
  assert.equal(exactBhk('4+'), null);
  assert.equal(exactBhk('4+', 4), '4BHK');
  assert.equal(exactBhk('4+', 12), '12BHK');
  assert.equal(exactBhk('4+', 3), null);
  assert.equal(exactBhk('4+', 4.5), null);
  assert.equal(bhkChoice('12BHK'), '4+');
});

test('pricing requires an explicit deposit and accepts zero', () => {
  assert.equal(pricingReady(15000, 0), true);
  assert.equal(pricingReady(15000, null), false);
  assert.equal(pricingReady(0, 0), false);
  assert.equal(pricingReady(15000, -1), false);
});
