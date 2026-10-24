import test from 'node:test';
import assert from 'node:assert/strict';
import { reconcileRoutingPage } from '../utils/operationsRoutingPagination.ts';

test('routing pagination canonicalizes empty populations to page index zero', () => {
  assert.deepEqual(reconcileRoutingPage(0, 0), { page: 0, reload: false });
  assert.deepEqual(reconcileRoutingPage(2, 0), { page: 0, reload: false });
});

test('routing pagination preserves valid pages for 1, 20 and 21 records', () => {
  assert.deepEqual(reconcileRoutingPage(0, Math.ceil(1 / 20)), { page: 0, reload: false });
  assert.deepEqual(reconcileRoutingPage(0, Math.ceil(20 / 20)), { page: 0, reload: false });
  assert.deepEqual(reconcileRoutingPage(1, Math.ceil(21 / 20)), { page: 1, reload: false });
});

test('routing pagination clamps a shrinking last page exactly once', () => {
  assert.deepEqual(reconcileRoutingPage(3, 2), { page: 1, reload: true });
  assert.deepEqual(reconcileRoutingPage(1, 2), { page: 1, reload: false });
});
