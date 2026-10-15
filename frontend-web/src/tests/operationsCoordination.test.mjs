/**
 * F2 — Request coordination and confirmed unavailability.
 *
 * The exact command bodies, the authoritative 200 response, the "unknown outcome is not a refusal"
 * rule and the cross-account guarantee are all driven through the real service.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { installOperationsActionHarness } from './support/operationsActionHarness.mjs';

const h = await installOperationsActionHarness();
const { service, session, presentation } = h;

const ACCOUNT = { id: 41, role: 'EMPLOYEE', token: 'token-account-coordination' };

const requestDetail = (overrides = {}) => ({
  requestId: 501,
  status: 'RECEIVED',
  version: 4,
  createdAt: '2026-10-12T21:25:30',
  cityId: 1,
  cityDisplayName: 'Indore',
  teamId: 7,
  teamDisplayName: 'Team A',
  listingId: 8001,
  listingTitle: '2 BHK apartment',
  listingAddress: 'Scheme No. 54',
  listingStatus: 'ACTIVE',
  listingCity: 'Indore',
  sector: 'Vijay Nagar',
  availabilityStartAt: '2026-10-13T04:00:00Z',
  availabilityEndAt: '2026-10-16T12:00:00Z',
  availabilityZoneId: 'Asia/Kolkata',
  preferredAt: null,
  sessionId: null,
  ...overrides
});

const sessionView = (overrides = {}) => ({
  sessionId: 3108,
  status: 'DRAFT',
  version: 1,
  city: 'Indore',
  areaName: 'Vijay Nagar',
  scheduledAt: null,
  reservedEndAt: null,
  durationMinutes: null,
  zoneId: null,
  representativeUserId: null,
  assignedAt: null,
  items: [
    {
      itemId: 91,
      listingId: 8001,
      title: '2 BHK apartment',
      sector: 'Vijay Nagar',
      position: 1,
      sourceRequestId: 501,
      derivedFromRequestId: null,
      origin: 'TENANT_REQUESTED',
      confirmationStatus: 'PENDING',
      removedAt: null,
      removalReason: null,
      propertyAvailabilityStartAt: null,
      propertyAvailabilityEndAt: null,
      propertyAvailabilityZoneId: null,
      propertyAvailabilitySource: null
    }
  ],
  linkedRequestVersions: [{ requestId: 501, version: 5 }],
  teamId: 7,
  teamDisplayName: 'Team A',
  coordinatorUserId: 41,
  updatedAt: '2026-10-12T16:00:00Z',
  tenantConfirmationState: 'NOT_REQUIRED',
  startedAt: null,
  expiresAt: null,
  executionStateChangedAt: null,
  ...overrides
});

test('coordinating into a new session sends exactly the null-target command and applies the returned session', async () => {
  h.reset(() => h.json(200, sessionView()));
  h.signIn(ACCOUNT);

  const created = await service.coordinateRequest(
    501,
    { expectedRequestVersion: 4, sessionId: null, expectedSessionVersion: null },
    { session: sessionModule() }
  );

  assert.equal(h.calls.length, 1);
  assert.equal(h.calls[0].method, 'POST');
  assert.match(h.calls[0].url, /\/operations\/visit-requests\/501\/coordinate$/);
  assert.equal(h.calls[0].contentType, 'application/json');
  assert.deepEqual(h.calls[0].body, { expectedRequestVersion: 4, sessionId: null, expectedSessionVersion: null });
  assert.equal(created.sessionId, 3108);
  assert.equal(created.status, 'DRAFT');
  assert.deepEqual(created.linkedRequestVersions, [{ requestId: 501, version: 5 }]);
});

test('attaching to an owned draft sends both roots and their versions', async () => {
  h.reset(() => h.json(200, sessionView({ sessionId: 3200, version: 9 })));
  h.signIn(ACCOUNT);

  const result = await service.coordinateRequest(
    501,
    { expectedRequestVersion: 4, sessionId: 3200, expectedSessionVersion: 9 },
    { session: sessionModule() }
  );

  assert.deepEqual(h.calls[0].body, {
    expectedRequestVersion: 4,
    sessionId: 3200,
    expectedSessionVersion: 9
  });
  assert.equal(result.sessionId, 3200);
  assert.equal(result.version, 9);
});

test('the command builders refuse a linked request or a missing version', () => {
  const linked = presentation.toRequestDetail(requestDetail({ status: 'COORDINATING', sessionId: 3108 }));
  assert.equal(presentation.coordinateNewSessionCommand(linked), null);
  assert.equal(presentation.coordinateAttachCommand(linked, 3200, 3), null);
  assert.equal(presentation.requestUnavailableCommand(linked), null);

  const versionless = presentation.toRequestDetail(requestDetail({ version: null }));
  assert.equal(presentation.coordinateNewSessionCommand(versionless), null);
  assert.equal(presentation.requestUnavailableCommand(versionless), null);

  const ok = presentation.toRequestDetail(requestDetail());
  assert.deepEqual(presentation.coordinateNewSessionCommand(ok), {
    targetType: 'REQUEST',
    expectedRequestVersion: 4,
    sessionId: null,
    expectedSessionVersion: null
  });
  assert.deepEqual(presentation.coordinateAttachCommand(ok, 3200, 9), {
    targetType: 'REQUEST',
    expectedRequestVersion: 4,
    sessionId: 3200,
    expectedSessionVersion: 9
  });
});

test('confirmed unavailability sends only the Request version and applies the returned request', async () => {
  h.reset(() => h.json(200, requestDetail({ status: 'UNAVAILABLE', version: 5 })));
  h.signIn(ACCOUNT);

  const result = await service.markRequestUnavailable(501, 4, { session: sessionModule() });

  assert.equal(h.calls[0].method, 'POST');
  assert.match(h.calls[0].url, /\/operations\/visit-requests\/501\/unavailable$/);
  assert.deepEqual(h.calls[0].body, { expectedRequestVersion: 4 });
  // No note, call attempt, source or reason may be invented for this command.
  assert.deepEqual(Object.keys(h.calls[0].body), ['expectedRequestVersion']);
  assert.equal(result.status, 'UNAVAILABLE');
  assert.equal(result.version, 5);
});

test('a transmitted command with an undecodable body is unconfirmed, never treated as a refusal', async () => {
  h.reset(() => h.undecodable(200));
  h.signIn(ACCOUNT);

  await assert.rejects(
    service.coordinateRequest(
      501,
      { expectedRequestVersion: 4, sessionId: null, expectedSessionVersion: null },
      { session: sessionModule() }
    ),
    (error) => {
      // The transport reports the real status, so the caller classifies "dispatched + no usable body".
      assert.equal(error.status, 200);
      assert.equal(presentation.ownershipFailureKind({ dispatched: true, status: error.status }), 'uncertain');
      return true;
    }
  );
});

test('a 409 conflict and a denied root keep their exact status for refresh-and-review handling', async () => {
  for (const status of [400, 403, 404, 409]) {
    h.reset(() => h.json(status, { message: 'conflict' }));
    h.signIn(ACCOUNT);
    await assert.rejects(
      service.coordinateRequest(
        501,
        { expectedRequestVersion: 4, sessionId: null, expectedSessionVersion: null },
        { session: sessionModule() }
      ),
      (error) => error.status === status
    );
  }
});

test('a paused coordinate response can never be applied to the account that signed in next', async () => {
  const held = h.deferred();
  h.reset(() => held.promise);
  h.signIn(ACCOUNT);
  const captured = session.captureOperationsSession();

  const pending = service.coordinateRequest(
    501,
    { expectedRequestVersion: 4, sessionId: null, expectedSessionVersion: null },
    { session: captured }
  );

  h.signIn({ id: 77, role: 'EMPLOYEE', token: 'token-other-account' });
  held.resolve(h.json(200, sessionView()));

  await assert.rejects(pending, (error) => session.isOperationsSessionChangedError(error));
});

test('a missing session fails closed before anything is transmitted', async () => {
  h.reset(() => h.json(200, sessionView()));
  h.signOut();
  await assert.rejects(
    service.coordinateRequest(
      501,
      { expectedRequestVersion: 4, sessionId: null, expectedSessionVersion: null },
      {}
    ),
    (error) => error.status === 401
  );
  assert.equal(h.calls.length, 0);
});

/** The current captured session, mirroring how the workspace passes one credential to one command. */
function sessionModule() {
  return session.captureOperationsSession();
}
