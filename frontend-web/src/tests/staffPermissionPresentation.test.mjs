import test from 'node:test';
import assert from 'node:assert/strict';
import {
  factMatchesGrantReview,
  factMatchesReviewedGrant,
  staffGrantCommandForReview,
  grantState
} from '../utils/staffPermissionPresentation.ts';

const fact = (overrides = {}) => ({
  capability: 'OPS_ROUTING',
  scopeType: 'CITY',
  cityId: 5,
  teamId: null,
  effectiveAt: '2026-10-23T12:00:00Z',
  expiresAt: null,
  revokedAt: null,
  ...overrides
});

test('an unverified fact is never labelled active from a browser date comparison', () => {
  assert.deepEqual(grantState(fact(), true, '2026-10-23T12:05:00Z'), {
    label: 'Recorded · effectiveness not verified',
    tone: 'recorded'
  });
  assert.deepEqual(grantState(fact({ effectiveAt: '2099-01-01T00:00:00Z' }), true, null), {
    label: 'Recorded · date status unverified',
    tone: 'recorded'
  });
});

test('server time distinguishes future, expired, revoked and inactive records truthfully', () => {
  const serverTime = '2026-10-23T12:00:00Z';
  assert.equal(grantState(fact({ effectiveAt: '2026-10-23T13:00:00Z' }), true, serverTime).tone, 'future');
  assert.equal(grantState(fact({ expiresAt: '2026-10-23T11:00:00Z' }), true, serverTime).tone, 'expired');
  assert.equal(grantState(fact({ revokedAt: '2026-10-23T11:30:00Z' }), true, serverTime).tone, 'revoked');
  assert.equal(grantState(fact(), false, serverTime).tone, 'inactive');
});

test('readback matching requires the exact scope, null no-expiry and explicit timestamps', () => {
  const review = {
    capability: 'OPS_ROUTING',
    scopeType: 'CITY',
    cityId: 5,
    teamId: null,
    effectiveAt: '2026-10-23T12:00:00.000Z',
    expiresAt: ''
  };
  assert.equal(factMatchesGrantReview(fact(), review), true);
  assert.equal(factMatchesGrantReview(fact({ cityId: 6 }), review), false);
  assert.equal(factMatchesGrantReview(
    fact({ effectiveAt: '2026-10-24T12:00:00Z' }), review
  ), false);
  assert.equal(factMatchesGrantReview(fact({ expiresAt: '2026-11-23T12:00:00Z' }), review),
    false, 'unlimited review cannot match a finite grant');
  assert.equal(factMatchesGrantReview(fact({ effectiveAt: 'server-default' }),
    { ...review, effectiveAt: '' }), false, 'an invalid server time is never a match');
  assert.equal(factMatchesGrantReview(fact(), { ...review, effectiveAt: '' }), true,
    'the server-selected immediate effective time is accepted');
  assert.equal(factMatchesGrantReview(
    fact({ expiresAt: null }),
    { ...review, expiresAt: '2026-11-23T12:00:00Z' }
  ), false, 'an explicit expiry cannot match no expiry');
});

test('TEAM grant command has only its Team FK while authoritative readback keeps derived City', () => {
  const review = {
    capability: 'OPS_COORDINATE',
    scopeType: 'TEAM',
    cityId: 6,
    teamId: 12,
    effectiveAt: '',
    expiresAt: ''
  };
  assert.deepEqual(staffGrantCommandForReview(review, 'TEAM_ASSIGNMENT'), {
    capability: 'OPS_COORDINATE',
    scopeType: 'TEAM',
    cityId: null,
    teamId: 12,
    effectiveAt: null,
    expiresAt: null,
    reasonCode: 'TEAM_ASSIGNMENT'
  });
  assert.equal(factMatchesGrantReview(fact({
    capability: 'OPS_COORDINATE', scopeType: 'TEAM', cityId: 6, teamId: 12,
    effectiveAt: '2026-10-23T12:00:00Z'
  }), review), true);
});

test('GLOBAL, CITY and supported capability commands serialize exact FK shapes', () => {
  const cases = [
    ['STAFF_ADMIN', 'GLOBAL', 5, 9, null, null],
    ['OPS_ROUTING', 'CITY', 5, 9, 5, null],
    ['OPS_SUPERVISE', 'TEAM', 5, 9, null, 9]
  ];
  for (const [capability, scopeType, cityId, teamId, expectedCity, expectedTeam] of cases) {
    const command = staffGrantCommandForReview({
      capability, scopeType, cityId, teamId, effectiveAt: '', expiresAt: ''
    }, 'SCOPE_TEST');
    assert.equal(command.cityId, expectedCity);
    assert.equal(command.teamId, expectedTeam);
  }
});

test('revocation matching requires the exact reviewed grant and cannot match another grant', () => {
  const reviewed = fact();
  assert.equal(factMatchesReviewedGrant(fact(), reviewed), true);
  assert.equal(factMatchesReviewedGrant(fact({ expiresAt: '2026-12-01T00:00:00Z' }), reviewed),
    false);
  assert.equal(factMatchesReviewedGrant(fact({ teamId: 9 }), reviewed), false);
});
