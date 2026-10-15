/**
 * P1-5 — a delayed lessor-contact reveal must never repopulate the panel.
 *
 * The race this reproduces: a reveal is requested for Request #501 at version 4, the response is held,
 * and the same record is then shown at version 5 (or under another account, or the panel unmounts)
 * before the answer arrives. Clearing the displayed value on a context change is not enough, because
 * the same record at a new version keeps the component mounted and its continuation then commits.
 *
 * The gate exercised here is the production `operationsContactReveal` unit that the panel uses; the
 * panel's wiring of it is verified in the targeted browser pass.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  beginContactReveal,
  contactRevealIsCurrent,
  createContactRevealGate,
  resetContactRevealGate
} from '../utils/operationsContactReveal.ts';

const CONTACT = {
  listingId: 8001,
  name: 'Rakesh Sharma',
  mobile: '+91 98260 11223',
  status: 'CONTACT_AVAILABLE'
};

const deferred = () => {
  let settle;
  let fail;
  const promise = new Promise((resolve, reject) => {
    settle = resolve;
    fail = reject;
  });
  return { promise, resolve: settle, reject: fail };
};

/**
 * A faithful model of the panel's decision logic: a live gate, a displayed value, and a commit step
 * that must re-check the gate in the same tick as the write.
 */
const createPanelModel = () => {
  const model = {
    gate: createContactRevealGate(),
    displayed: null,
    aborted: 0,
    /** Starts a reveal and records its ticket; the caller supplies the held transport. */
    reveal(contextKey) {
      const started = beginContactReveal(model.gate, contextKey);
      model.gate = started.gate;
      return {
        ticket: started.ticket,
        abort: () => { model.aborted += 1; },
        /** Applies an outcome exactly as the panel does: only while this reveal is still live. */
        commit(value) {
          if (!contactRevealIsCurrent(model.gate, started.ticket)) return false;
          model.displayed = value;
          return true;
        }
      };
    },
    /** A new root/version/identity/authority: clears what is shown and invalidates pending reveals. */
    changeContext(contextKey) {
      model.gate = resetContactRevealGate(model.gate, contextKey);
      model.displayed = null;
    }
  };
  return model;
};

test('a delayed reveal cannot repopulate the panel after the same record changes version', async () => {
  const panel = createPanelModel();
  const held = deferred();
  const pending = panel.reveal('account-41|owned|REQUEST-501|v4');
  const inFlight = held.promise.then(() => pending.commit(CONTACT));

  // The version changes while the read is in flight: same record, new context.
  panel.changeContext('account-41|owned|REQUEST-501|v5');
  held.resolve(CONTACT);
  assert.equal(await inFlight, false);
  assert.equal(panel.displayed, null, 'a late answer must not repopulate the panel');

  // A reveal in the new context is displayed normally.
  const fresh = panel.reveal('account-41|owned|REQUEST-501|v5');
  assert.equal(fresh.commit(CONTACT), true);
  assert.deepEqual(panel.displayed, CONTACT);
});

test('a delayed reveal cannot cross an account switch or an authority reset', () => {
  const panel = createPanelModel();
  const pending = panel.reveal('account-41|owned|SESSION-3108|v6');

  panel.changeContext('account-77|owned|SESSION-3108|v6');
  assert.equal(pending.commit(CONTACT), false);
  assert.equal(panel.displayed, null);

  // Losing the record entirely (unmount / authority withdrawn) also invalidates it.
  const second = panel.reveal('account-77|owned|SESSION-3108|v6');
  panel.changeContext(null);
  assert.equal(second.commit(CONTACT), false);
  assert.equal(panel.displayed, null);
  assert.equal(contactRevealIsCurrent(panel.gate, second.ticket), false);
});

test('a newer reveal supersedes an older one in the same context', async () => {
  const panel = createPanelModel();
  const older = panel.reveal('account-41|owned|SESSION-3108|v6');
  const newer = panel.reveal('account-41|owned|SESSION-3108|v6');

  assert.equal(newer.commit(CONTACT), true);
  // The older answer arrives afterwards and must be dropped rather than overwrite the newer reveal.
  assert.equal(older.commit({ ...CONTACT, name: 'Older Result' }), false);
  assert.equal(panel.displayed.name, 'Rakesh Sharma');
});

test('a late refusal is dropped too, so it cannot describe the wrong context', async () => {
  const panel = createPanelModel();
  const held = deferred();
  const pending = panel.reveal('account-41|owned|REQUEST-501|v4');
  const inFlight = held.promise.then(
    () => pending.commit(CONTACT),
    () => pending.commit({ kind: 'lost' })
  );

  panel.changeContext('account-41|owned|REQUEST-501|v5');
  held.reject(Object.assign(new Error('Forbidden'), { status: 403 }));
  await inFlight;
  assert.equal(panel.displayed, null);
});

test('an outstanding reveal that is still current commits, so fail-closed behaviour is preserved', () => {
  const panel = createPanelModel();
  const pending = panel.reveal('account-41|owned|REQUEST-501|v4');
  assert.equal(pending.commit(CONTACT), true);
  assert.deepEqual(panel.displayed, CONTACT);

  // Re-hiding the panel is an explicit reset: nothing pending may reappear.
  const second = panel.reveal('account-41|owned|REQUEST-501|v4');
  panel.changeContext('account-41|owned|REQUEST-501|v4');
  assert.equal(second.commit(CONTACT), false);
});

/* ------------------------------------------------------------------ *
 * R11 — hiding the document invalidates the reveal, before the transport is aborted
 * ------------------------------------------------------------------ */

test('hiding the document invalidates a pending reveal whose transport ignores abort', async () => {
  const panel = createPanelModel();
  const held = deferred();
  const pending = panel.reveal('account-41|owned|SESSION-3108|v6');

  /**
   * Exactly what the visibility handler does, in order: the generation is invalidated first, then the
   * transport is asked to abort. A transport that ignores that abort still cannot commit, because the
   * ticket it holds is dead by the time its answer arrives.
   */
  panel.changeContext(null);
  pending.abort();
  held.resolve(CONTACT);
  await held.promise;
  assert.equal(pending.commit(CONTACT), false);
  assert.equal(panel.displayed, null, 'a held reveal must not repopulate a hidden document');

  // Returning to the document needs a new deliberate reveal, which works normally.
  const afterReturn = panel.reveal('account-41|owned|SESSION-3108|v6');
  assert.equal(afterReturn.commit(CONTACT), true);
  assert.deepEqual(panel.displayed, CONTACT);
});

test('a displayed contact is dropped the moment the document is hidden', () => {
  const panel = createPanelModel();
  const shown = panel.reveal('account-41|owned|SESSION-3108|v6');
  assert.equal(shown.commit(CONTACT), true);
  assert.deepEqual(panel.displayed, CONTACT);

  // The visibility reset clears what is on screen and invalidates any ticket taken before it.
  panel.changeContext(null);
  assert.equal(panel.displayed, null);
  assert.equal(contactRevealIsCurrent(panel.gate, shown.ticket), false);
});
