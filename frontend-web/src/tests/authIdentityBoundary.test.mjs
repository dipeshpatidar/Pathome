/**
 * Behavioural coverage for the authentication identity boundary.
 *
 * Every case drives the real production module (`utils/authIdentity.ts`) rather than looking for code
 * strings, and then proves the two consequences that matter: nothing is persisted, and no protected
 * request can be transmitted, because the client is still unauthenticated.
 *
 * The reported defect was numeric coercion of the server's `userId`: `Number(true)` is `1` and
 * `Number([1])` is `1`, so a response such as `{ token: 't', userId: true }` would have signed the
 * visitor in as whichever real account owns id 1. The module under test proves the response type
 * *before* any conversion, so every malformed shape below must be rejected without a session.
 *
 * `operationsWorkspaceService` imports `../config/endpoints`, which reads the Vite-only
 * `import.meta.env`; a narrow loader hook redirects that single module (see `support/`) so the
 * production code runs unmodified. No production file was changed for testability.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import { resolve as resolveOperationsModule } from './support/operationsServiceLoader.mjs';

registerHooks({ resolve: resolveOperationsModule });

/* ------------------------------------------------------------------ *
 * Browser environment stubs (storage is real, not a mock of the module)
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
  clear: () => storage.clear(),
  key: (index) => [...storage.keys()][index] ?? null,
  get length() {
    return storage.size;
  }
};
globalThis.window = new FakeEventTarget();
globalThis.sessionStorage = {
  getItem: () => null,
  setItem: () => undefined,
  removeItem: () => undefined
};

const transmitted = [];
globalThis.fetch = async (url, init = {}) => {
  transmitted.push({ url, method: init.method ?? 'GET' });
  return { ok: true, status: 200, json: async () => ({}) };
};

const resetClient = () => {
  storage.clear();
  transmitted.length = 0;
};

/* ------------------------------------------------------------------ *
 * Modules under test (loaded after the loader hook is installed)
 * ------------------------------------------------------------------ */

const identityModule = await import('../utils/authIdentity.ts');
const { establishAuthenticatedSession, readAuthenticatedUserId } = identityModule;
const { readPersistedUser } = await import('../utils/authSession.ts');
const { captureOperationsSession } = await import('../utils/operationsSession.ts');
const { operationsWorkspaceService } = await import('../services/operationsWorkspaceService.ts');

const FALLBACK = { email: 'typed@example.com', fullName: 'Typed Name' };
const VALID_RESPONSE = {
  token: 'server-issued-token',
  userId: 41,
  email: 'issued@example.com',
  fullName: 'Issued Name',
  role: 'ROLE_TENANT',
  hasLessorProfile: false
};

/**
 * Proves the response left the client unauthenticated: nothing was persisted, the persisted-identity
 * reader still refuses a session, and the operations transport refuses to send a protected read.
 */
const assertUnauthenticated = async (label) => {
  assert.equal(storage.has('pathome_auth_token'), false, `${label}: no session token may be persisted`);
  assert.equal(storage.has('pathome_user'), false, `${label}: no account may be persisted`);
  assert.equal(readPersistedUser(), null, `${label}: no account can be restored`);
  assert.equal(captureOperationsSession(), null, `${label}: no Operations session exists`);
  await assert.rejects(
    () => operationsWorkspaceService.myWork(0, false),
    (error) => error.status === 401,
    `${label}: a protected read must be refused`
  );
  assert.equal(transmitted.length, 0, `${label}: no protected request may reach the transport`);
};

/* ------------------------------------------------------------------ *
 * The reported malformed payloads
 * ------------------------------------------------------------------ */

test('a malformed userId can never establish a session, whatever JavaScript coercion would do', async () => {
  const malformedIds = [
    ['boolean true', true],
    ['boolean false', false],
    ['single-element array', [1]],
    ['string-array', ['41']],
    ['object', { id: 41 }],
    ['null', null],
    ['undefined (missing field)', undefined],
    ['empty string', ''],
    ['whitespace-only string', '   '],
    ['zero', 0],
    ['negative number', -41],
    ['decimal number', 1.5],
    ['decimal string', '41.0'],
    ['exponent string', '1e3'],
    ['hex string', '0x29'],
    ['leading-zero string', '041'],
    ['signed string', '+41'],
    ['whitespace-padded string', ' 41 '],
    ['unsafe integer', Number.MAX_SAFE_INTEGER + 2],
    ['unsafe integer string', '9007199254740993'],
    ['NaN', Number.NaN],
    ['Infinity', Number.POSITIVE_INFINITY]
  ];

  for (const [label, value] of malformedIds) {
    resetClient();
    const response = { ...VALID_RESPONSE };
    if (value === undefined) delete response.userId;
    else response.userId = value;

    const outcome = establishAuthenticatedSession(response, FALLBACK);
    assert.equal(outcome.ok, false, `${label} must be rejected`);
    assert.equal(readAuthenticatedUserId(value), null, `${label} must not convert to an identity`);
    await assertUnauthenticated(label);
  }
});

test('a response envelope that is not an object is rejected', async () => {
  for (const [label, payload] of [
    ['null body', null],
    ['array body', [VALID_RESPONSE]],
    ['string body', 'ok'],
    ['missing token', { ...VALID_RESPONSE, token: undefined }],
    ['empty token', { ...VALID_RESPONSE, token: '' }],
    ['non-string token', { ...VALID_RESPONSE, token: 12345 }],
    ['missing role', { ...VALID_RESPONSE, role: undefined }],
    ['empty role', { ...VALID_RESPONSE, role: '' }]
  ]) {
    resetClient();
    const outcome = establishAuthenticatedSession(payload, FALLBACK);
    assert.equal(outcome.ok, false, `${label} must be rejected`);
    await assertUnauthenticated(label);
  }
});

/* ------------------------------------------------------------------ *
 * The supported contract still works
 * ------------------------------------------------------------------ */

test('the supported numeric contract still establishes the session it always did', () => {
  resetClient();
  const outcome = establishAuthenticatedSession(VALID_RESPONSE, FALLBACK);
  assert.equal(outcome.ok, true);
  assert.equal(outcome.userId, 41);
  assert.equal(outcome.email, 'issued@example.com');
  assert.equal(outcome.fullName, 'Issued Name');
  assert.equal(outcome.role, 'ROLE_TENANT');
  assert.equal(outcome.hasLessorProfile, false);
  assert.equal(storage.get('pathome_auth_token'), 'server-issued-token');
  assert.equal(readPersistedUser(), null, 'the account is persisted by the shell, not by this boundary');
});

test('a canonical decimal-string id is accepted, and only in its exact form', () => {
  resetClient();
  const outcome = establishAuthenticatedSession({ ...VALID_RESPONSE, userId: '41' }, FALLBACK);
  assert.equal(outcome.ok, true);
  assert.equal(outcome.userId, 41);

  assert.equal(readAuthenticatedUserId('41'), 41);
  assert.equal(readAuthenticatedUserId(41), 41);
  assert.equal(readAuthenticatedUserId('9007199254740991'), Number.MAX_SAFE_INTEGER);
  for (const rejected of ['41.0', '1e3', '041', '+41', ' 41', '41 ', '', '  ', '0', '-41', '0x29']) {
    assert.equal(readAuthenticatedUserId(rejected), null, `${JSON.stringify(rejected)} must be rejected`);
  }
});

test('a malformed response cannot start a protected read even after a rejected sign-in attempt', async () => {
  // The reported reproduction: the server answers 200 with `userId: true`.
  resetClient();
  const outcome = establishAuthenticatedSession({ ...VALID_RESPONSE, userId: true }, FALLBACK);
  assert.equal(outcome.ok, false);
  await assertUnauthenticated('userId:true');

  // A caller that ignored the verdict and asked for protected work anyway still transmits nothing.
  await assert.rejects(() => operationsWorkspaceService.claimableWork(0), (error) => error.status === 401);
  await assert.rejects(
    () => operationsWorkspaceService.requestDetail(7),
    (error) => error.status === 401
  );
  assert.equal(transmitted.length, 0);
});
