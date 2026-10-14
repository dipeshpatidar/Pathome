/**
 * Package 2 controlled browser verification — capability-gated `/operations` navigation.
 *
 * SIMULATED BACKEND. This suite runs the real built app (`frontend-web/dist`) in Chromium with every
 * `/api/**` response produced in-process by the controllable stub below. Nothing about the HTTP
 * contract is real here, so **no result from this file may be reported as a real-backend pass**. It
 * exists because the honest positive case cannot be produced end to end yet: the disposable database
 * has no Operating Team, so no account can hold an effective `OPS_COORDINATE@TEAM` grant
 * (BLOCKED — PACKAGE 3 DEPENDENCY, recorded by
 * `../operations-destination-real-backend/run.mjs`).
 *
 * What is real: the production React tree, the real router, the real `useOperationsCapability`
 * state machine, the real workspace module, localStorage, focus events, and the exact requests the
 * app transmits (asserted from the stub's server-side log).
 *
 * Usage:
 *   npm --prefix frontend-web run build
 *   node frontend-web/browser-verification/operations-destination/run.mjs
 */
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;

const DIST = fileURLToPath(new URL('../../dist/', import.meta.url));
const TEAM_ROLE = 'TENANT';
const TOKEN_A = 'controlled-token-tenant-a';
const TOKEN_B = 'controlled-token-tenant-b';
const TOKEN_NO_GRANT = 'controlled-token-no-grant';
const TOKEN_INACTIVE = 'controlled-token-inactive';

const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2'
};

const GRANT_A = {
  capability: 'OPS_COORDINATE',
  scopeType: 'TEAM',
  cityId: 1,
  cityDisplayName: 'Indore',
  teamId: 7,
  teamDisplayName: 'Team A Coordination'
};
const GRANT_B = {
  capability: 'OPS_COORDINATE',
  scopeType: 'TEAM',
  cityId: 2,
  cityDisplayName: 'Pune',
  teamId: 9,
  teamDisplayName: 'Team B Coordination'
};

let failures = 0;
let checks = 0;
const check = (label, passed, detail = '') => {
  checks += 1;
  if (!passed) failures += 1;
  process.stdout.write(`${passed ? 'PASS' : 'FAIL'}  ${label}${detail ? `  (${detail})` : ''}\n`);
};
const checkEqual = (label, actual, expected) =>
  check(label, actual === expected, `expected=${JSON.stringify(expected)} actual=${JSON.stringify(actual)}`);

/* ------------------------------------------------------------------ *
 * Controllable stub
 * ------------------------------------------------------------------ */

const stub = {
  log: [],
  /** 'auto' derives the verdict from the credential; the rest force one response shape. */
  mode: 'auto',
  /** Credentials that are denied even in 'auto' mode, so one account can be denied at a time. */
  denyTokens: new Set(),
  holdCapabilities: false,
  held: [],
  /**
   * The sign-in response the stub answers with. `malformed-identity` reproduces the reported defect
   * (`userId: true`, which `Number(true)` coerces to the real account id 1); `valid` is the ordinary
   * contract, used as the regression control.
   */
  loginMode: null,
  reset() {
    this.log.length = 0;
    this.mode = 'auto';
    this.denyTokens.clear();
    this.holdCapabilities = false;
    this.loginMode = null;
    this.held.splice(0).forEach((entry) => entry.release());
  },
  requests(suffix) {
    return this.log.filter((entry) => entry.path.endsWith(suffix));
  },
  /**
   * Releases held capability responses. With no arguments every held response is released (the
   * original behaviour). A count releases only that many, oldest first, and an explicit answer
   * overrides the verdict captured when the request arrived — which is how an authority revocation
   * that happens *while a check is in flight* is reproduced.
   */
  releaseHeld(count = Number.POSITIVE_INFINITY, answer = undefined) {
    const held = this.held.splice(0, count);
    held.forEach((entry) => entry.release(answer));
    return held.length;
  }
};

const capabilitiesFor = (token) => {
  if (stub.mode === 'error') return { status: 500, body: { status: 500, message: 'unavailable' } };
  if (stub.mode === 'none') return { status: 200, body: { staffActive: true, capabilities: [] } };
  if (stub.mode === 'inactive') {
    return { status: 200, body: { staffActive: false, capabilities: [GRANT_A] } };
  }
  if (stub.denyTokens.has(token)) return { status: 200, body: { staffActive: true, capabilities: [] } };
  if (token === TOKEN_B) return { status: 200, body: { staffActive: true, capabilities: [GRANT_B] } };
  if (token === TOKEN_NO_GRANT) return { status: 200, body: { staffActive: true, capabilities: [] } };
  if (token === TOKEN_INACTIVE) {
    return { status: 200, body: { staffActive: false, capabilities: [GRANT_A] } };
  }
  return { status: 200, body: { staffActive: true, capabilities: [GRANT_A] } };
};

const emptyWorkPage = { items: [], totalCount: 0, page: 0, size: 20, totalPages: 0 };

const apiResponse = (token, url) => {
  const path = url.pathname;
  if (path === '/api/v1/auth/login') {
    if (stub.loginMode === 'malformed-identity') {
      return {
        status: 200,
        body: {
          token: 'controlled-login-token',
          userId: true,
          email: 'controlled.login@test',
          fullName: 'Controlled Login',
          role: 'ROLE_TENANT'
        }
      };
    }
    if (stub.loginMode === 'valid') {
      return {
        status: 200,
        body: {
          token: 'controlled-login-token',
          userId: 61,
          email: 'controlled.login@test',
          fullName: 'Controlled Login',
          role: 'ROLE_TENANT'
        }
      };
    }
    return { status: 404, body: { status: 404, message: 'not stubbed' } };
  }
  if (path === '/api/v1/staff/me/capabilities') return capabilitiesFor(token);
  if (path === '/api/v1/notifications') return { status: 200, body: [] };
  if (path === '/api/v1/operations/my-work') return { status: 200, body: emptyWorkPage };
  if (path === '/api/v1/operations/claimable-work') return { status: 200, body: emptyWorkPage };
  return { status: 404, body: { status: 404, message: 'not stubbed' } };
};

const server = createServer(async (request, response) => {
  const url = new URL(request.url, 'http://oe.local');
  const path = url.pathname;

  if (path.startsWith('/api/')) {
    const token = (request.headers.authorization || '').replace(/^Bearer\s+/i, '');
    const record = { method: request.method, path, token };
    stub.log.push(record);

    const answer = apiResponse(token, url);
    const respond = (override) => {
      const settled = override ?? answer;
      response.writeHead(settled.status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      response.end(JSON.stringify(settled.body));
    };

    if (stub.holdCapabilities && path === '/api/v1/staff/me/capabilities') {
      stub.held.push({ record, answer, release: respond });
      return;
    }
    respond();
    return;
  }

  const file = path === '/' ? '/index.html' : path;
  try {
    const contents = await readFile(join(DIST, file));
    response.writeHead(200, { 'Content-Type': CONTENT_TYPES[extname(file)] || 'application/octet-stream' });
    response.end(contents);
  } catch {
    const shell = await readFile(join(DIST, 'index.html'));
    response.writeHead(200, { 'Content-Type': CONTENT_TYPES['.html'] });
    response.end(shell);
  }
});

await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const BASE = `http://127.0.0.1:${server.address().port}`;

/* ------------------------------------------------------------------ *
 * Page helpers
 * ------------------------------------------------------------------ */

const contextFor = async (browser, { path, storage, viewport = { width: 1280, height: 900 } }) => {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage();
  await page.addInitScript((entries) => {
    entries.forEach(([key, value]) => window.localStorage.setItem(key, value));
  }, storage);
  await page.goto(`${BASE}${path}`);
  return { context, page };
};

const identity = (token, id, role = TEAM_ROLE) => [
  ['pathome_auth_token', token],
  ['pathome_role', role],
  ['pathome_user', JSON.stringify({ id, role, fullName: `Controlled User ${id}`, email: `user${id}@controlled.test` })]
];

const navEntry = (page) => page.getByRole('button', { name: 'Operations', exact: true });
const restrictedNotice = (page) => page.getByText(/don.t have access to Operations/i).first();
const workspaceSubnav = (page) => page.getByRole('navigation', { name: 'Operations queues' });
/** The workspace's own region, so workspace controls are never confused with another surface's. */
const workspaceRegion = (page) => page.locator('section[aria-label="Operations workspace"]');

const settle = (page, ms = 700) => page.waitForTimeout(ms);
const pathname = (page) => new URL(page.url()).pathname;
/** Every protected Operations read the app has transmitted since the last `stub.reset()`. */
const protectedReads = () => stub.log.filter((entry) => entry.path.startsWith('/api/v1/operations/')).length;
/** The workspace's own "an entry verdict is still pending" state. */
const pendingEntryNotice = (page) =>
  page.getByText(/Work queues stay hidden until it is confirmed/i).first();

/* ================================================================== *
 * 1. A Tenant-role user with a real capability is offered the destination.
 * ================================================================== */

const browser = await chromium.launch({ channel: 'chromium' });

try {
  process.stdout.write('\n=== 1. a Tenant-role Executive is offered Operations ===\n');
  {
    stub.reset();
    const { context, page } = await contextFor(browser, { path: '/tenant', storage: identity(TOKEN_A, 41) });
    await navEntry(page).waitFor({ state: 'visible', timeout: 10000 });
    check('the destination is offered once the grant is verified', await navEntry(page).isVisible());
    checkEqual('the app never navigates there on its own', pathname(page), '/tenant');
    check(
      'the offer came from the live capability endpoint',
      stub.requests('/staff/me/capabilities').length >= 1,
      `${stub.requests('/staff/me/capabilities').length} call(s)`
    );
    check(
      'every capability call carried the signed-in credential',
      stub.requests('/staff/me/capabilities').every((entry) => entry.token === TOKEN_A)
    );
    const box = await navEntry(page).boundingBox();
    check('the entry meets the 44px minimum target', box !== null && box.height >= 44, `height=${box?.height}`);
    await context.close();
  }

  /* 2. The user deliberately chooses it. */
  process.stdout.write('\n=== 2. choosing Operations opens the dedicated destination ===\n');
  {
    stub.reset();
    const { context, page } = await contextFor(browser, { path: '/tenant', storage: identity(TOKEN_A, 41) });
    await navEntry(page).waitFor({ state: 'visible', timeout: 10000 });
    await navEntry(page).click();
    await workspaceSubnav(page).waitFor({ state: 'visible', timeout: 10000 });
    checkEqual('the destination owns the /operations path', pathname(page), '/operations');
    check('the existing workspace renders at the destination', await workspaceSubnav(page).isVisible());
    check(
      'entering re-verified authority with the server',
      stub.requests('/staff/me/capabilities').length >= 2,
      `${stub.requests('/staff/me/capabilities').length} calls`
    );
    check(
      'protected work was requested only with the verified credential',
      stub.log
        .filter((entry) => entry.path.startsWith('/api/v1/operations/'))
        .every((entry) => entry.token === TOKEN_A)
    );
    await context.close();
  }

  /* 3. Direct navigation and refresh always re-verify, and disclose nothing first. */
  process.stdout.write('\n=== 3. direct navigation and refresh ===\n');
  {
    stub.reset();
    const { context, page } = await contextFor(browser, { path: '/operations', storage: identity(TOKEN_A, 41) });
    await workspaceSubnav(page).waitFor({ state: 'visible', timeout: 10000 });
    checkEqual('a direct link is admitted on the verified capability', pathname(page), '/operations');

    stub.reset();
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await settle(page);
    check(
      'restoring focus re-verifies while the destination is open',
      stub.requests('/staff/me/capabilities').length >= 1,
      `${stub.requests('/staff/me/capabilities').length} call(s)`
    );

    stub.reset();
    stub.holdCapabilities = true;
    await page.reload();
    await settle(page, 500);
    checkEqual(
      'a refresh fetches no protected work before the verdict',
      stub.log.filter((entry) => entry.path.startsWith('/api/v1/operations/')).length,
      0
    );
    check(
      'a refresh shows the verifying state, not stale work',
      await page.getByText(/Verifying your Operations access/i).first().isVisible().catch(() => false),
      'checking state shown'
    );
    stub.holdCapabilities = false;
    stub.releaseHeld();
    await workspaceSubnav(page).waitFor({ state: 'visible', timeout: 10000 });
    check('the destination opens once the refresh verdict arrives', await workspaceSubnav(page).isVisible());
    await context.close();
  }

  /* 4. Denial shapes are refused, and a direct visit discloses nothing. */
  process.stdout.write('\n=== 4. every non-granted shape is refused ===\n');
  for (const [label, storage, mode] of [
    ['no grant at all', identity(TOKEN_NO_GRANT, 42), 'none'],
    ['inactive staff profile', identity(TOKEN_INACTIVE, 43), 'auto'],
    ['a role label of ADMIN with no grant', identity(TOKEN_NO_GRANT, 44, 'ADMIN'), 'none'],
    ['a role label of EMPLOYEE with no grant', identity(TOKEN_NO_GRANT, 45, 'EMPLOYEE'), 'none']
  ]) {
    stub.reset();
    stub.mode = mode;
    const { context, page } = await contextFor(browser, { path: '/operations', storage });
    await settle(page, 900);
    check(`${label}: the destination refuses explicitly`, await restrictedNotice(page).isVisible().catch(() => false));
    checkEqual(
      `${label}: no protected work is ever requested`,
      stub.log.filter((entry) => entry.path.startsWith('/api/v1/operations/')).length,
      0
    );
    checkEqual(`${label}: the URL is not silently redirected`, pathname(page), '/operations');
    await context.close();
  }

  /* 5. Revocation withdraws the destination without a reload. */
  process.stdout.write('\n=== 5. revocation withdraws the destination ===\n');
  {
    stub.reset();
    const { context, page } = await contextFor(browser, { path: '/operations', storage: identity(TOKEN_A, 41) });
    await workspaceSubnav(page).waitFor({ state: 'visible', timeout: 10000 });
    stub.mode = 'none';
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await restrictedNotice(page).waitFor({ state: 'visible', timeout: 10000 });
    check('a revoked capability withdraws the workspace without a reload', await restrictedNotice(page).isVisible());
    checkEqual('the withdrawn destination reveals no queue', await workspaceSubnav(page).count(), 0);
    await page.goto(`${BASE}/tenant`);
    await settle(page, 900);
    checkEqual('the withdrawn navigation entry is gone as well', await navEntry(page).count(), 0);
    await context.close();
  }

  /* 6. A transient failure hides protected work and recovers through Retry. */
  process.stdout.write('\n=== 6. transient failure hides work and recovers ===\n');
  {
    stub.reset();
    stub.mode = 'error';
    const { context, page } = await contextFor(browser, { path: '/operations', storage: identity(TOKEN_A, 41) });
    const unavailable = page.getByText(/couldn.t verify your Operations access/i).first();
    await unavailable.waitFor({ state: 'visible', timeout: 10000 });
    check('a failed verification is reported, not hidden', await unavailable.isVisible(), 'unavailable state shown');
    checkEqual('no protected work is shown while verification has failed', await workspaceSubnav(page).count(), 0);
    stub.mode = 'auto';
    await workspaceRegion(page).getByRole('button', { name: /Retry/i }).click();
    await settle(page, 1500);
    const recovered = await workspaceSubnav(page).isVisible().catch(() => false);
    check(
      'Retry recovers the destination from an authoritative re-read',
      recovered,
      recovered
        ? ''
        : `capabilityCalls=${stub.requests('/staff/me/capabilities').length}`
    );
    await context.close();
  }

  /* 7. A stale response from another account can never authorize the next one. */
  process.stdout.write('\n=== 7. a stale response cannot authorize the next account ===\n');
  {
    stub.reset();
    // Account A holds a valid grant; Account B holds none. Only A's response is held in flight, so the
    // scenario proves that a late grant issued for A can never admit B.
    stub.denyTokens.add(TOKEN_B);
    stub.holdCapabilities = true;
    const { context, page } = await contextFor(browser, { path: '/operations', storage: identity(TOKEN_A, 41) });
    await settle(page, 500);
    check('Account A’s verification is held in flight', stub.held.length >= 1, `${stub.held.length} held`);

    await page.evaluate((entries) => {
      entries.forEach(([key, value]) => window.localStorage.setItem(key, value));
      window.dispatchEvent(new Event('pathome_auth_changed'));
    }, identity(TOKEN_B, 51));
    await settle(page, 400);

    stub.releaseHeld();
    stub.holdCapabilities = false;
    await settle(page, 1200);
    check(
      'Account A’s late grant does not admit Account B',
      !(await workspaceSubnav(page).isVisible().catch(() => false)),
      'no protected workspace revealed'
    );
    checkEqual(
      'Account B is still refused at the destination',
      await restrictedNotice(page).isVisible().catch(() => false),
      true
    );
    check(
      'no protected request was made with Account A’s credential after the switch',
      stub.log
        .filter((entry) => entry.path.startsWith('/api/v1/operations/'))
        .every((entry) => entry.token !== TOKEN_A),
      'Account B only'
    );
    await context.close();
  }

  /* 8. Layout integrity at the required viewports. */
  process.stdout.write('\n=== 8. responsive integrity ===\n');
  for (const width of [320, 375, 768, 1024, 1440]) {
    stub.reset();
    const { context, page } = await contextFor(browser, {
      path: '/operations',
      storage: identity(TOKEN_A, 41),
      viewport: { width, height: 900 }
    });
    await workspaceSubnav(page).waitFor({ state: 'visible', timeout: 10000 });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    check(`the admitted destination does not overflow at ${width}px`, overflow <= 1, `overflow=${overflow}px`);
    if (width < 1024) {
      checkEqual(`the compact header hides the wide entry at ${width}px`, await navEntry(page).count(), 0);
    }
    await context.close();
  }
  {
    stub.reset();
    const { context, page } = await contextFor(browser, {
      path: '/tenant',
      storage: identity(TOKEN_A, 41),
      viewport: { width: 375, height: 812 }
    });
    await settle(page, 900);
    await page.getByRole('button', { name: /Profile menu for/i }).first().click();
    const accountEntry = navEntry(page);
    await accountEntry.waitFor({ state: 'visible', timeout: 10000 });
    check('the compact viewports still offer the destination in the account menu', await accountEntry.isVisible());
    // The dropdown enters with a spring scale, so the target is measured only once it has settled.
    await settle(page, 800);
    const box = await accountEntry.boundingBox();
    check('the account-menu entry meets the 44px minimum target', box !== null && box.height >= 44, `height=${box?.height}`);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    check('the account menu does not overflow at 375px', overflow <= 1, `overflow=${overflow}px`);
    await context.close();
  }

  /* 9. Every entry is verified before a single protected read, and only its own verdict admits it. */
  process.stdout.write('\n=== 9. a new entry is verified before any protected read ===\n');
  {
    stub.reset();
    const { context, page } = await contextFor(browser, { path: '/tenant', storage: identity(TOKEN_A, 41) });
    await navEntry(page).waitFor({ state: 'visible', timeout: 10000 });

    // The destination's own entry verdict is held in flight.
    stub.reset();
    stub.holdCapabilities = true;
    await navEntry(page).click();
    await settle(page, 800);
    checkEqual('entering the destination keeps the /operations URL', pathname(page), '/operations');
    checkEqual('zero protected reads start while the entry verdict is held', protectedReads(), 0);
    check(
      'the destination reports that the entry is still being verified',
      await pendingEntryNotice(page).isVisible().catch(() => false)
    );
    checkEqual('no queue surface is revealed before the verdict', await workspaceSubnav(page).count(), 0);

    stub.holdCapabilities = false;
    const released = stub.releaseHeld();
    check('the held response was the entry verdict', released >= 1, `${released} held`);
    await workspaceSubnav(page).waitFor({ state: 'visible', timeout: 10000 });
    check('a valid entry verdict admits the destination', await workspaceSubnav(page).isVisible());
    check('protected reads begin only after that verdict', protectedReads() > 0, `${protectedReads()} read(s)`);
    check(
      'every protected read carried the verified credential',
      stub.log
        .filter((entry) => entry.path.startsWith('/api/v1/operations/'))
        .every((entry) => entry.token === TOKEN_A)
    );
    await context.close();
  }
  {
    // The same entry, refused: nothing may be read and nothing may be revealed.
    stub.reset();
    const { context, page } = await contextFor(browser, { path: '/tenant', storage: identity(TOKEN_A, 41) });
    await navEntry(page).waitFor({ state: 'visible', timeout: 10000 });
    stub.reset();
    stub.denyTokens.add(TOKEN_A);
    stub.holdCapabilities = true;
    await navEntry(page).click();
    await settle(page, 800);
    checkEqual('a refused entry: zero protected reads while the verdict is held', protectedReads(), 0);
    stub.holdCapabilities = false;
    stub.releaseHeld();
    await restrictedNotice(page).waitFor({ state: 'visible', timeout: 10000 });
    check('a refusing entry verdict withdraws the destination', await restrictedNotice(page).isVisible());
    checkEqual('a refused entry reads no protected work at all', protectedReads(), 0);
    checkEqual('a refused entry reveals no queue', await workspaceSubnav(page).count(), 0);
    await context.close();
  }

  /* 10. A transition between two already-open surfaces is a new entry. */
  process.stdout.write('\n=== 10. an embedded workspace transitioning to /operations re-verifies ===\n');
  for (const [label, role, consolePath, tabName] of [
    ['Employee console', 'EMPLOYEE', '/crm', 'Operations workspace'],
    ['Admin console', 'ADMIN', '/admin', 'Operations workspace']
  ]) {
    stub.reset();
    const { context, page } = await contextFor(browser, { path: consolePath, storage: identity(TOKEN_A, 41, role) });
    await page.getByRole('button', { name: tabName }).first().waitFor({ state: 'visible', timeout: 10000 });
    await page.getByRole('button', { name: tabName }).first().click();
    await workspaceSubnav(page).waitFor({ state: 'visible', timeout: 10000 });
    check(`${label}: the embedded workspace is admitted for its own entry`, await workspaceSubnav(page).isVisible());

    // The workspace is on screen and the surface never closes: only the destination changes.
    stub.reset();
    stub.holdCapabilities = true;
    await navEntry(page).click();
    await settle(page, 800);
    checkEqual(`${label}: the destination is entered`, pathname(page), '/operations');
    checkEqual(`${label}: zero protected reads before the destination's own verdict`, protectedReads(), 0);
    check(
      `${label}: the earlier entry's verdict does not admit the destination`,
      await pendingEntryNotice(page).isVisible().catch(() => false)
    );
    stub.holdCapabilities = false;
    stub.releaseHeld();
    await workspaceSubnav(page).waitFor({ state: 'visible', timeout: 10000 });
    check(`${label}: the destination's own verdict admits it`, await workspaceSubnav(page).isVisible());
    await context.close();
  }

  /* 11. An account switch during a pending entry check needs the new account's own verdict. */
  process.stdout.write('\n=== 11. a switch during a pending entry check needs the new verdict ===\n');
  {
    stub.reset();
    const { context, page } = await contextFor(browser, { path: '/tenant', storage: identity(TOKEN_A, 41) });
    await navEntry(page).waitFor({ state: 'visible', timeout: 10000 });
    stub.reset();
    stub.holdCapabilities = true;
    await navEntry(page).click();
    await settle(page, 500);
    const heldForA = stub.held.length;
    check('Account A’s entry check is held', heldForA >= 1, `${heldForA} held`);

    await page.evaluate((entries) => {
      entries.forEach(([key, value]) => window.localStorage.setItem(key, value));
      window.dispatchEvent(new Event('pathome_auth_changed'));
    }, identity(TOKEN_B, 51));
    await settle(page, 400);
    check('Account B’s own entry check was issued', stub.held.length > heldForA, `${stub.held.length} held`);

    // Only Account A's outdated completion is released: it can never admit B.
    stub.releaseHeld(heldForA);
    await settle(page, 600);
    checkEqual('Account A’s late completion admits nothing', protectedReads(), 0);
    checkEqual('Account B is still waiting for its own verdict', await workspaceSubnav(page).count(), 0);

    stub.holdCapabilities = false;
    stub.releaseHeld();
    await workspaceSubnav(page).waitFor({ state: 'visible', timeout: 10000 });
    check('Account B is admitted on its own verdict', await workspaceSubnav(page).isVisible());
    check(
      'no protected read carried Account A’s credential',
      stub.log
        .filter((entry) => entry.path.startsWith('/api/v1/operations/'))
        .every((entry) => entry.token !== TOKEN_A),
      'Account B only'
    );
    await context.close();
  }

  /* 12. An outdated entry verdict can never reveal protected work. */
  process.stdout.write('\n=== 12. an outdated entry verdict reveals nothing ===\n');
  {
    stub.reset();
    const { context, page } = await contextFor(browser, { path: '/tenant', storage: identity(TOKEN_A, 41) });
    await navEntry(page).waitFor({ state: 'visible', timeout: 10000 });

    // The first entry's verdict is held in flight while the surface is left and entered again.
    stub.reset();
    stub.holdCapabilities = true;
    await navEntry(page).click();
    await settle(page, 500);
    const staleHeld = stub.held.length;

    // Authority is revoked on the server, and the surface is left by ordinary in-app navigation.
    stub.mode = 'none';
    await page.getByRole('button', { name: 'Return to Pathome home' }).first().click();
    await settle(page, 400);
    check('leaving the surface is an in-app navigation', pathname(page) !== '/operations', pathname(page));
    checkEqual('leaving the surface needs no new verification', stub.held.length, staleHeld);

    // Re-entering is a newer entry, whose own verdict is the denial.
    await navEntry(page).click();
    await settle(page, 600);
    check('a newer entry verdict is issued', stub.held.length > staleHeld, `${stub.held.length} held`);

    // Release the *outdated* grant first, and only it.
    stub.releaseHeld(staleHeld, { status: 200, body: { staffActive: true, capabilities: [GRANT_A] } });
    await settle(page, 700);
    checkEqual('the outdated verdict reveals no protected work', protectedReads(), 0);
    checkEqual('the outdated verdict reveals no queue', await workspaceSubnav(page).count(), 0);

    stub.holdCapabilities = false;
    stub.releaseHeld();
    await restrictedNotice(page).waitFor({ state: 'visible', timeout: 10000 });
    check('the current verdict still decides the entry', await restrictedNotice(page).isVisible());
    checkEqual('the refused entry never reads protected work', protectedReads(), 0);
    await context.close();
  }

  /* 13. The sign-in boundary refuses a malformed identity, and the app stays unauthenticated. */
  process.stdout.write('\n=== 13. a malformed sign-in response leaves the app unauthenticated ===\n');
  {
    stub.reset();
    stub.loginMode = 'malformed-identity';
    const { context, page } = await contextFor(browser, { path: '/', storage: [] });
    await page.getByRole('button', { name: 'Sign in', exact: true }).first().click();
    const dialog = page.getByRole('dialog');
    await dialog.waitFor({ state: 'visible', timeout: 10000 });
    await dialog.locator('#pathome-auth-email').fill('controlled.login@test');
    await dialog.locator('#pathome-auth-password').fill('correct horse battery staple');
    await dialog.getByRole('button', { name: /^Sign In$/ }).click();
    await settle(page, 1200);
    const rejectionShown = await page
      .getByText(/Sign-in could not be verified because the response was incomplete/i)
      .first()
      .isVisible()
      .catch(() => false);

    // A build that coerces the response instead would sign in here and fail this check.
    check('a malformed identity is reported instead of signing the visitor in', rejectionShown);
    checkEqual(
      'no session token is persisted',
      await page.evaluate(() => window.localStorage.getItem('pathome_auth_token')),
      null
    );
    checkEqual(
      'no account is persisted',
      await page.evaluate(() => window.localStorage.getItem('pathome_user')),
      null
    );
    checkEqual('the visitor stays on the public page', pathname(page), '/');
    checkEqual('no protected Operations read was transmitted', protectedReads(), 0);
    check(
      'no request carried the fabricated credential',
      stub.log.every((entry) => entry.token !== 'controlled-login-token'),
      'the token was never persisted'
    );
    await context.close();
  }
  {
    // Regression control: the supported response still signs in and still reaches the tenant surface.
    stub.reset();
    stub.loginMode = 'valid';
    const { context, page } = await contextFor(browser, { path: '/', storage: [] });
    await page.getByRole('button', { name: 'Sign in', exact: true }).first().click();
    const dialog = page.getByRole('dialog');
    await dialog.waitFor({ state: 'visible', timeout: 10000 });
    await dialog.locator('#pathome-auth-email').fill('controlled.login@test');
    await dialog.locator('#pathome-auth-password').fill('correct horse battery staple');
    await dialog.getByRole('button', { name: /^Sign In$/ }).click();
    await page.waitForFunction(
      () => window.localStorage.getItem('pathome_auth_token') === 'controlled-login-token',
      null,
      { timeout: 10000 }
    );
    const stored = await page.evaluate(() => JSON.parse(window.localStorage.getItem('pathome_user')));
    check('a valid response still establishes the session', stored?.id === 61, `id=${stored?.id}`);
    await settle(page, 700);
    checkEqual('a valid sign-in still reaches the tenant surface', pathname(page), '/tenant');
    await context.close();
  }
} finally {
  await browser.close();
  server.close();
}

process.stdout.write(
  '\nSIMULATED BACKEND: this file proves navigation and authority behaviour only. It is not\n' +
    'real-backend evidence; the real-backend boundary results come from\n' +
    '../operations-destination-real-backend/run.mjs.\n'
);
process.stdout.write(`\n${checks} checks, ${failures} failure(s)\n`);
process.exit(failures === 0 ? 0 : 1);
