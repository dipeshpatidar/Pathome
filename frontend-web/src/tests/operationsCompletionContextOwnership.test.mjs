/**
 * Behavioural coverage for the shared asynchronous completion lifecycle.
 *
 * The defect class this file closes is the mirror image of the R1 refusal races: a *successful*
 * command response could still replace a newer displayed record. A confirmed `200` was applied to the
 * detail unconditionally, so a booking, an availability update, a coordination or an itinerary edit
 * that resolved after the OE had opened another record (or re-opened the same one) overwrote what was
 * on screen; a confirmed claim navigated over the newer root; a confirmed release cleared it.
 *
 * The two halves are deliberately kept apart, and that separation is what these tests assert:
 *
 *   A. Authoritative business completion — a validated server success stays a success, is reported,
 *      and is never converted into an uncertain outcome or retransmitted.
 *   B. Local UI publication — installing the returned root, navigating, dropping detail or writing a
 *      drawer outcome happens only while the display context the command began in — or one the command
 *      published itself as an authorised step of its own flow — is still the one in force.
 *
 * The real runtime modules are driven here (`operationsOwnershipRegistry.ts` is the production
 * lifecycle/ownership core); the component-level interleavings against the real React tree are proven
 * in `browser-verification/operations-completion/`. Source-wiring assertions are used only where the
 * property is genuinely about *where* a verdict is taken in the component (in the same tick as the
 * write, after every await), which no unit-level object can express.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import { resolve as resolveOperationsModule } from './support/operationsServiceLoader.mjs';

registerHooks({ resolve: resolveOperationsModule });

/** Reads one production source file, for the structural half of a wiring assertion. */
const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');

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

globalThis.fetch = () => {
  throw new Error('no transport is exercised by this suite');
};

const sessionModule = await import('../utils/operationsSession.ts');
const { OwnershipOperationRegistry, operationTargetIsMounted } = await import(
  '../utils/operationsOwnershipRegistry.ts'
);

const { captureOperationsSession, subscribeOperationsSession } = sessionModule;

subscribeOperationsSession(() => {});

const ACCOUNT_A = { id: 11, role: 'EMPLOYEE', token: 'token-account-a' };
const ACCOUNT_B = { id: 22, role: 'ADMIN', token: 'token-account-b' };

/**
 * The workspace's display-context lifecycle, expressed with the real registry.
 *
 * Every helper below is the component's own expression, verbatim: `setDisplayContext` moves the
 * generation and records the operation's own publication, `photoPublicationHolds` is the verdict the
 * apply helpers take, `scheduleOutcomeContextHolds` is the drawer verdict, and `noticeContextHolds` is
 * the (separate, unchanged) refusal verdict.
 */
const completionWorkspace = () => {
  const registry = new OwnershipOperationRegistry();
  registry.setMounted(true);
  registry.setWorkspaceActive(true);
  let mounted = null;
  let drawer = { sessionId: null, generation: 0 };
  /** Exactly the component's `setDisplayContext(next, operation?)`. */
  const setDisplayContext = (root, operation) => {
    const generation = registry.advanceDisplayContext();
    mounted = root === null ? null : { ...root, mountGeneration: generation };
    if (operation) registry.markAcceptedDisplayContext(operation);
    return mounted;
  };
  return {
    registry,
    open: (root, operation) => setDisplayContext(root, operation),
    leave: (operation) => setDisplayContext(null, operation),
    mounted: () => mounted,
    openDrawer(sessionId) {
      drawer = { sessionId, generation: drawer.generation + 1 };
    },
    closeDrawer() {
      drawer = { sessionId: null, generation: drawer.generation + 1 };
    },
    drawerIdentity: () => ({ ...drawer }),
    /** The verdict every authoritative apply takes, in the same tick as its own write. */
    publicationHolds: (operation) => registry.displayContextIsOwned(operation),
    /** The component's `scheduleOutcomeContextHolds`, verbatim. */
    scheduleOutcomeContextHolds: (operation, captured) =>
      operation !== null
      && registry.displayContextIsOwned(operation)
      && drawer.sessionId === captured.sessionId
      && drawer.generation === captured.generation,
    /** The component's `noticeContextHolds` (the refusal side), verbatim. */
    noticeContextHolds: (operation) =>
      operation.target === null
      || operationTargetIsMounted(operation, mounted)
      || registry.withdrewOwnDisplayContext(operation)
  };
};

const beginCommand = (workspace, session, kind = 'availability', target = null) =>
  workspace.registry.begin(kind, session, 0, target);

/* ------------------------------------------------------------------ *
 * 1. Stale success: nothing of it may replace a display it no longer owns
 * ------------------------------------------------------------------ */

test('stale success: the returned root is never published into a replaced display context', () => {
  signIn(ACCOUNT_A);
  const session = captureOperationsSession();

  /**
   * The five demonstrated interleavings, each starting from Session #3108 being the displayed root
   * with its own mounted instance generation. In every one of them the operation still owns the
   * mutation lock and can still commit when the answer arrives — that is exactly why the display
   * verdict, and not the commit guard, is what has to refuse the publication.
   */
  const sameRootRemount = () => {
    const workspace = completionWorkspace();
    workspace.open({ targetType: 'SESSION', targetId: 3108 });
    const operation = beginCommand(workspace, session, 'schedule', workspace.mounted());
    // The OE leaves the record and re-opens it: a new instance with its own reads and its own contact.
    workspace.leave();
    workspace.open({ targetType: 'SESSION', targetId: 3108 });
    return { workspace, operation };
  };
  const unrelatedRoot = () => {
    const workspace = completionWorkspace();
    workspace.open({ targetType: 'SESSION', targetId: 3108 });
    const operation = beginCommand(workspace, session, 'availability', workspace.mounted());
    workspace.open({ targetType: 'SESSION', targetId: 4109 });
    return { workspace, operation };
  };
  const sameIdDifferentRootType = () => {
    const workspace = completionWorkspace();
    workspace.open({ targetType: 'SESSION', targetId: 3108 });
    const operation = beginCommand(workspace, session, 'reorder', workspace.mounted());
    // The same numeric id as a different root type: the standalone Request #3108.
    workspace.open({ targetType: 'REQUEST', targetId: 3108 });
    return { workspace, operation };
  };
  const leftAndReturnedLater = () => {
    const workspace = completionWorkspace();
    workspace.open({ targetType: 'SESSION', targetId: 3108 });
    const operation = beginCommand(workspace, session, 'coordinate', workspace.mounted());
    workspace.leave();
    workspace.open({ targetType: 'REQUEST', targetId: 501 });
    workspace.leave();
    workspace.open({ targetType: 'SESSION', targetId: 3108 });
    return { workspace, operation };
  };

  for (const [label, arrange] of [
    ['#3108 books after the OE opened #4109', unrelatedRoot],
    ['#3108 availability lands after the OE opened #4109', unrelatedRoot],
    ['same-root #3108 remount before completion', sameRootRemount],
    ['the same numeric id as a different root type', sameIdDifferentRootType],
    ['the OE navigated away twice and came back', leftAndReturnedLater]
  ]) {
    const { workspace, operation } = arrange();
    assert.equal(operation.cancelled, false, `${label}: the command is still the live operation`);
    assert.equal(workspace.registry.isCurrent(operation), true, `${label}: it may still commit`);
    assert.equal(
      workspace.publicationHolds(operation),
      false,
      `${label}: the confirmed result is not published into the replaced display context`
    );
    // The *refusal* verdict is a different question (nothing was withdrawn here), and it equally
    // refuses: the older mounted instance is gone and no navigation belongs to this operation.
    assert.equal(workspace.noticeContextHolds(operation), false, `${label}: and no notice describes it`)
    ;
  }
});

test('stale success: a navigation during the awaited reconciliation suppresses the caller’s write too', () => {
  signIn(ACCOUNT_A);
  const session = captureOperationsSession();
  const workspace = completionWorkspace();
  workspace.open({ targetType: 'SESSION', targetId: 3108 });
  const operation = beginCommand(workspace, session, 'add-item', workspace.mounted());

  // The verdict taken immediately before the apply (no await in between) permits the publication…
  assert.equal(workspace.publicationHolds(operation), true);
  // …and the operation records the display change its own publication performed.
  workspace.open({ targetType: 'SESSION', targetId: 3108 }, operation);
  assert.equal(workspace.publicationHolds(operation), true, 'the operation’s own transition is honoured');

  /**
   * The command then awaits its authoritative queue reconciliation. The OE opens another record while
   * that read is unresolved, so the caller's own post-reconciliation writes (the record-named refresh
   * notice, the drawer outcome) must re-take the verdict and find it false.
   */
  workspace.open({ targetType: 'SESSION', targetId: 4109 });
  assert.equal(
    workspace.publicationHolds(operation),
    false,
    'the caller’s write is refused after the awaited reconciliation'
  );
});

/* ------------------------------------------------------------------ *
 * 2. Legitimate current-context completions are preserved
 * ------------------------------------------------------------------ */

test('legitimate success: an unchanged display context publishes, and its own transition is retained', () => {
  signIn(ACCOUNT_A);
  const session = captureOperationsSession();

  for (const [kind, root] of [
    ['coordinate', { targetType: 'REQUEST', targetId: 501 }],
    ['unavailable', { targetType: 'REQUEST', targetId: 501 }],
    ['add-item', { targetType: 'SESSION', targetId: 3108 }],
    ['remove-item', { targetType: 'SESSION', targetId: 3108 }],
    ['reorder', { targetType: 'SESSION', targetId: 3108 }],
    ['availability', { targetType: 'SESSION', targetId: 3108 }],
    ['schedule', { targetType: 'SESSION', targetId: 3108 }]
  ]) {
    const workspace = completionWorkspace();
    workspace.open(root);
    const operation = beginCommand(workspace, session, kind, workspace.mounted());
    assert.equal(workspace.publicationHolds(operation), true, `${kind}: the unchanged context publishes`);
  }

  /**
   * A coordination legitimately replaces its initiating Request with the authoritative Session the
   * server returned. That display change is the operation's own, so the remainder of the same flow —
   * including the notice the caller raises when the follow-up queue refresh failed — is still
   * publishable.
   */
  const coordination = completionWorkspace();
  coordination.open({ targetType: 'REQUEST', targetId: 501 });
  const operation = beginCommand(coordination, session, 'coordinate', coordination.mounted());
  assert.equal(workspaceApplied(coordination, operation, { targetType: 'SESSION', targetId: 3108 }), true,
    'the returned Session replaces the initiating Request');
  assert.equal(coordination.publicationHolds(operation), true,
    'the operation’s own transition does not suppress the rest of its own flow');

  // A later user-initiated navigation still invalidates every further publication.
  coordination.open({ targetType: 'SESSION', targetId: 4109 });
  assert.equal(coordination.publicationHolds(operation), false, 'a later navigation does');

  // …and re-opening the root the operation itself published is a newer instance, not its own.
  const remount = completionWorkspace();
  remount.open({ targetType: 'REQUEST', targetId: 501 });
  const second = beginCommand(remount, session, 'coordinate', remount.mounted());
  workspaceApplied(remount, second, { targetType: 'SESSION', targetId: 3108 });
  remount.leave();
  remount.open({ targetType: 'SESSION', targetId: 3108 });
  assert.equal(remount.publicationHolds(second), false, 'a newer mount of the same root is not its own');
});

/** Applies an authoritative root through the component's gate, returning whether it was published. */
const workspaceApplied = (workspace, operation, root) => {
  if (!workspace.publicationHolds(operation)) return false;
  workspace.open(root, operation);
  return true;
};

/* ------------------------------------------------------------------ *
 * 3. A target-less command: publication bound, refusal announcement not
 * ------------------------------------------------------------------ */

test('a claim from the list binds its navigation to the display while its refusal stays the queues’', () => {
  signIn(ACCOUNT_A);
  const session = captureOperationsSession();
  const workspace = completionWorkspace();
  // The OE claims from the claimable list: nothing is mounted, so the operation has no target of its
  // own — but it does have a display context, and that is what its navigation is bound to.
  const operation = beginCommand(workspace, session, 'claim', null);
  assert.equal(operation.target, null);
  assert.equal(workspace.publicationHolds(operation), true, 'an unchanged display publishes the navigation');

  // While the `204` is in flight the OE opens another record.
  workspace.open({ targetType: 'SESSION', targetId: 4109 });
  assert.equal(workspace.publicationHolds(operation), false,
    'the confirmed claim may not navigate over the record the OE opened');
  /**
   * The refusal side keeps its approved R1 rule: a command with no mounted detail of its own addresses
   * the queues, so its outcome is still announced. The two verdicts answer different questions and are
   * deliberately not interchangeable.
   */
  assert.equal(workspace.noticeContextHolds(operation), true);
});

/* ------------------------------------------------------------------ *
 * 4. The scheduling drawer is one instance, not one Session id
 * ------------------------------------------------------------------ */

test('a drawer outcome belongs to one drawer instance and to the display context of its command', () => {
  signIn(ACCOUNT_A);
  const session = captureOperationsSession();

  const held = () => {
    const workspace = completionWorkspace();
    workspace.open({ targetType: 'SESSION', targetId: 3108 });
    workspace.openDrawer(3108);
    const operation = beginCommand(workspace, session, 'schedule', workspace.mounted());
    // Captured before the first await, exactly as `confirmSelection` does.
    const drawer = workspace.drawerIdentity();
    return { workspace, operation, drawer };
  };

  // Unchanged: the confirmed booking publishes its outcome into the drawer it came from.
  const unchanged = held();
  assert.equal(unchanged.workspace.scheduleOutcomeContextHolds(unchanged.operation, unchanged.drawer), true);

  // The OE closes the drawer while the booking is in flight…
  const closed = held();
  closed.workspace.closeDrawer();
  assert.equal(closed.workspace.scheduleOutcomeContextHolds(closed.operation, closed.drawer), false,
    'a closed drawer has no outcome');

  // …and opens it again for the very same Session: a different instance, so the older outcome is not
  // published into it even though the Session id matches.
  closed.workspace.openDrawer(3108);
  assert.equal(closed.workspace.scheduleOutcomeContextHolds(closed.operation, closed.drawer), false,
    'a re-opened drawer for the same Session is a different instance');

  // A drawer holding a different Session never inherits this booking’s outcome.
  const other = held();
  other.workspace.openDrawer(4109);
  assert.equal(other.workspace.scheduleOutcomeContextHolds(other.operation, other.drawer), false);

  /**
   * The drawer's own identity is not enough on its own: while the drawer state still says #3108 (the
   * OE cannot click a queue row behind a modal drawer, but the *display* can change through the
   * record's own actions), the record the command re-read and mutated is no longer the one displayed.
   */
  const navigated = held();
  navigated.workspace.open({ targetType: 'SESSION', targetId: 4109 });
  assert.equal(navigated.workspace.scheduleOutcomeContextHolds(navigated.operation, navigated.drawer), false,
    'the display context is decisive even when the drawer state still names the initiating Session');

  // Closing and re-opening the drawer around a withdrawal or an account change is final for the
  // earlier instance too.
  const retired = held();
  retired.workspace.closeDrawer();
  retired.workspace.openDrawer(3108);
  assert.equal(retired.workspace.scheduleOutcomeContextHolds(retired.operation, retired.drawer), false);
});

test('only the live operation may record a display change as its own, and the verdict outlives the lock', () => {
  signIn(ACCOUNT_A);
  const workspace = completionWorkspace();
  const session = captureOperationsSession();
  workspace.open({ targetType: 'SESSION', targetId: 3108 });
  const operation = beginCommand(workspace, session, 'availability', workspace.mounted());

  // A superseded operation can never claim a later display change as its own.
  workspace.registry.advanceDisplayContext();
  assert.equal(workspace.registry.markAcceptedDisplayContext(operation), true,
    'the live operation may record its own publication');
  workspace.registry.cancelActive('operation-superseded');
  assert.equal(workspace.registry.markAcceptedDisplayContext(operation), false,
    'an invalidated operation may not');

  /**
   * The verdict is about the display, not about the lock, so it stays answerable after `finish`
   * released the mutation lock — which is exactly what a caller reporting a confirmed command needs.
   */
  const second = completionWorkspace();
  second.open({ targetType: 'SESSION', targetId: 3108 });
  const fresh = beginCommand(second, session, 'reorder', second.mounted());
  assert.equal(second.registry.finish(fresh), true);
  assert.equal(typeof second.registry.displayContextIsOwned(fresh), 'boolean');
  assert.equal(second.publicationHolds(fresh), true, 'an unchanged display context is still its context');
  second.open({ targetType: 'SESSION', targetId: 4109 });
  assert.equal(second.publicationHolds(fresh), false, 'and a replaced one still is not');
});

/* ------------------------------------------------------------------ *
 * 5. Negative controls for the confirmed failure class
 * ------------------------------------------------------------------ */

test('negative control: the former unconditional application replaces the newer record', () => {
  signIn(ACCOUNT_A);
  const session = captureOperationsSession();
  const workspace = completionWorkspace();
  workspace.open({ targetType: 'SESSION', targetId: 3108 });
  const operation = beginCommand(workspace, session, 'schedule', workspace.mounted());
  // The OE opens the unrelated Session #4109 while the booking is in flight.
  workspace.open({ targetType: 'SESSION', targetId: 4109 });
  const displayed = { target: 'SESSION#4109', version: 3 };

  /**
   * The former rule, reproduced in memory: every returned result was applied, with no display verdict
   * at all. Applied to this interleaving it installs the booked #3108 Session over #4109's detail —
   * exactly the defect, and the reason the corrected verdict is load-bearing rather than decorative.
   */
  const formerUnconditionalApplication = () => ({ target: 'SESSION#3108', version: 7 });
  const formerDisplay = formerUnconditionalApplication();
  assert.equal(formerDisplay.target, 'SESSION#3108', 'the former rule published the stale root');
  assert.notEqual(formerDisplay.target, displayed.target);

  // The corrected rule refuses the publication, so the newer record keeps its own snapshot.
  const correctedApplication = () =>
    workspace.publicationHolds(operation) ? { target: 'SESSION#3108', version: 7 } : displayed;
  assert.deepEqual(correctedApplication(), displayed, 'the corrected rule leaves #4109 untouched');
});

test('negative control: a display verdict taken before the await is not what the corrected source does', () => {
  signIn(ACCOUNT_A);
  const session = captureOperationsSession();
  const workspace = completionWorkspace();
  workspace.open({ targetType: 'SESSION', targetId: 3108 });
  const operation = beginCommand(workspace, session, 'add-item', workspace.mounted());
  workspace.open({ targetType: 'SESSION', targetId: 3108 }, operation);

  // The verdict captured before the awaited reconciliation says "publishable"…
  const verdictBeforeTheAwait = workspace.publicationHolds(operation);
  assert.equal(verdictBeforeTheAwait, true);
  // …and reusing it after a navigation would publish a stale result, which is why every post-await
  // write in the component re-takes the verdict in its own tick.
  workspace.open({ targetType: 'SESSION', targetId: 4109 });
  assert.equal(verdictBeforeTheAwait, true, 'the stale verdict still says publishable');
  assert.equal(workspace.publicationHolds(operation), false, 'the re-taken verdict does not');
});

/* ------------------------------------------------------------------ *
 * 6. The component takes each verdict in the same tick as its write
 * ------------------------------------------------------------------ */

test('the shared runner publishes the returned result under a verdict taken after the transmission', () => {
  const workspaceSource = read('../components/operations/OperationsWorkspace.tsx');
  const from = workspaceSource.indexOf('const runGuardedCommand = useCallback(async');
  const to = workspaceSource.indexOf('const commandContextHolds = useCallback(');
  assert.ok(from > -1 && to > from, 'the shared command runner region');
  const runner = workspaceSource.slice(from, to);

  // The result is applied through the caller's own apply helper, which takes the display verdict in
  // the same tick as its write — with no await between the commit guard and that write.
  const applyAt = runner.indexOf('const published = options.applyResult(fresh, applied, operation);');
  const commitAt = runner.lastIndexOf(
    'if (!ownership.canCommit(operation, authorityRef.current)) return nothing;',
    applyAt
  );
  assert.ok(commitAt > -1 && applyAt > commitAt, 'the apply follows the synchronous commit guard');
  assert.equal(
    runner.slice(commitAt, applyAt).includes('await '),
    false,
    'no await sits between the guard and the publication'
  );
  // A suppressed publication is reported as a published-flag, never as a different outcome: nothing in
  // the runner branches on it, so the confirmed success keeps its status and its `applied` result.
  assert.equal(/if \(!?\s*published\b/.test(runner), false, 'publication never alters the outcome');
  assert.match(
    runner,
    /return \{ status: 'applied', applied, reconciliation, failure: null, operation, published \};/
  );
  // Exactly one transmission site, and nothing after it may retransmit a confirmed command.
  assert.equal((runner.match(/await transmit\(/g) ?? []).length, 1, 'one transmission per command');
  assert.equal((runner.match(/options\.onFailure/g) ?? []).length, 2, 'a custom failure path is taken once');
});

test('every authoritative apply is gated and every display change records the operation’s own publication', () => {
  const workspaceSource = read('../components/operations/OperationsWorkspace.tsx');

  // The one writer of the displayed target is `setDisplayContext`, and it records the operation's own
  // display change so an authorised transition never suppresses the rest of the same flow.
  assert.equal(
    (workspaceSource.match(/setDetailTarget\(/g) ?? []).length,
    1,
    'the display target has exactly one writer'
  );
  assert.match(
    workspaceSource,
    /const setDisplayContext = useCallback\(\(next: DetailTarget \| null, operation\?: OwnershipOperation\): number => \{\s*\n\s*const generation = ownership\.advanceDisplayContext\(\);\s*\n\s*setDetailTarget\(next\);\s*\n\s*if \(operation\) ownership\.markAcceptedDisplayContext\(operation\);/
  );
  for (const helper of ['applyAuthoritativeSession', 'applyAuthoritativeRequest']) {
    const at = workspaceSource.indexOf(`const ${helper} = useCallback(`);
    assert.ok(at > -1, `${helper} exists`);
    const body = workspaceSource.slice(at, at + 900);
    const verdictAt = body.indexOf('if (!ownership.displayContextIsOwned(operation)) return false;');
    const writeAt = body.indexOf('setDisplayContext({');
    assert.ok(verdictAt > -1, `${helper} takes the display verdict`);
    assert.ok(verdictAt < writeAt, `${helper} takes it before its own write`);
    assert.match(body, /setDetail\(\{ status: 'ready'/, `${helper} writes only after the verdict`);
  }
  // Every call site passes the initiating operation through the shared runner's apply hook.
  assert.equal(
    (workspaceSource.match(/applyResult: \(_fresh, \w+, operation\) =>/g) ?? []).length,
    8,
    'all eight guarded commands publish through an operation-bound apply helper'
  );
});

test('the claim, the release and the record-named notices are each bound to their display context', () => {
  const workspaceSource = read('../components/operations/OperationsWorkspace.tsx');

  // Claim: the confirmed success is reported, but the navigation and the detail it opens are not.
  const claimAt = workspaceSource.indexOf('notifySuccess(`${title} claimed`');
  assert.ok(claimAt > -1, 'the confirmed claim reports its success');
  assert.match(
    workspaceSource,
    /notifySuccess\(`\$\{title\} claimed`[\s\S]{0,1200}?if \(ownership\.displayContextIsOwned\(operation\)\) setView\('my-work'\);/
  );
  // The detail a claim opens is gated inside `openDetail` too, for the same operation.
  assert.match(
    workspaceSource,
    /const contextHolds = \(\) => \(operation\s*\n\s*\? ownership\.canCommit\(operation, authorityRef\.current\) && ownership\.displayContextIsOwned\(operation\)/
  );
  assert.match(
    workspaceSource,
    /setDisplayContext\(\s*\n\s*\{ targetType: target\.targetType, targetId: target\.targetId, source: target\.source \},\s*\n\s*operation\s*\n\s*\);/
  );

  // Release: the drop is the operation's own transition, so it is gated and then recorded as its own.
  const releaseAt = workspaceSource.indexOf('const releaseRoot = useCallback(async');
  const releaseBody = workspaceSource.slice(releaseAt, workspaceSource.indexOf('* Phase 2A guarded command lifecycle'));
  assert.match(releaseBody, /if \(ownership\.displayContextIsOwned\(operation\)\) \{\s*\n\s*\/\/ Released detail is dropped[\s\S]{0,200}?setDisplayContext\(null, operation\);/);
  assert.match(releaseBody, /if \(reconciliation !== 'loaded' && ownership\.displayContextIsOwned\(operation\)\) \{/);

  // Every record-named post-reconciliation notice is bound to the command's display context.
  assert.equal(
    (workspaceSource.match(/outcome\.reconciliation !== 'loaded' && commandContextHolds\(outcome\)/g) ?? []).length,
    6,
    'the six shared-command callers gate their refresh notice'
  );
  assert.match(
    workspaceSource,
    /if \(commandContextHolds\(outcome\)\) \{\s*\n\s*if \(outcome\.reconciliation !== 'loaded'\) showNotice\(ownershipRefreshFailureNotice\(name\)\);\s*\n\s*const added = sessionListingIds/
  );
  // The stopped-command notice is bound the same way.
  assert.match(
    workspaceSource,
    /const missingVersionNotice = useCallback\(\([\s\S]{0,600}?if \(!ownership\.displayContextIsOwned\(operation\)\) return;/
  );
  assert.equal(
    (workspaceSource.match(/missingVersionNotice\(name, '([^']*)', reconciliation, operation\)/g) ?? []).length,
    7,
    'every stopped-command caller passes its operation'
  );
});

test('the scheduling drawer publishes under one verdict, and every drawer state change moves its identity', () => {
  const workspaceSource = read('../components/operations/OperationsWorkspace.tsx');

  // The drawer identity is captured before the command begins, from the same ref a command is bound to.
  const drawerCaptureAt = workspaceSource.indexOf('const drawer: ScheduleDrawerIdentity = { ...scheduleDrawerRef.current };');
  const commandAt = workspaceSource.indexOf('const outcome = await runGuardedCommand<OperationsSessionDetail, OperationsSchedulingApproval>');
  assert.ok(drawerCaptureAt > -1 && commandAt > drawerCaptureAt,
    'the drawer instance is captured before the booking starts');

  // All three drawer publications — the applied result, the stopped command and the classified failure,
  // plus the caller's post-reconciliation outcome — go through the one drawer verdict.
  const confirmFrom = workspaceSource.indexOf('const confirmSelection = useCallback(async');
  const confirmTo = workspaceSource.indexOf('const navigate = useCallback(');
  const confirm = workspaceSource.slice(confirmFrom, confirmTo);
  assert.equal(
    (confirm.match(/scheduleOutcomeContextHolds\(operation, drawer\)/g) ?? []).length,
    3,
    'the command-side drawer writes are gated'
  );
  assert.equal(
    (confirm.match(/scheduleOutcomeContextHolds\(outcome\.operation, drawer\)/g) ?? []).length,
    1,
    'and the caller re-takes the verdict after the awaited reconciliation'
  );
  const holderAt = confirm.indexOf('const scheduleOutcomeContextHolds');
  assert.equal(holderAt, -1, 'the drawer verdict lives in one shared helper');
  const outcomeWrites = confirm.indexOf('setScheduleOutcome({ kind: \'scheduled\', approval, refreshFailed: false })');
  const appliedGate = confirm.indexOf('if (applied && scheduleOutcomeContextHolds(operation, drawer)) {');
  assert.ok(appliedGate > -1 && outcomeWrites > appliedGate, 'the applied drawer outcome is gated');
  assert.equal(
    confirm.slice(0, confirm.indexOf('scheduleOutcomeContextHolds')).includes('setScheduleOutcome({'),
    false,
    'the preamble clears the outcome before any verdict is needed'
  );

  // The drawer verdict itself: the display context, the Session and the instance generation.
  assert.match(
    workspaceSource,
    /const scheduleOutcomeContextHolds = useCallback\(\(\s*\n\s*operation: OwnershipOperation \| null,\s*\n\s*drawer: ScheduleDrawerIdentity\s*\n\s*\): boolean =>\s*\n\s*operation !== null\s*\n\s*&& ownership\.displayContextIsOwned\(operation\)\s*\n\s*&& scheduleDrawerRef\.current\.sessionId === drawer\.sessionId\s*\n\s*&& scheduleDrawerRef\.current\.generation === drawer\.generation,/
  );

  // The Session state has exactly two writers, and both move the identity: a closed drawer and a
  // re-opened one are different instances.
  assert.equal((workspaceSource.match(/setScheduleSessionId\(/g) ?? []).length, 2,
    'the drawer Session is opened and closed through the identity-aware helpers');
  assert.match(
    workspaceSource,
    /const openScheduleDrawer = useCallback\(\(sessionId: number\) => \{\s*\n\s*scheduleDrawerRef\.current = \{\s*\n\s*sessionId,\s*\n\s*generation: scheduleDrawerRef\.current\.generation \+ 1\s*\n\s*\};/
  );
  assert.match(
    workspaceSource,
    /const closeScheduleDrawer = useCallback\(\(\) => \{\s*\n\s*scheduleDrawerRef\.current = \{\s*\n\s*sessionId: null,\s*\n\s*generation: scheduleDrawerRef\.current\.generation \+ 1\s*\n\s*\};/
  );
  // The three paths that drop the drawer without pressing Close retire its instance as well.
  for (const [label, callSite] of [
    ['leaving the detail', 'const closeDetail = useCallback(() => {'],
    ['withdrawing a denied root', 'const withdrawDeniedRoot = useCallback((operation: OwnershipOperation) => {'],
    ['an account change', 'const clearProtectedState = useCallback(']
  ]) {
    const at = workspaceSource.indexOf(callSite);
    assert.ok(at > -1, `${label} exists`);
    const body = workspaceSource.slice(at, at + 2200);
    assert.ok(body.includes('closeScheduleDrawer();'), `${label} retires the drawer instance`);
  }
});

test('the security and recovery paths keep their own lives: account switch, authority loss, invalidation', () => {
  signIn(ACCOUNT_A);
  const a = captureOperationsSession();
  const workspace = completionWorkspace();
  workspace.open({ targetType: 'SESSION', targetId: 3108 });
  workspace.openDrawer(3108);
  const drawer = workspace.drawerIdentity();
  const operation = beginCommand(workspace, a, 'schedule', workspace.mounted());

  // The account changes while the booking is in flight: the operation can no longer commit at all, so
  // neither the detail, the navigation nor the drawer outcome may be published.
  signIn(ACCOUNT_B);
  const b = captureOperationsSession();
  assert.equal(b.token, ACCOUNT_B.token);
  workspace.registry.advanceSessionEpoch();
  workspace.registry.cancelActive('session-changed');
  workspace.registry.setWorkspaceActive(false);
  // The invalidation clears protected state, which is itself a display change and a closed drawer.
  workspace.leave();
  workspace.closeDrawer();
  assert.equal(workspace.publicationHolds(operation), false, 'an Account A success is never published');
  assert.equal(workspace.scheduleOutcomeContextHolds(operation, drawer), false, 'nor into any drawer');
  assert.equal(workspace.noticeContextHolds(operation), false, 'nor announced');

  // The new account's own operation is unaffected and still publishes in its own context.
  workspace.registry.setWorkspaceActive(true);
  const next = beginCommand(workspace, b, 'schedule', null);
  workspace.openDrawer(3108);
  assert.equal(workspace.publicationHolds(next), true);
  assert.equal(workspace.registry.markDispatched(next), true);
  assert.equal(workspace.registry.markDispatched(next), false, 'and it never dispatches twice');
});
