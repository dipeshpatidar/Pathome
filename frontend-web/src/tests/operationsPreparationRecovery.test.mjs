import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { installOperationsActionHarness } from './support/operationsActionHarness.mjs';

const h = await installOperationsActionHarness();
const memory = new Map();
globalThis.sessionStorage = {
  getItem: (key) => memory.get(key) ?? null,
  setItem: (key, value) => memory.set(key, String(value)),
  removeItem: (key) => memory.delete(key),
  key: (index) => [...memory.keys()][index] ?? null,
  get length() {
    return memory.size;
  }
};

const recovery = await import('../utils/operationsShareRecovery.ts');
const ids = await import('../utils/operationsOperationId.ts');
const presentation = await import('../utils/operationsLeadPresentation.ts');
const preparation = await import('../utils/operationsSharePreparation.ts');
const { ApiRequestError } = await import('../services/apiError.ts');
const leadServiceModule = await import('../services/operationsLeadService.ts');
const { operationsLeadService, ShareHistoryBoundReachedError } = leadServiceModule;

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const serviceSource = read('../services/operationsLeadService.ts');
const workspace = read('../components/operations/OperationsLeadsWorkspace.tsx');
const preparationSource = read('../utils/operationsSharePreparation.ts');
const account = { id: 91, role: 'EMPLOYEE', token: 'preparation-session-a' };
const operation = {
  operationId: '11111111-1111-4111-8111-111111111111',
  leadId: 17,
  expectedLeadVersion: 6,
  shortlistId: 7,
  expectedShortlistVersion: 2,
  contactPreferenceRevision: 3,
  mode: 'MANUAL_BUSINESS_HANDOFF'
};

const start = () => {
  memory.clear();
  h.signIn(account);
  return h.session.captureOperationsSession();
};

const historyItem = (id, overrides = {}) => ({
  id,
  shortlistId: 7,
  shortlistVersion: 2,
  contactPreferenceRevision: 3,
  mode: 'MANUAL_BUSINESS_HANDOFF',
  publicLinks: ['https://pathome.test/property/9'],
  previewText: 'Stored property message',
  maskedDestination: '********3210',
  recordedAt: '2026-10-23T00:00:00Z',
  events: [],
  currentFactsMatch: true,
  currentConsentMatches: true,
  currentDestinationMatches: true,
  currentAuthorityMatches: true,
  actionReady: true,
  ...overrides
});

const historyResponse = (page, {
  items = [],
  totalPages = 1,
  totalElements = items.length
} = {}) => h.json(200, {
  items,
  page,
  size: 20,
  totalPages,
  totalElements
});

test('one preparation identity survives a remount with only non-sensitive identifiers', () => {
  const session = start();
  const saved = recovery.preservePendingSharePreparation(session, operation, 1_000);
  assert.deepEqual(recovery.readPendingSharePreparation(session, 17, 2_000), saved);
  const serialized = [...memory.entries()].map(([key, value]) => `${key}=${value}`).join('|');
  assert.equal(serialized.includes(account.token), false);
  assert.equal(serialized.includes('+91'), false);
  assert.equal(serialized.includes('property options'), false);
  assert.match(serialized, /pathome_oe_share_prepare_v1:.*:17=/);
});

test('a reused operation cannot silently change its Lead or property-message facts', () => {
  const session = start();
  recovery.preservePendingSharePreparation(session, operation, 1_000);
  assert.throws(
    () => recovery.preservePendingSharePreparation(
      session,
      { ...operation, expectedShortlistVersion: 3 },
      2_000
    ),
    /still being checked/
  );
  assert.throws(
    () => recovery.preservePendingSharePreparation(
      session,
      { ...operation, operationId: '22222222-2222-4222-8222-222222222222' },
      2_000
    ),
    /still being checked/
  );
});

test('an absent receipt keeps the old operation and only a proven rejection permits a new UUID', () => {
  const session = start();
  const pending = recovery.preservePendingSharePreparation(session, operation, 1_000);
  assert.equal(
    recovery.readPendingSharePreparation(session, 17, 2_000)?.operationId,
    pending.operationId
  );
  assert.throws(
    () => recovery.preservePendingSharePreparation(
      session,
      { ...operation, operationId: '22222222-2222-4222-8222-222222222222' },
      2_000
    ),
    /still being checked/
  );

  recovery.forgetPendingSharePreparation(session, 17, pending.operationId);
  const deliberateRetry = recovery.preservePendingSharePreparation(
    session,
    { ...operation, operationId: '33333333-3333-4333-8333-333333333333' },
    2_001
  );
  assert.notEqual(deliberateRetry.operationId, pending.operationId);
  assert.match(workspace,
    /if \(prepareInFlight\.current \|\| busy \|\| checking \|\| pendingOperation\) return;/);
});

test('storage refusal and unavailable UUID generation stop before a preparation request', () => {
  const session = start();
  const original = sessionStorage.setItem;
  sessionStorage.setItem = () => {
    throw new Error('QuotaExceededError');
  };
  try {
    assert.throws(
      () => recovery.preservePendingSharePreparation(session, operation, 1_000),
      /could not keep the property message request/
    );
  } finally {
    sessionStorage.setItem = original;
  }
  assert.equal(memory.size, 0);
  assert.throws(() => ids.createOperationId({}), /Nothing was sent/);
  assert.throws(
    () => ids.createOperationId({ getRandomValues: () => { throw new Error('denied'); } }),
    /Nothing was sent/
  );
  assert.equal(h.calls.length, 0);
});

test('account changes clear preparation recovery and expiry is fail-closed', () => {
  const session = start();
  recovery.preservePendingSharePreparation(session, operation, 1_000);
  h.signIn({ id: 92, role: 'EMPLOYEE', token: 'preparation-session-b' });
  assert.equal(memory.size, 0);

  h.signIn(account);
  const restored = h.session.captureOperationsSession();
  recovery.preservePendingSharePreparation(restored, operation, 1_000);
  assert.equal(
    recovery.readPendingSharePreparation(
      restored,
      17,
      1_000 + 24 * 60 * 60 * 1000 + 1
    ),
    null
  );
  assert.equal(memory.size, 0);
});

test('exact receipt lookup uses authorized history pages and never the preparation POST', () => {
  const historyStart = serviceSource.indexOf('async preparedShareHistoryFromHistory(');
  const historyEnd = serviceSource.indexOf('recordShareEvent(', historyStart);
  const historyLookup = serviceSource.slice(historyStart, historyEnd);
  assert.ok(historyStart > 0 && historyEnd > historyStart);
  assert.match(historyLookup, /this\.shareHistory\(leadId, 0/);
  assert.match(historyLookup, /for \(let page = 1; page < lastPage; page \+= 1\)/);
  assert.doesNotMatch(historyLookup, /fetch\(|method: 'POST'|prepareShare\(/);

  const checkStart = workspace.indexOf('const checkPrepared = async () =>');
  const checkEnd = workspace.indexOf('return (', checkStart);
  const check = workspace.slice(checkStart, checkEnd);
  assert.match(check, /commandReceipt\([\s\S]*'LEAD_SHARE_PREPARE'/);
  assert.match(check, /preparedShareHistoryFromHistory\(/);
  assert.doesNotMatch(check, /prepareShare\(/);
  assert.match(check, /share\.id !== shareId/);
  assert.match(check, /share\.shortlistId !== pendingOperation\.shortlistId/);
  assert.match(check, /share\.contactPreferenceRevision !== pendingOperation\.contactPreferenceRevision/);

  const copyStart = workspace.indexOf('const copyPreparedText = async');
  const copyEnd = workspace.indexOf('const closeConfirmation =', copyStart);
  const copy = workspace.slice(copyStart, copyEnd);
  assert.doesNotMatch(copy, /prepareShare\(|revalidatePreparedShare/);
});

test('authorized history can restore the exact prepared-share shape', () => {
  const item = {
    id: '11111111-1111-4111-8111-111111111111',
    shortlistId: 7,
    shortlistVersion: 2,
    contactPreferenceRevision: 3,
    mode: 'MANUAL_BUSINESS_HANDOFF',
    publicLinks: ['https://pathome.test/property/9'],
    previewText: 'Stored property message',
    maskedDestination: '********3210',
    recordedAt: '2026-10-23T00:00:00Z',
    events: [],
    currentFactsMatch: true,
    currentConsentMatches: true,
    currentDestinationMatches: true,
    currentAuthorityMatches: true,
    actionReady: true
  };
  assert.deepEqual(presentation.leadShareFromHistoryItem(item, 17), {
    id: item.id,
    leadId: 17,
    shortlistId: 7,
    shortlistVersion: 2,
    contactPreferenceRevision: 3,
    mode: 'MANUAL_BUSINESS_HANDOFF',
    publicLinks: item.publicLinks,
    previewText: item.previewText,
    maskedDestination: item.maskedDestination,
    recordedAt: item.recordedAt
  });
});

test('exact-share pagination finds a share beyond the newest twenty without a mutation', async () => {
  const session = start();
  const exactId = '22222222-2222-4222-8222-222222222222';
  h.reset(({ url }) => {
    const page = Number(new URL(url).searchParams.get('page'));
    return page === 0
      ? historyResponse(0, { items: [historyItem('33333333-3333-4333-8333-333333333333')], totalPages: 3, totalElements: 41 })
      : page === 1
        ? historyResponse(1, { items: [historyItem(exactId)], totalPages: 3, totalElements: 41 })
        : historyResponse(2, { items: [historyItem('44444444-4444-4444-8444-444444444444')], totalPages: 3, totalElements: 41 });
  });

  const item = await operationsLeadService.preparedShareHistoryFromHistory(
    17, exactId, { session });

  assert.equal(item.id, exactId);
  assert.equal(item.currentConsentMatches, true);
  assert.equal(h.calls.every((call) => call.method === 'GET'), true);
  assert.equal(h.calls.some((call) => call.url.includes('/shares')
    && call.method === 'POST'), false);
});

test('exact-share resolution stops on page one when the receipt share is newest', async () => {
  const session = start();
  const exactId = '23232323-2323-4232-8232-232323232323';
  h.reset(() => historyResponse(0, {
    items: [historyItem(exactId)],
    totalPages: 3,
    totalElements: 41
  }));

  const item = await operationsLeadService.preparedShareHistoryFromHistory(
    17, exactId, { session });

  assert.equal(item.id, exactId);
  assert.equal(h.calls.length, 1);
  assert.equal(h.calls[0].method, 'GET');
});

test('exact-share resolution reaches the final approved history page', async () => {
  const session = start();
  const exactId = '24242424-2424-4242-8242-242424242424';
  h.reset(({ url }) => {
    const page = Number(new URL(url).searchParams.get('page'));
    return historyResponse(page, {
      items: [historyItem(page === 99
        ? exactId
        : `25252525-2525-4252-8252-${String(page).padStart(12, '0')}`)],
      totalPages: 100,
      totalElements: 2_000
    });
  });

  const item = await operationsLeadService.preparedShareHistoryFromHistory(
    17, exactId, { session });

  assert.equal(item.id, exactId);
  assert.equal(h.calls.length, 100);
  assert.equal(h.calls.every((call) => call.method === 'GET'), true);
});

test('a changed result set during paging restarts once and still resolves by exact UUID', async () => {
  const session = start();
  const exactId = '55555555-5555-4555-8555-555555555555';
  let pageZeroCalls = 0;
  let pageOneCalls = 0;
  h.reset(({ url }) => {
    const page = Number(new URL(url).searchParams.get('page'));
    if (page === 0) {
      pageZeroCalls += 1;
      return pageZeroCalls === 1
        ? historyResponse(0, { items: [historyItem('66666666-6666-4666-8666-666666666666')], totalPages: 2, totalElements: 21 })
        : historyResponse(0, { items: [historyItem('77777777-7777-4777-8777-777777777777')], totalPages: 3, totalElements: 22 });
    }
    if (page === 1) {
      pageOneCalls += 1;
      return pageOneCalls === 1
        ? historyResponse(1, { items: [historyItem('88888888-8888-4888-8888-888888888888')], totalPages: 3, totalElements: 22 })
        : historyResponse(1, { items: [historyItem('99999999-9999-4999-8999-999999999999')], totalPages: 3, totalElements: 22 });
    }
    return historyResponse(2, { items: [historyItem(exactId)], totalPages: 3, totalElements: 22 });
  });

  const item = await operationsLeadService.preparedShareHistoryFromHistory(
    17, exactId, { session });

  assert.equal(item.id, exactId);
  assert.equal(pageZeroCalls, 2);
  assert.equal(h.calls.every((call) => call.method === 'GET'), true);
});

test('history past the bounded page count returns a truthful unresolved result', async () => {
  const session = start();
  h.reset(({ url }) => {
    const page = Number(new URL(url).searchParams.get('page'));
    return historyResponse(page, {
      items: [historyItem(`aaaaaaaa-aaaa-4aaa-8aaa-${String(page).padStart(12, '0')}`)],
      totalPages: 101,
      totalElements: 2_020
    });
  });

  await assert.rejects(
    () => operationsLeadService.preparedShareHistoryFromHistory(
      17,
      'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      { session }
    ),
    ShareHistoryBoundReachedError
  );
  assert.equal(h.calls.length, 100);
  assert.equal(h.calls.every((call) => call.method === 'GET'), true);
});

test('page failures and account changes stay read-only and never invent a substitute share', async () => {
  const session = start();
  h.reset(({ url }) => {
    const page = Number(new URL(url).searchParams.get('page'));
    if (page === 0) {
      return historyResponse(0, {
        items: [historyItem('cccccccc-cccc-4ccc-8ccc-cccccccccccc')],
        totalPages: 2,
        totalElements: 21
      });
    }
    throw new Error('network interrupted between pages');
  });

  await assert.rejects(
    () => operationsLeadService.preparedShareHistoryFromHistory(
      17,
      'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
      { session }
    ),
    /network interrupted/
  );
  assert.equal(h.calls.every((call) => call.method === 'GET'), true);

  const nextSession = start();
  h.reset(({ url }) => {
    const page = Number(new URL(url).searchParams.get('page'));
    if (page === 0) {
      return historyResponse(0, {
        items: [historyItem('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee')],
        totalPages: 2,
        totalElements: 21
      });
    }
    h.signIn({ id: 92, role: 'EMPLOYEE', token: 'preparation-session-switched' });
    return historyResponse(1, {
      items: [historyItem('ffffffff-ffff-4fff-8fff-ffffffffffff')],
      totalPages: 2,
      totalElements: 21
    });
  });
  await assert.rejects(
    () => operationsLeadService.preparedShareHistoryFromHistory(
      17,
      '12121212-1212-4212-8212-121212121212',
      { session: nextSession }
    )
  );
  assert.equal(h.calls.every((call) => call.method === 'GET'), true);

  const abortSession = start();
  const abort = new AbortController();
  h.reset(({ url }) => {
    const page = Number(new URL(url).searchParams.get('page'));
    if (page === 0) {
      abort.abort();
      return historyResponse(0, {
        items: [historyItem('34343434-3434-4434-8434-343434343434')],
        totalPages: 2,
        totalElements: 21
      });
    }
    return historyResponse(1, {
      items: [historyItem('45454545-4545-4454-8454-454545454545')],
      totalPages: 2,
      totalElements: 21
    });
  });
  await assert.rejects(
    () => operationsLeadService.preparedShareHistoryFromHistory(
      17,
      '56565656-5656-4565-8565-565656565656',
      { session: abortSession, signal: abort.signal }
    ),
    (cause) => cause?.name === 'AbortError'
  );
  assert.equal(h.calls.length, 1);
  assert.equal(h.calls[0].method, 'GET');
});

test('only trusted no-write configuration responses become definitive preparation rejections', () => {
  assert.deepEqual(
    preparation.classifySharePrepareFailure(new ApiRequestError(
      'We could not complete this request right now. Please try again shortly.',
      503,
      'A canonical public property origin is not configured'
    )).kind,
    'definitive-rejection'
  );
  assert.equal(
    preparation.classifySharePrepareFailure(new ApiRequestError(
      'We could not complete this request right now. Please try again shortly.',
      503
    )).kind,
    'uncertain'
  );
  assert.equal(
    preparation.classifySharePrepareFailure(new TypeError('network timeout')).kind,
    'uncertain'
  );
  assert.equal(
    preparation.classifySharePrepareFailure(new ApiRequestError(
      'The prepared message response was malformed.',
      201
    )).kind,
    'uncertain'
  );
  assert.equal(
    preparation.classifySharePrepareFailure(new ApiRequestError(
      'The shortlist changed.',
      409
    )).kind,
    'conflict'
  );
});

test('preparation transport retains only the existing application configuration rejection marker', async () => {
  const session = start();
  const command = {
    operationId: operation.operationId,
    expectedLeadVersion: operation.expectedLeadVersion,
    shortlistId: operation.shortlistId,
    expectedShortlistVersion: operation.expectedShortlistVersion,
    contactPreferenceRevision: operation.contactPreferenceRevision,
    mode: operation.mode
  };
  h.reset(() => h.json(503, {
    status: 503,
    error: 'Service Unavailable',
    message: 'A canonical public property origin is not configured'
  }));
  const applicationFailure = await operationsLeadService.prepareShare(
    17, command, { session }
  ).catch((cause) => cause);
  assert.equal(applicationFailure.status, 503);
  assert.equal(
    preparation.classifySharePrepareFailure(applicationFailure).kind,
    'definitive-rejection'
  );

  h.reset(() => h.json(503, { error: 'Service Unavailable' }));
  const proxyFailure = await operationsLeadService.prepareShare(
    17, command, { session }
  ).catch((cause) => cause);
  assert.equal(proxyFailure.status, 503);
  assert.equal(preparation.classifySharePrepareFailure(proxyFailure).kind, 'uncertain');
  assert.equal(h.calls.every((call) => call.method === 'POST'), true);
});

test('preparation states distinguish saved, waiting, unavailable and uncertain outcomes', () => {
  assert.match(workspace, /We couldn’t confirm that the property message is ready\./);
  assert.match(workspace, /This property message is not ready yet\./);
  assert.match(preparationSource, /The customer property links are not available yet/);
  assert.match(workspace, /No message has been sent to the customer\./);
  assert.match(workspace, /Check if message is ready/);
  assert.match(workspace, /Prepare property message/);
  assert.match(workspace, /Prepare message/);
  assert.match(workspace, /prepareInFlight\.current/);
  assert.match(workspace, /pendingOperation\.operationId/);
  assert.doesNotMatch(workspace, /The prepare reply was lost/);
  assert.doesNotMatch(workspace, /Check share status/);
});

test('permission persistence and UUID creation are handled before consent dispatch', () => {
  assert.match(
    workspace,
    /let pending: PendingConsentOperation;\s*try \{\s*pending = preservePendingConsentOperation\([\s\S]*?\} catch \(cause\) \{[\s\S]*?return;\s*\}\s*setPendingOperation\(pending\);\s*setBusy\(true\);/
  );
  assert.equal(
    (workspace.match(/setMessage\(preDispatchPermissionFailure\(cause\)\)/g) ?? []).length,
    2
  );
  assert.match(workspace,
    /This browser could not keep the permission request\. Nothing was sent\. Your answers are still here\./);
  assert.match(workspace, /const requestId = \(\): string => createOperationId\(\)/);
  assert.doesNotMatch(workspace, /Date\.now\(\)\.toString\(16\)/);
});
