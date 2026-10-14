/**
 * Consolidated browser verification for the Operations (OE) workspace — Package 1A.
 *
 * Runs the real built app (frontend-web/dist) in Chromium with every HTTP request fulfilled
 * in-process by the controllable stub below. Nothing is mocked at the module level: the app, the
 * React tree, the fetch layer, localStorage and window events are the production ones.
 *
 * Covered here (see ../README.md for the mapping to the approved findings):
 *   - Admin / Sub-Admin / Employee entry gating from server capability responses.
 *   - Cross-account claim race with a deliberately paused response.
 *   - Revocation while a detail is open, transient verification failure with Retry, and recovery.
 *   - Persistent claim/release conflict feedback, including after detail closure.
 *   - Minimum 44x44 hit targets and 320/375/768/1440 layout with no horizontal overflow.
 *
 * Usage:  npm --prefix frontend-web run build && node frontend-web/browser-verification/operations-workspace/run.mjs
 * Requires Playwright with Chromium (installed globally in this environment).
 */
import { readFile } from 'node:fs/promises';
import { extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;

const DIST = fileURLToPath(new URL('../../dist/', import.meta.url));
const ORIGIN = 'http://oe.local';

const TOKEN_A = 'token-employee-a';
const TOKEN_B = 'token-employee-b';
const TOKEN_ADMIN = 'token-sub-admin-granted';
const TOKEN_ADMIN_NO_GRANT = 'token-sub-admin-ungranted';
const TOKEN_INACTIVE = 'token-inactive-staff';

const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2'
};

const now = Date.now();
const iso = (minutes) => new Date(now + minutes * 60_000).toISOString();

/* ------------------------------------------------------------------ *
 * Controllable stub backend
 * ------------------------------------------------------------------ */

const stub = {
  log: [],
  /** API paths the stub answered with its generic fallback — surfaced so an unexpected shape is visible. */
  unknown: [],
  /** 'auto' derives authority from the request token; the others force a response shape. */
  capabilityMode: 'auto',
  claimStatus: 204,
  releaseStatus: 204,
  /** Predicate deciding which request is held until the scenario releases it. */
  pauseMatcher: null,
  /**
   * When set, a held request's response is produced *before* it is held, so the caller can change the
   * stub's state while the request is still in flight and still observe the older response.
   */
  freezeHeldResponse: false,
  /** When set, the next ownership command is dropped in transit: transmitted, but no response arrives. */
  loseCommandResponse: false,
  /**
   * When set, a matching request answers with a controlled 500 instead of the real stub response, so a
   * reconciliation read can be made to fail on demand. Unlike the pause matcher it persists until the
   * scenario clears it (every matching read keeps failing).
   */
  failMatcher: null,
  /**
   * When set, a claimable item re-read omits `expectedVersion`, so the workspace cannot build a claim
   * command and must state that truthfully instead of sending anything.
   */
  dropClaimableVersion: false,
  held: [],
  reset() {
    this.log.length = 0;
    this.unknown.length = 0;
    this.capabilityMode = 'auto';
    this.claimStatus = 204;
    this.releaseStatus = 204;
    this.pauseMatcher = null;
    this.freezeHeldResponse = false;
    this.loseCommandResponse = false;
    this.failMatcher = null;
    this.dropClaimableVersion = false;
    this.held.length = 0;
  },
  pause(matcher) {
    this.pauseMatcher = matcher;
  },
  releaseHeld() {
    const held = this.held.splice(0);
    held.forEach((entry) => entry.release());
    return held.length;
  },
  /** Releases only the held requests a scenario selects, leaving the others in flight. */
  releaseHeldWhere(predicate) {
    const matched = this.held.filter((entry) => predicate(entry.record));
    this.held = this.held.filter((entry) => !predicate(entry.record));
    matched.forEach((entry) => entry.release());
    return matched.length;
  },
  requests(pathFragment, method) {
    return this.log.filter(
      (entry) => entry.path.includes(pathFragment) && (!method || entry.method === method)
    );
  }
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

const capabilitiesFor = (token) => {
  if (stub.capabilityMode === 'error') return { status: 500, body: { status: 500, message: 'boom' } };
  if (stub.capabilityMode === 'none') return { status: 200, body: { staffActive: true, capabilities: [], serverTime: iso(0) } };
  if (stub.capabilityMode === 'inactive') {
    return { status: 200, body: { staffActive: false, capabilities: [GRANT_A], serverTime: iso(0) } };
  }
  if (token === TOKEN_B) return { status: 200, body: { staffActive: true, capabilities: [GRANT_B], serverTime: iso(0) } };
  if (token === TOKEN_ADMIN_NO_GRANT) return { status: 200, body: { staffActive: true, capabilities: [], serverTime: iso(0) } };
  if (token === TOKEN_INACTIVE) {
    return { status: 200, body: { staffActive: false, capabilities: [GRANT_A], serverTime: iso(0) } };
  }
  return { status: 200, body: { staffActive: true, capabilities: [GRANT_A], serverTime: iso(0) } };
};

const SUMMARY_A = {
  targetType: 'REQUEST',
  targetId: 5200,
  expectedVersion: 7,
  cityId: 1,
  cityDisplayName: 'Indore',
  teamId: 7,
  teamDisplayName: 'Team A Coordination',
  status: 'RECEIVED',
  linkedRequestVersions: []
};
const OWNED_SESSION_A = {
  targetType: 'SESSION',
  targetId: 3108,
  expectedVersion: 6,
  cityId: 1,
  cityDisplayName: 'Indore',
  teamId: 7,
  teamDisplayName: 'Team A Coordination',
  status: 'DRAFT',
  updatedAt: iso(-60),
  scheduledAt: null,
  tenantConfirmationState: 'NOT_REQUIRED',
  linkedRequestVersions: [{ requestId: 501, version: 4 }]
};
const CLAIMABLE_B = {
  targetType: 'REQUEST',
  targetId: 6200,
  expectedVersion: 3,
  cityId: 2,
  cityDisplayName: 'Pune',
  teamId: 9,
  teamDisplayName: 'Team B Coordination',
  status: 'RECEIVED',
  linkedRequestVersions: []
};
const OWNED_SESSION_B = {
  targetType: 'SESSION',
  targetId: 7100,
  expectedVersion: 2,
  cityId: 2,
  cityDisplayName: 'Pune',
  teamId: 9,
  teamDisplayName: 'Team B Coordination',
  status: 'DRAFT',
  updatedAt: iso(-30),
  scheduledAt: null,
  tenantConfirmationState: 'NOT_REQUIRED',
  linkedRequestVersions: []
};

const page = (items) => ({ items, totalCount: items.length, page: 0, size: 20, totalPages: items.length ? 1 : 0 });

const apiResponse = (token, url) => {
  const path = url.pathname;
  if (path === '/api/v1/staff/me/capabilities') return capabilitiesFor(token);
  if (path === '/api/v1/notifications') return { status: 200, body: [] };

  const isAccountB = token === TOKEN_B;
  const owned = isAccountB ? [OWNED_SESSION_B] : [OWNED_SESSION_A];
  const claimable = isAccountB ? [CLAIMABLE_B] : [SUMMARY_A];

  if (path === '/api/v1/operations/my-work') {
    const attentionOnly = url.searchParams.get('attentionOnly') === 'true';
    return { status: 200, body: page(attentionOnly ? [] : owned) };
  }
  if (path === '/api/v1/operations/claimable-work') return { status: 200, body: page(claimable) };
  if (/^\/api\/v1\/operations\/claimable-work\/(REQUEST|SESSION)\/\d+$/.test(path)) {
    const id = Number(path.split('/').pop());
    const found = [...claimable, ...owned].find((item) => item.targetId === id);
    return found
      ? {
        status: 200,
        body: stub.dropClaimableVersion
          ? {
            targetType: found.targetType,
            targetId: id,
            status: found.status,
            cityId: found.cityId,
            cityDisplayName: found.cityDisplayName,
            teamId: found.teamId,
            teamDisplayName: found.teamDisplayName,
            linkedRequestVersions: found.linkedRequestVersions
          }
          : { ...found, id, status: found.status, expectedVersion: found.expectedVersion }
      }
      : { status: 404, body: { status: 404, message: 'Not found' } };
  }
  if (/^\/api\/v1\/operations\/visit-sessions\/\d+$/.test(path)) {
    const id = Number(path.split('/').pop());
    const found = owned.find((item) => item.targetId === id);
    if (!found) return { status: 404, body: { status: 404, message: 'Not found' } };
    return {
      status: 200,
      body: {
        sessionId: found.targetId,
        status: found.status,
        version: found.expectedVersion,
        city: found.cityDisplayName,
        areaName: 'Verification area',
        scheduledAt: null,
        reservedEndAt: null,
        durationMinutes: 120,
        zoneId: 'Asia/Kolkata',
        representativeUserId: null,
        assignedAt: null,
        items: [
          {
            itemId: 91,
            listingId: 8001,
            title: isAccountB ? 'Team B visit item' : 'Team A visit item',
            address: 'Stub address',
            city: found.cityDisplayName,
            sector: 'Stub sector',
            position: 1,
            sourceRequestId: 501,
            origin: 'TENANT_REQUESTED',
            confirmationStatus: 'PENDING',
            availabilityConfirmedAt: null,
            removedAt: null,
            removalReason: null,
            availabilityStartAt: null,
            availabilityEndAt: null,
            availabilityZoneId: null
          }
        ],
        linkedRequestVersions: found.linkedRequestVersions,
        teamId: found.teamId,
        teamDisplayName: found.teamDisplayName,
        coordinatorUserId: 42,
        updatedAt: found.updatedAt,
        tenantConfirmationState: found.tenantConfirmationState,
        startedAt: null,
        expiresAt: null,
        executionStateChangedAt: null
      }
    };
  }
  if (/^\/api\/v1\/operations\/visit-requests\/\d+$/.test(path)) {
    const id = Number(path.split('/').pop());
    const found = owned.find((item) => item.targetId === id) ?? (isAccountB ? null : SUMMARY_A);
    if (!found) return { status: 404, body: { status: 404, message: 'Not found' } };
    return {
      status: 200,
      body: {
        requestId: found.targetId,
        status: 'RECEIVED',
        version: found.expectedVersion,
        createdAt: '2026-10-12T21:25:30',
        cityId: found.cityId,
        cityDisplayName: found.cityDisplayName,
        teamId: found.teamId,
        teamDisplayName: found.teamDisplayName,
        listingId: 8001,
        listingTitle: 'Stub listing',
        listingAddress: 'Stub address',
        listingStatus: 'ACTIVE',
        listingCity: found.cityDisplayName,
        sector: 'Stub sector',
        availabilityStartAt: null,
        availabilityEndAt: null,
        availabilityZoneId: 'Asia/Kolkata',
        preferredAt: null,
        sessionId: null
      }
    };
  }
  if (/^\/api\/v1\/operations\/(visit-requests|visit-sessions)\/\d+\/(claim|release)$/.test(path)) {
    const status = path.endsWith('/claim') ? stub.claimStatus : stub.releaseStatus;
    return status === 204
      ? { status: 204, body: null }
      : { status, body: { status, message: status === 409 ? 'Ownership version is stale; refresh and retry' : 'Denied' } };
  }

  // Shells outside the OE scope still boot in these scenarios, so their collections answer empty.
  if (path === '/api/v1/admin/failed-uploads/count') return { status: 200, body: { count: 0 } };
  if (path === '/api/v1/admin/failed-uploads') return { status: 200, body: [] };
  if (path === '/api/v1/admin/drafts') return { status: 200, body: [] };
  stub.unknown.push(path);
  return {
    status: 200,
    body: {
      items: [],
      content: [],
      sessions: [],
      totalCount: 0,
      page: 0,
      size: 20,
      totalPages: 0
    }
  };
};

const handleApi = async (route, request, url) => {
  const token = (request.headers()['authorization'] ?? '').replace('Bearer ', '');
  const record = {
    method: request.method(),
    path: url.pathname + url.search,
    token,
    body: request.postData()
  };
  stub.log.push(record);

  // A command whose response never arrives: it really was transmitted, but the outcome is unknown to
  // the client. Used to prove the workspace reports an unconfirmed outcome instead of guessing.
  if (stub.loseCommandResponse && record.method === 'POST' && /\/(claim|release)$/.test(url.pathname)) {
    stub.loseCommandResponse = false;
    await route.abort('failed');
    return;
  }

  // A reconciliation read deliberately made to fail: the command result and the failed refresh must be
  // reported separately, never merged into a false "the lists are current" claim.
  if (stub.failMatcher && stub.failMatcher(record)) {
    await route.fulfill({
      status: 500,
      contentType: 'application/json',
      headers: { 'Cache-Control': 'no-store' },
      body: JSON.stringify({ status: 500, error: 'Internal Server Error', message: 'Controlled refresh failure' })
    });
    return;
  }

  let frozen = null;
  if (stub.pauseMatcher && stub.pauseMatcher(record)) {
    stub.pauseMatcher = null;
    if (stub.freezeHeldResponse) frozen = apiResponse(token, url);
    await new Promise((resolve) => {
      stub.held.push({ record, release: resolve });
    });
  }

  const { status, body } = frozen ?? apiResponse(token, url);
  await route.fulfill({
    status,
    contentType: 'application/json',
    headers: { 'Cache-Control': 'no-store' },
    body: body === null ? '' : JSON.stringify(body)
  });
};

const serveStatic = async (route, url) => {
  let pathname = url.pathname === '/' ? '/index.html' : url.pathname;
  let file = join(DIST, pathname);
  try {
    const body = await readFile(file);
    await route.fulfill({ status: 200, contentType: CONTENT_TYPES[extname(file)] ?? 'application/octet-stream', body });
  } catch {
    file = join(DIST, 'index.html');
    const body = await readFile(file);
    await route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body });
  }
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
    results.push(`  ✔ ${label}`);
  } else {
    failures += 1;
    results.push(`  ✖ ${label}${detail === undefined ? '' : ` — ${detail}`}`);
  }
};

const checkEqual = (label, actual, expected) =>
  check(label, Object.is(actual, expected), `expected ${JSON.stringify(expected)}, saw ${JSON.stringify(actual)}`);

const employeeProfile = (token, id, role = 'EMPLOYEE') => [
  ['pathome_auth_token', token],
  ['pathome_role', role],
  ['pathome_user', JSON.stringify({ id, role, fullName: `User ${id}`, email: `user${id}@example.test` })]
];

/**
 * Simulates a transport that ignores the caller's abort: the capability request keeps its promise
 * pending until the stub answers, so an aborted mutation check never settles on its own. This is the
 * exact condition correction B addresses ("hold the mutation capability promise indefinitely,
 * including after receiving an abort"); the real browser `fetch` rejects on abort, so without this the
 * condition cannot be reproduced.
 */
const ignoreCapabilityAbort = () => {
  const realFetch = window.fetch.bind(window);
  window.fetch = (input, init) => {
    const url = typeof input === 'string' ? input : String((input && input.url) || '');
    if (url.includes('/staff/me/capabilities') && init && init.signal) {
      const { signal, ...rest } = init;
      return realFetch(input, rest);
    }
    return realFetch(input, init);
  };
};

const newContext = async (browser, { path, storage, ignoreCapabilityAbort: stripAbort = false }) => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  // Page-scoped so that any auxiliary tab this harness opens cannot write the app's session keys.
  await page.addInitScript((entries) => {
    entries.forEach(([key, value]) => window.localStorage.setItem(key, value));
  }, storage);
  if (stripAbort) await page.addInitScript(ignoreCapabilityAbort);
  page.on('pageerror', (error) => {
    results.push(`  ! page error: ${error.message}`);
  });
  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url());
    if (url.origin !== ORIGIN) {
      await route.continue();
      return;
    }
    if (url.pathname.startsWith('/api/')) {
      await handleApi(route, route.request(), url);
      return;
    }
    await serveStatic(route, url);
  });
  await page.goto(`${ORIGIN}${path}`, { waitUntil: 'domcontentloaded' });
  return { context, page };
};

const oeTab = (page) => page.getByRole('button', { name: /Operations workspace/i });
const visitsTab = (page) => page.getByRole('button', { name: /My escort visits/i });
const visible = async (locator) => locator.count().then(async (count) => count > 0 && locator.first().isVisible());

/** Polls for a visible selector so a slow re-render is never reported as a behaviour failure. */
const waitForVisible = async (page, selector, timeoutMs = 6000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await page.locator(selector).first().isVisible().catch(() => false)) return true;
    await page.waitForTimeout(150);
  }
  return false;
};

const switchAccount = async (page, storage) => {
  await page.evaluate((entries) => {
    entries.forEach(([key, value]) => window.localStorage.setItem(key, value));
    window.dispatchEvent(new CustomEvent('pathome_auth_changed'));
  }, storage);
};

const scenarios = [
  /* 1. Entry gating ---------------------------------------------------------------- */
  async function employeeWithGrantSeesWorkspace(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    check('employee with an effective Team grant is offered the Operations destination', true);
    await oeTab(page).click();
    await page.waitForSelector('.oe-queue', { timeout: 8000 });
    const totalText = await page.locator('.oe-subnav-count').first().textContent();
    check('the workspace loads server totals', Boolean(totalText !== null), `count=${totalText}`);
    check('existing employee visits panel is still offered', await visible(visitsTab(page)));
    await visitsTab(page).click();
    await page.waitForTimeout(300);
    check('the existing visits destination still opens', (await page.locator('.oe-subnav').count()) === 0);
    const capabilityCalls = stub.requests('/staff/me/capabilities');
    check('authority comes from the capabilities endpoint', capabilityCalls.length >= 1, `${capabilityCalls.length} calls`);
    check(
      'the capability call carries the signed-in credential',
      capabilityCalls.every((call) => call.token === TOKEN_A)
    );
    await context.close();
  },

  async function employeeWithoutGrantSeesNoEntry(browser) {
    stub.reset();
    stub.capabilityMode = 'none';
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 12) });
    await visitsTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await page.waitForTimeout(600); // let the capability verdict settle
    checkEqual('employee without the grant is not offered the destination', await oeTab(page).count(), 0);
    check('the employee console still works without the destination', await visible(visitsTab(page)));
    await context.close();
  },

  async function inactiveStaffSeesNoEntry(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, {
      path: '/crm',
      storage: employeeProfile(TOKEN_INACTIVE, 13)
    });
    await visitsTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await page.waitForTimeout(600);
    checkEqual('an inactive staff profile is not offered the destination', await oeTab(page).count(), 0);
    await context.close();
  },

  async function adminGating(browser) {
    stub.reset();
    const granted = await newContext(browser, {
      path: '/admin',
      storage: employeeProfile(TOKEN_ADMIN, 21, 'SUB_ADMIN')
    });
    await oeTab(granted.page).waitFor({ state: 'visible', timeout: 8000 });
    check('a Sub-Admin holding the Team grant sees the Operations entry in the admin console', true);
    await granted.page.locator('nav').getByText('Update Property Listing').first().waitFor({ timeout: 8000 });
    check(
      'the existing admin tabs are preserved alongside it',
      await visible(granted.page.getByText('Learning review', { exact: false }).first())
    );
    await oeTab(granted.page).click();
    await granted.page.waitForSelector('.oe-queue', { timeout: 8000 });
    check('the admin console renders the same shared workspace component', true);
    const stored = await granted.page.evaluate(() => window.localStorage.getItem('pathome_active_admin_tab'));
    check('the Operations destination is never persisted as the active admin tab', stored !== 'operations', `stored=${stored}`);
    await granted.context.close();

    stub.reset();
    const ungranted = await newContext(browser, {
      path: '/admin',
      storage: employeeProfile(TOKEN_ADMIN_NO_GRANT, 22, 'SUPER_ADMIN')
    });
    await ungranted.page.locator('nav').getByText('Update Property Listing').first().waitFor({ timeout: 8000 });
    await ungranted.page.waitForTimeout(600);
    checkEqual(
      'a Super-Admin without the Team grant receives no Operations entry',
      await oeTab(ungranted.page).count(),
      0
    );
    await ungranted.context.close();
  },

  /* 2. Cross-account mutation race -------------------------------------------------- */
  async function accountSwitchDuringClaim(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });

    // Pause the claim's safety re-read so the account can change underneath it.
    stub.pause((record) => record.method === 'GET' && /claimable-work\/REQUEST\/5200/.test(record.path));
    await page.locator('.oe-row--claim .oe-btn').first().click();
    await page.waitForFunction(() => true);
    await new Promise((resolve) => setTimeout(resolve, 300));
    checkEqual('the claim re-read was transmitted before the account switch', stub.held.length, 1);

    await switchAccount(page, employeeProfile(TOKEN_B, 22));
    await new Promise((resolve) => setTimeout(resolve, 300));
    checkEqual('releasing the paused response', stub.releaseHeld(), 1);
    await page.waitForTimeout(700);

    checkEqual(
      'no ownership command was transmitted for the abandoned operation',
      stub.requests('/claim', 'POST').length + stub.requests('/release', 'POST').length,
      0
    );
    const bodyText = await page.locator('body').innerText();
    check('the previous account record is not shown after the switch', !bodyText.includes('#5200'), bodyText.slice(0, 200));
    check('no success announcement was made for the abandoned claim', !/Request #5200 claimed/.test(bodyText));

    // The new account's own authority and data are fetched fresh under its credential.
    await page.waitForTimeout(800);
    const afterSwitch = await page.locator('body').innerText();
    check(
      'the new account reads its own scope only',
      stub.requests('/operations/').every((call) => call.token === TOKEN_A || call.token === TOKEN_B)
    );
    check('the new account sees its own team', /Team B Coordination|Team B/.test(afterSwitch) || !/Team A/.test(afterSwitch));
    await context.close();
  },

  /* 3. Unmount during a pending read ------------------------------------------------ */
  async function unmountDuringPendingRead(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /My work/i }).first().click();
    await page.waitForSelector('.oe-row--owned', { timeout: 8000 });

    stub.pause((record) => record.method === 'GET' && /visit-sessions\/3108/.test(record.path));
    await page.locator('.oe-row--owned .oe-row-link').first().click();
    await new Promise((resolve) => setTimeout(resolve, 200));
    checkEqual('the detail read is in flight', stub.held.length, 1);

    // Leave the destination while the read is still pending.
    await visitsTab(page).click();
    await new Promise((resolve) => setTimeout(resolve, 250));
    stub.releaseHeld();
    await page.waitForTimeout(500);

    const detailAfterUnmount = await page.locator('.oe-detail-grid').count();
    checkEqual('the late detail response does not render after leaving the destination', detailAfterUnmount, 0);
    const readsBeforeReentry = stub.requests('/operations/my-work').length;
    await oeTab(page).click();
    check('returning to the destination reloads the queues', await waitForVisible(page, '.oe-queue'));
    check(
      'the reload is a fresh read, not the discarded snapshot',
      stub.requests('/operations/my-work').length > readsBeforeReentry,
      `${stub.requests('/operations/my-work').length} total my-work reads`
    );
    await context.close();
  },

  /* 4. Runtime revocation, transient failure, recovery ------------------------------ */
  async function revocationWhileDetailOpen(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /My work/i }).first().click();
    await page.waitForSelector('.oe-row--owned', { timeout: 8000 });
    await page.locator('.oe-row--owned .oe-row-link').first().click();
    await page.waitForSelector('.oe-detail-grid', { timeout: 8000 });
    check('a protected detail is open before revocation', (await page.locator('body').innerText()).includes('Team A visit item'));

    stub.capabilityMode = 'none';
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await page.waitForTimeout(900);
    const afterRevocation = await page.locator('body').innerText();
    check('focus revalidation discovers the revocation', !afterRevocation.includes('Team A visit item'));
    check('protected data is cleared rather than left on screen', !afterRevocation.includes('#3108'));
    checkEqual('the withdrawn destination is no longer offered', await oeTab(page).count(), 0);

    stub.capabilityMode = 'auto';
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    const beforeReopen = stub.requests('/operations/my-work').length;
    await oeTab(page).click();
    await page.waitForSelector('.oe-queue', { timeout: 8000 });
    await page.waitForTimeout(400);
    check(
      'restored authority re-authorizes the reads instead of redisplaying the old snapshot',
      stub.requests('/operations/my-work').length > beforeReopen
    );
    await context.close();
  },

  async function transientFailureAndRetry(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /My work/i }).first().click();
    await page.waitForSelector('.oe-row--owned', { timeout: 8000 });

    stub.capabilityMode = 'error';
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await page.waitForSelector('text=We couldn’t verify your Operations access', { timeout: 8000 });
    const restricted = await page.locator('body').innerText();
    check('a failed capability refresh hides protected details', !restricted.includes('#3108'));
    checkEqual('mutations are blocked in the restricted state', await page.locator('.oe-actions').count(), 0);
    check('the recoverable state offers Retry', await visible(page.getByRole('button', { name: /^Retry$/ })));
    check('the entry point is preserved for a previously verified grant', await visible(oeTab(page)));

    stub.capabilityMode = 'auto';
    await page.getByRole('button', { name: /^Retry$/ }).click();
    await page.waitForSelector('.oe-subnav', { timeout: 8000 });
    check('a successful fresh authorization restores the workspace', await visible(page.locator('.oe-list--owned').first()));
    check(
      'the restored workspace re-reads work instead of showing the old snapshot',
      stub.requests('/operations/my-work').length >= 2,
      `${stub.requests('/operations/my-work').length} my-work reads`
    );
    await context.close();
  },

  async function visibilityRevalidationDiscoversRevocation(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /My work/i }).first().click();
    await page.waitForSelector('.oe-row--owned', { timeout: 8000 });
    check('protected work is visible before the tab is backgrounded', (await page.locator('body').innerText()).includes('#3108'));

    /**
     * Headless Chromium does not perform real tab visibility transitions, so the documented state is
     * forced on the live document and the genuine `visibilitychange` event is dispatched: the app's
     * own listener and revalidation path run unmodified. What is NOT proven here is that a browser
     * tab switch itself fires the event — that is browser behaviour, not application code.
     */
    const forceVisibility = (value) =>
      page.evaluate((next) => {
        Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => next });
        document.dispatchEvent(new Event('visibilitychange'));
      }, value);

    stub.capabilityMode = 'none';
    const before = stub.requests('/staff/me/capabilities').length;
    await forceVisibility('hidden');
    await page.waitForTimeout(600);
    checkEqual(
      'a backgrounded document does not revalidate authority',
      stub.requests('/staff/me/capabilities').length,
      before
    );

    await forceVisibility('visible');
    await page.waitForTimeout(1500);
    check(
      'restoring visibility revalidates authority',
      stub.requests('/staff/me/capabilities').length > before
    );

    const text = await page.locator('body').innerText();
    const diagnostics = await page.evaluate(() => ({
      url: window.location.href,
      signedIn: Boolean(window.localStorage.getItem('pathome_auth_token'))
    }));
    check(
      'restoring document visibility revalidates and clears revoked access',
      !text.includes('#3108') && diagnostics.signedIn && diagnostics.url.includes('/crm'),
      `${diagnostics.url} signedIn=${diagnostics.signedIn} :: ${text.slice(0, 120)}`
    );
    check('the revoked destination is withdrawn after the visibility change', (await oeTab(page).count()) === 0);
    await context.close();
  },

  async function pollingDiscoversRevocation(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /My work/i }).first().click();
    await page.waitForSelector('.oe-row--owned', { timeout: 8000 });
    const before = stub.requests('/staff/me/capabilities').length;
    stub.capabilityMode = 'none';
    // No focus or visibility change: only the approved ~30s cadence can discover this.
    const deadline = Date.now() + 40_000;
    let discovered = false;
    while (Date.now() < deadline && !discovered) {
      await page.waitForTimeout(1000);
      const text = await page.locator('body').innerText();
      discovered = !text.includes('#3108');
    }
    check('the ~30s revalidation cadence discovers revocation without user interaction', discovered);
    check(
      'the cadence produced additional capability checks',
      stub.requests('/staff/me/capabilities').length > before,
      `${stub.requests('/staff/me/capabilities').length - before} extra`
    );
    await context.close();
  },

  /* 5. Conflict feedback ------------------------------------------------------------ */
  async function claimConflictFeedback(browser) {
    stub.reset();
    stub.claimStatus = 409;
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });
    await page.locator('.oe-row--claim .oe-btn').first().click();

    const alert = page.locator('.oe-banner[role="alert"]');
    await alert.waitFor({ state: 'visible', timeout: 8000 });
    const text = await alert.innerText();
    check('a claim conflict is explained where the workspace is working', /changed on the server/.test(text), text);
    check('the conflict alert is a live alert region', (await alert.getAttribute('role')) === 'alert');
    checkEqual('the conflict is never resent automatically', stub.requests('/claim', 'POST').length, 1);
    checkEqual('the stale claim did not claim anything', stub.requests('/release', 'POST').length, 0);

    // Navigating inside the workspace must not make the feedback disappear.
    await page.getByRole('button', { name: /My work/i }).first().click();
    await page.waitForTimeout(300);
    check('the conflict survives navigation inside the workspace', await visible(page.locator('.oe-banner[role="alert"]')));

    // ...and it must be genuinely visible on a phone-sized viewport, not just in the DOM.
    await page.setViewportSize({ width: 320, height: 640 });
    await page.evaluate(() => {
      document.querySelector('.oe-banner[role="alert"]')?.scrollIntoView({ block: 'center' });
    });
    await page.waitForTimeout(400);
    const topped = await page.evaluate(() => {
      const banner = document.querySelector('.oe-banner[role="alert"]');
      if (!banner) return { ok: false, why: 'missing' };
      const rect = banner.getBoundingClientRect();
      const x = Math.min(Math.max(rect.left + 8, 1), window.innerWidth - 1);
      const y = Math.min(Math.max(rect.top + rect.height / 2, 1), window.innerHeight - 1);
      const hit = document.elementFromPoint(x, y);
      if (!hit) {
        return {
          ok: false,
          why: `no element at ${Math.round(x)},${Math.round(y)}; banner ${Math.round(rect.top)}..${Math.round(rect.bottom)} of ${window.innerHeight}`
        };
      }
      const className = typeof hit.className === 'string' ? hit.className : hit.getAttribute('class') ?? '';
      return { ok: banner === hit || banner.contains(hit), why: `${hit.tagName}.${className}` };
    });
    check('the alert is not obscured on a 320px viewport', topped.ok, topped.why);

    await page.getByRole('button', { name: /^Dismiss$/ }).click();
    await page.waitForTimeout(200);
    checkEqual('the alert can be dismissed', await page.locator('.oe-banner[role="alert"]').count(), 0);
    await context.close();
  },

  async function releaseConflictAfterDetailCloses(browser) {
    stub.reset();
    stub.releaseStatus = 409;
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /My work/i }).first().click();
    await page.waitForSelector('.oe-row--owned', { timeout: 8000 });
    await page.locator('.oe-row--owned .oe-row-link').first().click();
    await page.waitForSelector('.oe-detail-grid', { timeout: 8000 });
    await page.getByRole('button', { name: /Release session/i }).click();

    const alert = page.locator('.oe-banner[role="alert"]');
    await alert.waitFor({ state: 'visible', timeout: 8000 });
    const text = await alert.innerText();
    check('a release conflict is visible after the detail closes', /changed on the server/.test(text), text);
    checkEqual('the released detail is closed', await page.locator('.oe-detail-grid').count(), 0);
    checkEqual('the release conflict is not retried automatically', stub.requests('/release', 'POST').length, 1);
    await context.close();
  },

  /* 6. Hit targets and responsive integrity ----------------------------------------- */
  async function hitTargetsAndOverflow(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.waitForSelector('.oe-queue', { timeout: 8000 });

    const measure = async (label) =>
      page.evaluate(() => {
        const MIN = 44;
        const report = { tooSmall: [], overflow: [] };
        document.querySelectorAll('.oe button, .oe [role="button"], .oe a').forEach((element) => {
          if (element.getAttribute('aria-hidden') === 'true') return;
          const style = window.getComputedStyle(element);
          if (style.display === 'none' || style.visibility === 'hidden') return;
          let rect = element.getBoundingClientRect();
          if (rect.width === 0 || rect.height === 0) return;
          const row = element.closest('.oe-row, .oe-mini-row');
          if (row && element.classList.contains('oe-row-link')) rect = row.getBoundingClientRect();
          if (rect.width + 0.5 < MIN || rect.height + 0.5 < MIN) {
            report.tooSmall.push(
              `${element.className || element.tagName}:${Math.round(rect.width)}x${Math.round(rect.height)}`
            );
          }
        });
        if (document.documentElement.scrollWidth > window.innerWidth + 1) {
          report.overflow.push(`${document.documentElement.scrollWidth}>${window.innerWidth}`);
        }
        return report;
      });

    for (const width of [320, 375, 768, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      await page.waitForTimeout(150);
      const report = await measure();
      check(`no interactive control is below 44px in the overview at ${width}px`, report.tooSmall.length === 0, report.tooSmall.join(', '));
      check(`no horizontal overflow in the overview at ${width}px`, report.overflow.length === 0, report.overflow.join(', '));
    }

    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });
    for (const width of [320, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      await page.waitForTimeout(150);
      const report = await measure();
      check(`no interactive control is below 44px in the claimable queue at ${width}px`, report.tooSmall.length === 0, report.tooSmall.join(', '));
      check(`no horizontal overflow in the claimable queue at ${width}px`, report.overflow.length === 0, report.overflow.join(', '));
    }

    await page.locator('.oe-row--claim .oe-btn').first().click();
    await page.waitForSelector('.oe-detail-grid', { timeout: 8000 });
    for (const width of [320, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      await page.waitForTimeout(150);
      const report = await measure();
      check(`no interactive control is below 44px in the record detail at ${width}px`, report.tooSmall.length === 0, report.tooSmall.join(', '));
      check(`no horizontal overflow in the record detail at ${width}px`, report.overflow.length === 0, report.overflow.join(', '));
    }
    await context.close();
  },

  /* 7. Ownership-operation lifecycle and authority races (Package 1A corrections) ---- */

  async function claimUnmountDuringPendingRead(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });

    // The claim's mandatory fresh re-read of the root is held in flight.
    stub.pause((record) => record.method === 'GET' && /claimable-work\/REQUEST\/5200/.test(record.path));
    await page.locator('.oe-row--claim .oe-btn').first().click();
    await new Promise((resolve) => setTimeout(resolve, 300));
    checkEqual('the claim is waiting on its fresh root read', stub.held.length, 1);

    // The OE destination closes while that read is still pending: the operation is abandoned.
    await visitsTab(page).click();
    await new Promise((resolve) => setTimeout(resolve, 250));
    checkEqual('closing the destination during a pending claim transmits no command', stub.requests('/claim', 'POST').length, 0);
    stub.releaseHeld();
    await page.waitForTimeout(600);
    checkEqual('the released read cannot transmit a command after the workspace closed', stub.requests('/claim', 'POST').length, 0);
    checkEqual('no release command is transmitted either', stub.requests('/release', 'POST').length, 0);
    const text = await page.locator('body').innerText();
    check('no success is announced for the abandoned claim', !/Request #5200 claimed/.test(text));
    checkEqual('the closed destination leaves no stale ownership notice', await page.locator('.oe-banner').count(), 0);
    await context.close();
  },

  async function releaseUnmountDuringPendingVerification(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /My work/i }).first().click();
    await page.waitForSelector('.oe-row--owned', { timeout: 8000 });
    await page.locator('.oe-row--owned .oe-row-link').first().click();
    await page.waitForSelector('.oe-detail-grid', { timeout: 8000 });

    // A release re-verifies authority for the captured session before it may act; hold that check.
    stub.pause((record) => record.method === 'GET' && record.path.includes('/staff/me/capabilities'));
    await page.getByRole('button', { name: /Release session/i }).click();
    await new Promise((resolve) => setTimeout(resolve, 300));
    checkEqual('the release is waiting on its authority re-verification', stub.held.length, 1);

    await visitsTab(page).click();
    await new Promise((resolve) => setTimeout(resolve, 250));
    stub.releaseHeld();
    await page.waitForTimeout(600);
    checkEqual('closing the destination during a pending release transmits no command', stub.requests('/release', 'POST').length, 0);
    checkEqual('no claim command is transmitted either', stub.requests('/claim', 'POST').length, 0);
    const text = await page.locator('body').innerText();
    check('no release is announced for the abandoned operation', !/released/.test(text));
    await context.close();
  },

  async function claimAuthorityWithdrawnWhileReadPending(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });

    stub.pause((record) => record.method === 'GET' && /claimable-work\/REQUEST\/5200/.test(record.path));
    await page.locator('.oe-row--claim .oe-btn').first().click();
    await new Promise((resolve) => setTimeout(resolve, 300));
    checkEqual('the claim is waiting on its fresh root read', stub.held.length, 1);

    // While the claim waits, a capability re-validation reports the grant is gone.
    stub.capabilityMode = 'none';
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await page.waitForTimeout(1200);
    checkEqual('the withdrawn destination is closed', await oeTab(page).count(), 0);
    checkEqual('authority withdrawal during a pending claim transmits no command', stub.requests('/claim', 'POST').length, 0);

    stub.releaseHeld();
    await page.waitForTimeout(700);
    checkEqual('the released read cannot revive the withdrawn claim', stub.requests('/claim', 'POST').length, 0);
    checkEqual('no release command is transmitted either', stub.requests('/release', 'POST').length, 0);
    const text = await page.locator('body').innerText();
    check('protected work is not restored after the withdrawal', !text.includes('#5200'));
    await context.close();
  },

  async function releaseTransientFailureWhileVerifying(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /My work/i }).first().click();
    await page.waitForSelector('.oe-row--owned', { timeout: 8000 });
    await page.locator('.oe-row--owned .oe-row-link').first().click();
    await page.waitForSelector('.oe-detail-grid', { timeout: 8000 });

    stub.pause((record) => record.method === 'GET' && record.path.includes('/staff/me/capabilities'));
    await page.getByRole('button', { name: /Release session/i }).click();
    await new Promise((resolve) => setTimeout(resolve, 300));
    checkEqual('the release is waiting on its authority re-verification', stub.held.length, 1);

    // The re-verification fails transiently: authority is unverifiable, so the command fails closed.
    stub.capabilityMode = 'error';
    stub.releaseHeld();
    await page.waitForSelector('text=We couldn’t verify your Operations access', { timeout: 8000 });
    checkEqual('a transient verification failure before a release transmits no command', stub.requests('/release', 'POST').length, 0);
    checkEqual('no claim command is transmitted either', stub.requests('/claim', 'POST').length, 0);
    const text = await page.locator('body').innerText();
    check('protected detail is hidden while authority is unverified', !text.includes('Team A visit item'));
    check('the recoverable state still offers Retry', await visible(page.getByRole('button', { name: /^Retry$/ })));
    await context.close();
  },

  async function delayedOlderGrantCannotOverrideNewerDenial(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /My work/i }).first().click();
    await page.waitForSelector('.oe-row--owned', { timeout: 8000 });
    await page.locator('.oe-row--owned .oe-row-link').first().click();
    await page.waitForSelector('.oe-detail-grid', { timeout: 8000 });

    // An older authority check that will answer `granted` is held in flight. Its response is frozen
    // now, so the state can change underneath it.
    stub.freezeHeldResponse = true;
    stub.pause((record) => record.method === 'GET' && record.path.includes('/staff/me/capabilities'));
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await new Promise((resolve) => setTimeout(resolve, 300));
    checkEqual('an older authority check is in flight', stub.held.length, 1);

    // A newer, fresh check (the release's own re-verification) is denied before it settles.
    stub.capabilityMode = 'none';
    await page.getByRole('button', { name: /Release session/i }).click();
    await page.waitForTimeout(1000);
    checkEqual('the newer denial prevents the release from being transmitted', stub.requests('/release', 'POST').length, 0);
    checkEqual('the newer denial withdraws the destination', await oeTab(page).count(), 0);

    // The older `granted` answer finally arrives and must be dropped, not applied.
    stub.releaseHeld();
    await page.waitForTimeout(900);
    const text = await page.locator('body').innerText();
    checkEqual('the delayed granted response cannot restore the workspace', await page.locator('.oe-subnav').count(), 0);
    check('the delayed granted response cannot restore protected data', !text.includes('Team A visit item'));
    checkEqual('the withdrawn destination stays withdrawn', await oeTab(page).count(), 0);
    checkEqual(
      'no ownership command is transmitted in either direction',
      stub.requests('/claim', 'POST').length + stub.requests('/release', 'POST').length,
      0
    );
    await context.close();
  },

  async function lostCommandResponseIsUncertainAndNeverRetried(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });

    // The command is transmitted and its response never arrives: the outcome is unknown.
    stub.loseCommandResponse = true;
    await page.locator('.oe-row--claim .oe-btn').first().click();

    const alert = page.locator('.oe-banner[role="alert"]');
    await alert.waitFor({ state: 'visible', timeout: 8000 });
    const text = await alert.innerText();
    check('a lost response is reported as an unconfirmed outcome', /not confirmed whether it was applied/.test(text), text);
    check('an unconfirmed outcome never claims that nothing was changed', !/nothing was changed/i.test(text), text);
    check(
      'the workspace reconciled the authoritative ownership',
      /queues were reloaded from the server and show the current ownership/.test(text),
      text
    );
    checkEqual('the command was transmitted exactly once', stub.requests('/claim', 'POST').length, 1);
    await page.waitForTimeout(1500);
    checkEqual(
      'an uncertain outcome is never resent automatically',
      stub.requests('/claim', 'POST').length + stub.requests('/release', 'POST').length,
      1
    );
    const afterFailure = await page.locator('body').innerText();
    check('no successful claim is announced for an unconfirmed command', !/Request #5200 claimed/.test(afterFailure));
    check('the reconciliation shows the real ownership: the item is still claimable', afterFailure.includes('#5200'));
    const claimableReadsAfterFailure = stub.requests('/operations/claimable-work').length;
    check(
      'the reconciliation was a fresh authoritative read, not invented state',
      claimableReadsAfterFailure >= 2,
      `${claimableReadsAfterFailure} claimable reads`
    );

    // With a real answer the same command still works exactly as before.
    await page.locator('.oe-row--claim .oe-btn').first().click();
    await page.waitForSelector('.oe-detail-grid', { timeout: 8000 });
    const claimed = await page.locator('body').innerText();
    check('a 204 claim is still announced', /Request #5200 claimed/.test(claimed), claimed.slice(0, 200));
    checkEqual('exactly one further claim command was transmitted', stub.requests('/claim', 'POST').length, 2);

    await page.getByRole('button', { name: /Release request/i }).click();
    await page.waitForTimeout(900);
    checkEqual('a 204 release is transmitted once', stub.requests('/release', 'POST').length, 1);
    checkEqual('the released detail is closed', await page.locator('.oe-detail-grid').count(), 0);
    const released = await page.locator('body').innerText();
    check('the release is announced', /released/.test(released), released.slice(0, 200));
    await context.close();
  },

  /* 8. Frozen async-operation contract: reconciliation results and session-epoch isolation ------- */

  async function postClaimAccountSwitchRace(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });

    // The claim succeeds (204) but its reconciliation read is held, so Account A's flow can never
    // finish writing before the account changes.
    stub.pause((record) => record.method === 'GET' && /\/operations\/claimable-work\?/.test(record.path));
    await page.locator('.oe-row--claim .oe-btn').first().click();
    await new Promise((resolve) => setTimeout(resolve, 400));
    checkEqual('the claim command was transmitted under Account A', stub.requests('/claim', 'POST').length, 1);
    checkEqual('the post-204 reconciliation is held in flight', stub.held.length, 1);

    // The account changes while A's reconciliation is still pending, then B opens its own detail.
    await switchAccount(page, employeeProfile(TOKEN_B, 22));
    await page.waitForTimeout(500);
    await page.getByRole('button', { name: /My work/i }).first().click();
    await page.waitForSelector('.oe-row--owned', { timeout: 8000 });
    stub.pause((record) => record.method === 'GET' && /visit-sessions\/7100/.test(record.path));
    await page.locator('.oe-row--owned .oe-row-link').first().click();
    await new Promise((resolve) => setTimeout(resolve, 300));
    const bDetailHeld = () => stub.held.filter((entry) => /visit-sessions\/7100/.test(entry.record.path)).length;
    checkEqual('Account B’s own detail request is in flight', bDetailHeld(), 1);

    // Release A's stale reconciliation while B's read is pending: it must not abort or replace B's.
    checkEqual(
      'the stale Account A reconciliation is released while B is selecting',
      stub.releaseHeldWhere((record) => /\/operations\/claimable-work\?/.test(record.path)),
      1
    );
    await new Promise((resolve) => setTimeout(resolve, 500));
    checkEqual('A’s delayed refresh did not abort Account B’s detail request', bDetailHeld(), 1);
    stub.releaseHeld();
    await page.waitForSelector('.oe-detail-grid', { timeout: 8000 });
    const detailText = await page.locator('body').innerText();
    check('Account B’s own record is the one selected', detailText.includes('Team B visit item'));
    check('Account A’s record never appears under Account B', !detailText.includes('#5200'));
    check('no success is announced for Account A’s claim under Account B', !/Request #5200 claimed/.test(detailText));
    checkEqual('exactly one ownership command was ever transmitted', stub.requests('/claim', 'POST').length, 1);
    await context.close();
  },

  async function lostCommandAccountSwitchRace(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });

    // The command is transmitted but its response is lost, and the reconciliation is then held.
    stub.loseCommandResponse = true;
    stub.pause((record) => record.method === 'GET' && /\/operations\/claimable-work\?/.test(record.path));
    await page.locator('.oe-row--claim .oe-btn').first().click();
    await new Promise((resolve) => setTimeout(resolve, 400));
    checkEqual('the uncertain command was transmitted exactly once', stub.requests('/claim', 'POST').length, 1);
    checkEqual('the uncertain reconciliation is held in flight', stub.held.length, 1);

    // The account changes before the uncertain outcome can be announced.
    await switchAccount(page, employeeProfile(TOKEN_B, 22));
    await page.waitForTimeout(500);
    stub.releaseHeld();
    await page.waitForTimeout(800);

    const text = await page.locator('body').innerText();
    check('Account A’s unconfirmed-outcome notice never appears under Account B', !/not confirmed whether it was applied/.test(text));
    check('Account A’s record never appears under Account B', !text.includes('#5200'));
    checkEqual('no ownership notice is left in the workspace', await page.locator('.oe-banner').count(), 0);
    checkEqual('exactly one Account A command was ever transmitted', stub.requests('/claim', 'POST').length, 1);
    await context.close();
  },

  async function duplicateClicksTransmitOnce(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });

    // Hold the claim's mandatory fresh root read so the operation stays live across the extra taps.
    stub.pause((record) => record.method === 'GET' && /claimable-work\/REQUEST\/5200$/.test(record.path));
    await page.locator('.oe-row--claim .oe-btn').first().click();
    await new Promise((resolve) => setTimeout(resolve, 250));
    checkEqual('the first tap starts exactly one root re-read', stub.held.length, 1);

    await page.evaluate(() => {
      const button = document.querySelector('.oe-row--claim .oe-btn');
      if (button) { button.click(); button.click(); }
    });
    await new Promise((resolve) => setTimeout(resolve, 300));
    checkEqual('duplicate taps never start a second operation', stub.held.length, 1);

    stub.releaseHeld();
    await page.waitForSelector('.oe-detail-grid', { timeout: 8000 });
    checkEqual('exactly one ownership mutation is transmitted', stub.requests('/claim', 'POST').length, 1);
    await context.close();
  },

  async function uncertainOutcomeWithFailedReconciliation(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });

    // The command is transmitted, its response is lost, and every authoritative queue read then fails.
    stub.loseCommandResponse = true;
    stub.failMatcher = (record) => record.method === 'GET'
      && (/\/operations\/claimable-work\?/.test(record.path) || /\/operations\/my-work\?/.test(record.path));
    await page.locator('.oe-row--claim .oe-btn').first().click();

    const alert = page.locator('.oe-banner[role="alert"]');
    await alert.waitFor({ state: 'visible', timeout: 8000 });
    const text = await alert.innerText();
    check('an uncertain outcome with a failed reconciliation stays unconfirmed', /not confirmed whether it was applied/.test(text), text);
    check('it never claims the queues were reloaded from the server', !/queues were reloaded from the server/.test(text), text);
    check('it says current ownership could not be verified', /Current ownership could not be verified/.test(text), text);
    await page.waitForTimeout(1200);
    checkEqual('the uncertain command is never resent', stub.requests('/claim', 'POST').length, 1);
    stub.failMatcher = null;
    await context.close();
  },

  async function conflictWithFailedReconciliation(browser) {
    stub.reset();
    stub.claimStatus = 409;
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });

    // A real 409 conflict whose reconciliation reads all fail.
    stub.failMatcher = (record) => record.method === 'GET'
      && (/\/operations\/claimable-work\?/.test(record.path) || /\/operations\/my-work\?/.test(record.path));
    await page.locator('.oe-row--claim .oe-btn').first().click();
    const alert = page.locator('.oe-banner[role="alert"]');
    await alert.waitFor({ state: 'visible', timeout: 8000 });
    const text = await alert.innerText();
    check('a conflict is still explained when the reconciliation fails', /changed on the server/.test(text), text);
    check('a failed reconciliation is not described as a completed refresh', !/queues were refreshed from the server/.test(text), text);
    check('it says current ownership could not be verified', /Current ownership could not be verified/.test(text), text);
    await page.waitForTimeout(1200);
    checkEqual('a conflict is never resent automatically', stub.requests('/claim', 'POST').length, 1);
    checkEqual('a conflict never releases the item into a claimed state', stub.requests('/release', 'POST').length, 0);
    stub.failMatcher = null;
    await context.close();
  },

  async function missingVersionWithFailedRefresh(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });

    // Now that the queue is loaded: the item re-read omits the version, and the follow-up refresh fails.
    stub.dropClaimableVersion = true;
    stub.failMatcher = (record) => record.method === 'GET'
      && (/\/operations\/claimable-work\?/.test(record.path) || /\/operations\/my-work\?/.test(record.path));
    await page.locator('.oe-row--claim .oe-btn').first().click();
    const alert = page.locator('.oe-banner[role="alert"]');
    await alert.waitFor({ state: 'visible', timeout: 8000 });
    const text = await alert.innerText();
    checkEqual('a missing version transmits no command', stub.requests('/claim', 'POST').length, 0);
    check('a missing version is stated truthfully', /did not return the version needed to claim it/.test(text), text);
    check('a failed refresh is never called a completed reload', !/refreshed from the server/.test(text), text);
    check('the copy says the work lists could not be refreshed', /could not be refreshed just now/.test(text), text);
    stub.failMatcher = null;
    await context.close();
  },

  async function confirmedSuccessWithFailedRefresh(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });

    // The claim succeeds (204) but every follow-up queue read fails.
    stub.failMatcher = (record) => record.method === 'GET'
      && (/\/operations\/claimable-work\?/.test(record.path) || /\/operations\/my-work\?/.test(record.path));
    await page.locator('.oe-row--claim .oe-btn').first().click();
    await page.waitForSelector('.oe-detail-grid', { timeout: 8000 });
    const bodyText = await page.locator('body').innerText();
    check('a confirmed 204 is still announced as a success', /Request #5200 claimed/.test(bodyText), bodyText.slice(0, 200));
    const banner = page.locator('.oe-banner[role="alert"]');
    await banner.waitFor({ state: 'visible', timeout: 8000 });
    const bannerText = await banner.innerText();
    check('the failed refresh is reported as a separate result', /could not be refreshed just now/.test(bannerText), bannerText);
    check('the confirmed success is never reclassified as uncertain', !/not confirmed whether it was applied/.test(bannerText), bannerText);
    checkEqual('exactly one command was transmitted', stub.requests('/claim', 'POST').length, 1);
    stub.failMatcher = null;
    await context.close();
  },

  async function accountSwitchResetsToNeutralView(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });
    check('Account A is on a queue screen before the switch', (await page.locator('.oe-row--claim').count()) > 0);

    await switchAccount(page, employeeProfile(TOKEN_B, 22));
    await page.waitForSelector('.oe-board', { timeout: 8000 });
    await page.waitForTimeout(400);
    check('an account change returns the workspace to its neutral entry view', await visible(page.locator('.oe-board')));
    checkEqual('the previous account’s queue screen is gone', await page.locator('.oe-row--claim').count(), 0);
    check(
      'the new account sees its own scope, not the previous one',
      !(await page.locator('body').innerText()).includes('#5200')
    );
    await context.close();
  },

  /**
   * Package 1A correction 1: the Claim continuation must re-validate its operation after the awaited
   * `openDetail` read. A confirmed 204 whose queue reconciliation then fails leaves one last write in
   * that continuation — the refresh-failure notice. Here Account A's detail read is held while the
   * account changes, and Account B establishes its own visible conflict notice. When A's read finally
   * settles it must neither announce anything nor touch B's notice, selection or loading state.
   */
  async function claimStaleNoticeAfterHeldDetailReadAcrossAccountSwitch(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });

    // The claim is confirmed (204), then its queue reconciliation fails, then the claimed-record detail
    // read after it is held in flight — so Account A's continuation is pinned on `openDetail`.
    stub.failMatcher = (record) => record.method === 'GET'
      && (/\/operations\/my-work\?/.test(record.path) || /\/operations\/claimable-work\?/.test(record.path));
    stub.pause((record) => record.method === 'GET' && /visit-requests\/5200$/.test(record.path));
    await page.locator('.oe-row--claim .oe-btn').first().click();
    await new Promise((resolve) => setTimeout(resolve, 800));
    checkEqual('the claim command was transmitted under Account A', stub.requests('/claim', 'POST').length, 1);
    checkEqual('the post-204 claimed-record detail read is held in flight', stub.held.length, 1);
    check(
      'the held read is Account A’s claimed-record detail',
      /\/operations\/visit-requests\/5200$/.test(stub.held[0].record.path),
      stub.held[0].record.path
    );
    checkEqual('the held read carries Account A’s credential', stub.held[0].record.token, TOKEN_A);
    stub.failMatcher = null;

    // Account B signs in while Account A's detail read is still pending.
    await switchAccount(page, employeeProfile(TOKEN_B, 22));
    await page.waitForTimeout(500);
    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });

    // Account B establishes its own visible conflict notice.
    stub.claimStatus = 409;
    await page.locator('.oe-row--claim .oe-btn').first().click();
    const banner = page.locator('.oe-banner[role="alert"]');
    await banner.waitFor({ state: 'visible', timeout: 8000 });
    const noticeBefore = await banner.innerText();
    check('Account B has its own visible conflict notice', /changed on the server while you were working/.test(noticeBefore), noticeBefore);
    checkEqual('Account B’s conflict was refused exactly once', stub.requests('/claim', 'POST').length, 2);

    // Account A's stale read finally settles. Nothing of Account A's continuation may land in B.
    stub.releaseHeld();
    await page.waitForTimeout(900);
    stub.claimStatus = 204;

    checkEqual('Account B’s notice is still displayed', await page.locator('.oe-banner').count(), 1);
    checkEqual('Account B’s notice text is unchanged', await page.locator('.oe-banner').innerText(), noticeBefore);
    check(
      'Account B’s own claimable selection is still shown',
      await visible(page.locator('.oe-row--claim'))
    );
    const bodyText = await page.locator('body').innerText();
    check('Account A’s refresh-failure notice never appears under Account B', !/could not be refreshed just now/.test(bodyText));
    check('Account A’s claimed record never appears under Account B', !bodyText.includes('#5200'));
    checkEqual(
      'no extra command is transmitted when Account A’s stale read settles',
      stub.requests('/claim', 'POST').length + stub.requests('/release', 'POST').length,
      2
    );
    check(
      'every transmitted command carried the credential of the account that issued it',
      stub.requests('/claim', 'POST').every((call) => call.token === TOKEN_A || call.token === TOKEN_B)
    );
    await context.close();
  },

  /**
   * Package 1A correction 1 (discriminating form): the guard, not a render-time identity filter, is
   * what stops the stale notice write. A confirmed 204 with a failed reconciliation leaves Account A's
   * claim continuation awaiting `openDetail`; a transient authority loss invalidates the operation while
   * that read is in flight, and authority then recovers. Because it is the same account, an unguarded
   * write would survive the recovery and re-appear as a live banner — so the workspace must show none.
   */
  async function claimStaleNoticeSuppressedAcrossAuthorityLoss(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });

    // 204 confirmed, reconciliation fails, then the claimed-record detail read is held in flight.
    stub.failMatcher = (record) => record.method === 'GET'
      && (/\/operations\/my-work\?/.test(record.path) || /\/operations\/claimable-work\?/.test(record.path));
    stub.pause((record) => record.method === 'GET' && /visit-requests\/5200$/.test(record.path));
    await page.locator('.oe-row--claim .oe-btn').first().click();
    await new Promise((resolve) => setTimeout(resolve, 800));
    checkEqual('the claim command was transmitted under Account A', stub.requests('/claim', 'POST').length, 1);
    checkEqual('the post-204 claimed-record detail read is held in flight', stub.held.length, 1);
    stub.failMatcher = null;

    // A transient verification failure invalidates the pending operation while its read is in flight.
    stub.capabilityMode = 'error';
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await page.waitForSelector('text=We couldn’t verify your Operations access', { timeout: 8000 });
    await page.waitForTimeout(400);
    checkEqual('no notice is written while authority is unverified', await page.locator('.oe-banner').count(), 0);
    checkEqual('the invalidated claim transmits nothing', stub.requests('/claim', 'POST').length, 1);

    // Authority recovers: the workspace re-reads its own account's work.
    stub.capabilityMode = 'auto';
    await page.getByRole('button', { name: /^Retry$/ }).click();
    await page.waitForSelector('.oe-subnav', { timeout: 8000 });
    await page.waitForTimeout(600);

    // The stale continuation's refresh-failure notice must never resurface for the recovered account.
    checkEqual('no stale ownership notice resurfaces after recovery', await page.locator('.oe-banner').count(), 0);
    const bodyText = await page.locator('body').innerText();
    check('the invalidated claim’s refresh-failure notice is never written', !/could not be refreshed just now/.test(bodyText));
    checkEqual('the invalidated claim never transmits an extra command', stub.requests('/claim', 'POST').length, 1);

    // The held read settles last: still nothing may be written.
    stub.releaseHeld();
    await page.waitForTimeout(700);
    checkEqual('the late settle still writes no notice', await page.locator('.oe-banner').count(), 0);
    await context.close();
  },

  /**
   * Package 1A correction 2: a pending capability check must be scoped to the current session. Account
   * A's background verification is held indefinitely, then Account B signs in. B's own verification must
   * succeed and B must be able to claim while A's obsolete request is still pending; when A's request
   * finally settles it can neither clear B's gate nor alter B's authorization. Finally, a check that is
   * genuinely B's own must still fail the dispatch gate closed until it is verified.
   */
  async function obsoleteCapabilityCheckCannotBlockNextAccount(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await oeTab(page).click();
    await page.waitForSelector('.oe-queue', { timeout: 8000 });
    await page.waitForTimeout(400);

    // Account A's background capability check is held indefinitely (an approved cadence revalidation).
    stub.pause((record) => record.method === 'GET' && record.path.includes('/staff/me/capabilities'));
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await new Promise((resolve) => setTimeout(resolve, 400));
    checkEqual('an Account A capability check is held in flight', stub.held.length, 1);
    checkEqual('the held check belongs to Account A', stub.held[0].record.token, TOKEN_A);

    // Account B signs in while Account A's verification is still pending.
    await switchAccount(page, employeeProfile(TOKEN_B, 22));
    await page.waitForSelector('.oe-queue', { timeout: 8000 });
    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });
    check(
      'Account B’s own verification succeeded from its own credential',
      stub.requests('/staff/me/capabilities').some((call) => call.token === TOKEN_B)
    );
    // Account A's request is still pending while B is fully authenticated and on screen.
    checkEqual('Account A’s obsolete check is still in flight under Account B', stub.held.length, 1);
    checkEqual('Account A’s obsolete check is still Account A’s', stub.held[0].record.token, TOKEN_A);

    // B claims its own work: Account A's obsolete pending check must not block it.
    await page.locator('.oe-row--claim .oe-btn').first().click();
    await new Promise((resolve) => setTimeout(resolve, 1000));
    checkEqual('Account B’s claim is dispatched while Account A’s check is pending', stub.requests('/claim', 'POST').length, 1);
    checkEqual('the command carries Account B’s credential, not Account A’s', stub.requests('/claim', 'POST')[0].token, TOKEN_B);
    check(
      'Account A’s credential is never used for any command after the switch',
      stub.requests('/claim', 'POST').every((call) => call.token === TOKEN_B)
    );

    // Account A's obsolete request and its finalizer settle late: B is unaffected.
    stub.releaseHeld();
    await page.waitForTimeout(700);
    checkEqual('Account A’s late settlement never unlocks a duplicate command', stub.requests('/claim', 'POST').length, 1);
    const afterLateSettle = await page.locator('body').innerText();
    check('Account B still sees its own team after Account A’s late settlement', !/#5200/.test(afterLateSettle));

    // Fail-closed: a check that is genuinely Account B's own keeps B's mutation blocked until verified.
    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });
    stub.pause((record) => record.method === 'GET' && record.path.includes('/staff/me/capabilities'));
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await new Promise((resolve) => setTimeout(resolve, 400));
    checkEqual('an Account B capability check is held in flight', stub.held.length, 1);
    checkEqual('the held check belongs to Account B', stub.held[0].record.token, TOKEN_B);

    await page.locator('.oe-row--claim .oe-btn').first().click();
    await new Promise((resolve) => setTimeout(resolve, 900));
    checkEqual(
      'Account B’s own pending check keeps its claim blocked (fail closed)',
      stub.requests('/claim', 'POST').length,
      1
    );

    // Releasing Account B's own check is what allows the claim; the gate is then genuinely clean.
    stub.releaseHeld();
    await page.waitForTimeout(700);
    await page.locator('.oe-row--claim .oe-btn').first().click();
    await new Promise((resolve) => setTimeout(resolve, 1000));
    checkEqual('Account B’s claim is dispatched once its own check is verified', stub.requests('/claim', 'POST').length, 2);
    checkEqual('every command belongs to Account B', stub.requests('/claim', 'POST').every((call) => call.token === TOKEN_B), true);
    await context.close();
  },

  /* 8. Same-account workspace-entry lifecycle (Package 2 correction) ----------------- */

  /**
   * Scenario 1. Entry A's own verification is held indefinitely, the same authenticated account leaves
   * Operations and re-enters as entry B, completes B's verification, and then claims.
   *
   * The obsolete entry-scoped check from A must be retired when the entry identity changes, so it can no
   * longer gate the mutation; B is still admitted only by its own successful verification, and the
   * mutation's own fresh pre-flight requirement is still enforced before the POST.
   */
  async function obsoleteSameAccountEntryCheckCannotBlockClaim(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await page.waitForTimeout(400);

    // Entry A's own entry verification is held indefinitely.
    stub.pause((record) => record.method === 'GET' && record.path.includes('/staff/me/capabilities'));
    await oeTab(page).click();
    await page.waitForTimeout(400);
    checkEqual('entry A’s verification is held in flight', stub.held.length, 1);
    checkEqual('the held entry check belongs to the signed-in account', stub.held[0].record.token, TOKEN_A);
    checkEqual('entry A is not admitted while its own verification is pending', await page.locator('.oe-queue').count(), 0);

    // The same account leaves Operations and re-enters as entry B.
    await visitsTab(page).click();
    await page.waitForTimeout(250);
    await oeTab(page).click();
    await page.waitForSelector('.oe-queue', { timeout: 8000 });
    check('entry B is admitted only after its own verification succeeds', true);
    checkEqual('entry A’s obsolete check is still unresolved when B is admitted', stub.held.length, 1);
    checkEqual('entry A’s obsolete check is still Account A’s', stub.held[0].record.token, TOKEN_A);

    // Claim from entry B while entry A's obsolete verification remains unresolved.
    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });
    const capsBeforeClaim = stub.requests('/staff/me/capabilities').length;
    await page.locator('.oe-row--claim .oe-btn').first().click();
    await page.waitForTimeout(1200);
    checkEqual('the claim transmits exactly one authorized POST', stub.requests('/claim', 'POST').length, 1);
    checkEqual('the claim carries the signed-in credential', stub.requests('/claim', 'POST')[0]?.token ?? null, TOKEN_A);
    check(
      'the mutation’s own fresh pre-flight verification still ran before it was sent',
      stub.requests('/staff/me/capabilities').length > capsBeforeClaim,
      `before=${capsBeforeClaim} after=${stub.requests('/staff/me/capabilities').length}`
    );
    const claimed = await page.locator('body').innerText();
    check('the claim is reported as succeeded', /Request #5200 claimed/.test(claimed));

    // Entry A's obsolete verification settles last: it may not duplicate or disturb the command.
    stub.releaseHeld();
    await page.waitForTimeout(800);
    checkEqual('entry A’s late settle cannot unlock a duplicate command', stub.requests('/claim', 'POST').length, 1);
    checkEqual('entry A’s late settle writes no failure notice', await page.locator('.oe-banner').count(), 0);
    await context.close();
  },

  /**
   * Scenario 2. The same lifecycle, but the command is a Release of work the signed-in OE owns. The
   * obsolete entry check must not block it either, and the ownership/reconciliation rules are unchanged.
   */
  async function obsoleteSameAccountEntryCheckCannotBlockRelease(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await page.waitForTimeout(400);

    stub.pause((record) => record.method === 'GET' && record.path.includes('/staff/me/capabilities'));
    await oeTab(page).click();
    await page.waitForTimeout(400);
    checkEqual('entry A’s verification is held in flight', stub.held.length, 1);

    await visitsTab(page).click();
    await page.waitForTimeout(250);
    await oeTab(page).click();
    await page.waitForSelector('.oe-queue', { timeout: 8000 });
    check('entry B is admitted only after its own verification succeeds', true);
    checkEqual('entry A’s obsolete check is still unresolved when B is admitted', stub.held.length, 1);

    // Open owned work and release it while entry A's obsolete verification is still pending.
    await page.getByRole('button', { name: /My work/i }).first().click();
    await page.waitForSelector('.oe-row--owned', { timeout: 8000 });
    await page.locator('.oe-row--owned .oe-row-link').first().click();
    await page.waitForSelector('.oe-detail-grid', { timeout: 8000 });
    await page.getByRole('button', { name: /Release session/i }).click();
    await page.waitForTimeout(1200);
    checkEqual('the release transmits exactly one authorized POST', stub.requests('/release', 'POST').length, 1);
    checkEqual('the release carries the signed-in credential', stub.requests('/release', 'POST')[0]?.token ?? null, TOKEN_A);
    const released = await page.locator('body').innerText();
    check('the release is reported as succeeded', /released/.test(released));
    checkEqual('no claim command is transmitted either', stub.requests('/claim', 'POST').length, 0);

    stub.releaseHeld();
    await page.waitForTimeout(800);
    checkEqual('entry A’s late settle cannot duplicate the release', stub.requests('/release', 'POST').length, 1);
    checkEqual('no ownership command is repeated after the obsolete check settles', stub.requests('/claim', 'POST').length, 0);
    await context.close();
  },

  /**
   * Scenario 3. Retiring the obsolete entry check must not weaken the current-check contract: with entry
   * B admitted, a genuinely current revalidation is started and held. The mutation stays fail-closed
   * until that current check verifies, and only then is exactly one POST transmitted.
   */
  async function currentEntryCheckStillBlocksSameAccountMutation(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await page.waitForTimeout(400);

    // Establish an obsolete entry check, then re-enter and admit a fresh entry B.
    stub.pause((record) => record.method === 'GET' && record.path.includes('/staff/me/capabilities'));
    await oeTab(page).click();
    await page.waitForTimeout(400);
    await visitsTab(page).click();
    await page.waitForTimeout(250);
    await oeTab(page).click();
    await page.waitForSelector('.oe-queue', { timeout: 8000 });
    checkEqual('entry A’s obsolete check is still unresolved', stub.held.length, 1);

    // A genuinely current revalidation for entry B's own generation is now held in flight.
    stub.pause((record) => record.method === 'GET' && record.path.includes('/staff/me/capabilities'));
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await page.waitForTimeout(400);
    checkEqual('a genuinely current capability check is held in flight', stub.held.length, 2);

    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });
    await page.locator('.oe-row--claim .oe-btn').first().click();
    await page.waitForTimeout(1000);
    checkEqual('a current pending check keeps the claim blocked (fail closed)', stub.requests('/claim', 'POST').length, 0);

    // Only the current check is released; its success is what makes dispatch possible.
    const currentRecord = stub.held[1].record;
    stub.releaseHeldWhere((record) => record === currentRecord);
    await page.waitForTimeout(700);
    await page.locator('.oe-row--claim .oe-btn').first().click();
    await page.waitForTimeout(1200);
    checkEqual('the claim is dispatched once the current check is verified', stub.requests('/claim', 'POST').length, 1);

    // Entry A's obsolete check settles last: it must not duplicate or disturb the command.
    stub.releaseHeld();
    await page.waitForTimeout(700);
    checkEqual('no duplicate command after the obsolete check settles last', stub.requests('/claim', 'POST').length, 1);
    await context.close();
  },

  /**
   * Scenario 4. A late completion/finalizer from entry A must not alter entry B: with A's response frozen
   * as a *grant* and B's own verification still pending, releasing A's response may neither admit B, nor
   * start a protected read, nor clear B's pending gate, nor transmit a stale mutation.
   */
  async function obsoleteEntryFinalizerCannotAlterCurrentEntry(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await page.waitForTimeout(400);

    // Entry A's response is frozen as a grant *now*, then held indefinitely.
    stub.freezeHeldResponse = true;
    stub.pause((record) => record.method === 'GET' && record.path.includes('/staff/me/capabilities'));
    await oeTab(page).click();
    await page.waitForTimeout(400);
    checkEqual('entry A’s verification is held in flight', stub.held.length, 1);

    await visitsTab(page).click();
    await page.waitForTimeout(250);

    // Re-enter, holding entry B's own verification too, so B stays genuinely unverified.
    stub.pause((record) => record.method === 'GET' && record.path.includes('/staff/me/capabilities'));
    await oeTab(page).click();
    await page.waitForTimeout(500);
    checkEqual('entry B’s own verification is held in flight', stub.held.length, 2);
    checkEqual('entry B is not admitted while its own check is pending', await page.locator('.oe-queue').count(), 0);
    const readsWhilePending = stub.requests('/operations/').length;

    // Entry A's frozen granted response settles first.
    const entryARecord = stub.held[0].record;
    stub.releaseHeldWhere((record) => record === entryARecord);
    await page.waitForTimeout(800);
    checkEqual('a late grant from entry A cannot admit entry B', await page.locator('.oe-queue').count(), 0);
    checkEqual('entry A’s late grant starts no protected read', stub.requests('/operations/').length, readsWhilePending);
    checkEqual('entry B’s own pending check survives entry A’s finalizer', stub.held.length, 1);
    checkEqual(
      'no mutation is transmitted while entry B is unverified',
      stub.requests('/claim', 'POST').length + stub.requests('/release', 'POST').length,
      0
    );

    // Entry B is admitted by its own verification, and still nothing stale is transmitted.
    stub.releaseHeld();
    await page.waitForTimeout(800);
    check('entry B is admitted once its own verification settles', (await page.locator('.oe-queue').count()) > 0);
    checkEqual(
      'no stale mutation was transmitted in either direction',
      stub.requests('/claim', 'POST').length + stub.requests('/release', 'POST').length,
      0
    );
    await context.close();
  },

  /**
   * Scenario 5 (same-account regression guard): rapid exit/re-entry keeps a single working authority and
   * still leaves a clean mutation path — no duplicate commands, no lost admission, no leaked reads.
   */
  async function rapidSameAccountExitReentry(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, { path: '/crm', storage: employeeProfile(TOKEN_A, 11) });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await page.waitForTimeout(400);

    for (let cycle = 0; cycle < 3; cycle += 1) {
      await oeTab(page).click();
      await page.waitForSelector('.oe-queue', { timeout: 8000 });
      await visitsTab(page).click();
      await page.waitForTimeout(180);
    }
    await oeTab(page).click();
    await page.waitForSelector('.oe-queue', { timeout: 8000 });
    check('a rapid same-account exit/re-entry cycle still admits the workspace', true);

    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });
    await page.locator('.oe-row--claim .oe-btn').first().click();
    await page.waitForTimeout(1200);
    checkEqual('a claim still transmits exactly once after rapid re-entries', stub.requests('/claim', 'POST').length, 1);
    check(
      'every capability read carried the signed-in credential across the churn',
      stub.requests('/staff/me/capabilities').every((call) => call.token === TOKEN_A)
    );
    checkEqual('the churn transmits no release command', stub.requests('/release', 'POST').length, 0);
    await context.close();
  },

  /**
   * Package 2 correction B (Claim). A mutation check belongs to the operation that asked for it, not to
   * the account. When that operation is invalidated and aborted — the entry is left — its capability
   * transport is held open forever, so its promise never settles. The retired check must stop gating the
   * mutation gate at the moment it is cancelled: the next entry's own Claim must run its own fresh
   * verification and transmit exactly one POST, with nothing stale sent from the abandoned entry.
   */
  async function abortedMutationCheckNeverBlocksNextEntryClaim(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, {
      path: '/crm',
      storage: employeeProfile(TOKEN_A, 11),
      ignoreCapabilityAbort: true
    });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await page.waitForTimeout(400);

    // Entry A is admitted, then a Claim starts and its own mutation pre-flight is held indefinitely.
    await oeTab(page).click();
    await page.waitForSelector('.oe-queue', { timeout: 8000 });
    stub.pause((record) => record.method === 'GET' && record.path.includes('/staff/me/capabilities'));
    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });
    await page.locator('.oe-row--claim .oe-btn').first().click();
    await page.waitForTimeout(500);
    checkEqual('entry A’s Claim mutation pre-flight is held in flight', stub.held.length, 1);
    checkEqual('the held mutation check belongs to the signed-in account', stub.held[0].record.token, TOKEN_A);

    // Leaving entry A invalidates and aborts the operation; its held transport never settles.
    await visitsTab(page).click();
    await page.waitForTimeout(250);
    await oeTab(page).click();
    await page.waitForSelector('.oe-queue', { timeout: 8000 });
    check('entry B is admitted while entry A’s mutation check is still unresolved', true);
    checkEqual('entry A’s aborted mutation check is still unresolved', stub.held.length, 1);
    checkEqual('nothing was transmitted from the abandoned entry', stub.requests('/claim', 'POST').length, 0);

    // Entry B's own Claim must run its own verification and transmit exactly once.
    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.waitForSelector('.oe-row--claim', { timeout: 8000 });
    const capsBeforeClaim = stub.requests('/staff/me/capabilities').length;
    await page.locator('.oe-row--claim .oe-btn').first().click();
    await page.waitForTimeout(1200);
    checkEqual('exactly one authorized Claim POST is transmitted for entry B', stub.requests('/claim', 'POST').length, 1);
    check(
      'the new Claim ran its own fresh verification first',
      stub.requests('/staff/me/capabilities').length > capsBeforeClaim,
      `before=${capsBeforeClaim} after=${stub.requests('/staff/me/capabilities').length}`
    );
    check('the new Claim is reported as succeeded', /Request #5200 claimed/.test(await page.locator('body').innerText()));

    // Entry A's never-settling check settles last: nothing more may leave the client.
    stub.releaseHeld();
    await page.waitForTimeout(800);
    checkEqual('no stale Claim is transmitted after the retired check settles', stub.requests('/claim', 'POST').length, 1);
    checkEqual('the stale settle transmits no Release either', stub.requests('/release', 'POST').length, 0);
    checkEqual('the stale settle writes no failure notice', await page.locator('.oe-banner').count(), 0);
    await context.close();
  },

  /**
   * Package 2 correction B (Release). The same lifecycle for a Release of owned work: the abandoned
   * entry's never-settling mutation check must not block the next entry's authorized Release, and no
   * ownership or lock state may be corrupted.
   */
  async function abortedMutationCheckNeverBlocksNextEntryRelease(browser) {
    stub.reset();
    const { context, page } = await newContext(browser, {
      path: '/crm',
      storage: employeeProfile(TOKEN_A, 11),
      ignoreCapabilityAbort: true
    });
    await oeTab(page).waitFor({ state: 'visible', timeout: 8000 });
    await page.waitForTimeout(400);

    await oeTab(page).click();
    await page.waitForSelector('.oe-queue', { timeout: 8000 });
    await page.getByRole('button', { name: /My work/i }).first().click();
    await page.waitForSelector('.oe-row--owned', { timeout: 8000 });
    await page.locator('.oe-row--owned .oe-row-link').first().click();
    await page.waitForSelector('.oe-detail-grid', { timeout: 8000 });

    // The Release's own mutation pre-flight is held indefinitely.
    stub.pause((record) => record.method === 'GET' && record.path.includes('/staff/me/capabilities'));
    await page.getByRole('button', { name: /Release session/i }).click();
    await page.waitForTimeout(500);
    checkEqual('entry A’s Release mutation pre-flight is held in flight', stub.held.length, 1);

    // Leave entry A: the operation is invalidated and aborted, its transport never settles.
    await visitsTab(page).click();
    await page.waitForTimeout(250);
    await oeTab(page).click();
    await page.waitForSelector('.oe-queue', { timeout: 8000 });
    checkEqual('entry A’s aborted mutation check is still unresolved', stub.held.length, 1);
    checkEqual('nothing was transmitted from the abandoned entry', stub.requests('/release', 'POST').length, 0);

    // Release the same owned work from entry B: exactly one authorized POST.
    await page.getByRole('button', { name: /My work/i }).first().click();
    await page.waitForSelector('.oe-row--owned', { timeout: 8000 });
    await page.locator('.oe-row--owned .oe-row-link').first().click();
    await page.waitForSelector('.oe-detail-grid', { timeout: 8000 });
    await page.getByRole('button', { name: /Release session/i }).click();
    await page.waitForTimeout(1200);
    checkEqual('exactly one authorized Release POST is transmitted for entry B', stub.requests('/release', 'POST').length, 1);
    checkEqual('the release carries the signed-in credential', stub.requests('/release', 'POST')[0]?.token ?? null, TOKEN_A);
    check('the new Release is reported as succeeded', /Session #3108 released/.test(await page.locator('body').innerText()));
    checkEqual('no Claim is transmitted along the way', stub.requests('/claim', 'POST').length, 0);

    stub.releaseHeld();
    await page.waitForTimeout(800);
    checkEqual('no duplicate Release after the retired check settles', stub.requests('/release', 'POST').length, 1);
    checkEqual('no stale Claim after the retired check settles', stub.requests('/claim', 'POST').length, 0);
    await context.close();
  }
];

const main = async () => {
  // The environment has the full Chromium build (chromium-1200) but not the headless shell.
  const browser = await chromium.launch({ channel: 'chromium' });
  // Optional focused run, e.g. OE_BROWSER_SCENARIOS=a,b to exercise single scenarios while iterating.
  const only = (process.env.OE_BROWSER_SCENARIOS ?? '').split(',').map((name) => name.trim()).filter(Boolean);
  for (const scenario of scenarios) {
    if (only.length > 0 && !only.includes(scenario.name)) continue;
    stub.unknown.length = 0;
    results.push(`\n▶ ${scenario.name}`);
    try {
      await scenario(browser);
    } catch (error) {
      failures += 1;
      results.push(`  ✖ scenario threw: ${error.message}`);
    }
    if (stub.unknown.length > 0) {
      results.push(`  · unanswered stub paths: ${[...new Set(stub.unknown)].join(', ')}`);
    }
  }
  await browser.close();
  console.log(results.join('\n'));
  console.log(
    `\n${checks} checks, ${failures} failure(s) — ${failures === 0 ? 'ALL BROWSER CHECKS PASSED' : 'BROWSER CHECKS FAILED'}`
  );
  process.exitCode = failures === 0 ? 0 : 1;
};

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
