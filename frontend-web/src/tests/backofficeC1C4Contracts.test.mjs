import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { commitParsedSessionResponse, OperationsSessionChangedError } from '../utils/operationsSession.ts';
import { operationIdForIntent } from '../utils/operationIntent.ts';
import {
  describeMediaChange,
  formatActivityEvidenceValue,
  summarizeMediaChange
} from '../utils/backofficeActivityPresentation.ts';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../../..');
const read = relative => readFile(path.join(root, relative), 'utf8');

test('a parsed body is rejected when the captured session changed during JSON parsing', async () => {
  const session = { token: 'old', userId: 1, role: 'ADMIN', identity: 'old', generation: 1 };
  assert.equal(commitParsedSessionResponse(session, { value: 1 }, () => true).value, 1);
  assert.throws(
    () => commitParsedSessionResponse(session, { value: 'private' }, () => false),
    OperationsSessionChangedError
  );
});

test('operation UUID is stable for identical intent and replaced for changed intent', () => {
  const first = operationIdForIntent(null, 'listing:42|reason:rent');
  assert.equal(first.id, first.id);
  const retry = operationIdForIntent(first, 'listing:42|reason:rent');
  assert.equal(retry.id, first.id);
  const changed = operationIdForIntent(retry, 'listing:42|reason:deposit');
  assert.notEqual(changed.id, first.id);
});

test('media history compares stable resources instead of counts or temporary draft UUIDs', () => {
  const before = [
    { resourceId: 10, mediaId: 'draft-old', url: 'a.jpg', sortOrder: 0, cover: true, roomTag: 'LIVING_ROOM', caption: 'Living' },
    { resourceId: 11, mediaId: 'draft-old-2', url: 'b.jpg', sortOrder: 1, cover: false, roomTag: 'KITCHEN', caption: null }
  ];
  const after = [
    { resourceId: 10, mediaId: 'draft-new', url: 'a.jpg', sortOrder: 1, cover: false, roomTag: 'LIVING_ROOM', caption: 'Living room' },
    { resourceId: 11, mediaId: 'draft-new-2', url: 'b.jpg', sortOrder: 0, cover: true, roomTag: 'KITCHEN', caption: null }
  ];
  const summary = summarizeMediaChange(before, after);
  assert.equal(summary.added, 0);
  assert.equal(summary.removed, 0);
  assert.equal(summary.changed, 2);
  assert.equal(summary.reordered, true);
  assert.equal(summary.coverChanged, true);
  assert.match(summary.text, /cover/i);
  assert.match(summary.text, /order/i);
});

test('temporary draft UUID-only changes are not reported as business content changes', () => {
  const before = [{ sourceMediaAssetId: 10, mediaId: 'draft-a', url: 'a.jpg', sortOrder: 0, cover: true }];
  const after = [{ sourceMediaAssetId: 10, mediaId: 'draft-b', url: 'a.jpg', sortOrder: 0, cover: true }];
  const summary = summarizeMediaChange(before, after);
  assert.equal(summary.changed, 0);
  assert.equal(summary.hasChanges, false);
});

test('caption-only history evidence includes both actual captions', () => {
  const before = [{ sourceMediaAssetId: 10, mediaId: 'draft-a', url: 'a.jpg',
    mediaType: 'IMAGE', roomTag: 'LIVING_ROOM', caption: 'Old caption', sortOrder: 0, cover: true }];
  const after = [{ sourceMediaAssetId: 10, mediaId: 'draft-b', url: 'a.jpg',
    mediaType: 'IMAGE', roomTag: 'LIVING_ROOM', caption: 'New caption', sortOrder: 0, cover: true }];
  const evidence = describeMediaChange(before, after);
  assert.deepEqual(evidence.map(item => item.kind), ['UPDATED']);
  assert.deepEqual(evidence[0].changes.map(change => [change.label, change.before, change.after]), [
    ['Caption', 'Old caption', 'New caption']
  ]);
});

test('rendered evidence formatter preserves human text and identifiers exactly', () => {
  assert.equal(formatActivityEvidenceValue('caption', 'Old caption'), 'Old caption');
  assert.equal(formatActivityEvidenceValue('caption', 'New caption'), 'New caption');
  assert.equal(formatActivityEvidenceValue('url', 'https://x.test/Photo_A.jpg'),
    'https://x.test/Photo_A.jpg');
  assert.equal(formatActivityEvidenceValue('url', 'फोटो_बालकनी?view=main'),
    'फोटो_बालकनी?view=main');
  assert.equal(formatActivityEvidenceValue('roomTag', 'LIVING_ROOM'), 'Living room');
  assert.equal(formatActivityEvidenceValue('mediaType', 'VIDEO_WALKTHROUGH'), 'Video walkthrough');
  assert.equal(formatActivityEvidenceValue('cover', false), 'No');
  assert.equal(formatActivityEvidenceValue('sortOrder', 0), '0');
  assert.equal(formatActivityEvidenceValue('caption', null), 'None');
  assert.equal(formatActivityEvidenceValue('caption', ''), 'None');
});

test('media evidence shows tag, cover, order, membership, and replacement differences', () => {
  const before = [
    { sourceMediaAssetId: 10, url: 'a.jpg', mediaType: 'IMAGE', roomTag: 'LIVING_ROOM', sortOrder: 0, cover: true },
    { sourceMediaAssetId: 11, url: 'b.jpg', mediaType: 'IMAGE', roomTag: 'KITCHEN', sortOrder: 1, cover: false }
  ];
  const after = [
    { sourceMediaAssetId: 10, url: 'a.jpg', mediaType: 'IMAGE', roomTag: 'BALCONY', sortOrder: 1, cover: false },
    { sourceMediaAssetId: 12, url: 'c.jpg', mediaType: 'IMAGE', roomTag: 'KITCHEN', sortOrder: 0, cover: true }
  ];
  const evidence = describeMediaChange(before, after);
  const changed = evidence.find(item => item.identity === 'resource:10');
  assert.deepEqual(changed.changes.map(change => change.label), ['Room', 'Cover', 'Display order']);
  assert.equal(evidence.find(item => item.identity === 'resource:11').kind, 'REMOVED');
  assert.equal(evidence.find(item => item.identity === 'resource:12').kind, 'ADDED');
});

test('media evidence handles empty and legacy null snapshots without inventing changes', () => {
  assert.deepEqual(describeMediaChange(null, null), []);
  assert.deepEqual(describeMediaChange([], []), []);
  const added = describeMediaChange(null, [{ sourceMediaAssetId: 10, url: 'a.jpg', mediaType: 'IMAGE' }]);
  assert.equal(added.length, 1);
  assert.equal(added[0].kind, 'ADDED');
});

test('activity component renders resource-level evidence instead of a count-only summary', async () => {
  const workspace = await read('frontend-web/src/components/backoffice/BackofficePropertiesWorkspace.tsx');
  assert.match(workspace, /describeMediaChange\(change\.before, change\.after\)/);
  assert.match(workspace, /aria-label="Exact media changes"/);
  assert.match(workspace, /<MediaHistoryEvidence/);
  assert.match(workspace, /change\.before[\s\S]{0,80}change\.after/);
});

test('protected services check session after JSON parsing and history requires audit duty', async () => {
  const service = await read('frontend-web/src/services/backofficePropertyService.ts');
  const removalService = await read('frontend-web/src/services/propertyRemovalService.ts');
  const workspace = await read('frontend-web/src/components/backoffice/BackofficePropertiesWorkspace.tsx');
  assert.match(service, /response\.json\(\)[\s\S]{0,160}commitParsedSessionResponse/);
  assert.match(removalService, /response\.json\(\)[\s\S]{0,160}commitParsedSessionResponse/);
  assert.match(workspace, /capability\.canAudit/);
  assert.doesNotMatch(workspace, /capability\.canAudit \|\| capability\.canEdit/);
  assert.match(service, /activity\(listingId: number, cursor: string \| null = null, limit = 20\)/);
  assert.match(service, /nextCursor: string \| null/);
});

test('public property mapping never reads private presentation-address fields', async () => {
  const propertyService = await read('frontend-web/src/services/propertyService.ts');
  const mapping = propertyService.slice(
    propertyService.indexOf('const mapPublicProperty'),
    propertyService.indexOf('export const mapDiscoveryProperty')
  );
  assert.doesNotMatch(mapping, /item\.(address|landmark|colony|pincode)/);
  assert.match(mapping, /landmark: null/);
  assert.match(mapping, /colony: null/);
  assert.match(mapping, /pincode: null/);
});
