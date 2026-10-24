import test from 'node:test';
import assert from 'node:assert/strict';
import {
  hasOperationsWorkspaceAccess,
  hasRoutingWorkspaceAccess,
  operationsAuthorityScopeFingerprint,
  routingAuthorityScopeFingerprint,
  routingCapabilityScopes
} from '../utils/operationsWorkspacePresentation.ts';

const capabilities = (...entries) => ({
  staffActive: true,
  capabilities: entries,
  serverTime: '2026-10-23T12:00:00Z'
});

const grant = (capability, scopeType, cityId = null, teamId = null) => ({
  capability,
  scopeType,
  cityId,
  cityDisplayName: cityId === null ? null : `City ${cityId}`,
  teamId,
  teamDisplayName: teamId === null ? null : `Team ${teamId}`
});

test('City routing admits routing-only and broad City administrators without Team OE access', () => {
  const routingOnly = capabilities(grant('OPS_ROUTING', 'CITY', 5));
  assert.equal(hasRoutingWorkspaceAccess(routingOnly), true);
  assert.equal(hasOperationsWorkspaceAccess(routingOnly), false);
  assert.deepEqual(routingCapabilityScopes(routingOnly), [
    { cityId: 5, cityName: 'City 5' }
  ]);

  const broadAdmin = capabilities(grant('CITY_TEAM_ADMIN', 'CITY', 7));
  assert.equal(hasRoutingWorkspaceAccess(broadAdmin), true);
  assert.equal(hasOperationsWorkspaceAccess(broadAdmin), false);
});

test('combined responsibilities keep separate City routing and Team OE fingerprints', () => {
  const combined = capabilities(
    grant('OPS_COORDINATE', 'TEAM', 5, 11),
    grant('OPS_ROUTING', 'CITY', 5)
  );
  const initialRouting = routingAuthorityScopeFingerprint(combined);
  const initialOperations = operationsAuthorityScopeFingerprint(combined);

  const cityRevoked = capabilities(grant('OPS_COORDINATE', 'TEAM', 5, 11));
  assert.equal(routingAuthorityScopeFingerprint(cityRevoked), '');
  assert.equal(operationsAuthorityScopeFingerprint(cityRevoked), initialOperations);
  assert.notEqual(initialRouting, '');

  const teamRevoked = capabilities(grant('OPS_ROUTING', 'CITY', 5));
  assert.equal(routingAuthorityScopeFingerprint(teamRevoked), initialRouting);
  assert.equal(operationsAuthorityScopeFingerprint(teamRevoked), '');
  assert.notEqual(initialOperations, '');
});

test('routing-only grants never create broad City Admin or Lead authority', () => {
  const routingOnly = capabilities(grant('OPS_ROUTING', 'CITY', 5));
  assert.equal(hasOperationsWorkspaceAccess(routingOnly), false);
  assert.deepEqual(routingCapabilityScopes(capabilities(
    grant('OPS_ROUTING', 'CITY', 5),
    grant('OPS_ROUTING', 'CITY', 9)
  )).map((scope) => scope.cityId), [5, 9]);
});
