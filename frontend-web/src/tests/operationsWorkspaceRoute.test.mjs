import test from 'node:test';
import assert from 'node:assert/strict';
import {
  EMPTY_OPERATIONS_FILTERS,
  detailRoute,
  isOperationsPath,
  operationsReturnPath,
  parseOperationsRoute,
  queueRoute,
  serializeOperationsRoute
} from '../utils/operationsWorkspaceRoute.ts';

test('home and every approved queue route parse canonically', () => {
  assert.deepEqual(parseOperationsRoute('/operations', ''), { kind: 'home' });
  for (const queue of ['attention', 'my-work', 'claimable']) {
    assert.deepEqual(
      parseOperationsRoute(`/operations/queues/${queue}`, '?page=2'),
      { kind: 'queue', queue, page: 2, filters: EMPTY_OPERATIONS_FILTERS }
    );
  }
});

test('queue filters round-trip through the URL without leaking anything else', () => {
  const route = queueRoute('my-work', 3, {
    targetType: 'REQUEST',
    status: 'RECEIVED',
    cityId: 11,
    teamId: 21,
    targetId: 501
  });
  const url = serializeOperationsRoute(route);
  assert.equal(url, '/operations/queues/my-work?page=3&type=REQUEST&status=RECEIVED&city=11&team=21&ref=501');
  assert.deepEqual(parseOperationsRoute(url.split('?')[0], `?${url.split('?')[1]}`), route);
});

test('detail URLs retain the queue, page and filters they were opened from', () => {
  const route = detailRoute('SESSION', 77, 'my-work', 2, {
    targetType: 'SESSION',
    status: 'SCHEDULED',
    cityId: 3,
    teamId: null,
    targetId: null
  });
  const url = serializeOperationsRoute(route);
  assert.equal(
    url,
    '/operations/sessions/77?page=2&type=SESSION&status=SCHEDULED&city=3&from=my-work'
  );
  assert.deepEqual(parseOperationsRoute(url.split('?')[0], `?${url.split('?')[1]}`), route);
});

test('malformed, unknown and unsafe route state is rejected or dropped safely', () => {
  assert.equal(parseOperationsRoute('/operations/queues/unknown', ''), null);
  assert.equal(parseOperationsRoute('/operations/queues/my-work/extra', ''), null);
  assert.equal(parseOperationsRoute('/operations/requests/not-a-number', ''), null);
  assert.equal(parseOperationsRoute('/operations/requests/-1', ''), null);
  assert.equal(parseOperationsRoute('/tenant', ''), null);

  const sanitized = parseOperationsRoute(
    '/operations/queues/claimable',
    '?page=-2&type=BOGUS&status=RECEIVED&city=0&team=abc&ref=9&phone=9999999999&token=secret'
  );
  assert.deepEqual(sanitized, { kind: 'queue', queue: 'claimable', page: 1, filters: EMPTY_OPERATIONS_FILTERS });
  assert.equal(serializeOperationsRoute(sanitized).includes('phone'), false);
  assert.equal(serializeOperationsRoute(sanitized).includes('token'), false);
});

test('a status is only accepted with a compatible explicit record type', () => {
  assert.equal(
    parseOperationsRoute('/operations/queues/my-work', '?status=SCHEDULED').filters.status,
    null
  );
  assert.equal(
    parseOperationsRoute('/operations/queues/my-work', '?type=REQUEST&status=DRAFT').filters.status,
    null
  );
  assert.equal(
    parseOperationsRoute('/operations/queues/my-work', '?type=SESSION&status=DRAFT').filters.status,
    'DRAFT'
  );
  assert.equal(
    parseOperationsRoute('/operations/queues/my-work', '?ref=5').filters.targetId,
    null,
    'an exact record reference requires a type'
  );
});

test('the return path preserves only a canonical Operations destination', () => {
  assert.equal(operationsReturnPath('/operations', ''), '/operations');
  assert.equal(
    operationsReturnPath('/operations/queues/my-work', '?page=2&type=REQUEST'),
    '/operations/queues/my-work?page=2&type=REQUEST'
  );
  assert.equal(operationsReturnPath('https://evil.example/operations', ''), '/operations');
  assert.equal(operationsReturnPath('/operations/queues/my-work', '?next=https://evil.example'), '/operations/queues/my-work');
  assert.equal(isOperationsPath('/operations/requests/9'), true);
  assert.equal(isOperationsPath('/operations-other'), false);
});

test('trailing slashes and duplicate-safe parsing keep one canonical form', () => {
  const withSlash = parseOperationsRoute('/operations/queues/attention/', '');
  assert.equal(serializeOperationsRoute(withSlash), '/operations/queues/attention');
  const detail = parseOperationsRoute('/operations/requests/42/', '?from=bogus&page=1');
  assert.equal(detail.from, 'home');
  assert.equal(serializeOperationsRoute(detail), '/operations/requests/42');
});
