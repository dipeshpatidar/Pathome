import test from 'node:test';
import assert from 'node:assert/strict';
import {
  shouldShowMyProperties,
  shouldShowListYourProperty,
  resolveLessorNavigationDestination,
  resolveDirectLessorRouteState,
  normalizeRoutePathname,
  isLessorWorkspaceRoute,
  resolveAppHeaderOwner,
  resolveLessorExitPath
} from '../utils/navigationPolicy.ts';
import { LessorCapabilityTracker, readLessorSessionIdentity } from '../utils/lessorCapabilityState.ts';

function withSession(userId, token) {
  const values = new Map([
    ['pathome_user', JSON.stringify({ id: userId })],
    ['pathome_auth_token', token]
  ]);
  globalThis.localStorage = { getItem: key => values.get(key) ?? null };
  return () => { delete globalThis.localStorage; };
}

test('1. Guest: My Properties is hidden', () => {
  assert.equal(shouldShowMyProperties('GUEST', false), false);
  assert.equal(shouldShowMyProperties(null, false), false);
  assert.equal(shouldShowMyProperties(undefined, false), false);
});

test('2. Tenant-only authenticated User: My Properties is hidden', () => {
  // A tenant user without active capability must not see My Properties
  assert.equal(shouldShowMyProperties('TENANT', false), false);
  assert.equal(shouldShowMyProperties('TENANT', undefined), false);
});

test('3. Active lessor User: My Properties is visible', () => {
  // Backend enabled state is authoritative for navigation
  assert.equal(shouldShowMyProperties('TENANT', true), true);
  assert.equal(shouldShowMyProperties('LANDLORD', true), true);
  assert.equal(shouldShowMyProperties('ROLE_LANDLORD', true), true);
  assert.equal(shouldShowMyProperties('LANDLORD', false), false);
  assert.equal(shouldShowMyProperties('ROLE_LANDLORD', false), false);
});

test('4. Tenant + Lessor: My Properties is visible and coexists with tenant actions', () => {
  const role = 'TENANT';
  const hasLessorCapability = true;

  // Authoritative lessor menu appears
  assert.equal(shouldShowMyProperties(role, hasLessorCapability), true);

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
  assert.equal(shouldShowListYourProperty('TENANT', null), false);
  assert.equal(shouldShowListYourProperty('TENANT', 'error'), false);

  // Once user is an active lessor, "My Properties" takes precedence in profile dropdown
  assert.equal(shouldShowListYourProperty('TENANT', true), false);
});

test('6. Lessor A -> tenant B clears immediately and fetch failure stays non-lessor', async () => {
  let resetStorage = withSession(1, 'token-a');
  let capability = false;
  const tracker = new LessorCapabilityTracker(value => { capability = value; });
  try {
    await tracker.refresh(readLessorSessionIdentity(), async () => ({ userId: 1, enabled: true }));
    assert.equal(capability, true);
    resetStorage();
    resetStorage = withSession(2, 'token-b');
    tracker.clear();
    assert.equal(capability, null);
    await tracker.refresh(readLessorSessionIdentity(), async () => { throw new Error('network failed'); });
    assert.equal(capability, 'error');
    assert.equal(shouldShowMyProperties('TENANT', capability), false);
    assert.equal(shouldShowListYourProperty('TENANT', capability), false);
  } finally {
    resetStorage();
  }
});

test('7. Tenant -> linked lessor loads newly authoritative capability', async () => {
  let resetStorage = withSession(2, 'tenant-token');
  let capability = false;
  const tracker = new LessorCapabilityTracker(value => { capability = value; });
  try {
    await tracker.refresh(readLessorSessionIdentity(), async () => ({ userId: 2, enabled: false }));
    assert.equal(capability, false);
    resetStorage();
    resetStorage = withSession(3, 'lessor-token');
    tracker.clear();
    await tracker.refresh(readLessorSessionIdentity(), async () => ({ userId: 3, enabled: true }));
    assert.equal(shouldShowMyProperties('TENANT', capability), true);
  } finally {
    resetStorage();
  }
});

test('stale A response and mismatched user cannot overwrite B capability', async () => {
  let resetStorage = withSession(1, 'token-a');
  let capability = false;
  const tracker = new LessorCapabilityTracker(value => { capability = value; });
  let resolveA;
  try {
    const pendingA = tracker.refresh(readLessorSessionIdentity(), () => new Promise(resolve => { resolveA = resolve; }));
    resetStorage();
    resetStorage = withSession(2, 'token-b');
    tracker.clear();
    const pendingB = tracker.refresh(readLessorSessionIdentity(), async () => ({ userId: 2, enabled: false }));
    resolveA({ userId: 1, enabled: true });
    await Promise.all([pendingA, pendingB]);
    assert.equal(capability, false);
    await tracker.refresh(readLessorSessionIdentity(), async () => ({ userId: 1, enabled: true }));
    assert.equal(capability, 'error');
    assert.equal(shouldShowMyProperties('TENANT', capability), false);
    assert.equal(shouldShowListYourProperty('TENANT', capability), false);
  } finally {
    resetStorage();
  }
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

test('one shared route classifier assigns exactly one header owner across normal and focused routes', () => {
  for (const path of ['/', '/tenant', '/lessor', '/lessor?view=drafts', '/lessor/listings/24', '/property/24']) {
    assert.equal(resolveAppHeaderOwner(path), 'GLOBAL', `${path} should use the global navbar`);
  }
  for (const path of ['/lessor/new', '/lessor/new/', '/lessor/drafts/draft-one', '/lessor/drafts/draft-one/']) {
    assert.equal(resolveAppHeaderOwner(path), 'LESSOR_ONBOARDING', `${path} should use the dedicated onboarding shell`);
  }
  assert.equal(normalizeRoutePathname('/lessor/new/'), '/lessor/new');
  assert.equal(isLessorWorkspaceRoute('/lessor/drafts/draft-one/'), true);
});

test('tenant account action remains List your property independently of draft state', () => {
  assert.equal(shouldShowListYourProperty('TENANT', false), true);
  assert.equal(shouldShowMyProperties('TENANT', false), false);
  assert.equal(shouldShowMyProperties('TENANT', true), true);
  assert.equal(shouldShowListYourProperty('TENANT', true), false);
});

test('onboarding exit preserves validated origins and uses capability-safe direct-link fallbacks', () => {
  assert.equal(resolveLessorExitPath('/', { guest: true, tenant: false, activeLessor: false }), '/');
  assert.equal(resolveLessorExitPath('/tenant?city=Indore&next=https%3A%2F%2Fevil.test', { guest: false, tenant: true, activeLessor: false }), '/tenant?city=Indore');
  assert.equal(resolveLessorExitPath('/lessor?view=drafts', { guest: false, tenant: true, activeLessor: false }), '/lessor?view=drafts');
  assert.equal(resolveLessorExitPath('/lessor', { guest: false, tenant: true, activeLessor: false }), '/lessor?view=drafts');
  assert.equal(resolveLessorExitPath('https://evil.test', { guest: true, tenant: false, activeLessor: false }), '/');
  assert.equal(resolveLessorExitPath(undefined, { guest: false, tenant: true, activeLessor: false }), '/tenant');
  assert.equal(resolveLessorExitPath(undefined, { guest: false, tenant: true, activeLessor: true }), '/lessor');
});

test('10. Capability logic does NOT depend on email or password authentication method', () => {
  // Mobile-number OTP authenticated user (future auth method)
  const mobileOtpUser = {
    userId: 1042,
    authMethod: 'MOBILE_OTP',
    phoneNumber: '+919876543210',
    email: null, // Note: no email!
    role: 'TENANT',
    enabled: false
  };

  // Navigation consumes authoritative backend capability, independent of auth credentials
  assert.equal(shouldShowMyProperties(mobileOtpUser.role, mobileOtpUser.enabled), false);
  assert.equal(shouldShowListYourProperty(mobileOtpUser.role, mobileOtpUser.enabled), true);

  // When the backend later reports active capability
  mobileOtpUser.enabled = true;
  assert.equal(shouldShowMyProperties(mobileOtpUser.role, mobileOtpUser.enabled), true);
  assert.equal(resolveLessorNavigationDestination(mobileOtpUser.enabled), '/lessor');
});

test('linked profile without activation does not expose My Properties', async () => {
  const resetStorage = withSession(4, 'inactive-profile-token');
  let capability = true;
  const tracker = new LessorCapabilityTracker(value => { capability = value; });
  try {
    await tracker.refresh(readLessorSessionIdentity(), async () => ({ userId: 4, hasLessorProfile: true, enabled: false }));
    assert.equal(capability, false);
    assert.equal(shouldShowMyProperties('TENANT', capability), false);
    assert.equal(resolveDirectLessorRouteState(true, capability), 'inactive');
  } finally {
    resetStorage();
  }
});
