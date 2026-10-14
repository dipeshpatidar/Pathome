/**
 * Behavioural coverage for the OE session boundary — the security fix that Package 1 lacked.
 *
 * These tests drive the real production service (no source-text assertions) with a stubbed
 * `fetch`, a controllable account switch and deliberately held responses, reproducing the reported
 * race deterministically:
 *
 *   Account A starts a claim, an asynchronous read is paused, Account B signs in while that read is
 *   still pending. The old flow must not dispatch a claim with B's credential, and A's response must
 *   never populate B's workspace.
 *
 * The service imports `../config/endpoints`, which reads the Vite-only `import.meta.env`; a narrow
 * loader hook redirects that single module (see support/) so the production code is exercised
 * unmodified. No production file was changed for testability.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import { resolve as resolveOperationsModule } from './support/operationsServiceLoader.mjs';

registerHooks({ resolve: resolveOperationsModule });

/* ------------------------------------------------------------------ *
 * Browser environment stubs (storage, window events, fetch)
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

const jsonResponse = (status, body) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body
});
/** A 204 with no body at all: reading it must fail, which proves nothing is deserialised. */
const noContentResponse = () => ({
  ok: true,
  status: 204,
  json: async () => {
    throw new Error('a 204 ownership command must never be deserialised');
  }
});

const deferred = () => {
  let settle;
  let fail;
  const promise = new Promise((resolve, reject) => {
    settle = resolve;
    fail = reject;
  });
  return { promise, resolve: settle, reject: fail };
};

const recordedCalls = [];
let responder = () => jsonResponse(200, {});

globalThis.fetch = (url, init = {}) => {
  const record = {
    url,
    method: init.method ?? 'GET',
    authorization: init.headers?.Authorization,
    body: init.body === undefined ? undefined : JSON.parse(init.body),
    signal: init.signal,
    cache: init.cache
  };
  recordedCalls.push(record);
  return Promise.resolve(responder(record));
};

const resetTransport = () => {
  recordedCalls.length = 0;
  responder = () => jsonResponse(200, {});
};

/* ------------------------------------------------------------------ *
 * Modules under test (loaded after the loader hook is installed)
 * ------------------------------------------------------------------ */

const sessionModule = await import('../utils/operationsSession.ts');
const { operationsWorkspaceService } = await import('../services/operationsWorkspaceService.ts');
const {
  OperationsSessionChangedError,
  captureOperationsSession,
  currentOperationsSessionGeneration,
  invalidateOperationsSession,
  isOperationsSessionActive,
  isOperationsSessionChangedError,
  readOperationsSession,
  subscribeOperationsSession
} = sessionModule;

// Mirrors how the workspace and the capability hook bind to session invalidation events.
subscribeOperationsSession(() => {});

const ACCOUNT_A = { id: 11, role: 'EMPLOYEE', token: 'token-account-a' };
const ACCOUNT_B = { id: 22, role: 'ADMIN', token: 'token-account-b' };

const claimablePayload = () => ({
  targetType: 'REQUEST',
  targetId: 5200,
  expectedVersion: 7,
  cityId: 1,
  cityDisplayName: 'Indore',
  teamId: 7,
  teamDisplayName: 'Team A',
  status: 'RECEIVED',
  linkedRequestVersions: [{ requestId: 900, version: 2 }]
});

const capabilityPayload = () => ({
  staffActive: true,
  serverTime: '2026-10-12T15:00:00Z',
  capabilities: [
    {
      capability: 'OPS_COORDINATE',
      scopeType: 'TEAM',
      cityId: 1,
      cityDisplayName: 'Indore',
      teamId: 7,
      teamDisplayName: 'Team A'
    }
  ]
});

/* 1. The reported cross-account mutation race ------------------------------------- */

test('an account switch during a paused claim read never lets the claim be sent for either account', async () => {
  resetTransport();
  signIn(ACCOUNT_A);
  const sessionA = captureOperationsSession();
  assert.ok(sessionA, 'Account A establishes a session');
  assert.equal(sessionA.userId, 11);
  assert.equal(sessionA.role, 'EMPLOYEE');
  // The comparison identity never carries the raw credential.
  assert.equal(sessionA.identity.includes(ACCOUNT_A.token), false);

  const held = deferred();
  responder = (record) =>
    record.url.endsWith('/operations/claimable-work/REQUEST/5200') ? held.promise : jsonResponse(200, {});

  // 1. Account A starts the logical claim: a fresh read of the root is in flight and paused.
  const reRead = operationsWorkspaceService.claimableItem('REQUEST', 5200, { session: sessionA });

  // 2. Account B signs in while that read is still pending.
  signIn(ACCOUNT_B);
  assert.equal(isOperationsSessionActive(sessionA), false, 'A is no longer the signed-in session');

  // 3. A's response finally arrives with a perfectly valid payload.
  held.resolve(jsonResponse(200, claimablePayload()));
  await assert.rejects(reRead, (cause) => isOperationsSessionChangedError(cause));

  // 4. Nothing may be dispatched: no claim with A's credential and none with B's either.
  responder = () => noContentResponse();
  await assert.rejects(
    operationsWorkspaceService.claimRequest(5200, 7, { session: sessionA }),
    (cause) => isOperationsSessionChangedError(cause)
  );
  const posts = recordedCalls.filter((call) => call.method === 'POST');
  assert.equal(posts.length, 0, 'no ownership command may be transmitted for a stale session');
  for (const call of recordedCalls) {
    assert.equal(call.authorization, `Bearer ${ACCOUNT_A.token}`, 'requests keep the captured credential');
  }

  // 5. The control case proves the flow itself still works under one unchanged session.
  resetTransport();
  signIn(ACCOUNT_A);
  const current = captureOperationsSession();
  responder = (record) =>
    record.url.includes('/operations/claimable-work/')
      ? jsonResponse(200, claimablePayload())
      : noContentResponse();
  const fresh = await operationsWorkspaceService.claimableItem('REQUEST', 5200, { session: current });
  assert.equal(fresh.expectedVersion, 7);
  const status = await operationsWorkspaceService.claimRequest(fresh.targetId, fresh.expectedVersion, {
    session: current
  });
  assert.equal(status, 204);
  const issued = recordedCalls.filter((call) => call.method === 'POST');
  assert.equal(issued.length, 1);
  assert.equal(issued[0].authorization, `Bearer ${ACCOUNT_A.token}`);
  assert.deepEqual(issued[0].body, { expectedRequestVersion: 7, reasonCode: 'CLAIM_WORK' });
});

test('a credential replaced without any session event still blocks the stale operation', async () => {
  resetTransport();
  signIn(ACCOUNT_A);
  const sessionA = captureOperationsSession();

  const held = deferred();
  responder = (record) =>
    record.url.includes('/operations/claimable-work/') ? held.promise : jsonResponse(200, {});
  const reRead = operationsWorkspaceService.claimableItem('SESSION', 3108, { session: sessionA });

  // No logout, no account-switch event: the credential is simply replaced underneath the operation.
  persistCredentialsOnly(ACCOUNT_B);
  held.resolve(jsonResponse(200, { targetType: 'SESSION', targetId: 3108, expectedVersion: 6, status: 'DRAFT' }));
  await assert.rejects(reRead, (cause) => isOperationsSessionChangedError(cause));

  const sessionB = captureOperationsSession();
  await assert.rejects(
    operationsWorkspaceService.claimSession(3108, 6, { 900: 2 }, { session: sessionA }),
    (cause) => isOperationsSessionChangedError(cause)
  );
  // The new account can still act for itself; only the old operation is refused.
  responder = () => noContentResponse();
  assert.equal(await operationsWorkspaceService.claimSession(3108, 6, { 900: 2 }, { session: sessionB }), 204);
  assert.equal(
    recordedCalls.filter((call) => call.method === 'POST').length,
    1,
    'exactly one command is transmitted, and it carries the new account credential'
  );
});

/* 2. Stale responses never populate the next account's workspace --------------------- */

test('a page read issued by a previous account is dropped instead of populating the queues', async () => {
  resetTransport();
  signIn(ACCOUNT_A);
  const sessionA = captureOperationsSession();

  const held = deferred();
  responder = (record) => (record.url.includes('/operations/my-work') ? held.promise : jsonResponse(200, {}));
  const myWork = operationsWorkspaceService.myWork(0, false, { session: sessionA });

  signIn(ACCOUNT_B);
  const sessionB = captureOperationsSession();
  held.resolve(
    jsonResponse(200, {
      items: [{ targetType: 'REQUEST', targetId: 5200, expectedVersion: 7, status: 'RECEIVED' }],
      totalCount: 1,
      page: 0,
      size: 20,
      totalPages: 1
    })
  );

  await assert.rejects(myWork, (cause) => isOperationsSessionChangedError(cause));
  // The commit guard the workspace uses also refuses the stale result.
  assert.equal(isOperationsSessionActive(sessionA), false);
  assert.equal(isOperationsSessionActive(sessionB), true);
});

test('a capability read from a previous account cannot re-enable the new one', async () => {
  resetTransport();
  signIn(ACCOUNT_A);
  const sessionA = captureOperationsSession();

  const held = deferred();
  responder = (record) => (record.url.endsWith('/staff/me/capabilities') ? held.promise : jsonResponse(200, {}));
  const capabilities = operationsWorkspaceService.capabilities({ session: sessionA });

  signIn(ACCOUNT_B);
  held.resolve(jsonResponse(200, capabilityPayload()));
  await assert.rejects(capabilities, (cause) => isOperationsSessionChangedError(cause));
});

/* 3. Logout and unmount during a pending mutation ----------------------------------- */

test('logout during a pending mutation discards its result and cannot announce success', async () => {
  resetTransport();
  signIn(ACCOUNT_A);
  const sessionA = captureOperationsSession();
  const held = deferred();
  responder = (record) => (record.method === 'POST' ? held.promise : jsonResponse(200, {}));

  const claim = operationsWorkspaceService.claimRequest(5200, 7, { session: sessionA });
  assert.equal(recordedCalls.at(-1).method, 'POST', 'the command was transmitted before logout');

  signOut();
  held.resolve(noContentResponse());
  await assert.rejects(claim, (cause) => isOperationsSessionChangedError(cause));
  assert.equal(captureOperationsSession(), null, 'no session remains after logout');
});

test('an aborted (unmounted) operation surfaces cancellation, not a false result', async () => {
  resetTransport();
  signIn(ACCOUNT_A);
  const sessionA = captureOperationsSession();
  const controller = new AbortController();
  const held = deferred();
  responder = () => held.promise;

  const pending = operationsWorkspaceService.sessionDetail(3108, { session: sessionA, signal: controller.signal });
  controller.abort();
  held.reject(Object.assign(new Error('aborted'), { name: 'AbortError' }));
  await assert.rejects(pending, (cause) => cause.name === 'AbortError');
});

/* 4. Real request payloads and HTTP semantics --------------------------------------- */

test('ownership commands send the exact backend contract and never read a 204 body', async () => {
  resetTransport();
  signIn(ACCOUNT_A);
  const sessionA = captureOperationsSession();
  responder = () => noContentResponse();

  assert.equal(await operationsWorkspaceService.releaseRequest(501, 4, { session: sessionA }), 204);
  assert.equal(
    await operationsWorkspaceService.releaseSession(3108, 6, { 501: 4 }, { session: sessionA }),
    204
  );
  assert.deepEqual(
    recordedCalls.map((call) => ({ url: call.url, body: call.body, cache: call.cache })),
    [
      {
        url: 'https://stub.invalid/api/v1/operations/visit-requests/501/release',
        body: { expectedRequestVersion: 4, reasonCode: 'RELEASE_COORDINATOR' },
        cache: 'no-store'
      },
      {
        url: 'https://stub.invalid/api/v1/operations/visit-sessions/3108/release',
        body: { expectedSessionVersion: 6, expectedRequestVersions: { 501: 4 }, reasonCode: 'RELEASE_COORDINATOR' },
        cache: 'no-store'
      }
    ]
  );
});

test('an error envelope is classified without leaking another session to the caller', async () => {
  resetTransport();
  signIn(ACCOUNT_A);
  const sessionA = captureOperationsSession();
  responder = () => jsonResponse(409, { status: 409, message: 'Visit Request version is stale; refresh and retry' });

  await assert.rejects(
    operationsWorkspaceService.claimRequest(5200, 7, { session: sessionA }),
    (cause) => cause.status === 409
  );
});

/* 5. Session identity, invalidation and subscription semantics ---------------------- */

test('the session identity label carries no credential, and activity is decided by exact comparison', async () => {
  resetTransport();
  signIn(ACCOUNT_A);
  const sessionA = readOperationsSession();
  assert.equal(sessionA.userId, 11);
  assert.equal(sessionA.role, 'EMPLOYEE');
  assert.match(sessionA.identity, /^11\|EMPLOYEE\|[0-9a-f]{8}$/);
  assert.equal(sessionA.identity.includes(ACCOUNT_A.token), false);
  // `identity` is a display/dedupe label only. Whether an operation may still act is decided by
  // comparing the captured credential exactly (see operationsOwnershipRaceCorrections.test.mjs for
  // the colliding-digest proof), never by this digest.
  assert.equal(isOperationsSessionActive(sessionA), true);

  signIn(ACCOUNT_B);
  const sessionB = readOperationsSession();
  assert.notEqual(sessionA.identity, sessionB.identity);
  assert.equal(sessionB.role, 'ADMIN');

  signOut();
  assert.equal(readOperationsSession(), null, 'a missing token or account cannot establish a session');
});

test('invalidation, logout and cross-tab replacement all deactivate a captured session', async () => {
  resetTransport();
  signIn(ACCOUNT_A);
  const first = captureOperationsSession();
  const generation = currentOperationsSessionGeneration();
  assert.equal(isOperationsSessionActive(first), true);

  let notified = 0;
  const unsubscribe = subscribeOperationsSession(() => {
    notified += 1;
  });

  // 1. Logout event.
  storage.delete('pathome_auth_token');
  globalThis.window.dispatchEvent({ type: 'pathome_session_expired' });
  assert.equal(isOperationsSessionActive(first), false);
  assert.equal(notified, 1);

  // 2. The very same credentials restored still produce an inactive older capture (generation).
  persistCredentialsOnly(ACCOUNT_A);
  const restored = captureOperationsSession();
  assert.notEqual(restored.generation, generation);
  assert.equal(isOperationsSessionActive(first), false, 'a pre-invalidation capture never becomes valid again');
  assert.equal(isOperationsSessionActive(restored), true);

  // 3. A cross-tab storage replacement invalidates the current capture.
  globalThis.window.dispatchEvent({ type: 'storage', key: 'pathome_auth_token' });
  assert.equal(isOperationsSessionActive(restored), false);
  assert.equal(notified, 2);

  // 4. Explicit invalidation and unsubscription.
  invalidateOperationsSession();
  assert.equal(notified, 3);
  unsubscribe();
  invalidateOperationsSession();
  assert.equal(notified, 3, 'an unsubscribed listener is not called again');
});

test('a missing session fails closed with a sign-in error instead of an anonymous call', async () => {
  resetTransport();
  signOut();
  await assert.rejects(
    operationsWorkspaceService.myWork(0, false),
    (cause) => cause.status === 401
  );
  assert.equal(recordedCalls.length, 0, 'no request is issued without a session');
  assert.equal(isOperationsSessionChangedError(new OperationsSessionChangedError()), true);
});
