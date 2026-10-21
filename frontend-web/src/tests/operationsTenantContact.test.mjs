/**
 * R1-OE-1 tenant-contact transport and fail-closed decoding. The production service and mapper are
 * used; only the network is stubbed.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { installOperationsActionHarness } from './support/operationsActionHarness.mjs';

const h = await installOperationsActionHarness();
const { service, session } = h;
const ACCOUNT = { id: 41, role: 'EMPLOYEE', token: 'token-tenant-contact' };

const contact = (overrides = {}) => ({
  requestId: 501,
  name: 'Asha Verma',
  mobile: '+91 98765 43210',
  status: 'CONTACT_AVAILABLE',
  ...overrides
});

test('request tenant contact is an on-demand no-store GET of the exact root route', async () => {
  h.reset(() => h.json(200, contact()));
  h.signIn(ACCOUNT);
  const captured = session.captureOperationsSession();

  const result = await service.tenantContactForRequest(501, { session: captured });

  assert.equal(h.calls[0].method, 'GET');
  assert.ok(h.calls[0].url.endsWith('/api/v1/operations/visit-requests/501/tenant-contact'));
  assert.equal(h.calls[0].cache, 'no-store');
  assert.equal(result.name, 'Asha Verma');
  assert.equal(result.mobile, '+91 98765 43210');
  assert.equal(result.requestId, 501);
});

test('session tenant contact uses only the session root identifier', async () => {
  h.reset(() => h.json(200, {
    sessionId: 700,
    name: 'Asha Verma',
    mobile: '+91 98765 43210',
    status: 'CONTACT_AVAILABLE'
  }));
  h.signIn(ACCOUNT);

  const result = await service.tenantContactForSession(700);

  assert.ok(h.calls[0].url.endsWith('/api/v1/operations/visit-sessions/700/tenant-contact'));
  assert.equal(result.sessionId, 700);
  assert.equal(result.name, 'Asha Verma');
});

test('a partial contact is downgraded to unavailable instead of rendering half a record', async () => {
  h.reset(() => h.json(200, contact({ name: 'Asha Verma', mobile: null, email: 'asha@example.test' })));
  h.signIn(ACCOUNT);

  const result = await service.tenantContactForRequest(501);

  assert.equal(result.status, 'CONTACT_UNAVAILABLE');
  assert.equal(result.name, null);
  assert.equal(result.mobile, null);
});

test('a response arriving after the account changed is rejected rather than returned', async () => {
  const pending = h.deferred();
  h.reset(() => pending.promise);
  h.signIn(ACCOUNT);

  const request = service.tenantContactForRequest(501);
  h.signIn({ id: 99, role: 'EMPLOYEE', token: 'token-different-account' });
  pending.resolve(h.json(200, contact()));

  await assert.rejects(request, (error) => session.isOperationsSessionChangedError(error));
});

test('a revealed tenant contact is never written to browser storage', async () => {
  h.reset(() => h.json(200, contact()));
  h.signIn(ACCOUNT);

  await service.tenantContactForRequest(501);

  const stored = JSON.stringify([...h.storage.entries()]);
  assert.equal(stored.includes('Asha Verma'), false);
  assert.equal(stored.includes('98765'), false);
});
