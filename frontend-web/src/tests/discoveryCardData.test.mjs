import assert from 'node:assert/strict';
import test from 'node:test';
import {
  formatPropertyArea,
  formatSecurityDeposit,
  parseSecurityDeposit
} from '../utils/discoveryCardData.ts';

test('rental deposit preserves positive, explicit zero, and missing values', () => {
  assert.equal(parseSecurityDeposit('90000'), 90000);
  assert.equal(parseSecurityDeposit(0), 0);
  assert.equal(parseSecurityDeposit(null), null);
  assert.equal(parseSecurityDeposit(undefined), null);
  assert.equal(parseSecurityDeposit(''), null);
  assert.equal(parseSecurityDeposit('not an amount'), null);
  assert.equal(formatSecurityDeposit(90000), '₹90,000');
  assert.equal(formatSecurityDeposit(0), 'Nil Deposit');
  assert.equal(formatSecurityDeposit(null), 'Deposit on Request');
  assert.equal(formatSecurityDeposit(NaN), 'Deposit on Request');
});

test('invalid or unavailable area never becomes zero square feet', () => {
  assert.equal(formatPropertyArea(1800), '1,800 sq ft');
  for (const value of [0, -1, NaN, null, undefined]) {
    assert.equal(formatPropertyArea(value), null);
  }
});
