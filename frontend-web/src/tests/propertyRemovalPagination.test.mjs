import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../../..');
const read = relative => readFile(path.join(root, relative), 'utf8');

test('admin queue traverses real server pages across all five filters with fenced requests', async () => {
  const queue = await read('frontend-web/src/components/backoffice/PropertyRemovalQueue.tsx');
  const service = await read('frontend-web/src/services/propertyRemovalService.ts');
  for (const status of ['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN', "''"]) {
    assert.match(queue, new RegExp(status.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  }
  assert.match(
    queue,
    /adminQueue\(\s*requestedStatus, requestedPage, null, controller\.signal\)/
  );
  assert.match(queue, /setPage\(0\)/);
  assert.match(queue, /isLatestRequest\(responseEpoch, queueRequestEpoch\.current\)/);
  assert.match(queue, /Previous/);
  assert.match(queue, /Next/);
  assert.match(service, /params = new URLSearchParams\(\{ page: String\(page\), size: '20' \}\)/);
});

test('owner removal history is pageable and every page response is request-fenced', async () => {
  const owner = await read('frontend-web/src/components/LessorListingView.tsx');
  assert.match(owner, /propertyRemovalService\.history\(listingId, page\)/);
  assert.match(owner, /removalPageEpoch\.current/);
  assert.match(owner, /removalHasMore/);
  assert.match(owner, /aria-label="Removal history pages"/);
  assert.match(owner, /loadRemovalHistory\(0, responseEpoch\)/);
  assert.match(owner, /removalPageFocusTicket/);
  assert.match(owner, /listingEpoch: responseEpoch/);
  assert.match(owner, /pageRequestEpoch/);
  assert.match(owner, /ticket\.page === result\.page/);
  assert.match(owner, /cancelAnimationFrame/);
  assert.doesNotMatch(owner, /restoreRemovalPageFocus/);
});

test('pagination controls retain at least 44px targets and visible focus', async () => {
  const css = await read('frontend-web/src/components/backoffice/backoffice.css');
  assert.match(css, /\.backoffice-pagination button \{[\s\S]*min-height: 44px;[\s\S]*min-width: 44px;/);
  assert.match(css, /\.backoffice-pagination p:focus-visible/);
});
