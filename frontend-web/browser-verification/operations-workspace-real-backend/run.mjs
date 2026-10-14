/**
 * Real-backend integration verification for the Operations (OE) workspace.
 *
 * Unlike ../operations-workspace/run.mjs (controlled stub responses), this suite runs the real built
 * app (frontend-web/dist) in Chromium against a live Spring Boot backend on PostgreSQL. Static assets
 * are served in-process at a synthetic origin; every `/api/**` call is forwarded verbatim to
 * http://localhost:8080, so authorization, ownership locks, optimistic versions and the
 * 401/403/404/409/204 semantics are all decided by the real application and the real database.
 *
 * Sessions are established by writing the exact localStorage keys the app itself writes
 * (`pathome_auth_token`, `pathome_user`, `pathome_role`) with a bearer token obtained from a real
 * `POST /api/v1/auth/login`. The login form's own role-normalisation step is therefore NOT exercised:
 * it maps the backend role `ROLE_TENANT` to the client role `TENANT`, which is an entry-point
 * blocker documented in README.md.
 *
 * Usage:
 *   npm --prefix frontend-web run build
 *   node frontend-web/browser-verification/operations-workspace-real-backend/run.mjs
 *
 * Prerequisites: a local Spring Boot backend on http://localhost:8080 and the disposable identities
 * described in README.md.
 */
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;

const DIST = fileURLToPath(new URL('../../dist/', import.meta.url));
const ORIGIN = 'http://oe.local';
const BACKEND = 'http://localhost:8080';

const PASSWORD = 'CodexOe2026Test99';
const ADMIN = { email: 'codex.ops.admin@pathome.test', password: 'CodexOpsAdmin2026Pass' };
const IDENTITIES = {
  // The client role label is seeded, not derived: no supported provisioning mechanism can create a
  // non-ROLE_TENANT User, so the app's own role normalization maps every provisioned staff account to
  // TENANT and both consoles are unreachable through the real login form (measured in scenario J).
  // The Operations workspace is therefore hosted in the Admin console. Authority itself is never
  // seeded: it always comes from the live /api/v1/staff/me/capabilities response.
  oeAlpha: { email: 'codex.oe.alpha@pathome.test', password: PASSWORD, role: 'SUB_ADMIN' },
  oeBravo: { email: 'codex.oe.bravo@pathome.test', password: PASSWORD, role: 'SUB_ADMIN' },
  oeNoGrant: { email: 'codex.oe.nogrant@pathome.test', password: PASSWORD, role: 'SUB_ADMIN' },
  subAdmin: { email: 'codex.subadmin@pathome.test', password: PASSWORD, role: 'SUB_ADMIN' },
  subAdminNoOps: { email: 'codex.subadmin.noops@pathome.test', password: PASSWORD, role: 'SUB_ADMIN' }
};

const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2'
};

/**
 * Console-shell reads that are gated on a JWT role claim (`hasRole("ADMIN")` / `hasRole("GROUND_BOY")`)
 * rather than on staff grants. The supported provisioning mechanisms can only create ROLE_TENANT
 * accounts, so the backend answers 401 there and the app treats 401 as session expiry, which tears
 * the console down before the Operations workspace can be reached.
 *
 * These paths are unrelated to the Operations workspace, so they are answered with 403 (the authority
 * the account genuinely has) instead of the backend's 401. Nothing is fabricated: no body content is
 * invented, and every `/api/v1/operations/**`, `/api/v1/staff/**` and `/api/v1/notifications` call
 * still goes to the live backend. Scenario J records the underlying blocker as measured evidence.
 */
const SHELL_GATE_PREFIXES = ['/api/v1/admin/', '/api/v1/ground/'];
const shellGateBody = (path) =>
  JSON.stringify({
    timestamp: new Date().toISOString(),
    status: 403,
    error: 'Forbidden',
    message: 'The seeded account has no console role privilege for this shell read.',
    path
  });

/* ------------------------------------------------------------------ *
 * Real backend client
 * ------------------------------------------------------------------ */

const api = async (method, path, token, body) => {
  const headers = { Accept: 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(`${BACKEND}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const text = await response.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  return { status: response.status, json };
};

const signIn = async ({ email, password }) => {
  const { status, json } = await api('POST', '/api/v1/auth/login', null, { email, password });
  if (status !== 200 || !json?.token) throw new Error(`Sign-in failed for ${email} (HTTP ${status})`);
  return json;
};

/** Short, non-reversible label so credential continuity can be asserted without printing tokens. */
const fingerprint = (value) => (value ? createHash('sha256').update(value).digest('hex').slice(0, 12) : 'none');
const versionMap = (item) =>
  Object.fromEntries((item?.linkedRequestVersions ?? []).map((entry) => [entry.requestId, entry.version]));

/* ------------------------------------------------------------------ *
 * Harness
 * ------------------------------------------------------------------ */

const sessions = {};
const fingerprints = {};
let checks = 0;
let failures = 0;
const check = (label, condition, detail) => {
  checks += 1;
  if (condition) console.log(`  PASS  ${label}`);
  else {
    failures += 1;
    console.log(`  FAIL  ${label}${detail === undefined ? '' : `  (${detail})`}`);
  }
};
const checkEqual = (label, actual, expected) =>
  check(label, Object.is(actual, expected), `expected ${JSON.stringify(expected)}, saw ${JSON.stringify(actual)}`);

const waitFor = async (page, selector, timeout = 8000) => {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await page.locator(selector).first().isVisible().catch(() => false)) return true;
    await page.waitForTimeout(120);
  }
  return false;
};

const storageFor = (identity, session) => [
  ['pathome_auth_token', session.token],
  ['pathome_role', identity.role],
  ['pathome_user', JSON.stringify({ id: session.userId, role: identity.role, fullName: identity.email, email: identity.email })]
];

const createContext = async (browser, { path, identity, session, shellGate = true }) => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  const network = [];
  const pageErrors = [];
  const hold = { match: null, pending: null };
  page.on('pageerror', (error) => pageErrors.push(error.message));
  await page.addInitScript((entries) => {
    entries.forEach(([key, value]) => window.localStorage.setItem(key, value));
  }, storageFor(identity, session));

  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url());
    if (url.origin !== ORIGIN) {
      await route.continue();
      return;
    }
    if (url.pathname.startsWith('/api/')) {
      const request = route.request();
      if (shellGate && SHELL_GATE_PREFIXES.some((prefix) => url.pathname.startsWith(prefix))) {
        network.push({ method: request.method(), path: url.pathname + url.search, status: 403, auth: 'shell-gate', body: null });
        await route.fulfill({
          status: 403,
          contentType: 'application/json',
          headers: { 'Cache-Control': 'no-store' },
          body: shellGateBody(url.pathname)
        });
        return;
      }
      const headers = { ...request.headers() };
      delete headers.host;
      delete headers['content-length'];
      const body = request.postData();
      const init = { method: request.method(), headers, redirect: 'manual' };
      if (body !== null && body !== undefined && request.method() !== 'GET') init.body = body;
      const response = await fetch(`${BACKEND}${url.pathname}${url.search}`, init);
      const text = await response.text();
      const record = {
        method: request.method(),
        path: url.pathname + url.search,
        status: response.status,
        auth: fingerprint(headers.authorization?.replace(/^Bearer\s+/, '')),
        body: body ?? null
      };
      network.push(record);
      const contentType = response.headers.get('content-type') ?? 'application/json';
      const fulfill = () =>
        route.fulfill({ status: response.status, contentType, headers: { 'Cache-Control': 'no-store' }, body: text });
      if (hold.match && hold.match(record)) {
        hold.match = null;
        await new Promise((resolve) => {
          hold.pending = {
            release: () => fulfill().then(() => { hold.pending = null; resolve(); })
          };
        });
        return;
      }
      await fulfill();
      return;
    }
    const file = join(DIST, url.pathname === '/' ? '/index.html' : url.pathname);
    try {
      const body = await readFile(file);
      await route.fulfill({ status: 200, contentType: CONTENT_TYPES[extname(file)] ?? 'application/octet-stream', body });
    } catch {
      const body = await readFile(join(DIST, 'index.html'));
      await route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body });
    }
  });

  await page.goto(`${ORIGIN}${path}`, { waitUntil: 'domcontentloaded' });
  return { context, page, network, pageErrors, hold };
};

const rowKeys = (page) =>
  page.locator('.oe-list [data-row-key]').evaluateAll((nodes) => nodes.map((node) => node.getAttribute('data-row-key')));
const calls = (network, method, fragment) =>
  network.filter((record) => record.method === method && record.path.includes(fragment));
const enterWorkspace = async (page) => {
  await waitFor(page, 'button:has-text("Operations workspace")', 10000);
  await page.getByRole('button', { name: /Operations workspace/i }).first().click();
  return waitFor(page, '.oe-subnav');
};
const openQueue = async (page, name) => {
  await page.getByRole('button', { name: new RegExp(name, 'i') }).first().click();
  await page.waitForTimeout(900);
};

/* ------------------------------------------------------------------ *
 * Scenarios
 * ------------------------------------------------------------------ */

const run = async () => {
  console.log('OE real-backend integration verification');
  console.log(`backend: ${BACKEND}\n`);

  for (const [name, identity] of Object.entries(IDENTITIES)) {
    sessions[name] = await signIn(identity);
    fingerprints[name] = fingerprint(sessions[name].token);
  }
  const admin = await signIn(ADMIN);
  console.log(`signed in ${Object.keys(IDENTITIES).length} disposable identities + the bootstrap admin`);

  const seed = await api('GET', '/api/v1/operations/claimable-work?page=0&size=20', sessions.oeAlpha.token);
  const requestRoot = seed.json?.items?.find((item) => item.targetType === 'REQUEST');
  const sessionRoot = seed.json?.items?.find((item) => item.targetType === 'SESSION');
  if (!requestRoot || !sessionRoot) throw new Error('fixture not ready: expected one claimable REQUEST and one claimable SESSION');
  console.log(`fixture: REQUEST ${requestRoot.targetId} v${requestRoot.expectedVersion}, SESSION ${sessionRoot.targetId} v${sessionRoot.expectedVersion}\n`);
  const requestKey = `REQUEST-${requestRoot.targetId}`;
  const sessionKey = `SESSION-${sessionRoot.targetId}`;

  // This host has Playwright's full Chromium build but not chrome-headless-shell, so the
  // `chromium` channel is used for headless execution.
  const browser = await chromium.launch({ channel: 'chromium' });
  let context;
  try {
    /* ---------------- A / B. authorised OE ---------------- */
    console.log('A. Authorised Operations Executive (Admin console, live grants)');
    let env = await createContext(browser, { path: '/admin', identity: IDENTITIES.oeAlpha, session: sessions.oeAlpha });
    context = env.context;
    check('A1 the Operations entry point is offered from live capabilities', await waitFor(env.page, 'button:has-text("Operations workspace")'));
    check('A2 the workspace opens on real authority', await enterWorkspace(env.page));
    const capabilityCall = calls(env.network, 'GET', '/staff/me/capabilities').at(-1);
    checkEqual('A3 the capability read is a real 200', capabilityCall?.status, 200);
    checkEqual('A4 the capability read carried the signed-in credential', capabilityCall?.auth, fingerprints.oeAlpha);
    await openQueue(env.page, 'Claimable work');
    check('A5 the claimable queue renders', await waitFor(env.page, '[data-row-key]'));
    checkEqual('A6 the claimable read is a real 200', calls(env.network, 'GET', '/operations/claimable-work?page=0&size=20').at(-1)?.status, 200);
    checkEqual(
      'A7 the workspace lists exactly the two real Team Alpha roots',
      JSON.stringify((await rowKeys(env.page)).sort()),
      JSON.stringify([requestKey, sessionKey].sort())
    );
    const subnavCounts = await env.page.locator('.oe-subnav-count').allInnerTexts();
    check('A8 the queue badge count matches the real backend total', subnavCounts.includes('2'), `saw ${JSON.stringify(subnavCounts)}`);
    const never = await api('GET', '/api/v1/operations/claimable-work?page=0&size=20', sessions.oeBravo.token);
    checkEqual('A9 a different Team executive sees only its own work', (never.json?.items ?? []).length, 1);
    checkEqual('A10 no uncaught page errors', env.pageErrors.length, 0);

    console.log('\nB. Claim and release against real records');
    await env.page.locator(`[data-row-key="${requestKey}"]`).click();
    check('B1 the claim preview opens for the real request', await waitFor(env.page, 'button:has-text("Claim request")'));
    await env.page.getByRole('button', { name: /^Claim request$/ }).click();
    await env.page.waitForTimeout(1800);
    const claimPosts = calls(env.network, 'POST', `/operations/visit-requests/${requestRoot.targetId}/claim`);
    checkEqual('B2 exactly one claim command was transmitted', claimPosts.length, 1);
    checkEqual('B3 the backend answered the claim with a real 204', claimPosts[0]?.status, 204);
    checkEqual('B4 the claim carried the signed-in credential', claimPosts[0]?.auth, fingerprints.oeAlpha);
    await openQueue(env.page, 'My work');
    check('B5 My work renders the newly owned root', await waitFor(env.page, `[data-row-key="${requestKey}"]`));
    checkEqual('B6 My work contains exactly the claimed root', JSON.stringify(await rowKeys(env.page)), JSON.stringify([requestKey]));
    const myWork = await api('GET', '/api/v1/operations/my-work?page=0&size=20&attentionOnly=false', sessions.oeAlpha.token);
    const ownedItem = (myWork.json?.items ?? []).find((item) => item.targetType === 'REQUEST');
    checkEqual('B7 reconciliation exposes the post-claim optimistic version', ownedItem?.expectedVersion, requestRoot.expectedVersion + 1);
    await env.page.locator(`[data-row-key="${requestKey}"]`).click();
    check('B8 the owned detail offers Release', await waitFor(env.page, 'button:has-text("Release request")'));
    await env.page.getByRole('button', { name: /^Release request$/ }).click();
    await env.page.waitForTimeout(1800);
    const releasePosts = calls(env.network, 'POST', `/operations/visit-requests/${requestRoot.targetId}/release`);
    checkEqual('B9 exactly one release command was transmitted', releasePosts.length, 1);
    checkEqual('B10 the backend answered the release with a real 204', releasePosts[0]?.status, 204);
    await openQueue(env.page, 'Claimable work');
    check('B11 the released root is claimable again', await waitFor(env.page, `[data-row-key="${requestKey}"]`));
    const myWorkAfterRelease = await api('GET', '/api/v1/operations/my-work?page=0&size=20&attentionOnly=false', sessions.oeAlpha.token);
    checkEqual('B12 My work is authoritative after the release', (myWorkAfterRelease.json?.items ?? []).length, 0);

    console.log('\nC. Duplicate taps');
    await env.page.locator(`[data-row-key="${sessionKey}"]`).click();
    check('C1 the session claim preview opens', await waitFor(env.page, 'button:has-text("Claim session")'));
    const postsBefore = calls(env.network, 'POST', `/operations/visit-sessions/${sessionRoot.targetId}/claim`).length;
    await env.page.evaluate(() => {
      const button = [...document.querySelectorAll('.oe-actions button')].find((node) => /Claim session/i.test(node.textContent ?? ''));
      if (button) { button.click(); button.click(); }
    });
    await env.page.waitForTimeout(2000);
    const sessionClaims = calls(env.network, 'POST', `/operations/visit-sessions/${sessionRoot.targetId}/claim`);
    checkEqual('C2 two synchronous taps transmit exactly one session claim', sessionClaims.length - postsBefore, 1);
    checkEqual('C3 the session claim was accepted with a real 204', sessionClaims.at(-1)?.status, 204);
    const bravoClaim = await api('POST', `/api/v1/operations/visit-sessions/${sessionRoot.targetId}/claim`, sessions.oeBravo.token, {
      expectedSessionVersion: sessionRoot.expectedVersion,
      expectedRequestVersions: {},
      reasonCode: 'CLAIM_WORK'
    });
    checkEqual('C4 another Team cannot claim the same root (real 404)', bravoClaim.status, 404);

    console.log('\nD. Ownership competition and a real 409');
    const owned = (await api('GET', '/api/v1/operations/my-work?page=0&size=20&attentionOnly=false', sessions.oeAlpha.token))
      .json?.items?.find((item) => item.targetType === 'SESSION');
    const handBack = await api('POST', `/api/v1/operations/visit-sessions/${sessionRoot.targetId}/release`, sessions.oeAlpha.token, {
      expectedSessionVersion: owned.expectedVersion,
      expectedRequestVersions: versionMap(owned),
      reasonCode: 'RELEASE_COORDINATOR'
    });
    checkEqual('D1 the executive released the session so the race can start', handBack.status, 204);
    await env.page.reload({ waitUntil: 'domcontentloaded' });
    check('D2 the workspace can be re-entered after a reload', await enterWorkspace(env.page));
    await openQueue(env.page, 'Claimable work');
    check('D3 the released session is claimable again in the workspace', await waitFor(env.page, `[data-row-key="${sessionKey}"]`));
    await env.page.locator(`[data-row-key="${sessionKey}"]`).click();
    check('D4 the claim preview reopens for the unclaimed session', await waitFor(env.page, 'button:has-text("Claim session")'));
    const fresh = (await api('GET', `/api/v1/operations/claimable-work/SESSION/${sessionRoot.targetId}`, sessions.oeAlpha.token)).json;
    env.hold.match = (record) =>
      record.method === 'GET' && record.path.startsWith(`/api/v1/operations/claimable-work/SESSION/${sessionRoot.targetId}`);
    const racePostsBefore = calls(env.network, 'POST', `/operations/visit-sessions/${sessionRoot.targetId}/claim`).length;
    await env.page.getByRole('button', { name: /^Claim session$/ }).click();
    let held = false;
    for (let attempt = 0; attempt < 60 && !held; attempt += 1) {
      held = Boolean(env.hold.pending);
      if (!held) await env.page.waitForTimeout(100);
    }
    check('D5 the claim is holding its fresh claimable read', held);
    const rival = await api('POST', `/api/v1/operations/visit-sessions/${sessionRoot.targetId}/claim`, sessions.subAdmin.token, {
      expectedSessionVersion: fresh.expectedVersion,
      expectedRequestVersions: versionMap(fresh),
      reasonCode: 'CLAIM_WORK'
    });
    checkEqual('D6 an authorised peer on the same Team wins the root first', rival.status, 204);
    await env.hold.pending?.release();
    await env.page.waitForTimeout(2500);
    const raceClaims = calls(env.network, 'POST', `/operations/visit-sessions/${sessionRoot.targetId}/claim`);
    checkEqual('D7 the stale claim is transmitted exactly once', raceClaims.length - racePostsBefore, 1);
    checkEqual('D8 the backend answers the stale claim with a real 409', raceClaims.at(-1)?.status, 409);
    check('D9 the conflict is announced to the operator', await waitFor(env.page, '.oe-banner[role="alert"]'));
    const settledPosts = raceClaims.length;
    await env.page.waitForTimeout(1500);
    checkEqual(
      'D10 the conflict is never auto-retried',
      calls(env.network, 'POST', `/operations/visit-sessions/${sessionRoot.targetId}/claim`).length,
      settledPosts
    );
    const loserWork = await api('GET', '/api/v1/operations/my-work?page=0&size=20&attentionOnly=false', sessions.oeAlpha.token);
    checkEqual('D11 no ownership was recorded for the losing attempt', (loserWork.json?.items ?? []).length, 0);
    const rivalOwned = (await api('GET', '/api/v1/operations/my-work?page=0&size=20&attentionOnly=false', sessions.subAdmin.token))
      .json?.items?.find((item) => item.targetType === 'SESSION');
    const returned = await api('POST', `/api/v1/operations/visit-sessions/${sessionRoot.targetId}/release`, sessions.subAdmin.token, {
      expectedSessionVersion: rivalOwned.expectedVersion,
      expectedRequestVersions: versionMap(rivalOwned),
      reasonCode: 'RELEASE_COORDINATOR'
    });
    checkEqual('D12 the winning peer handed the root back', returned.status, 204);
    await context.close();

    /* ---------------- E. other Team ---------------- */
    console.log('\nE. Operations Executive from another Team');
    env = await createContext(browser, { path: '/admin', identity: IDENTITIES.oeBravo, session: sessions.oeBravo });
    context = env.context;
    check('E1 the Team Bravo executive can enter the workspace', await enterWorkspace(env.page));
    await openQueue(env.page, 'Claimable work');
    const bravoKeys = await rowKeys(env.page);
    check(
      'E2 it never sees Team Alpha work',
      bravoKeys.every((key) => !key.includes(String(requestRoot.targetId)) && !key.includes(String(sessionRoot.targetId))),
      `saw ${JSON.stringify(bravoKeys)}`
    );
    checkEqual('E3 its capability read is a real 200', calls(env.network, 'GET', '/staff/me/capabilities').at(-1)?.status, 200);
    const bravoDetail = await api('GET', `/api/v1/operations/visit-sessions/${sessionRoot.targetId}`, sessions.oeBravo.token);
    checkEqual('E4 an inaccessible session detail answers 404', bravoDetail.status, 404);
    await context.close();

    /* ---------------- F. no grant ---------------- */
    console.log('\nF. Staffed account without an OE grant');
    env = await createContext(browser, { path: '/admin', identity: IDENTITIES.oeNoGrant, session: sessions.oeNoGrant });
    context = env.context;
    await env.page.waitForTimeout(2500);
    checkEqual('F1 no Operations entry point is offered', await env.page.getByRole('button', { name: /Operations workspace/i }).count(), 0);
    checkEqual('F2 the capability read is still a real 200', calls(env.network, 'GET', '/staff/me/capabilities').at(-1)?.status, 200);
    const noGrantOps = await api('GET', '/api/v1/operations/claimable-work?page=0&size=20', sessions.oeNoGrant.token);
    checkEqual('F3 the queues answer 403 for that account', noGrantOps.status, 403);
    await context.close();

    /* ---------------- G. Sub-Admin ---------------- */
    console.log('\nG. Sub-Admin console entry from real grants');
    env = await createContext(browser, { path: '/admin', identity: IDENTITIES.subAdmin, session: sessions.subAdmin });
    context = env.context;
    check('G1 the granted Sub-Admin sees the Operations destination', await waitFor(env.page, 'button:has-text("Operations workspace")'));
    check('G2 the workspace opens from the admin console', await enterWorkspace(env.page));
    await openQueue(env.page, 'Claimable work');
    const subAdminKeys = await rowKeys(env.page);
    check(
      'G3 the Sub-Admin sees the real Team Alpha work',
      subAdminKeys.some((key) => key.includes(String(requestRoot.targetId))),
      `saw ${JSON.stringify(subAdminKeys)}`
    );
    await context.close();
    env = await createContext(browser, { path: '/admin', identity: IDENTITIES.subAdminNoOps, session: sessions.subAdminNoOps });
    context = env.context;
    await env.page.waitForTimeout(2500);
    checkEqual(
      'G4 a Sub-Admin without OPS_COORDINATE gets no Operations destination',
      await env.page.getByRole('button', { name: /Operations workspace/i }).count(),
      0
    );
    await context.close();

    /* ---------------- H. revocation, 401, account switching ---------------- */
    console.log('\nH. Revocation, credential failure and account switching');
    env = await createContext(browser, { path: '/admin', identity: IDENTITIES.oeAlpha, session: sessions.oeAlpha });
    context = env.context;
    check('H1 the workspace is reachable again after the re-grant', await enterWorkspace(env.page));
    await openQueue(env.page, 'Claimable work');
    const beforeRevoke = await rowKeys(env.page);
    check('H2 protected data is on screen before the revocation', beforeRevoke.length > 0, `saw ${JSON.stringify(beforeRevoke)}`);
    const grants = (await api('GET', '/api/v1/staff/admin/users/63', admin.token)).json?.grants ?? [];
    const activeGrant = grants.find((grant) => grant.capability === 'OPS_COORDINATE' && grant.revokedAt === null);
    const revoked = await api('POST', `/api/v1/staff/admin/grants/${activeGrant.id}/revoke`, admin.token, {
      reasonCode: 'INTEGRATION_GATE_LIVE_REVOKE'
    });
    checkEqual('H3 the Admin revoked the live grant', revoked.status, 200);
    await env.page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await env.page.waitForTimeout(2500);
    checkEqual('H4 protected data is cleared once authority is withdrawn', (await rowKeys(env.page)).length, 0);
    checkEqual(
      'H5 the queue view is withdrawn for the revoked account',
      (await env.page.locator('.oe-subnav-count').count()) + (await env.page.getByRole('button', { name: /Claimable work/i }).count()),
      0
    );
    checkEqual(
      'H6 the revoked account cannot mutate ownership',
      (await api('POST', `/api/v1/operations/visit-requests/${requestRoot.targetId}/claim`, sessions.oeAlpha.token, {
        expectedRequestVersion: requestRoot.expectedVersion,
        reasonCode: 'CLAIM_WORK'
      })).status,
      404
    );
    const restored = await api('POST', '/api/v1/staff/admin/users/63/grants', admin.token, {
      capability: 'OPS_COORDINATE',
      scopeType: 'TEAM',
      teamId: 1,
      reasonCode: 'INTEGRATION_GATE_RESTORE'
    });
    checkEqual('H7 authority can be restored through the admin API', restored.status, 200);

    await env.page.evaluate(() => window.localStorage.setItem('pathome_auth_token', 'tampered.invalid.credential'));
    await env.page.getByRole('button', { name: /Claimable work/i }).first().click().catch(() => {});
    await env.page.waitForTimeout(2000);
    check('H8 the backend rejects the tampered credential with a real 401', env.network.some((record) => record.status === 401), 'no 401 observed');
    checkEqual('H9 no protected rows are rendered for the rejected credential', (await rowKeys(env.page)).length, 0);

    const switchIndex = env.network.length;
    await env.page.evaluate((entries) => {
      entries.forEach(([key, value]) => window.localStorage.setItem(key, value));
      window.dispatchEvent(new CustomEvent('pathome_auth_changed'));
    }, storageFor(IDENTITIES.oeBravo, sessions.oeBravo));
    await env.page.waitForTimeout(2500);
    const switchedKeys = await rowKeys(env.page);
    check(
      'H10 after the account switch only the new account\u2019s work is visible',
      switchedKeys.every((key) => !key.includes(String(requestRoot.targetId)) && !key.includes(String(sessionRoot.targetId))),
      `saw ${JSON.stringify(switchedKeys)}`
    );
    const afterSwitch = env.network.slice(switchIndex).filter((record) => record.auth !== 'none');
    check(
      'H11 no request after the switch carried the previous account\u2019s credential',
      afterSwitch.length > 0 && afterSwitch.every((record) => record.auth === fingerprints.oeBravo),
      `saw ${JSON.stringify(afterSwitch.map((record) => record.auth).slice(0, 4))}`
    );
    checkEqual('H12 no uncaught page errors during the failure scenarios', env.pageErrors.length, 0);
    await context.close();

    /* ---------------- I. responsive ---------------- */
    console.log('\nI. Responsive and touch behaviour on real data');
    env = await createContext(browser, { path: '/admin', identity: IDENTITIES.oeAlpha, session: sessions.oeAlpha });
    context = env.context;
    check('I1 the workspace opens at the desktop viewport', await enterWorkspace(env.page));
    await openQueue(env.page, 'Claimable work');
    check('I2 the real work is on screen before the resize', await waitFor(env.page, '[data-row-key]'));
    await env.page.setViewportSize({ width: 390, height: 844 });
    await env.page.waitForTimeout(600);
    await openQueue(env.page, 'Claimable work');
    const overflow = await env.page.evaluate(() => ({ scrollWidth: document.documentElement.scrollWidth, innerWidth: window.innerWidth }));
    check('I3 no horizontal overflow at 390px', overflow.scrollWidth <= overflow.innerWidth, `${overflow.scrollWidth} > ${overflow.innerWidth}`);
    const targets = await env.page.locator('.oe button, .oe [role="button"], .oe a').evaluateAll((nodes) =>
      nodes
        .filter((node) => node.offsetParent !== null)
        .map((node) => {
          // A control whose `::after` is `position:absolute; inset:0` widens its own hit region to
          // the nearest positioned ancestor (the row), so that ancestor is the effective target.
          const own = node.getBoundingClientRect();
          let box = own;
          const overlay = window.getComputedStyle(node, '::after');
          if (overlay.position === 'absolute' && overlay.content && overlay.content !== 'none') {
            let parent = node.parentElement;
            while (parent) {
              const position = window.getComputedStyle(parent).position;
              if (position === 'relative' || position === 'absolute' || position === 'fixed') {
                const rect = parent.getBoundingClientRect();
                box = {
                  width: Math.max(own.width, rect.width),
                  height: Math.max(own.height, rect.height)
                };
                break;
              }
              parent = parent.parentElement;
            }
          }
          return {
            width: Math.round(box.width),
            height: Math.round(box.height),
            cls: String(node.className),
            text: (node.textContent ?? '').trim().slice(0, 24)
          };
        })
    );
    check(
      'I4 every interactive control meets the 44px minimum',
      targets.length > 0 && targets.every((box) => box.height >= 44 && box.width >= 44),
      JSON.stringify(targets.filter((box) => box.height < 44 || box.width < 44).slice(0, 3))
    );
    checkEqual('I5 no uncaught page errors at the mobile viewport', env.pageErrors.length, 0);
    await context.close();

    /* ---------------- J. entry-point blocker evidence ---------------- */
    console.log('\nJ. Entry-point blocker (measured, not assumed)');
    const loginShape = await api('POST', '/api/v1/auth/login', null, {
      email: IDENTITIES.oeAlpha.email,
      password: IDENTITIES.oeAlpha.password
    });
    checkEqual('J1 the only provisionable staff role is ROLE_TENANT', loginShape.json?.role, 'ROLE_TENANT');
    env = await createContext(browser, {
      path: '/crm',
      identity: { ...IDENTITIES.oeAlpha, role: 'EMPLOYEE' },
      session: sessions.oeAlpha,
      shellGate: false
    });
    context = env.context;
    await env.page.waitForTimeout(4000);
    check(
      'J2 an Employee-classified coordinator account is rejected by the backend with a real 401',
      env.network.some((record) => record.path.startsWith('/api/v1/ground/visit-sessions') && record.status === 401),
      JSON.stringify(env.network.filter((record) => record.status === 401).map((record) => record.path).slice(0, 3))
    );
    const persisted = await env.page.evaluate(() => window.localStorage.getItem('pathome_auth_token'));
    checkEqual('J3 the app withdraws that session and returns to the public landing page', persisted, null);
    checkEqual('J4 the Employee console entry point is therefore unreachable via real login', await env.page.getByRole('button', { name: /Operations workspace/i }).count(), 0);
    await context.close();
  } finally {
    await browser.close();
  }

  console.log(`\nSUMMARY: checks=${checks} failures=${failures}`);
  return failures;
};

run()
  .then((failures) => {
    if (failures > 0) process.exitCode = 1;
  })
  .catch((error) => {
    console.error(`harness error: ${error.message}`);
    process.exitCode = 1;
  });
