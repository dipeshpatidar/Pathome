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
import { registerHooks } from 'node:module';
import { resolve as resolveOperationsModule } from './support/operationsServiceLoader.mjs';

registerHooks({ resolve: resolveOperationsModule });

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
const { OwnershipOperationRegistry } = await import('../utils/operationsOwnershipRegistry.ts');
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
    `${RECORD} is no longer claimable or owned by you. The queues were refreshed from the server — review the latest state before deciding again.`
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
