/**
 * Controlled C07 routing terminal-state verification.
 *
 * SIMULATED BACKEND: the real production React bundle, router, capability hook, routing workspace,
 * localStorage and browser events run in Chromium. Every API response is produced by the local stub.
 * This is NOT genuine Chromium + Spring Boot + PostgreSQL acceptance.
 *
 * Usage:
 *   npm --prefix frontend-web run build
 *   node frontend-web/browser-verification/operations-routing-c07/run.mjs
 */
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;
const DIST = fileURLToPath(new URL('../../dist/', import.meta.url));
/**
 * Focused non-disruptive revalidation evidence. The ordinary harness remains the broad controlled
 * campaign; this selector runs only the current C07 scroll/focus/input scenario and its receipts.
 */
const EVIDENCE_ONLY = process.env.OE_ROUTING_C07_EVIDENCE === '1';
const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2'
};

const TOKEN_A = 'controlled-routing-token-a';
const TOKEN_B = 'controlled-routing-token-b';
const TOKEN_C = 'controlled-routing-token-c';
const TOKEN_EMPTY = 'controlled-routing-token-empty';
const TOKEN_TEAM_ONLY = 'controlled-routing-token-team-only';
const TOKEN_MALFORMED = 'controlled-routing-token-malformed';
const TOKEN_ERROR = 'controlled-routing-token-error';
const TOKEN_TIMEOUT = 'controlled-routing-token-timeout';
const TOKEN_FORBIDDEN = 'controlled-routing-token-forbidden';
const TOKEN_UNAUTHORIZED = 'controlled-routing-token-unauthorized';

const grant = (cityId, cityDisplayName = `City ${cityId}`) => ({
  capability: 'OPS_ROUTING',
  scopeType: 'CITY',
  cityId,
  cityDisplayName,
  teamId: null,
  teamDisplayName: null
});
const teamGrant = (cityId, teamId) => ({
  capability: 'OPS_COORDINATE',
  scopeType: 'TEAM',
  cityId,
  cityDisplayName: `City ${cityId}`,
  teamId,
  teamDisplayName: `Team ${teamId}`
});
const capabilityResponse = (...capabilities) => ({
  status: 200,
  body: { staffActive: true, capabilities, serverTime: '2026-10-24T15:00:00Z' }
});
const emptyPage = { items: [], totalCount: 0, page: 0, size: 20, totalPages: 0 };

let checks = 0;
let failures = 0;
const check = (name, condition, detail = '') => {
  checks += 1;
  if (!condition) failures += 1;
  process.stdout.write(`${condition ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}\n`);
};

const stub = {
  capabilities: new Map(),
  holdTokens: new Set(),
  held: [],
  log: [],
  routingTasks: [],
  reset() {
    for (const entry of this.held) {
      if (!entry.response.destroyed) entry.response.destroy();
    }
    this.capabilities.clear();
    this.holdTokens.clear();
    this.held.length = 0;
    this.log.length = 0;
    this.routingTasks.length = 0;
  },
  setCapability(token, answer) {
    this.capabilities.set(token, answer);
  },
  requestCount(token, pathFragment = '') {
    return this.log.filter((entry) =>
      (token === undefined || entry.token === token)
      && entry.path.includes(pathFragment)).length;
  },
  releaseHeld(token, override = undefined) {
    const index = this.held.findIndex((entry) => entry.token === token);
    if (index < 0) return false;
    const [entry] = this.held.splice(index, 1);
    entry.respond(override);
    return true;
  }
};

const capabilityFor = (token) =>
  stub.capabilities.get(token)
  ?? { status: 200, body: { staffActive: true, capabilities: [], serverTime: null } };

const apiResponse = (token, url) => {
  const path = url.pathname;
  if (path === '/api/v1/staff/me/capabilities') return capabilityFor(token);
  if (path === '/api/v1/operations/automatic-routing/tasks') {
    return {
      status: 200,
      body: {
        ...emptyPage,
        items: stub.routingTasks,
        totalCount: stub.routingTasks.length
      }
    };
  }
  if (path === '/api/v1/operations/automatic-routing/tasks/unclaimed') {
    return { status: 200, body: emptyPage };
  }
  if (path === '/api/v1/operations/automatic-routing/coverage/cities') {
    return { status: 200, body: [] };
  }
  if (path === '/api/v1/operations/automatic-routing/coverage') {
    return { status: 200, body: emptyPage };
  }
  if (path === '/api/v1/operations/automatic-routing/coverage/options') {
    const cityId = Number(url.searchParams.get('cityId'));
    return {
      status: 200,
      body: {
        city: { id: cityId, cityId, displayName: `City ${cityId}`, version: 1 },
        localities: [{
          id: cityId * 100 + 1,
          cityId,
          displayName: `Controlled locality ${cityId}`,
          version: 1
        }],
        teams: [{
          id: cityId * 10 + 1,
          cityId,
          displayName: `Controlled team ${cityId}`,
          version: 1
        }]
      }
    };
  }
  if (path === '/api/v1/notifications') return { status: 200, body: [] };
  return { status: 404, body: { status: 404, message: 'not stubbed' } };
};

const server = createServer(async (request, response) => {
  const url = new URL(request.url, 'http://routing-c07.local');
  const path = url.pathname;

  if (path.startsWith('/api/')) {
    const token = (request.headers.authorization || '').replace(/^Bearer\s+/i, '');
    stub.log.push({ method: request.method, path, token });
    const answer = apiResponse(token, url);

    if (path === '/api/v1/staff/me/capabilities' && stub.holdTokens.has(token)) {
      const entry = {
        token,
        response,
        aborted: false,
        respond(override = undefined) {
          if (response.destroyed || response.writableEnded) return;
          const settled = override ?? answer;
          response.writeHead(settled.status, {
            'Content-Type': 'application/json',
            'Cache-Control': 'no-store'
          });
          response.end(JSON.stringify(settled.body));
        }
      };
      response.on('close', () => {
        if (!response.writableEnded) entry.aborted = true;
      });
      stub.held.push(entry);
      return;
    }

    response.writeHead(answer.status, {
      'Content-Type': 'application/json',
      'Cache-Control': 'no-store'
    });
    response.end(JSON.stringify(answer.body));
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

const identity = (token, id) => [
  ['pathome_auth_token', token],
  ['pathome_role', 'EMPLOYEE'],
  ['pathome_user', JSON.stringify({
    id,
    role: 'EMPLOYEE',
    fullName: `Controlled routing user ${id}`,
    email: `routing${id}@controlled.test`
  })]
];

const open = async (browser, storage, viewport = { width: 1280, height: 900 }) => {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage();
  await page.addInitScript((entries) => {
    entries.forEach(([key, value]) => window.localStorage.setItem(key, value));
  }, storage);
  await page.goto(`${BASE}/operations/routing`);
  return { context, page };
};

const switchAccount = (page, entries) => page.evaluate((nextEntries) => {
  ['pathome_auth_token', 'pathome_role', 'pathome_user'].forEach((key) => {
    window.localStorage.removeItem(key);
  });
  nextEntries.forEach(([key, value]) => window.localStorage.setItem(key, value));
  window.dispatchEvent(new Event('pathome_auth_changed'));
}, entries);

const signOut = (page) => page.evaluate(() => {
  ['pathome_auth_token', 'pathome_role', 'pathome_user'].forEach((key) => {
    window.localStorage.removeItem(key);
  });
  window.dispatchEvent(new Event('pathome_auth_changed'));
});

const heading = (page, text) => page.getByRole('heading', { name: text, exact: true });
const checking = (page) => page.getByText('Checking your current City routing access…', { exact: true });
const restricted = (page) => heading(page, 'City routing is unavailable');
const unavailable = (page) => heading(page, 'City routing could not be verified');
const workspace = (page) => heading(page, 'City routing and coverage');
const retry = (page) => page.getByRole('button', { name: 'Retry access check' });
const protectedReads = () =>
  stub.log.filter((entry) => entry.path.startsWith('/api/v1/operations/')).length;
const wait = (page, ms) => page.waitForTimeout(ms);
const interactionContext = (page) => page.evaluate(() => {
  const active = document.activeElement;
  const surface = document.querySelector('.routing-page');
  return {
    pathname: window.location.pathname,
    scrollY: window.scrollY,
    activeId: active?.id ?? '',
    activeTag: active?.tagName ?? '',
    activeText: active?.textContent?.trim() ?? '',
    activeValue: active instanceof HTMLSelectElement || active instanceof HTMLInputElement
      ? active.value
      : null,
    surfaceMarker: surface?.getAttribute('data-c07-surface-marker') ?? null,
    sameObservedSurface: window.__c07Surface === surface,
    navigationEntries: performance.getEntriesByType('navigation').length,
    scrolledContainers: Array.from(document.querySelectorAll('*'))
      .filter((element) => element.scrollTop > 0)
      .map((element) => [String(element.id || element.className), element.scrollTop])
  };
});

const waitForHeld = async (token, expected = 1) => {
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) {
    if (stub.held.filter((entry) => entry.token === token).length >= expected) return;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  throw new Error(`Timed out waiting for held capability request for ${token}`);
};

/**
 * Matrix row 31 and the focused same-account non-disruption checks. A real same-origin Chromium page
 * renders twenty representative routing rows, the existing coverage controls provide a real editable
 * input, and the document is actually scrolled to a measured nonzero offset. Revalidation is held while
 * route, scroll, focused control, input value, DOM identity and navigation-entry count are observed.
 */
const runNonzeroScrollRevalidation = async (browser) => {
  stub.reset();
  stub.setCapability(TOKEN_C, capabilityResponse(grant(7)));
  stub.routingTasks.push(...Array.from({ length: 20 }, (_, index) => ({
    taskId: 900 + index,
    taskVersion: 1,
    state: 'NEEDS_REVIEW',
    reasonCode: 'NO_ACTIVE_TEAM_COVERAGE',
    requestId: 5000 + index,
    requestVersion: 1,
    requestStatus: 'RECEIVED',
    cityId: 7,
    cityDisplayName: 'City 7',
    localityId: 701,
    localityDisplayName: `Representative locality ${index + 1}`,
    listingId: 8000 + index,
    listingTitle: `Representative Rental ${index + 1}`,
    teamId: null,
    coordinatorUserId: null,
    resultTeamId: null,
    resultTeamDisplayName: null,
    resultRequestVersion: null,
    enrolledAt: null,
    updatedAt: '2026-10-24T15:00:00Z',
    initialRoutingEligible: false
  })));
  const { context, page } = await open(
    browser,
    identity(TOKEN_C, 108),
    { width: 390, height: 600 }
  );
  await workspace(page).waitFor({ state: 'visible', timeout: 10000 });
  await page.locator('.routing-row').nth(19).waitFor({ state: 'attached', timeout: 10000 });
  check('routing fixture renders twenty representative rows', await page.locator('.routing-row').count() === 20);

  const coverageTeam = page.locator('#coverage-team');
  const coverageLocality = page.locator('#coverage-locality');
  await coverageTeam.waitFor({ state: 'visible', timeout: 10000 });
  await coverageLocality.selectOption('701');
  await coverageTeam.selectOption('71');
  await coverageTeam.focus();

  const scrollEvidence = await page.evaluate(async () => {
    const root = document.scrollingElement || document.documentElement;
    const candidates = [root, ...Array.from(document.querySelectorAll('*'))]
      .filter((element) => element.scrollHeight > element.clientHeight + 40);
    const scroller = candidates[0];
    if (!scroller) return { available: false };
    const before = scroller.scrollTop;
    const target = Math.min(260, scroller.scrollHeight - scroller.clientHeight);
    const previousBehavior = scroller.style.scrollBehavior;
    scroller.style.scrollBehavior = 'auto';
    scroller.scrollTo({ top: target, behavior: 'instant' });
    await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    const after = scroller.scrollTop;
    scroller.style.scrollBehavior = previousBehavior;
    return {
      available: true,
      kind: scroller === root ? 'document' : scroller.tagName,
      id: scroller.id || null,
      className: scroller === root ? null : String(scroller.className || ''),
      before,
      after,
      max: scroller.scrollHeight - scroller.clientHeight
    };
  });
  check('a real routing document has a measured nonzero scroll offset',
    scrollEvidence.available === true && scrollEvidence.before === 0 && scrollEvidence.after > 0,
    JSON.stringify(scrollEvidence));

  await page.evaluate(() => {
    const surface = document.querySelector('.routing-page');
    if (surface) surface.setAttribute('data-c07-surface-marker', 'same-account-scroll-evidence');
    window.__c07Surface = surface;
    window.__c07Disruption = { checkingFlash: false, surfaceDisconnected: false };
    window.__c07Observer = new MutationObserver(() => {
      if (window.__c07Surface && !window.__c07Surface.isConnected) {
        window.__c07Disruption.surfaceDisconnected = true;
      }
      if (document.body.textContent?.includes('Checking your current City routing access…')) {
        window.__c07Disruption.checkingFlash = true;
      }
    });
    window.__c07Observer.observe(document.body, { childList: true, subtree: true, characterData: true });
  });

  const contextBeforeRevalidation = await interactionContext(page);
  const protectedReadsBeforeRevalidation = protectedReads();
  stub.setCapability(TOKEN_C, capabilityResponse());
  stub.holdTokens.add(TOKEN_C);
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await waitForHeld(TOKEN_C);
  await wait(page, 300);

  const contextDuringRevalidation = await interactionContext(page);
  check('revalidation starts without reverting to the checking screen', await workspace(page).isVisible());
  check(
    'revalidation preserves a measured nonzero scroll offset',
    contextBeforeRevalidation.scrollY > 0 && contextDuringRevalidation.scrollY === contextBeforeRevalidation.scrollY,
    `${JSON.stringify({ scrollEvidence, before: contextBeforeRevalidation, during: contextDuringRevalidation })}`
  );
  check(
    'revalidation preserves route, focused coverage control and its selected input',
    contextDuringRevalidation.pathname === '/operations/routing'
      && contextDuringRevalidation.activeId === 'coverage-team'
      && contextDuringRevalidation.activeTag === 'SELECT'
      && contextDuringRevalidation.activeValue === '71'
      && contextDuringRevalidation.activeId === contextBeforeRevalidation.activeId
      && contextDuringRevalidation.activeValue === contextBeforeRevalidation.activeValue,
    `${JSON.stringify({ before: contextBeforeRevalidation, during: contextDuringRevalidation })}`
  );
  check(
    'revalidation preserves the same mounted routing surface',
    contextBeforeRevalidation.surfaceMarker === 'same-account-scroll-evidence'
      && contextDuringRevalidation.sameObservedSurface === true
  );
  check(
    'revalidation does not reload the document',
    contextDuringRevalidation.navigationEntries === contextBeforeRevalidation.navigationEntries
  );

  await page.evaluate(() => {
    window.dispatchEvent(new Event('focus'));
    window.dispatchEvent(new Event('focus'));
  });
  await wait(page, 100);
  check(
    'duplicate current revalidations share the one held read',
    stub.requestCount(TOKEN_C, '/staff/me/capabilities') === 2,
    `calls=${stub.requestCount(TOKEN_C, '/staff/me/capabilities')}`
  );
  check(
    'no protected read or checking flash occurs during held revalidation',
    protectedReads() === protectedReadsBeforeRevalidation
      && await page.evaluate(() => window.__c07Disruption.checkingFlash === false
        && window.__c07Disruption.surfaceDisconnected === false),
    `protectedReads=${protectedReadsBeforeRevalidation}->${protectedReads()}`
  );

  await page.evaluate(() => window.__c07Observer.disconnect());
  stub.holdTokens.delete(TOKEN_C);
  stub.releaseHeld(TOKEN_C);
  await restricted(page).waitFor({ state: 'visible', timeout: 10000 });
  check('the current denial still reaches the explicit restricted terminal view', await restricted(page).isVisible());
  check(
    'terminal denial does not force a reload',
    (await interactionContext(page)).navigationEntries === contextBeforeRevalidation.navigationEntries
  );
  await context.close();
};

const browser = await chromium.launch({ channel: 'chromium' });

try {
  if (EVIDENCE_ONLY) {
    process.stdout.write('\n=== C07 focused real-scroll revalidation evidence ===\n');
    await runNonzeroScrollRevalidation(browser);
    process.stdout.write('\nSIMULATED BACKEND: focused controlled Chromium evidence only.\n');
    process.stdout.write(`${checks} checks, ${failures} failure(s)\n`);
    await browser.close();
    server.close();
    process.exit(failures === 0 ? 0 : 1);
  }

  process.stdout.write('\n=== pending, granted, restricted and malformed states ===\n');
  {
    stub.reset();
    stub.setCapability(TOKEN_A, capabilityResponse(grant(5)));
    stub.holdTokens.add(TOKEN_A);
    const { context, page } = await open(browser, identity(TOKEN_A, 101));
    await checking(page).waitFor({ state: 'visible', timeout: 10000 });
    await waitForHeld(TOKEN_A);
    check('a pending current check renders checking', await checking(page).isVisible());
    check('a pending check transmits no protected routing read', protectedReads() === 0);

    stub.holdTokens.delete(TOKEN_A);
    stub.releaseHeld(TOKEN_A);
    await workspace(page).waitFor({ state: 'visible', timeout: 10000 });
    check('valid admitted City routing reaches the workspace', await workspace(page).isVisible());
    check(
      'granted protected reads use only the current account credential',
      stub.log
        .filter((entry) => entry.path.startsWith('/api/v1/operations/'))
        .every((entry) => entry.token === TOKEN_A)
    );
    await context.close();
  }
  {
    stub.reset();
    stub.setCapability(TOKEN_EMPTY, capabilityResponse());
    const { context, page } = await open(browser, identity(TOKEN_EMPTY, 102));
    await restricted(page).waitFor({ state: 'visible', timeout: 10000 });
    check('clean-login zero grants reaches explicit restricted, not infinite checking', await restricted(page).isVisible());
    check('zero grants never request protected routing reads', protectedReads() === 0);
    await context.close();
  }
  {
    stub.reset();
    stub.setCapability(TOKEN_TEAM_ONLY, capabilityResponse(teamGrant(5, 9)));
    const { context, page } = await open(browser, identity(TOKEN_TEAM_ONLY, 103));
    await restricted(page).waitFor({ state: 'visible', timeout: 10000 });
    check('Team coordination without City routing remains restricted', await restricted(page).isVisible());
    check('insufficient City authority exposes no protected routing read', protectedReads() === 0);
    await context.close();
  }
  {
    stub.reset();
    stub.setCapability(TOKEN_MALFORMED, {
      status: 200,
      body: { staffActive: true, capabilities: null, serverTime: null }
    });
    const { context, page } = await open(browser, identity(TOKEN_MALFORMED, 104));
    await unavailable(page).waitFor({ state: 'visible', timeout: 10000 });
    check('malformed capability structure reaches unavailable, not fabricated denial', await unavailable(page).isVisible());
    check('malformed structure exposes no protected routing read', protectedReads() === 0);
    const navigationBeforeRetry = await page.evaluate(() => performance.getEntriesByType('navigation').length);
    stub.setCapability(TOKEN_MALFORMED, capabilityResponse(grant(5)));
    await retry(page).click();
    await workspace(page).waitFor({ state: 'visible', timeout: 10000 });
    check('Retry recovers after a malformed current response', await workspace(page).isVisible());
    check(
      'Retry keeps the current route and does not force a document reload',
      new URL(page.url()).pathname === '/operations/routing'
        && await page.evaluate(() => performance.getEntriesByType('navigation').length) === navigationBeforeRetry
    );
    await context.close();
  }
  {
    stub.reset();
    stub.setCapability(TOKEN_ERROR, {
      status: 500,
      body: { status: 500, error: 'Internal Server Error', message: 'private transport detail' }
    });
    const { context, page } = await open(browser, identity(TOKEN_ERROR, 105));
    await unavailable(page).waitFor({ state: 'visible', timeout: 10000 });
    check('current transport failure reaches unavailable with Retry', await retry(page).isVisible());
    check(
      'transport failure never discloses the internal error message',
      !(await page.getByText('private transport detail').isVisible().catch(() => false))
    );
    await context.close();
  }
  {
    stub.reset();
    stub.setCapability(TOKEN_FORBIDDEN, {
      status: 403,
      body: { status: 403, error: 'Forbidden', message: 'private authorization detail' }
    });
    const { context, page } = await open(browser, identity(TOKEN_FORBIDDEN, 106));
    await restricted(page).waitFor({ state: 'visible', timeout: 10000 });
    check('current HTTP 403 reaches restricted', await restricted(page).isVisible());
    check(
      'HTTP 403 never discloses the backend error detail',
      !(await page.getByText('private authorization detail').isVisible().catch(() => false))
    );
    await context.close();
  }

  process.stdout.write('\n=== timeout and revalidation ===\n');
  {
    stub.reset();
    stub.setCapability(TOKEN_TIMEOUT, capabilityResponse(grant(5)));
    stub.holdTokens.add(TOKEN_TIMEOUT);
    const { context, page } = await open(browser, identity(TOKEN_TIMEOUT, 107));
    await waitForHeld(TOKEN_TIMEOUT);
    await unavailable(page).waitFor({ state: 'visible', timeout: 15000 });
    check('a dropped response settles unavailable within the 10-second bound', await unavailable(page).isVisible());
    check('timeout aborts its owned capability transport', stub.held[0]?.aborted === true);
    check('timeout exposes no protected routing read', protectedReads() === 0);
    stub.holdTokens.delete(TOKEN_TIMEOUT);
    await retry(page).click();
    await workspace(page).waitFor({ state: 'visible', timeout: 10000 });
    check('a retry after timeout admits the valid current response', await workspace(page).isVisible());
    stub.releaseHeld(TOKEN_TIMEOUT, capabilityResponse());
    await wait(page, 250);
    check('a late pre-timeout response cannot overwrite a newer successful retry', await workspace(page).isVisible());
    await context.close();
  }
  await runNonzeroScrollRevalidation(browser);

  process.stdout.write('\n=== account transitions and stale-response fences ===\n');
  {
    stub.reset();
    stub.setCapability(TOKEN_A, capabilityResponse(grant(5)));
    stub.setCapability(TOKEN_B, capabilityResponse());
    const { context, page } = await open(browser, identity(TOKEN_A, 201));
    await workspace(page).waitFor({ state: 'visible', timeout: 10000 });
    stub.holdTokens.add(TOKEN_A);
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await waitForHeld(TOKEN_A);
    await switchAccount(page, identity(TOKEN_B, 202));
    await restricted(page).waitFor({ state: 'visible', timeout: 10000 });
    check('A granted to B restricted commits B terminal restriction', await restricted(page).isVisible());
    const before = protectedReads();
    stub.releaseHeld(TOKEN_A, capabilityResponse(grant(5)));
    await wait(page, 250);
    check('A success after B commit is ignored', await restricted(page).isVisible());
    check('A late success starts no protected work for B', protectedReads() === before);
    check('A prior capability transport is aborted on transition', stub.held.length === 0 || stub.held[0].aborted);
    await context.close();
  }
  {
    stub.reset();
    stub.setCapability(TOKEN_A, capabilityResponse(grant(5)));
    stub.setCapability(TOKEN_B, capabilityResponse(grant(6, 'City 6')));
    const { context, page } = await open(browser, identity(TOKEN_A, 301));
    await workspace(page).waitFor({ state: 'visible', timeout: 10000 });
    stub.holdTokens.add(TOKEN_A);
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await waitForHeld(TOKEN_A);
    stub.holdTokens.delete(TOKEN_A);
    await switchAccount(page, identity(TOKEN_B, 302));
    await workspace(page).waitFor({ state: 'visible', timeout: 10000 });
    stub.releaseHeld(TOKEN_A, {
      status: 500,
      body: { status: 500, message: 'stale A failure' }
    });
    await wait(page, 250);
    check('A failure after B success cannot mask B grant', await workspace(page).isVisible());

    stub.holdTokens.add(TOKEN_B);
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await waitForHeld(TOKEN_B);
    stub.holdTokens.delete(TOKEN_B);
    await switchAccount(page, identity(TOKEN_A, 303));
    await workspace(page).waitFor({ state: 'visible', timeout: 10000 });
    stub.releaseHeld(TOKEN_B, {
      status: 500,
      body: { status: 500, message: 'stale B failure' }
    });
    await wait(page, 250);
    check('A to B to A settles only the newest generation', await workspace(page).isVisible());
    check(
      'A to B to A never combines protected reads across identities',
      stub.log
        .filter((entry) => entry.path.startsWith('/api/v1/operations/'))
        .every((entry) => entry.token === TOKEN_A || entry.token === TOKEN_B)
    );
    await context.close();
  }
  {
    stub.reset();
    stub.setCapability(TOKEN_A, capabilityResponse());
    stub.setCapability(TOKEN_B, capabilityResponse(grant(6)));
    const { context, page } = await open(browser, identity(TOKEN_A, 401));
    await restricted(page).waitFor({ state: 'visible', timeout: 10000 });
    await switchAccount(page, identity(TOKEN_B, 402));
    await workspace(page).waitFor({ state: 'visible', timeout: 10000 });
    check('A restricted to B granted reaches B workspace', await workspace(page).isVisible());
    await context.close();
  }
  {
    stub.reset();
    stub.setCapability(TOKEN_A, capabilityResponse(grant(5)));
    stub.setCapability(TOKEN_B, capabilityResponse());
    stub.setCapability(TOKEN_C, capabilityResponse(grant(7)));
    stub.holdTokens.add(TOKEN_B);
    const { context, page } = await open(browser, identity(TOKEN_A, 501));
    await workspace(page).waitFor({ state: 'visible', timeout: 10000 });
    await switchAccount(page, identity(TOKEN_B, 502));
    await waitForHeld(TOKEN_B);
    await switchAccount(page, identity(TOKEN_C, 503));
    await workspace(page).waitFor({ state: 'visible', timeout: 10000 });
    stub.releaseHeld(TOKEN_B, capabilityResponse());
    await wait(page, 250);
    check('rapid A to B to C keeps the newest granted account', await workspace(page).isVisible());
    await context.close();
  }

  process.stdout.write('\n=== direct entry, refresh, signed-out and revalidation ===\n');
  {
    stub.reset();
    stub.setCapability(TOKEN_A, capabilityResponse(grant(5)));
    const { context, page } = await open(browser, identity(TOKEN_A, 601));
    await workspace(page).waitFor({ state: 'visible', timeout: 10000 });
    const beforeReload = stub.requestCount(TOKEN_A, '/staff/me/capabilities');
    await page.reload();
    await workspace(page).waitFor({ state: 'visible', timeout: 10000 });
    check(
      'refresh remounts and re-verifies before admitting routing',
      stub.requestCount(TOKEN_A, '/staff/me/capabilities') > beforeReload
    );
    await context.close();
  }
  {
    stub.reset();
    const { context, page } = await open(browser, []);
    await wait(page, 500);
    check('signed-out direct route exposes no routing workspace', !(await workspace(page).isVisible().catch(() => false)));
    check('signed-out direct route transmits no capability or protected read', protectedReads() === 0);
    await context.close();
  }
  {
    stub.reset();
    stub.setCapability(TOKEN_UNAUTHORIZED, {
      status: 401,
      body: { status: 401, error: 'Unauthorized', message: 'private session detail' }
    });
    const { context, page } = await open(browser, identity(TOKEN_UNAUTHORIZED, 602));
    await wait(page, 750);
    check('current HTTP 401 exposes no routing workspace', !(await workspace(page).isVisible().catch(() => false)));
    check(
      'current HTTP 401 never discloses backend session detail',
      !(await page.getByText('private session detail').isVisible().catch(() => false))
    );
    check('current HTTP 401 performs no protected routing read', protectedReads() === 0);
    await context.close();
  }
  {
    stub.reset();
    stub.setCapability(TOKEN_A, capabilityResponse(grant(5)));
    const { context, page } = await open(browser, identity(TOKEN_A, 603));
    await workspace(page).waitFor({ state: 'visible', timeout: 10000 });
    await signOut(page);
    await wait(page, 300);
    check('logout removes stale routing content immediately', !(await workspace(page).isVisible().catch(() => false)));
    await context.close();
  }

  process.stdout.write('\n=== responsive, touch-target and keyboard checks ===\n');
  for (const width of [320, 390, 768, 1280]) {
    stub.reset();
    stub.setCapability(TOKEN_MALFORMED, {
      status: 200,
      body: { staffActive: true, capabilities: null }
    });
    const { context, page } = await open(
      browser,
      identity(TOKEN_MALFORMED, 700 + width),
      { width, height: 900 }
    );
    await unavailable(page).waitFor({ state: 'visible', timeout: 10000 });
    const dimensions = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      innerWidth: window.innerWidth
    }));
    const retryBox = await retry(page).boundingBox();
    await retry(page).focus();
    const focusVisible = await retry(page).evaluate((element) => {
      const style = getComputedStyle(element);
      return document.activeElement === element
        && style.outlineStyle !== 'none'
        && parseFloat(style.outlineWidth) > 0;
    });
    check(
      `${width}px: terminal state has no horizontal overflow`,
      dimensions.scrollWidth <= dimensions.innerWidth,
      `${dimensions.scrollWidth}/${dimensions.innerWidth}`
    );
    check(`${width}px: Retry target meets 44px minimum`, (retryBox?.height ?? 0) >= 44, `height=${retryBox?.height}`);
    check(`${width}px: Retry has a visible keyboard focus indicator`, focusVisible);
    if (width === 320) {
      check('320px: routing status remains visible', await unavailable(page).isVisible());
    }
    await context.close();
  }
} finally {
  await browser.close();
  server.close();
}

process.stdout.write(
  '\nSIMULATED BACKEND: controlled Chromium verification only. It does not prove genuine '
  + 'Chromium + Spring Boot + PostgreSQL authentication or authorization.\n'
);
process.stdout.write(`${checks} checks, ${failures} failure(s)\n`);
process.exit(failures === 0 ? 0 : 1);
