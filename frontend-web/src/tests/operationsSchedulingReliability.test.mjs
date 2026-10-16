import test from 'node:test';
import assert from 'node:assert/strict';
import { installOperationsActionHarness } from './support/operationsActionHarness.mjs';
const h = await installOperationsActionHarness();
const pending = new Map();
globalThis.sessionStorage = {
  getItem: k => pending.get(k) ?? null, setItem: (k, v) => pending.set(k, v), removeItem: k => pending.delete(k),
  key: i => [...pending.keys()][i] ?? null, get length() { return pending.size; }
};
const intents = await import('../utils/operationsSchedulingIntent.ts');
const account = { id: 91, role: 'EMPLOYEE', token: 'reliability-account-a' };
const body = { expectedSessionVersion: 3, groundExecutiveUserId: 82, scheduledAt: '2027-02-14T05:00:00Z', zoneId: 'Asia/Kolkata', overrideReason: null };
const start = () => { pending.clear(); h.signIn(account); return h.session.captureOperationsSession(); };

test('one logical intent keeps its ID across retries and fresh module state and refuses a changed payload', async () => {
  const session = start();
  const a = intents.preserveSchedulingIntent(session, 17, body);
  assert.match(a.commandId, /^[0-9a-f-]{36}$/);
  assert.deepEqual(intents.preserveSchedulingIntent(session, 17, body), a);
  const reloaded = await import('../utils/operationsSchedulingIntent.ts?remount');
  assert.deepEqual(reloaded.readSchedulingIntent(session, 17), a);
  assert.throws(() => intents.preserveSchedulingIntent(session, 17, { ...body, groundExecutiveUserId: 83 }), /still unconfirmed/);
  assert.equal(pending.size, 1);
  assert.equal([...pending.values()].join('').includes(account.token), false);
});
test('logout/account change clears intent and late completion cannot clear the new account intent', () => {
  const a = start(); const first = intents.preserveSchedulingIntent(a, 17, body);
  h.signOut(); assert.equal(pending.size, 0);
  h.signIn({ id: 92, role: 'EMPLOYEE', token: 'reliability-account-b' });
  const b = h.session.captureOperationsSession(); const next = intents.preserveSchedulingIntent(b, 17, body);
  intents.forgetSchedulingIntent(a, 17, first.commandId);
  assert.equal(intents.readSchedulingIntent(b, 17).commandId, next.commandId);
  assert.equal(intents.readSchedulingIntent(a, 17), null);
});
test('unavailable persistent intent storage refuses transmission rather than creating an untracked command', () => {
  const session = start(); const original = sessionStorage.setItem;
  sessionStorage.setItem = () => { throw Error('Quota exceeded'); };
  try { assert.throws(() => intents.preserveSchedulingIntent(session, 17, body), /Quota/); }
  finally { sessionStorage.setItem = original; }
  assert.equal(pending.size, 0);
});
test('NOT_RECORDED is a scoped GET observation; no retry POST is dispatched', async () => {
  const session = start(); const intent = intents.preserveSchedulingIntent(session, 17, body);
  h.reset(() => h.json(200, { commandId: intent.commandId, state: 'NOT_RECORDED', approval: null }));
  assert.equal(await h.service.schedulingCommand(17, intent, { session }), null);
  assert.equal(h.calls.length, 1); assert.equal(h.calls[0].method, 'GET'); assert.equal(h.calls[0].cache, 'no-store');
  assert.equal(intents.readSchedulingIntent(session, 17).commandId, intent.commandId);
});
test('a command read completed after account switch cannot confirm the replacement account', async () => {
  const session = start(); const intent = intents.preserveSchedulingIntent(session, 17, body); const response = h.deferred();
  h.reset(() => response.promise);
  const reading = h.service.schedulingCommand(17, intent, { session });
  h.signIn({ id: 92, role: 'EMPLOYEE', token: 'reliability-account-b' });
  response.resolve(h.json(200, { commandId: intent.commandId, state: 'NOT_RECORDED', approval: null }));
  await assert.rejects(reading, { name: 'OperationsSessionChangedError' });
});
