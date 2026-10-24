import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  assertExpectedStatus,
  assertRequiredBody,
  awaitSettled,
  requireObservedResponse,
  RoutingHttpContractError,
  validateObservedResponses
} from '../lib/routing-http-contract.mjs';

const taskPath = '/api/v1/operations/automatic-routing/tasks/unclaimed?page=0&size=20';

test('the old broad status predicate accepts failures while the exact contract rejects each one', () => {
  for (const status of [401, 403, 404, 409, 500]) {
    assert.equal(status >= 200, true, 'negative control must reproduce the old false positive');
    assert.throws(() => assertExpectedStatus('GET', taskPath, status),
      RoutingHttpContractError, String(status));
  }
  assert.equal(assertExpectedStatus('GET', taskPath, 200), true);
  assert.equal(assertExpectedStatus('GET', '/api/v1/lessor/guest/drafts/resume', 204), true);
});

test('late and unexpected API responses fail the settled response ledger', () => {
  assert.equal(validateObservedResponses([
    { method: 'GET', path: taskPath, status: 200 }
  ]), true);
  assert.throws(() => validateObservedResponses([
    { method: 'GET', path: taskPath, status: 200 },
    { method: 'GET', path: taskPath, status: 500 }
  ]), /returned 500/);
});

test('missing, timed-out and malformed required responses fail closed', async () => {
  assert.throws(() => requireObservedResponse([], 'GET', taskPath), /was missing/);
  await assert.rejects(() => awaitSettled(
    new Promise(() => {}), 'late response', 25), /timed out/);
  assert.throws(() => assertRequiredBody('GET', taskPath, { items: 'not-an-array', totalCount: 0 }),
    /malformed/);
  assert.throws(() => assertRequiredBody('POST', '/api/v1/auth/login', { token: null }),
    /settled token/);
  assert.throws(() => assertRequiredBody('GET',
    '/api/v1/operations/automatic-routing/coverage/options',
    { city: {}, localities: [], teams: 'not-an-array' }), /malformed/);
});
