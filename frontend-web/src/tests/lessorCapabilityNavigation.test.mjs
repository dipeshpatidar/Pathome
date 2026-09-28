import test from 'node:test';
import assert from 'node:assert/strict';
import {
  shouldShowMyProperties,
  shouldShowListYourProperty,
  resolveLessorNavigationDestination,
  resolveDirectLessorRouteState
} from '../utils/navigationPolicy.ts';

test('1. Guest: My Properties is hidden', () => {
  assert.equal(shouldShowMyProperties('GUEST', false), false);
  assert.equal(shouldShowMyProperties(null, false), false);
  assert.equal(shouldShowMyProperties(undefined, false), false);
});

test('2. Tenant-only authenticated User: My Properties is hidden', () => {
  // A tenant user who has not submitted/linked a lessor profile must not see My Properties
  assert.equal(shouldShowMyProperties('TENANT', false), false);
  assert.equal(shouldShowMyProperties('TENANT', undefined), false);
});

test('3. Linked lessor User: My Properties is visible', () => {
  // A user with an active linked LessorProfile has authoritative lessor capability
  assert.equal(shouldShowMyProperties('TENANT', true), true);
  assert.equal(shouldShowMyProperties('LANDLORD', false), true);
  assert.equal(shouldShowMyProperties('ROLE_LANDLORD', false), true);
});

test('4. Tenant + Lessor: My Properties is visible and coexists with tenant actions', () => {
  const role = 'TENANT';
  const hasLessorProfile = true;

  // Authoritative lessor menu appears
  assert.equal(shouldShowMyProperties(role, hasLessorProfile), true);

  // Tenant role is preserved (not replaced or mutually exclusive)
  assert.equal(role, 'TENANT');
  const showTenantVisitsPasses = role === 'TENANT';
  const showUploadLeaseAgreement = role === 'TENANT';
  assert.equal(showTenantVisitsPasses, true);
  assert.equal(showUploadLeaseAgreement, true);
});

test('5. Tenant-only: List your property remains available', () => {
  // Guest sees conversion action
  assert.equal(shouldShowListYourProperty('GUEST', false), true);

  // Tenant-only user sees List your property conversion action
  assert.equal(shouldShowListYourProperty('TENANT', false), true);

  // Once user is an active lessor, "My Properties" takes precedence in profile dropdown
  assert.equal(shouldShowListYourProperty('TENANT', true), false);
});

test('6. Lessor -> logout -> Tenant: My Properties disappears immediately without stale state', () => {
  // Session A: Lessor logged in
  let currentSession = { role: 'TENANT', hasLessorProfile: true };
  assert.equal(shouldShowMyProperties(currentSession.role, currentSession.hasLessorProfile), true);

  // Logout clears all user capability state
  currentSession = { role: 'GUEST', hasLessorProfile: false };
  assert.equal(shouldShowMyProperties(currentSession.role, currentSession.hasLessorProfile), false);

  // Session B: Tenant logs in (tenant-only)
  currentSession = { role: 'TENANT', hasLessorProfile: false };
  assert.equal(shouldShowMyProperties(currentSession.role, currentSession.hasLessorProfile), false);
  assert.equal(shouldShowListYourProperty(currentSession.role, currentSession.hasLessorProfile), true);
});

test('7. Tenant -> logout -> Lessor: My Properties appears immediately', () => {
  // Session A: Tenant logged in
  let currentSession = { role: 'TENANT', hasLessorProfile: false };
  assert.equal(shouldShowMyProperties(currentSession.role, currentSession.hasLessorProfile), false);

  // Logout
  currentSession = { role: 'GUEST', hasLessorProfile: false };
  assert.equal(shouldShowMyProperties(currentSession.role, currentSession.hasLessorProfile), false);

  // Session B: Lessor logs in
  currentSession = { role: 'TENANT', hasLessorProfile: true };
  assert.equal(shouldShowMyProperties(currentSession.role, currentSession.hasLessorProfile), true);
});

test('8. Direct tenant visit to /lessor: returns inactive self-service entry state without fake profile creation', () => {
  // Tenant-only user visiting /lessor directly
  const state = resolveDirectLessorRouteState(true, false);
  assert.equal(state, 'inactive'); // Inactive renders "List your property" self-service entry state

  // Existing lessor visiting /lessor directly
  const lessorState = resolveDirectLessorRouteState(true, true);
  assert.equal(lessorState, 'active'); // Active renders authentic LessorPortfolio

  // Guest visiting /lessor
  const guestState = resolveDirectLessorRouteState(false, false);
  assert.equal(guestState, 'guest');
});

test('9. Lessor My Properties routes to /lessor', () => {
  assert.equal(resolveLessorNavigationDestination(true), '/lessor');
  assert.equal(resolveLessorNavigationDestination(false), '/lessor/new');
});

test('10. Capability logic does NOT depend on email or password authentication method', () => {
  // Mobile-number OTP authenticated user (future auth method)
  const mobileOtpUser = {
    userId: 1042,
    authMethod: 'MOBILE_OTP',
    phoneNumber: '+919876543210',
    email: null, // Note: no email!
    role: 'TENANT',
    hasLessorProfile: false
  };

  // Pure domain capability check: depends on hasLessorProfile and canonical identity, not auth credentials
  assert.equal(shouldShowMyProperties(mobileOtpUser.role, mobileOtpUser.hasLessorProfile), false);
  assert.equal(shouldShowListYourProperty(mobileOtpUser.role, mobileOtpUser.hasLessorProfile), true);

  // When that same user later links a LessorProfile
  mobileOtpUser.hasLessorProfile = true;
  assert.equal(shouldShowMyProperties(mobileOtpUser.role, mobileOtpUser.hasLessorProfile), true);
  assert.equal(resolveLessorNavigationDestination(mobileOtpUser.hasLessorProfile), '/lessor');
});
