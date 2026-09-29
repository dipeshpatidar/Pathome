import test from 'node:test';
import assert from 'node:assert/strict';
import { clearPersistedUser, readPersistedUser } from '../utils/authSession.ts';
import { createApiRequestError } from '../services/apiError.ts';
import { isCurrentLessorSession, readLessorSessionIdentity } from '../utils/lessorCapabilityState.ts';

function storage(entries = []) {
  const values = new Map(entries);
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: key => values.delete(key),
    get length() { return values.size; },
    key: index => [...values.keys()][index] ?? null,
    values
  };
}

test('logout removes only persisted account identity and prevents rehydration', () => {
  const profile = { id: 11, role: 'TENANT', fullName: 'Lessor A' };
  const local = storage([
    ['pathome_auth_token', 'token-a'],
    ['pathome_role', 'TENANT'],
    ['pathome_user', JSON.stringify(profile)],
    ['pathome_active_admin_tab', 'overview'],
    ['pathome_lessor_unsynced_11_draft-a', 'private-edit'],
    ['pathome_lessor_unsynced_22_draft-b', 'other-account-edit'],
    ['pathome_guest_draft_id', 'guest-draft'],
    ['pathome_search_city', 'Indore']
  ]);
  globalThis.localStorage = local;
  try {
    assert.deepEqual(readPersistedUser(), profile);
    clearPersistedUser();
    assert.equal(readPersistedUser(), null);
    for (const key of ['pathome_auth_token', 'pathome_role', 'pathome_user', 'pathome_active_admin_tab']) {
      assert.equal(local.getItem(key), null);
    }
    assert.equal(local.getItem('pathome_guest_draft_id'), 'guest-draft');
    assert.equal(local.getItem('pathome_search_city'), 'Indore');
    assert.equal(local.getItem('pathome_lessor_unsynced_11_draft-a'), null);
    assert.equal(local.getItem('pathome_lessor_unsynced_22_draft-b'), 'other-account-edit');
    local.setItem('pathome_auth_token', 'token-b');
    assert.equal(readPersistedUser(), null, 'old profile cannot rehydrate with a new token alone');
  } finally {
    delete globalThis.localStorage;
  }
});

test('delayed 401 from A cannot expire B, while B 401 still expires B', async () => {
  const local = storage([['pathome_auth_token', 'token-b'], ['pathome_user', JSON.stringify({ id: 22 })]]);
  const events = [];
  globalThis.localStorage = local;
  globalThis.window = { dispatchEvent: event => { events.push(event); return true; } };
  try {
    const failed = () => new Response(JSON.stringify({ message: 'Unauthorized' }), { status: 401 });
    await createApiRequestError(failed(), 'Failed', false, 'token-a');
    assert.equal(events.length, 0);
    await createApiRequestError(failed(), 'Failed', false, 'token-b');
    assert.equal(events.length, 1);
    assert.equal(events[0].type, 'pathome_session_expired');
    assert.equal(events[0].detail.token, 'token-b');
  } finally {
    delete globalThis.localStorage;
    delete globalThis.window;
  }
});

test('old lessor request identity is invalid after logout and after another account signs in', () => {
  const local = storage([
    ['pathome_auth_token', 'token-a'],
    ['pathome_user', JSON.stringify({ id: 11, role: 'TENANT' })],
    ['pathome_role', 'TENANT']
  ]);
  globalThis.localStorage = local;
  try {
    const requestA = readLessorSessionIdentity();
    assert.ok(requestA);
    assert.equal(isCurrentLessorSession(requestA), true);
    clearPersistedUser();
    assert.equal(isCurrentLessorSession(requestA), false);
    local.setItem('pathome_auth_token', 'token-b');
    local.setItem('pathome_user', JSON.stringify({ id: 22, role: 'TENANT' }));
    local.setItem('pathome_role', 'TENANT');
    assert.equal(isCurrentLessorSession(requestA), false);
    assert.equal(isCurrentLessorSession(readLessorSessionIdentity()), true);
  } finally {
    delete globalThis.localStorage;
  }
});

test('incoherent stored identity cannot restore account UI', () => {
  const local = storage([
    ['pathome_auth_token', 'old-token'],
    ['pathome_role', 'TENANT'],
    ['pathome_user', JSON.stringify({ id: 11, role: 'SUPER_ADMIN' })]
  ]);
  globalThis.localStorage = local;
  try {
    assert.equal(readPersistedUser(), null);
    assert.equal(local.getItem('pathome_auth_token'), null);
    assert.equal(local.getItem('pathome_user'), null);
  } finally {
    delete globalThis.localStorage;
  }
});
