import test from 'node:test';
import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import { resolve as resolveOperationsModule } from './support/operationsServiceLoader.mjs';

registerHooks({ resolve: resolveOperationsModule });

const {
  hasRoutingWorkspaceAccess,
  routingAuthorityScopeFingerprint,
  toStaffCapabilities
} = await import('../utils/operationsWorkspacePresentation.ts');
const {
  awaitOperationsCapabilityRequest,
  resolveOperationsAuthorityView
} = await import('../hooks/useOperationsCapability.ts');

const routingGrant = (cityId = 5) => ({
  capability: 'OPS_ROUTING',
  scopeType: 'CITY',
  cityId,
  cityDisplayName: `City ${cityId}`,
  teamId: null,
  teamDisplayName: null
});

const validResponse = (...capabilities) => ({
  staffActive: true,
  capabilities,
  serverTime: '2026-10-24T15:00:00Z'
});

test('strict capability parsing preserves valid granted and zero-grant responses', () => {
  const granted = toStaffCapabilities(validResponse(routingGrant(5)));
  assert.equal(hasRoutingWorkspaceAccess(granted), true);
  assert.deepEqual(routingAuthorityScopeFingerprint(granted), 'OPS_ROUTING:CITY:5');

  const zeroGrants = toStaffCapabilities(validResponse());
  assert.deepEqual(zeroGrants.capabilities, []);
  assert.equal(hasRoutingWorkspaceAccess(zeroGrants), false);

  const teamOnly = toStaffCapabilities(validResponse({
    capability: 'OPS_COORDINATE',
    scopeType: 'TEAM',
    cityId: 5,
    cityDisplayName: 'City 5',
    teamId: 9,
    teamDisplayName: 'Team 9'
  }));
  assert.equal(hasRoutingWorkspaceAccess(teamOnly), false);
});

test('strict capability parsing rejects malformed required response structure', () => {
  const malformed = [
    null,
    [],
    { capabilities: [] },
    { staffActive: 'true', capabilities: [] },
    { staffActive: true },
    { staffActive: true, capabilities: null },
    { staffActive: true, capabilities: {} },
    validResponse(null),
    validResponse({}),
    validResponse({ ...routingGrant(), capability: null }),
    validResponse({ ...routingGrant(), capability: 'UNKNOWN_CAPABILITY' }),
    validResponse({ ...routingGrant(), scopeType: 'UNKNOWN_SCOPE' }),
    validResponse({ ...routingGrant(), cityId: '5' }),
    validResponse({ ...routingGrant(), cityDisplayName: 5 })
  ];

  for (const payload of malformed) {
    assert.throws(
      () => toStaffCapabilities(payload),
      /Staff capability response|capability response/i,
      JSON.stringify(payload)
    );
  }
});

test('terminal authority states take precedence over missing admission', () => {
  assert.equal(
    resolveOperationsAuthorityView({ state: 'restricted', entryAdmitted: false }),
    'restricted'
  );
  assert.equal(
    resolveOperationsAuthorityView({ state: 'unavailable', entryAdmitted: false }),
    'unavailable'
  );
  assert.equal(
    resolveOperationsAuthorityView({ state: 'checking', entryAdmitted: false }),
    'checking'
  );
  assert.equal(
    resolveOperationsAuthorityView({ state: 'granted', entryAdmitted: false }),
    'checking'
  );
  assert.equal(
    resolveOperationsAuthorityView({ state: 'granted', entryAdmitted: true }),
    'granted'
  );
});

test('a current capability check times out once and aborts its owned transport', async () => {
  const control = new AbortController();
  let settleRequest;
  const request = new Promise((resolve) => {
    settleRequest = resolve;
  });

  const outcome = await awaitOperationsCapabilityRequest(request, control, 15);

  assert.equal(outcome.kind, 'timeout');
  assert.equal(control.signal.aborted, true);

  // A transport that ignores abort and settles later cannot create a second outcome.
  settleRequest(validResponse(routingGrant()));
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(outcome.kind, 'timeout');
});

test('retiring a check resolves silently and cancels its timer before timeout', async () => {
  const control = new AbortController();
  control.abort();

  const startedAt = Date.now();
  const outcome = await awaitOperationsCapabilityRequest(
    new Promise(() => {}),
    control,
    100
  );

  assert.equal(outcome.kind, 'aborted');
  assert.ok(Date.now() - startedAt < 80, 'retirement must not wait for the timeout');
});
