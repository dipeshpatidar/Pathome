import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

import {
  applyRecoveredMedia,
  applySavedEditorSection,
  dirtyEditorSections,
  editorSectionValues,
  isEditorSectionDirty
} from '../utils/backofficeEditorState.ts';
import {
  canRequestLessorRemoval,
  latestLessorRemoval,
  lessorRemovalState,
  showLessorListingChanges
} from '../utils/lessorRemovalPresentation.ts';
import { isLatestRequest } from '../utils/requestEpoch.ts';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../../..');
const read = relative => readFile(path.join(root, relative), 'utf8');

const snapshot = (overrides = {}) => ({
  title: 'Light-filled family apartment',
  titleProvenance: 'EXPLICIT',
  description: 'A calm home.',
  propertyType: 'FLAT',
  bhkCount: '2BHK',
  monthlyRent: 28500,
  securityDeposit: 57000,
  availableFrom: '2026-11-01',
  maintenanceCharge: 1800,
  brokerageAmount: 0,
  brokerageDays: 0,
  securityDepositMonths: 2,
  totalAreaSqFt: 890,
  floorNumber: 4,
  totalFloors: 9,
  furnishingStatus: 'SEMI_FURNISHED',
  amenities: 'Lift, parking',
  bathroomCount: 2,
  vastuFacing: 'East',
  bachelorAllowed: true,
  preferredTenant: 'Working professionals',
  address: '12 Palm Residency',
  landmark: 'District park',
  colony: 'Palm Residency',
  pincode: '452010',
  media: [],
  ...overrides
});

const removal = (requestStatus, requestId = 1) => ({
  requestId,
  listingId: 42,
  requestStatus,
  requestVersion: 0,
  currentListingStatus: 'PUBLISHED',
  currentListingVersion: 12,
  requestedAt: '2026-10-24T08:30:00Z',
  terminalAt: null,
  ownerReason: 'Relocating',
  decisionReason: null,
  permittedActions: []
});

test('editor dirty state tracks zero, one and multiple sections from real values', () => {
  const saved = snapshot();
  assert.deepEqual(dirtyEditorSections(saved, saved), []);

  const oneChange = snapshot({ monthlyRent: 30000 });
  assert.deepEqual(dirtyEditorSections(oneChange, saved), ['commercial']);
  assert.equal(isEditorSectionDirty(oneChange, saved, 'commercial'), true);
  assert.equal(isEditorSectionDirty(oneChange, saved, 'basics'), false);

  const twoChanges = snapshot({ monthlyRent: 30000, title: 'Updated title' });
  assert.deepEqual(dirtyEditorSections(twoChanges, saved), ['basics', 'commercial']);
});

test('media recovery commits by stable identity after reorder or same-position replacement', () => {
  const media = [
    { mediaId: 'media-a', url: '/a.jpg', mediaType: 'IMAGE', roomTag: 'LIVING_ROOM',
      caption: 'A', sortOrder: 0, cover: true, uploadStatus: 'PROCESSING', sourceMediaAssetId: 10 },
    { mediaId: 'media-b', url: '/b.jpg', mediaType: 'IMAGE', roomTag: 'KITCHEN',
      caption: 'B', sortOrder: 1, cover: false, uploadStatus: 'UPLOADED', sourceMediaAssetId: 11 }
  ];
  const reordered = snapshot({ media: [media[1], media[0]] });
  const recovered = applyRecoveredMedia(reordered, 'media-a',
    { url: '/recovered-a.jpg', uploadStatus: 'UPLOADED' });
  assert.equal(recovered.media[0].mediaId, 'media-b');
  assert.equal(recovered.media[0].url, '/b.jpg');
  assert.equal(recovered.media[1].mediaId, 'media-a');
  assert.equal(recovered.media[1].url, '/recovered-a.jpg');

  const replacement = snapshot({ media: [
    { ...media[1], sortOrder: 0 },
    { ...media[0], mediaId: 'media-c', sortOrder: 1 }
  ] });
  const unchanged = applyRecoveredMedia(replacement, 'media-a',
    { url: '/must-not-apply.jpg', uploadStatus: 'UPLOADED' });
  assert.deepEqual(unchanged, replacement);
});

test('saving one section preserves unsaved values in every other section', () => {
  const saved = snapshot();
  const localDraft = snapshot({ title: 'Local basics edit', monthlyRent: 31000 });
  const serverSaved = snapshot({ title: 'Server-confirmed basics' });
  const merged = applySavedEditorSection(localDraft, serverSaved, 'basics');

  assert.equal(merged.title, 'Server-confirmed basics');
  assert.equal(merged.monthlyRent, 31000);
  assert.deepEqual(dirtyEditorSections(merged, serverSaved), ['commercial']);
  assert.deepEqual(editorSectionValues(merged, 'basics'), editorSectionValues(serverSaved, 'basics'));
});

test('editor uses the server-confirmed baseline and never claims unconfirmed saves', async () => {
  const editor = await read('frontend-web/src/components/backoffice/BackofficePropertyEditor.tsx');
  assert.match(editor, /const \[savedContent, setSavedContent\]/);
  assert.match(editor, /const dirtySections = dirtyEditorSections\(draft, savedContent\)/);
  assert.match(editor, /const saved = await operation\(\);[\s\S]*setSavedContent\(saved\.content\)[\s\S]*setNotice\(`\$\{label\} saved\.`\)/);
  assert.match(editor, /was not confirmed by the server[\s\S]*Not saved/);
  assert.match(editor, /disabled=\{Boolean\(busy\) \|\| dirty \|\| !revision\.preview\.ready\}/);
  assert.match(editor, /Save these sections before publishing/);
  assert.match(editor, /aria-label="Editor sections"/);
  assert.doesNotMatch(editor, /saved as revision version/i);
});

test('activity history exposes real cursor paging with stable event identities', async () => {
  const workspace = await read('frontend-web/src/components/backoffice/BackofficePropertiesWorkspace.tsx');
  assert.match(workspace, /activityHasMore/);
  assert.match(workspace, /Load older activity/);
  assert.match(workspace, /activityCursor/);
  assert.match(workspace, /item\.eventId/);
  assert.match(workspace, /activityInFlight\.current/);
  assert.doesNotMatch(workspace, /Only the most recent activity is shown in this version/);
  assert.doesNotMatch(workspace, /demo records|fabricated counts/i);
});

test('removal filters keep the five real statuses and fence stale responses', async () => {
  const queue = await read('frontend-web/src/components/backoffice/PropertyRemovalQueue.tsx');
  const css = await read('frontend-web/src/components/backoffice/backoffice.css');
  for (const status of ['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN', "''"]) {
    assert.match(queue, new RegExp(status.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  }
  assert.match(queue, /isLatestRequest\(responseEpoch, queueRequestEpoch\.current\)/);
  assert.match(queue, /aria-pressed=\{status === value\}/);
  assert.match(css, /\.backoffice-removal-filters button \{[\s\S]*min-width: 44px;[\s\S]*min-height: 44px;/);
});

test('removal decision uses the approved split layout and preserves guarded actions', async () => {
  const queue = await read('frontend-web/src/components/backoffice/PropertyRemovalQueue.tsx');
  const css = await read('frontend-web/src/components/backoffice/backoffice.css');
  assert.match(queue, /backoffice-removal-decision-layout/);
  assert.match(queue, /What approval does/);
  assert.match(queue, /Current property state/);
  assert.match(queue, /Approve removal/);
  assert.match(queue, /Reject request/);
  assert.match(queue, /disabled=\{busy \|\| !reason\.trim\(\)\}/);
  assert.match(queue, /decisionOpener\.current\?\.focus\(\)/);
  assert.match(queue, /detailOpener\.current\?\.focus\(\)/);
  assert.match(css, /grid-template-columns: minmax\(0, 1fr\) 340px/);
  assert.match(css, /@media \(min-width: 1024px\)/);
});

test('lessor removal presentation derives all five server states', () => {
  assert.equal(lessorRemovalState(null), 'none');
  assert.equal(lessorRemovalState(removal('PENDING')), 'pending');
  assert.equal(lessorRemovalState(removal('WITHDRAWN')), 'withdrawn');
  assert.equal(lessorRemovalState(removal('REJECTED')), 'rejected');
  assert.equal(lessorRemovalState(removal('APPROVED')), 'approved');

  assert.equal(latestLessorRemoval([removal('WITHDRAWN', 2), removal('PENDING', 1)]).requestId, 1);
  assert.equal(canRequestLessorRemoval('ready', 'PUBLISHED', 'none'), true);
  assert.equal(canRequestLessorRemoval('ready', 'PUBLISHED', 'withdrawn'), true);
  assert.equal(canRequestLessorRemoval('ready', 'PUBLISHED', 'rejected'), true);
  assert.equal(canRequestLessorRemoval('ready', 'PUBLISHED', 'pending'), false);
  assert.equal(canRequestLessorRemoval('ready', 'ARCHIVED', 'approved'), false);
  assert.equal(canRequestLessorRemoval('error', 'PUBLISHED', 'none'), false);
  assert.equal(showLessorListingChanges('PUBLISHED', 'pending'), true);
  assert.equal(showLessorListingChanges('ARCHIVED', 'approved'), false);
});

test('lessor detail keeps Listing changes separate from Removal and preserves Pause and edit', async () => {
  const owner = await read('frontend-web/src/components/LessorListingView.tsx');
  assert.match(owner, />Listing changes</);
  assert.match(owner, />Removal</);
  assert.match(owner, /Edit details for review/);
  assert.match(owner, /Pause listing/);
  assert.match(owner, /Request property removal/);
  assert.match(owner, /Removal request pending/);
  assert.match(owner, /Removal request withdrawn/);
  assert.match(owner, /Removal request not approved/);
  assert.match(owner, /Removal approved/);
  assert.match(owner, /isLatestRequest\(operationEpoch, listingEpoch\.current\)/);
  assert.doesNotMatch(owner, /Design fixture|ls-state|state selector/i);
});

test('admin navigation controls meet target size and focus contracts', async () => {
  const admin = await read('frontend-web/src/components/MasterAdminDashboard.tsx');
  assert.match(admin, /mobileMenuTriggerRef/);
  assert.match(admin, /aria-expanded=\{isMobileMenuOpen\}/);
  assert.match(admin, /aria-controls="admin-mobile-navigation"/);
  assert.match(admin, /min-h-11 min-w-11/);
  assert.match(admin, /h-11 w-11/);
  assert.match(admin, /aria-expanded=\{!isSidebarCollapsed\}/);
  assert.match(admin, /mobileDrawerCloseRef\.current\?\.focus\(\)/);
  assert.match(admin, /mobileMenuTriggerRef\.current\?\.focus\(\)/);
  assert.match(admin, /aria-current=\{isActive \? 'page' : undefined\}/);
});

test('negative control rejects a stale asynchronous response', () => {
  assert.equal(isLatestRequest(7, 7), true);
  assert.equal(isLatestRequest(6, 7), false);
});
