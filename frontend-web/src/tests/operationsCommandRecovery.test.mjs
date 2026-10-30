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

const recovery = await import('../utils/operationsCommandRecovery.ts');
const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const workspace = read('../components/operations/OperationsLeadsWorkspace.tsx');
const account = { id: 91, role: 'EMPLOYEE', token: 'command-recovery-token' };

const start = () => {
  memory.clear();
  h.signIn(account);
  return h.session.captureOperationsSession();
};

const capture = {
  operationId: '11111111-1111-4111-8111-111111111111'
};
const shortlist = {
  operationId: '22222222-2222-4222-8222-222222222222',
  leadId: 17
};
const shareEvent = {
  operationId: '33333333-3333-4333-8333-333333333333',
  leadId: 17,
  shareId: '44444444-4444-4444-8444-444444444444',
  eventType: 'HANDOFF_ATTEMPTED'
};
const assertRecoveryCode = (fn, code, reason = null) =>
  assert.throws(fn, (error) => {
    assert.equal(error.code, code);
    if (reason !== null) assert.equal(error.reason, reason);
    return true;
  });

test('capture, shortlist creation and share-event identities survive a remount without payloads', () => {
  const session = start();
  const savedCapture = recovery.preservePendingCapture(session, capture.operationId, 1_000);
  const savedShortlist = recovery.preservePendingShortlistCreation(
    session, shortlist, 1_001
  );
  const savedEvent = recovery.preservePendingShareEvent(session, shareEvent, 1_002);

  assert.deepEqual(recovery.readPendingCapture(session, 2_000), {
    state: 'PENDING',
    command: savedCapture
  });
  assert.deepEqual(
    recovery.readPendingShortlistCreation(session, shortlist.leadId, 2_000),
    { state: 'PENDING', command: savedShortlist }
  );
  assert.deepEqual(
    recovery.readPendingShareEvent(session, shareEvent.leadId, 2_000),
    { state: 'PENDING', command: savedEvent }
  );

  const serialized = [...memory.entries()]
    .map(([key, value]) => `${key}=${value}`)
    .join('|');
  assert.equal(serialized.includes(account.token), false);
  assert.equal(serialized.includes('+91'), false);
  assert.equal(serialized.includes('customer name'), false);
  assert.equal(serialized.includes('message body'), false);
  assert.equal(serialized.includes('private/property'), false);
  assert.equal(serialized.includes('listingIds'), false);
  assert.match(serialized, /pathome_oe_command_recovery_v1:/);
});

test('one pending identity per command scope cannot silently change its target or event type', () => {
  const session = start();
  recovery.preservePendingCapture(session, capture.operationId, 1_000);
  assert.throws(
    () => recovery.preservePendingCapture(
      session,
      '55555555-5555-4555-8555-555555555555',
      1_001
    ),
    /another enquiry command/i
  );

  recovery.preservePendingShortlistCreation(session, shortlist, 1_000);
  assert.throws(
    () => recovery.preservePendingShortlistCreation(
      session,
      { ...shortlist, operationId: '66666666-6666-4666-8666-666666666666' },
      1_001
    ),
    /another shortlist command/i
  );

  recovery.preservePendingShareEvent(session, shareEvent, 1_000);
  assert.throws(
    () => recovery.preservePendingShareEvent(
      session,
      { ...shareEvent, eventType: 'CLIPBOARD_COPIED' },
      1_001
    ),
    /another share action/i
  );
});

test('storage refusal and corrupt records remain explicit fail-closed blockers', () => {
  const session = start();
  const original = sessionStorage.setItem;
  sessionStorage.setItem = () => {
    throw new Error('QuotaExceededError');
  };
  try {
    assertRecoveryCode(
      () => recovery.preservePendingCapture(session, capture.operationId, 1_000),
      'STORAGE_UNAVAILABLE'
    );
  } finally {
    sessionStorage.setItem = original;
  }

  recovery.preservePendingCapture(session, capture.operationId, 1_000);
  const storageKey = [...memory.keys()][0];
  memory.set(storageKey, '{not-json');
  assert.deepEqual(
    recovery.readPendingCapture(session, 2_000),
    { state: 'BLOCKED', reason: 'CORRUPT' }
  );
  assert.equal(memory.has(storageKey), true);
  assertRecoveryCode(
    () => recovery.preservePendingCapture(
      session,
      '55555555-5555-4555-8555-555555555555',
      2_001
    ),
    'RECOVERY_BLOCKED',
    'CORRUPT'
  );

  const originalGet = sessionStorage.getItem;
  sessionStorage.getItem = () => {
    throw new Error('SecurityError');
  };
  try {
    assert.deepEqual(
      recovery.readPendingCapture(session, 2_002),
      { state: 'BLOCKED', reason: 'STORAGE_UNAVAILABLE' }
    );
  } finally {
    sessionStorage.getItem = originalGet;
  }
  assert.equal(h.calls.length, 0);
});

test('expired, invalid-time and previous-account records cannot admit a new command', () => {
  const session = start();
  recovery.preservePendingCapture(session, capture.operationId, 1_000);
  const expiredAt = 1_000 + 24 * 60 * 60 * 1000 + 1;
  assert.deepEqual(
    recovery.readPendingCapture(session, expiredAt),
    { state: 'BLOCKED', reason: 'EXPIRED' }
  );
  const storageKey = [...memory.keys()][0];
  assert.equal(memory.has(storageKey), true);
  assertRecoveryCode(
    () => recovery.preservePendingCapture(
      session,
      '55555555-5555-4555-8555-555555555555',
      expiredAt
    ),
    'RECOVERY_BLOCKED',
    'EXPIRED'
  );

  const future = JSON.parse(memory.get(storageKey));
  future.savedAtEpochMs = expiredAt + 120_000;
  memory.set(storageKey, JSON.stringify(future));
  assert.deepEqual(
    recovery.readPendingCapture(session, expiredAt),
    { state: 'BLOCKED', reason: 'INVALID_TIME' }
  );

  recovery.preservePendingShortlistCreation(session, shortlist, 1_000);
  h.signIn({ id: 92, role: 'EMPLOYEE', token: 'replacement-token' });
  assert.equal(memory.size, 0);
  const replacement = h.session.captureOperationsSession();
  assert.deepEqual(
    recovery.readPendingShortlistCreation(replacement, shortlist.leadId),
    { state: 'ABSENT' }
  );
  assert.deepEqual(
    recovery.readPendingShareEvent(replacement, shareEvent.leadId),
    { state: 'ABSENT' }
  );
});

test('recovery state is bounded without evicting unresolved identities', () => {
  const session = start();
  for (let leadId = 1; leadId <= 12; leadId += 1) {
    recovery.preservePendingShortlistCreation(session, {
      operationId: `77777777-7777-4777-8777-${String(leadId).padStart(12, '0')}`,
      leadId
    }, 1_000);
  }
  assert.equal(memory.size, 12);
  const before = new Map(memory);
  assertRecoveryCode(
    () => recovery.preservePendingShortlistCreation(session, {
      operationId: '88888888-8888-4888-8888-888888888888',
      leadId: 13
    }, 1_001),
    'CAPACITY'
  );
  assert.deepEqual(memory, before);
});

test('a dispatched malformed or missing acknowledgement is classified as uncertain', () => {
  for (const status of [undefined, 200, 201, 204, 500, 503]) {
    assert.equal(recovery.commandStatusIsUncertain(status), true);
  }
  for (const status of [400, 401, 403, 404, 409]) {
    assert.equal(recovery.commandStatusIsUncertain(status), false);
  }
});

test('workspace reserves before the external action, blocks unresolved state, and reconciles the saved share', () => {
  const captureStart = workspace.indexOf('const save = async (');
  const captureEnd = workspace.indexOf('const checkSaved = async () =>', captureStart);
  const captureSave = workspace.slice(captureStart, captureEnd);
  assert.ok(captureStart > 0 && captureEnd > captureStart);
  assert.ok(
    captureSave.indexOf('preservePendingCapture') < captureSave.indexOf('operationsLeadService.capture')
  );
  assert.match(captureSave, /commandStatusIsUncertain\(status\)/);
  assert.match(workspace, /forgetPendingCapture\(session, operationId\.current\)/);

  const shortlistStart = workspace.indexOf('const saveAndContinue = async');
  const shortlistEnd = workspace.indexOf('return (', shortlistStart);
  const shortlistSave = workspace.slice(shortlistStart, shortlistEnd);
  assert.ok(
    shortlistSave.indexOf('preservePendingShortlistCreation')
      < shortlistSave.indexOf('operationsLeadService.createShortlist')
  );
  assert.match(shortlistSave, /commandStatusIsUncertain\(status\)/);

  const eventStart = workspace.indexOf('const submitReservedEvent = async (');
  const eventEnd = workspace.indexOf('const writeRecord = async (', eventStart);
  const eventSave = workspace.slice(eventStart, eventEnd);
  assert.match(eventSave, /commandStatusIsUncertain\(status\)/);

  for (const scopedSource of [captureSave, shortlistSave, eventSave]) {
    assert.doesNotMatch(
      scopedSource,
      /dispatched\.current && \(status === undefined \|\| status >= 500\)/
    );
  }
  assert.match(workspace, /readPendingCapture\(session\)/);
  assert.match(workspace, /readPendingShortlistCreation\(session, lead\.id\)/);
  assert.match(workspace, /readPendingShareEvent\(session, leadId\)/);
  assert.match(workspace, /pending\.state === 'BLOCKED'/);
  assert.match(workspace, /eventRecoveryBlocked/);
  const handoffMountStart = workspace.indexOf(
    'const pending = readPendingShareEvent(session, lead.id);'
  );
  const handoffMountEnd = workspace.indexOf(
    'useEffect(() => subscribeOperationsSession',
    handoffMountStart
  );
  assert.ok(handoffMountStart > 0 && handoffMountEnd > handoffMountStart);
  const handoffMount = workspace.slice(handoffMountStart, handoffMountEnd);
  assert.match(handoffMount, /pending\.state === 'PENDING'/);
  assert.match(handoffMount, /eventShareId\.current = pending\.command\.shareId/);
  assert.match(handoffMount, /pending\.state === 'BLOCKED'/);
  assert.match(workspace, /if \(actionInFlight\.current \|\| busy \|\| eventRecoveryBlocked \|\| pendingEvent\) return;/);
  assert.match(workspace, /if \(recordInFlight\.current\) return null;/);
  assert.match(workspace, /if \(saveInFlight\.current \|\| saving \|\| checkingCreation\) return;/);

  const shortlistCheckStart = workspace.indexOf('const checkPendingCreation = async () =>');
  const shortlistCheckEnd = workspace.indexOf('const saveAndContinue = async', shortlistCheckStart);
  const shortlistCheck = workspace.slice(shortlistCheckStart, shortlistCheckEnd);
  assert.doesNotMatch(shortlistCheck, /createShortlist\(/);

  const eventCheckStart = workspace.indexOf('const reconcilePendingEvent = async');
  const eventCheckEnd = workspace.indexOf('const openWhatsAppConfirmation = async', eventCheckStart);
  const eventCheck = workspace.slice(eventCheckStart, eventCheckEnd);
  assert.doesNotMatch(eventCheck, /recordShareEvent\(/);
  assert.match(eventCheck, /eventShareId\.current/);

  const copyStart = workspace.indexOf('const performCopy = async');
  const copyEnd = workspace.indexOf('const copyPreparedText = async', copyStart);
  const copy = workspace.slice(copyStart, copyEnd);
  assert.ok(copy.indexOf('reserveEvent(') < copy.indexOf('navigator.clipboard.writeText'));
  assert.match(copy, /submitReservedEvent\('CLIPBOARD_COPIED'/);
});

test('an expired or corrupt reservation retains its metadata and reports a bounded recovery boundary', () => {
  const session = start();
  recovery.preservePendingShareEvent(session, shareEvent, 1_000);
  const storageKey = [...memory.keys()][0];
  const original = JSON.parse(memory.get(storageKey));
  const expiredAt = 1_000 + 24 * 60 * 60 * 1000 + 1;
  const cases = [
    { value: '{broken', reason: 'CORRUPT', now: 1_001 },
    { value: 'null', reason: 'CORRUPT', now: 1_001 },
    {
      value: JSON.stringify({
        ...original,
        shareId: null
      }),
      reason: 'CORRUPT',
      now: 1_001
    },
    {
      value: JSON.stringify({
        ...original,
        savedAtEpochMs: 1_000
      }),
      reason: 'EXPIRED',
      now: expiredAt
    }
  ];
  for (const testCase of cases) {
    memory.set(storageKey, testCase.value);
    assert.deepEqual(
      recovery.readPendingShareEvent(session, shareEvent.leadId, testCase.now),
      { state: 'BLOCKED', reason: testCase.reason }
    );
    assert.equal(memory.has(storageKey), true);
  }
});
