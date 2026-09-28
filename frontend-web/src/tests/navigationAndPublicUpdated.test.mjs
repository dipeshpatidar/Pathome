import test from 'node:test';
import assert from 'node:assert/strict';
import {
  resolveWorkspaceContext,
  resolveLogoDestination
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
});

test('formatLastUpdated handles timezone-aware ISO-8601 timestamps and viewer-local calendar day', () => {
  // Absolute ISO-8601 UTC timestamp from backend (Instant with "Z")
  const isoUtcTimestamp = '2026-09-28T09:00:00Z';
  const parsedDate = new Date(isoUtcTimestamp);
  assert.equal(isNaN(parsedDate.getTime()), false, 'ISO string with Z must parse cleanly');

  // 1. When viewer's current time is on the same local calendar day as parsedDate
  const viewerNowToday = new Date(parsedDate);
  viewerNowToday.setHours(viewerNowToday.getHours() + 2); // 2 hours later same day
  const todayFormatted = formatLastUpdated(isoUtcTimestamp, viewerNowToday);
  assert.ok(todayFormatted?.startsWith('Updated today · '), `Expected today prefix, got ${todayFormatted}`);

  // 2. When viewer's current time is on the next local calendar day (timestamp is yesterday in viewer's local calendar)
  const viewerNowTomorrow = new Date(parsedDate);
  viewerNowTomorrow.setDate(viewerNowTomorrow.getDate() + 1);
  const yesterdayFormatted = formatLastUpdated(isoUtcTimestamp, viewerNowTomorrow);
  assert.ok(yesterdayFormatted?.startsWith('Updated yesterday · '), `Expected yesterday prefix, got ${yesterdayFormatted}`);

  // 3. When viewer's current time is several days later (older formatting)
  const viewerNowLater = new Date(parsedDate);
  viewerNowLater.setDate(viewerNowLater.getDate() + 7);
  const olderFormatted = formatLastUpdated(isoUtcTimestamp, viewerNowLater);
  assert.ok(!olderFormatted?.startsWith('Updated today') && !olderFormatted?.startsWith('Updated yesterday'));
  assert.ok(olderFormatted?.startsWith('Updated '), `Expected Updated prefix, got ${olderFormatted}`);

  // 4. Null timestamp returns null (remains hidden)
  assert.equal(formatLastUpdated(null), null);
  assert.equal(formatLastUpdated(undefined), null);
  assert.equal(formatLastUpdated(''), null);
  assert.equal(formatLastUpdated('invalid-timestamp'), null);
});

test('public discovery card uses live published timestamp and does not leak pending revisions', () => {
  // Simulating a live published listing with an unapproved draft revision
  const livePublishedListing = {
    id: 101,
    title: 'Live 2 BHK Flat',
    updatedAt: '2026-09-20T10:00:00Z',
    hasPendingRevision: true,
    pendingRevisionUpdatedAt: '2026-09-28T16:00:00Z' // Should NOT be exposed or used for card meta
  };

  const referenceNow = new Date('2026-09-28T18:00:00Z');

  // Card metadata MUST use live listing's updatedAt, NOT pending revision
  const publicCardMeta = formatLastUpdated(livePublishedListing.updatedAt, referenceNow);
  assert.equal(publicCardMeta?.startsWith('Updated today'), false);
  assert.equal(publicCardMeta?.startsWith('Updated yesterday'), false);
  // It is from 20 Sep (older)
  assert.ok(publicCardMeta?.includes('20 Sep 2026'));
});
