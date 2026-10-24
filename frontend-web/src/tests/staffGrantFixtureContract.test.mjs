import test from 'node:test';
import assert from 'node:assert/strict';
import { fixedGrantRequestError } from '../../browser-verification/automatic-routing-controlled/grantFixtureContract.mjs';

const command = (capability, scopeType, cityId, teamId) => ({
  capability,
  scopeType,
  cityId,
  teamId,
  effectiveAt: null,
  expiresAt: null,
  reasonCode: 'CONTROLLED_TEST'
});

test('controlled grant fixture accepts only the authoritative GLOBAL/CITY/TEAM shapes', () => {
  assert.equal(fixedGrantRequestError(command('STAFF_ADMIN', 'GLOBAL', null, null)), null);
  assert.equal(fixedGrantRequestError(command('OPS_ROUTING', 'CITY', 5, null)), null);
  assert.equal(fixedGrantRequestError(command('OPS_COORDINATE', 'TEAM', null, 9)), null);
  assert.equal(fixedGrantRequestError(command('OPS_SUPERVISE', 'TEAM', null, 9)), null);
});

test('controlled grant fixture rejects illegal scope FKs and capability combinations', () => {
  assert.match(
    fixedGrantRequestError(command('OPS_COORDINATE', 'TEAM', 5, 9)) ?? '',
    /incompatible/
  );
  assert.match(
    fixedGrantRequestError(command('STAFF_ADMIN', 'GLOBAL', 5, null)) ?? '',
    /incompatible/
  );
  assert.match(
    fixedGrantRequestError(command('OPS_ROUTING', 'CITY', null, 9)) ?? '',
    /incompatible/
  );
  assert.match(
    fixedGrantRequestError(command('OPS_COORDINATE', 'CITY', 5, null)) ?? '',
    /incompatible/
  );
});
