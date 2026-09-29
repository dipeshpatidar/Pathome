import test from 'node:test';
import assert from 'node:assert/strict';
import { LessorAutosave } from '../services/lessorAutosave.ts';
import { clearPersistedUser } from '../utils/authSession.ts';

function storage() {
  const values = new Map([
    ['pathome_auth_token', 'test-token-7'],
    ['pathome_user', JSON.stringify({ id: 7, role: 'TENANT' })]
  ]);
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key),
    get length() { return values.size; },
    key: index => [...values.keys()][index] ?? null
  };
}

const basics = { propertyType: 'FLAT', rentalMode: 'LONG_TERM_RENTAL', bhkCount: '1RK' };
const pricing = { monthlyRent: 15000, securityDeposit: 0 };
const result = version => ({ draftId: 'd1', status: 'DRAFT', version, completionPercent: 20,
  data: { basics, pricing: null, location: null, details: null }, createdAt: '', updatedAt: '' });

test('batches latest section edit and serializes distinct sections with fresh versions', async () => {
  globalThis.localStorage = storage();
  const calls = [];
  const statuses = [];
  const save = async (_id, section, version, value) => {
    calls.push({ section, version, value });
    return result(version + 1);
  };
  const queue = new LessorAutosave(7, 'd1', 1, s => statuses.push(s), () => {}, save);
  queue.change('basics', { ...basics, bhkCount: '1BHK' });
  queue.change('basics', basics);
  queue.change('pricing', pricing);
  assert.equal(await queue.flush(), true);
  assert.deepEqual(calls, [
    { section: 'basics', version: 1, value: basics },
    { section: 'pricing', version: 2, value: pricing }
  ]);
  assert.equal(queue.getStatus(), 'saved');
  assert.equal(localStorage.getItem('pathome_lessor_unsynced_7_d1'), null);
  queue.dispose();
});

test('overlapping flushes never send the same draft version twice', async () => {
  globalThis.localStorage = storage();
  let releaseFirst;
  const firstPending = new Promise(resolve => { releaseFirst = resolve; });
  const calls = [];
  const queue = new LessorAutosave(7, 'd1', 1, () => {}, () => {},
    async (_id, section, version) => {
      calls.push({ section, version });
      if (calls.length === 1) await firstPending;
      return result(version + 1);
    });
  queue.change('pricing', pricing);
  const first = queue.flush();
  // Basics was already skipped by the in-flight drain when this edit arrived.
  queue.change('basics', basics);
  const second = queue.flush();
  const third = queue.flush();
  releaseFirst();
  assert.deepEqual(await Promise.all([first, second, third]), [true, true, true]);
  assert.deepEqual(calls, [{ section: 'pricing', version: 1 }, { section: 'basics', version: 2 }]);
  queue.dispose();
});

test('keeps failed edits for refresh and blocks stale local data', async () => {
  globalThis.localStorage = storage();
  const fail = async () => { throw new Error('offline'); };
  const queue = new LessorAutosave(7, 'd1', 1, () => {}, () => {}, fail);
  queue.change('pricing', pricing);
  assert.equal(await queue.flush(), false);
  assert.equal(queue.getStatus(), 'error');
  queue.dispose();

  const retried = new LessorAutosave(7, 'd1', 1, () => {}, () => {},
    async (_id, _section, version) => result(version + 1));
  assert.deepEqual(retried.getPending().pricing, pricing);
  assert.equal(await retried.flush(), true);
  retried.dispose();

  localStorage.setItem('pathome_lessor_unsynced_7_d1', JSON.stringify({ version: 1, pending: { pricing } }));
  const stale = new LessorAutosave(7, 'd1', 2, () => {}, () => {}, fail);
  assert.equal(stale.getStatus(), 'conflict');
  assert.equal(await stale.flush(), false);
  stale.dispose();
});

test('conflict preserves pending edits without silently replaying them', async () => {
  globalThis.localStorage = storage();
  let calls = 0;
  const queue = new LessorAutosave(7, 'd1', 1, () => {}, () => {}, async () => {
    calls++;
    throw { status: 409 };
  });
  queue.change('pricing', pricing);
  assert.equal(await queue.flush(), false);
  assert.equal(await queue.flush(), false);
  assert.equal(calls, 1);
  assert.deepEqual(queue.getPending().pricing, pricing);
  queue.change('basics', basics);
  assert.deepEqual(queue.getPending().basics, basics);
  assert.equal(JSON.parse(localStorage.getItem('pathome_lessor_unsynced_7_d1')).version, 1);
  queue.dispose();
});

test('guest retry keeps pending edits in memory without writing property data to localStorage', async () => {
  globalThis.localStorage = storage();
  let fail = true;
  const queue = new LessorAutosave(0, 'guest-draft', 1, () => {}, () => {},
    async (_id, _section, version) => {
      if (fail) throw new Error('offline');
      return result(version + 1);
    }, false);
  queue.change('pricing', pricing);
  assert.equal(await queue.flush(), false);
  assert.deepEqual(queue.getPending().pricing, pricing);
  assert.equal(localStorage.getItem('pathome_lessor_unsynced_0_guest-draft'), null);
  fail = false;
  assert.equal(await queue.flush(), true);
  assert.equal(queue.getStatus(), 'saved');
  queue.dispose();
});

test('logout clears pending user edits and dispose cannot restore the old buffer', () => {
  globalThis.localStorage = storage();
  const queue = new LessorAutosave(7, 'd1', 1, () => {}, () => {}, async () => result(2));
  queue.change('pricing', pricing);
  assert.ok(localStorage.getItem('pathome_lessor_unsynced_7_d1'));
  clearPersistedUser();
  queue.dispose();
  assert.equal(localStorage.getItem('pathome_lessor_unsynced_7_d1'), null);
});
