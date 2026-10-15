/**
 * F1 — canonical lessor contact.
 *
 * Drives the real service and mapper with a stubbed transport, so the exact request, the fail-closed
 * mapping and the "no protected value survives a refusal" rule are all exercised as runtime behaviour
 * rather than read out of the source.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { installOperationsActionHarness } from './support/operationsActionHarness.mjs';

const h = await installOperationsActionHarness();
const { service, session, presentation } = h;

const ACCOUNT = { id: 41, role: 'EMPLOYEE', token: 'token-account-contact' };

const contactPayload = (overrides = {}) => ({
  listingId: 8001,
  name: 'Rakesh Sharma',
  mobile: '+91 98260 11223',
  status: 'CONTACT_AVAILABLE',
  ...overrides
});

test('the contact read is an on-demand GET of the canonical route with the captured credential', async () => {
  h.reset(() => h.json(200, contactPayload()));
  h.signIn(ACCOUNT);
  const captured = session.captureOperationsSession();

  const contact = await service.lessorContactForRequest(501, { session: captured });

  assert.equal(h.calls.length, 1);
  assert.equal(h.calls[0].method, 'GET');
  assert.match(h.calls[0].url, /\/api\/v1\/operations\/visit-requests\/501\/lessor-contact$/);
  assert.equal(h.calls[0].body, undefined);
  assert.equal(h.calls[0].cache, 'no-store');
  assert.equal(h.calls[0].authorization, `Bearer ${ACCOUNT.token}`);
  assert.deepEqual(contact, {
    listingId: 8001,
    name: 'Rakesh Sharma',
    mobile: '+91 98260 11223',
    status: 'CONTACT_AVAILABLE'
  });
});

test('a Session item contact is read by session and item id, never by request id', async () => {
  h.reset(() => h.json(200, contactPayload({ listingId: 8123 })));
  h.signIn(ACCOUNT);

  const contact = await service.lessorContactForSessionItem(3108, 91, {
    session: session.captureOperationsSession()
  });

  assert.match(h.calls[0].url, /\/operations\/visit-sessions\/3108\/items\/91\/lessor-contact$/);
  assert.equal(contact.listingId, 8123);
});

test('a partial contact fails closed to CONTACT_UNAVAILABLE instead of showing half a record', () => {
  // The backend promises both values or neither; if that promise were ever broken, a name alone must
  // never be rendered or turned into a call link.
  for (const partial of [
    { name: 'Rakesh Sharma', mobile: null },
    { name: null, mobile: '+91 98260 11223' },
    { name: '   ', mobile: '+91 98260 11223' },
    { name: 'Rakesh Sharma', mobile: '' }
  ]) {
    const mapped = presentation.toLessorContact(contactPayload(partial));
    assert.equal(mapped.status, 'CONTACT_UNAVAILABLE');
    assert.equal(mapped.name, null);
    assert.equal(mapped.mobile, null);
  }
});

test('an unavailable outcome carries no contact value at all', () => {
  const mapped = presentation.toLessorContact({
    listingId: 8001,
    name: null,
    mobile: null,
    status: 'CONTACT_UNAVAILABLE'
  });
  assert.deepEqual(mapped, {
    listingId: 8001,
    name: null,
    mobile: null,
    status: 'CONTACT_UNAVAILABLE'
  });
  // A value smuggled alongside the unavailable status must not survive either.
  assert.equal(
    presentation.toLessorContact(contactPayload({ status: 'CONTACT_UNAVAILABLE' })).name,
    null
  );
  assert.equal(presentation.toLessorContact(null), null);
  assert.equal(presentation.toLessorContact('nope'), null);
});

test('a refused or missing root surfaces its real status so no contact can be shown', async () => {
  for (const status of [403, 404, 500]) {
    h.reset(() => h.json(status, { message: 'denied' }));
    h.signIn(ACCOUNT);
    await assert.rejects(
      service.lessorContactForRequest(501, { session: session.captureOperationsSession() }),
      (error) => error.status === status
    );
  }
});

test('revealed contact is never written to client storage', async () => {
  h.reset(() => h.json(200, contactPayload()));
  h.signIn(ACCOUNT);
  const before = [...h.storage.keys()].sort();

  const contact = await service.lessorContactForRequest(501, { session: session.captureOperationsSession() });
  assert.equal(contact.mobile, '+91 98260 11223');

  assert.deepEqual([...h.storage.keys()].sort(), before);
  const serializedStorage = [...h.storage.entries()].map(([key, value]) => `${key}=${value}`).join('|');
  assert.equal(serializedStorage.includes('98260'), false);
  assert.equal(serializedStorage.includes('Rakesh'), false);
});

test('a contact read issued by a previous account is refused after an account switch', async () => {
  const held = h.deferred();
  h.reset(() => held.promise);
  h.signIn(ACCOUNT);
  const captured = session.captureOperationsSession();
  const pending = service.lessorContactForRequest(501, { session: captured });

  h.signIn({ id: 77, role: 'EMPLOYEE', token: 'token-account-other' });
  held.resolve(h.json(200, contactPayload()));

  await assert.rejects(pending, (error) => session.isOperationsSessionChangedError(error));
});

/* ------------------------------------------------------------------ *
 * R8 — the revealable session statuses are the backend's, and only those
 * ------------------------------------------------------------------ */

test('a Session item contact is offered exactly for the backend-revealable statuses', () => {
  // Mirrors `OperationsLessorContactService.REVEALABLE_SESSION_STATUSES` exactly.
  for (const status of ['DRAFT', 'SCHEDULED', 'STARTED', 'INTERRUPTED']) {
    assert.equal(presentation.sessionPermitsItemContact(status), true, status);
  }
  for (const status of [
    'COMPLETED',
    'CANCELLED',
    'REPAIR_REQUIRED',
    'EXPIRED',
    null,
    undefined,
    '',
    'scheduled'
  ]) {
    assert.equal(presentation.sessionPermitsItemContact(status), false, String(status));
  }
});
