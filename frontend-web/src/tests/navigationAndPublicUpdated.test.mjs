import test from 'node:test';
import assert from 'node:assert/strict';
import {
  resolveWorkspaceContext,
  resolveLogoDestination,
  resolveOnboardingExitDestination
} from '../utils/navigationPolicy.ts';
import { formatLastUpdated } from '../utils/lessorFormatting.ts';

test('resolveWorkspaceContext respects current workspace context over user role', () => {
  // Guest browsing marketplace
  assert.equal(resolveWorkspaceContext('/', 'GUEST'), 'PUBLIC');
  assert.equal(resolveWorkspaceContext('/', null), 'PUBLIC');

  // Authenticated tenant browsing marketplace
  assert.equal(resolveWorkspaceContext('/', 'TENANT'), 'TENANT');

  // Multi-role user (TENANT + LESSOR capability) browsing rental homes -> marketplace context
  assert.equal(resolveWorkspaceContext('/', 'TENANT'), 'TENANT');

  // Same user inside lessor workspace -> LESSOR context
  assert.equal(resolveWorkspaceContext('/lessor', 'TENANT'), 'LESSOR');
  assert.equal(resolveWorkspaceContext('/lessor/new', 'TENANT'), 'LESSOR');
  assert.equal(resolveWorkspaceContext('/lessor/drafts/draft-123', 'TENANT'), 'LESSOR');

  // Admin on /admin route
  assert.equal(resolveWorkspaceContext('/admin', 'ADMIN'), 'ADMIN');
  assert.equal(resolveWorkspaceContext('/admin', 'SUPER_ADMIN'), 'ADMIN');

  // Sub-admin on /admin route
  assert.equal(resolveWorkspaceContext('/admin', 'SUB_ADMIN'), 'SUB_ADMIN');

  // OE / GE context detection
  assert.equal(resolveWorkspaceContext('/oe', 'OE'), 'OE');
  assert.equal(resolveWorkspaceContext('/ge', 'GE'), 'GE');
});

test('resolveLogoDestination maps to authoritative workspace homes', () => {
  // Public/Guest logo destination
  const publicDest = resolveLogoDestination('PUBLIC');
  assert.equal(publicDest.path, '/');
  assert.equal(publicDest.isAvailable, true);

  // Tenant rental-browsing logo destination
  const tenantDest = resolveLogoDestination('TENANT');
  assert.equal(tenantDest.path, '/');
  assert.equal(tenantDest.isAvailable, true);

  // Lessor workspace logo destination
  const lessorDest = resolveLogoDestination('LESSOR');
  assert.equal(lessorDest.path, '/lessor');
  assert.equal(lessorDest.isAvailable, true);

  // Admin workspace logo destination
  const adminDest = resolveLogoDestination('ADMIN');
  assert.equal(adminDest.path, '/admin');
  assert.equal(adminDest.isAvailable, true);

  // Sub-admin workspace logo destination
  const subAdminDest = resolveLogoDestination('SUB_ADMIN');
  assert.equal(subAdminDest.path, '/admin');
  assert.equal(subAdminDest.isAvailable, true);

  // Unimplemented OE / GE report isAvailable: false
  const oeDest = resolveLogoDestination('OE');
  assert.equal(oeDest.isAvailable, false);
  const geDest = resolveLogoDestination('GE');
  assert.equal(geDest.isAvailable, false);
});

test('resolveOnboardingExitDestination directs guest to / and authenticated lessor to /lessor', () => {
  // Guest onboarding Exit preserves draft and goes to main marketplace
  assert.equal(resolveOnboardingExitDestination(true), '/');

  // Authenticated lessor onboarding Exit preserves draft and goes to Lessor Home
  assert.equal(resolveOnboardingExitDestination(false), '/lessor');
});

test('formatLastUpdated produces required public card metadata format', () => {
  const referenceNow = new Date(2026, 9, 28, 14, 0, 0); // 28 Oct 2026 2:00 PM

  // Today
  const todayMorning = new Date(2026, 9, 28, 10, 30, 0);
  assert.equal(formatLastUpdated(todayMorning, referenceNow), 'Updated today · 10:30 AM');

  // Yesterday
  const yesterdayEvening = new Date(2026, 9, 27, 18, 15, 0);
  assert.equal(formatLastUpdated(yesterdayEvening, referenceNow), 'Updated yesterday · 6:15 PM');

  // Older date (e.g. 21 Oct 2026 · 10:40 PM)
  const olderDate = new Date(2026, 9, 21, 22, 40, 0);
  assert.equal(formatLastUpdated(olderDate, referenceNow), 'Updated 21 Oct 2026 · 10:40 PM');

  // Null, undefined, empty, or invalid timestamps return null to omit metadata cleanly
  assert.equal(formatLastUpdated(null), null);
  assert.equal(formatLastUpdated(undefined), null);
  assert.equal(formatLastUpdated(''), null);
  assert.equal(formatLastUpdated('invalid-timestamp'), null);
});
