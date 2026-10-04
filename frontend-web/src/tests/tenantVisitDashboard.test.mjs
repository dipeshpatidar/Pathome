import test from 'node:test';
import assert from 'node:assert/strict';
import { belongsToTenantVisitSession, readTenantVisitSession, isCurrentTenantVisitSession } from '../utils/tenantVisitSession.ts';
import { appendUniqueVisitRequests, isActiveTenantVisitRequest, tenantVisitCtaLabel, tenantVisitStatusLabel, tenantVisitSummary, tenantVisitView } from '../utils/tenantVisitView.ts';

const withSession = (userId, token) => {
  const values = new Map([
    ['pathome_user', JSON.stringify({ id: userId })],
    ['pathome_auth_token', token]
  ]);
  globalThis.localStorage = { getItem: key => values.get(key) ?? null };
  return () => { delete globalThis.localStorage; };
};

test('loading, failure, empty, and populated history remain distinct', () => {
  const request = { requestId: 11 };
  assert.equal(tenantVisitView('loading', []), 'loading');
  assert.equal(tenantVisitView('error', []), 'error');
  assert.equal(tenantVisitView('ready', []), 'empty');
  assert.equal(tenantVisitView('ready', [request]), 'populated');
});

test('visit status uses existing RECEIVED meaning without claiming a scheduled visit', () => {
  assert.equal(tenantVisitStatusLabel('RECEIVED'), 'Request received');
  assert.equal(tenantVisitStatusLabel('COORDINATING'), 'Coordinating with property owner');
  assert.equal(tenantVisitStatusLabel('SCHEDULED'), 'Added to Visit Session');
  assert.equal(tenantVisitStatusLabel('UNAVAILABLE'), 'Owner unavailable for visits');
  assert.equal(tenantVisitStatusLabel('CANCELLED'), 'Request cancelled');
  assert.equal(tenantVisitStatusLabel('CHANGES_REQUIRED'), 'Changes required');
  assert.equal(tenantVisitStatusLabel(null), 'Status unavailable');
});

test('active visit CTAs route to tracking and terminal requests remain requestable', () => {
  assert.equal(tenantVisitCtaLabel('RECEIVED'), 'Visit Requested');
  assert.equal(tenantVisitCtaLabel('COORDINATING'), 'Visit Being Coordinated');
  assert.equal(tenantVisitCtaLabel('SCHEDULED'), 'View Visit');
  assert.equal(tenantVisitCtaLabel('UNKNOWN'), 'View My Visits');
  assert.equal(isActiveTenantVisitRequest('RECEIVED'), true);
  assert.equal(isActiveTenantVisitRequest('UNAVAILABLE'), false);
  assert.equal(isActiveTenantVisitRequest('CANCELLED'), false);
});

test('compact Visit Requests summary stays truthful for loading, error, empty, and populated states', () => {
  assert.equal(tenantVisitSummary('loading', 0), 'Loading requests…');
  assert.equal(tenantVisitSummary('error', 0), 'Requests unavailable');
  assert.equal(tenantVisitSummary('empty', 0), 'No requests yet');
  assert.equal(tenantVisitSummary('populated', 1), '1 request sent');
  assert.equal(tenantVisitSummary('populated', 3), '3 requests sent');
});

test('pagination keeps prior visits and ignores repeated request IDs', () => {
  const first = { requestId: 11, propertyTitle: 'First' };
  const second = { requestId: 12, propertyTitle: 'Second' };
  assert.deepEqual(appendUniqueVisitRequests([first], [first, second]), [first, second]);
  assert.deepEqual(appendUniqueVisitRequests([first], [second, second]), [first, second]);
});

test('a response from tenant A cannot enter tenant B session', () => {
  const clearA = withSession(101, 'token-a');
  const sessionA = readTenantVisitSession(101);
  assert.ok(sessionA);
  assert.equal(isCurrentTenantVisitSession(sessionA), true);
  assert.equal(belongsToTenantVisitSession(sessionA, 101), true);
  assert.equal(belongsToTenantVisitSession(sessionA, 202), false);
  clearA();

  const clearB = withSession(202, 'token-b');
  try {
    assert.equal(readTenantVisitSession(101), null);
    assert.equal(isCurrentTenantVisitSession(sessionA), false);
    assert.equal(readTenantVisitSession(202)?.userId, 202);
  } finally {
    clearB();
  }
});

test('logout and token replacement invalidate in-flight history responses', () => {
  const clear = withSession(101, 'old-token');
  const session = readTenantVisitSession(101);
  assert.ok(session);
  clear();
  assert.equal(isCurrentTenantVisitSession(session), false);
  const clearNew = withSession(101, 'new-token');
  try {
    assert.equal(isCurrentTenantVisitSession(session), false);
  } finally {
    clearNew();
  }
});
