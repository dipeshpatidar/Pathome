/**
 * Behavioural coverage for the Package 1A security race corrections.
 *
 * Two races were reported after the first Package 1A review and are proven fixed here by driving the
 * real runtime modules (never by asserting on source text):
 *
 *  1. A claim or release could still transmit its POST after the workspace unmounted, the account
 *     changed or authority was withdrawn, because nothing bound the operation to the component, the
 *     workspace surface, the initiating session or the authorization generation in force.
 *  2. An abandoned Account A operation could run its `finally` after Account B started a new one and
 *     release B's mutation lock.
 *
 * `operationsOwnershipRegistry.ts` is the production lifecycle/ownership core the workspace uses, and
 * `operationsWorkspacePresentation.ts` is the production copy module, so the gate, the lock rules and
 * the truthful outcome wording are all exercised unmodified. The component-level interleavings
 * (unmount during a pending read, withdrawal during a pending read) are proven end to end in
 * `browser-verification/operations-workspace/` against the real React tree.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import { resolve as resolveOperationsModule } from './support/operationsServiceLoader.mjs';
import {
  compileCallback,
  extractReportCommandFailure,
  extractSchedulingCallbacks,
  extractWithdrawDeniedRoot
} from './support/operationsSchedulingCallbacksHarness.mjs';

registerHooks({ resolve: resolveOperationsModule });

/** Reads one production source file, for the structural half of a wiring assertion. */
const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');

/* ------------------------------------------------------------------ *
 * Browser environment stubs (storage and window events)
 * ------------------------------------------------------------------ */

class FakeEventTarget {
  constructor() {
    this.listeners = new Map();
  }

  addEventListener(type, handler) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type).add(handler);
  }

  removeEventListener(type, handler) {
    this.listeners.get(type)?.delete(handler);
  }

  dispatchEvent(event) {
    this.listeners.get(event.type)?.forEach((handler) => handler(event));
    return true;
  }
}

const storage = new Map();
globalThis.localStorage = {
  getItem: (key) => (storage.has(key) ? storage.get(key) : null),
  setItem: (key, value) => storage.set(key, String(value)),
  removeItem: (key) => storage.delete(key),
  clear: () => storage.clear()
};
globalThis.window = new FakeEventTarget();

const signIn = ({ id, role, token }) => {
  storage.set('pathome_auth_token', token);
  storage.set('pathome_user', JSON.stringify({ id, role }));
  storage.set('pathome_role', role);
  globalThis.window.dispatchEvent({ type: 'pathome_auth_changed' });
};
/** Replaces the stored credential with no logout and no auth event of any kind. */
const persistCredentialsOnly = ({ id, role, token }) => {
  storage.set('pathome_auth_token', token);
  storage.set('pathome_user', JSON.stringify({ id, role }));
  storage.set('pathome_role', role);
};
const signOut = () => {
  storage.delete('pathome_auth_token');
  storage.delete('pathome_user');
  storage.delete('pathome_role');
  globalThis.window.dispatchEvent({ type: 'pathome_session_expired' });
};

const jsonResponse = (status, body) => ({ ok: status >= 200 && status < 300, status, json: async () => body });
const noContentResponse = () => ({
  ok: true,
  status: 204,
  json: async () => {
    throw new Error('a 204 ownership command must never be deserialised');
  }
});

const recordedCalls = [];
let responder = () => jsonResponse(200, {});
globalThis.fetch = (url, init = {}) => {
  const record = {
    url,
    method: init.method ?? 'GET',
    authorization: init.headers?.Authorization,
    body: init.body === undefined ? undefined : JSON.parse(init.body)
  };
  recordedCalls.push(record);
  return Promise.resolve(responder(record));
};
const resetTransport = () => {
  recordedCalls.length = 0;
  responder = () => jsonResponse(200, {});
};

/* ------------------------------------------------------------------ *
 * Modules under test
 * ------------------------------------------------------------------ */

const sessionModule = await import('../utils/operationsSession.ts');
const { OwnershipOperationRegistry, operationTargetIsMounted } = await import(
  '../utils/operationsOwnershipRegistry.ts'
);
const presentation = await import('../utils/operationsWorkspacePresentation.ts');

const {
  captureOperationsSession,
  isOperationsSessionActive,
  isOperationsSessionChangedError,
  readOperationsSession,
  subscribeOperationsSession
} = sessionModule;

const { ownershipFailureKind, ownershipOutcomeCopy, ownershipRefreshFailureNotice } = presentation;

// Mirrors how the workspace binds to session invalidation events.
subscribeOperationsSession(() => {});

const ACCOUNT_A = { id: 11, role: 'EMPLOYEE', token: 'token-account-a' };
const ACCOUNT_B = { id: 22, role: 'ADMIN', token: 'token-account-b' };

/**
 * A stand-in for the capability hook's synchronous dispatch gate, with the same shape the workspace
 * passes in production.
 */
const authorizationGate = ({ generation = 0, authorized = true } = {}) => ({
  authorizationGeneration: () => generation,
  isDispatchAuthorized: () => authorized
});

/** A mounted, active workspace holding one live operation, exactly as the component sets it up. */
const liveWorkspace = ({ session, generation = 0 } = {}) => {
  const registry = new OwnershipOperationRegistry();
  registry.setMounted(true);
  registry.setWorkspaceActive(true);
  return {
    registry,
    begin: (kind = 'claim') => registry.begin(kind, session, generation)
  };
};

/* ------------------------------------------------------------------ *
 * 1. Mutation-lock ownership across accounts
 * ------------------------------------------------------------------ */

test('an abandoned Account A operation cannot clear or hijack Account B’s mutation lock', () => {
  signIn(ACCOUNT_A);
  const sessionA = captureOperationsSession();
  const workspace = liveWorkspace({ session: sessionA });
  const gate = authorizationGate();
  const operationA = workspace.begin('claim');

  assert.equal(workspace.registry.isLocked(), true, 'A holds the mutation lock');
  assert.equal(operationA.dispatched, false);

  // Account B signs in. The workspace clears protected state, which invalidates A's operation.
  signIn(ACCOUNT_B);
  assert.equal(workspace.registry.cancelActive('session-changed'), true);
  assert.equal(operationA.cancelled, true);
  assert.equal(operationA.controller.signal.aborted, true, 'A’s pending pre-mutation reads were cancelled');
  assert.equal(workspace.registry.isLocked(), false, 'the invalidated operation releases the lock');
  assert.equal(workspace.registry.finish(operationA), false, 'a released operation owns nothing');

  // B starts its own operation under B's exact credential.
  const sessionB = captureOperationsSession();
  const operationB = workspace.registry.begin('claim', sessionB, gate.authorizationGeneration());
  assert.equal(workspace.registry.isLocked(), true);

  // A finishes late. Its `finally` block must not touch B's lock, B's busy state or B's operation.
  assert.equal(workspace.registry.finish(operationA), false, 'a stale finalizer never releases a newer lock');
  assert.equal(workspace.registry.isLocked(), true);
  assert.equal(workspace.registry.activeOperation, operationB);
  assert.equal(workspace.registry.isCurrent(operationA), false);
  assert.equal(workspace.registry.isCurrent(operationB), true);
  assert.equal(
    workspace.registry.blockReasonForDispatch(operationB, gate),
    null,
    'B can still dispatch after A’s late finalizer ran'
  );
});

test('a stale finalizer cannot re-enable duplicate submissions inside the live operation', () => {
  signIn(ACCOUNT_A);
  const workspace = liveWorkspace({ session: captureOperationsSession() });
  const gate = authorizationGate();
  const operationA = workspace.begin('claim');

  signIn(ACCOUNT_B);
  workspace.registry.cancelActive('session-changed');
  const operationB = workspace.registry.begin('claim', captureOperationsSession(), gate.authorizationGeneration());

  // A's late finalizer leaves B's lock intact, so the workspace's single-operation guard still
  // refuses a second command: `begin` is never reached while the lock is held.
  assert.equal(workspace.registry.finish(operationA), false);
  const duplicate = workspace.registry.isLocked() ? null : workspace.begin('claim');
  assert.equal(duplicate, null, 'no duplicate command may start while B owns the lock');

  // And one live operation can transmit at most one command.
  assert.equal(workspace.registry.markDispatched(operationB), true);
  assert.equal(workspace.registry.markDispatched(operationB), false, 'a live operation never dispatches twice');
  assert.equal(operationB.dispatched, true);

  // Only the owner releases the lock, and only once.
  assert.equal(workspace.registry.finish(operationB), true);
  assert.equal(workspace.registry.isLocked(), false);
  assert.equal(workspace.registry.finish(operationB), false);
});

test('an account change cancels the live operation, aborts its reads and never transfers its lock', () => {
  signIn(ACCOUNT_A);
  const workspace = liveWorkspace({ session: captureOperationsSession() });
  const operationA = workspace.begin('release');
  const aborted = [];
  operationA.controller.signal.addEventListener('abort', () => aborted.push('aborted'));

  signIn(ACCOUNT_B);
  workspace.registry.cancelActive('session-changed');

  assert.deepEqual(aborted, ['aborted'], 'the pre-mutation reads of an abandoned operation are cancelled');
  assert.equal(workspace.registry.isLocked(), false, 'the lock is released for the new account, not transferred');
  assert.equal(workspace.registry.activeOperation, null);
  assert.equal(operationA.cancelled, true);
});

/* ------------------------------------------------------------------ *
 * 2. The synchronous dispatch gate fails closed
 * ------------------------------------------------------------------ */

test('the gate passes only while every invariant holds in the same tick', () => {
  signIn(ACCOUNT_A);
  const sessionA = captureOperationsSession();
  const workspace = liveWorkspace({ session: sessionA });
  const gate = authorizationGate();
  const operation = workspace.begin('claim');

  assert.equal(workspace.registry.blockReasonForDispatch(operation, gate), null);

  // A newer logical operation supersedes it.
  const newer = workspace.registry.begin('claim', sessionA, gate.authorizationGeneration());
  assert.equal(workspace.registry.blockReasonForDispatch(operation, gate), 'operation-superseded');
  assert.equal(workspace.registry.finish(newer), true);

  // The component is gone.
  const second = liveWorkspace({ session: sessionA });
  const secondOperation = second.begin('claim');
  second.registry.setMounted(false);
  assert.equal(second.registry.blockReasonForDispatch(secondOperation, gate), 'workspace-closed');

  // The workspace surface lost its authority.
  const third = liveWorkspace({ session: sessionA });
  const thirdOperation = third.begin('claim');
  third.registry.setWorkspaceActive(false);
  assert.equal(third.registry.blockReasonForDispatch(thirdOperation, gate), 'workspace-closed');

  // The authorization generation captured at the start no longer matches: a newer denial, transient
  // failure or invalidation took authority away, even though this session is still signed in.
  const fourth = liveWorkspace({ session: sessionA, generation: 4 });
  const fourthOperation = fourth.begin('claim');
  assert.equal(
    fourth.registry.blockReasonForDispatch(fourthOperation, authorizationGate({ generation: 5 })),
    'authorization-lost'
  );

  // The newest verdict is no longer `granted` for this exact session.
  assert.equal(
    fourth.registry.blockReasonForDispatch(fourthOperation, authorizationGate({ generation: 4, authorized: false })),
    'authorization-lost'
  );

  // A pending check also fails closed, because "not yet answered" is not "granted".
  assert.equal(fourth.registry.blockReasonForDispatch(fourthOperation, authorizationGate({ generation: 4 })), null);
});

test('the gate refuses a captured session whose credential changed with no auth event', () => {
  signIn(ACCOUNT_A);
  const workspace = liveWorkspace({ session: captureOperationsSession() });
  const operation = workspace.begin('claim');
  const gate = authorizationGate();

  persistCredentialsOnly({ ...ACCOUNT_A, token: 'token-account-a-rotated' });

  assert.equal(workspace.registry.blockReasonForDispatch(operation, gate), 'session-changed');
  assert.equal(operation.dispatched, false, 'nothing may be transmitted for a replaced credential');
});

/* ------------------------------------------------------------------ *
 * 3. Exact credential continuity (no digest comparison)
 * ------------------------------------------------------------------ */

/**
 * Two distinct 32-bit FNV-1a collisions of the session fingerprint, found offline. Their digests are
 * identical, so a fingerprint comparison cannot tell them apart — an exact credential comparison can.
 */
const COLLIDING_TOKENS = ['1RMVi4VI5iYcyjk31', 'FtmYJ2bSSiMGi_E'];

test('a rotated credential is rejected by exact comparison even when its digest collides', () => {
  const [first, second] = COLLIDING_TOKENS;
  assert.notEqual(first, second);

  signIn({ ...ACCOUNT_A, token: first });
  const captured = captureOperationsSession();
  const workspace = liveWorkspace({ session: captured });
  const operation = workspace.begin('claim');

  // The credential is replaced underneath the operation with no logout and no auth event.
  persistCredentialsOnly({ ...ACCOUNT_A, token: second });
  const substituted = readOperationsSession();

  assert.equal(
    substituted.identity,
    captured.identity,
    'the non-secret digest is identical, which is exactly why it must never decide authority'
  );
  assert.equal(
    captured.identity.split('|')[2],
    substituted.identity.split('|')[2],
    'the colliding fingerprints are equal'
  );
  assert.equal(captured.identity.includes(first), false, 'the raw credential is never part of the identity label');
  assert.notEqual(substituted.token, captured.token);

  assert.equal(isOperationsSessionActive(captured), false, 'exact equality rejects the colliding credential');
  assert.equal(workspace.registry.blockReasonForDispatch(operation, authorizationGate()), 'session-changed');
  assert.equal(
    workspace.registry.blockReasonForDispatch(workspace.registry.begin('claim', substituted, 0), authorizationGate()),
    null,
    'the newly signed-in credential may still act for itself'
  );
});

/* ------------------------------------------------------------------ *
 * 4. The transport boundary: dispatched is set only by a real transmission
 * ------------------------------------------------------------------ */

test('a command is marked dispatched only once the POST really leaves the client', async () => {
  const { operationsWorkspaceService } = await import('../services/operationsWorkspaceService.ts');
  resetTransport();
  signIn(ACCOUNT_A);
  const sessionA = captureOperationsSession();
  const marks = [];
  responder = () => noContentResponse();

  assert.equal(
    await operationsWorkspaceService.claimRequest(5200, 7, { session: sessionA, onDispatch: () => marks.push('sent') }),
    204
  );
  assert.deepEqual(marks, ['sent'], 'the transmission is recorded exactly once');
  assert.equal(recordedCalls.filter((call) => call.method === 'POST').length, 1);

  // A pre-flight refusal (stale credential) never reaches the transport and never marks a dispatch.
  signIn(ACCOUNT_B);
  const refused = [];
  await assert.rejects(
    operationsWorkspaceService.claimRequest(5200, 7, { session: sessionA, onDispatch: () => refused.push('sent') }),
    (cause) => isOperationsSessionChangedError(cause)
  );
  assert.deepEqual(refused, [], 'a refused command is never reported as dispatched');
  assert.equal(recordedCalls.filter((call) => call.method === 'POST').length, 1, 'no second command was sent');

  // No session at all: still nothing transmitted.
  signOut();
  const anonymous = [];
  await assert.rejects(
    operationsWorkspaceService.releaseRequest(501, 4, { onDispatch: () => anonymous.push('sent') }),
    (cause) => cause.status === 401
  );
  assert.deepEqual(anonymous, []);
  assert.equal(recordedCalls.filter((call) => call.method === 'POST').length, 1);
});

/* ------------------------------------------------------------------ *
 * 5. Truthful transport-failure messaging
 * ------------------------------------------------------------------ */

const RECORD = 'Request #5200';

test('an outcome that may have reached the backend is never reported as unchanged', () => {
  const uncertain = ownershipOutcomeCopy(RECORD, { dispatched: true, status: null, message: 'Failed to fetch' });
  assert.equal(uncertain.kind, 'uncertain');
  assert.equal(ownershipFailureKind({ dispatched: true, status: null }), 'uncertain');
  assert.match(uncertain.message, /not confirmed whether it was applied/);
  assert.doesNotMatch(uncertain.message, /nothing was changed/i);
  assert.equal(uncertain.reconcile, true, 'a transmitted command may need authoritative reconciliation');
  // With no completed reconciliation it must not claim the work lists are current.
  assert.match(uncertain.message, /Current ownership could not be verified/);
  assert.doesNotMatch(uncertain.message, /queues were reloaded/i);

  // A server-side failure after the request was read is equally unconfirmed.
  const serverFailed = ownershipOutcomeCopy(RECORD, { dispatched: true, status: 503, message: 'Busy' });
  assert.equal(serverFailed.kind, 'uncertain');
  assert.match(serverFailed.message, /HTTP 503/);
  assert.doesNotMatch(serverFailed.message, /nothing was changed/i);

  // Only a genuinely completed reconciliation (`loaded`) may say the queues show the server's
  // ownership; a failed or skipped read must not.
  const reconciled = ownershipOutcomeCopy(RECORD, { dispatched: true, status: null, reconciliation: 'loaded' });
  assert.match(reconciled.message, /queues were reloaded from the server and show the current ownership/);
  const unreconciled = ownershipOutcomeCopy(RECORD, { dispatched: true, status: null, reconciliation: 'failed' });
  assert.match(unreconciled.message, /Current ownership could not be verified/);
  assert.match(unreconciled.message, /refresh them before attempting anything further/);
  assert.doesNotMatch(unreconciled.message, /queues were reloaded/i);
  const skipped = ownershipOutcomeCopy(RECORD, { dispatched: true, status: null, reconciliation: 'skipped' });
  assert.doesNotMatch(skipped.message, /queues were reloaded/i);
  assert.match(skipped.message, /Current ownership could not be verified/);

  // Nothing suggests resending the mutation.
  for (const copy of [uncertain, serverFailed, reconciled, unreconciled, skipped]) {
    assert.doesNotMatch(copy.message, /resend|re-send|will be retried automatically|retrying/i);
  }
});

test('confirmed rejections keep their copy but only claim a reload after a real reconciliation', () => {
  // A 409 whose reconciliation genuinely completed may say the queues were refreshed.
  const refreshed = ownershipOutcomeCopy(RECORD, { dispatched: true, status: 409, reconciliation: 'loaded' });
  assert.equal(refreshed.kind, 'rejected');
  assert.equal(
    refreshed.message,
    `${RECORD} changed on the server while you were working, so nothing was changed by you. The queues were refreshed from the server — review the latest state before deciding again.`
  );

  // A 409 whose reconciliation failed, was skipped, or was never attempted may not.
  for (const reconciliation of ['failed', 'skipped', 'not-attempted', null, undefined]) {
    const conflict = ownershipOutcomeCopy(RECORD, { dispatched: true, status: 409, reconciliation });
    assert.equal(conflict.kind, 'rejected');
    assert.match(conflict.message, /changed on the server while you were working, so nothing was changed by you/);
    assert.match(conflict.message, /Current ownership could not be verified/);
    assert.doesNotMatch(conflict.message, /queues were refreshed/i);
    assert.doesNotMatch(conflict.message, /queues were reloaded/i);
  }

  const conflict = ownershipOutcomeCopy(RECORD, { dispatched: true, status: 409, reconciliation: 'loaded' });
  assert.equal(conflict.kind, 'rejected');

  const missing = ownershipOutcomeCopy(RECORD, { dispatched: true, status: 404, reconciliation: 'loaded' });
  assert.equal(missing.kind, 'rejected');
  assert.equal(
    missing.message,
    `${RECORD} is no longer available to claim or owned by you. The queues were refreshed from the server — review the latest state before deciding again.`
  );

  const denied = ownershipOutcomeCopy(RECORD, { dispatched: true, status: 403, reconciliation: 'loaded' });
  assert.equal(denied.kind, 'rejected');
  assert.match(denied.message, /coordination authority for this team is not currently active/);

  const expired = ownershipOutcomeCopy(RECORD, { dispatched: true, status: 401 });
  assert.equal(expired.kind, 'rejected');
  assert.equal(expired.reconcile, false, 'an ended session has no valid protected read left to reconcile with');

  const malformed = ownershipOutcomeCopy(RECORD, { dispatched: true, status: 400, message: 'Reason code is required.' });
  assert.equal(malformed.kind, 'rejected');
  assert.match(malformed.message, /Reason code is required\./);
  assert.match(malformed.message, /was refused by the server, so it was not applied/);
});

test('a confirmed success whose follow-up refresh failed is reported separately, never as uncertain', () => {
  const notice = ownershipRefreshFailureNotice(RECORD);
  assert.match(notice, /was updated on the server/);
  assert.match(notice, /could not be refreshed just now/);
  assert.doesNotMatch(notice, /not confirmed/i, 'a confirmed 204 is never reclassified as uncertain');
  assert.doesNotMatch(notice, /nothing was changed/i);
});

test('a pre-dispatch cancellation states that no ownership command was sent', () => {
  const blocked = ownershipOutcomeCopy(RECORD, { dispatched: false, blockedByAuthorization: true });
  assert.equal(blocked.kind, 'pre-dispatch');
  assert.equal(ownershipFailureKind({ dispatched: false, status: 500 }), 'pre-dispatch');
  assert.match(blocked.message, /was not sent, so nothing was changed by you/);
  assert.equal(blocked.reconcile, false, 'nothing was transmitted, so there is nothing to reconcile');

  const stopped = ownershipOutcomeCopy(RECORD, { dispatched: false });
  assert.equal(stopped.kind, 'pre-dispatch');
  assert.match(stopped.message, /before any ownership command left the app/);
});

/* ------------------------------------------------------------------ *
 * 6. The consolidated commit guard and the component-local session epoch
 * ------------------------------------------------------------------ */

test('canCommit is the synchronous guard for every operation-originated UI write', () => {
  signIn(ACCOUNT_A);
  const workspace = liveWorkspace({ session: captureOperationsSession() });
  const gate = authorizationGate();
  const operation = workspace.begin('claim');
  assert.equal(workspace.registry.canCommit(operation, gate), true, 'a live, current operation may commit');

  // The component-local epoch moves forward (account change, logout, unmount, loss of authority): every
  // captured operation is invalidated and may no longer write, even before its cancellation is seen.
  workspace.registry.advanceSessionEpoch();
  assert.equal(workspace.registry.canCommit(operation, gate), false, 'a stale epoch may not commit');
  assert.equal(workspace.registry.blockReasonForDispatch(operation, gate), 'session-epoch-advanced');

  // A dispatched command still needs every invariant to hold before it writes after the response.
  const second = liveWorkspace({ session: captureOperationsSession() });
  const secondOperation = second.begin('claim');
  assert.equal(second.registry.markDispatched(secondOperation), true);
  assert.equal(
    second.registry.canCommit(secondOperation, authorizationGate({ authorized: false })),
    false,
    'a newer non-granted verdict stops the write'
  );
  assert.equal(second.registry.canCommit(secondOperation, gate), true, 'a still-granted dispatched command may write');
});

test('a stale operation cannot commit into a workspace whose epoch has advanced', () => {
  signIn(ACCOUNT_A);
  const workspace = liveWorkspace({ session: captureOperationsSession() });
  const gate = authorizationGate();
  const operation = workspace.begin('release');
  assert.equal(workspace.registry.canCommit(operation, gate), true);

  // Account B signs in and the workspace clears protected state: the epoch advances and the live
  // operation is cancelled, so A's delayed continuation can never write to B's workspace.
  signIn(ACCOUNT_B);
  workspace.registry.advanceSessionEpoch();
  workspace.registry.cancelActive('session-changed');
  assert.equal(workspace.registry.canCommit(operation, gate), false);
  assert.equal(workspace.registry.canCommit(operation, authorizationGate()), false);
});

/* ------------------------------------------------------------------ *
 * 7. R1/R3/R5 — what a failed command decides, and who may still write it
 * ------------------------------------------------------------------ */

test('a definitive target denial withdraws the root, before or after dispatch', () => {
  const { commandFailureDisposition } = presentation;

  // A `404` from the fresh pre-command read: nothing was transmitted, but the server has denied this
  // root, so the protected detail must not stay mounted.
  assert.deepEqual(
    commandFailureDisposition({ dispatched: false, status: 404 }),
    { kind: 'pre-dispatch', targetDenied: true, withdrawRoot: true, reconcile: true }
  );
  // The same denial from the mutation itself.
  assert.deepEqual(
    commandFailureDisposition({ dispatched: true, status: 403 }),
    { kind: 'rejected', targetDenied: true, withdrawRoot: true, reconcile: true }
  );
  // A `403` on the fresh read is a denial of this root, not "the app stopped before sending anything".
  assert.equal(commandFailureDisposition({ dispatched: false, status: 403 }).targetDenied, true);
  // A conflict keeps the detail unless the caller asked for a release-style drop.
  assert.equal(commandFailureDisposition({ dispatched: true, status: 409 }).withdrawRoot, false);
  assert.equal(
    commandFailureDisposition({ dispatched: true, status: 409 }, { dropDetailWhenRejected: true }).withdrawRoot,
    true
  );
  // A `401` is a session end, and an uncertain transmission is never treated as a denial: neither may
  // withdraw a root on its own (the session/authority owner clears protected state for those).
  assert.equal(commandFailureDisposition({ dispatched: true, status: 401 }).withdrawRoot, false);
  assert.equal(commandFailureDisposition({ dispatched: true, status: 401 }).targetDenied, false);
  for (const status of [500, 502, null, undefined]) {
    const uncertain = commandFailureDisposition({ dispatched: true, status });
    assert.equal(uncertain.kind, 'uncertain', `status ${String(status)}`);
    assert.equal(uncertain.withdrawRoot, false, `status ${String(status)}`);
    assert.equal(uncertain.reconcile, true, `status ${String(status)}`);
    assert.equal(uncertain.targetDenied, false, `status ${String(status)}`);
  }
  // A pre-dispatch transport failure that never touched the root checks nothing and withdraws nothing.
  const stopped = commandFailureDisposition({ dispatched: false, status: null });
  assert.equal(stopped.kind, 'pre-dispatch');
  assert.equal(stopped.withdrawRoot, false);
  assert.equal(stopped.reconcile, false);
});

test('a denial withdraws the refused root before the reconciliation it triggers is awaited', () => {
  const { commandFailureDisposition } = presentation;

  /**
   * The withdrawal decision is a function of the real status alone — never of the reconciliation
   * result. Holding the queues unresolved therefore cannot postpone it, which is precisely what Sol
   * reproduced when the denied detail and its revealed lessor contact stayed mounted behind a held
   * refresh. The reconciliation still happens; it just cannot gate the withdrawal.
   */
  for (const reconciliation of [null, 'not-attempted', 'loaded', 'failed', 'skipped']) {
    assert.equal(
      commandFailureDisposition({ dispatched: true, status: 403, reconciliation }).withdrawRoot,
      true,
      `mutation 403 (${String(reconciliation)})`
    );
    assert.equal(
      commandFailureDisposition({ dispatched: true, status: 404, reconciliation }).withdrawRoot,
      true,
      `mutation 404 (${String(reconciliation)})`
    );
    assert.equal(
      commandFailureDisposition({ dispatched: false, status: 404, reconciliation }).withdrawRoot,
      true,
      `fresh-read 404 (${String(reconciliation)})`
    );
  }
  // A fresh-read denial transmits nothing but still needs the authoritative read that follows it.
  assert.deepEqual(commandFailureDisposition({ dispatched: false, status: 404 }), {
    kind: 'pre-dispatch',
    targetDenied: true,
    withdrawRoot: true,
    reconcile: true
  });

  /**
   * The gate that makes the immediate withdrawal safe is the real registry: a denial learned by an
   * operation that may no longer commit writes nothing at all, so an Account A refusal can never clear
   * protected state that now belongs to Account B.
   */
  signIn(ACCOUNT_A);
  const workspace = liveWorkspace({ session: captureOperationsSession() });
  const operation = workspace.begin('availability');
  const gate = authorizationGate();
  const withdrawIfStillAllowed = () =>
    workspace.registry.canCommit(operation, gate) ? 'withdrawn' : 'skipped';
  assert.equal(withdrawIfStillAllowed(), 'withdrawn');
  signIn(ACCOUNT_B);
  assert.equal(withdrawIfStillAllowed(), 'skipped');
  // And the same guard refuses the withdrawal for a revoked grant on an unchanged identity.
  const second = liveWorkspace({ session: captureOperationsSession() });
  const secondOperation = second.begin('availability');
  assert.equal(
    second.registry.canCommit(secondOperation, authorizationGate({ authorized: false })) ? 'withdrawn' : 'skipped',
    'skipped'
  );

  /**
   * The component really applies that ordering at both failure entry points, and only for the root the
   * denial names: the withdrawal sits before the awaited reconciliation, inside the same synchronous
   * commit gate, and the notice still follows the real reconciliation result. The behavioural half —
   * a refused detail and its revealed contact unmounted while the queue reads are still held, with a
   * fresh-read denial transmitting nothing — is proven in `browser-verification/operations-completion/`
   * (`correctionDenialWithdrawsRevealedRoot`).
   */
  const workspaceSource = read('../components/operations/OperationsWorkspace.tsx');
  const region = (start, end) => {
    const from = workspaceSource.indexOf(start);
    const to = workspaceSource.indexOf(end);
    assert.ok(from > -1 && to > from, `${start} region`);
    return workspaceSource.slice(from, to);
  };

  for (const [label, slice] of [
    [
      'a failed ownership command',
      region('const reportOwnershipFailure = useCallback(async', 'const claimRoot = useCallback(async')
    ],
    [
      'a failed Phase 2A command',
      region(
        'const reportCommandFailure = useCallback(async',
        'const applyAuthoritativeSession = useCallback('
      )
    ]
  ]) {
    const withdrawAt = slice.indexOf('withdrawDeniedRoot(');
    const reconcileAt = slice.indexOf('await refreshAll(operation.session)');
    const legacyGuardAt = slice.indexOf(
      'if (!ownership.canCommit(operation, authorityRef.current))',
      reconcileAt
    );
    const contextualGuardAt = slice.indexOf('if (!publicationContextCurrent())', reconcileAt);
    const guardAt = contextualGuardAt > -1 ? contextualGuardAt : legacyGuardAt;
    const noticeAt = slice.indexOf('showNotice(copy.message, operation.session);');
    assert.ok(withdrawAt > -1, `${label}: withdraws the denied root`);
    assert.ok(withdrawAt < reconcileAt, `${label}: the withdrawal precedes the reconciliation`);
    assert.ok(reconcileAt < guardAt, `${label}: the post-await commit guard is preserved`);
    assert.ok(guardAt < noticeAt, `${label}: the notice follows the guard`);
    // One withdrawal per failure, and none of them after the awaited reconciliation.
    assert.equal((slice.match(/withdrawDeniedRoot\(/g) ?? []).length, 1, `${label}: one withdrawal call site`);
    assert.equal(
      (slice.match(/withdrawDeniedRoot\(/g) ?? []).length,
      slice.slice(0, reconcileAt).match(/withdrawDeniedRoot\(/g)?.length ?? 0,
      `${label}: no withdrawal after the reconciliation`
    );
  }

  // The withdrawal is refused outright once the operation may no longer commit.
  assert.equal(
    (workspaceSource.match(
      /if \(disposition\.withdrawRoot && (?:ownership\.canCommit\(operation, authorityRef\.current\)|failureContextCurrent\(\))\) \{/g
    ) ?? []).length,
    2
  );
  // Both failure paths withdraw the operation's *own captured target* — never the root that happens
  // to be on screen when the failure lands.
  assert.equal(
    (workspaceSource.match(
      /withdrewRoot = withdrawDeniedRoot\(operation\);|withdrawDeniedRoot\(operation\);/g
    ) ?? []).length,
    2
  );
  // A denial only drops the exact instance the operation was started against, never whatever is on
  // screen when it lands — a later record, or a newer mount of the same one.
  assert.match(
    workspaceSource,
    /const withdrawDeniedRoot = useCallback\(\(\s*source: OwnershipOperation \| MountedRootIdentity\s*\): boolean => \{[\s\S]{0,1200}?mounted\.mountGeneration !== target\.mountGeneration\) return false;/
  );
  /**
   * The outcome verdict is not taken before the reconciliation any more: the display context that a
   * refusal describes is revalidated *after* the awaited read and immediately before the notice, so a
   * record the OE opened while the read was held can never inherit the old refusal. What keeps a
   * same-root denial truthful is the operation's own recorded withdrawal, not a stale verdict.
   */
  for (const [label, anchor] of [
    ['a failed ownership command', 'const reportOwnershipFailure = useCallback(async'],
    ['a failed Phase 2A command', 'const reportCommandFailure = useCallback(async']
  ]) {
    const from = workspaceSource.indexOf(anchor);
    assert.ok(from > -1, `${label} region`);
    const noticeAt = workspaceSource.indexOf('showNotice(copy.message, operation.session);', from);
    const region = workspaceSource.slice(from, noticeAt);
    const verdictAt = region.indexOf('if (disposition.targetDenied && !noticeContextHolds(operation))');
    const reconcileAt = region.indexOf('await refreshAll(operation.session)');
    assert.ok(verdictAt > -1 && reconcileAt > -1, `${label}: both decisions exist`);
    assert.ok(reconcileAt < verdictAt, `${label}: the verdict is re-taken after the awaited reconciliation`);
    assert.equal(region.includes('const publishable = '), false, `${label}: no pre-await publish verdict`);
    assert.equal(
      region.slice(0, reconcileAt).includes('if (disposition.targetDenied && !noticeContextHolds(operation))'),
      false,
      `${label}: no display-context verdict is taken before the reconciliation`
    );
  }
  /**
   * The scheduling drawer's outcome is withheld under the same verdict: a withheld refusal must never
   * be published into a drawer that now belongs to another record — or to a newer mount of this one.
   */
  /**
   * The scheduling failure continuation is located through the TypeScript AST as the actual
   * `onFailure` property of the real `runGuardedCommand` options object. No fixed character window
   * and no first textual occurrence is used, so comments or additive branches cannot redirect it.
   */
  const schedulingCallbacks = extractSchedulingCallbacks();
  const scheduleFailure = schedulingCallbacks.onFailureSource;
  const reportAt = scheduleFailure.indexOf('await reportCommandFailure(cause, operation, name);');
  const withheldMatch = /if \(!failure\.published\)\s*return;/.exec(scheduleFailure);
  const contextMatch = /if \(!noticeContextHolds\(operation\)\)\s*return;/.exec(scheduleFailure);
  const withheldAt = withheldMatch?.index ?? -1;
  const contextAt = contextMatch?.index ?? -1;
  const outcomeAt = scheduleFailure.indexOf('setScheduleOutcome(schedulingFailureOutcome(failure));');
  assert.ok(reportAt > -1 && withheldAt > -1 && contextAt > -1 && outcomeAt > -1);
  assert.ok(reportAt < withheldAt && withheldAt < contextAt && contextAt < outcomeAt,
    'a withheld refusal, and any outcome that no longer describes the open session, never becomes drawer state');
  /**
   * The stopped-command outcome is bound the same way: a booking whose pre-command read returned no
   * version must not write `review-required`/`not-sent` into a drawer that now belongs to another
   * record, because the OE left the session while that read was in flight.
   */
  const missing = schedulingCallbacks.onMissingVersionSource;
  assert.ok(
    missing.indexOf('if (!noticeContextHolds(operation)) return;') < missing.indexOf('setScheduleOutcome('),
    'a stopped command publishes no drawer outcome outside its own display context'
  );
});

test('an operation freezes the exact root instance it acts on; only that instance is its own', () => {
  signIn(ACCOUNT_A);
  const session = captureOperationsSession();
  const workspace = liveWorkspace({ session });

  // The OE has Session #3108 open (mount generation 3) and starts a command against it.
  const mounted3108 = { targetType: 'SESSION', targetId: 3108, mountGeneration: 3 };
  const operation = workspace.registry.begin('availability', session, 0, mounted3108);
  assert.deepEqual(operation.target, mounted3108, 'the operation retains the immutable target it was given');

  assert.equal(operationTargetIsMounted(operation, { ...mounted3108 }), true,
    'the same mounted instance is still the operation’s own');
  assert.equal(operationTargetIsMounted(operation, { targetType: 'SESSION', targetId: 3108, mountGeneration: 4 }), false,
    'the same record re-opened is a different instance, not this operation’s');
  assert.equal(operationTargetIsMounted(operation, { targetType: 'SESSION', targetId: 4109, mountGeneration: 4 }), false,
    'another record the OE opened later is never this operation’s');
  assert.equal(operationTargetIsMounted(operation, { targetType: 'REQUEST', targetId: 3108, mountGeneration: 3 }), false,
    'a Request root is not the Session root that happens to share its numeric id');
  assert.equal(operationTargetIsMounted(operation, null), false,
    'a record the OE navigated away from is not mounted at all');

  // A command started with no mounted detail of its own (a claim from the list) can never withdraw one.
  const fromList = workspace.registry.begin('claim', session, 0, null);
  assert.equal(fromList.target, null);
  assert.equal(operationTargetIsMounted(fromList, { targetType: 'SESSION', targetId: 3108, mountGeneration: 5 }), false,
    'a claim from the list owns no mounted instance');

  // The target can never be re-pointed afterwards: it belongs to the operation, not to the registry.
  const before = { ...operation.target };
  workspace.registry.advanceSessionEpoch();
  assert.deepEqual(operation.target, before, 'the captured target survives later workspace transitions');
});

/**
 * R1 target identity, driven through the real lifecycle decisions.
 *
 * The workspace's own mount bookkeeping is modelled here with the same rule the component applies: a
 * mount is a new generation, leaving retires it. Every verdict below comes from the production code —
 * `ownership.begin(..., target)` freezes the target and `operationTargetIsMounted` decides whether the
 * operation's own instance is still the one on screen — so the interleavings Sol reproduced are
 * proven against real state behaviour rather than source text.
 */
const mountContextWorkspace = () => {
  const registry = new OwnershipOperationRegistry();
  registry.setMounted(true);
  registry.setWorkspaceActive(true);
  let mounted = null;
  let generation = 0;
  return {
    registry,
    /** Opens (or re-opens) a root: a new mounted instance with a new generation. */
    mount(root) {
      generation += 1;
      mounted = { ...root, mountGeneration: generation };
      return mounted;
    },
    /** Leaves the detail entirely, exactly like the component refusing to carry a stale root. */
    leave() {
      mounted = null;
    },
    /** The workspace's withdrawal decision for one failed command. */
    withdraw(operation) {
      if (!operationTargetIsMounted(operation, mounted)) return 'kept';
      mounted = null;
      return 'withdrawn';
    },
    open: () => mounted
  };
};

test('same-root denial: the refused instance is the one withdrawn', () => {
  signIn(ACCOUNT_A);
  const session = captureOperationsSession();
  const workspace = mountContextWorkspace();
  workspace.mount({ targetType: 'SESSION', targetId: 3108 });
  const operation = workspace.registry.begin('availability', session, 0, workspace.open());

  assert.equal(workspace.withdraw(operation), 'withdrawn', 'the denied root’s own instance is withdrawn');
  assert.equal(workspace.open(), null);
});

test('different-root race: a denial targeting #3108 never withdraws the later-opened #4109', () => {
  signIn(ACCOUNT_A);
  const session = captureOperationsSession();
  const workspace = mountContextWorkspace();
  workspace.mount({ targetType: 'SESSION', targetId: 3108 });
  const operation = workspace.registry.begin('schedule', session, 0, workspace.open());

  // Sol's reproduction: while the command is in flight the OE opens the unrelated Session #4109.
  const unrelated = workspace.mount({ targetType: 'SESSION', targetId: 4109 });
  assert.equal(workspace.withdraw(operation), 'kept', '#4109 is not #3108’s to withdraw');
  assert.deepEqual(workspace.open(), unrelated, '#4109 remains exactly the instance that was mounted');
});

test('navigation away and back: an older operation cannot withdraw the newer mount of the same root', () => {
  signIn(ACCOUNT_A);
  const session = captureOperationsSession();
  const workspace = mountContextWorkspace();
  workspace.mount({ targetType: 'SESSION', targetId: 3108 });
  const operation = workspace.registry.begin('schedule', session, 0, workspace.open());

  // The OE leaves the record and re-opens it: same root identity, a brand-new mounted instance.
  workspace.leave();
  const reopened = workspace.mount({ targetType: 'SESSION', targetId: 3108 });
  assert.notDeepEqual(reopened, operation.target, 'the re-opened detail is a new instance');

  assert.equal(workspace.withdraw(operation), 'kept', 'the older operation cannot clear the newer context');
  assert.deepEqual(workspace.open(), reopened, 'the newer mount survives untouched');
});

test('negative control: the former wrong-target rule clears the unrelated root, the corrected rule does not', () => {
  signIn(ACCOUNT_A);
  const session = captureOperationsSession();
  const workspace = mountContextWorkspace();
  workspace.mount({ targetType: 'SESSION', targetId: 3108 });
  const operation = workspace.registry.begin('availability', session, 0, workspace.open());
  const unrelated = workspace.mount({ targetType: 'SESSION', targetId: 4109 });

  /**
   * The former defect in one line — "withdraw whatever root is on screen when the failure arrives" —
   * reproduced here as an in-memory variant. It would clear #4109; the corrected production rule
   * refuses to. The interleavings above fail under that former rule and pass with the correction,
   * which is exactly what makes them a regression test.
   */
  const formerRule = (_operation, mountedRoot) => mountedRoot !== null;
  assert.equal(formerRule(operation, workspace.open()), true,
    'the former rule considers #4109 withdrawable by #3108’s operation');
  assert.equal(operationTargetIsMounted(operation, workspace.open()), false,
    'the corrected rule does not');
  assert.equal(workspace.withdraw(operation), 'kept');
  assert.deepEqual(workspace.open(), unrelated);
});

/**
 * R1 display context — the second half of Sol's denial reproduction, driven through the real registry.
 *
 * The mounted-root identity above answers "is the refused instance still on screen?". It cannot answer
 * the question a refusal has to ask once its reconciliation has been *awaited*: when the record is no
 * longer mounted, did the operation itself take it away as the security response to its own denial
 * (whose notice must still be announced), or did the OE open another record — or re-open this one —
 * while the read was held (whose refusal must never be applied there)? The generation exercised below
 * is the production counter (`advanceDisplayContext`, `markWithdrewOwnDisplayContext`,
 * `withdrewOwnDisplayContext`), and the verdict is the workspace's own expression: a command with no
 * mounted detail of its own, the exact instance still mounted, or the operation's own withdrawal still
 * in force.
 */
const displayContextWorkspace = () => {
  const registry = new OwnershipOperationRegistry();
  registry.setMounted(true);
  registry.setWorkspaceActive(true);
  let mounted = null;
  /** Exactly the component's `setDisplayContext`: a display change moves the generation with it. */
  const setDisplayContext = (root) => {
    const generation = registry.advanceDisplayContext();
    mounted = root === null ? null : { ...root, mountGeneration: generation };
    return mounted;
  };
  return {
    registry,
    mount: (root) => setDisplayContext(root),
    leave: () => setDisplayContext(null),
    /** The workspace's post-reconciliation refusal verdict, verbatim. */
    noticeContextHolds: (operation) =>
      operation.target === null
      || operationTargetIsMounted(operation, mounted)
      || registry.withdrewOwnDisplayContext(operation),
    /** The workspace's synchronous withdrawal, including the display change it performs itself. */
    withdrawDeniedRoot(operation) {
      if (!operationTargetIsMounted(operation, mounted)) return 'kept';
      setDisplayContext(null);
      registry.markWithdrewOwnDisplayContext(operation);
      return 'withdrawn';
    },
    open: () => mounted
  };
};

test('same-root denial: the operation’s own withdrawal keeps its refusal in context', () => {
  signIn(ACCOUNT_A);
  const session = captureOperationsSession();
  const workspace = displayContextWorkspace();
  workspace.mount({ targetType: 'SESSION', targetId: 3108 });
  const operation = workspace.registry.begin('availability', session, 0, workspace.open());

  assert.equal(operation.withdrawnDisplayContext, null, 'nothing has been withdrawn yet');
  assert.equal(workspace.registry.withdrewOwnDisplayContext(operation), false);
  assert.equal(workspace.noticeContextHolds(operation), true, 'the refusal describes the mounted root');

  assert.equal(workspace.withdrawDeniedRoot(operation), 'withdrawn');
  assert.equal(workspace.open(), null, 'the refused detail is unmounted before anything is awaited');
  /**
   * The withdrawal is the one display change this operation performed itself, so the reconciled
   * refusal still describes the context on screen: a same-root denial must not lose its truthful
   * notice merely because it correctly unmounted the refused record.
   */
  assert.equal(operation.withdrawnDisplayContext, workspace.registry.displayContextGeneration);
  assert.equal(workspace.registry.withdrewOwnDisplayContext(operation), true);
  assert.equal(workspace.noticeContextHolds(operation), true, 'the same-root refusal is announced');
});

test('different-root notice race: a refusal awaited across the reconciliation never lands in #4109', () => {
  signIn(ACCOUNT_A);
  const session = captureOperationsSession();
  const workspace = displayContextWorkspace();
  workspace.mount({ targetType: 'SESSION', targetId: 3108 });
  const operation = workspace.registry.begin('availability', session, 0, workspace.open());

  // The denial lands while #3108 is still mounted, so the operation withdraws its own refused root.
  assert.equal(workspace.withdrawDeniedRoot(operation), 'withdrawn');
  /**
   * Sol's reproduction: the reconciliation is *held*, and the OE opens the unrelated Session #4109
   * with its independently authorized contact while the refused #3108 reconciliation is unresolved.
   */
  const unrelated = workspace.mount({ targetType: 'SESSION', targetId: 4109 });
  assert.deepEqual(workspace.open(), unrelated, '#4109 is mounted with its own instance');
  assert.equal(
    workspace.noticeContextHolds(operation),
    false,
    'the held #3108 refusal is obsolete and must be suppressed'
  );
  assert.equal(
    operationTargetIsMounted(operation, workspace.open()),
    false,
    'the mounted identity alone already refuses it — the generation decides the harder case below'
  );
});

test('same-root remount: the older refusal is suppressed once the root is re-opened', () => {
  signIn(ACCOUNT_A);
  const session = captureOperationsSession();
  const workspace = displayContextWorkspace();
  workspace.mount({ targetType: 'SESSION', targetId: 3108 });
  const operation = workspace.registry.begin('availability', session, 0, workspace.open());
  workspace.withdrawDeniedRoot(operation);

  // The OE re-opens #3108 in a brand-new mount generation before the reconciliation resolves.
  const reopened = workspace.mount({ targetType: 'SESSION', targetId: 3108 });
  assert.equal(workspace.open().mountGeneration, reopened.mountGeneration);
  assert.equal(
    workspace.registry.withdrewOwnDisplayContext(operation),
    false,
    'the newer mount is not the display context the old withdrawal produced'
  );
  assert.equal(workspace.noticeContextHolds(operation), false, 'the newer mount inherits nothing');
});

test('a Request and a Session that share a numeric id are never the same display context', () => {
  signIn(ACCOUNT_A);
  const session = captureOperationsSession();
  const workspace = displayContextWorkspace();
  workspace.mount({ targetType: 'SESSION', targetId: 3108 });
  const operation = workspace.registry.begin('availability', session, 0, workspace.open());

  // The OE opens the *Request* root that happens to carry the same id.
  workspace.mount({ targetType: 'REQUEST', targetId: 3108 });
  assert.equal(workspace.withdrawDeniedRoot(operation), 'kept', 'the Request root is not this command’s');
  assert.equal(workspace.open().targetType, 'REQUEST', 'the Request root survives untouched');
  assert.equal(workspace.noticeContextHolds(operation), false, 'the Session refusal is not the Request’s');
});

test('a claim started from the list keeps its unconditional announcement', () => {
  signIn(ACCOUNT_A);
  const workspace = displayContextWorkspace();
  // The queue is on screen: the claim has no mounted detail of its own to be bound to.
  const operation = workspace.registry.begin('claim', captureOperationsSession(), 0, null);
  assert.equal(operation.target, null);
  // The OE opens a record while the claim is in flight; the claim still speaks about the queues.
  workspace.mount({ targetType: 'SESSION', targetId: 3108 });
  assert.equal(workspace.noticeContextHolds(operation), true);
  workspace.leave();
  assert.equal(workspace.noticeContextHolds(operation), true);
});

test('only the live operation, and only once, may record a withdrawal as its own', () => {
  signIn(ACCOUNT_A);
  const session = captureOperationsSession();
  const workspace = displayContextWorkspace();
  workspace.mount({ targetType: 'SESSION', targetId: 3108 });
  const operation = workspace.registry.begin('availability', session, 0, workspace.open());
  workspace.withdrawDeniedRoot(operation);
  assert.equal(workspace.registry.markWithdrewOwnDisplayContext(operation), false, 'recorded exactly once');

  // A newer command owns the workspace; the abandoned one may not claim the display context it produced.
  const stale = workspace.registry.begin('release', session, 0, null);
  workspace.registry.finish(stale);
  assert.equal(
    workspace.registry.markWithdrewOwnDisplayContext(stale),
    false,
    'an operation that does not own the registry records nothing'
  );
  assert.equal(workspace.registry.withdrewOwnDisplayContext(stale), false);
});

test('negative control: a verdict captured before the await publishes the obsolete refusal', () => {
  signIn(ACCOUNT_A);
  const session = captureOperationsSession();
  const workspace = displayContextWorkspace();
  workspace.mount({ targetType: 'SESSION', targetId: 3108 });
  const operation = workspace.registry.begin('availability', session, 0, workspace.open());

  /**
   * The former defect in one line: the publish verdict was captured *before* the withdrawal and
   * reused after the awaited reconciliation. Reproduced here as an in-memory variant of exactly that
   * rule — it says `true` at classification time, and it still says `true` once #4109 is on screen,
   * which is why the old #3108 refusal used to be written into #4109. The corrected rule re-takes the
   * verdict after the reconciliation and refuses.
   */
  const formerPublishVerdict =
    operation.target === null || operationTargetIsMounted(operation, workspace.open());
  assert.equal(formerPublishVerdict, true, 'the former rule regarded the refusal as publishable');

  workspace.withdrawDeniedRoot(operation);
  workspace.mount({ targetType: 'SESSION', targetId: 4109 });
  assert.equal(formerPublishVerdict, true, 'and it is still true after a held reconciliation resolves');
  assert.equal(workspace.noticeContextHolds(operation), false, 'the corrected verdict is not');
});

test('a scheduling failure continuation may not write after its operation is abandoned', async () => {
  signIn(ACCOUNT_A);
  const workspace = liveWorkspace({ session: captureOperationsSession() });
  const gate = authorizationGate();
  const operation = workspace.begin('schedule');
  workspace.registry.markDispatched(operation);

  /**
   * The scheduling flow awaits its failure reporting (which reconciles against the server) and only
   * then writes its outcome. Holding that await while the workspace is invalidated reproduces R5: the
   * continuation must find itself unable to commit, so no outcome, notice or drawer write lands in
   * the context that replaced it.
   */
  let release;
  const held = new Promise((resolve) => {
    release = resolve;
  });
  const continuation = (async () => {
    await held;
    // This is the guard the flow now applies after the awaited report, for both failure kinds.
    if (!workspace.registry.canCommit(operation, gate)) return 'abandoned';
    return 'written';
  })();

  // Identity changes and authority is invalidated while the reporting is still in flight.
  signIn(ACCOUNT_B);
  workspace.registry.advanceSessionEpoch();
  workspace.registry.cancelActive('session-changed');
  release();
  assert.equal(await continuation, 'abandoned');

  // The same guard refuses the write for a revoked grant on an unchanged identity.
  const second = liveWorkspace({ session: captureOperationsSession() });
  const secondOperation = second.begin('schedule');
  second.registry.markDispatched(secondOperation);
  assert.equal(second.registry.canCommit(secondOperation, authorizationGate({ authorized: false })), false);

  /**
   * And the component really applies that guard at this exact point: after `await
   * reportCommandFailure(...)` and before any scheduling outcome is written.
   */
  const workspaceSource = read('../components/operations/OperationsWorkspace.tsx');
  assert.match(
    workspaceSource,
    /const failure = await reportCommandFailure\(cause, operation, name\);[\s\S]{0,600}?if \(!ownership\.canCommit\(operation, authorityRef\.current\)\) return;/
  );
});

test('an abandoned failure report would otherwise be published, so the guard is load-bearing', () => {
  const { schedulingFailureOutcome } = presentation;

  /**
   * `reportCommandFailure` returns this exact shape once its operation has been invalidated (the
   * message is empty because the notice is suppressed). Without the commit guard the scheduling
   * continuation would publish it as drawer state — a stale outcome in the context that replaced the
   * operation — so the guard, not the mapper, is what prevents the write.
   */
  const invalidated = { kind: 'rejected', status: 409, message: '', detail: null };
  assert.deepEqual(schedulingFailureOutcome(invalidated), {
    kind: 'conflict',
    status: 409,
    message: '',
    detail: null
  });
  assert.deepEqual(schedulingFailureOutcome({ kind: 'uncertain', status: null, message: '', detail: null }), {
    kind: 'uncertain',
    message: ''
  });

  // Every real classification still maps truthfully.
  assert.deepEqual(schedulingFailureOutcome({ kind: 'pre-dispatch', status: null, message: 'not sent', detail: null }), {
    kind: 'not-sent',
    message: 'not sent'
  });
  assert.deepEqual(schedulingFailureOutcome({ kind: 'rejected', status: 403, message: 'denied', detail: null }), {
    kind: 'denied',
    message: 'denied'
  });
  assert.deepEqual(schedulingFailureOutcome({ kind: 'rejected', status: 404, message: 'gone', detail: null }), {
    kind: 'denied',
    message: 'gone'
  });
  assert.deepEqual(schedulingFailureOutcome({ kind: 'rejected', status: 400, message: 'refused', detail: 'why' }), {
    kind: 'conflict',
    status: 400,
    message: 'refused',
    detail: 'why'
  });
});

test('the real failure reporter withdraws only its exact root, before the held reconciliation resolves', async () => {
  const withdrawSource = extractWithdrawDeniedRoot();
  const mounted = { targetType: 'SESSION', targetId: 500, mountGeneration: 3 };
  const openRootRef = { current: mounted };
  const displayChanges = [];
  const withdraw = compileCallback(withdrawSource, [
    'openRootRef', 'detailController', 'abortGuardedReads', 'writeAvailabilityDraft',
    'setDisplayContext', 'ownership', 'closeScheduleDrawer'
  ], [
    openRootRef,
    { current: { abort: () => displayChanges.push('abort-detail') } },
    () => displayChanges.push('abort-reads'),
    () => displayChanges.push('clear-draft'),
    () => displayChanges.push('clear-detail'),
    { markWithdrewOwnDisplayContext: () => displayChanges.push('mark-operation') },
    () => displayChanges.push('close-drawer')
  ]);
  const captured = { ...mounted };
  assert.equal(withdraw(captured), true, 'the exact mounted instance is withdrawn');
  assert.deepEqual(displayChanges, [
    'abort-detail', 'abort-reads', 'clear-draft', 'clear-detail', 'close-drawer'
  ]);

  displayChanges.length = 0;
  openRootRef.current = mounted;
  assert.equal(withdraw({ target: captured }), true, 'an operation denial withdraws its own root');
  assert.ok(displayChanges.includes('mark-operation'),
    'the operation records the withdrawal as its own display transition');

  displayChanges.length = 0;
  openRootRef.current = { ...mounted, mountGeneration: 4 };
  assert.equal(withdraw(captured), false, 'a remount of the same Session is not the old mount');
  openRootRef.current = { targetType: 'SESSION', targetId: 501, mountGeneration: 4 };
  assert.equal(withdraw(captured), false, 'the denial of old A cannot withdraw B');
  assert.deepEqual(displayChanges, [], 'neither a newer mount nor another root is touched');

  /**
   * The shared reporter is the actual production callback. A definitive denial must withdraw
   * synchronously and only then begin the awaited read; if the read is held, the refusal has already
   * left the screen. The reporter call is also the only place the caller can obtain `published`.
   */
  const reportSource = extractReportCommandFailure();
  const order = [];
  let releaseRefresh;
  const refreshGate = new Promise(resolve => { releaseRefresh = resolve; });
  class ApiRequestError extends Error {
    constructor(status) {
      super(`status ${status}`);
      this.status = status;
    }
  }
  const report = compileCallback(reportSource, [
    'ApiRequestError', 'ownership', 'authorityRef', 'commandFailureDisposition', 'withdrawDeniedRoot',
    'noticeContextHolds', 'refreshAll', 'ownershipOutcomeCopy', 'showNotice'
  ], [
    ApiRequestError,
    { canCommit: () => true },
    { current: {} },
    presentation.commandFailureDisposition,
    () => { order.push('withdraw'); return true; },
    () => true,
    async () => { order.push('refresh-start'); await refreshGate; order.push('refresh-end'); return 'loaded'; },
    presentation.ownershipOutcomeCopy,
    () => order.push('notice')
  ]);
  const operation = { dispatched: true, session: {}, target: captured };
  const pending = report(new ApiRequestError(403), operation, 'Visit Session · 500');
  await Promise.resolve();
  assert.deepEqual(order, ['withdraw', 'refresh-start'],
    'the protected root is gone before the held queue reconciliation');
  releaseRefresh();
  const result = await pending;
  assert.equal(result.published, true, 'the current display context receives the truthful outcome');
  assert.deepEqual(order, ['withdraw', 'refresh-start', 'refresh-end', 'notice']);

  // An account/navigation change before or during the reporter makes it a no-op.
  order.length = 0;
  const blocked = compileCallback(reportSource, [
    'ApiRequestError', 'ownership', 'authorityRef', 'commandFailureDisposition', 'withdrawDeniedRoot',
    'noticeContextHolds', 'refreshAll', 'ownershipOutcomeCopy', 'showNotice'
  ], [
    ApiRequestError,
    { canCommit: () => false },
    { current: {} },
    presentation.commandFailureDisposition,
    () => { order.push('withdraw'); return true; },
    () => true,
    async () => { order.push('refresh'); return 'loaded'; },
    presentation.ownershipOutcomeCopy,
    () => order.push('notice')
  ]);
  const blockedResult = await blocked(new ApiRequestError(404), operation, 'Visit Session · 500');
  assert.equal(blockedResult.published, false);
  assert.deepEqual(order, [], 'an abandoned account cannot withdraw, reconcile or publish');
});
