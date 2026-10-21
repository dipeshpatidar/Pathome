import test from 'node:test';
import assert from 'node:assert/strict';
import {
  linkedRequestsLabel,
  operationsRecordTag,
  operationsStatusMeta,
  parseWorkPage,
  toSessionDetail
} from '../utils/operationsWorkspacePresentation.ts';

test('approved plain-language status labels preserve the underlying codes', () => {
  assert.equal(operationsStatusMeta('REQUEST', 'RECEIVED').label, 'Awaiting coordination');
  assert.equal(operationsStatusMeta('REQUEST', 'COORDINATING').label, 'In coordination');
  assert.equal(operationsStatusMeta('REQUEST', 'SCHEDULED').label, 'In booked visit');
  assert.equal(operationsStatusMeta('SESSION', 'DRAFT').label, 'Planning');
  assert.equal(operationsStatusMeta('SESSION', 'SCHEDULED').label, 'Visit booked');
  assert.equal(operationsStatusMeta('SESSION', 'STARTED').label, 'Visit in progress');
  assert.equal(operationsStatusMeta('SESSION', 'COMPLETED').label, 'Visit completed');
  assert.equal(operationsStatusMeta('SESSION', 'DRAFT').tone, 'open', 'a planning visit is not a success state');
  assert.equal(operationsStatusMeta('SESSION', 'DRAFT').hint, 'No visit time is booked yet');
});

test('record and linked-request language is explicit and unambiguous', () => {
  assert.equal(operationsRecordTag('REQUEST'), 'Visit request');
  assert.equal(operationsRecordTag('SESSION'), 'Visit plan');
  assert.equal(linkedRequestsLabel(1), '1 tenant visit request');
  assert.equal(linkedRequestsLabel(4), '4 tenant visit requests');
});

test('page parsing keeps the additive filtered and unfiltered totals distinct', () => {
  const page = parseWorkPage(
    { items: [], totalCount: 2, unfilteredTotalCount: 9, page: 1, size: 20, totalPages: 1 },
    { page: 0, size: 20 }
  );
  assert.equal(page.totalCount, 2);
  assert.equal(page.unfilteredTotalCount, 9);
});

test('older page payloads remain compatible without inventing a larger total', () => {
  const page = parseWorkPage({ items: [], totalCount: 0, page: 0, size: 20, totalPages: 0 }, { page: 0, size: 20 });
  assert.equal(page.unfilteredTotalCount, 0);
});

test('session items carry media context without inventing an image', () => {
  const detail = toSessionDetail({
    sessionId: 11,
    items: [
      { itemId: 1, listingId: 2, title: 'A property', listingStatus: 'ACTIVE', coverImageUrl: 'https://example.test/a.jpg' },
      { itemId: 2, listingId: 3, title: 'Another property', listingStatus: 'PENDING', coverImageUrl: null }
    ]
  });
  assert.equal(detail.items[0].coverImageUrl, 'https://example.test/a.jpg');
  assert.equal(detail.items[0].listingStatus, 'ACTIVE');
  assert.equal(detail.items[1].coverImageUrl, null);
  assert.equal(detail.items[1].listingStatus, 'PENDING');
});
