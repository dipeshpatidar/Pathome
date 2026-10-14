/**
 * Package 2 corrections — commit-phase entry ownership and committed-fact revalidation under React 18
 * concurrent rendering.
 *
 * Every other browser harness in this directory drives the built application through ordinary
 * navigation, which can never exercise "a render React starts and then throws away". This harness does
 * exactly that, with the real client renderer:
 *
 *   - the **real** `useOperationsCapability` hook (the production module is bundled unmodified),
 *   - the **real** React 18 `react-dom/client` concurrent renderer,
 *   - a `startTransition` update that suspends, so React renders the new entry's tree in the background
 *     and never commits it while the suspended promise is unresolved,
 *   - a controlled in-page `fetch` that holds the capability transport open indefinitely and records
 *     whether it was aborted.
 *
 * Scenario 1 (discarded render preserves A): a speculative transition to entry B is left suspended.
 * The committed entry must stay A, A's live verification must not be aborted or retired, and A must
 * still be admitted, with no protected read before its verdict.
 *
 * Scenario 2 (committed B retires A): the same transition is allowed to commit. A's obsolete entry
 * check is retired, B gets its own verification, B is admitted only by that verification, and A's late
 * grant authorises nothing in B.
 *
 * Scenario 3 (a discarded *exit* render retains the committed entry's focus revalidation): entry A is
 * committed, admitted and visible; a concurrent transition to the closed Operations surface renders and
 * then suspends forever. A real `focus` event on the page must still re-verify the *committed* entry A,
 * the denial it produces must withdraw A's protected content and close the dispatch gate, and the
 * discarded render must not have changed which surface the lifecycle belongs to. This is the check that
 * fails when the hook derives revalidation eligibility from the requested prop instead of the committed
 * entry.
 *
 * Scenario 4 (the same through a real `visibilitychange` event on the document).
 *
 * Scenario 5 (a committed exit stops workspace revalidation): once the closed surface really commits,
 * the obsolete entry authority is retired, the closed surface owns no cadence and performs no protected
 * read, a granted-but-closed workspace is no longer polled from activity events, and re-entering requires
 * a fresh entry-scoped verification before any protected read.
 *
 * Scenario 6 (a speculative *open* cannot activate a closed workspace): while a closed, verified surface
 * is committed, an Operations entry renders and suspends. That speculative render must not establish
 * admission, must not start capability polling and must not perform any protected read; only the real
 * commit of the entry does.
 *
 * Scenario 7 (a retired check is never reused — Sol's focus-recovery counterexample): Operations entry A
 * commits with its verification held open, the surface then closes in a committed transition that
 * retires and aborts A's check, and the same signed-in user returns focus to the closed, non-granted
 * surface. A fresh capability request must be transmitted instead of adopting the retired promise; the
 * obsolete request's late grant must not touch the new check; denied, the surface must stay fail closed;
 * and granted, it must still support a normal future entry.
 *
 * Scenario 8 (genuinely current checks are still shared): overlapping supported revalidations of one
 * open entry adopt the one live check instead of stacking duplicate reads, the live check keeps failing
 * the dispatch gate closed, and a Claim/Release pre-flight is still its own check.
 *
 * Usage:  node frontend-web/browser-verification/operations-entry-commit/run.mjs
 *   OE_ENTRY_COMMIT_SCENARIOS=3,4   run a subset of scenarios 1-8
 * Requires Playwright with Chromium (installed globally in this environment), like its siblings.
 */
import { fileURLToPath } from 'node:url';
import { readFile } from 'node:fs/promises';
import { build } from 'esbuild';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;

const FRONTEND_ROOT = fileURLToPath(new URL('../../', import.meta.url));
const ORIGIN = 'http://oe.local';
/**
 * The hook module the harness mounts. Overridable so the negative control can point the harness at a
 * temporary copy that deliberately reproduces the old render-phase side effect; the real module is the
 * default, so an ordinary run always exercises production code.
 */
const HOOK_MODULE = process.env.OE_ENTRY_COMMIT_HOOK ?? './src/hooks/useOperationsCapability';

/**
 * Negative controls. Each mode reproduces one superseded defect inside a *test-only* copy of the hook,
 * at bundle time. The production module on disk is never touched, so a control cannot drift from it and
 * nothing has to be restored afterwards.
 *
 *   - `render-phase`: a render pass advances committed entry ownership and retires the still-committed
 *     entry's checks (correction A).
 *   - `workspace-open-render-phase`: a render pass mirrors the *requested* `workspaceEntry` prop and the
 *     render-pass state into refs, which is the superseded input of the focus/visibility decision.
 *   - `stale-inflight-sharing`: both halves of the superseded sharing model — eligibility for reusing an
 *     already-issued check is decided from the cached in-flight pointer alone with no consult of the
 *     pending-check registry, and a retirement leaves that pointer dangling at the retired check. That
 *     is what let a retired check's obsolete promise suppress a fresh request.
 */
const CONTROL = process.env.OE_ENTRY_COMMIT_CONTROL ?? '';

const renderPhaseDefectPlugin = {
  name: 'oe-render-phase-defect-control',
  setup(build) {
    build.onLoad({ filter: /useOperationsCapability\.ts$/ }, async (args) => {
      const source = await readFile(args.path, 'utf8');
      const marker = 'const pendingChecksRef = useRef<Map<number, PendingCapabilityCheck>>(new Map());';
      if (!source.includes(marker)) throw new Error('negative-control marker not found in the hook');
      const defect = marker + `
  // NEGATIVE CONTROL (test-only): the superseded render-phase ownership advance. A render pass must
  // have no such side effect; reproducing it here proves this harness detects the defect.
  if (committedEntryRef.current !== null && committedEntryRef.current.key !== workspaceEntry) {
    const controlGeneration = committedEntryRef.current.generation + 1;
    committedEntryRef.current = { key: workspaceEntry, generation: controlGeneration };
    retireObsoleteEntryChecks(pendingChecksRef.current, controlGeneration);
  }`;
      return { contents: source.replace(marker, defect), loader: 'ts' };
    });
  }
};

/**
 * The superseded focus/visibility input: `workspaceOpenRef.current` (and the render-pass `stateRef`
 * mirror) written during render. Reinserted verbatim into a test-only copy of the committed hook.
 */
const renderPhaseWorkspaceOpenDefectPlugin = {
  name: 'oe-render-phase-workspace-open-control',
  setup(build) {
    build.onLoad({ filter: /useOperationsCapability\.ts$/ }, async (args) => {
      const source = await readFile(args.path, 'utf8');
      const anchor = '  const [capabilities, setCapabilities] = useState<StaffCapabilities | null>(null);';
      const committedGuard = `    const committed = committedEntryRef.current;
    const committedWorkspaceOpen = committed !== null && committed.key !== null;
    if (!committedWorkspaceOpen && verdictRef.current.state === 'granted') return;`;
      if (!source.includes(anchor) || !source.includes(committedGuard)) {
        throw new Error('render-phase workspace-open negative-control marker not found in the hook');
      }
      const mirror = anchor + `
  // NEGATIVE CONTROL (test-only): the superseded render-phase mirrors of the requested prop and the
  // render-pass state. A render pass must have no such side effect.
  const workspaceOpenRef = useRef(workspaceOpen);
  const stateRef = useRef(state);
  stateRef.current = state;
  workspaceOpenRef.current = workspaceOpen;`;
      return {
        contents: source
          .replace(anchor, mirror)
          .replace(committedGuard, '    if (!workspaceOpenRef.current && stateRef.current === \'granted\') return;'),
        loader: 'ts'
      };
    });
  }
};

/**
 * The superseded sharing input: whether an already-issued capability check may be reused is decided from
 * the cached in-flight pointer alone, with no consult of the pending-check registry. A check retired by a
 * committed workspace transition therefore stayed "shareable", so its obsolete, deliberately
 * never-settling promise was adopted in place of the fresh request a new caller needed. Reinserted into a
 * test-only copy of the hook, exactly as reported, so this harness can prove it detects that defect.
 */
const staleInFlightSharingDefectPlugin = {
  name: 'oe-stale-inflight-sharing-control',
  setup(build) {
    build.onLoad({ filter: /useOperationsCapability\.ts$/ }, async (args) => {
      const source = await readFile(args.path, 'utf8');
      const marker = '  if (registered === undefined || registered.control.signal.aborted) return false;';
      const pointerMarker = `    const pointedAt = inFlightRef.current;
    if (pointedAt !== null && !pendingChecksRef.current.has(pointedAt.id)) inFlightRef.current = null;`;
      if (!source.includes(marker)) {
        throw new Error('stale-sharing negative-control marker not found in the hook');
      }
      if (!source.includes(pointerMarker)) {
        throw new Error('stale-sharing negative-control pointer marker not found in the hook');
      }
      const superseded = `  void registered;
  // NEGATIVE CONTROL (test-only): the superseded sharing rule. Eligibility comes from the cached pointer
  // alone, so a retired check remains shareable and suppresses the fresh request.
  if (!sameOperationsCredential(shared.session, session)) return false;
  return source === 'background'
    || (source === 'entry' && shared.source === 'entry' && shared.entryGeneration === entryGeneration);`;
      return {
        contents: source
          .replace(marker, superseded)
          .replace(
            pointerMarker,
            '    // NEGATIVE CONTROL (test-only): the superseded dangling pointer is left in place.'
          ),
        loader: 'ts'
      };
    });
  }
};

const controlPlugins = {
  'render-phase': () => [renderPhaseDefectPlugin],
  'workspace-open-render-phase': () => [renderPhaseWorkspaceOpenDefectPlugin],
  'stale-inflight-sharing': () => [staleInFlightSharingDefectPlugin]
};

const TOKEN_A = 'token-entry-a';
const USER_A = JSON.stringify({ id: 11, role: 'EMPLOYEE', fullName: 'Entry A', email: 'a@example.test' });

/* ------------------------------------------------------------------ *
 * The controlled page environment
 * ------------------------------------------------------------------ */

const HARNESS_HTML = '<!doctype html><html><head><meta charset="utf-8"><title>OE entry commit</title></head>'
  + '<body><div id="root"></div></body></html>';

/**
 * Installed before any page script. Owns localStorage and `fetch`: the capability transport is held
 * open until a scenario releases it, and an abort of that transport is recorded rather than rejected,
 * so the harness can observe both "the transport was aborted" and "an aborted transport nevertheless
 * produced a late response".
 */
function installStub(storageEntries) {
  const state = {
    /** One record per capability request, in issue order. */
    capRequests: [],
    /** Every other API request that actually reached the transport. */
    others: [],
    /** Set to 'deny' to answer eligibility-denied. */
    capabilityBody: 'grant',
    grant: {
      staffActive: true,
      capabilities: [{
        capability: 'OPS_COORDINATE',
        scopeType: 'TEAM',
        cityId: 1,
        cityDisplayName: 'Indore',
        teamId: 7,
        teamDisplayName: 'Team A Coordination'
      }],
      serverTime: new Date().toISOString()
    },
    deny: { staffActive: true, capabilities: [], serverTime: new Date().toISOString() }
  };
  window.__stub = state;
  storageEntries.forEach(function (entry) { window.localStorage.setItem(entry[0], entry[1]); });

  /**
   * Cadence instrumentation. The approved ~30s revalidation cadence is the hook's only
   * `window.setInterval`, so recording every registration and clearing lets a scenario prove whether
   * the surface on screen owns a live cadence — and whether a discarded render ever activated polling.
   */
  window.__timers = [];
  const realSetInterval = window.setInterval;
  const realClearInterval = window.clearInterval;
  window.setInterval = function (handler, delay) {
    const id = realSetInterval.call(window, handler, delay);
    window.__timers.push({ kind: 'interval', id: id, delay: delay });
    return id;
  };
  window.clearInterval = function (id) {
    window.__timers.push({ kind: 'clearInterval', id: id });
    return realClearInterval.call(window, id);
  };

  const jsonResponse = function (status, body) {
    return {
      ok: status >= 200 && status < 300,
      status: status,
      json: function () { return Promise.resolve(body); },
      text: function () { return Promise.resolve(JSON.stringify(body)); }
    };
  };

  window.fetch = function (input, init) {
    const options = init || {};
    const url = typeof input === 'string' ? input : String(input && input.url);
    const path = url.replace(/^https?:\/\/[^/]+/, '');
    const authorization = options.headers && options.headers.Authorization ? String(options.headers.Authorization) : '';
    const token = authorization.replace('Bearer ', '');
    const signal = options.signal;

    if (path.indexOf('/api/v1/staff/me/capabilities') === 0) {
      const record = { token: token, aborted: false, released: false, settled: false, release: null };
      state.capRequests.push(record);
      return new Promise(function (resolve) {
        record.release = function () {
          record.released = true;
          record.settled = true;
          resolve(jsonResponse(200, state.capabilityBody === 'deny' ? state.deny : state.grant));
        };
        // A held capability check never settles on its own; the scenario releases it explicitly. An
        // abort is recorded but deliberately does not reject, so a late response can still be produced.
        if (signal) {
          if (signal.aborted) record.aborted = true;
          else signal.addEventListener('abort', function () { record.aborted = true; });
        }
      });
    }

    state.others.push({ path: path, method: options.method || 'GET', token: token });
    return Promise.resolve(jsonResponse(200, { items: [], totalCount: 0, page: 0, size: 20, totalPages: 0 }));
  };
}

/* ------------------------------------------------------------------ *
 * The harness component tree (bundled together with the real hook)
 * ------------------------------------------------------------------ */

const harnessSource = `
import React, { Suspense, useEffect, useState, useTransition } from 'react';
import { createRoot } from 'react-dom/client';
import { useOperationsCapability } from '${HOOK_MODULE}';
import { captureOperationsSession } from './src/utils/operationsSession';

const ENTRY_A = 'destination';
const ENTRY_B = 'embedded:employee';

const probe = {
  renders: [],
  commits: [],
  admissions: [],
  protectedReads: [],
  authority: null,
  /** Authority object of the newest render React actually committed (effects only run on commit). */
  committedAuthority: null,
  session: null
};
window.__probe = probe;

/**
 * The one suspension gate. A \`startTransition\` update whose subtree renders this gate suspends, so
 * React renders the new tree in the background and keeps the previously committed one until the gate is
 * resolved — and never commits anything at all while it is not.
 */
const makeGate = () => {
  let resolve = () => {};
  const promise = new Promise((next) => { resolve = next; });
  const gate = { promise, status: 'pending', resolve: null };
  gate.resolve = () => { gate.status = 'ready'; resolve(); };
  return gate;
};
let pendingGate = makeGate();

function Suspender() {
  throw pendingGate.promise;
}

/**
 * Mirrors the real application: the shared authority lives in an always-mounted surface owner (Home)
 * and is told which Operations surface is *requested*. A closed workspace renders no workspace, and
 * every protected read is issued from an effect, so it can only ever run for a committed, admitted
 * render — a discarded render cannot reach it.
 */
function Surface(props) {
  probe.renders.push(props.entry);
  const authority = useOperationsCapability({ workspaceEntry: props.entry });
  probe.authority = authority;
  probe.session = captureOperationsSession();
  const admitted = props.entry !== null && authority.state === 'granted' && authority.entryAdmitted;
  useEffect(() => {
    probe.commits.push(props.entry);
    probe.admissions.push({ entry: props.entry, state: authority.state, admitted: admitted });
    probe.committedAuthority = authority;
  }, [props.entry, authority.state, admitted]);
  useEffect(() => {
    if (!admitted) return;
    probe.protectedReads.push(props.entry);
    void fetch('/api/v1/operations/my-work?page=0&size=20&attentionOnly=false');
  }, [admitted, props.entry]);
  // Suspends only while the gate is unresolved; once it is resolved the surface renders normally.
  if (props.suspense && pendingGate.status !== 'ready') return <Suspender />;
  if (props.entry === null) return <div data-testid="closed" />;
  return (
    <div data-testid="workspace" data-entry={String(props.entry)} data-admitted={String(admitted)}>
      {admitted ? 'ADMITTED' : 'withheld'}
    </div>
  );
}

function Root() {
  const [entry, setEntry] = useState(() => ('__OE_INITIAL_ENTRY' in window ? window.__OE_INITIAL_ENTRY : ENTRY_A));
  const [suspense, setSuspense] = useState(false);
  const [, startTransition] = useTransition();
  const go = (next) => startTransition(() => { setSuspense(true); setEntry(next); });
  window.__harness = {
    goB: () => go(ENTRY_B),
    goOpenA: () => go(ENTRY_A),
    goClosed: () => go(null),
    resolve: () => pendingGate.resolve(),
    resolveB: () => pendingGate.resolve(),
    resetGate: () => { pendingGate = makeGate(); }
  };
  return (
    <div>
      <Suspense fallback={<div data-testid="fallback">suspending</div>}>
        <Surface entry={entry} suspense={suspense} />
      </Suspense>
    </div>
  );
}

createRoot(document.getElementById('root')).render(<Root />);
`;

const bundleHarness = async () => {
  if (CONTROL !== '' && controlPlugins[CONTROL] === undefined) {
    throw new Error('unknown OE_ENTRY_COMMIT_CONTROL: ' + CONTROL);
  }
  const output = await build({
    stdin: {
      contents: harnessSource,
      resolveDir: FRONTEND_ROOT,
      sourcefile: 'oe-entry-commit-harness.tsx',
      loader: 'tsx'
    },
    bundle: true,
    platform: 'browser',
    format: 'iife',
    target: 'chrome100',
    write: false,
    plugins: CONTROL === '' ? [] : controlPlugins[CONTROL](),
    define: {
      'import.meta.env': '{"VITE_API_BASE_URL":"/api/v1","VITE_OAUTH_BASE_URL":""}',
      'process.env.NODE_ENV': '"production"'
    }
  });
  return output.outputFiles[0].text;
};

/* ------------------------------------------------------------------ *
 * Scenario harness
 * ------------------------------------------------------------------ */

const results = [];
let failures = 0;
let checks = 0;

const check = (label, condition, detail) => {
  checks += 1;
  if (condition) {
    results.push('  \u2714 ' + label);
  } else {
    failures += 1;
    results.push('  \u2716 ' + label + (detail === undefined ? '' : ' \u2014 ' + detail));
  }
};

const checkEqual = (label, actual, expected) =>
  check(label, Object.is(actual, expected), 'expected ' + JSON.stringify(expected) + ', saw ' + JSON.stringify(actual));

/** Live ~30s cadences: registrations that have not been cleared, i.e. surfaces polling right now. */
const liveCadences = (snap) => {
  const live = new Set();
  snap.timers.forEach((record) => {
    if (record.kind === 'interval') {
      if (record.delay === 30000) live.add(record.id);
    } else {
      live.delete(record.id);
    }
  });
  return Array.from(live);
};

const waitFor = async (page, predicate, timeoutMs = 6000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await predicate()) return true;
    await page.waitForTimeout(100);
  }
  return false;
};

const snapshot = (page) => page.evaluate(() => ({
  renders: window.__probe.renders.slice(),
  commits: window.__probe.commits.slice(),
  admissions: window.__probe.admissions.slice(),
  protectedReads: window.__probe.protectedReads.slice(),
  capRequests: window.__stub.capRequests.map((record) => ({
    token: record.token,
    aborted: record.aborted,
    released: record.released,
    settled: record.settled
  })),
  otherReads: window.__stub.others.slice(),
  timers: window.__timers.slice()
}));

/** How many capability requests have actually reached the transport, in issue order. */
const capabilityCount = (page) => page.evaluate(() => window.__stub.capRequests.length);

/**
 * Releases one held capability check, answering with `body` ('grant' | 'deny'). A missing check is a
 * no-op: the check that expected the request has already failed, and the scenario must keep reporting
 * clean, attributable failures rather than throwing.
 */
const releaseCapability = (page, index, body) => page.evaluate(([position, responseBody]) => {
  if (responseBody !== undefined) window.__stub.capabilityBody = responseBody;
  const record = window.__stub.capRequests[position];
  if (!record || typeof record.release !== 'function') return false;
  record.release();
  return true;
}, [index, body]);

const dispatchFocus = (page) => page.evaluate(() => window.dispatchEvent(new Event('focus')));
const dispatchVisibilityChange = (page) =>
  page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));

const committedWorkspaceNode = (page) => page.evaluate(() => {
  const node = document.querySelector('[data-testid="workspace"]');
  return node === null
    ? null
    : { entry: node.getAttribute('data-entry'), admitted: node.getAttribute('data-admitted') };
});

const committedAdmission = (page) =>
  page.evaluate(() => window.__probe.committedAuthority !== null
    ? {
      state: window.__probe.committedAuthority.state,
      entryAdmitted: window.__probe.committedAuthority.entryAdmitted
    }
    : null);

const dispatchGateOpen = (page) =>
  page.evaluate(() => window.__probe.committedAuthority !== null
    && window.__probe.committedAuthority.isDispatchAuthorized(window.__probe.session) === true);

const openPage = async (browser, options = {}) => {
  const initialEntry = options.initialEntry === undefined ? 'destination' : options.initialEntry;
  const context = await browser.newContext({ viewport: { width: 1100, height: 800 } });
  const page = await context.newPage();
  page.on('pageerror', (error) => { results.push('  ! page error: ' + error.message); });
  await page.addInitScript(installStub, [
    ['pathome_auth_token', TOKEN_A],
    ['pathome_role', 'EMPLOYEE'],
    ['pathome_user', USER_A]
  ]);
  await page.route(ORIGIN + '/**', async (route) => {
    await route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: HARNESS_HTML });
  });
  await page.goto(ORIGIN + '/harness', { waitUntil: 'domcontentloaded' });
  await page.evaluate((entry) => { window.__OE_INITIAL_ENTRY = entry; }, initialEntry);
  await page.addScriptTag({ content: await bundleHarness() });
  await waitFor(page, async () => (await page.evaluate(() => window.__stub.capRequests.length)) === 1);
  return { context, page };
};

/* ------------------------------------------------------------------ *
 * Scenario 1 - a discarded concurrent render preserves the committed entry
 * ------------------------------------------------------------------ */

const scenarioDiscardedRenderPreservesEntryA = async (browser) => {
  const before = failures;
  const { context, page } = await openPage(browser);
  // Entry A's verification is held open: A is the committed entry and is not yet admitted.
  const first = await snapshot(page);
  checkEqual('entry A requests exactly one capability verification', first.capRequests.length, 1);
  checkEqual('the verification carries the signed-in credential', first.capRequests[0].token, TOKEN_A);
  checkEqual('entry A is not admitted before its verification settles', first.admissions.filter((entry) => entry.admitted).length, 0);
  checkEqual('no protected read runs before a verdict', first.protectedReads.length, 0);

  // Start a concurrent transition to entry B and let it suspend without ever committing.
  await page.evaluate(() => window.__harness.goB());
  await waitFor(page, async () => (await page.evaluate(() => window.__probe.renders.includes('embedded:employee'))));
  await page.waitForTimeout(400);

  const speculative = await snapshot(page);
  check('React really rendered the speculative entry B', speculative.renders.includes('embedded:employee'));
  check('the transition never committed entry B', !speculative.commits.includes('embedded:employee'));
  checkEqual('the committed entry is still A', speculative.commits[speculative.commits.length - 1], 'destination');
  check(
    'the discarded render did not abort entry A\u2019s live verification',
    speculative.capRequests[0].aborted === false,
    'aborted=' + String(speculative.capRequests[0].aborted)
  );
  checkEqual('the discarded render issued no new capability read', speculative.capRequests.length, 1);
  checkEqual('no protected read began from the uncommitted entry', speculative.protectedReads.length, 0);

  // Entry A's own verification now settles, and A must be admitted normally. The committed DOM is what
  // counts: `probe.authority` can legitimately hold the latest *speculative* B render, so the rendered
  // workspace element (which only React commits) is the honest witness here.
  await page.evaluate(() => window.__stub.capRequests[0].release());
  const admitted = await waitFor(page, async () => (await page.evaluate(() => {
    const node = document.querySelector('[data-testid="workspace"]');
    return node !== null
      && node.getAttribute('data-entry') === 'destination'
      && node.getAttribute('data-admitted') === 'true';
  })));
  check('entry A is admitted by its own verification after the discarded render', admitted);
  await waitFor(page, async () => (await page.evaluate(() => window.__probe.protectedReads.length)) === 1);

  const settled = await snapshot(page);
  check(
    'the committed workspace still shows entry A and never the uncommitted entry B',
    settled.commits.every((entry) => entry === 'destination')
  );
  check(
    'React re-ran the speculative entry B render while A stayed committed',
    settled.renders.filter((entry) => entry === 'embedded:employee').length >= 1
      && settled.commits.filter((entry) => entry === 'destination').length >= 1
  );
  checkEqual('entry A performs exactly one protected read, after its verdict', settled.protectedReads.length, 1);
  checkEqual('the protected read belongs to entry A', settled.protectedReads[0], 'destination');
  check(
    'no protected read ever ran for the uncommitted entry B',
    settled.protectedReads.every((entry) => entry === 'destination')
  );
  checkEqual('no entry B verification was ever issued', settled.capRequests.length, 1);
  if (failures > before) results.push('  \u00b7 final probe: ' + JSON.stringify(settled));
  await context.close();
};

/* ------------------------------------------------------------------ *
 * Scenario 2 - a committed transition retires A and verifies B on its own
 * ------------------------------------------------------------------ */

const scenarioCommittedEntryRetiresA = async (browser) => {
  const before = failures;
  const { context, page } = await openPage(browser);
  await page.evaluate(() => window.__harness.goB());
  await waitFor(page, async () => (await page.evaluate(() => window.__probe.renders.includes('embedded:employee'))));

  // Let React retry and commit entry B.
  await page.evaluate(() => window.__harness.resolveB());
  const committedB = await waitFor(page, async () => (await page.evaluate(() => window.__probe.commits.includes('embedded:employee'))));
  check('the transition commits entry B once its suspension resolves', committedB);
  const bVerified = await waitFor(page, async () => (await page.evaluate(() => window.__stub.capRequests.length)) === 2);
  check('entry B issues its own capability verification', bVerified);

  const committed = await snapshot(page);
  check(
    'committing entry B retires (aborts) entry A\u2019s obsolete entry check',
    committed.capRequests[0].aborted === true,
    'aborted=' + String(committed.capRequests[0].aborted)
  );
  checkEqual('entry B is not admitted while its own verification is pending', committed.admissions.filter((entry) => entry.admitted).length, 0);
  checkEqual('entry B performs no protected read before it is admitted', committed.protectedReads.length, 0);

  // Entry A's aborted transport nevertheless produces a late grant. It must authorise nothing.
  await page.evaluate(() => window.__stub.capRequests[0].release());
  await page.waitForTimeout(500);
  const afterLateGrant = await snapshot(page);
  checkEqual('entry A\u2019s late grant does not admit entry B', afterLateGrant.admissions.filter((entry) => entry.admitted).length, 0);
  checkEqual('entry A\u2019s late grant starts no protected read', afterLateGrant.protectedReads.length, 0);
  const blockedWhilePending = await page.evaluate(
    () => window.__probe.authority.isDispatchAuthorized(window.__probe.session) === false
  );
  check('entry B\u2019s own pending check keeps the dispatch gate closed after A\u2019s finalizer', blockedWhilePending);
  checkEqual('entry B\u2019s pending check survives entry A\u2019s late finalizer', afterLateGrant.capRequests.length, 2);

  // Only entry B's own verification admits B.
  await page.evaluate(() => window.__stub.capRequests[1].release());
  const bAdmitted = await waitFor(page, async () => (await page.evaluate(
    () => window.__probe.authority && window.__probe.authority.state === 'granted' && window.__probe.authority.entryAdmitted === true
  )));
  check('entry B is admitted by its own verification', bAdmitted);
  await waitFor(page, async () => (await page.evaluate(() => window.__probe.protectedReads.length)) === 1);
  const final = await snapshot(page);
  checkEqual('exactly one protected read ran, for entry B', final.protectedReads.length, 1);
  checkEqual('the protected read belongs to entry B', final.protectedReads[0], 'embedded:employee');
  const authorised = await page.evaluate(
    () => window.__probe.authority.isDispatchAuthorized(window.__probe.session) === true
  );
  check('the dispatch gate opens only after entry B\u2019s own verification succeeds', authorised);
  if (failures > before) results.push('  \u00b7 final probe: ' + JSON.stringify(final));
  await context.close();
};

/* ------------------------------------------------------------------ *
 * Scenarios 3-4 - a discarded exit render must not suppress the committed entry's revalidation
 * ------------------------------------------------------------------ */

/**
 * Entry A is committed, admitted and visible. A concurrent transition to the closed Operations surface
 * then renders and suspends forever, so the render is thrown away. Authority is denied while that
 * discarded render is still in place, and the user returns to the page through `trigger`.
 */
const discardedExitRevalidation = async (browser, trigger) => {
  const before = failures;
  const label = trigger === 'focus' ? 'focus event' : 'visibilitychange event';
  const { context, page } = await openPage(browser);
  await releaseCapability(page, 0, 'grant');
  const admitted = await waitFor(page, async () => {
    const node = await committedWorkspaceNode(page);
    return node !== null && node.entry === 'destination' && node.admitted === 'true';
  });
  check('entry A is committed and admitted before the exit render', admitted);
  await waitFor(page, async () => (await page.evaluate(() => window.__probe.protectedReads.length)) === 1);
  checkEqual('the open surface owns one live cadence', liveCadences(await snapshot(page)).length, 1);

  await page.evaluate(() => window.__harness.goClosed());
  const renderedClosed = await waitFor(page, () => page.evaluate(() => window.__probe.renders.includes(null)));
  await page.waitForTimeout(400);
  check('React really rendered the discarded closed surface', renderedClosed);

  const discarded = await snapshot(page);
  check('the exit transition never committed', !discarded.commits.includes(null));
  checkEqual('the committed entry is still A', discarded.commits[discarded.commits.length - 1], 'destination');
  const stillVisible = await committedWorkspaceNode(page);
  check(
    'entry A is still committed, visible and admitted after the discarded render',
    stillVisible !== null && stillVisible.entry === 'destination' && stillVisible.admitted === 'true'
  );
  checkEqual('the discarded render issued no capability read', discarded.capRequests.length, 1);
  checkEqual('the discarded render added no protected read', discarded.protectedReads.length, 1);
  checkEqual('the discarded render did not activate a second cadence', liveCadences(discarded).length, 1);

  // Authority is denied while the discarded render is still in place, and the user returns to the page.
  await page.evaluate(() => { window.__stub.capabilityBody = 'deny'; });
  const visibilityAtDispatch = await page.evaluate(() => document.visibilityState);
  if (trigger === 'focus') await dispatchFocus(page);
  else await dispatchVisibilityChange(page);
  results.push('  \u00b7 ' + label + ' dispatched through the real DOM event system; the browser\u2019s own '
    + 'document.visibilityState at that moment was \u201c' + visibilityAtDispatch + '\u201d and was read by '
    + 'the production guard');

  const revalidated = await waitFor(page, async () => (await page.evaluate(() => window.__stub.capRequests.length)) === 2);
  check('the ' + label + ' re-verifies the committed entry A', revalidated);

  const reverified = await snapshot(page);
  checkEqual(
    'the fresh verification carries the signed-in credential',
    reverified.capRequests[1] === undefined ? null : reverified.capRequests[1].token,
    TOKEN_A
  );
  const whilePending = await committedWorkspaceNode(page);
  check('no protected read runs while the re-verification is pending', reverified.protectedReads.length === 1);
  check(
    'the still-committed entry keeps its content while the check is pending',
    whilePending !== null && whilePending.admitted === 'true'
  );

  await releaseCapability(page, 1, 'deny');
  const withdrawn = await waitFor(page, async () => {
    const node = await committedWorkspaceNode(page);
    return node !== null && node.entry === 'destination' && node.admitted === 'false';
  });
  check('the denial withdraws entry A\u2019s protected content', withdrawn);

  const denied = await snapshot(page);
  checkEqual('the denial issues no additional protected read', denied.protectedReads.length, 1);
  const deniedAdmission = await committedAdmission(page);
  check('the committed authority is no longer admitted', deniedAdmission !== null && deniedAdmission.entryAdmitted === false);
  checkEqual('the committed authority is restricted', deniedAdmission === null ? null : deniedAdmission.state, 'restricted');
  check('the dispatch gate stays closed after the denial', (await dispatchGateOpen(page)) === false);

  // No protected mutation is possible any more: a Claim/Release pre-flight for the exact session is
  // re-issued and refuses.
  await page.evaluate(() => {
    window.__probe.mutationAttempt = window.__probe.committedAuthority.verifySession(window.__probe.session);
  });
  const beforePreflight = await page.evaluate(() => window.__stub.capRequests.length - 1);
  const preflight = await waitFor(page, async () => (await page.evaluate(() => window.__stub.capRequests.length)) === beforePreflight + 1);
  check('a Claim/Release pre-flight is still issued for the exact session', preflight);
  if (preflight) {
    await releaseCapability(page, beforePreflight, 'deny');
    const mutationAuthorized = await page.evaluate(() => window.__probe.mutationAttempt);
    check('no protected mutation can be authorized after the denial', mutationAuthorized === false,
      'verifySession resolved ' + JSON.stringify(mutationAuthorized));
  }

  const final = await snapshot(page);
  checkEqual('no protected read ran for the discarded closed surface', final.protectedReads.length, 1);
  check('the discarded exit render never influenced the committed-entry lifecycle', !final.commits.includes(null));
  if (failures > before) results.push('  \u00b7 final probe: ' + JSON.stringify(final));
  await context.close();
};

const scenarioDiscardedExitRetainsFocusRevalidation = (browser) => discardedExitRevalidation(browser, 'focus');
const scenarioDiscardedExitRetainsVisibilityRevalidation =
  (browser) => discardedExitRevalidation(browser, 'visibilitychange');

/* ------------------------------------------------------------------ *
 * Scenario 5 - a committed exit stops workspace revalidation
 * ------------------------------------------------------------------ */

const scenarioCommittedExitStopsWorkspaceRevalidation = async (browser) => {
  const before = failures;
  const { context, page } = await openPage(browser);
  await releaseCapability(page, 0, 'grant');
  await waitFor(page, async () => {
    const node = await committedWorkspaceNode(page);
    return node !== null && node.admitted === 'true';
  });
  await waitFor(page, async () => (await page.evaluate(() => window.__probe.protectedReads.length)) === 1);

  // This time the transition to the closed surface really commits.
  await page.evaluate(() => window.__harness.goClosed());
  await waitFor(page, () => page.evaluate(() => window.__probe.renders.includes(null)));
  await page.evaluate(() => window.__harness.resolve());
  const committedClosed = await waitFor(page, () => page.evaluate(() => window.__probe.commits.includes(null)));
  check('the exit transition commits the closed surface', committedClosed);

  const closed = await snapshot(page);
  check('the closed surface renders no workspace', (await committedWorkspaceNode(page)) === null);
  check('no protected read runs for the closed surface', closed.protectedReads.length === 1);
  checkEqual('the closed surface owns no live cadence', liveCadences(closed).length, 0);
  const closedAdmission = await committedAdmission(page);
  check('the retired entry is no longer admitted', closedAdmission !== null && closedAdmission.entryAdmitted === false);
  check('the dispatch gate is closed while no Operations surface is on screen', (await dispatchGateOpen(page)) === false);

  // Activity revalidation must no longer treat the closed surface as an open workspace.
  await dispatchFocus(page);
  await dispatchVisibilityChange(page);
  await page.waitForTimeout(400);
  checkEqual(
    'a granted but closed workspace is not revalidated from focus or visibility',
    (await snapshot(page)).capRequests.length,
    1
  );

  // Re-entering is a new entry: it needs its own verification before anything protected is read.
  await page.evaluate(() => window.__harness.goOpenA());
  await waitFor(page, () => page.evaluate(() => window.__probe.renders.includes('destination')));
  await page.evaluate(() => window.__harness.resolve());
  const reEntered = await waitFor(page, async () => (await page.evaluate(() => window.__stub.capRequests.length)) === 2);
  check('re-entering issues a fresh capability verification', reEntered);
  const pending = await snapshot(page);
  check('no protected read runs before the new entry is admitted', pending.protectedReads.length === 1);
  const withheld = await committedWorkspaceNode(page);
  check(
    'protected content is withheld while the new entry is unverified',
    withheld !== null && withheld.entry === 'destination' && withheld.admitted === 'false'
  );
  checkEqual('the re-entered surface owns one cadence', liveCadences(pending).length, 1);

  await releaseCapability(page, 1, 'grant');
  const readmitted = await waitFor(page, async () => {
    const node = await committedWorkspaceNode(page);
    return node !== null && node.admitted === 'true';
  });
  check('the re-entered surface is admitted by its own verification', readmitted);
  await waitFor(page, async () => (await page.evaluate(() => window.__probe.protectedReads.length)) === 2);
  const final = await snapshot(page);
  checkEqual('exactly two protected reads ran: one per admitted entry', final.protectedReads.length, 2);
  check(
    'both protected reads belong to a committed Operations entry',
    final.protectedReads.every((entry) => entry === 'destination')
  );
  if (failures > before) results.push('  \u00b7 final probe: ' + JSON.stringify(final));
  await context.close();
};

/* ------------------------------------------------------------------ *
 * Scenario 6 - a speculative open cannot activate a closed workspace
 * ------------------------------------------------------------------ */

const scenarioSpeculativeOpenCannotActivateClosedWorkspace = async (browser) => {
  const before = failures;
  const { context, page } = await openPage(browser, { initialEntry: null });
  await releaseCapability(page, 0, 'grant');
  const grantedClosed = await waitFor(page, async () => {
    const admission = await committedAdmission(page);
    return admission !== null && admission.state === 'granted';
  });
  check('a verified closed surface holds a valid grant', grantedClosed);

  const closed = await snapshot(page);
  checkEqual('a closed surface performs no protected read', closed.protectedReads.length, 0);
  checkEqual('a closed surface owns no cadence', liveCadences(closed).length, 0);
  const closedAdmission = await committedAdmission(page);
  check('a closed surface is never admitted', closedAdmission !== null && closedAdmission.entryAdmitted === false);

  // An Operations entry renders speculatively and suspends: it is never committed.
  await page.evaluate(() => window.__harness.goOpenA());
  const renderedOpen = await waitFor(page, () => page.evaluate(() => window.__probe.renders.includes('destination')));
  await page.waitForTimeout(400);
  check('React really rendered the speculative Operations entry', renderedOpen);

  const speculative = await snapshot(page);
  check('the speculative entry never committed', !speculative.commits.includes('destination'));
  checkEqual('the speculative render issues no capability read of its own', speculative.capRequests.length, 1);
  checkEqual('the speculative render performs no protected read', speculative.protectedReads.length, 0);
  checkEqual('the speculative render activates no cadence', liveCadences(speculative).length, 0);
  const speculativeAdmission = await committedAdmission(page);
  check(
    'the speculative render leaves the committed closed surface unadmitted',
    speculativeAdmission !== null && speculativeAdmission.entryAdmitted === false
  );

  // A granted *closed* surface is not polled from activity events; a discarded open render must not
  // change that, which is exactly what a render-phase openness mirror did.
  await dispatchFocus(page);
  await dispatchVisibilityChange(page);
  await page.waitForTimeout(400);
  checkEqual(
    'a speculative open render does not activate workspace polling',
    (await snapshot(page)).capRequests.length,
    1
  );

  // Only the real commit of the entry establishes it.
  await page.evaluate(() => window.__harness.resolve());
  const committedOpen = await waitFor(page, () => page.evaluate(() => window.__probe.commits.includes('destination')));
  check('the Operations entry commits once its suspension resolves', committedOpen);
  const requiredFresh = await waitFor(page, async () => (await page.evaluate(() => window.__stub.capRequests.length)) === 2);
  check('the committed entry requires its own fresh verification', requiredFresh);
  const pending = await snapshot(page);
  checkEqual('no protected read runs before the committed entry is admitted', pending.protectedReads.length, 0);
  checkEqual('the committed open surface now owns one cadence', liveCadences(pending).length, 1);

  await releaseCapability(page, 1, 'grant');
  const admitted = await waitFor(page, async () => {
    const node = await committedWorkspaceNode(page);
    return node !== null && node.entry === 'destination' && node.admitted === 'true';
  });
  check('the committed entry is admitted by its own verdict', admitted);
  await waitFor(page, async () => (await page.evaluate(() => window.__probe.protectedReads.length)) === 1);
  const final = await snapshot(page);
  checkEqual('exactly one protected read runs, for the committed entry', final.protectedReads.length, 1);
  checkEqual('the protected read belongs to the committed entry', final.protectedReads[0], 'destination');
  if (failures > before) results.push('  \u00b7 final probe: ' + JSON.stringify(final));
  await context.close();
};

/* ------------------------------------------------------------------ *
 * Scenario 7 - a retired check is never reused (Sol's focus-recovery counterexample)
 * ------------------------------------------------------------------ */

/**
 * Sol's exact counterexample. Entry A commits with its capability verification held open; the surface
 * then closes in a committed transition, which retires and aborts A's check while its promise
 * deliberately never settles; and the same signed-in user returns focus to the closed, still
 * non-granted surface.
 *
 * The retired verification must never be adopted in place of a fresh read, its late verdict must not
 * disturb the new check, and the two independent outcomes must hold: a denial stays fail closed, and a
 * grant still supports a normal future entry.
 */
const scenarioRetiredCheckIsNeverReusedOnFocusRecovery = async (browser) => {
  const before = failures;
  const { context, page } = await openPage(browser);

  // 1. Operations entry A commits with its verification pending and the transport held indefinitely.
  const pending = await snapshot(page);
  checkEqual('entry A issues exactly one capability verification', pending.capRequests.length, 1);
  checkEqual('the verification carries the signed-in credential', pending.capRequests[0].token, TOKEN_A);
  checkEqual('entry A is not admitted while its verification is pending', pending.admissions.filter((entry) => entry.admitted).length, 0);
  checkEqual('no protected read runs while entry A is unverified', pending.protectedReads.length, 0);

  // 2. Operations closes in a committed transition: entry A's pending check is retired and aborted.
  await page.evaluate(() => window.__harness.goClosed());
  await waitFor(page, () => page.evaluate(() => window.__probe.renders.includes(null)));
  await page.evaluate(() => window.__harness.resolve());
  const closed = await waitFor(page, () => page.evaluate(() => window.__probe.commits.includes(null)));
  check('the close transition commits', closed);

  const freshAfterClose = await waitFor(page, async () => (await capabilityCount(page)) === 2, 2500);
  check(
    'the committed close transmits a fresh check instead of adopting the retired one',
    freshAfterClose,
    'capability requests: ' + JSON.stringify((await snapshot(page)).capRequests)
  );
  const retired = await snapshot(page);
  checkEqual('the committed close aborts entry A\u2019s obsolete verification', retired.capRequests[0].aborted, true);
  checkEqual('the fresh check carries the signed-in credential', retired.capRequests[1] && retired.capRequests[1].token, TOKEN_A);
  checkEqual('the fresh check is a new transport, not the retired promise', retired.capRequests[1] && retired.capRequests[1].aborted, false);
  checkEqual('the fresh check belongs to the committed closed surface, so nothing is admitted', retired.admissions.filter((entry) => entry.admitted).length, 0);
  checkEqual('no protected read runs for the closed surface', retired.protectedReads.length, 0);

  // 3. Focus while the closed surface is still non-granted, with entry A's retired promise still
  //    deliberately unresolved. The genuinely current check is adopted rather than duplicated.
  await dispatchFocus(page);
  await dispatchVisibilityChange(page);
  await page.waitForTimeout(400);
  checkEqual('focus adopts the genuinely current check instead of duplicating it', (await capabilityCount(page)), 2);
  checkEqual('the retired promise was never reused by that revalidation', (await capabilityCount(page)) === 2, true);

  // 4. Now the obsolete request settles late, with a grant, while the fresh check is still pending. Its
  //    continuation must not clear the fresh check, must not open its gate, and must not be a verdict.
  await releaseCapability(page, 0, 'grant');
  await page.waitForTimeout(500);
  const afterLateGrant = await snapshot(page);
  checkEqual('entry A\u2019s late grant transmits no further capability request', afterLateGrant.capRequests.length, 2);
  checkEqual('entry A\u2019s late grant admits nothing while the surface is closed', afterLateGrant.admissions.filter((entry) => entry.admitted).length, 0);
  checkEqual('entry A\u2019s late grant starts no protected read', afterLateGrant.protectedReads.length, 0);
  const lateVerdict = await committedAdmission(page);
  check(
    'entry A\u2019s late grant cannot overwrite the current verdict',
    lateVerdict !== null && lateVerdict.state !== 'granted',
    'committed state: ' + JSON.stringify(lateVerdict)
  );
  check('the fresh check survives entry A\u2019s late finalizer while genuinely pending', (await capabilityCount(page)) === 2);
  check('the fresh pending check keeps the dispatch gate closed', (await dispatchGateOpen(page)) === false);

  // A denial delivered by that check must fail closed.
  await releaseCapability(page, 1, 'deny');
  const restricted = await waitFor(page, async () => {
    const admission = await committedAdmission(page);
    return admission !== null && admission.state === 'restricted';
  });
  check('the denial of the fresh check leaves authority restricted', restricted);
  checkEqual('the denial admits nothing while the surface is closed', (await snapshot(page)).admissions.filter((entry) => entry.admitted).length, 0);
  check('the dispatch gate stays closed after the denial', (await dispatchGateOpen(page)) === false);

  // 5. Focusing the closed, non-granted surface again must transmit a fresh request of its own.
  await dispatchFocus(page);
  const focusRequest = await waitFor(page, async () => (await capabilityCount(page)) === 3, 2500);
  check('focusing the closed non-granted surface transmits a fresh capability request', focusRequest);
  const requested = await snapshot(page);
  checkEqual('the focus-driven check carries the signed-in credential', requested.capRequests[2] && requested.capRequests[2].token, TOKEN_A);
  checkEqual('the focus-driven check is its own transport, never a retired promise', requested.capRequests[2] && requested.capRequests[2].aborted, false);
  checkEqual('no protected read runs for the closed non-granted surface', requested.protectedReads.length, 0);

  // 6. A valid new response supports a normal future entry.
  await releaseCapability(page, 2, 'grant');
  const recovered = await waitFor(page, async () => {
    const admission = await committedAdmission(page);
    return admission !== null && admission.state === 'granted';
  });
  check('the valid new response restores authority for the closed surface', recovered);
  checkEqual('the recovered grant admits no closed surface', (await snapshot(page)).admissions.filter((entry) => entry.admitted).length, 0);

  await page.evaluate(() => window.__harness.goOpenA());
  await waitFor(page, () => page.evaluate(() => window.__probe.renders.includes('destination')));
  await page.evaluate(() => window.__harness.resolve());
  const reEntered = await waitFor(page, async () => (await capabilityCount(page)) === 4, 2500);
  check('re-entering Operations issues its own fresh entry verification', reEntered);
  checkEqual('no protected read runs before the re-entered surface is admitted', (await snapshot(page)).protectedReads.length, 0);
  await releaseCapability(page, 3, 'grant');
  const admitted = await waitFor(page, async () => {
    const node = await committedWorkspaceNode(page);
    return node !== null && node.entry === 'destination' && node.admitted === 'true';
  });
  check('the re-entered workspace is admitted by its own verification', admitted);
  await waitFor(page, async () => (await page.evaluate(() => window.__probe.protectedReads.length)) === 1);
  const final = await snapshot(page);
  checkEqual('exactly one protected read ran, for the admitted entry', final.protectedReads.length, 1);
  checkEqual('the protected read belongs to the Operations entry', final.protectedReads[0], 'destination');
  if (failures > before) results.push('  \u00b7 final probe: ' + JSON.stringify(final));
  await context.close();
};

/* ------------------------------------------------------------------ *
 * Scenario 8 - genuinely current checks are still shared
 * ------------------------------------------------------------------ */

/**
 * The deduplication the correction must not lose. While one entry is open and admitted, overlapping
 * supported revalidations (focus, visibility, cadence) adopt the one live check rather than stacking
 * duplicate reads; the live check keeps failing the dispatch gate closed; and a Claim/Release pre-flight
 * is still its own independent re-authorisation.
 */
const scenarioCurrentCheckDeduplicationIsPreserved = async (browser) => {
  const before = failures;
  const { context, page } = await openPage(browser);
  await releaseCapability(page, 0, 'grant');
  const admitted = await waitFor(page, async () => {
    const node = await committedWorkspaceNode(page);
    return node !== null && node.admitted === 'true';
  });
  check('the open surface is admitted by its entry verification', admitted);
  await waitFor(page, async () => (await page.evaluate(() => window.__probe.protectedReads.length)) === 1);

  // Returning focus while the workspace is open re-verifies it; that check is held open.
  await dispatchFocus(page);
  const reverified = await waitFor(page, async () => (await capabilityCount(page)) === 2, 2500);
  check('returning focus re-verifies the open workspace', reverified);
  const live = await snapshot(page);
  checkEqual('the revalidation carries the signed-in credential', live.capRequests[1] && live.capRequests[1].token, TOKEN_A);

  // Overlapping supported revalidations must share that one live check.
  await dispatchFocus(page);
  await dispatchVisibilityChange(page);
  await dispatchVisibilityChange(page);
  await page.waitForTimeout(400);
  checkEqual('overlapping revalidations share the one live check', (await capabilityCount(page)), 2);
  check('a genuinely current pending check still fails the dispatch gate closed', (await dispatchGateOpen(page)) === false);

  // A mutation pre-flight is never shared, in either direction.
  await page.evaluate(() => {
    window.__probe.mutationAttempt = window.__probe.committedAuthority.verifySession(window.__probe.session);
  });
  const preflight = await waitFor(page, async () => (await capabilityCount(page)) === 3, 2500);
  check('a Claim/Release pre-flight is issued as its own check', preflight);
  checkEqual('exactly one background check and one mutation check are live', (await capabilityCount(page)), 3);

  await releaseCapability(page, 2, 'grant');
  const preflightAuthorized = await waitFor(page, () => page.evaluate(() => window.__probe.mutationAttempt), 2500);
  check('the mutation pre-flight is authorised by its own verdict', preflightAuthorized === true, 'resolved ' + JSON.stringify(preflightAuthorized));
  check('the still-pending shared check keeps the gate closed after the pre-flight settles', (await dispatchGateOpen(page)) === false);

  // Draining the shared background check returns the workspace to a settled, dispatchable state.
  await releaseCapability(page, 1, 'grant');
  const settledOpen = await waitFor(page, async () => (await dispatchGateOpen(page)) === true, 2500);
  check('the workspace returns to a settled dispatchable state once the shared check drains', settledOpen);
  const final = await snapshot(page);
  checkEqual('no duplicate capability request was ever transmitted', final.capRequests.length, 3);
  checkEqual('exactly one protected read ran', final.protectedReads.length, 1);
  if (failures > before) results.push('  \u00b7 final probe: ' + JSON.stringify(final));
  await context.close();
};

const scenarios = [
  scenarioDiscardedRenderPreservesEntryA,
  scenarioCommittedEntryRetiresA,
  scenarioDiscardedExitRetainsFocusRevalidation,
  scenarioDiscardedExitRetainsVisibilityRevalidation,
  scenarioCommittedExitStopsWorkspaceRevalidation,
  scenarioSpeculativeOpenCannotActivateClosedWorkspace,
  scenarioRetiredCheckIsNeverReusedOnFocusRecovery,
  scenarioCurrentCheckDeduplicationIsPreserved
];

/**
 * Optional scenario filter, e.g. `OE_ENTRY_COMMIT_SCENARIOS=3,4` to run only the two revalidation
 * scenarios while a negative control is installed.
 */
const selectedScenarios = () => {
  const raw = (process.env.OE_ENTRY_COMMIT_SCENARIOS ?? '').trim();
  if (raw === '') return scenarios;
  const wanted = raw.split(',').map((value) => value.trim()).filter((value) => value !== '');
  const unknown = wanted.filter((value) => !/^[1-9]$/.test(value) || scenarios[Number(value) - 1] === undefined);
  if (unknown.length > 0) throw new Error('unknown OE_ENTRY_COMMIT_SCENARIOS entry: ' + unknown.join(','));
  return wanted.map((value) => scenarios[Number(value) - 1]);
};

const main = async () => {
  const selected = selectedScenarios();
  if (CONTROL !== '') results.push('\u26a0 NEGATIVE CONTROL ACTIVE: ' + CONTROL);
  const browser = await chromium.launch({ channel: 'chromium' });
  for (const scenario of selected) {
    results.push('\n\u25b6 ' + scenario.name);
    try {
      await scenario(browser);
    } catch (error) {
      failures += 1;
      results.push('  \u2716 scenario threw: ' + error.message);
    }
  }
  await browser.close();
  console.log(results.join('\n'));
  console.log('\n' + checks + ' checks, ' + failures + ' failure(s) \u2014 '
    + (failures === 0 ? 'ALL CONCURRENT-RENDER CHECKS PASSED' : 'CONCURRENT-RENDER CHECKS FAILED'));
  process.exitCode = failures === 0 ? 0 : 1;
};

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
