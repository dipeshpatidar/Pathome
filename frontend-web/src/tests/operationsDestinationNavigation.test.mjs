import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createApiRequestError } from '../services/apiError.ts';

/**
 * Package 2 contracts: the dedicated Operations destination, capability-gated navigation, the single
 * shared authority, and identity/error semantics at the authentication boundary.
 *
 * Structural guards on source text live here; the runtime behaviour they protect is proven by driving
 * the real modules (`operationsWorkspaceSession.test.mjs`, `operationsOwnershipRaceCorrections.test.mjs`)
 * and by the Chromium suites in `browser-verification/`.
 */

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const home = read('../components/Home.tsx');
const navbar = read('../components/Navbar.tsx');
const dashboard = read('../components/EmployeeCrmDashboard.tsx');
const admin = read('../components/MasterAdminDashboard.tsx');
const destination = read('../components/operations/OperationsDestination.tsx');
const authModal = read('../components/AuthModal.tsx');
const workspace = read('../components/operations/OperationsWorkspace.tsx');
const authorityHook = read('../hooks/useOperationsCapability.ts');

/* 1. The dedicated destination ----------------------------------------------------- */

test('a dedicated /operations destination hosts the existing workspace untouched', () => {
  // The destination renders the same approved workspace component; no second implementation exists.
  assert.match(destination, /<OperationsWorkspace authority=\{authority\} \/>/);
  assert.equal(destination.includes('useOperationsCapability('), false);
  assert.match(home, /const operationsRoute = isOperationsPath\(normalizeRoutePathname\(location\.pathname\)\);/);
  assert.match(home, /\{operationsRoute && user && <OperationsDestination authority=\{operationsAuthority\} \/>\}/);
});

test('the destination admits an account on capability alone, never on a role label', () => {
  // The role-coercion guard yields to the route, and the route's own gate reads no role at all.
  assert.match(home, /if \(operationsRoute\) return;/);
  const gate = home.match(/const operationsRoute = [\s\S]*?\n/);
  assert.ok(gate);
  assert.doesNotMatch(gate[0], /role/);

  // A direct link or a refresh on /operations is treated as a protected destination.
  assert.match(
    home,
    /const isProtectedRoute = path === '\/tenant' \|\| path === '\/admin' \|\| path === '\/crm'\s*\n\s*\|\| isOperationsPath\(path\);/
  );

  // Signing in from an expired session returns the visitor to the destination they chose. Restoring
  // the route grants nothing: the destination re-verifies and fails closed on its own.
  assert.match(home, /if \(isOperationsPath\(pendingLessor\)\) \{/);
  assert.match(home, /isOperationsPath\(currentPath\)\s*\n\s*\? operationsReturnPath\(currentPath, currentSearch\)/);
});

/* 2. One shared authority ---------------------------------------------------------- */

test('exactly one capability state machine drives the entry, the destination and the consoles', () => {
  const hookCalls = [home, navbar, dashboard, admin, destination]
    .reduce((total, source) => total + (source.match(/useOperationsCapability\(/g) ?? []).length, 0);
  assert.equal(hookCalls, 1);
  assert.match(home, /useOperationsCapability\(\{ workspaceEntry: operationsSurface \}\)/);
  // The surface is *named* rather than reported as a boolean: an embedded console workspace and the
  // dedicated destination are two different entries, so a shared "open" flag could never distinguish
  // them. Every transition therefore re-verifies, and the runtime behaviour is proven by the
  // browser suite (`browser-verification/operations-destination/run.mjs`, scenarios 9-10).
  assert.match(
    home,
    /const operationsSurface = operationsRoute\s*\n\s*\? 'destination'\s*\n\s*: embeddedOperationsOpen\s*\n\s*\? \(role === 'EMPLOYEE' \? 'embedded:employee' : 'embedded:admin'\)\s*\n\s*: null;/
  );
  assert.match(workspace, /const accessGranted = authority\.state === 'granted' && authority\.entryAdmitted;/);

  // The consoles consume the shared authority and report their own surface upward, so the single
  // instance keeps its focus/visibility/cadence revalidation while either surface is on screen.
  assert.match(dashboard, /onOperationsWorkspaceOpenChange\?\.\(activeTab === 'operations'\);/);
  assert.match(admin, /onOperationsWorkspaceOpenChange\?\.\(activeTab === 'operations'\);/);
  assert.match(home, /onOperationsWorkspaceOpenChange=\{setEmbeddedOperationsOpen\}/);
});

/* 2b. Entry-scoped admission ------------------------------------------------------- */

test('authority is admitted per entry, never inherited from a previously open surface', () => {
  // Exactly one owner still: the entry machinery lives inside the same shared hook.
  assert.match(authorityHook, /workspaceEntry\?: string \| null;/);
  assert.equal((authorityHook.match(/export const useOperationsCapability = \(/g) ?? []).length, 1);

  // Render is side-effect free (Package 2 correction A): a render pass only *derives* whether the
  // surface it was asked to render is the surface React actually committed. No render-phase ref is
  // advanced, so a concurrent render React suspends, interrupts or discards can never advance ownership
  // or retire the still-committed entry's checks.
  assert.doesNotMatch(authorityHook, /entryRef\.current = \{ key: workspaceEntry/);
  assert.doesNotMatch(authorityHook, /entryRef\.current\.key !== workspaceEntry/);
  assert.match(
    authorityHook,
    /const \[committedEntry, setCommittedEntry\] = useState<\{ key: string \| null; generation: number \} \| null>\(null\);/
  );
  // Committed ownership advances only in the commit-phase layout effect, which a discarded render
  // never reaches, and which is idempotent across a StrictMode remount.
  assert.match(
    authorityHook,
    /useLayoutEffect\(\(\) => \{\s*\n\s*mountedRef\.current = true;\s*\n\s*const committed = committedEntryRef\.current;\s*\n\s*if \(committed !== null && committed\.key === workspaceEntry\) return;/
  );
  assert.match(authorityHook, /committedEntryRef\.current = \{ key: workspaceEntry, generation \};/);
  // The render gate compares the requested surface with the committed one, so the first render of a
  // requested entry is withheld without any effect having to run.
  assert.match(
    authorityHook,
    /const entryAdmitted = workspaceOpen\s*\n\s*&& committedEntry !== null\s*\n\s*&& committedEntry\.key === workspaceEntry/
  );
  // A grant only admits the committed entry generation it was actually produced for.
  assert.match(
    authorityHook,
    /if \(admittedEntry === committedEntryRef\.current\?\.generation\) setAdmission\(\{ generation: admittedEntry, session \}\);/
  );
  // Admission is bound to the exact credential as well as the entry.
  assert.match(
    authorityHook,
    /const entryAdmitted = workspaceOpen[\s\S]{0,300}isOperationsSessionActive\(admission\.session\);/
  );
  // The dispatch gate refuses a command whose entry is no longer the admitted one.
  assert.match(
    authorityHook,
    /const committed = committedEntryRef\.current;\s*\n\s*if \(committed === null \|\| admission === null \|\| admission\.generation !== committed\.generation\s*\n\s*\|\| !sameOperationsCredential\(admission\.session, session\)\) return false;/
  );
  // Entering a surface always issues its own check; leaving one does not need a new read.
  assert.match(authorityHook, /source: key === null \? 'background' : 'entry'/);
  assert.doesNotMatch(authorityHook, /verifyOnEntry/);
  // A session change clears the admission, so the next account verifies for the entry still on screen.
  assert.match(authorityHook, /setAdmission\(null\);\s*\n\s*setCapabilities\(null\);/);
});

/* 2c. Entry-scoped pending-check lifecycle (Package 2 correction) ------------------ */

test('a superseded entry retires its own pending checks, never the current mutation check', () => {
  // Every pending check is owned by the exact session, workspace-entry generation and purpose that
  // issued it, so its lifecycle can be decided from those three facts alone.
  assert.match(authorityHook, /interface PendingCapabilityCheck \{[\s\S]{0,200}entryGeneration: number;/);
  assert.match(
    authorityHook,
    /pendingChecksRef\.current\.set\(id, \{ session, source, entryGeneration: entry, control, detach \}\);/
  );

  // The superseded entry's pending checks are retired from the mutation gate immediately — in the
  // commit-phase layout effect that establishes the new entry, before any dispatch gate can observe the
  // new authority, and never during a render that React may discard.
  assert.match(authorityHook, /retireObsoleteEntryChecks\(pendingChecksRef\.current, generation\);/);
  assert.doesNotMatch(authorityHook, /retireObsoleteEntryChecks\(pendingChecksRef\.current, entryGeneration\);/);
  assert.match(authorityHook, /useLayoutEffect\(\(\) => \{[\s\S]{0,1200}retireObsoleteEntryChecks\(pendingChecksRef\.current, generation\);/);
  // A mutation pre-flight is the *current operation's own* security check and owns its own abort
  // lifecycle, so it must never be retired as though it were an obsolete entry check...
  assert.match(authorityHook, /if \(check\.source === 'mutation'\) continue;/);
  // ...while a check that belongs to the entry now on screen is untouched, so a genuinely current,
  // still-pending revalidation keeps failing the dispatch gate closed.
  assert.match(authorityHook, /if \(check\.entryGeneration === currentEntryGeneration\) continue;/);
  // Retiring a check aborts its transport and removes its gate entry without waiting for it to settle.
  assert.match(authorityHook, /pending\.delete\(id\);\s*\n\s*check\.control\.abort\(\);/);

  // The correction stays inside the one shared authority: no second hook or authority owner was added.
  assert.equal((authorityHook.match(/export const useOperationsCapability = \(/g) ?? []).length, 1);
});

/* 2d. Cancelled-mutation-check retirement (Package 2 correction B) ---------------- */

test('a cancelled mutation check is retired synchronously, by its own id, without waiting to settle', () => {
  // A mutation pre-flight belongs to the operation that asked for it. When that operation aborts — the
  // workspace unmounts, the account changes, authority is withdrawn — the check's gate entry must be
  // removed at once, because its transport may never settle and must not keep failing later work closed.
  assert.match(
    authorityHook,
    /const forwardAbort = \(\): void => \{\s*\n\s*detach\(\);\s*\n\s*retireOwnPendingCheck\(\);\s*\n\s*control\.abort\(\);\s*\n\s*\};/
  );
  // Retirement is keyed by the check's own id and controller, so an old finalizer can never remove a
  // newer request's gate.
  assert.match(
    authorityHook,
    /const registered = pendingChecksRef\.current\.get\(id\);\s*\n\s*if \(registered !== undefined && registered\.control === control\) pendingChecksRef\.current\.delete\(id\);/
  );
  // It runs on the abort listener itself, so no network settlement is awaited before the gate opens.
  assert.match(authorityHook, /externalSignal\.addEventListener\('abort', forwardAbort, \{ once: true \}\);/);
  // An already-aborted operation may not register a live gate at all.
  assert.match(authorityHook, /if \(mode\.signal\?\.aborted\) return Promise\.resolve\(false\);/);
  // The listener is detached on every definitive cleanup path (the retire helpers and the finalizer), so
  // listeners cannot accumulate on a long-lived signal.
  assert.match(authorityHook, /const detach = \(\): void => \{\s*\n\s*externalSignal\?\.removeEventListener\('abort', forwardAbort\);\s*\n\s*\};/);
  assert.match(authorityHook, /check\.detach\(\);\s*\n\s*pending\.delete\(id\);/);
  assert.match(authorityHook, /} finally \{\s*\n\s*detach\(\);/);
  // A late result from a cancelled check can never establish authority: an aborted control is refused
  // before any verdict is applied.
  assert.match(authorityHook, /if \(!isCurrent\(\)\) return false;\s*\n\s*if \(control\.signal\.aborted\) return false;/);

  // The correction stays inside the one shared authority: no second hook or authority owner was added.
  assert.equal((authorityHook.match(/export const useOperationsCapability = \(/g) ?? []).length, 1);
});

/* 2e. Committed-fact revalidation inputs (Package 2 correction) -------------------- */

test('activity revalidation reads committed ownership, never a render-phase prop mirror', () => {
  // The superseded input of the focus/visibility decision: the requested `workspaceEntry` prop and a
  // render-pass state value mirrored into refs *during render*. Both mirrors are gone, and no ref is
  // assigned at render scope anywhere in the hook any more.
  assert.doesNotMatch(authorityHook, /workspaceOpenRef/);
  assert.doesNotMatch(authorityHook, /stateRef/);
  assert.equal(
    /^ {2}[A-Za-z_$][A-Za-z0-9_$]*Ref\.current\s*=(?!=)/m.test(authorityHook),
    false,
    'a render pass must not assign a ref'
  );

  // The activity guard derives openness from the committed entry generation and reads the newest
  // *settled* verdict, so a render React suspends, interrupts or discards cannot change whether the
  // entry that is actually on screen is revalidated — a discarded exit render can no longer suppress
  // the still-committed entry's check, and a discarded open render cannot start polling for a
  // committed closed surface.
  assert.match(
    authorityHook,
    /const committed = committedEntryRef\.current;\s*\n\s*const committedWorkspaceOpen = committed !== null && committed\.key !== null;\s*\n\s*if \(!committedWorkspaceOpen && verdictRef\.current\.state === 'granted'\) return;/
  );
  // The other two halves of the approved cadence are unchanged: a hidden document is never revalidated,
  // and a non-granted verdict is still recovered from focus/visibility while closed.
  assert.match(
    authorityHook,
    /if \(typeof document !== 'undefined' && document\.visibilityState === 'hidden'\) return;/
  );
  assert.match(authorityHook, /void runVerification\(captureOperationsSession\(\)\);/);
  assert.match(authorityHook, /window\.addEventListener\('focus', revalidateOnActivity\);/);
  assert.match(authorityHook, /document\.addEventListener\('visibilitychange', onVisibilityChange\);/);
  // The correction stays inside the one shared authority: no second hook or authority owner was added.
  assert.equal((authorityHook.match(/export const useOperationsCapability = \(/g) ?? []).length, 1);
});

/* 2f. Sharing eligibility (Package 2 correction: obsolete in-flight recovery) ------ */

test('an issued capability check is reused only while it is still eligible', () => {
  // Reuse is decided against the live pending-check registry — the authority for what is still running —
  // and never from the cached pointer alone. Every retirement path removes the check from that registry
  // in the same tick it aborts the transport, and a retired verification's promise deliberately never
  // settles, so an obsolete promise must never stand in for the fresh read a caller needs. The exact
  // runtime behaviour is proven in `browser-verification/operations-entry-commit/run.mjs` (scenario 7),
  // including the negative control that reinserts the superseded rule.
  assert.match(authorityHook, /const isInFlightCheckShareable = \(/);
  assert.match(authorityHook, /const registered = pending\.get\(shared\.id\);/);
  assert.match(
    authorityHook,
    /if \(registered === undefined \|\| registered\.control\.signal\.aborted\) return false;/
  );
  assert.match(authorityHook, /const shared = inFlightRef\.current;/);
  assert.match(
    authorityHook,
    /isInFlightCheckShareable\(pendingChecksRef\.current, shared, session, source, entry\)/
  );
  // A mutation pre-flight is still never shared in either direction.
  assert.match(authorityHook, /if \(source === 'mutation' \|\| shared\.source === 'mutation'\) return false;/);
  // The superseded registry-blind rule is gone.
  assert.doesNotMatch(authorityHook, /const shareable = source === 'background'/);
  // A transition that retires the superseded entry's checks also drops a pointer to a retired check, so
  // the very next caller considers no candidate at all before it transmits a fresh read.
  assert.match(
    authorityHook,
    /const pointedAt = inFlightRef\.current;\s*\n\s*if \(pointedAt !== null && !pendingChecksRef\.current\.has\(pointedAt\.id\)\) inFlightRef\.current = null;/
  );
  // The correction stays inside the one shared authority: no second hook or authority owner was added.
  assert.equal((authorityHook.match(/export const useOperationsCapability = \(/g) ?? []).length, 1);
});

/* 3. Navigation entry -------------------------------------------------------------- */

test('the Operations entry is offered only after verified capability, as a deliberate choice', () => {
  assert.match(navbar, /const showOperationsEntry = Boolean\(onOpenOperations\) && operationsAuthority\?\.canEnter === true;/);
  assert.doesNotMatch(
    navbar.match(/const showOperationsEntry = .*\n/)[0],
    /role|ADMIN|EMPLOYEE|SUB_ADMIN/
  );

  // Offered on desktop navigation and in the account menu, and it never navigates by itself.
  const headerEntry = navbar.match(/\{showOperationsEntry && \(\s*\n\s*<button\s*\n\s*type="button"\s*\n\s*onClick=\{openOperations\}\s*\n\s*title="Operations"[\s\S]*?className=\{`hidden min-h-11/);
  assert.ok(headerEntry, 'a labelled desktop entry must exist and stay hidden on compact viewports');
  const menuEntry = navbar.match(/\{showOperationsEntry && \(\s*\n\s*<button\s*\n\s*type="button"\s*\n\s*onClick=\{openOperations\}[\s\S]*?min-h-11 w-full/);
  assert.ok(menuEntry, 'the account menu must carry the same entry for compact viewports');
  assert.match(navbar, /onOpenOperations\(\);/);
  assert.equal(/useEffect\(\(\) => \{\s*openOperations\(\)/.test(navbar), false, 'no automatic navigation');

  // The entry and the employee-console tab are different destinations, so their accessible names
  // must stay distinguishable - both for assistive technology and for element selection.
  assert.equal(navbar.includes('Operations workspace'), false);
});

/* 4. Authentication identity safety ------------------------------------------------ */

test('a malformed login response cannot establish a fabricated session', () => {
  assert.equal(authModal.includes('data.userId || 1'), false);
  // No conversion of the raw field survives in the component at all: the strict boundary owns it.
  assert.equal(/Number\(data\.userId\)|parseInt\(data\.userId/.test(authModal), false);
  assert.equal(authModal.includes("localStorage.setItem('pathome_auth_token'"), false);

  // The response is interpreted by the strict module, which persists the token only after every
  // required field - including a positively proven safe-integer id - has been validated.
  assert.match(authModal, /const established = establishAuthenticatedSession\(data, \{ email, fullName \}\);/);
  assert.match(authModal, /if \(!established\.ok\) \{\s*\n\s*setErrorMessage\(authenticationRejectionMessage\(established\.reason\)\);/);
  assert.match(authModal, /id: established\.userId,/);
  assert.ok(
    authModal.indexOf('establishAuthenticatedSession(data') > 0,
    'the authentication boundary must be consulted before any session state is produced'
  );

  const identity = read('../utils/authIdentity.ts');
  // The permitted JSON type is proven before any conversion, and only a positive safe integer passes.
  assert.match(identity, /if \(typeof raw === 'number'\) \{\s*\n\s*return Number\.isSafeInteger\(raw\) && raw > 0 \? raw : null;/);
  assert.match(identity, /const POSITIVE_INTEGER_TEXT = \/\^\[1-9\]\[0-9\]\*\$\/;/);
  assert.equal(/parseInt\(/.test(identity), false);
  // The token is written only after the identity was validated, and a rejection writes nothing.
  assert.ok(
    identity.indexOf('Number.isSafeInteger') < identity.indexOf('localStorage.setItem(AUTH_TOKEN_STORAGE_KEY'),
    'validation must precede persisting the session token'
  );
});

/* 5. 401 versus 403 semantics ------------------------------------------------------ */

test('a 401 ends the session while a 403 only reports insufficient permission', async () => {
  const events = [];
  const previousWindow = globalThis.window;
  const previousStorage = globalThis.localStorage;
  globalThis.window = { dispatchEvent: event => events.push(event.type) };
  // An exact credential stub keeps the decision independent of the host's storage implementation.
  globalThis.localStorage = {
    getItem: key => (key === 'pathome_auth_token' ? 'tok-1' : null),
    setItem: () => undefined,
    removeItem: () => undefined
  };

  try {
    const unauthorized = await createApiRequestError(
      { status: 401, json: async () => ({ message: 'Unauthorized' }) },
      'Failed',
      false,
      'tok-1'
    );
    assert.equal(unauthorized.status, 401);
    assert.deepEqual(events, ['pathome_session_expired']);

    events.length = 0;
    const forbidden = await createApiRequestError(
      { status: 403, json: async () => ({ message: 'Forbidden' }) },
      'Failed',
      false,
      'tok-1'
    );
    assert.equal(forbidden.status, 403);
    assert.match(forbidden.message, /do not have permission/);
    assert.deepEqual(events, [], 'an authenticated permission denial must not sign the user out');

    events.length = 0;
    const notFound = await createApiRequestError({ status: 404, json: async () => ({}) }, 'Failed', false, 'tok-1');
    assert.equal(notFound.status, 404);
    assert.deepEqual(events, []);

    // A response that belongs to a previous credential may never end the current session.
    events.length = 0;
    await createApiRequestError({ status: 401, json: async () => ({}) }, 'Failed', false, 'tok-stale');
    assert.deepEqual(events, []);
  } finally {
    globalThis.window = previousWindow;
    globalThis.localStorage = previousStorage;
  }
});
