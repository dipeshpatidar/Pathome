/**
 * F1-F4 production-path reliability matrix for property availability save/recovery.
 *
 * These tests execute the source-extracted production `saveAvailability`, `runGuardedCommand`,
 * `withdrawDeniedRoot`, ownership registry and availability helpers against controlled reads and
 * transport. No React mount, real HTTP or PostgreSQL call is claimed.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { installOperationsActionHarness } from './support/operationsActionHarness.mjs';

const h = await installOperationsActionHarness();
const { createProductionAvailabilityHarness } = await import(
  './support/availabilityProductionRunnerHarness.mjs'
);
const { ApiRequestError } = await import('../services/apiError.ts');
const p = h.presentation;
const KOLKATA = 'Asia/Kolkata';
const FIXTURE_NOW = new Date('2026-10-22T00:00:00Z');
const firstTick = () => new Promise((resolve) => setTimeout(resolve, 0));

const item = (overrides = {}) => ({
  itemId: 91,
  listingId: 8001,
  title: 'Review stop',
  position: 1,
  sourceRequestId: 501,
  derivedFromRequestId: null,
  origin: 'TENANT_REQUESTED',
  confirmationStatus: 'CONFIRMED',
  availabilityConfirmedAt: null,
  removedAt: null,
  removalReason: null,
  propertyAvailabilityStartAt: '2026-10-22T03:17:00.123456Z',
  propertyAvailabilityEndAt: '2026-10-30T07:17:00Z',
  propertyAvailabilityZoneId: KOLKATA,
  propertyAvailabilitySource: 'PHONE',
  ...overrides
});

const detail = (overrides = {}, version = 6) => p.toSessionDetail({
  sessionId: 3108,
  status: 'DRAFT',
  version,
  city: 'Indore',
  items: [item(overrides)],
  linkedRequestVersions: [{ requestId: 501, version: 5 }],
  teamId: 7,
  teamDisplayName: 'Team A',
  coordinatorUserId: 41,
  updatedAt: '2026-10-12T16:00:00Z',
  tenantConfirmationState: 'NOT_REQUIRED',
  startedAt: null,
  expiresAt: null,
  executionStateChangedAt: null
});

const windowOf = (session) => ({
  availabilityStartAt: p.instantToZoneInstant(session.items[0].availabilityStartAt, KOLKATA),
  availabilityEndAt: p.instantToZoneInstant(session.items[0].availabilityEndAt, KOLKATA),
  availabilityZoneId: KOLKATA,
  availabilitySource: 'PHONE'
});

const editedWindow = (session) => ({
  ...windowOf(session),
  availabilityStartAt: '2026-10-23T18:17:00+05:30'
});

const attempt = (window, version = 6) => ({
  choice: 'window',
  itemId: 91,
  expectedSessionVersion: version,
  window
});

const createHarness = (before, options = {}) => {
  h.signIn({ id: 41, role: 'EMPLOYEE', token: 'local-test-only' });
  const session = h.session.captureOperationsSession();
  const harness = createProductionAvailabilityHarness(p, { before, session, ...options });
  return {
    ...harness,
    save: (editorGeneration, window) =>
      harness.save(editorGeneration, window, FIXTURE_NOW)
  };
};

/* -------------------------------------------------------------------------- *
 * F1 — current editor at the real dispatch boundary
 * -------------------------------------------------------------------------- */

test('F1-a actual runner: Cancel/Escape during a held preflight sends zero PUTs', async () => {
  const before = detail();
  const gate = { resolve: null };
  const s = createHarness(before, {
    reads: [() => new Promise((resolve) => { gate.resolve = resolve; })]
  });
  const pending = s.save(1, windowOf(before));
  await firstTick();
  // Both the Cancel button and Escape invoke this workspace discard handler.
  s.draftRef.current = null;
  gate.resolve(before);
  const result = await pending;
  assert.equal(result.status, 'superseded');
  assert.equal(s.state.puts.length, 0);
  assert.equal(s.state.details.length, 0);
  assert.equal(s.state.notices.length, 0);
});

test('F1-b actual runner: item, Session and same-item reopen replacements block stale dispatch', async () => {
  for (const replacement of [
    { editorGeneration: 2, itemId: 92 },
    { editorGeneration: 2, sessionId: 9999 },
    null
  ]) {
    const before = detail();
    let release;
    const s = createHarness(before, {
      reads: [() => new Promise((resolve) => { release = resolve; })]
    });
    const pending = s.save(1, windowOf(before));
    await firstTick();
    s.draftRef.current = replacement === null
      ? null
      : { ...s.draftRef.current, ...replacement };
    const newer = s.draftRef.current;
    release(before);
    assert.equal((await pending).status, 'superseded');
    assert.equal(s.state.puts.length, 0, JSON.stringify(replacement));
    assert.deepEqual(s.draftRef.current, newer);
  }
});

test('F1-c actual runner: a stale held response publishes no malformed, wrong-Session or lifecycle result', async () => {
  const before = detail();
  const staleResponses = [
    null,
    { sessionId: 9999 },
    { ...before, status: 'SCHEDULED', version: 7 },
    { ...before, items: [{ itemId: 91, malformed: true }] }
  ];
  for (const response of staleResponses) {
    let release;
    const s = createHarness(before, {
      reads: [() => new Promise((resolve) => { release = resolve; })]
    });
    const pending = s.save(1, windowOf(before));
    await firstTick();
    s.draftRef.current = { ...s.draftRef.current, editorGeneration: 2, itemId: 92 };
    const newDraft = s.draftRef.current;
    release(response);
    assert.equal((await pending).status, 'superseded');
    assert.equal(s.state.puts.length, 0);
    assert.equal(s.state.details.length, 0);
    assert.equal(s.state.notices.length, 0);
    assert.equal(s.draftRef.current, newDraft);
  }
});

test('F1-d actual runner: current unchanged/version-only saves once; changed availability sends no PUT', async () => {
  const before = detail();
  const unchanged = createHarness(before);
  assert.equal((await unchanged.save(1, windowOf(before))).status, 'saved');
  assert.equal(unchanged.state.puts.length, 1);

  const versionOnly = createHarness(before, { reads: [detail({}, 7)] });
  assert.equal((await versionOnly.save(1, windowOf(before))).status, 'saved');
  assert.equal(versionOnly.state.puts.length, 1);

  const changed = detail({ propertyAvailabilityStartAt: '2026-10-24T03:17:00Z' }, 7);
  const conflict = createHarness(before, { reads: [changed] });
  const result = await conflict.save(1, windowOf(before));
  assert.equal(result.status, 'not-saved');
  assert.equal(conflict.state.puts.length, 0);
  assert.match(conflict.draftRef.current.failureMessage, /changed on the server before your save was sent/);
});

test('F1-e actual runner: Cancel or editor B after dispatch never sends a second PUT', async () => {
  const before = detail();
  const late = [];
  for (const mutation of ['cancel', 'replace', 'fail']) {
    const s = createHarness(before, {
      onPut: ({ draftRef }) => {
        if (mutation === 'cancel') draftRef.current = null;
        if (mutation === 'replace') draftRef.current = { ...draftRef.current, editorGeneration: 2, itemId: 92 };
        if (mutation === 'fail') throw new Error('late transport failure');
        return before;
      }
    });
    const result = await s.save(1, windowOf(before));
    late.push(result);
    assert.equal(s.state.puts.length, 1);
    if (mutation === 'replace') {
      assert.equal(s.draftRef.current.editorGeneration, 2);
      assert.equal(s.state.successes.length, 0);
    }
  }
  assert.ok(late.every((result) => !/cancelled|canceled/i.test(result.message ?? '')));
});

test('F1-f final-check negative control: only the final pre-dispatch guard discriminates a boundary cancel', async () => {
  const before = detail();
  for (const removeFinalGuard of [false, true]) {
    const s = createHarness(before, {
      sourceMutation: (source) => {
        const withBoundaryCancel = source.replace(
          '      const blocked = ownership.blockReasonForDispatch(operation, authorityRef.current);',
          '      availabilityDraftRef.current = null;\n'
            + '      const blocked = ownership.blockReasonForDispatch(operation, authorityRef.current);'
        );
        assert.notEqual(withBoundaryCancel, source);
        if (!removeFinalGuard) return withBoundaryCancel;
        const finalGuardRemoved = withBoundaryCancel.replace(
          /if \(blocked !== null\s*\|\| operation\.dispatched\s*\|\| options\.contextCurrent\?\.\(\) === false\)\s*return nothing;/,
          'if (blocked !== null || operation.dispatched) return nothing;'
        );
        assert.notEqual(finalGuardRemoved, withBoundaryCancel);
        return finalGuardRemoved;
      }
    });
    await s.save(1, windowOf(before));
    assert.equal(
      s.state.puts.length,
      removeFinalGuard ? 1 : 0,
      removeFinalGuard ? 'removing the final guard must expose the stale PUT' : 'the final guard must stop dispatch'
    );
  }
});

test('F1-f cancellation timing: Cancel while preflight is still held dispatches nothing, including under a pre-dispatch fault', async () => {
  const before = detail();
  for (const removeContextGuards of [false, true]) {
    const gate = { reject: null, resolve: null };
    const s = createHarness(before, {
      reads: [() => new Promise((resolve, reject) => {
        gate.resolve = resolve;
        gate.reject = reject;
      })],
      sourceMutation: removeContextGuards
        ? (source) => {
            const mutated = source.replaceAll('|| options.contextCurrent?.() === false', '');
            assert.notEqual(mutated, source);
            return mutated;
          }
        : undefined
    });
    const pending = s.save(1, windowOf(before));
    await firstTick();
    assert.equal(s.state.puts.length, 0, 'preflight must still be held');
    s.discard(1);
    gate.resolve(before);
    const result = await pending;
    assert.equal(s.draftRef.current, null);
    if (removeContextGuards) {
      assert.equal(s.state.puts.length, 1, 'the isolated fault must expose the missing pre-dispatch protection');
      assert.throws(() => assert.equal(s.state.puts.length, 0));
    } else {
      assert.equal(result.status, 'superseded');
      assert.equal(s.state.puts.length, 0);
      assert.equal(s.state.notices.length, 0);
    }
  }
});

test('NC-F1-A exception-scope control: removing only the rejected-preflight publication guards exposes stale mutation', async () => {
  const before = detail();
  const gate = { reject: null };
  const s = createHarness(before, {
    reads: [() => new Promise((_resolve, reject) => { gate.reject = reject; })],
    sourceMutation: (source) => {
      const catchGuardRemoved = source.replace(
        /if \(isOperationsSessionChangedError\(cause\)\)\s*return nothing;\s*if \(!ownership\.canCommit\(operation, authorityRef\.current\)\s*\|\| options\.contextCurrent\?\.\(\) === false\)\s*return nothing;/,
        'if (isOperationsSessionChangedError(cause))\n'
          + '            return nothing;\n'
          + '        if (!ownership.canCommit(operation, authorityRef.current))\n'
          + '            return nothing;'
      );
      assert.notEqual(catchGuardRemoved, source);
      const reportContextRemoved = catchGuardRemoved.replace(
        'contextCurrent: options.contextCurrent',
        'contextCurrent: undefined'
      );
      assert.notEqual(reportContextRemoved, catchGuardRemoved);
      return reportContextRemoved;
    }
  });
  const pending = s.save(1, windowOf(before));
  await firstTick();
  s.discard(1);
  s.begin(91);
  const replacement = s.draftRef.current;
  gate.reject(new ApiRequestError('generic denied', 403));
  await pending;
  assert.equal(s.state.withdraws, 1);
  assert.equal(s.draftRef.current, null);
  assert.throws(() => assert.equal(s.draftRef.current, replacement));
  assert.throws(() => assert.equal(s.state.withdraws, 0));
});

for (const status of [null, 403, 404]) {
  test(`F1 rejected preflight after Cancel/reopen leaves generation B untouched (${status ?? 'network'})`, async () => {
    const before = detail();
    const gate = { reject: null };
    const s = createHarness(before, {
      reads: [() => new Promise((_resolve, reject) => { gate.reject = reject; })]
    });
    const pending = s.save(1, windowOf(before));
    await firstTick();
    s.discard(1);
    s.begin(91);
    const replacement = s.draftRef.current;
    assert.equal(replacement.editorGeneration, 2);
    gate.reject(status === null
      ? new ApiRequestError('temporary network failure', 503)
      : new ApiRequestError('generic denied', status));
    const result = await pending;
    assert.equal(result.status, 'superseded');
    assert.equal(s.draftRef.current, replacement);
    assert.equal(s.state.puts.length, 0);
    assert.equal(s.state.notices.length, 0);
    assert.equal(s.state.withdraws, 0);
  });
}

test('F1 rejected preflight after item, Session, account or authority replacement publishes nothing', async () => {
  const before = detail();
  for (const replacement of ['item', 'session', 'account', 'authority']) {
    const gate = { reject: null, resolve: null };
    const s = createHarness(before, {
      reads: [() => new Promise((resolve, reject) => {
        gate.resolve = resolve;
        gate.reject = reject;
      })]
    });
    const pending = s.save(1, windowOf(before));
    await firstTick();
    s.discard(1);
    s.begin(91);
    if (replacement === 'item') {
      s.draftRef.current = { ...s.draftRef.current, editorGeneration: 2, itemId: 92 };
    } else if (replacement === 'session') {
      s.draftRef.current = { ...s.draftRef.current, editorGeneration: 2, sessionId: 9999 };
    } else if (replacement === 'account') {
      h.signOut();
      h.signIn({ id: 42, role: 'EMPLOYEE', token: 'second-account-token' });
      const nextSession = h.session.captureOperationsSession();
      s.draftRef.current = {
        ...s.draftRef.current,
        editorGeneration: 2,
        identity: nextSession.identity
      };
    } else {
      s.state.authorizationGeneration = 2;
      s.draftRef.current = {
        ...s.draftRef.current,
        editorGeneration: 2,
        authorizationGeneration: 2
      };
    }
    const replacementDraft = structuredClone(s.draftRef.current);
    gate.reject(new ApiRequestError('generic denied', 403));
    assert.equal((await pending).status, 'superseded', replacement);
    assert.deepEqual(structuredClone(s.draftRef.current), replacementDraft, replacement);
    assert.equal(s.state.puts.length, 0, replacement);
    assert.equal(s.state.notices.length, 0, replacement);
    assert.equal(s.state.withdraws, 0, replacement);
  }
});

test('F1 reverse ordering: a rejection while A is still current is truthful, then B starts independently', async () => {
  const before = detail();
  const gate = { reject: null };
  const s = createHarness(before, {
    reads: [
      () => new Promise((_resolve, reject) => { gate.reject = reject; }),
      before
    ]
  });
  const pending = s.save(1, windowOf(before));
  await firstTick();
  gate.reject(new ApiRequestError('temporary unavailable', 503));
  const result = await pending;
  assert.equal(result.status, 'not-saved');
  assert.equal(s.state.notices.length, 1);
  assert.equal(s.draftRef.current.editorGeneration, 1);
  assert.equal(s.draftRef.current.status, 'refused');

  s.discard(1);
  s.begin(91);
  const replacement = s.draftRef.current;
  assert.equal(replacement.editorGeneration, 2);
  assert.equal(replacement.status, 'editing');
  assert.equal(replacement.failureMessage, null);
  assert.equal((await s.save(2, windowOf(before))).status, 'saved');
  assert.equal(s.state.puts.length, 1);
});

test('F1 current-editor preflight rejection is still reported with raw input retained', async () => {
  const before = detail();
  for (const cause of [
    new ApiRequestError('temporary unavailable', 503),
    new ApiRequestError('timeout', 504)
  ]) {
    const gate = { reject: null };
    const s = createHarness(before, {
      reads: [() => new Promise((_resolve, reject) => { gate.reject = reject; })]
    });
    const original = s.draftRef.current;
    const pending = s.save(1, windowOf(before));
    await firstTick();
    gate.reject(cause);
    const result = await pending;
    assert.equal(result.status, 'not-saved');
    assert.equal(s.state.puts.length, 0);
    assert.equal(s.state.notices.length, 1);
    assert.equal(s.draftRef.current.status, 'refused');
    assert.deepEqual(s.draftRef.current.window, original.window);
    assert.deepEqual(s.draftRef.current.fieldProvenance, original.fieldProvenance);
    assert.equal(s.draftRef.current.failureMessage, result.message);
  }
});

test('F1 current-editor preflight denial still withdraws the exact root and draft', async () => {
  const before = detail();
  for (const status of [403, 404]) {
    const gate = { reject: null };
    const s = createHarness(before, {
      reads: [() => new Promise((_resolve, reject) => { gate.reject = reject; })]
    });
    const pending = s.save(1, windowOf(before));
    await firstTick();
    gate.reject(new ApiRequestError('generic denied', status));
    assert.equal((await pending).status, 'superseded');
    assert.equal(s.draftRef.current, null);
    assert.equal(s.state.withdraws, 1);
    assert.equal(s.state.displayTarget, null);
    assert.equal(s.state.notices.length, 1);
    assert.doesNotMatch(s.state.notices[0][0], /exists|not found|forbidden/i);
  }
});

test('F1 stale rejection finalizer cannot damage or lock out the replacement editor', async () => {
  const before = detail();
  const gate = { reject: null };
  const s = createHarness(before, {
    reads: [
      () => new Promise((_resolve, reject) => { gate.reject = reject; }),
      before
    ]
  });
  const pending = s.save(1, windowOf(before));
  await firstTick();
  s.discard(1);
  s.begin(91);
  const replacement = s.draftRef.current;
  gate.reject(new ApiRequestError('generic denied', 403));
  await pending;
  assert.equal(s.draftRef.current, replacement);
  assert.equal(s.ownership.isLocked(), false);
  assert.equal((await s.save(2, windowOf(before))).status, 'saved');
  assert.equal(s.state.puts.length, 1);
});

/* -------------------------------------------------------------------------- *
 * F2 — one settlement flight and no fall-through PUT
 * -------------------------------------------------------------------------- */

test('F2-a actual runner: repeated Save during held reconciliation joins one read and one original PUT', async () => {
  const before = detail();
  const window = editedWindow(before);
  let release;
  const s = createHarness(before, {
    lost: true,
    reads: [
      before,
      before,
      () => new Promise((resolve) => { release = resolve; })
    ]
  });
  const first = await s.save(1, window);
  assert.equal(first.status, 'unconfirmed');
  assert.equal(s.state.puts.length, 1);
  const originalAttempt = s.draftRef.current.attempt;

  const second = s.save(1, window);
  await firstTick();
  const third = s.save(1, window);
  await firstTick();
  assert.equal(s.state.gets.length, 3, 'one preflight plus one shared settlement read');
  assert.equal(s.state.puts.length, 1);
  assert.deepEqual(s.draftRef.current.attempt, originalAttempt);
  release(before);
  await Promise.all([second, third]);
  assert.equal(s.state.puts.length, 1);
});

test('F2-b actual runner: overlap before and after settlement never dispatches another PUT', async () => {
  const before = detail();
  const window = editedWindow(before);
  const s = createHarness(before, { lost: true, reads: [before, before, before] });
  assert.equal((await s.save(1, window)).status, 'unconfirmed');
  const originalAttempt = s.draftRef.current.attempt;
  const overlapping = await Promise.all([s.save(1, window), s.save(1, window)]);
  assert.equal(overlapping.length, 2);
  assert.equal(s.state.puts.length, 1);
  assert.deepEqual(s.draftRef.current.attempt, originalAttempt);
  await s.save(1, window);
  assert.equal(s.state.puts.length, 1);
});

test('F2-b actual runner: matching, mismatch, version, error and null reads classify without a resend', async () => {
  const before = detail();
  const window = editedWindow(before);
  const matching = detail({ propertyAvailabilityStartAt: '2026-10-23T12:47:00Z' }, 7);
  const cases = [
    { name: 'matching', response: matching, status: 'saved' },
    { name: 'same-version mismatch', response: before, status: 'unconfirmed' },
    { name: 'lower version mismatch', response: { ...before, version: 5 }, status: 'unconfirmed' },
    { name: 'missing version', response: { ...before, version: null }, status: 'unconfirmed' },
    { name: 'higher version mismatch', response: detail({ propertyAvailabilityStartAt: '2026-10-24T03:17:00Z' }, 7), status: 'not-saved' },
    { name: 'server error', response: new ApiRequestError('temporary failure', 503), status: 'unconfirmed' },
    { name: 'null read', response: null, status: 'unconfirmed' }
  ];
  for (const row of cases) {
    const s = createHarness(before, {
      lost: true,
      reads: [before, row.response]
    });
    const result = await s.save(1, window);
    assert.equal(result.status, row.status, row.name);
    assert.equal(s.state.puts.length, 1, row.name);
    assert.doesNotMatch(result.message ?? '', /not applied|nothing was changed/i, row.name);
  }
});

test('F2-c actual runner: edits during uncertainty retain all newer input and the immutable attempt', async () => {
  const before = detail();
  const originalWindow = editedWindow(before);
  let release;
  const matching = before;
  const s = createHarness(before, {
    reads: [() => new Promise((resolve) => { release = resolve; })]
  });
  const draft = p.createAvailabilityDraft({
    sessionId: 3108,
    sessionVersion: before.version,
    item: before.items[0],
    identity: s.draftRef.current.identity,
    authorizationGeneration: 1,
    editorGeneration: 1
  });
  s.draftRef.current = {
    ...draft,
    choice: 'window',
    window: originalWindow,
    attempt: attempt(originalWindow),
    status: 'uncertain'
  };
  const immutableAttempt = structuredClone(s.draftRef.current.attempt);
  const pending = s.save(1, originalWindow);
  await firstTick();
  s.draftRef.current = {
    ...s.draftRef.current,
    choice: 'unavailable',
    window: {
      availabilityStartAt: '2026-10-24T09:15',
      availabilityEndAt: '2026-10-31T11:45',
      availabilityZoneId: 'America/New_York',
      availabilitySource: 'WHATSAPP'
    },
    fieldProvenance: { start: 'EDITED', end: 'EDITED' }
  };
  const newer = structuredClone(s.draftRef.current);
  release(matching);
  const result = await pending;
  assert.equal(result.status, 'unconfirmed');
  assert.deepEqual(s.draftRef.current.attempt, immutableAttempt);
  assert.equal(s.draftRef.current.choice, newer.choice);
  assert.deepEqual(s.draftRef.current.window, newer.window);
  assert.deepEqual(s.draftRef.current.fieldProvenance, newer.fieldProvenance);
  assert.doesNotMatch(result.message, /earlier save now appears/i);
  assert.equal(s.state.puts.length, 0);
});

test('F2-d actual runner: equal current state never becomes a historical receipt', async () => {
  const before = detail();
  const matching = detail({ propertyAvailabilityStartAt: '2026-10-23T12:47:00Z' }, 7);
  const first = createHarness(before, { reads: [matching] });
  first.draftRef.current = {
    ...first.draftRef.current,
    window: editedWindow(before),
    attempt: attempt(editedWindow(before)),
    status: 'uncertain'
  };
  const a = await first.save(1, editedWindow(before));

  const second = createHarness(before, { reads: [matching] });
  second.draftRef.current = {
    ...second.draftRef.current,
    window: editedWindow(before),
    attempt: attempt(editedWindow(before)),
    status: 'uncertain'
  };
  const b = await second.save(1, editedWindow(before));
  assert.equal(a.message, b.message);
  assert.doesNotMatch(a.message, /accepted|applied|receipt|earlier save now appears/i);
  assert.equal(first.state.puts.length + second.state.puts.length, 0);
});

test('F2-e actual runner: higher-version fencing publishes current state and a later explicit Save rechecks it', async () => {
  const before = detail();
  const higher = detail({ propertyAvailabilityStartAt: '2026-10-24T03:17:00Z' }, 7);
  let release;
  let putCount = 0;
  const s = createHarness(before, {
    reads: [before, () => new Promise((resolve) => { release = resolve; }), higher],
    onPut: () => {
      putCount += 1;
      if (putCount === 1) throw new Error('lost first response');
      return higher;
    }
  });
  const original = s.save(1, editedWindow(before));
  await firstTick();
  release(higher);
  const fenced = await original;
  assert.equal(fenced.status, 'not-saved');
  assert.equal(s.state.details.length, 1);
  assert.equal(s.state.puts.length, 1);
  assert.equal(s.draftRef.current.attempt, null);
  assert.doesNotMatch(fenced.message, /another actor|accepted|applied|receipt/i);
  assert.match(fenced.message, /outcome is not confirmed/i);

  const nextWindow = {
    ...windowOf(higher),
    availabilityStartAt: '2026-10-25T18:17:00+05:30'
  };
  s.draftRef.current = {
    ...s.draftRef.current,
    choice: 'window',
    window: nextWindow,
    status: 'editing'
  };
  const later = await s.save(1, nextWindow);
  assert.equal(later.status, 'saved', JSON.stringify(later));
  assert.equal(s.state.puts.length, 2);
});

test('F2-f actual runner: an old held settlement cannot clear or overwrite a newer editor', async () => {
  const before = detail();
  let release;
  const s = createHarness(before, {
    lost: true,
    reads: [before, before, () => new Promise((resolve) => { release = resolve; })]
  });
  assert.equal((await s.save(1, editedWindow(before))).status, 'unconfirmed');
  const pending = s.save(1, editedWindow(before));
  await firstTick();
  // Cancel clears the owning draft before the newer editor is opened.
  const identity = s.draftRef.current.identity;
  s.draftRef.current = null;
  s.draftRef.current = {
    ...p.createAvailabilityDraft({
      sessionId: 3108,
      sessionVersion: before.version,
      item: before.items[0],
      identity,
      authorizationGeneration: 1,
      editorGeneration: 2
    }),
    editorGeneration: 2,
    itemId: 92,
    status: 'editing',
    attempt: null
  };
  const newer = structuredClone(s.draftRef.current);
  release(detail({}, 8));
  await pending;
  assert.deepEqual(s.draftRef.current, newer);
  assert.equal(s.state.puts.length, 1);
});

test('F2-f actual runner: settlement cleanup is scoped to its editor generation', async () => {
  const before = detail();
  let releaseA;
  let releaseB;
  const s = createHarness(before, {
    reads: [
      () => new Promise((resolve) => { releaseA = resolve; }),
      () => new Promise((resolve) => { releaseB = resolve; })
    ]
  });
  s.draftRef.current = {
    ...s.draftRef.current,
    window: editedWindow(before),
    attempt: attempt(editedWindow(before)),
    status: 'uncertain'
  };
  const first = s.save(1, editedWindow(before));
  await firstTick();
  s.draftRef.current = {
    ...s.draftRef.current,
    editorGeneration: 2,
    itemId: 91,
    status: 'uncertain',
    attempt: attempt(editedWindow(before))
  };
  const second = s.save(2, editedWindow(before));
  await firstTick();
  assert.equal(
    s.settlementFlights.size,
    2,
    JSON.stringify({ keys: [...s.settlementFlights.keys()], draft: s.draftRef.current })
  );
  releaseA(before);
  await first;
  assert.equal(s.settlementFlights.size, 1);
  assert.equal(s.draftRef.current.editorGeneration, 2);
  releaseB(before);
  await second;
  assert.equal(s.settlementFlights.size, 0);
  assert.equal(s.draftRef.current.editorGeneration, 2);
});

test('F2 negative control: removing the editor generation from the flight key causes cross-generation joining', async () => {
  const before = detail();
  let releaseA;
  const s = createHarness(before, {
    reads: [() => new Promise((resolve) => { releaseA = resolve; })],
    sourceMutation: (source) => {
      const mutated = source.replace(/editorGeneration\s*\]\.join/, "0].join");
      assert.notEqual(mutated, source);
      return mutated;
    }
  });
  s.draftRef.current = {
    ...s.draftRef.current,
    window: editedWindow(before),
    attempt: attempt(editedWindow(before)),
    status: 'uncertain'
  };
  const first = s.save(1, editedWindow(before));
  await firstTick();
  s.draftRef.current = {
    ...s.draftRef.current,
    editorGeneration: 2,
    itemId: 91,
    status: 'uncertain',
    attempt: attempt(editedWindow(before))
  };
  const second = s.save(2, editedWindow(before));
  await firstTick();
  assert.equal(s.settlementFlights.size, 1);
  assert.throws(() => assert.equal(s.settlementFlights.size, 2));
  releaseA(before);
  await Promise.all([first, second]);
});

test('F2 negative control: restoring uncertain-only fall-through dispatches a second PUT', async () => {
  const before = detail();
  let release;
  const s = createHarness(before, {
    lost: true,
    reads: [before, () => new Promise((resolve) => { release = resolve; })],
    sourceMutation: (source) => source.replace(
      "if (draft.status === 'reconciling') {",
      "if (false) {"
    )
  });
  const first = s.save(1, editedWindow(before));
  await firstTick();
  assert.equal(s.draftRef.current.status, 'reconciling');
  const overlapping = s.save(1, editedWindow(before));
  await firstTick();
  assert.equal(s.state.puts.length, 2);
  release(before);
  await Promise.all([first, overlapping]);
  assert.equal(s.state.puts.length, 2);
  assert.throws(() => assert.equal(s.state.puts.length, 1));
});

/* -------------------------------------------------------------------------- *
 * F3 — denied reconciliation roots
 * -------------------------------------------------------------------------- */

for (const status of [403, 404]) {
  test(`F3-a actual runner: reconciliation ${status} withdraws only its denied root`, async () => {
    const before = detail();
    const s = createHarness(before, {
      lost: true,
      reads: [before, new ApiRequestError('Access refused', status)]
    });
    const result = await s.save(1, editedWindow(before));
    assert.equal(result.status, 'superseded');
    assert.equal(s.draftRef.current, null);
    assert.equal(s.state.withdraws, 1);
    assert.equal(s.state.displayTarget, null);
    assert.equal(s.state.puts.length, 1);
    assert.equal(s.state.notices.length, 1, 'only the original uncertain notice');
  });
}

test('F3-c actual runner: transient read failures retain the authorized draft and immutable attempt', async () => {
  const before = detail();
  const cases = [
    new Error('network interrupted'),
    new ApiRequestError('timeout', 504),
    new ApiRequestError('server error', 500),
    null,
    { ...before, items: null }
  ];
  for (const failure of cases) {
    const s = createHarness(before, { lost: true, reads: [before, failure] });
    const result = await s.save(1, editedWindow(before));
    assert.equal(result.status, 'unconfirmed');
    assert.notEqual(s.draftRef.current, null);
    assert.notEqual(s.draftRef.current.attempt, null);
    assert.equal(s.state.withdraws, 0);
    assert.equal(s.state.puts.length, 1);
  }
});

for (const status of [403, 404]) {
  test(`F3-d actual runner: a genuinely rejected late ${status} cannot withdraw editor B, a remount or account B`, async () => {
    const before = detail();
    for (const replacement of ['editor', 'remount', 'account']) {
      let reject;
      const s = createHarness(before, {
        lost: true,
        reads: [before, before, () => new Promise((_resolve, fail) => { reject = fail; })]
      });
      assert.equal((await s.save(1, editedWindow(before))).status, 'unconfirmed');
      const pending = s.save(1, editedWindow(before));
      await firstTick();
      if (replacement === 'editor') {
        s.draftRef.current = { ...s.draftRef.current, editorGeneration: 2, itemId: 92 };
      } else if (replacement === 'remount') {
        s.context.openRootRef.current = { ...s.target, mountGeneration: 1 };
      } else {
        h.signOut();
        h.signIn({ id: 42, role: 'EMPLOYEE', token: 'second-account-token' });
        s.draftRef.current = {
          ...s.draftRef.current,
          identity: h.session.captureOperationsSession().identity,
          editorGeneration: 2,
          itemId: 92
        };
      }
      const newer = structuredClone(s.draftRef.current);
      reject(new ApiRequestError('generic denied', status));
      await pending;
      assert.deepEqual(structuredClone(s.draftRef.current), newer, replacement);
      assert.equal(s.state.withdraws, 0, replacement);
      assert.equal(s.state.puts.length, 1, replacement);
    }
  });
}

test('F3-b actual runner: a stale session-change result after account replacement cannot clear the new account', async () => {
  const before = detail();
  let release;
  const s = createHarness(before, {
    lost: true,
    reads: [before, before, () => new Promise((resolve) => { release = resolve; })]
  });
  assert.equal((await s.save(1, editedWindow(before))).status, 'unconfirmed');
  const pending = s.save(1, editedWindow(before));
  await firstTick();
  h.signOut();
  s.state.active = false;
  h.signIn({ id: 42, role: 'EMPLOYEE', token: 'second-account-token' });
  s.state.active = true;
  const nextSession = h.session.captureOperationsSession();
  s.draftRef.current = {
    ...s.draftRef.current,
    identity: nextSession.identity,
    editorGeneration: 2,
    itemId: 92,
    status: 'editing',
    failureMessage: null
  };
  const newer = structuredClone(s.draftRef.current);
  release(new s.sessionChangedError());
  const staleResult = await pending;
  assert.ok(['superseded', 'unconfirmed'].includes(staleResult.status));
  assert.deepEqual(JSON.parse(JSON.stringify(s.draftRef.current)), newer);
  assert.equal(s.state.withdraws, 0);
  assert.equal(s.state.notices.length, 1, 'the stale completion adds no notice');
  assert.equal(h.session.readOperationsSession().userId, 42);
});

test('F3-e actual runner: a grant-generation change cannot resurrect an old denied draft', async () => {
  const before = detail();
  let release;
  const s = createHarness(before, {
    lost: true,
    reads: [before, before, () => new Promise((resolve) => { release = resolve; })]
  });
  assert.equal((await s.save(1, editedWindow(before))).status, 'unconfirmed');
  const pending = s.save(1, editedWindow(before));
  await firstTick();
  s.state.authorizationGeneration = 2;
  s.draftRef.current = null;
  release(new ApiRequestError('Access refused', 403));
  assert.equal((await pending).status, 'superseded');
  assert.equal(s.draftRef.current, null);
  assert.equal(s.state.withdraws, 0);
});

test('F3-f actual runner: a later refresh failure cannot resurrect denied detail or draft', async () => {
  const before = detail();
  const s = createHarness(before, {
    lost: true,
    reads: [before, new ApiRequestError('Access refused', 404)],
    refreshOutcome: 'failed'
  });
  await s.save(1, editedWindow(before));
  assert.equal(s.draftRef.current, null);
  assert.equal(s.state.details.length, 0);
  assert.equal(s.state.withdraws, 1);
});

const startNeighboringCommand = async (s, gate) => {
  const pending = s.context.runGuardedCommand({
    kind: 'reorder',
    busyKey: 'neighbor',
    name: 'Neighbor reorder',
    loadFresh: () => gate.promise,
    buildTransmit: (fresh) => async () => fresh,
    onMissingVersion: () => {},
    applyResult: () => false
  });
  await firstTick();
  return { pending, operation: s.ownership.activeOperation };
};

for (const status of [403, 404]) {
  test(`F3-a/f actual runner: reconciliation ${status} withdraws under an unrelated mutation lock without stealing it`, async () => {
    const before = detail();
    const settlement = { reject: null, resolve: null };
    const neighbor = { reject: null, resolve: null };
    const s = createHarness(before, {
      lost: true,
      reads: [before, before, () => new Promise((resolve, reject) => {
        settlement.resolve = resolve;
        settlement.reject = reject;
      })]
    });
    assert.equal((await s.save(1, editedWindow(before))).status, 'unconfirmed');
    const pending = s.save(1, editedWindow(before));
    await firstTick();
    assert.equal(s.draftRef.current.status, 'reconciling');
    const neighborGate = {
      promise: new Promise((resolve, reject) => {
        neighbor.resolve = resolve;
        neighbor.reject = reject;
      })
    };
    const { pending: neighborPending, operation: neighborOperation } = await startNeighboringCommand(s, neighborGate);
    assert.equal(s.ownership.activeOperation, neighborOperation);
    assert.equal(s.ownership.isLocked(), true);

    settlement.reject(new ApiRequestError('generic denied', status));
    assert.equal((await pending).status, 'superseded');
    assert.equal(s.draftRef.current, null);
    assert.equal(s.state.withdraws, 1);
    assert.equal(s.state.displayTarget, null);
    assert.equal(s.ownership.activeOperation, neighborOperation, 'the neighboring command must keep its lock');
    assert.equal(s.ownership.isLocked(), true);

    neighbor.resolve(before);
    const neighborOutcome = await neighborPending;
    assert.equal(neighborOutcome.status, 'applied');
    assert.equal(s.ownership.isLocked(), false);
    assert.equal(s.draftRef.current, null);
    assert.equal(s.state.displayTarget, null, 'neighbor completion cannot remount the denied root');
  });
}

test('F3-c actual runner: transient settlement failures do not withdraw across a held neighboring lock', async () => {
  const before = detail();
  const cases = [
    { name: 'network', settle: ({ reject }) => reject(new Error('network interrupted')) },
    { name: 'timeout', settle: ({ reject }) => reject(new ApiRequestError('timeout', 504)) },
    { name: 'abort', settle: ({ reject }) => reject(new DOMException('aborted', 'AbortError')) },
    { name: '5xx', settle: ({ reject }) => reject(new ApiRequestError('server error', 500)) },
    { name: 'null', settle: ({ resolve }) => resolve(null) },
    { name: 'malformed', settle: ({ resolve }) => resolve({ ...before, items: null }) }
  ];
  for (const row of cases) {
    const settlement = { resolve: null, reject: null };
    const neighbor = { resolve: null, reject: null };
    const s = createHarness(before, {
      lost: true,
      reads: [before, before, () => new Promise((resolve, reject) => {
        settlement.resolve = resolve;
        settlement.reject = reject;
      })]
    });
    assert.equal((await s.save(1, editedWindow(before))).status, 'unconfirmed', row.name);
    const pending = s.save(1, editedWindow(before));
    await firstTick();
    const neighborGate = {
      promise: new Promise((resolve, reject) => {
        neighbor.resolve = resolve;
        neighbor.reject = reject;
      })
    };
    const { pending: neighborPending, operation } = await startNeighboringCommand(s, neighborGate);
    const attemptBefore = JSON.parse(JSON.stringify(s.draftRef.current.attempt));
    row.settle(settlement);
    assert.equal((await pending).status, 'unconfirmed', row.name);
    assert.notEqual(s.draftRef.current, null, row.name);
    assert.equal(s.draftRef.current.status, 'uncertain', row.name);
    assert.deepEqual(JSON.parse(JSON.stringify(s.draftRef.current.attempt)), attemptBefore, row.name);
    assert.equal(s.state.withdraws, 0, row.name);
    assert.equal(s.ownership.activeOperation, operation, row.name);
    assert.equal(s.ownership.isLocked(), true, row.name);
    neighbor.resolve(before);
    await neighborPending;
    assert.equal(s.ownership.isLocked(), false, row.name);
  }
});

test('F3 negative control A: restoring the old lock-dependent denial skips the contested withdrawal', async () => {
  const before = detail();
  const settlement = { reject: null };
  const neighbor = { resolve: null };
  const s = createHarness(before, {
    lost: true,
    reads: [before, before, () => new Promise((_resolve, reject) => { settlement.reject = reject; })],
    sourceMutation: (source) => {
      const mutated = source.replace(
        "if (cause instanceof ApiRequestError && (cause.status === 403 || cause.status === 404)) {",
        "if (cause instanceof ApiRequestError && (cause.status === 403 || cause.status === 404)) {\n"
          + "          if (ownership.isLocked()) return { status: 'superseded' };"
      );
      assert.notEqual(mutated, source);
      return mutated;
    }
  });
  assert.equal((await s.save(1, editedWindow(before))).status, 'unconfirmed');
  const pending = s.save(1, editedWindow(before));
  await firstTick();
  const { pending: neighborPending } = await startNeighboringCommand(s, {
    promise: new Promise((resolve) => { neighbor.resolve = resolve; })
  });
  settlement.reject(new ApiRequestError('generic denied', 403));
  await pending;
  assert.equal(s.state.withdraws, 0);
  assert.notEqual(s.draftRef.current, null);
  assert.throws(() => assert.equal(s.state.withdraws, 1));
  neighbor.resolve(before);
  await neighborPending;
});

test('F3 negative control B: a broader late-denial scope would clear replacement editor B', async () => {
  const before = detail();
  let reject;
  const s = createHarness(before, {
    lost: true,
    reads: [before, before, () => new Promise((_resolve, fail) => { reject = fail; })],
    sourceMutation: (source) => {
      const mutated = source.replace(
        /if \(!draftStillCurrent\(\)\s*\|\| mountedAtSettlementStart === null/,
        'if (false || mountedAtSettlementStart === null'
      );
      assert.notEqual(mutated, source);
      return mutated;
    }
  });
  assert.equal((await s.save(1, editedWindow(before))).status, 'unconfirmed');
  const pending = s.save(1, editedWindow(before));
  await firstTick();
  s.draftRef.current = { ...s.draftRef.current, editorGeneration: 2, itemId: 92 };
  const replacement = s.draftRef.current;
  reject(new ApiRequestError('generic denied', 403));
  await pending;
  assert.equal(s.state.withdraws, 1);
  assert.equal(s.draftRef.current, null);
  assert.throws(() => assert.equal(s.draftRef.current, replacement));
});

test('F3 negative control C: an injected forced unlock is detected by neighboring ownership', async () => {
  const before = detail();
  const settlement = { reject: null };
  const neighbor = { resolve: null };
  const s = createHarness(before, {
    lost: true,
    reads: [before, before, () => new Promise((_resolve, reject) => { settlement.reject = reject; })],
    sourceMutation: (source) => {
      const mutated = source.replace(
        '          withdrawDeniedRoot(mountedAtSettlementStart);',
        "          ownership.cancelActive('operation-superseded');\n"
          + '          withdrawDeniedRoot(mountedAtSettlementStart);'
      );
      assert.notEqual(mutated, source);
      return mutated;
    }
  });
  assert.equal((await s.save(1, editedWindow(before))).status, 'unconfirmed');
  const pending = s.save(1, editedWindow(before));
  await firstTick();
  const { pending: neighborPending, operation } = await startNeighboringCommand(s, {
    promise: new Promise((resolve) => { neighbor.resolve = resolve; })
  });
  settlement.reject(new ApiRequestError('generic denied', 404));
  await pending;
  assert.equal(s.ownership.activeOperation, null);
  assert.throws(() => assert.equal(s.ownership.activeOperation, operation));
  neighbor.resolve(before);
  await neighborPending;
});

test('F3 negative control D: treating a transient rejection as denial exposes false withdrawal', async () => {
  const before = detail();
  const s = createHarness(before, {
    lost: true,
    reads: [before, new ApiRequestError('temporary failure', 503)],
    sourceMutation: (source) => {
      const mutated = source.replace(
        "if (cause instanceof ApiRequestError && (cause.status === 403 || cause.status === 404)) {",
        'if (true) {'
      );
      assert.notEqual(mutated, source);
      return mutated;
    }
  });
  const result = await s.save(1, editedWindow(before));
  assert.equal(result.status, 'superseded');
  assert.equal(s.state.withdraws, 1);
  assert.equal(s.draftRef.current, null);
  assert.throws(() => assert.notEqual(s.draftRef.current, null));
});

test('F3-15 reverse ordering: a completed denial followed by a fresh editor leaves the new state intact', async () => {
  const before = detail();
  const s = createHarness(before, {
    lost: true,
    reads: [before, new ApiRequestError('generic denied', 404)]
  });
  assert.equal((await s.save(1, editedWindow(before))).status, 'superseded');
  assert.equal(s.draftRef.current, null);
  assert.equal(s.state.withdraws, 1);

  s.begin(91);
  const replacement = s.draftRef.current;
  assert.equal(replacement.editorGeneration, 2);
  assert.equal(replacement.status, 'editing');
  await firstTick();
  assert.equal(s.draftRef.current, replacement);
  assert.equal(s.state.withdraws, 1);
});

test('F3 negative control: restoring the catch-all null read leaves the denied draft accessible', async () => {
  const before = detail();
  const s = createHarness(before, {
    lost: true,
    reads: [before, new ApiRequestError('Access refused', 403)],
    sourceMutation: (source) => {
      const mutated = source.replace(
        "if (cause instanceof ApiRequestError && (cause.status === 403 || cause.status === 404)) {",
        "if (false) {"
      );
      assert.notEqual(mutated, source);
      return mutated;
    }
  });
  const result = await s.save(1, editedWindow(before));
  assert.equal(result.status, 'unconfirmed');
  assert.notEqual(s.draftRef.current, null);
  assert.equal(s.state.withdraws, 0);
  assert.throws(() => assert.equal(s.draftRef.current, null));
});

/* -------------------------------------------------------------------------- *
 * F4 — truthful current-state, unknown-history copy
 * -------------------------------------------------------------------------- */

test('F4-a/F4-b actual runner: matching current state with newer edits uses current-state-only copy', async () => {
  const before = detail();
  const s = createHarness(before, {
    reads: [detail({ propertyAvailabilityStartAt: '2026-10-23T12:47:00Z' }, 7)]
  });
  const original = editedWindow(before);
  s.draftRef.current = {
    ...s.draftRef.current,
    window: { ...original, availabilityEndAt: '2026-10-31T12:47' },
    fieldProvenance: { start: 'EDITED', end: 'EDITED' },
    attempt: attempt(original),
    status: 'uncertain'
  };
  const newer = structuredClone(s.draftRef.current);
  const result = await s.save(1, {
    ...original,
    availabilityEndAt: '2026-10-31T12:47:00+05:30'
  });
  assert.equal(result.status, 'not-saved');
  assert.doesNotMatch(result.message, /earlier save now appears/i);
  assert.match(result.message, /server currently shows/i);
  assert.match(result.message, /outcome remains unconfirmed/i);
  assert.match(result.message, /nothing new was sent/i);
  assert.equal(s.draftRef.current.failureMessage, result.message);
  assert.deepEqual(s.draftRef.current.window, newer.window);
  assert.equal(s.state.puts.length, 0);
});

test('F4-c actual runner: post-dispatch lifecycle refusal acknowledges the earlier request', async () => {
  const before = detail();
  const scheduled = { ...before, status: 'SCHEDULED', version: 7 };
  const s = createHarness(before, { lost: true, reads: [before, scheduled] });
  const result = await s.save(1, editedWindow(before));
  assert.equal(result.status, 'superseded');
  assert.doesNotMatch(result.message, /Nothing was sent$/);
  assert.match(result.message, /now SCHEDULED/);
  assert.match(result.message, /earlier request’s outcome remains unconfirmed/i);
  assert.match(result.message, /nothing new was sent/i);
  assert.equal(s.state.notices.at(-1)[0], result.message);
  assert.equal(s.draftRef.current, null);
  assert.equal(s.state.puts.length, 1);
});

test('F4-c actual runner: removed item after dispatch uses the same unknown-history distinction', async () => {
  const before = detail();
  const removed = { ...before, items: [], version: 7 };
  const s = createHarness(before, { lost: true, reads: [before, removed] });
  const result = await s.save(1, editedWindow(before));
  assert.equal(result.status, 'superseded');
  assert.match(result.message, /no longer editable/i);
  assert.match(result.message, /earlier request’s outcome remains unconfirmed/i);
  assert.doesNotMatch(result.message, /Nothing was sent/i);
});

test('F4-d actual runner: a pre-dispatch lifecycle refusal still says no request was sent', async () => {
  const before = detail();
  const scheduled = { ...before, status: 'SCHEDULED', version: 7 };
  const s = createHarness(before, { reads: [scheduled] });
  const result = await s.save(1, editedWindow(before));
  assert.equal(result.status, 'not-saved');
  assert.equal(s.state.puts.length, 0);
  assert.match(result.message, /Nothing was sent/);
  assert.doesNotMatch(result.message, /earlier request’s outcome/i);
});

test('F4-f actual runner: confirmed success plus refresh failure remains success and separate notice', async () => {
  const before = detail();
  const s = createHarness(before, { refreshOutcome: 'failed' });
  const result = await s.save(1, windowOf(before));
  assert.equal(result.status, 'saved');
  assert.equal(s.state.successes.length, 1);
  assert.equal(s.state.notices.length, 1);
  assert.match(s.state.notices[0][0], /updated on the server/);
  assert.match(s.state.notices[0][0], /could not be refreshed/);
});

test('F4 negative control: restoring historical attribution is detected by the current-state assertion', async () => {
  const before = detail();
  const s = createHarness(before, {
    reads: [detail({ propertyAvailabilityStartAt: '2026-10-23T12:47:00Z' }, 7)],
    sourceMutation: (source) => {
      const mutated = source.replace(
        'The server currently shows the availability from your earlier attempt. ',
        'The earlier save now appears on the server. '
      );
      assert.notEqual(mutated, source);
      return mutated;
    }
  });
  const original = editedWindow(before);
  s.draftRef.current = {
    ...s.draftRef.current,
    window: { ...original, availabilityEndAt: '2026-10-31T12:47' },
    fieldProvenance: { start: 'EDITED', end: 'EDITED' },
    attempt: attempt(original),
    status: 'uncertain'
  };
  const result = await s.save(1, {
    ...original,
    availabilityEndAt: '2026-10-31T12:47:00+05:30'
  });
  assert.match(result.message, /earlier save now appears/i);
  assert.throws(() => assert.doesNotMatch(result.message, /earlier save now appears/i));
});

test('F4 negative control: restoring “Nothing was sent” after dispatch is detected', async () => {
  const before = detail();
  const s = createHarness(before, {
    lost: true,
    reads: [before, { ...before, status: 'SCHEDULED', version: 7 }],
    sourceMutation: (source) => {
      const mutated = source.replace(
        'The earlier request’s outcome remains unconfirmed; nothing new was sent.',
        'Nothing was sent, and the current session state is shown.'
      );
      assert.notEqual(mutated, source);
      return mutated;
    }
  });
  const result = await s.save(1, editedWindow(before));
  assert.match(result.message, /Nothing was sent/);
  assert.throws(() => assert.doesNotMatch(result.message, /Nothing was sent/));
});

/* -------------------------------------------------------------------------- *
 * Shared identity/authority and precision controls
 * -------------------------------------------------------------------------- */

test('G1 actual runner: logout during a held preflight transmits nothing and restores no draft', async () => {
  const before = detail();
  let release;
  const s = createHarness(before, {
    reads: [() => new Promise((resolve) => { release = resolve; })]
  });
  const pending = s.save(1, windowOf(before));
  await firstTick();
  h.signOut();
  s.state.active = false;
  s.draftRef.current = null;
  release(before);
  assert.equal((await pending).status, 'superseded');
  assert.equal(s.state.puts.length, 0);
  assert.equal(s.draftRef.current, null);
});

test('G2 actual runner: a malformed version command stops before transmission', async () => {
  const before = detail();
  const malformed = { ...before, version: '6' };
  const s = createHarness(before, { reads: [malformed] });
  const result = await s.save(1, editedWindow(before));
  assert.equal(result.status, 'not-saved');
  assert.equal(s.state.puts.length, 0);
  assert.match(result.message, /version needed/);
});

test('G3/G4 actual runner: precision, provenance and failed-save input remain unchanged', async () => {
  const before = detail();
  const s = createHarness(before, { lost: true, reads: [before, before] });
  const draft = p.createAvailabilityDraft({
    sessionId: 3108,
    sessionVersion: before.version,
    item: before.items[0],
    identity: s.draftRef.current.identity,
    authorizationGeneration: 1,
    editorGeneration: 1
  });
  s.draftRef.current = {
    ...draft,
    window: { ...editedWindow(before), availabilityStartAt: '2026-10-23T18:17' },
    fieldProvenance: { start: 'EDITED', end: 'RECORDED' }
  };
  const result = await s.save(1, editedWindow(before));
  assert.equal(result.status, 'unconfirmed');
  assert.equal(s.draftRef.current.window.availabilityStartAt, '2026-10-23T18:17');
  assert.deepEqual(s.draftRef.current.fieldProvenance, { start: 'EDITED', end: 'RECORDED' });
  assert.equal(s.draftRef.current.openingStartAt, before.items[0].availabilityStartAt);
  assert.equal(s.state.puts.length, 1);
});
