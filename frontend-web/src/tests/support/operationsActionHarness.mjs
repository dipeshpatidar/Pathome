/**
 * Shared transport harness for the Phase 2A action suites.
 *
 * The production service, session boundary and presentation mappers are the real ones; only the
 * network is stubbed and the browser globals are supplied. `config/endpoints` is redirected by the
 * existing loader hook because it reads the Vite-only `import.meta.env`, so no production file is
 * changed for testability.
 */
import { registerHooks } from 'node:module';
import { resolve as resolveOperationsModule } from './operationsServiceLoader.mjs';

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

/** Installs the browser globals and the recording transport, then loads the production modules. */
export const installOperationsActionHarness = async () => {
  registerHooks({ resolve: resolveOperationsModule });

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

  const calls = [];
  let responder = () => ({ ok: true, status: 200, json: async () => ({}) });

  globalThis.fetch = (url, init = {}) => {
    const record = {
      url: String(url),
      method: init.method ?? 'GET',
      authorization: init.headers?.Authorization,
      contentType: init.headers?.['Content-Type'],
      body: init.body === undefined ? undefined : JSON.parse(init.body),
      signal: init.signal,
      cache: init.cache
    };
    calls.push(record);
    return Promise.resolve(responder(record));
  };

  const json = (status, body) => ({ ok: status >= 200 && status < 300, status, json: async () => body });
  /** A 200 answer whose body cannot be decoded: the command was transmitted but is unconfirmed. */
  const undecodable = (status = 200) => ({
    ok: true,
    status,
    json: async () => {
      throw new Error('undecodable body');
    }
  });
  const noContent = () => ({
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

  const signIn = ({ id, role, token }) => {
    storage.set('pathome_auth_token', token);
    storage.set('pathome_user', JSON.stringify({ id, role }));
    storage.set('pathome_role', role);
    globalThis.window.dispatchEvent({ type: 'pathome_auth_changed' });
  };
  const signOut = () => {
    storage.delete('pathome_auth_token');
    storage.delete('pathome_user');
    storage.delete('pathome_role');
    globalThis.window.dispatchEvent({ type: 'pathome_session_expired' });
  };

  const sessionModule = await import('../../utils/operationsSession.ts');
  const presentation = await import('../../utils/operationsWorkspacePresentation.ts');
  const { operationsWorkspaceService } = await import('../../services/operationsWorkspaceService.ts');

  // Mirrors how the workspace binds to invalidation: the module must observe the events it listens
  // for, otherwise a sign-out would not deactivate an operation's captured credential.
  sessionModule.subscribeOperationsSession(() => {});

  return {
    calls,
    presentation,
    service: operationsWorkspaceService,
    session: sessionModule,
    json,
    undecodable,
    noContent,
    deferred,
    signIn,
    signOut,
    /** Replaces the response function and clears the recorded calls. */
    reset(handler) {
      calls.length = 0;
      responder = handler ?? (() => json(200, {}));
    },
    /** Changes only the response function, keeping the recorded calls (for multi-step flows). */
    respondWith(handler) {
      responder = handler;
    },
    storage
  };
};
