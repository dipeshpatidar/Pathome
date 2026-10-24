import test from 'node:test';
import assert from 'node:assert/strict';
import { operationsRoutingService } from '../services/operationsRoutingService.ts';
import { staffAdministrationService } from '../services/staffAdministrationService.ts';
import {
  captureOperationsSession,
  invalidateOperationsSession,
  isOperationsSessionChangedError
} from '../utils/operationsSession.ts';

const values = new Map();
const storage = {
  getItem: (key) => values.get(key) ?? null,
  setItem: (key, value) => values.set(key, String(value)),
  removeItem: (key) => values.delete(key),
  clear: () => values.clear()
};

const seed = (userId, token) => {
  storage.setItem('pathome_auth_token', token);
  storage.setItem('pathome_role', 'EMPLOYEE');
  storage.setItem('pathome_user', JSON.stringify({ id: userId, role: 'EMPLOYEE' }));
};

const restoreGlobals = {
  storage: Object.getOwnPropertyDescriptor(globalThis, 'localStorage'),
  fetch: globalThis.fetch
};
Object.defineProperty(globalThis, 'localStorage', {
  configurable: true,
  value: storage
});

test.after(() => {
  globalThis.fetch = restoreGlobals.fetch;
  if (restoreGlobals.storage) {
    Object.defineProperty(globalThis, 'localStorage', restoreGlobals.storage);
  } else {
    delete globalThis.localStorage;
  }
});

test('routing response decoded after account switch is never returned', async () => {
  storage.clear();
  seed(101, 'token-a');
  const session = captureOperationsSession();
  assert.ok(session);
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    json: async () => {
      seed(202, 'token-b');
      return { items: [], totalCount: 0, page: 0, size: 20, totalPages: 0 };
    }
  });

  await assert.rejects(
    operationsRoutingService.tasks(0, { session }),
    (cause) => isOperationsSessionChangedError(cause)
  );
});

test('staff error decoding after token rotation cannot return protected state', async () => {
  storage.clear();
  seed(101, 'token-a');
  const session = captureOperationsSession();
  assert.ok(session);
  globalThis.fetch = async () => ({
    ok: false,
    status: 500,
    json: async () => {
      seed(101, 'rotated-token');
      return { message: 'technical detail that must not escape after rotation' };
    }
  });

  await assert.rejects(
    staffAdministrationService.inspect(55, { session }),
    (cause) => isOperationsSessionChangedError(cause)
  );
});

test('routing error decoding after account switch cannot return an account-A error', async () => {
  storage.clear();
  seed(101, 'token-a');
  const session = captureOperationsSession();
  assert.ok(session);
  globalThis.fetch = async () => ({
    ok: false,
    status: 503,
    json: async () => {
      seed(202, 'token-b');
      return { message: 'stale operational detail' };
    }
  });

  await assert.rejects(
    operationsRoutingService.coverage(5, 0, { session }),
    (cause) => isOperationsSessionChangedError(cause)
  );
});

test('account A to B to A cannot revive an invalidated operation', async () => {
  storage.clear();
  seed(101, 'token-a');
  const session = captureOperationsSession();
  assert.ok(session);
  seed(202, 'token-b');
  invalidateOperationsSession('auth-changed');
  seed(101, 'token-a');
  globalThis.fetch = async () => {
    throw new Error('stale operation must not dispatch');
  };

  await assert.rejects(
    staffAdministrationService.searchAccounts('staff', { session }),
    (cause) => isOperationsSessionChangedError(cause)
  );
});
