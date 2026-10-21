/**
 * Mounted tenant-availability verification.
 *
 * Runs the real production dashboard from `frontend-web/dist` in Chromium with a controlled
 * tenant-only HTTP surface. The React effects, draft owner, Save/Clear handlers and editor are the
 * production ones; only Spring/PostgreSQL is replaced by the deterministic transport below.
 *
 * Usage: npm --prefix frontend-web run build
 *        node frontend-web/browser-verification/tenant-availability/run.mjs
 */
import { readFile } from 'node:fs/promises';
import { extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;
const DIST = fileURLToPath(new URL('../../dist/', import.meta.url));
const ORIGIN = 'http://tenant.local';
const BUDGET_MS = 9 * 60 * 1000;
const STARTED_AT = Date.now();
const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2'
};

const requestFixture = (overrides = {}) => ({
  requestId: 51,
  version: 3,
  propertyId: 10,
  propertyTitle: 'Garden apartment',
  propertyType: 'FLAT',
  bhk: '2BHK',
  city: 'Indore',
  sector: 'Vijay Nagar',
  propertyAvailable: true,
  coverImageUrl: null,
  preferredVisitTiming: 'Saturday afternoon',
  status: 'RECEIVED',
  requestedAt: '2026-10-18T09:00:00',
  availabilityStartAt: '2026-11-21T10:00:00+05:30',
  availabilityEndAt: '2026-11-21T13:00:00+05:30',
  availabilityZoneId: 'Asia/Kolkata',
  preferredAt: null,
  ...overrides
});

const secondRequest = (overrides = {}) => requestFixture({
  requestId: 52,
  propertyId: 11,
  propertyTitle: 'Lakeview home',
  ...overrides
});

const state = {
  userId: 7,
  requests: [requestFixture(), secondRequest()],
  listed: 0,
  listFailureRemaining: 0,
  holdNextList: false,
  heldList: null,
  puts: [],
  listTokens: [],
  putMode: 'ok',
  heldPut: null,
  reset() {
    this.userId = 7;
    this.requests = [requestFixture(), secondRequest()];
    this.listed = 0;
    this.listFailureRemaining = 0;
    this.holdNextList = false;
    this.heldList = null;
    this.puts = [];
    this.listTokens = [];
    this.putMode = 'ok';
    this.heldPut = null;
  },
  request(id) {
    return this.requests.find(item => item.requestId === id);
  },
  commit(id, body) {
    this.requests = this.requests.map(item => item.requestId === id
      ? {
          ...item,
          version: item.version + 1,
          availabilityStartAt: body.availabilityStartAt,
          availabilityEndAt: body.availabilityEndAt,
          availabilityZoneId: body.availabilityZoneId,
          preferredAt: body.preferredAt
        }
      : item);
    return this.request(id);
  }
};

const results = [];
let checks = 0;
let failures = 0;
let notTested = 0;
const check = (label, condition, detail) => {
  checks += 1;
  if (condition) results.push(`  ✔ ${label}`);
  else {
    failures += 1;
    results.push(`  ✖ ${label}${detail === undefined ? '' : ` — ${detail}`}`);
  }
};
const checkEqual = (label, actual, expected) =>
  check(label, Object.is(actual, expected), `expected ${JSON.stringify(expected)}, saw ${JSON.stringify(actual)}`);
const budgetLeft = () => BUDGET_MS - (Date.now() - STARTED_AT);

const credentials = (userId, token) => [
  ['pathome_auth_token', token],
  ['pathome_role', 'TENANT'],
  ['pathome_user', JSON.stringify({ id: userId, role: 'TENANT', fullName: `Tenant ${userId}`, email: `tenant${userId}@example.test` })]
];

const serveStatic = async (route, url) => {
  const pathname = url.pathname === '/' ? '/index.html' : url.pathname;
  try {
    const body = await readFile(join(DIST, pathname));
    await route.fulfill({
      status: 200,
      contentType: CONTENT_TYPES[extname(pathname)] ?? 'application/octet-stream',
      body
    });
  } catch {
    await route.fulfill({
      status: 200,
      contentType: 'text/html; charset=utf-8',
      body: await readFile(join(DIST, 'index.html'))
    });
  }
};

const json = (route, status, body) => route.fulfill({
  status,
  contentType: 'application/json',
  body: JSON.stringify(body)
});

const pageResponse = (page) => ({
  userId: state.userId,
  requests: state.requests,
  totalCount: state.requests.length,
  page,
  hasMore: false
});

const handleList = async (route, url) => {
  state.listed += 1;
  state.listTokens.push(route.request().headers()['authorization'] ?? '');
  if (state.listFailureRemaining > 0) {
    state.listFailureRemaining -= 1;
    await json(route, 503, { status: 503, message: 'Controlled read failure' });
    return;
  }
  if (state.holdNextList) {
    state.holdNextList = false;
    await new Promise(resolve => { state.heldList = { route, page: Number(url.searchParams.get('page') ?? 0), resolve }; });
    return;
  }
  await json(route, 200, pageResponse(Number(url.searchParams.get('page') ?? 0)));
};

const handleApi = async (route, request, url) => {
  const path = url.pathname;
  if (path === '/api/v1/tenant/visit-requests') {
    await handleList(route, url);
    return;
  }
  const update = /^\/api\/v1\/tenant\/visit-requests\/(\d+)\/availability$/.exec(path);
  if (update && request.method() === 'PUT') {
    const requestId = Number(update[1]);
    const body = JSON.parse(request.postData() ?? '{}');
    state.puts.push({ requestId, body, token: request.headers()['authorization'] ?? '' });
    if (state.putMode === 'hold') {
      await new Promise(resolve => { state.heldPut = { route, requestId, body, resolve }; });
      return;
    }
    if (state.putMode === 'commit-abort') {
      const committed = state.commit(requestId, body);
      await route.abort('failed');
      return committed;
    }
    if (state.putMode === 'abort') {
      await route.abort('failed');
      return;
    }
    const committed = state.commit(requestId, body);
    await json(route, 200, {
      requestId,
      version: committed.version,
      availabilityStartAt: committed.availabilityStartAt,
      availabilityEndAt: committed.availabilityEndAt,
      availabilityZoneId: committed.availabilityZoneId,
      preferredAt: committed.preferredAt
    });
    return;
  }
  if (path === '/api/v1/tenant/visit-entitlement') {
    await json(route, 200, {
      totalGrantedSessions: 3,
      remainingSessions: 3,
      reservedSessions: 0,
      availableSessions: 3,
      totalReconciliationRequired: false
    });
    return;
  }
  if (path === '/api/v1/tenant/visit-sessions') {
    await json(route, 200, { sessions: [], page: 0, size: 20, totalPages: 0 });
    return;
  }
  if (path === '/api/v1/tenant/visit-sessions/outcomes') {
    await json(route, 200, { sessions: [], page: 0, size: 20, totalPages: 0, totalElements: 0 });
    return;
  }
  if (path === '/api/v1/notifications') {
    await json(route, 200, []);
    return;
  }
  if (path === '/api/v1/properties') {
    await json(route, 200, { properties: [], hasMore: false, page: 0 });
    return;
  }
  if (path.startsWith('/api/')) {
    await json(route, 200, {});
    return;
  }
  await serveStatic(route, url);
};

const newPage = async (browser, { viewport = { width: 1280, height: 900 }, userId = 7, token = 'tenant-token-a' } = {}) => {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage();
  await page.addInitScript((entries) => {
    entries.forEach(([key, value]) => window.localStorage.setItem(key, value));
  }, credentials(userId, token));
  page.on('pageerror', error => results.push(`  ! page error: ${error.message}`));
  page.on('console', message => {
    if (message.type() === 'error'
        && !message.text().includes('status of 503')
        && !message.text().includes('ERR_FAILED')) {
      results.push(`  ! console error: ${message.text().slice(0, 180)}`);
    }
  });
  await page.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.origin !== ORIGIN) {
      await route.continue();
      return;
    }
    if (url.pathname.startsWith('/api/')) await handleApi(route, route.request(), url);
    else await serveStatic(route, url);
  });
  return { context, page };
};

const openVisits = async (page) => {
  await page.goto(`${ORIGIN}/tenant#visit-history`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: /A look around/ }).waitFor({ state: 'visible', timeout: 15000 });
  await page.getByRole('button', { name: 'Update available times' }).first().waitFor({ state: 'visible', timeout: 10000 });
};

const cardFor = (page, title) => page.locator('.tenant-v0-request-card').filter({ hasText: title }).first();
const openEditor = async (page, title = 'Garden apartment') => {
  await cardFor(page, title).getByRole('button', { name: 'Update available times' }).click();
  return cardFor(page, title).locator('form');
};
const refreshHistory = async (page) => {
  await page.evaluate(() => window.dispatchEvent(new Event('pathome_visit_request_created')));
  await page.waitForTimeout(250);
};
const waitFor = async (page, predicate, timeout = 8000) => {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await predicate()) return true;
    await page.waitForTimeout(100);
  }
  return false;
};

/**
 * E1 residual: a successful acknowledgement computed from v3 must not clear a review latch that was
 * set by v5 while the response was held. The production dashboard, list effect, save callback and
 * editor all run here; only the HTTP surface is controlled.
 */
const e1LateAcknowledgement = async (browser) => {
  state.reset();
  const mounted = await newPage(browser);
  await openVisits(mounted.page);
  let editor = await openEditor(mounted.page);
  await editor.getByLabel('Start time').fill('11:00');
  state.putMode = 'hold';
  await editor.getByRole('button', { name: 'Save available times' }).click();
  check('E1-late the original Save is actually held in flight',
    await waitFor(mounted.page, () => state.heldPut !== null));
  const held = state.heldPut;
  checkEqual('E1-late the held command was built from reviewed v3',
    held?.body?.expectedRequestVersion, 3);

  state.requests = [
    requestFixture({
      version: 5,
      availabilityStartAt: '2026-11-21T12:00:00+05:30',
      availabilityEndAt: '2026-11-21T15:00:00+05:30'
    }),
    secondRequest()
  ];
  await refreshHistory(mounted.page);
  await mounted.page.getByText(/This visit changed while you were editing/).waitFor({ state: 'visible', timeout: 8000 });

  state.putMode = 'ok';
  state.heldPut = null;
  await json(held.route, 200, {
    requestId: held.requestId,
    version: 4,
    availabilityStartAt: held.body.availabilityStartAt,
    availabilityEndAt: held.body.availabilityEndAt,
    availabilityZoneId: held.body.availabilityZoneId,
    preferredAt: held.body.preferredAt
  });
  await waitFor(mounted.page, () => state.heldPut === null && state.puts.length === 1);
  await mounted.page.waitForTimeout(350);

  editor = cardFor(mounted.page, 'Garden apartment').locator('form');
  check('E1-late the late v4 acknowledgement keeps the v5 review latch',
    await mounted.page.getByText(/earlier save was accepted/i).isVisible()
    && await mounted.page.getByText(/newer saved times/i).isVisible());
  checkEqual('E1-late the dirty form text is preserved', await editor.getByLabel('Start time').inputValue(), '11:00');
  check('E1-late Save stays blocked until explicit rebase',
    await editor.getByRole('button', { name: 'Save available times' }).isDisabled());
  check('E1-late Clear stays blocked until explicit rebase',
    await editor.getByRole('button', { name: 'Clear saved times' }).isDisabled());
  checkEqual('E1-late no Save or Clear PUT escaped the review gate', state.puts.length, 1);
  check('E1-late the current saved v5 window is still presented',
    (await mounted.page.getByText(/12:00 pm/).count()) > 0
    && (await mounted.page.getByText(/3:00 pm/).count()) > 0);

  await editor.getByRole('button', { name: 'Use saved times as my starting point' }).click();
  checkEqual('E1-late explicit rebase adopts v5', await editor.getByLabel('Start time').inputValue(), '12:00');
  check('E1-late explicit rebase re-enables Save',
    !(await editor.getByRole('button', { name: 'Save available times' }).isDisabled()));
  await editor.getByRole('button', { name: 'Save available times' }).click();
  await waitFor(mounted.page, () => state.puts.length === 2);
  checkEqual('E1-late the post-rebase Save submits v5', state.puts[1]?.body?.expectedRequestVersion, 5);
  await mounted.context.close();
};

const lateRecoveryReads = async (browser) => {
  for (const transition of ['account', 'rotation', 'discard-reopen', 'remount', 'navigation', 'multi-tab']) {
    state.reset();
    const mounted = await newPage(browser);
    await openVisits(mounted.page);
    let editor = await openEditor(mounted.page);
    // This controlled transport really ignores AbortSignal for history reads. Production guards
    // must reject the answer even when browser cancellation cannot settle the fetch for them.
    await mounted.page.evaluate(() => {
      const original = window.fetch.bind(window);
      window.fetch = (input, init = {}) => {
        const url = new URL(typeof input === 'string' ? input : input.url, location.href);
        return original(input, url.pathname === '/api/v1/tenant/visit-requests'
          ? { ...init, signal: undefined } : init);
      };
    });
    state.putMode = 'abort';
    state.listFailureRemaining = 1;
    await editor.getByLabel('Start time').fill('11:00');
    await editor.getByRole('button', { name: 'Save available times' }).click();
    await mounted.page.getByText(/latest saved times could not be read/i).waitFor({ state: 'visible', timeout: 8000 });
    state.holdNextList = true;
    await editor.getByRole('button', { name: 'Recheck saved times' }).click();
    await waitFor(mounted.page, () => state.heldList !== null);
    const held = state.heldList;
    const oldPayload = { ...pageResponse(held.page), requests: [requestFixture({
      version: 99, availabilityStartAt: '2026-11-21T18:00:00+05:30', availabilityEndAt: '2026-11-21T20:00:00+05:30'
    })] };
    state.heldList = null;
    let tokenIndex = state.listTokens.length;
    check(`E7 late GET ${transition}: the held read used A’s original credential`, state.listTokens.at(-1) === 'Bearer tenant-token-a');
    if (transition === 'multi-tab') {
      const other = await mounted.context.newPage();
      await other.route('**/*', async route => {
        const url = new URL(route.request().url());
        if (url.origin !== ORIGIN) return route.abort('blockedbyclient');
        if (url.pathname === '/storage-event-driver.html') return route.fulfill({
          status: 200, contentType: 'text/html', body: '<!doctype html><title>Controlled storage event driver</title>'
        });
        if (url.pathname.startsWith('/api/')) await handleApi(route, route.request(), url);
        else await serveStatic(route, url);
      });
      await other.goto(`${ORIGIN}/storage-event-driver.html`, { waitUntil: 'domcontentloaded' });
      tokenIndex = state.listTokens.length;
      state.userId = 8;
      // Browser-generated storage events, rather than a synthetic event in the held tab.
      await other.evaluate(entries => entries.forEach(([key, value]) => localStorage.setItem(key, value)), credentials(8, 'tenant-token-b'));
      await mounted.page.waitForTimeout(300);
      await refreshHistory(mounted.page);
      await other.close();
    } else if (transition === 'account' || transition === 'rotation') {
      const userId = transition === 'account' ? 8 : 7;
      const token = transition === 'account' ? 'tenant-token-b' : 'tenant-token-rotated';
      state.userId = userId;
      await mounted.page.evaluate(entries => {
        entries.forEach(([key, value]) => localStorage.setItem(key, value));
        window.dispatchEvent(new CustomEvent('pathome_auth_changed'));
        window.dispatchEvent(new StorageEvent('storage', { key: 'pathome_user' }));
      }, credentials(userId, token));
      await refreshHistory(mounted.page);
    } else if (transition === 'remount') {
      await mounted.page.reload({ waitUntil: 'domcontentloaded' });
    } else if (transition === 'navigation') {
      await mounted.page.goto(`${ORIGIN}/tenant#account`, { waitUntil: 'domcontentloaded' });
      await mounted.page.goBack({ waitUntil: 'domcontentloaded' });
      await mounted.page.goForward({ waitUntil: 'domcontentloaded' });
      await mounted.page.goBack({ waitUntil: 'domcontentloaded' });
    } else {
      await cardFor(mounted.page, 'Lakeview home').getByRole('button', { name: 'Update available times' }).click();
      await mounted.page.getByRole('button', { name: 'Discard earlier edits and edit this visit' }).click();
      await cardFor(mounted.page, 'Lakeview home').getByRole('button', { name: 'Discard editing' }).click();
    }
    if (transition === 'navigation') {
      // Hash navigation retains this mounted editor and its legitimate operation. It must retain
      // the draft and review a changed authoritative answer, rather than pretending it remounted.
      await json(held.route, 200, oldPayload).catch(() => undefined);
      held.resolve();
      editor = cardFor(mounted.page, 'Garden apartment').locator('form');
      await editor.getByRole('button', { name: 'Use saved times as my starting point' }).waitFor({ state: 'visible', timeout: 8000 });
      checkEqual('E7 history navigation keeps the original unsaved draft', await editor.getByLabel('Start time').inputValue(), '11:00');
      check('E7 history navigation reviews the newer saved facts before another write',
        await editor.getByRole('button', { name: 'Save available times' }).isDisabled()
        && await editor.getByRole('button', { name: 'Clear saved times' }).isDisabled());
      check('E7 history navigation retains credential ownership',
        state.listTokens.slice(tokenIndex).every(token => token === 'Bearer tenant-token-a'));
      checkEqual('E7 back/forward navigation never automatically resends the PUT', state.puts.length, 1);
      await mounted.context.close();
      continue;
    }
    await mounted.page.getByRole('button', { name: 'Update available times' }).first().waitFor({ state: 'visible', timeout: 10000 });
    editor = await openEditor(mounted.page);
    await editor.getByLabel('Start time').fill('12:00');
    await json(held.route, 200, oldPayload).catch(() => undefined);
    held.resolve();
    await mounted.page.waitForTimeout(250);
    checkEqual(`E7 late GET ${transition}: old facts cannot replace the newer editor generation`,
      await editor.getByLabel('Start time').inputValue(), '12:00');
    check(`E7 late GET ${transition}: the old finalizer neither latches nor unlocks the new draft incorrectly`,
      !(await editor.getByRole('button', { name: 'Save available times' }).isDisabled())
      && (await mounted.page.getByText(/saved times differ|newer saved times arrived/).count()) === 0);
    const expectedToken = transition === 'account' || transition === 'multi-tab' ? 'Bearer tenant-token-b'
      : transition === 'rotation' ? 'Bearer tenant-token-rotated' : 'Bearer tenant-token-a';
    check(`E7 late GET ${transition}: subsequent reads use only the current credential`,
      state.listTokens.slice(tokenIndex).every(token => token === expectedToken),
      JSON.stringify({ tokenIndex, tokens: state.listTokens.slice(tokenIndex) }));
    checkEqual(`E7 late GET ${transition}: read recovery never resends the PUT`, state.puts.length, 1);
    await mounted.context.close();
  }
};

const zoneReviewMounted = async (browser) => {
  // Z01: a zone-only edit is real unsaved intent when newer history arrives.
  state.reset();
  let mounted = await newPage(browser);
  await openVisits(mounted.page);
  let editor = await openEditor(mounted.page, 'Garden apartment');
  await editor.getByLabel('Visit timezone').selectOption('Asia/Kathmandu');
  checkEqual('Z01 zone-only edit is visibly Kathmandu', await editor.getByLabel('Visit timezone').inputValue(), 'Asia/Kathmandu');
  checkEqual('Z01 untouched start is presented in Kathmandu', await editor.getByLabel('Start time').inputValue(), '10:15');
  checkEqual('Z01 untouched end is presented in Kathmandu', await editor.getByLabel('End time').inputValue(), '13:15');
  checkEqual('Z01 no write is sent for an unsaved zone choice', state.puts.length, 0);

  state.requests = [
    requestFixture({
      version: 4,
      availabilityStartAt: '2026-11-21T12:00:00+05:30',
      availabilityEndAt: '2026-11-21T15:00:00+05:30',
      availabilityZoneId: 'Asia/Kolkata'
    }),
    secondRequest()
  ];
  await refreshHistory(mounted.page);
  await mounted.page.getByText(/This visit changed while you were editing/).waitFor({ state: 'visible', timeout: 8000 });
  editor = cardFor(mounted.page, 'Garden apartment').locator('form');
  check('Z01 newer v4 history latches review for the zone-only edit',
    await mounted.page.getByText(/This visit changed while you were editing/).isVisible());
  checkEqual('Z01 unsaved zone survives the newest saved facts',
    await editor.getByLabel('Visit timezone').inputValue(), 'Asia/Kathmandu');
  checkEqual('Z01 unsaved Kathmandu start survives', await editor.getByLabel('Start time').inputValue(), '10:15');
  check('Z01 Save and Clear remain disabled before explicit review',
    await editor.getByRole('button', { name: 'Save available times' }).isDisabled()
    && await editor.getByRole('button', { name: 'Clear saved times' }).isDisabled());
  checkEqual('Z01 observation sends no Save or Clear', state.puts.length, 0);
  check('Z01 current saved v4 facts remain separately visible',
    (await mounted.page.getByText(/12:00 pm/).count()) > 0
    && (await mounted.page.getByText(/3:00 pm/).count()) > 0);

  const rebase = editor.getByRole('button', { name: 'Use saved times as my starting point' });
  await rebase.focus();
  await mounted.page.keyboard.press('Enter');
  await waitFor(mounted.page, async () =>
    !(await mounted.page.getByText(/This visit changed while you were editing/).isVisible().catch(() => false)));
  checkEqual('Z01 explicit rebase adopts the authoritative zone', await editor.getByLabel('Visit timezone').inputValue(), 'Asia/Kolkata');
  checkEqual('Z01 explicit rebase adopts the v4 saved start', await editor.getByLabel('Start time').inputValue(), '12:00');
  check('Z01 rebase re-enables the reviewed Save', !(await editor.getByRole('button', { name: 'Save available times' }).isDisabled()));
  await editor.getByRole('button', { name: 'Save available times' }).click();
  await waitFor(mounted.page, () => state.puts.length === 1);
  checkEqual('Z01 post-review Save submits v4, not the pre-review baseline', state.puts[0]?.body?.expectedRequestVersion, 4);
  checkEqual('Z01 post-review Save sends the reviewed zone', state.puts[0]?.body?.availabilityZoneId, 'Asia/Kolkata');
  checkEqual('Z01 review boundary never repeats a hidden PUT', state.puts.length, 1);
  await mounted.context.close();
};

const zoneSwitchMounted = async (browser) => {
  // Z03: closing a dirty zone-only draft still protects Keep/Discard ownership.
  state.reset();
  const mounted = await newPage(browser);
  await openVisits(mounted.page);
  let editor = await openEditor(mounted.page, 'Garden apartment');
  await editor.getByLabel('Visit timezone').selectOption('Asia/Kathmandu');
  await cardFor(mounted.page, 'Garden apartment').getByRole('button', { name: 'Close editor' }).click();
  await cardFor(mounted.page, 'Lakeview home').getByRole('button', { name: 'Update available times' }).click();
  const protectedVisible = await mounted.page.getByText(/Keep your earlier availability edits/)
    .waitFor({ state: 'visible', timeout: 8000 }).then(() => true).catch(() => false);
  check('Z03 a closed zone-only A draft blocks silent replacement by B', protectedVisible);
  if (!protectedVisible) {
    await mounted.context.close();
    return;
  }
  checkEqual('Z03 B is not opened behind the protection notice',
    await cardFor(mounted.page, 'Lakeview home').locator('form').count(), 0);

  const keep = mounted.page.getByRole('button', { name: 'Keep my earlier edits' });
  await keep.focus();
  await mounted.page.keyboard.press('Enter');
  checkEqual('Z03 Keep closes the switch prompt without opening B',
    await cardFor(mounted.page, 'Lakeview home').locator('form').count(), 0);
  await cardFor(mounted.page, 'Garden apartment').getByRole('button', { name: 'Update available times' }).click();
  editor = cardFor(mounted.page, 'Garden apartment').locator('form');
  checkEqual('Z03 Keep retains A and its Kathmandu intent',
    await editor.getByLabel('Visit timezone').inputValue(), 'Asia/Kathmandu');
  await cardFor(mounted.page, 'Garden apartment').getByRole('button', { name: 'Close editor' }).click();

  await cardFor(mounted.page, 'Lakeview home').getByRole('button', { name: 'Update available times' }).click();
  const discard = mounted.page.getByRole('button', { name: 'Discard earlier edits and edit this visit' });
  await discard.focus();
  await mounted.page.keyboard.press('Enter');
  const bEditor = cardFor(mounted.page, 'Lakeview home').locator('form');
  checkEqual('Z03 explicit Discard opens B', await bEditor.getByLabel('Visit timezone').inputValue(), 'Asia/Kolkata');
  checkEqual('Z03 explicit Discard leaves exactly one replacement form',
    await mounted.page.locator('.tenant-v0-request-card form').count(), 1);
  checkEqual('Z03 the deliberate switch sends no availability write', state.puts.length, 0);
  await mounted.context.close();
};

const zoneRecoveryMounted = async (browser) => {
  // Z05: after an uncertain PUT and failed read, a zone edit remains distinct from that attempt.
  state.reset();
  state.putMode = 'abort';
  const mounted = await newPage(browser);
  await openVisits(mounted.page);
  let editor = await openEditor(mounted.page, 'Garden apartment');
  await editor.getByLabel('Visit timezone').selectOption('Asia/Kathmandu');
  state.listFailureRemaining = 1;
  await editor.getByRole('button', { name: 'Save available times' }).click();
  await mounted.page.getByText(/latest saved times could not be read/i).waitFor({ state: 'visible', timeout: 10000 });
  checkEqual('Z05 the uncertain attempt is transmitted exactly once', state.puts.length, 1);
  check('Z05 the failed read keeps Save disabled and exposes an explicit Check',
    await editor.getByRole('button', { name: 'Save available times' }).isDisabled()
    && await mounted.page.getByRole('button', { name: 'Recheck saved times' }).isVisible());
  check('Z05 the failed read unlocks the zone selector only after reconciliation stops',
    !(await editor.getByLabel('Visit timezone').isDisabled()));

  await editor.getByLabel('Visit timezone').selectOption('Asia/Dubai');
  checkEqual('Z05 the newer zone intent is retained after the failed read',
    await editor.getByLabel('Visit timezone').inputValue(), 'Asia/Dubai');
  checkEqual('Z05 the untouched start is re-expressed only in display',
    await editor.getByLabel('Start time').inputValue(), '08:30');
  const beforeCheck = state.listed;
  await mounted.page.getByRole('button', { name: 'Recheck saved times' }).click();
  await waitFor(mounted.page, () => state.listed > beforeCheck);
  await mounted.page.getByText(/saved times differ from your earlier request/i).waitFor({ state: 'visible', timeout: 8000 });
  checkEqual('Z05 the explicit Check starts exactly one guarded read', state.listed - beforeCheck, 1);
  checkEqual('Z05 the read never auto-repeats the uncertain PUT', state.puts.length, 1);
  checkEqual('Z05 the newer Dubai intent survives the mismatching read',
    await editor.getByLabel('Visit timezone').inputValue(), 'Asia/Dubai');
  check('Z05 the review latch remains until an explicit boundary',
    await editor.getByRole('button', { name: 'Save available times' }).isDisabled()
    && await editor.getByRole('button', { name: 'Clear saved times' }).isDisabled());
  await editor.getByRole('button', { name: 'Use saved times as my starting point' }).click();
  checkEqual('Z05 explicit rebase discards the newer zone intent', await editor.getByLabel('Visit timezone').inputValue(), 'Asia/Kolkata');
  checkEqual('Z05 rebase adds no hidden PUT', state.puts.length, 1);
  await mounted.context.close();
};

const zoneIntentMounted = async (browser) => {
  await zoneReviewMounted(browser);
  await zoneSwitchMounted(browser);
  await zoneRecoveryMounted(browser);
};

/**
 * C01-C04/C10: a Clear commits all four fields as null but its acknowledgement is lost, the
 * guarded read fails, the tenant chooses a newer zone, and an explicit Check then returns the
 * matching all-null v4. The editor must show blank authoritative fields in the retained zone,
 * preserve only genuine newer edits, never resend the Clear, and keep keyboard recovery usable.
 */
const clearNullRecoveryMounted = async (browser) => {
  state.reset();
  state.putMode = 'commit-abort';
  const mounted = await newPage(browser);
  await openVisits(mounted.page);
  let editor = await openEditor(mounted.page);
  state.listFailureRemaining = 1;

  const clearButton = editor.getByRole('button', { name: 'Clear saved times' });
  await clearButton.focus();
  await mounted.page.keyboard.press('Enter');
  const confirmClear = editor.getByRole('button', { name: 'Yes, clear times' });
  await confirmClear.focus();
  await mounted.page.keyboard.press('Enter');
  await mounted.page.getByText(/latest saved times could not be read/i).waitFor({ state: 'visible', timeout: 10000 });
  checkEqual('C02 the Clear is transmitted exactly once', state.puts.length, 1);
  check('C02 the Clear carried four authoritative nulls',
    state.puts[0]?.body?.availabilityStartAt === null
    && state.puts[0]?.body?.availabilityEndAt === null
    && state.puts[0]?.body?.availabilityZoneId === null
    && state.puts[0]?.body?.preferredAt === null);
  checkEqual('C02 the dropped acknowledgement still committed v4', state.request(51)?.version, 4);
  check('C02 the failed read keeps Save blocked and offers an explicit Check',
    await editor.getByRole('button', { name: 'Save available times' }).isDisabled()
    && await mounted.page.getByRole('button', { name: 'Recheck saved times' }).isVisible());

  await editor.getByLabel('Visit timezone').selectOption('Asia/Dubai');
  checkEqual('C02 the newer Dubai zone survives the failed read',
    await editor.getByLabel('Visit timezone').inputValue(), 'Asia/Dubai');
  checkEqual('C02 the pre-Check display still relabels the v3 opening',
    await editor.getByLabel('Start time').inputValue(), '08:30');

  const beforeCheck = state.listed;
  const recheck = mounted.page.getByRole('button', { name: 'Recheck saved times' });
  await recheck.focus();
  await mounted.page.keyboard.press('Enter');
  await mounted.page.getByText(/Current saved times match your earlier request/i)
    .waitFor({ state: 'visible', timeout: 10000 });
  checkEqual('C02 the keyboard Check starts exactly one guarded read', state.listed - beforeCheck, 1);
  checkEqual('C02 the retained zone remains Dubai after the matching read',
    await editor.getByLabel('Visit timezone').inputValue(), 'Asia/Dubai');
  checkEqual('C02 the untouched start time is blank, not the obsolete 08:30',
    await editor.getByLabel('Start time').inputValue(), '');
  checkEqual('C02 the untouched start date is blank',
    await editor.getByLabel('Start date').inputValue(), '');
  checkEqual('C02 the untouched end time is blank, not the obsolete 11:30',
    await editor.getByLabel('End time').inputValue(), '');
  checkEqual('C02 the untouched end date is blank',
    await editor.getByLabel('End date').inputValue(), '');
  checkEqual('C02 the untouched preferred time is blank',
    await editor.getByLabel('Preferred time').inputValue(), '');
  check('C02 the card truthfully states no structured availability is saved',
    await cardFor(mounted.page, 'Garden apartment').getByText(/No structured availability is saved yet/).isVisible());
  check('C02 the card shows no obsolete 10:00–13:00 window',
    (await cardFor(mounted.page, 'Garden apartment').getByText(/10:00 am–1:00 pm/).count()) === 0);
  checkEqual('C02 the matching read never resends the uncertain Clear', state.puts.length, 1);

  await editor.getByRole('button', { name: 'Save available times' }).click();
  await mounted.page.getByText(/Choose both dates, a start time and an end time/i)
    .waitFor({ state: 'visible', timeout: 5000 });
  checkEqual('C02 an empty Save stays local and never retries the Clear', state.puts.length, 1);
  await mounted.context.close();

  // C03 mounted control: genuine newer start and end edits made while the Clear is unresolved
  // survive the matching all-null Check; untouched fields stay blank and only those two edits write.
  state.reset();
  state.putMode = 'commit-abort';
  const editedMounted = await newPage(browser);
  await openVisits(editedMounted.page);
  let editedEditor = await openEditor(editedMounted.page);
  state.listFailureRemaining = 1;
  await editedEditor.getByRole('button', { name: 'Clear saved times' }).click();
  await editedEditor.getByRole('button', { name: 'Yes, clear times' }).click();
  await editedMounted.page.getByText(/latest saved times could not be read/i)
    .waitFor({ state: 'visible', timeout: 10000 });
  checkEqual('C03 the second Clear is transmitted exactly once', state.puts.length, 1);
  await editedEditor.getByLabel('Visit timezone').selectOption('Asia/Dubai');
  await editedEditor.getByLabel('Start time').fill('09:30');
  await editedEditor.getByLabel('End time').fill('12:00');
  checkEqual('C03 the genuine edits are not written before the explicit Check', state.puts.length, 1);
  const editedCheck = editedMounted.page.getByRole('button', { name: 'Recheck saved times' });
  await editedCheck.focus();
  await editedMounted.page.keyboard.press('Enter');
  await editedMounted.page.getByText(/Current saved times match your earlier request/i)
    .waitFor({ state: 'visible', timeout: 10000 });
  checkEqual('C03 the genuine start survives the matching all-null read',
    await editedEditor.getByLabel('Start time').inputValue(), '09:30');
  checkEqual('C03 the genuine end survives the matching all-null read',
    await editedEditor.getByLabel('End time').inputValue(), '12:00');
  checkEqual('C03 the untouched preferred time stays blank',
    await editedEditor.getByLabel('Preferred time').inputValue(), '');
  checkEqual('C03 the retained zone survives the matching read',
    await editedEditor.getByLabel('Visit timezone').inputValue(), 'Asia/Dubai');
  state.putMode = 'ok';
  await editedEditor.getByRole('button', { name: 'Save available times' }).click();
  await waitFor(editedMounted.page, () => state.puts.length === 2);
  checkEqual('C03 a complete genuine edit saves against the reviewed v4',
    state.puts[1]?.body?.expectedRequestVersion, 4);
  checkEqual('C03 the genuine edit saves in the retained zone',
    state.puts[1]?.body?.availabilityZoneId, 'Asia/Dubai');
  checkEqual('C03 the genuine start survives as typed civil intent',
    state.puts[1]?.body?.availabilityStartAt, '2026-11-21T09:30:00+04:00');
  checkEqual('C03 the genuine end survives as typed civil intent',
    state.puts[1]?.body?.availabilityEndAt, '2026-11-21T12:00:00+04:00');
  checkEqual('C03 no preferred value is fabricated', state.puts[1]?.body?.preferredAt, null);

  state.requests = [
    requestFixture({
      version: 6,
      availabilityStartAt: '2026-11-22T12:00:00+05:30',
      availabilityEndAt: '2026-11-22T15:00:00+05:30',
      availabilityZoneId: 'Asia/Kolkata'
    }),
    secondRequest()
  ];
  await refreshHistory(editedMounted.page);
  await editedMounted.page.getByText(/This visit changed while you were editing/i)
    .waitFor({ state: 'visible', timeout: 8000 });
  const rebase = editedEditor.getByRole('button', { name: 'Use saved times as my starting point' });
  await rebase.focus();
  await editedMounted.page.keyboard.press('Enter');
  await waitFor(editedMounted.page, async () =>
    !(await editedMounted.page.getByText(/This visit changed while you were editing/i).isVisible().catch(() => false)));
  checkEqual('C10 keyboard Rebase adopts the newest saved zone',
    await editedEditor.getByLabel('Visit timezone').inputValue(), 'Asia/Kolkata');
  checkEqual('C10 keyboard Rebase adopts the newest saved start',
    await editedEditor.getByLabel('Start time').inputValue(), '12:00');
  checkEqual('C10 keyboard Rebase adopts the newest saved end',
    await editedEditor.getByLabel('End time').inputValue(), '15:00');
  checkEqual('C10 Rebase never writes automatically', state.puts.length, 2);

  await editedEditor.getByLabel('Start time').fill('11:00');
  await cardFor(editedMounted.page, 'Lakeview home').getByRole('button', { name: 'Update available times' }).click();
  await editedMounted.page.getByText(/Keep your earlier availability edits/i).waitFor({ state: 'visible', timeout: 8000 });
  const keep = editedMounted.page.getByRole('button', { name: 'Keep my earlier edits' });
  await keep.focus();
  await editedMounted.page.keyboard.press('Enter');
  checkEqual('C10 keyboard Keep preserves the earlier draft',
    await editedEditor.getByLabel('Start time').inputValue(), '11:00');
  checkEqual('C10 keyboard Keep opens no replacement form',
    await cardFor(editedMounted.page, 'Lakeview home').locator('form').count(), 0);
  await cardFor(editedMounted.page, 'Garden apartment').getByRole('button', { name: 'Close editor' }).click();
  await cardFor(editedMounted.page, 'Lakeview home').getByRole('button', { name: 'Update available times' }).click();
  const discard = editedMounted.page.getByRole('button', { name: 'Discard earlier edits and edit this visit' });
  await discard.focus();
  await editedMounted.page.keyboard.press('Enter');
  const bEditor = cardFor(editedMounted.page, 'Lakeview home').locator('form');
  checkEqual('C10 keyboard Discard opens the deliberate replacement',
    await bEditor.getByLabel('Start time').inputValue(), '10:00');
  checkEqual('C10 only one form remains after keyboard Discard',
    await editedMounted.page.locator('.tenant-v0-request-card form').count(), 1);
  checkEqual('C10 Keep/Discard never write availability', state.puts.length, 2);
  await editedMounted.context.close();
};

const run = async (browser) => {
  if (process.env.ONLY === 'e7-late-get') {
    await lateRecoveryReads(browser);
    return;
  }
  if (process.env.ONLY === 'zone-intent') {
    await zoneIntentMounted(browser);
    return;
  }
  if (process.env.ONLY === 'zone-review') {
    await zoneReviewMounted(browser);
    return;
  }
  if (process.env.ONLY === 'zone-switch') {
    await zoneSwitchMounted(browser);
    return;
  }
  if (process.env.ONLY === 'zone-recovery') {
    await zoneRecoveryMounted(browser);
    return;
  }
  if (process.env.ONLY === 'e1-late-ack') {
    await e1LateAcknowledgement(browser);
    return;
  }
  if (process.env.ONLY === 'clear-null') {
    await clearNullRecoveryMounted(browser);
    return;
  }
  // E1: a dirty v3 editor must not silently advance to v4.
  state.reset();
  let mounted = await newPage(browser);
  await openVisits(mounted.page);
  let editor = await openEditor(mounted.page);
  await editor.getByLabel('Start time').fill('11:00');
  state.requests = [
    requestFixture({
      version: 4,
      availabilityStartAt: '2026-11-21T12:00:00+05:30',
      availabilityEndAt: '2026-11-21T15:00:00+05:30'
    }),
    secondRequest()
  ];
  await refreshHistory(mounted.page);
  await mounted.page.getByText(/This visit changed while you were editing/).waitFor({ state: 'visible', timeout: 8000 });
  editor = cardFor(mounted.page, 'Garden apartment').locator('form');
  check('E9 the review notice receives focus when the latch appears',
    (await mounted.page.evaluate(() => document.activeElement?.textContent ?? '')).includes('This visit changed while you were editing'));
  checkEqual('E1 unsaved form text survives the refresh', await editor.getByLabel('Start time').inputValue(), '11:00');
  check('E1 Save is disabled at the explicit review boundary', await editor.getByRole('button', { name: 'Save available times' }).isDisabled());
  check('E1 Clear is disabled at the explicit review boundary', await editor.getByRole('button', { name: 'Clear saved times' }).isDisabled());
  checkEqual('E1 no PUT is transmitted while review is required', state.puts.length, 0);
  await editor.getByLabel('End time').fill('14:00');
  await editor.getByLabel('Visit timezone').selectOption('Asia/Dubai');
  const stillLatched = await editor.getByRole('button', { name: 'Save available times' }).isDisabled()
    && await mounted.page.getByText(/This visit changed while you were editing/).isVisible();
  check('E1 further edits keep the review gate latched', stillLatched,
    JSON.stringify({
      saveDisabled: await editor.getByRole('button', { name: 'Save available times' }).isDisabled(),
      reviewNotice: await mounted.page.getByText(/This visit changed while you were editing/).count()
    }));
  const rebaseButton = editor.getByRole('button', { name: 'Use saved times as my starting point' });
  await rebaseButton.focus();
  await mounted.page.keyboard.press('Enter');
  check('E9 Rebase activates from the keyboard',
    await waitFor(mounted.page, async () =>
      !(await mounted.page.getByText(/This visit changed while you were editing/).isVisible())));
  check('E1 explicit rebase clears the gate', !(await editor.getByRole('button', { name: 'Save available times' }).isDisabled()));
  checkEqual('E1 rebase loads the current saved start', await editor.getByLabel('Start time').inputValue(), '12:00');
  await editor.getByRole('button', { name: 'Save available times' }).click();
  await waitFor(mounted.page, () => state.puts.length === 1);
  checkEqual('E1 Save submits the reviewed version, not the observed v4', state.puts[0]?.body?.expectedRequestVersion, 4);
  checkEqual('E1 exactly one PUT was transmitted', state.puts.length, 1);
  await mounted.context.close();

  // E2: cleared facts stay cleared even when the row leaves page zero.
  state.reset();
  mounted = await newPage(browser);
  await openVisits(mounted.page);
  editor = await openEditor(mounted.page);
  await editor.getByRole('button', { name: 'Clear saved times' }).click();
  await editor.getByRole('button', { name: 'Yes, clear times' }).click();
  await waitFor(mounted.page, () => state.puts.length === 1);
  check('E2 clear sends all four authoritative fields as null',
    state.puts[0]?.body?.availabilityStartAt === null
    && state.puts[0]?.body?.availabilityEndAt === null
    && state.puts[0]?.body?.availabilityZoneId === null
    && state.puts[0]?.body?.preferredAt === null);
  await refreshHistory(mounted.page);
  check('E2 no obsolete opening window is rendered after the committed clear',
    (await cardFor(mounted.page, 'Garden apartment').getByText(/10:00 am–1:00 pm/).count()) === 0);
  check('E2 the cleared truth is stated', await mounted.page.getByText(/No structured availability is saved yet/).first().isVisible());
  state.requests = [];
  await refreshHistory(mounted.page);
  await mounted.page.getByRole('heading', { name: 'Availability updates in progress' }).waitFor({ state: 'visible', timeout: 8000 });
  check('E2 a displaced Request still projects the same all-null snapshot',
    (await mounted.page.locator('.tenant-v0-request-card').filter({ hasText: 'Garden apartment' }).getByText(/10:00 am–1:00 pm/).count()) === 0
    && await mounted.page.getByText(/No structured availability is saved yet/).first().isVisible());
  await mounted.context.close();

  // E3: an ambiguous PUT starts exactly one guarded read; no marker without a read.
  state.reset();
  state.putMode = 'commit-abort';
  mounted = await newPage(browser);
  await openVisits(mounted.page);
  editor = await openEditor(mounted.page);
  const beforeReads = state.listed;
  await editor.getByLabel('Start time').fill('11:00');
  await editor.getByRole('button', { name: 'Save available times' }).click();
  await mounted.page.getByText(/Current saved times match your earlier request/).waitFor({ state: 'visible', timeout: 10000 });
  checkEqual('E3 immediate execution issues one recovery GET', state.listed - beforeReads, 1);
  checkEqual('E3 ambiguous commit is transmitted once', state.puts.length, 1);
  check('E3 no false spinner remains after reconciliation',
    (await mounted.page.locator('form[aria-busy="true"]').count()) === 0);
  await mounted.context.close();

  state.reset();
  state.putMode = 'abort';
  mounted = await newPage(browser);
  await openVisits(mounted.page);
  editor = await openEditor(mounted.page);
  await editor.getByLabel('Start time').fill('11:00');
  await editor.getByRole('button', { name: 'Save available times' }).click();
  await mounted.page.getByText(/The saved times differ from your earlier request/).waitFor({ state: 'visible', timeout: 10000 });
  check('E3 a rolled-back write keeps the draft and requires review',
    await cardFor(mounted.page, 'Garden apartment').locator('form').getByLabel('Start time').inputValue() === '11:00'
    && await cardFor(mounted.page, 'Garden apartment').locator('form').getByRole('button', { name: 'Save available times' }).isDisabled());
  checkEqual('E3 the ambiguous attempt was never retried', state.puts.length, 1);
  await mounted.context.close();

  state.reset();
  state.putMode = 'abort';
  mounted = await newPage(browser);
  await openVisits(mounted.page);
  editor = await openEditor(mounted.page);
  state.listFailureRemaining = 1;
  const beforeFailureRead = state.listed;
  await editor.getByLabel('Start time').fill('11:00');
  await editor.getByRole('button', { name: 'Save available times' }).click();
  await mounted.page.getByText(/latest saved times could not be read/i).waitFor({ state: 'visible', timeout: 10000 });
  checkEqual('E3 a failed recovery read is attempted once', state.listed - beforeFailureRead, 1);
  check('E3 the visible Retry Read action is available', await mounted.page.getByRole('button', { name: 'Recheck saved times' }).isVisible());
  check('E3 focus moves to the unresolved read result',
    (await mounted.page.evaluate(() => document.activeElement?.textContent ?? '')).includes('latest saved times could not be read'));
  const beforeRetry = state.listed;
  const recheck = mounted.page.getByRole('button', { name: 'Recheck saved times' });
  await recheck.focus();
  await mounted.page.keyboard.press('Enter');
  await waitFor(mounted.page, () => state.listed > beforeRetry);
  checkEqual('E3 explicit Check starts one new read', state.listed - beforeRetry, 1);
  checkEqual('E3 no implicit PUT follows the read failure', state.puts.length, 1);
  await mounted.context.close();

  state.reset();
  state.putMode = 'hold';
  mounted = await newPage(browser);
  await openVisits(mounted.page);
  editor = await openEditor(mounted.page);
  await editor.getByLabel('Start time').fill('11:00');
  await mounted.page.evaluate(() => {
    const button = [...document.querySelectorAll('button')].find(node => (node.textContent ?? '').includes('Save available times'));
    button?.click();
    button?.click();
  });
  await waitFor(mounted.page, () => state.heldPut !== null);
  checkEqual('E3 double Save in one turn transmits at most one write', state.puts.length, 1);
  state.heldPut.route.abort('failed');
  state.heldPut = null;
  await mounted.context.close();

  state.reset();
  state.putMode = 'abort';
  mounted = await newPage(browser);
  await openVisits(mounted.page);
  editor = await openEditor(mounted.page);
  state.listFailureRemaining = 1;
  await editor.getByLabel('Start time').fill('11:00');
  await editor.getByRole('button', { name: 'Save available times' }).click();
  await mounted.page.getByText(/latest saved times could not be read/i).waitFor({ state: 'visible', timeout: 10000 });
  state.holdNextList = true;
  const beforeDoubleCheck = state.listed;
  await mounted.page.evaluate(() => {
    const button = [...document.querySelectorAll('button')].find(node => (node.textContent ?? '').includes('Recheck saved times'));
    button?.click();
    button?.click();
  });
  await waitFor(mounted.page, () => state.heldList !== null);
  checkEqual('E3 double Check in one turn starts one read', state.listed - beforeDoubleCheck, 1);
  const held = state.heldList;
  await json(held.route, 200, pageResponse(held.page));
  state.heldList = null;
  await mounted.context.close();

  // E4: one retained draft; dirty work is never silently evicted.
  state.reset();
  mounted = await newPage(browser);
  await openVisits(mounted.page);
  editor = await openEditor(mounted.page, 'Garden apartment');
  await editor.getByLabel('Start time').fill('11:00');
  await cardFor(mounted.page, 'Lakeview home').getByRole('button', { name: 'Update available times' }).click();
  await mounted.page.getByText(/Keep your earlier availability edits/).waitFor({ state: 'visible', timeout: 8000 });
  check('E9 the protected-switch notice is an explicit alert',
    (await mounted.page.getByRole('alert').filter({ hasText: /Keep your earlier availability edits/ }).count()) > 0);
  checkEqual('E4 a dirty A blocks silent replacement by B', await mounted.page.locator('.tenant-v0-request-card form').count(), 1);
  checkEqual('E4 A keeps its exact unsaved value', await editor.getByLabel('Start time').inputValue(), '11:00');
  const keepEdits = mounted.page.getByRole('button', { name: 'Keep my earlier edits' });
  await keepEdits.focus();
  await mounted.page.keyboard.press('Enter');
  check('E9 the protected-switch action activates from the keyboard', true);
  check('E4 keeping earlier edits leaves the notice clean', (await mounted.page.getByText(/Keep your earlier availability edits/).count()) === 0);
  await cardFor(mounted.page, 'Lakeview home').getByRole('button', { name: 'Update available times' }).click();
  await mounted.page.getByRole('button', { name: 'Discard earlier edits and edit this visit' }).focus();
  await mounted.page.keyboard.press('Enter');
  const bEditor = cardFor(mounted.page, 'Lakeview home').locator('form');
  checkEqual('E4 explicit discard opens B', await bEditor.getByLabel('Start time').inputValue(), '10:00');
  checkEqual('E4 only one form remains after the deliberate switch', await mounted.page.locator('.tenant-v0-request-card form').count(), 1);
  check('E9 protected-switch Discard activates from the keyboard', await bEditor.isVisible());

  await bEditor.getByRole('button', { name: 'Discard editing' }).focus();
  await mounted.page.keyboard.press('Enter');
  check('E9 editor Discard activates from the keyboard and closes its form', await bEditor.count() === 0);
  await openEditor(mounted.page, 'Garden apartment');
  editor = cardFor(mounted.page, 'Garden apartment').locator('form');
  await editor.getByLabel('Start time').fill('11:00');
  state.requests = [secondRequest()];
  await refreshHistory(mounted.page);
  await mounted.page.getByRole('heading', { name: 'Availability updates in progress' }).waitFor({ state: 'visible', timeout: 8000 });
  checkEqual('E4 pagination displacement retains the one active draft',
    await mounted.page.locator('.tenant-v0-request-card form').getByLabel('Start time').inputValue(), '11:00');
  state.userId = 8;
  await mounted.page.evaluate((entries) => {
    entries.forEach(([key, value]) => window.localStorage.setItem(key, value));
    window.dispatchEvent(new CustomEvent('pathome_auth_changed'));
  }, credentials(8, 'tenant-token-b'));
  await mounted.page.waitForTimeout(600);
  check('E4 account switch clears the retained draft',
    (await mounted.page.getByRole('heading', { name: 'Availability updates in progress' }).count()) === 0
    && (await mounted.page.locator('.tenant-v0-request-card form').count()) === 0);
  await mounted.context.close();

  // E7 identity controls: an abort-ignoring PUT remains in flight while the owning identity changes.
  state.reset();
  state.putMode = 'hold';
  mounted = await newPage(browser);
  await openVisits(mounted.page);
  editor = await openEditor(mounted.page);
  await editor.getByLabel('Start time').fill('11:00');
  await editor.getByRole('button', { name: 'Save available times' }).click();
  await waitFor(mounted.page, () => state.heldPut !== null);
  checkEqual('E7 the held account-A PUT carries account A credentials',
    state.puts[0]?.token, 'Bearer tenant-token-a');
  const heldForSwitch = state.heldPut;
  state.heldPut = null;
  const listReadsBeforeSwitch = state.listTokens.length;
  await mounted.page.evaluate((entries) => {
    entries.forEach(([key, value]) => window.localStorage.setItem(key, value));
    window.dispatchEvent(new CustomEvent('pathome_auth_changed'));
  }, credentials(8, 'tenant-token-b'));
  await mounted.page.waitForTimeout(600);
  await refreshHistory(mounted.page);
  await json(heldForSwitch.route, 200, {
    requestId: heldForSwitch.requestId,
    version: 4,
    availabilityStartAt: heldForSwitch.body.availabilityStartAt,
    availabilityEndAt: heldForSwitch.body.availabilityEndAt,
    availabilityZoneId: heldForSwitch.body.availabilityZoneId,
    preferredAt: heldForSwitch.body.preferredAt
  });
  await mounted.page.waitForTimeout(350);
  check('E7 a stale A success publishes no saved state into account B',
    (await mounted.page.getByText(/Available times saved/).count()) === 0
    && (await mounted.page.locator('.tenant-v0-request-card form').count()) === 0);
  checkEqual('E7 account switch transmits no second availability write', state.puts.length, 1);
  check('E7 no post-switch read reused account A credentials',
    state.listTokens.slice(listReadsBeforeSwitch).every((token) => token === 'Bearer tenant-token-b'),
    JSON.stringify({ before: listReadsBeforeSwitch, tokens: state.listTokens }));
  await mounted.context.close();

  state.reset();
  state.putMode = 'hold';
  mounted = await newPage(browser);
  await openVisits(mounted.page);
  editor = await openEditor(mounted.page);
  await editor.getByLabel('Start time').fill('11:00');
  await editor.getByRole('button', { name: 'Save available times' }).click();
  await waitFor(mounted.page, () => state.heldPut !== null);
  const heldForRotation = state.heldPut;
  state.heldPut = null;
  await mounted.page.evaluate(() => {
    window.localStorage.setItem('pathome_auth_token', 'tenant-token-rotated');
    window.dispatchEvent(new CustomEvent('pathome_auth_changed'));
  });
  await mounted.page.waitForTimeout(600);
  await json(heldForRotation.route, 200, {
    requestId: heldForRotation.requestId,
    version: 4,
    availabilityStartAt: heldForRotation.body.availabilityStartAt,
    availabilityEndAt: heldForRotation.body.availabilityEndAt,
    availabilityZoneId: heldForRotation.body.availabilityZoneId,
    preferredAt: heldForRotation.body.preferredAt
  });
  await mounted.page.waitForTimeout(350);
  check('E7 same-user token rotation invalidates the held A finalizer',
    (await mounted.page.getByText(/Available times saved/).count()) === 0
    && (await mounted.page.locator('.tenant-v0-request-card form').count()) === 0);
  check('E7 the rotated identity read carries only the new token',
    state.listTokens.at(-1) === 'Bearer tenant-token-rotated');
  checkEqual('E7 token rotation transmits no second availability write', state.puts.length, 1);
  await mounted.context.close();

  state.reset();
  state.putMode = 'hold';
  mounted = await newPage(browser);
  await openVisits(mounted.page);
  editor = await openEditor(mounted.page);
  await editor.getByLabel('Start time').fill('11:00');
  await editor.getByRole('button', { name: 'Save available times' }).click();
  await waitFor(mounted.page, () => state.heldPut !== null);
  const heldForRemount = state.heldPut;
  state.heldPut = null;
  await mounted.page.reload({ waitUntil: 'domcontentloaded' });
  await mounted.page.getByRole('button', { name: 'Update available times' }).first()
    .waitFor({ state: 'visible', timeout: 12000 });
  await json(heldForRemount.route, 200, {
    requestId: heldForRemount.requestId,
    version: 4,
    availabilityStartAt: heldForRemount.body.availabilityStartAt,
    availabilityEndAt: heldForRemount.body.availabilityEndAt,
    availabilityZoneId: heldForRemount.body.availabilityZoneId,
    preferredAt: heldForRemount.body.preferredAt
  }).catch(() => undefined);
  await mounted.page.waitForTimeout(350);
  check('E7 remount publishes no stale success and restores no in-memory draft',
    (await mounted.page.getByText(/Available times saved/).count()) === 0
    && (await mounted.page.locator('.tenant-v0-request-card form').count()) === 0);
  editor = await openEditor(mounted.page);
  checkEqual('E7 the reopened editor starts from authoritative v3, not the stale draft',
    await editor.getByLabel('Start time').inputValue(), '10:00');
  checkEqual('E7 remount transmits no second availability write', state.puts.length, 1);
  await mounted.context.close();

  // E9: keyboard, dimensions and mobile overflow on the real editor.
  state.reset();
  mounted = await newPage(browser, { viewport: { width: 390, height: 844 } });
  await openVisits(mounted.page);
  editor = await openEditor(mounted.page);
  await editor.getByLabel('Visit timezone').focus();
  const reached = [];
  for (let index = 0; index < 40; index += 1) {
    reached.push(await mounted.page.evaluate(() => {
      const active = document.activeElement;
      const label = active?.closest('label')?.querySelector('span')?.textContent
        ?? active?.closest('label')?.childNodes?.[0]?.textContent
        ?? active?.textContent ?? '';
      return String(label).trim();
    }));
    await mounted.page.keyboard.press('Tab');
  }
  for (const label of ['Visit timezone', 'Start date', 'Start time', 'End date', 'End time',
    'Preferred date', 'Preferred time', 'Save available times', 'Clear saved times', 'Discard editing']) {
    check(`E9 keyboard reaches ${label}`, reached.some(value => value.includes(label)), JSON.stringify(reached));
  }
  const mobile = await mounted.page.evaluate(() => {
    const controls = [...document.querySelectorAll('.tenant-v0-request-card form input, .tenant-v0-request-card form select, .tenant-v0-request-card form button')];
    return {
      overflow: document.documentElement.scrollWidth > window.innerWidth,
      smallTarget: controls.some(control => control.getBoundingClientRect().height < 44),
      smallFont: [...document.querySelectorAll('.tenant-v0-request-card form input, .tenant-v0-request-card form select')]
        .some(control => Number(getComputedStyle(control).fontSize.replace('px', '')) < 16)
    };
  });
  check('E9 no horizontal overflow at 390px', !mobile.overflow);
  check('E9 44px targets remain intact', !mobile.smallTarget);
  check('E9 editable controls remain at least 16px on mobile', !mobile.smallFont);
  await mounted.context.close();

  state.reset();
  mounted = await newPage(browser, { viewport: { width: 320, height: 800 } });
  state.requests[0].propertyTitle = 'A very long garden apartment name intended to wrap without horizontal displacement';
  await openVisits(mounted.page);
  editor = await openEditor(mounted.page);
  await editor.getByLabel('Start time').fill('');
  await editor.getByRole('button', { name: 'Save available times' }).click();
  check('E9 long copy and an error state do not overflow at 320px',
    await mounted.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  await mounted.page.emulateMedia({ reducedMotion: 'reduce' });
  check('E9 reduced-motion mode keeps the editor usable', await editor.isVisible());
  await mounted.context.close();

  state.reset();
  mounted = await newPage(browser, { viewport: { width: 1280, height: 900 } });
  await openVisits(mounted.page);
  await openEditor(mounted.page);
  check('E9 no horizontal overflow at 1280px',
    await mounted.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  await mounted.context.close();
};

let browser;
try {
  browser = await chromium.launch({ channel: 'chromium' });
  await run(browser);
} catch (error) {
  failures += 1;
  results.push(`  ✖ mounted tenant-availability run threw: ${error.message}`);
} finally {
  await browser?.close();
}

if (budgetLeft() < 0) {
  notTested += 1;
  results.push('  ⊘ NOT TESTED: the nine-minute mounted verification budget was exhausted');
}
console.log(`\n${results.join('\n')}\n`);
console.log(`${checks} checks, ${failures} failure(s), ${notTested} not tested — `
  + `${((Date.now() - STARTED_AT) / 1000).toFixed(1)}s — ${failures === 0 ? 'TENANT MOUNTED CHECKS PASSED' : 'TENANT MOUNTED CHECKS FAILED'}`);
process.exit(failures === 0 ? 0 : 1);
