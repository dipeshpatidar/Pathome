/**
 * OE Batch B controlled browser acceptance.
 *
 * SIMULATED BACKEND. The production app, router, workspace, route helpers, components and CSS are
 * real; every `/api/**` response is produced by the in-process stub below. No result here is a
 * real-backend or physical-device pass.
 *
 * Usage:
 *   npm --prefix frontend-web run build
 *   node frontend-web/browser-verification/operations-batch-b/run.mjs
 */
import { readFile } from 'node:fs/promises';
import { extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;
const DIST = fileURLToPath(new URL('../../dist/', import.meta.url));
const ORIGIN = 'http://oe-batch-b.local';
const TOKEN = 'controlled-oe-token';

const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2'
};

const COVER = 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIxNjAiIGhlaWdodD0iMTIwIj48cmVjdCB3aWR0aD0iMTYwIiBoZWlnaHQ9IjEyMCIgZmlsbD0iIzM1NWM0OSIvPjwvc3ZnPg==';

const GRANT_A = {
  capability: 'OPS_COORDINATE', scopeType: 'TEAM', cityId: 1, cityDisplayName: 'Indore',
  teamId: 7, teamDisplayName: 'Team A Coordination'
};
const GRANT_B = {
  capability: 'OPS_COORDINATE', scopeType: 'TEAM', cityId: 2, cityDisplayName: 'Pune',
  teamId: 9, teamDisplayName: 'Team B Coordination'
};

const OWNED_SESSIONS = Array.from({ length: 25 }, (_, index) => ({
  targetType: 'SESSION', targetId: 3100 + index, expectedVersion: 2,
  cityId: 1, cityDisplayName: 'Indore', teamId: 7, teamDisplayName: 'Team A Coordination',
  status: 'SCHEDULED', updatedAt: '2026-10-18T11:00:00Z',
  scheduledAt: '2026-10-21T05:30:00Z', tenantConfirmationState: 'CONFIRMED',
  linkedRequestVersions: [{ requestId: 502, version: 3 }]
}));

const MY_WORK = [
  { targetType: 'REQUEST', targetId: 5200, expectedVersion: 3, cityId: 1, cityDisplayName: 'Indore',
    teamId: 7, teamDisplayName: 'Team A Coordination', status: 'RECEIVED', updatedAt: null,
    scheduledAt: null, tenantConfirmationState: null, linkedRequestVersions: [] },
  { targetType: 'REQUEST', targetId: 5201, expectedVersion: 4, cityId: 1, cityDisplayName: 'Indore',
    teamId: 7, teamDisplayName: 'Team A Coordination', status: 'COORDINATING', updatedAt: null,
    scheduledAt: null, tenantConfirmationState: null, linkedRequestVersions: [] },
  ...OWNED_SESSIONS,
  { targetType: 'SESSION', targetId: 3300, expectedVersion: 3, cityId: 2, cityDisplayName: 'Pune',
    teamId: 9, teamDisplayName: 'Team B Coordination', status: 'SCHEDULED',
    updatedAt: '2026-10-18T12:00:00Z', scheduledAt: '2026-10-22T05:30:00Z',
    tenantConfirmationState: 'PENDING', linkedRequestVersions: [] }
];

const CLAIMABLE = [
  { targetType: 'REQUEST', targetId: 6200, expectedVersion: 1, cityId: 1, cityDisplayName: 'Indore',
    teamId: 7, teamDisplayName: 'Team A Coordination', status: 'RECEIVED', linkedRequestVersions: [] },
  { targetType: 'SESSION', targetId: 3200, expectedVersion: 2, cityId: 2, cityDisplayName: 'Pune',
    teamId: 9, teamDisplayName: 'Team B Coordination', status: 'DRAFT',
    linkedRequestVersions: [{ requestId: 601, version: 1 }] }
];

const requestDetail = (requestId) => ({
  requestId,
  status: 'RECEIVED',
  version: 3,
  preferredVisitTiming: 'This weekend after 4pm',
  createdAt: '2026-10-18T09:00:00',
  cityId: 1,
  cityDisplayName: 'Indore',
  teamId: 7,
  teamDisplayName: 'Team A Coordination',
  listingId: 7001,
  listingTitle: 'Sunlit 3BHK',
  listingAddress: 'Vijay Nagar, Indore',
  listingStatus: 'ACTIVE',
  listingCity: 'Indore',
  sector: 'Vijay Nagar',
  availabilityStartAt: null,
  availabilityEndAt: null,
  availabilityZoneId: 'Asia/Kolkata',
  preferredAt: null,
  sessionId: null
});

const sessionDetail = (sessionId, items, overrides = {}) => ({
  sessionId,
  status: 'DRAFT',
  version: 6,
  city: 'Indore',
  areaName: 'Vijay Nagar',
  scheduledAt: null,
  reservedEndAt: null,
  durationMinutes: null,
  zoneId: 'Asia/Kolkata',
  representativeUserId: null,
  assignedAt: null,
  items,
  linkedRequestVersions: [{ requestId: 502, version: 3 }],
  linkedRequestTimingFacts: [],
  teamId: 7,
  teamDisplayName: 'Team A Coordination',
  coordinatorUserId: 11,
  updatedAt: '2026-10-18T11:00:00Z',
  tenantConfirmationState: 'NOT_REQUIRED',
  startedAt: null,
  expiresAt: null,
  executionStateChangedAt: null,
  ...overrides
});

const item = (itemId, listingId, title, status, cover) => ({
  itemId,
  listingId,
  title,
  address: `${title} address`,
  city: 'Indore',
  sector: 'Vijay Nagar',
  position: itemId === 9001 ? 1 : 2,
  sourceRequestId: 502,
  derivedFromRequestId: null,
  origin: 'OE_ADDED',
  confirmationStatus: status === 'ACTIVE' ? 'CONFIRMED' : 'PENDING',
  availabilityConfirmedAt: null,
  removedAt: null,
  removalReason: null,
  propertyAvailabilityStartAt: null,
  propertyAvailabilityEndAt: null,
  propertyAvailabilityZoneId: 'Asia/Kolkata',
  propertyAvailabilitySource: null,
  coverImageUrl: cover,
  listingStatus: status
});

const SESSION_3124 = sessionDetail(3124, [
  item(9001, 7001, 'Sunlit 3BHK', 'ACTIVE', COVER),
  item(9002, 7002, 'Draft media property', 'PENDING', null)
], {
  status: 'SCHEDULED',
  scheduledAt: '2026-10-21T05:30:00Z',
  reservedEndAt: '2026-10-21T06:30:00Z',
  durationMinutes: 60,
  tenantConfirmationState: 'CONFIRMED'
});
const SESSION_3300 = sessionDetail(3300, [item(9101, 7101, 'Unpublished property', 'PENDING', null)], {
  status: 'SCHEDULED',
  scheduledAt: '2026-10-22T05:30:00Z',
  tenantConfirmationState: 'PENDING'
});
const SESSION_3400 = sessionDetail(3400, [item(9401, 7401, 'Stale media plan', 'PENDING', COVER)], {
  status: 'DRAFT',
  tenantConfirmationState: 'NOT_REQUIRED'
});
const SESSION_3402 = sessionDetail(3402, [
  item(9601, 7601, 'Broken cover plan', 'ACTIVE', 'https://broken.invalid/cover.jpg')
], {
  status: 'SCHEDULED',
  tenantConfirmationState: 'CONFIRMED'
});
const STATUS_SESSIONS = Object.fromEntries(
  ['DRAFT', 'SCHEDULED', 'STARTED', 'COMPLETED', 'INTERRUPTED'].map((status, index) => [
    3410 + index,
    sessionDetail(3410 + index, [item(9500 + index, 7500 + index, `${status} property`, 'PENDING', null)], {
      status,
      tenantConfirmationState: status === 'SCHEDULED' ? 'CONFIRMED' : 'NOT_REQUIRED'
    })
  ])
);

const stub = {
  grants: [GRANT_A, GRANT_B],
  holdMyWork: false,
  heldMyWork: [],
  holdDetailId: null,
  heldDetails: [],
  failDetailId: null,
  failDetailDelayMs: 0,
  failWork: false,
  reset() {
    this.grants = [GRANT_A, GRANT_B];
    this.holdMyWork = false;
    this.heldMyWork.splice(0).forEach((release) => release());
    this.holdDetailId = null;
    this.heldDetails.splice(0).forEach((release) => release());
    this.failDetailId = null;
    this.failDetailDelayMs = 0;
    this.failWork = false;
  }
};

const authorizedTeamIds = () => new Set(stub.grants
  .filter((grant) => grant.capability === 'OPS_COORDINATE' && grant.scopeType === 'TEAM')
  .map((grant) => grant.teamId));

const RESULTS = [];
let checks = 0;
let failures = 0;
const check = (label, ok, detail = '') => {
  checks += 1;
  RESULTS.push(`  ${ok ? '✔' : '✖'} ${label}${ok || !detail ? '' : ` — ${detail}`}`);
  if (!ok) failures += 1;
};

const positive = (url, key) => {
  const raw = url.searchParams.get(key);
  if (!raw || !/^\d+$/.test(raw)) return null;
  const value = Number(raw);
  return Number.isSafeInteger(value) && value > 0 ? value : null;
};

const applyFilters = (rows, url) => {
  const type = url.searchParams.get('targetType');
  const status = url.searchParams.get('status');
  const city = positive(url, 'cityId');
  const team = positive(url, 'teamId');
  const ref = positive(url, 'targetId');
  return rows.filter((row) =>
    (!type || row.targetType === type)
    && (!status || row.status === status)
    && (city === null || row.cityId === city)
    && (team === null || row.teamId === team)
    && (ref === null || row.targetId === ref));
};

const pageOf = (rows, url) => {
  const unfiltered = rows.length;
  const filtered = applyFilters(rows, url);
  const size = Math.max(1, Math.min(100, Number(url.searchParams.get('size') ?? 20)));
  const requested = Math.max(0, Number(url.searchParams.get('page') ?? 0) || 0);
  const totalPages = filtered.length === 0 ? 0 : Math.ceil(filtered.length / size);
  const page = totalPages === 0 ? 0 : Math.min(requested, totalPages - 1);
  return {
    items: filtered.slice(page * size, page * size + size),
    totalCount: filtered.length,
    unfilteredTotalCount: unfiltered,
    page,
    size,
    totalPages
  };
};

const apiResponse = (url) => {
  if (url.pathname === '/api/v1/auth/login') {
    return {
      token: TOKEN,
      userId: 11,
      role: 'TENANT',
      email: 'oe@example.test',
      fullName: 'OE Operator'
    };
  }
  if (url.pathname === '/api/v1/staff/me/capabilities') {
    return { staffActive: true, capabilities: stub.grants, serverTime: '2026-10-19T10:00:00Z' };
  }
  const teams = authorizedTeamIds();
  if (url.pathname === '/api/v1/operations/my-work') {
    return pageOf(MY_WORK.filter((row) => teams.has(row.teamId)), url);
  }
  if (url.pathname === '/api/v1/operations/claimable-work') {
    return pageOf(CLAIMABLE.filter((row) => teams.has(row.teamId)), url);
  }
  if (url.pathname === '/api/v1/operations/visit-requests/5200') {
    return teams.has(7) ? requestDetail(5200) : { __status: 404, message: 'Visit Request not found' };
  }
  if (url.pathname === '/api/v1/operations/visit-sessions/3124') {
    return teams.has(7) ? SESSION_3124 : { __status: 404, message: 'Visit Session not found' };
  }
  if (url.pathname === '/api/v1/operations/visit-sessions/3300') {
    return teams.has(9) ? SESSION_3300 : { __status: 404, message: 'Visit Session not found' };
  }
  if (url.pathname === '/api/v1/operations/visit-sessions/3400') {
    return teams.has(7) ? SESSION_3400 : { __status: 404, message: 'Visit Session not found' };
  }
  if (url.pathname === '/api/v1/operations/visit-sessions/3402') {
    return teams.has(7) ? SESSION_3402 : { __status: 404, message: 'Visit Session not found' };
  }
  for (const [id, session] of Object.entries(STATUS_SESSIONS)) {
    if (url.pathname === `/api/v1/operations/visit-sessions/${id}`) {
      return teams.has(7) ? session : { __status: 404, message: 'Visit Session not found' };
    }
  }
  return [];
};

const requests = [];

const serveStatic = async (route, url) => {
  const pathname = url.pathname === '/' ? '/index.html' : url.pathname;
  try {
    const file = join(DIST, pathname);
    const body = await readFile(file);
    await route.fulfill({
      status: 200,
      contentType: CONTENT_TYPES[extname(file)] ?? 'application/octet-stream',
      body
    });
  } catch {
    const body = await readFile(join(DIST, 'index.html'));
    await route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body });
  }
};

const storage = [
  ['pathome_auth_token', TOKEN],
  ['pathome_role', 'TENANT'],
  ['pathome_user', JSON.stringify({ id: 11, role: 'TENANT', fullName: 'OE Operator', email: 'oe@example.test' })]
];

const open = async (browser, path, options = {}) => {
  stub.reset();
  options.configureStub?.();
  const context = await browser.newContext({
    viewport: options.viewport ?? { width: 1280, height: 900 },
    reducedMotion: options.reducedMotion ?? 'no-preference'
  });
  const page = await context.newPage();
  await page.addInitScript((entries) => {
    entries.forEach(([key, value]) => window.localStorage.setItem(key, value));
  }, options.storage ?? storage);
  page.on('pageerror', (error) => RESULTS.push(`  ! page error: ${error.message}`));
  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url());
    if (url.origin !== ORIGIN) return route.continue();
    if (url.pathname.startsWith('/api/')) {
      requests.push(url.pathname + url.search);
      const body = apiResponse(url);
      const detailMatch = url.pathname.match(/\/(?:visit-requests|visit-sessions)\/(\d+)$/);
      const detailId = detailMatch ? Number(detailMatch[1]) : null;
      if (url.pathname === '/api/v1/operations/my-work' && stub.holdMyWork) {
        stub.holdMyWork = false;
        await new Promise((release) => stub.heldMyWork.push(release));
      }
      if (detailId !== null && stub.holdDetailId === detailId) {
        stub.holdDetailId = null;
        await new Promise((release) => stub.heldDetails.push(release));
      }
      if (detailId !== null && stub.failDetailId === detailId) {
        stub.failDetailId = null;
        if (stub.failDetailDelayMs > 0) await new Promise((resolve) => setTimeout(resolve, stub.failDetailDelayMs));
        return route.fulfill({
          status: 401,
          contentType: 'application/json',
          headers: { 'Cache-Control': 'no-store' },
          body: JSON.stringify({ status: 401, message: 'Session expired' })
        });
      }
      if (url.pathname === '/api/v1/operations/my-work'
          && url.searchParams.get('attentionOnly') === 'false'
          && stub.failWork) {
        return route.fulfill({
          status: 503,
          contentType: 'application/json',
          headers: { 'Cache-Control': 'no-store' },
          body: JSON.stringify({ status: 503, message: 'Temporarily unavailable' })
        });
      }
      const status = body?.__status ?? 200;
      try {
        return await route.fulfill({
          status,
          contentType: 'application/json',
          headers: { 'Cache-Control': 'no-store' },
          body: JSON.stringify(status === 200 ? body : { status, message: body.message })
        });
      } catch {
        // The client may have aborted a superseded read before it was released.
        return undefined;
      }
    }
    return serveStatic(route, url);
  });
  await page.goto(`${ORIGIN}${path}`, { waitUntil: 'domcontentloaded' });
  return { context, page };
};

const waitVisible = async (page, selector, timeout = 8000) => {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await page.locator(selector).first().isVisible().catch(() => false)) return true;
    await page.waitForTimeout(120);
  }
  return false;
};

const waitFor = async (predicate, timeout = 8000) => {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await predicate()) return true;
    await new Promise((resolve) => setTimeout(resolve, 80));
  }
  return false;
};

const containerBackground = async (locator) => locator.first().evaluate((node) => {
  const background = getComputedStyle(node).backgroundColor;
  if (background.startsWith('rgba(') && background.endsWith(', 0)')) return 'transparent';
  return background;
});

const scenarios = [
  async function responsiveNavigation(browser) {
    for (const width of [320, 375, 390, 430, 767, 768, 1024, 1440]) {
      requests.length = 0;
      const { context, page } = await open(browser, '/operations', { viewport: { width, height: 900 } });
      const navSelector = width < 768 ? '.oe-tab-btn' : '.oe-sidebar-btn';
      const ready = await waitVisible(page, navSelector);
      check(`${width}px: workspace shell renders`, ready);
      const navCount = await page.locator(navSelector).count();
      check(`${width}px: four destinations are present`, navCount === 4, `${navCount}`);
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      check(`${width}px: no horizontal overflow`, overflow <= 0, `${overflow}px`);
      const sidebarVisible = await page.locator('.oe-sidebar').isVisible().catch(() => false);
      const tabbarVisible = await page.locator('.oe-tabbar').isVisible().catch(() => false);
      check(`${width}px: ${width < 768 ? 'mobile tab bar' : 'persistent sidebar'} is visible`,
        width < 768 ? tabbarVisible : sidebarVisible);
      const heights = await page.locator(navSelector).evaluateAll((nodes) =>
        nodes.map((node) => node.getBoundingClientRect().height));
      check(`${width}px: every destination keeps a 44px target`,
        heights.length === 4 && heights.every((height) => height >= 44), heights.join(','));
      await context.close();
    }
  },

  async function directQueueAndDetailContinuity(browser) {
    requests.length = 0;
    const { context, page } = await open(browser,
      '/operations/queues/my-work?page=2&type=SESSION&status=SCHEDULED&city=1&team=7');
    await page.locator('[data-row-key="SESSION-3124"]').waitFor({ state: 'visible', timeout: 8000 });
    const queueRead = requests.filter((path) => path.startsWith('/api/v1/operations/my-work')).at(-1) ?? '';
    check('direct queue URL preserves page and every filter on the server read',
      queueRead.includes('page=1') && queueRead.includes('targetType=SESSION')
      && queueRead.includes('status=SCHEDULED') && queueRead.includes('cityId=1')
      && queueRead.includes('teamId=7'), queueRead);
    check('the filtered row is rendered', await page.locator('[data-row-key="SESSION-3124"]').isVisible());

    await page.locator('[data-row-key="SESSION-3124"]').click();
    await page.locator('.oe-detail-head').waitFor({ state: 'visible', timeout: 8000 });
    check('opening a detail carries the queue/page/filter return context',
      page.url().includes('/operations/sessions/3124')
      && page.url().includes('from=my-work') && page.url().includes('page=2')
      && page.url().includes('type=SESSION'), page.url());
    check('the ACTIVE property exposes a public listing link',
      (await page.locator('a.oe-thumb--link[href="/property/7001"]').count()) === 1);
    check('the detail states tenant visit requests and properties to visit separately',
      (await page.getByText('1 tenant visit request · 2 properties to visit').count()) === 1);
    check('the thumbnail has descriptive alt text',
      (await page.locator('img[alt="property photo: Sunlit 3BHK"]').count()) === 1);
    check('an unpublished property has no public link',
      (await page.locator('a.oe-thumb--link[href="/property/7002"]').count()) === 0);
    check('a missing cover is an explicit fallback, not fabricated media',
      (await page.locator('.oe-thumb-empty[aria-label="No property photo"]').count()) >= 1);

    await page.locator('.oe-back').click();
    await page.waitForFunction(() => window.location.pathname === '/operations/queues/my-work', null, { timeout: 8000 });
    check('Back restores the queue route with page and filters',
      page.url().includes('page=2') && page.url().includes('type=SESSION'), page.url());
    await page.waitForTimeout(250);
    const focused = await page.evaluate(() => document.activeElement?.getAttribute('data-row-key'));
    check('Back restores focus to the originating row', focused === 'SESSION-3124', String(focused));

    await page.goForward();
    await page.locator('.oe-detail-head').waitFor({ state: 'visible', timeout: 8000 });
    check('Forward restores the detail route', page.url().includes('/operations/sessions/3124'));
    const detailReadsBefore = requests.filter((path) => path.includes('/visit-sessions/3124')).length;
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.locator('.oe-detail-head').waitFor({ state: 'visible', timeout: 8000 });
    const detailReadsAfter = requests.filter((path) => path.includes('/visit-sessions/3124')).length;
    check('refreshing the detail re-fetches authoritative facts', detailReadsAfter > detailReadsBefore);
    await context.close();
  },

  async function filterChipsAndNoMatch(browser) {
    requests.length = 0;
    const { context, page } = await open(browser, '/operations/queues/my-work');
    await waitVisible(page, '.oe-filters');
    await page.getByLabel('Record type').selectOption('REQUEST');
    await page.getByLabel('Status').selectOption('RECEIVED');
    await page.getByLabel('City').selectOption('1');
    await page.getByLabel('Team').selectOption('7');
    await page.getByRole('button', { name: 'Apply filters' }).click();
    await page.waitForFunction(() => window.location.search.includes('type=REQUEST'), null, { timeout: 8000 });
    check('applying filters updates the URL contract',
      ['type=REQUEST', 'status=RECEIVED', 'city=1', 'team=7'].every((part) => page.url().includes(part)),
      page.url());
    const filteredRead = requests.filter((path) => path.startsWith('/api/v1/operations/my-work')).at(-1) ?? '';
    check('the visible filter state drives the authoritative server query',
      filteredRead.includes('targetType=REQUEST') && filteredRead.includes('status=RECEIVED'),
      filteredRead);
    await page.waitForTimeout(250);
    const countText = await page.locator('.oe-filter-count').innerText();
    check('the matching/total count is truthful', countText.includes('1') && countText.includes('28'), countText);
    check('active filter chips are visible', (await page.locator('.oe-filter-chips button').count()) === 4);

    await page.getByRole('button', { name: 'Clear all' }).click();
    await page.waitForFunction(() => window.location.search === '', null, { timeout: 8000 });
    check('clearing filters removes the route query', page.url().endsWith('/operations/queues/my-work'));

    await page.getByLabel('Exact record').fill('501');
    await page.getByRole('button', { name: 'Apply filters' }).click();
    check('an exact record cannot be applied without a type',
      await page.locator('#oe-filter-error').isVisible());
    await context.close();
  },

  async function noMatchAndOutOfRange(browser) {
    const { context, page } = await open(browser,
      '/operations/queues/my-work?type=SESSION&status=COMPLETED');
    const noMatch = page.getByText('No records match these filters');
    await noMatch.waitFor({ state: 'visible', timeout: 8000 });
    check('a no-match filter state is distinct from an empty queue', await noMatch.isVisible());
    await context.close();

    const second = await open(browser, '/operations/queues/my-work?page=99');
    await second.page.locator('[data-row-key="SESSION-3124"]').waitFor({ state: 'visible', timeout: 8000 });
    await second.page.waitForTimeout(1200);
    check('an out-of-range page clamps with replace-history semantics',
      second.page.url().includes('/operations/queues/my-work?page=2'), second.page.url());
    await second.context.close();
  },

  async function safeFallbackAndAccessibility(browser) {
    const { context, page } = await open(browser, '/operations/queues/not-a-queue?type=REQUEST');
    await page.waitForFunction(() => window.location.pathname === '/operations', null, { timeout: 8000 });
    check('an unknown Operations path falls back to the overview', page.url().endsWith('/operations'));
    await page.locator('.oe-skip').focus();
    check('the skip link becomes visible on keyboard focus', await page.locator('.oe-skip').isVisible());
    await page.keyboard.press('Enter');
    const target = await page.evaluate(() => document.activeElement?.id);
    check('the skip link moves focus to the Operations main region', target === 'oe-main-content', String(target));
    await context.close();

    const invalid = await open(browser, '/operations/requests/not-a-number?type=REQUEST');
    await invalid.page.waitForFunction(() => window.location.pathname === '/operations', null, { timeout: 8000 });
    check('an invalid detail id canonicalizes to the safe Operations entry',
      invalid.page.url().endsWith('/operations'), invalid.page.url());
    await invalid.context.close();

    const reduced = await open(browser, '/operations/queues/my-work', { reducedMotion: 'reduce' });
    await waitVisible(reduced.page, '.oe-sidebar');
    const reducedMatches = await reduced.page.evaluate(() =>
      window.matchMedia('(prefers-reduced-motion: reduce)').matches);
    check('the reduced-motion environment renders the workspace', reducedMatches);
    await reduced.context.close();
  },

  async function embeddedAndTenantRegression(browser) {
    const employee = [
      ['pathome_auth_token', TOKEN],
      ['pathome_role', 'EMPLOYEE'],
      ['pathome_user', JSON.stringify({ id: 12, role: 'EMPLOYEE', fullName: 'Console User', email: 'console@example.test' })]
    ];
    const { context, page } = await open(browser, '/crm', { storage: employee });
    const tab = page.getByRole('button', { name: /Operations workspace/i });
    await tab.waitFor({ state: 'visible', timeout: 8000 });
    await tab.click();
    const embeddedReady = await waitVisible(page, '.oe-subnav');
    const subnavCount = await page.locator('.oe-subnav').count();
    const sidebarCount = await page.locator('.oe-sidebar').count();
    check('the embedded Employee console keeps the compact workspace',
      embeddedReady && subnavCount === 1 && sidebarCount === 0,
      `ready=${embeddedReady}, subnav=${subnavCount}, sidebar=${sidebarCount}, url=${page.url()}`);
    await context.close();

    const tenant = [
      ['pathome_auth_token', 'tenant-token'],
      ['pathome_role', 'TENANT'],
      ['pathome_user', JSON.stringify({ id: 13, role: 'TENANT', fullName: 'Tenant User', email: 'tenant@example.test' })]
    ];
    const tenantContext = await open(browser, '/tenant', { storage: tenant });
    await tenantContext.page.waitForTimeout(600);
    check('existing Tenant routing remains on /tenant',
      new URL(tenantContext.page.url()).pathname === '/tenant'
      && (await tenantContext.page.locator('.oe--page, .oe--embedded').count()) === 0);
    await tenantContext.context.close();
  },

  async function directDetailBackRestoresQueue(browser) {
    const request = await open(browser,
      '/operations/requests/5200?from=my-work&page=2&type=REQUEST&status=RECEIVED&city=1&team=7');
    await request.page.locator('.oe-detail-head').waitFor({ state: 'visible', timeout: 8000 });
    check('a direct Request detail URL is served without an in-app origin',
      request.page.url().includes('/operations/requests/5200'), request.page.url());
    await request.page.locator('.oe-back').click();
    await request.page.waitForFunction(() => window.location.pathname === '/operations/queues/my-work',
      null, { timeout: 8000 });
    check('direct Request detail Back restores its encoded queue/page/filter context',
      request.page.url().includes('type=REQUEST') && request.page.url().includes('status=RECEIVED'),
      request.page.url());
    check('direct Request detail Back never leaves the app', request.page.url().startsWith(ORIGIN));
    await request.context.close();

    const session = await open(browser,
      '/operations/sessions/3124?from=my-work&page=2&type=SESSION&status=SCHEDULED&city=1&team=7');
    await session.page.locator('.oe-detail-head').waitFor({ state: 'visible', timeout: 8000 });
    await session.page.locator('.oe-back').click();
    await session.page.waitForFunction(() => window.location.pathname === '/operations/queues/my-work',
      null, { timeout: 8000 });
    check('direct Session detail Back restores its encoded queue/page/filter context',
      session.page.url().includes('page=2') && session.page.url().includes('type=SESSION'),
      session.page.url());
    check('direct Session detail Back stays inside the app', session.page.url().startsWith(ORIGIN));
    await session.context.close();
  },

  async function embeddedHostContinuity(browser) {
    const employee = [
      ['pathome_auth_token', TOKEN],
      ['pathome_role', 'EMPLOYEE'],
      ['pathome_user', JSON.stringify({ id: 12, role: 'EMPLOYEE', fullName: 'Console User', email: 'console@example.test' })]
    ];
    const crm = await open(browser, '/crm', { storage: employee });
    await crm.page.getByRole('button', { name: /Operations workspace/i }).click();
    await waitVisible(crm.page, '.oe-subnav');
    await crm.page.locator('.oe-subnav-btn', { hasText: 'My work' }).click();
    await crm.page.locator('[data-row-key="REQUEST-5200"]').click();
    await crm.page.locator('.oe-detail-head').waitFor({ state: 'visible', timeout: 8000 });
    check('embedded Employee navigation keeps the host URL', new URL(crm.page.url()).pathname === '/crm',
      crm.page.url());
    stub.grants = [GRANT_B];
    await crm.page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await crm.page.locator('[data-row-key="SESSION-3300"]').waitFor({ state: 'visible', timeout: 8000 });
    check('an embedded scope change clears old host-tab work without leaving the console',
      new URL(crm.page.url()).pathname === '/crm'
      && (await crm.page.locator('[data-row-key="REQUEST-5200"]').count()) === 0);
    await crm.page.reload({ waitUntil: 'domcontentloaded' });
    check('refresh restores the Employee Operations host tab',
      await waitVisible(crm.page, '.oe-subnav') && new URL(crm.page.url()).pathname === '/crm',
      crm.page.url());
    check('refresh never converts the Employee host into standalone Operations',
      !crm.page.url().includes('/operations') && (await crm.page.locator('.oe-sidebar').count()) === 0);
    await crm.context.close();

    const adminStorage = [
      ['pathome_auth_token', TOKEN],
      ['pathome_role', 'ADMIN'],
      ['pathome_user', JSON.stringify({ id: 14, role: 'ADMIN', fullName: 'Admin User', email: 'admin@example.test' })]
    ];
    const admin = await open(browser, '/admin', { storage: adminStorage });
    await admin.page.getByRole('button', { name: /Operations workspace/i }).first().click();
    await waitVisible(admin.page, '.oe-subnav');
    check('embedded Admin navigation keeps the host URL', new URL(admin.page.url()).pathname === '/admin',
      admin.page.url());
    await admin.page.reload({ waitUntil: 'domcontentloaded' });
    check('refresh restores the Admin Operations host tab',
      await waitVisible(admin.page, '.oe-subnav') && new URL(admin.page.url()).pathname === '/admin',
      admin.page.url());
    await admin.context.close();
  },

  async function sessionExpiryReturnAndStaleToken(browser) {
    const current = await open(browser, '/operations/queues/my-work');
    await current.page.locator('[data-row-key="REQUEST-5200"]').waitFor({ state: 'visible', timeout: 8000 });
    stub.failDetailId = 5200;
    stub.failDetailDelayMs = 500;
    await current.page.locator('[data-row-key="REQUEST-5200"]').click();
    await current.page.waitForFunction(() => window.location.pathname === '/operations/requests/5200',
      null, { timeout: 8000 });
    const displayed = current.page.url();
    await current.page.waitForFunction(() => window.location.pathname === '/', null, { timeout: 8000 });
    const pending = await current.page.evaluate(() => sessionStorage.getItem('pathome_pending_after_auth'));
    const expected = new URL(displayed);
    check('a current-token 401 captures the currently displayed detail route',
      pending === `${expected.pathname}${expected.search}`, `pending=${pending} displayed=${displayed}`);
    check('session expiry opens sign-in instead of leaving protected detail visible',
      await current.page.locator('.fixed.inset-0, [role="dialog"]').count() > 0);
    await current.page.locator('#pathome-auth-email').fill('oe@example.test');
    await current.page.locator('#pathome-auth-password').fill('test-password');
    await current.page.getByRole('button', { name: 'Sign In', exact: true }).click();
    const returned = await waitFor(async () =>
      (await current.page.evaluate(() => window.location.pathname)) === '/operations/requests/5200', 10000);
    if (!returned) {
      RESULTS.push(`  · sign-in debug: url=${current.page.url()} text=${(await current.page.locator('body').innerText()).slice(0, 300).replace(/\s+/g, ' ')}`);
    }
    check('sign-in returns to the exact captured detail route and query',
      returned && current.page.url() === displayed, `returned=${current.page.url()} expected=${displayed}`);
    await current.context.close();
  },

  async function filteredExpiryReturnAndBack(browser) {
    const { context, page } = await open(browser,
      '/operations/queues/my-work?page=2&type=SESSION&status=SCHEDULED&city=1&team=7');
    await page.locator('[data-row-key="SESSION-3124"]').waitFor({ state: 'visible', timeout: 8000 });
    stub.failDetailId = 3124;
    stub.failDetailDelayMs = 400;
    await page.locator('[data-row-key="SESSION-3124"]').click();
    await page.waitForFunction(() => window.location.pathname === '/operations/sessions/3124',
      null, { timeout: 8000 });
    const displayed = page.url();
    await page.waitForFunction(() => window.location.pathname === '/', null, { timeout: 8000 });
    await page.locator('#pathome-auth-email').fill('oe@example.test');
    await page.locator('#pathome-auth-password').fill('test-password');
    await page.getByRole('button', { name: 'Sign In', exact: true }).click();
    await page.waitForFunction(() => window.location.pathname === '/operations/sessions/3124',
      null, { timeout: 10000 });
    check('X1: sign-in restores the filtered page-2 detail exactly',
      page.url() === displayed, `returned=${page.url()} expected=${displayed}`);
    await page.locator('.oe-back').click();
    await page.waitForFunction(() => window.location.pathname === '/operations/queues/my-work',
      null, { timeout: 8000 });
    await page.locator('[data-row-key="SESSION-3124"]').waitFor({ state: 'visible', timeout: 8000 });
    check('X1: Back after sign-in keeps page 2 and the filter context',
      page.url().includes('page=2') && page.url().includes('type=SESSION')
      && page.url().includes('status=SCHEDULED'), page.url());
    await context.close();
  },

  async function rapidNavigationRejectsLateDetail(browser) {
    const { context, page } = await open(browser, '/operations/queues/my-work');
    await page.locator('[data-row-key="REQUEST-5200"]').waitFor({ state: 'visible', timeout: 8000 });
    stub.holdDetailId = 5200;
    await page.locator('[data-row-key="REQUEST-5200"]').click();
    await page.waitForFunction(() => window.location.pathname === '/operations/requests/5200',
      null, { timeout: 8000 });
    await page.locator('.oe-back').click();
    await page.waitForFunction(() => window.location.pathname === '/operations/queues/my-work',
      null, { timeout: 8000 });
    await waitFor(() => stub.heldDetails.length > 0);
    stub.heldDetails.splice(0).forEach((release) => release());
    await page.waitForTimeout(500);
    check('a late detail response cannot overwrite the newer queue route',
      (await page.locator('.oe-detail-head').count()) === 0
      && (await page.locator('[data-row-key="REQUEST-5200"]').isVisible()));
    await context.close();
  },

  async function filterDependenciesAndNavigationTotals(browser) {
    const { context, page } = await open(browser,
      '/operations/queues/my-work?type=REQUEST&status=RECEIVED&ref=5200&city=1&team=7');
    await page.locator('[data-row-key="REQUEST-5200"]').waitFor({ state: 'visible', timeout: 8000 });
    const navTotal = await page.locator('.oe-sidebar-btn', { hasText: 'My work' })
      .locator('.oe-nav-count').innerText();
    check('destination navigation shows the authorized unfiltered total', navTotal === '28', navTotal);

    await page.getByRole('button', { name: 'Remove exact record filter' }).click();
    await page.waitForFunction(() => !window.location.search.includes('ref='), null, { timeout: 8000 });
    const afterRecord = requests.filter((path) => path.startsWith('/api/v1/operations/my-work')).at(-1) ?? '';
    check('removing the exact record preserves type and status in the URL',
      page.url().includes('type=REQUEST') && page.url().includes('status=RECEIVED'), page.url());
    check('removing the exact record dispatches a backend-valid query',
      afterRecord.includes('targetType=REQUEST') && afterRecord.includes('status=RECEIVED')
      && !afterRecord.includes('targetId='), afterRecord);

    await page.getByRole('button', { name: 'Remove status filter' }).click();
    await page.waitForFunction(() => !window.location.search.includes('status='), null, { timeout: 8000 });
    check('removing the status chip preserves type, City and Team',
      page.url().includes('type=REQUEST') && page.url().includes('city=1')
      && page.url().includes('team=7'), page.url());

    await page.getByRole('button', { name: 'Remove team filter' }).click();
    await page.waitForFunction(() => !window.location.search.includes('team='), null, { timeout: 8000 });
    check('removing the Team chip preserves type and City independently',
      page.url().includes('type=REQUEST') && page.url().includes('city=1')
      && !page.url().includes('team='), page.url());

    await page.getByRole('button', { name: 'Remove record type filter' }).click();
    await page.waitForFunction(() => !window.location.search.includes('type='), null, { timeout: 8000 });
    check('removing the target type also clears dependent status and record',
      !page.url().includes('status=') && !page.url().includes('ref='), page.url());
    check('removing the target type preserves the independent City filter',
      page.url().includes('city=1'), page.url());

    await page.getByRole('button', { name: 'Remove city filter' }).click();
    await page.waitForFunction(() => !window.location.search.includes('city='), null, { timeout: 8000 });
    check('removing the final City chip clears the filter query',
      page.url().endsWith('/operations/queues/my-work'), page.url());
    await context.close();

    const noMatch = await open(browser, '/operations/queues/my-work?type=SESSION&status=COMPLETED');
    await noMatch.page.getByText('No records match these filters').waitFor({ state: 'visible', timeout: 8000 });
    const filteredNav = await noMatch.page.locator('.oe-sidebar-btn', { hasText: 'My work' })
      .locator('.oe-nav-count').innerText();
    check('a zero-match filter does not turn the destination total into zero',
      filteredNav === '28', filteredNav);
    await noMatch.page.locator('.oe-sidebar-btn', { hasText: 'Available work' }).click();
    await waitVisible(noMatch.page, '.oe-filters');
    await noMatch.page.getByLabel('Record type').selectOption('REQUEST');
    await noMatch.page.getByRole('button', { name: 'Apply filters' }).click();
    await noMatch.page.waitForFunction(() => window.location.search.includes('type=REQUEST'),
      null, { timeout: 8000 });
    const availableNav = await noMatch.page.locator('.oe-sidebar-btn', { hasText: 'Available work' })
      .locator('.oe-nav-count').innerText();
    const availableCount = await noMatch.page.locator('.oe-filter-count').innerText();
    check('switching destinations keeps each authorized unfiltered total separate',
      availableNav === '2' && availableCount.includes('1') && availableCount.includes('2'),
      `nav=${availableNav}, filter=${availableCount}`);
    await noMatch.context.close();
  },

  async function serverFailureRetryKeepsFilterIntent(browser) {
    const { context, page } = await open(browser,
      '/operations/queues/my-work?type=REQUEST&status=RECEIVED&ref=5200',
      { configureStub: () => { stub.failWork = true; } });
    await page.getByText(/couldn’t load my work/).waitFor({ state: 'visible', timeout: 8000 });
    check('a 5xx queue response is reported instead of rendered as empty work',
      await page.getByText(/couldn’t load my work/).isVisible());
    stub.failWork = false;
    await page.getByRole('button', { name: 'Try again' }).click();
    await page.locator('[data-row-key="REQUEST-5200"]').waitFor({ state: 'visible', timeout: 8000 });
    const retryRead = requests.filter((path) => path.startsWith('/api/v1/operations/my-work')).at(-1) ?? '';
    check('retry preserves the valid current filter intent',
      page.url().includes('type=REQUEST') && page.url().includes('status=RECEIVED')
      && retryRead.includes('targetType=REQUEST') && retryRead.includes('targetId=5200'), retryRead);
    await context.close();
  },

  async function effectiveScopeChangesWithholdStaleWork(browser) {
    const { context, page } = await open(browser, '/operations/queues/my-work');
    await page.locator('[data-row-key="REQUEST-5200"]').waitFor({ state: 'visible', timeout: 8000 });
    stub.holdMyWork = true;
    await page.locator('.oe-sidebar-btn', { hasText: 'Overview' }).click();
    await page.locator('.oe-sidebar-btn', { hasText: 'My work' }).click();
    await waitFor(() => stub.heldMyWork.length > 0);
    stub.grants = [GRANT_B];
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    const oldTeamWithdrawn = await waitFor(async () =>
      (await page.locator('[data-row-key="REQUEST-5200"]').count()) === 0, 5000);
    check('A+B to B withdraws the old Team A queue immediately', oldTeamWithdrawn);
    stub.heldMyWork.splice(0).forEach((release) => release());
    await page.locator('[data-row-key="SESSION-3300"]').waitFor({ state: 'visible', timeout: 8000 });
    await page.waitForTimeout(350);
    check('a late Team A response cannot republish old data',
      (await page.locator('[data-row-key="REQUEST-5200"]').count()) === 0);
    check('remaining Team B work is fetched afresh and usable',
      await page.locator('[data-row-key="SESSION-3300"]').isVisible());
    await context.close();

    const detail = await open(browser, '/operations/queues/my-work');
    await detail.page.locator('[data-row-key="REQUEST-5200"]').waitFor({ state: 'visible', timeout: 8000 });
    await detail.page.locator('[data-row-key="REQUEST-5200"]').click();
    await detail.page.locator('.oe-detail-head').waitFor({ state: 'visible', timeout: 8000 });
    stub.grants = [GRANT_B];
    await detail.page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await detail.page.locator('[data-row-key="SESSION-3300"]').waitFor({ state: 'visible', timeout: 8000 });
    check('an open Team A detail is removed when Team A authority disappears',
      (await detail.page.locator('.oe-detail-head').count()) === 0
      && (await detail.page.locator('[data-row-key="REQUEST-5200"]').count()) === 0);
    await detail.context.close();

    const draft = await open(browser, '/operations/sessions/3400');
    await draft.page.locator('.oe-detail-head').waitFor({ state: 'visible', timeout: 8000 });
    await draft.page.locator('#oe-avail-btn-9401').click();
    await draft.page.locator('.oe-avail-editor').waitFor({ state: 'visible', timeout: 8000 });
    await draft.page.locator('#oe-avail-9401-start').fill('2026-10-20T10:00');
    stub.grants = [GRANT_B];
    await draft.page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await draft.page.locator('[data-row-key="SESSION-3300"]').waitFor({ state: 'visible', timeout: 8000 });
    check('a scope change clears an unsaved availability draft with its old-scope detail',
      (await draft.page.locator('.oe-avail-editor').count()) === 0
      && (await draft.page.locator('.oe-detail-head').count()) === 0);
    await draft.context.close();

    const lateDetail = await open(browser, '/operations/queues/my-work');
    await lateDetail.page.locator('[data-row-key="REQUEST-5200"]').waitFor({ state: 'visible', timeout: 8000 });
    stub.holdDetailId = 5200;
    await lateDetail.page.locator('[data-row-key="REQUEST-5200"]').click();
    await waitFor(() => stub.heldDetails.length > 0);
    stub.grants = [GRANT_B];
    await lateDetail.page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await lateDetail.page.locator('[data-row-key="SESSION-3300"]').waitFor({ state: 'visible', timeout: 8000 });
    stub.heldDetails.splice(0).forEach((release) => release());
    await lateDetail.page.waitForTimeout(400);
    check('a delayed old-scope detail response cannot reopen after invalidation',
      (await lateDetail.page.locator('.oe-detail-head').count()) === 0
      && (await lateDetail.page.locator('[data-row-key="REQUEST-5200"]').count()) === 0);
    await lateDetail.context.close();

    const unchanged = await open(browser, '/operations/queues/my-work');
    await unchanged.page.locator('[data-row-key="REQUEST-5200"]').waitFor({ state: 'visible', timeout: 8000 });
    await unchanged.page.locator('[data-row-key="REQUEST-5200"]').click();
    await unchanged.page.locator('.oe-detail-head').waitFor({ state: 'visible', timeout: 8000 });
    stub.grants = [
      GRANT_B,
      { ...GRANT_A },
      { ...GRANT_A },
      { capability: 'REPORTS_VIEW', scopeType: 'GLOBAL', cityId: null, teamId: null }
    ];
    await unchanged.page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await unchanged.page.waitForTimeout(500);
    check('reordered/duplicated grants and irrelevant capabilities keep valid work in place',
      await unchanged.page.locator('.oe-detail-head').isVisible());
    await unchanged.page.waitForTimeout(500);
    stub.grants = [];
    await unchanged.page.evaluate(() => window.dispatchEvent(new Event('focus')));
    const denied = await unchanged.page.getByText(/don’t have access to Operations/)
      .waitFor({ state: 'visible', timeout: 8000 }).then(() => true).catch(() => false);
    check('complete revocation still fails closed', denied);
    await unchanged.context.close();
  },

  async function neutralTypeAndTruthfulLifecycle(browser) {
    const draft = await open(browser, '/operations/sessions/3400');
    await draft.page.locator('.oe-detail-head').waitFor({ state: 'visible', timeout: 8000 });
    const typeStyle = draft.page.locator('.oe-type--session');
    check('DRAFT Visit plan type has no success fill',
      (await containerBackground(typeStyle)) === 'transparent');
    check('DRAFT Visit plan type keeps the neutral approved text color',
      (await typeStyle.evaluate((node) => getComputedStyle(node).color)) === 'rgb(98, 103, 95)');
    check('DRAFT lifecycle still says Planning',
      (await draft.page.getByText('Planning', { exact: true }).count()) > 0);
    check('stale inactive media is rejected by the mounted thumbnail guard',
      (await draft.page.locator('.oe-item img').count()) === 0
      && (await draft.page.locator('a.oe-thumb--link[href="/property/7401"]').count()) === 0);
    await draft.context.close();

    const broken = await open(browser, '/operations/sessions/3402');
    await broken.page.locator('.oe-detail-head').waitFor({ state: 'visible', timeout: 8000 });
    const unavailable = await broken.page.locator('.oe-thumb-empty[aria-label="Property photo unavailable"]')
      .first().waitFor({ state: 'visible', timeout: 8000 }).then(() => true).catch(() => false);
    check('a broken ACTIVE cover renders an explicit unavailable fallback', unavailable);
    await broken.context.close();

    const mobile = await open(browser, '/operations/sessions/3400', { viewport: { width: 390, height: 844 } });
    await mobile.page.locator('.oe-detail-head').waitFor({ state: 'visible', timeout: 8000 });
    const mobileOverflow = await mobile.page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    const mobileNeutral = (await containerBackground(mobile.page.locator('.oe-type--session'))) === 'transparent';
    check('X6: 390px detail keeps neutral type, truthful Planning status and no overflow',
      mobileNeutral && mobileOverflow <= 0
      && (await mobile.page.getByText('Planning', { exact: true }).count()) > 0,
      `overflow=${mobileOverflow}`);
    await mobile.context.close();

    for (const [id, expected] of [[3410, 'Planning'], [3411, 'Visit booked'], [3412, 'Visit in progress'],
      [3413, 'Visit completed'], [3414, 'Interrupted']]) {
      const page = await open(browser, `/operations/sessions/${id}`);
      await page.page.locator('.oe-detail-head').waitFor({ state: 'visible', timeout: 8000 });
      const transparent = (await containerBackground(page.page.locator('.oe-type--session'))) === 'transparent';
      const label = (await page.page.getByText(expected, { exact: true }).count()) > 0;
      check(`${expected}: Visit plan type stays neutral and lifecycle is truthful`,
        transparent && label, `typeNeutral=${transparent}, label=${label}`);
      await page.context.close();
    }
  }
];

const main = async () => {
  const browser = await chromium.launch({ channel: 'chromium' });
  for (const scenario of scenarios) {
    RESULTS.push(`\n▶ ${scenario.name}`);
    try {
      await scenario(browser);
    } catch (error) {
      failures += 1;
      RESULTS.push(`  ✖ scenario threw: ${error.message}`);
    }
  }
  await browser.close();
  console.log(RESULTS.join('\n'));
  console.log(`\n${checks} checks, ${failures} failure(s) — ${failures === 0 ? 'ALL BROWSER CHECKS PASSED' : 'BROWSER CHECKS FAILED'}`);
  process.exitCode = failures === 0 ? 0 : 1;
};

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
