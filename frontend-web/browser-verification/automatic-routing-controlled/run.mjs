/**
 * Controlled Chromium pass for the approved automatic-routing and fixed-grant UI.
 *
 * The browser is real, but every API answer is a controlled fixture. This pass proves responsive
 * layout, target sizing, confirmation, account/session fencing and wire payloads. It is not a
 * substitute for the required genuine Spring Boot/PostgreSQL journey.
 */
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';
import { fixedGrantRequestError } from './grantFixtureContract.mjs';

const { chromium } = playwright;
const DIST = fileURLToPath(new URL('../../dist/', import.meta.url));
const contentTypes = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon'
};

const server = http.createServer(async (request, response) => {
  try {
    const pathname = decodeURIComponent(new URL(request.url, 'http://local').pathname);
    const requested = normalize(join(DIST, pathname));
    const path = requested.startsWith(DIST) && extname(requested) ? requested : join(DIST, 'index.html');
    const body = await readFile(path);
    response.writeHead(200, {
      'Content-Type': contentTypes[extname(path)] ?? 'application/octet-stream',
      'Cache-Control': 'no-store'
    });
    response.end(body);
  } catch {
    response.writeHead(404);
    response.end('Not found');
  }
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;

let checks = 0;
let failures = 0;
const check = (label, condition, detail = '') => {
  checks += 1;
  if (condition) console.log(`  PASS  ${label}`);
  else {
    failures += 1;
    console.log(`  FAIL  ${label}${detail ? `  (${detail})` : ''}`);
  }
};

const json = (route, body, status = 200) => route.fulfill({
  status,
  contentType: 'application/json',
  headers: { 'Cache-Control': 'no-store' },
  body: JSON.stringify(body)
});

check('controlled TEAM validation negative control rejects a City FK on the command',
  fixedGrantRequestError({
    capability: 'OPS_COORDINATE',
    scopeType: 'TEAM',
    cityId: 5,
    teamId: 9
  }) !== null);

const routingTaskView = (taskId, cityId = 5) => ({
  taskId, taskVersion: 1, state: 'NEEDS_REVIEW', reasonCode: 'NO_ACTIVE_TEAM_COVERAGE',
  requestId: 1000 + taskId, requestVersion: 1, requestStatus: 'RECEIVED', cityId,
  cityDisplayName: cityId === 5 ? 'Indore' : 'Bhopal', localityId: 13,
  localityDisplayName: `Locality ${taskId}`, listingId: 2000 + taskId,
  listingTitle: `Routing task ${taskId}`, teamId: null, coordinatorUserId: null,
  resultTeamId: null, resultTeamDisplayName: null, resultRequestVersion: null,
  enrolledAt: '2026-10-23T10:00:00Z', updatedAt: '2026-10-23T12:00:00Z',
  initialRoutingEligible: true
});

const coverageView = (id, cityId = 5, teamId = 9) => ({
  id, cityId, cityDisplayName: cityId === 5 ? 'Indore' : 'Bhopal',
  localityId: 13 + id, localityDisplayName: `Coverage locality ${id}`,
  teamId, teamDisplayName: `Team ${teamId}`, active: true, version: 0,
  updatedAt: '2026-10-23T12:00:00Z', changeReasonCode: 'COVERAGE_REVIEWED'
});

const seed = async (page, { role = 'EMPLOYEE', userId = 101, activeTab = '' } = {}) => {
  await page.addInitScript(({ role, userId, activeTab }) => {
    localStorage.setItem('pathome_auth_token', `controlled-token-${userId}`);
    localStorage.setItem('pathome_user', JSON.stringify({
      id: userId,
      email: `controlled-${userId}@example.test`,
      fullName: `Controlled User ${userId}`,
      role,
      freeVisitsUsed: 0,
      walletBalance: 0
    }));
    localStorage.setItem('pathome_role', role);
    if (activeTab) localStorage.setItem('pathome_active_admin_tab', activeTab);
  }, { role, userId, activeTab });
};

const browser = await chromium.launch({
  headless: true,
  executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
});
try {
  const routingContext = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const routingPage = await routingContext.newPage();
  await seed(routingPage);
  let routed = false;
  let routePayload = null;

  await routingPage.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname === '/api/v1/staff/me/capabilities') {
      return json(route, {
        staffActive: true,
        serverTime: '2026-10-23T12:00:00Z',
        capabilities: [
          { capability: 'OPS_ROUTING', scopeType: 'CITY', cityId: 5, cityDisplayName: 'Indore', teamId: null, teamDisplayName: null },
          { capability: 'OPS_COORDINATE', scopeType: 'TEAM', cityId: 5, cityDisplayName: 'Indore', teamId: 9, teamDisplayName: 'Central Homes Team' }
        ]
      });
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/coverage/cities') {
      return json(route, [{ id: 5, displayName: 'Indore', cityId: 5, version: 0 }]);
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/tasks/unclaimed') {
      return json(route, {
        items: [{
          targetType: 'REQUEST', targetId: 77, cityId: 5, cityDisplayName: 'Indore',
          teamId: 9, teamDisplayName: 'Central Homes Team', status: 'RECEIVED',
          createdAt: '2026-10-23T10:00:00Z'
        }],
        totalCount: 1, page: 0, size: 20, totalPages: 1
      });
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/tasks/7/recheck') {
      return json(route, {
        taskId: 7, taskVersion: 4, state: 'PENDING', reasonCode: null,
        requestId: 41, requestVersion: 2, requestStatus: 'RECEIVED', cityId: 5,
        cityDisplayName: 'Indore', localityId: 13, localityDisplayName: 'Vijay Nagar',
        listingId: 21, listingTitle: 'Sunlit 3 BHK', teamId: null, coordinatorUserId: null,
        resultTeamId: null, resultTeamDisplayName: null, resultRequestVersion: null,
        enrolledAt: '2026-10-23T10:00:00Z', updatedAt: '2026-10-23T12:00:00Z',
        initialRoutingEligible: true
      });
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/tasks/7') {
      return json(route, {
        taskId: 7, taskVersion: routed ? 4 : 3, state: routed ? 'DONE' : 'NEEDS_REVIEW',
        reasonCode: routed ? null : 'NO_ACTIVE_TEAM_COVERAGE',
        requestId: 41, requestVersion: routed ? 3 : 2, requestStatus: 'RECEIVED', cityId: 5,
        cityDisplayName: 'Indore', localityId: 13, localityDisplayName: 'Vijay Nagar',
        listingId: 21, listingTitle: 'Sunlit 3 BHK', teamId: routed ? 9 : null,
        coordinatorUserId: null, resultTeamId: routed ? 9 : null,
        resultTeamDisplayName: routed ? 'Central Homes Team' : null,
        resultRequestVersion: routed ? 3 : null, enrolledAt: '2026-10-23T10:00:00Z',
        updatedAt: '2026-10-23T12:00:00Z', initialRoutingEligible: !routed
      });
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/tasks') {
      const items = routed ? [] : [{
        taskId: 7, taskVersion: 3, state: 'NEEDS_REVIEW',
        reasonCode: 'NO_ACTIVE_TEAM_COVERAGE', requestId: 41, requestVersion: 2,
        requestStatus: 'RECEIVED', cityId: 5, cityDisplayName: 'Indore', localityId: 13,
        localityDisplayName: 'Vijay Nagar', listingId: 21, listingTitle: 'Sunlit 3 BHK',
        teamId: null, coordinatorUserId: null, resultTeamId: null,
        resultTeamDisplayName: null, resultRequestVersion: null,
        enrolledAt: '2026-10-23T10:00:00Z', updatedAt: '2026-10-23T12:00:00Z',
        initialRoutingEligible: true
      }];
      return json(route, { items, totalCount: items.length, page: 0, size: 20, totalPages: items.length ? 1 : 0 });
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/coverage/options') {
      return json(route, {
        city: { id: 5, displayName: 'Indore', cityId: 5, version: 0 },
        localities: [
          { id: 13, displayName: 'Vijay Nagar', cityId: 5, version: null },
          { id: 14, displayName: 'Saket Nagar', cityId: 5, version: null }
        ],
        teams: [
          { id: 9, displayName: 'Central Homes Team', cityId: 5, version: 1 },
          { id: 10, displayName: 'East Homes Team', cityId: 5, version: 1 }
        ]
      });
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/coverage/locality') {
      return json(route, [{
        id: 31, cityId: 5, cityDisplayName: 'Indore', localityId: 13,
        localityDisplayName: 'Vijay Nagar', teamId: 10, teamDisplayName: 'East Homes Team',
        active: true, version: 2, updatedAt: '2026-10-23T11:00:00Z',
        changeReasonCode: 'TERRITORY_COVERAGE_UPDATE'
      }]);
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/coverage') {
      if (request.method() === 'POST') {
        return json(route, {
          id: 31, cityId: 5, cityDisplayName: 'Indore', localityId: 13,
          localityDisplayName: 'Vijay Nagar', teamId: 9, teamDisplayName: 'Central Homes Team',
          active: true, version: 0, updatedAt: '2026-10-23T12:00:00Z',
          changeReasonCode: 'TERRITORY_COVERAGE_UPDATE'
        });
      }
      return json(route, {
        items: [],
        totalCount: 0, page: 0, size: 20, totalPages: 0
      });
    }
    if (url.pathname.endsWith('/candidates')) {
      return json(route, {
        request: null,
        city: { id: 5, code: 'indore', displayName: 'Indore', active: true, version: 0 },
        teams: {
          items: [{ id: 9, cityId: 5, code: 'central', displayName: 'Central Homes Team', active: true, version: 1 }],
          totalCount: 1, page: 0, size: 50, totalPages: 1
        }
      });
    }
    if (url.pathname.endsWith('/route') && request.method() === 'POST') {
      routePayload = JSON.parse(request.postData() ?? '{}');
      routed = true;
      return route.fulfill({ status: 204, headers: { 'Cache-Control': 'no-store' } });
    }
    return json(route, { message: 'Controlled endpoint not used' }, 404);
  });

  await routingPage.goto(`${origin}/operations/routing`, { waitUntil: 'domcontentloaded' });
  await routingPage.getByRole('heading', { name: 'City routing and coverage' }).waitFor();
  check('routing-only account reaches the routing surface without the OE gate', true);
  check('exception reason is translated into plain language',
    await routingPage.getByText('No active Team covers this locality.').isVisible());
  check('unclaimed Team work overview is visible',
    await routingPage.getByRole('heading', { name: 'Unclaimed Team work' }).isVisible());

  await routingPage.getByRole('button', { name: /Sunlit 3 BHK/ }).first().click();
  await routingPage.getByRole('button', { name: 'Review Team selection' }).waitFor();
  await routingPage.getByRole('button', { name: 'Review Team selection' }).click();
  check('Team routing requires explicit confirmation',
    await routingPage.getByRole('button', { name: 'Confirm Team routing' }).isVisible());
  await routingPage.getByRole('button', { name: 'Confirm Team routing' }).click();
  await routingPage.getByText(/Routed to Central Homes Team/).waitFor();
  check('route command carries all authoritative versions and no coordinator', Boolean(
    routePayload
    && routePayload.expectedRequestVersion === 2
    && routePayload.expectedCityVersion === 0
    && routePayload.expectedTeamVersion === 1
    && routePayload.expectedRoutingTaskVersion === 3
    && routePayload.coordinatorUserId === undefined
  ));

  await routingPage.getByRole('button', { name: 'Review coverage' }).click();
  await routingPage.getByText(/Adding this Team creates an overlap/).waitFor();
  check('overlap is disclosed before confirming additional coverage',
    await routingPage.getByText(/Adding this Team creates an overlap/).isVisible());

  const widths = [320, 375, 390, 430, 768, 1024, 1280, 1440];
  for (const width of widths) {
    routed = false;
    routePayload = null;
    await routingPage.setViewportSize({ width, height: 900 });
    await routingPage.goto(`${origin}/operations/routing`, { waitUntil: 'domcontentloaded' });
    await routingPage.getByRole('heading', { name: 'City routing and coverage' }).waitFor();
    const layout = await routingPage.evaluate(() => {
      const visible = (element) => {
        const style = getComputedStyle(element);
        const rect = element.getBoundingClientRect();
        return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0;
      };
      const interactive = [...document.querySelectorAll('.routing-page button, .routing-page select')]
        .filter(visible);
      const editable = [...document.querySelectorAll('.routing-page select')].filter(visible);
      return {
        overflow: document.documentElement.scrollWidth - window.innerWidth,
        smallTargets: interactive.filter((element) => element.getBoundingClientRect().height < 44).length,
        smallEditableFonts: editable.filter((element) => parseFloat(getComputedStyle(element).fontSize) < 16).length
      };
    });
    check(`routing ${width}px has no horizontal overflow`, layout.overflow <= 0, `overflow=${layout.overflow}`);
    check(`routing ${width}px controls meet 44px`, layout.smallTargets === 0, `${layout.smallTargets} small`);
    check(`routing ${width}px editable text is 16px+`, layout.smallEditableFonts === 0, `${layout.smallEditableFonts} small`);
  }
  await routingContext.close();

  const switchContext = await browser.newContext({ viewport: { width: 390, height: 900 } });
  const switchPage = await switchContext.newPage();
  await seed(switchPage, { userId: 101 });
  let switched = false;
  let releaseTasks;
  let tasksStarted;
  const tasksStartedPromise = new Promise((resolve) => { tasksStarted = resolve; });
  const delayedTasks = new Promise((resolve) => { releaseTasks = resolve; });
  await switchPage.route('**/api/**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === '/api/v1/staff/me/capabilities') {
      return json(route, switched
        ? { staffActive: true, capabilities: [], serverTime: '2026-10-23T12:00:00Z' }
        : {
            staffActive: true,
            serverTime: '2026-10-23T12:00:00Z',
            capabilities: [
              { capability: 'OPS_ROUTING', scopeType: 'CITY', cityId: 5, cityDisplayName: 'Indore', teamId: null, teamDisplayName: null }
            ]
          });
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/tasks') {
      tasksStarted();
      await delayedTasks;
      return json(route, {
        items: [{
          taskId: 7, taskVersion: 3, state: 'NEEDS_REVIEW', reasonCode: 'NO_ACTIVE_TEAM_COVERAGE',
          requestId: 41, requestVersion: 2, requestStatus: 'RECEIVED', cityId: 5,
          cityDisplayName: 'Indore', localityId: 13, localityDisplayName: 'Vijay Nagar',
          listingId: 21, listingTitle: 'Sunlit 3 BHK', teamId: null, coordinatorUserId: null,
          resultTeamId: null, resultTeamDisplayName: null, resultRequestVersion: null,
          enrolledAt: '2026-10-23T10:00:00Z', updatedAt: '2026-10-23T12:00:00Z',
          initialRoutingEligible: true
        }],
        totalCount: 1, page: 0, size: 20, totalPages: 1
      });
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/coverage/cities') {
      return json(route, [{ id: 5, displayName: 'Indore', cityId: 5, version: 0 }]);
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/tasks/unclaimed') {
      return json(route, { items: [], totalCount: 0, page: 0, size: 20, totalPages: 0 });
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/coverage') {
      return json(route, { items: [], totalCount: 0, page: 0, size: 20, totalPages: 0 });
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/coverage/options') {
      return json(route, {
        city: { id: 5, displayName: 'Indore', cityId: 5, version: 0 },
        localities: [], teams: []
      });
    }
    return json(route, { message: 'Controlled endpoint not used' }, 404);
  });
  await switchPage.goto(`${origin}/operations/routing`, { waitUntil: 'domcontentloaded' });
  await switchPage.getByRole('heading', { name: 'City routing and coverage' }).waitFor();
  await tasksStartedPromise;
  switched = true;
  await switchPage.evaluate(() => {
    localStorage.setItem('pathome_auth_token', 'controlled-token-202');
    localStorage.setItem('pathome_user', JSON.stringify({
      id: 202, email: 'controlled-202@example.test', fullName: 'Controlled User 202',
      role: 'EMPLOYEE', freeVisitsUsed: 0, walletBalance: 0
    }));
    window.dispatchEvent(new Event('pathome_auth_changed'));
  });
  releaseTasks();
  await switchPage.waitForTimeout(500);
  check('late routing response cannot repopulate a switched account',
    (await switchPage.getByText('Sunlit 3 BHK').count()) === 0);
  await switchContext.close();

  const routingMutationRaceContext = await browser.newContext({
    viewport: { width: 1024, height: 900 }
  });
  const routingMutationRacePage = await routingMutationRaceContext.newPage();
  await seed(routingMutationRacePage, { userId: 151 });
  await routingMutationRacePage.addInitScript(() => {
    const nativeFetch = window.fetch.bind(window);
    window.fetch = (input, init) => {
      const url = typeof input === 'string' ? input : input.url;
      if (url.endsWith('/route') && (init?.method ?? 'GET') === 'POST') {
        const { signal, ...withoutAbort } = init ?? {};
        return nativeFetch(input, withoutAbort);
      }
      return nativeFetch(input, init);
    };
  });
  let routingRouteCalls = 0;
  let routingRouted = false;
  let releaseOldRoute;
  let releaseCurrentRoute;
  let oldRouteStarted;
  let currentRouteStarted;
  const oldRouteGate = new Promise((resolve) => { releaseOldRoute = resolve; });
  const currentRouteGate = new Promise((resolve) => { releaseCurrentRoute = resolve; });
  const oldRouteStartedPromise = new Promise((resolve) => { oldRouteStarted = resolve; });
  const currentRouteStartedPromise = new Promise((resolve) => { currentRouteStarted = resolve; });
  await routingMutationRacePage.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname === '/api/v1/staff/me/capabilities') {
      return json(route, { staffActive: true, serverTime: '2026-10-23T12:00:00Z',
        capabilities: [{ capability: 'OPS_ROUTING', scopeType: 'CITY', cityId: 5,
          cityDisplayName: 'Indore', teamId: null, teamDisplayName: null }] });
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/coverage/cities') {
      return json(route, [{ id: 5, displayName: 'Indore', cityId: 5, version: 0 }]);
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/coverage/options') {
      return json(route, {
        city: { id: 5, displayName: 'Indore', cityId: 5, version: 0 },
        localities: [{ id: 13, displayName: 'Vijay Nagar', cityId: 5, version: null }],
        teams: [{ id: 9, displayName: 'Central Homes Team', cityId: 5, version: 1 }]
      });
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/coverage/locality') {
      return json(route, []);
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/coverage') {
      return json(route, { items: [], totalCount: 0, page: 0, size: 20, totalPages: 0 });
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/tasks/unclaimed') {
      return json(route, { items: [], totalCount: 0, page: 0, size: 20, totalPages: 0 });
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/tasks') {
      const items = routingRouted ? [] : [routingTaskView(7)];
      return json(route, {
        items, totalCount: items.length, page: 0, size: 20, totalPages: items.length ? 1 : 0
      });
    }
    if (url.pathname.endsWith('/candidates')) {
      return json(route, {
        request: null,
        city: { id: 5, code: 'indore', displayName: 'Indore', active: true, version: 0 },
        teams: { items: [{ id: 9, cityId: 5, code: 'central',
          displayName: 'Central Homes Team', active: true, version: 1 }],
        totalCount: 1, page: 0, size: 50, totalPages: 1 }
      });
    }
    if (url.pathname.endsWith('/route') && request.method() === 'POST') {
      routingRouteCalls += 1;
      if (routingRouteCalls === 1) {
        oldRouteStarted();
        await oldRouteGate;
        return route.fulfill({ status: 204, headers: { 'Cache-Control': 'no-store' } });
      }
      currentRouteStarted();
      await currentRouteGate;
      routingRouted = true;
      return route.fulfill({ status: 204, headers: { 'Cache-Control': 'no-store' } });
    }
    return json(route, { message: 'Controlled endpoint not used' }, 404);
  });
  await routingMutationRacePage.goto(`${origin}/operations/routing`, {
    waitUntil: 'domcontentloaded'
  });
  await routingMutationRacePage.getByRole('heading', { name: 'City routing and coverage' }).waitFor();
  await routingMutationRacePage.getByRole('button', { name: /Routing task 7/ }).first().click();
  await routingMutationRacePage.getByRole('button', { name: 'Review Team selection' }).click();
  await routingMutationRacePage.getByRole('button', { name: 'Confirm Team routing' }).click();
  await oldRouteStartedPromise;
  await routingMutationRacePage.evaluate(() => {
    localStorage.setItem('pathome_auth_token', 'controlled-token-152');
    localStorage.setItem('pathome_user', JSON.stringify({
      id: 152, fullName: 'Controlled User 152', role: 'EMPLOYEE'
    }));
    window.dispatchEvent(new Event('pathome_auth_changed'));
  });
  await routingMutationRacePage.getByRole('button', { name: /Routing task 7/ }).first().waitFor();
  await routingMutationRacePage.getByRole('button', { name: /Routing task 7/ }).first().click();
  await routingMutationRacePage.getByRole('button', { name: 'Review Team selection' }).click();
  await routingMutationRacePage.getByRole('button', { name: 'Confirm Team routing' }).click();
  await currentRouteStartedPromise;
  releaseOldRoute();
  await routingMutationRacePage.waitForTimeout(250);
  check('old routing mutation finalizer cannot clear the current routing busy slot',
    await routingMutationRacePage.getByRole('button', { name: 'Routing…' }).isDisabled());
  releaseCurrentRoute();
  await routingMutationRacePage.getByText(/Routed to Central Homes Team/).waitFor();
  check('replacement account routing operation completes through its own controller',
    routingRouteCalls === 2);
  await routingMutationRaceContext.close();

  const staffContext = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const staffPage = await staffContext.newPage();
  await seed(staffPage, { role: 'ADMIN', userId: 501, activeTab: 'staff-permissions' });
  let grantPayload = null;
  let grantCreated = false;
  await staffPage.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname === '/api/v1/staff/me/capabilities') {
      return json(route, {
        staffActive: true,
        serverTime: '2026-10-23T12:00:00Z',
        capabilities: [{ capability: 'STAFF_ADMIN', scopeType: 'GLOBAL', cityId: null, cityDisplayName: null, teamId: null, teamDisplayName: null }]
      });
    }
    if (url.pathname === '/api/v1/staff/admin/operating-teams/cities') {
      return json(route, { items: [{ id: 5, code: 'indore', displayName: 'Indore', active: true, version: 0 }], totalCount: 1, page: 0, size: 100, totalPages: 1 });
    }
    if (url.pathname === '/api/v1/staff/admin/cities/5/operating-teams') {
      return json(route, {
        city: { id: 5, code: 'indore', displayName: 'Indore', active: true, version: 0 },
        items: [{ id: 9, cityId: 5, code: 'central', displayName: 'Central Homes Team', active: true, version: 1 }],
        totalCount: 1, page: 0, size: 100, totalPages: 1
      });
    }
    if (url.pathname === '/api/v1/staff/admin/users' && request.method() === 'GET') {
      return json(route, {
        items: [{ userId: 101, email: 'controlled-101@example.test', fullName: 'Controlled User 101', profileExists: true, staffActive: true }],
        totalCount: 1, page: 0, size: 20, totalPages: 1
      });
    }
    if (url.pathname === '/api/v1/staff/admin/users/101' && request.method() === 'GET') {
      return json(route, {
        userId: 101, profileExists: true, staffActive: true, version: 2,
        staffActivatedAt: '2026-10-01T10:00:00Z', staffDeactivatedAt: null,
        grants: [{ id: 50, capability: 'OPS_COORDINATE', scopeType: 'TEAM', cityId: 5,
          cityDisplayName: 'Indore', teamId: 9, teamDisplayName: 'Central Homes Team',
          effectiveAt: '2026-10-01T10:00:00Z', expiresAt: null, revokedAt: null,
          createdAt: '2026-10-01T10:00:00Z', provisioningSource: 'ADMIN_API' },
        ...(grantCreated && grantPayload ? [{
          id: 51, capability: grantPayload.capability, scopeType: grantPayload.scopeType,
          cityId: grantPayload.cityId, cityDisplayName: 'Indore', teamId: grantPayload.teamId,
          teamDisplayName: null, effectiveAt: '2026-10-23T12:00:00Z',
          expiresAt: grantPayload.expiresAt, revokedAt: null,
          createdAt: '2026-10-23T12:00:00Z', provisioningSource: 'ADMIN_API'
        }] : [])]
      });
    }
    if (url.pathname === '/api/v1/staff/admin/users/101/grants' && request.method() === 'POST') {
      grantPayload = JSON.parse(request.postData() ?? '{}');
      const invalid = fixedGrantRequestError(grantPayload);
      if (invalid) return json(route, { message: invalid }, 400);
      grantCreated = true;
      return json(route, {
        id: 51, capability: grantPayload.capability, scopeType: grantPayload.scopeType,
        cityId: grantPayload.cityId, cityDisplayName: 'Indore', teamId: grantPayload.teamId,
        teamDisplayName: null, effectiveAt: '2026-10-23T12:00:00Z', expiresAt: null,
        revokedAt: null, createdAt: '2026-10-23T12:00:00Z', provisioningSource: 'ADMIN_API'
      });
    }
    return json(route, { message: 'Controlled endpoint not used' }, 404);
  });
  await staffPage.goto(`${origin}/admin`, { waitUntil: 'domcontentloaded' });
  await staffPage.getByRole('heading', { name: 'Staff permissions' }).waitFor();
  await staffPage.getByLabel('User ID, name, or email').fill('controlled');
  await staffPage.getByRole('button', { name: 'Search' }).click();
  await staffPage.getByRole('button', { name: /Controlled User 101/ }).click();
  await staffPage.getByRole('article').filter({ hasText: 'Team coordination' }).first().waitFor();
  await staffPage.locator('.staff-assign select').first().selectOption('OPS_ROUTING');
  await staffPage.getByLabel('Reason code').last().fill('CITY_ROUTING_COVERAGE');
  await staffPage.getByRole('button', { name: 'Review and confirm' }).click();
  check('fixed-grant UI reviews the exact City route responsibility before saving',
    await staffPage.getByText(/City routing and exceptions: Indore only/).isVisible());
  await staffPage.getByRole('button', { name: 'Confirm assignment' }).click();
  check('fixed-grant UI sends one fixed OPS_ROUTING City grant',
    grantPayload?.capability === 'OPS_ROUTING'
    && grantPayload?.scopeType === 'CITY'
    && grantPayload?.cityId === 5
    && grantPayload?.teamId === null);
  check('fixed-grant UI accepts a bounded operator reason', grantPayload?.reasonCode === 'CITY_ROUTING_COVERAGE');
  await staffPage.waitForFunction(() => {
    const text = document.querySelector('.staff-permissions-notice')?.textContent ?? '';
    return text.includes('is recorded and visible in current access')
      || text.includes('did not show that exact fact')
      || text.includes('outcome is uncertain');
  });
  const grantNotice = await staffPage.locator('.staff-permissions-notice').innerText();
  console.log(`  INFO  staff grant result: ${grantNotice}`);
  check('fixed-grant success requires exact authoritative readback',
    grantNotice.includes('is recorded and visible in current access'));

  for (const width of [320, 375, 390, 430, 768, 1024, 1280, 1440]) {
    await staffPage.setViewportSize({ width, height: 900 });
    const layout = await staffPage.evaluate(() => ({
      overflow: document.documentElement.scrollWidth - window.innerWidth,
      smallTargets: [...document.querySelectorAll('.staff-permissions button, .staff-permissions input, .staff-permissions select')]
        .filter((element) => element.getBoundingClientRect().height > 0)
        .filter((element) => element.getBoundingClientRect().height < 44).length
    }));
    check(`staff permissions ${width}px has no horizontal overflow`, layout.overflow <= 0, `overflow=${layout.overflow}`);
    check(`staff permissions ${width}px targets meet 44px`, layout.smallTargets === 0, `${layout.smallTargets} small`);
  }
  await staffContext.close();

  const paginationContext = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const paginationPage = await paginationContext.newPage();
  await seed(paginationPage, { userId: 701 });
  let routed21 = false;
  let unclaimedEmptied = false;
  const unclaimedRequests = [];
  let route21Payload = null;
  let route21Path = null;
  await paginationPage.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname === '/api/v1/staff/me/capabilities') {
      return json(route, {
        staffActive: true, serverTime: '2026-10-23T12:00:00Z',
        capabilities: [{ capability: 'OPS_ROUTING', scopeType: 'CITY', cityId: 5,
          cityDisplayName: 'Indore', teamId: null, teamDisplayName: null }]
      });
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/coverage/cities') {
      return json(route, [{ id: 5, displayName: 'Indore', cityId: 5, version: 0 }]);
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/tasks/unclaimed') {
      const page = Number(url.searchParams.get('page') ?? 0);
      unclaimedRequests.push(page);
      if (unclaimedEmptied) {
        return json(route, { items: [], totalCount: 0, page, size: 20, totalPages: 0 });
      }
      const items = page === 0 ? Array.from({ length: 20 }, (_, index) => ({
        targetType: 'REQUEST', targetId: index + 1, cityId: 5, cityDisplayName: 'Indore',
        teamId: 9, teamDisplayName: 'Central Homes Team', status: 'RECEIVED',
        createdAt: '2026-10-23T10:00:00Z'
      })) : [{ targetType: 'REQUEST', targetId: 21, cityId: 5, cityDisplayName: 'Indore',
        teamId: 9, teamDisplayName: 'Central Homes Team', status: 'RECEIVED',
        createdAt: '2026-10-23T10:00:00Z' }];
      return json(route, { items, totalCount: 21, page, size: 20, totalPages: 2 });
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/tasks') {
      const page = Number(url.searchParams.get('page') ?? 0);
      const items = page === 0
        ? Array.from({ length: 20 }, (_, index) => routingTaskView(index + 1))
        : routed21 ? [] : [routingTaskView(21)];
      return json(route, {
        items, totalCount: routed21 ? 20 : 21, page, size: 20,
        totalPages: routed21 ? 1 : 2
      });
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/coverage/options') {
      return json(route, {
        city: { id: 5, displayName: 'Indore', cityId: 5, version: 0 },
        localities: [{ id: 13, displayName: 'Vijay Nagar', cityId: 5, version: null }],
        teams: [{ id: 9, displayName: 'Central Homes Team', cityId: 5, version: 1 }]
      });
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/coverage/locality') {
      return json(route, []);
    }
    if (url.pathname === '/api/v1/operations/automatic-routing/coverage') {
      const page = Number(url.searchParams.get('page') ?? 0);
      const items = page === 0
        ? Array.from({ length: 20 }, (_, index) => coverageView(index + 1))
        : [coverageView(21)];
      return json(route, { items, totalCount: 21, page, size: 20, totalPages: 2 });
    }
    if (url.pathname.endsWith('/candidates')) {
      return json(route, {
        request: null, city: { id: 5, code: 'indore', displayName: 'Indore', active: true, version: 0 },
        teams: { items: [{ id: 9, cityId: 5, code: 'central', displayName: 'Central Homes Team',
          active: true, version: 1 }], totalCount: 1, page: 0, size: 50, totalPages: 1 }
      });
    }
    if (url.pathname.endsWith('/route') && request.method() === 'POST') {
      route21Path = url.pathname;
      route21Payload = JSON.parse(request.postData() ?? '{}');
      routed21 = true;
      return route.fulfill({ status: 204, headers: { 'Cache-Control': 'no-store' } });
    }
    return json(route, { message: 'Controlled endpoint not used' }, 404);
  });
  await paginationPage.goto(`${origin}/operations/routing`, { waitUntil: 'domcontentloaded' });
  await paginationPage.getByRole('heading', { name: 'City routing and coverage' }).waitFor();
  const taskPages = paginationPage.getByRole('navigation', { name: 'Routing queue pages' });
  await taskPages.getByRole('button', { name: 'Next' }).click();
  await paginationPage.getByText('Routing task 21').first().waitFor();
  check('routing queue reaches record 21 through bounded pagination', true);
  const coveragePages = paginationPage.getByRole('navigation', { name: 'Coverage pages' });
  await coveragePages.getByRole('button', { name: 'Next' }).click();
  await paginationPage.getByText('Coverage locality 21').waitFor();
  check('coverage reaches record 21 through bounded pagination', true);
  const unclaimedPages = paginationPage.getByRole('navigation', { name: 'Unclaimed Team work pages' });
  await unclaimedPages.getByRole('button', { name: 'Next' }).click();
  await paginationPage.getByText('Visit Request #21').waitFor();
  check('unclaimed oversight reaches record 21 through bounded pagination', true);
  await paginationPage.getByRole('button', { name: /Routing task 21/ }).first().click();
  await paginationPage.getByRole('button', { name: 'Review Team selection' }).waitFor();
  await paginationPage.getByRole('button', { name: 'Review Team selection' }).click();
  await paginationPage.getByRole('button', { name: 'Confirm Team routing' }).click();
  await paginationPage.waitForFunction(() => {
    const text = document.querySelector('.routing-notice')?.textContent ?? '';
    return text.includes('Routed to Central Homes Team') || text.includes('uncertain');
  });
  console.log(`  INFO  routing 21 payload: ${JSON.stringify(route21Payload)}`);
  check('the 21st routing exception can be acted on with its authoritative versions',
    route21Path === '/api/v1/operations/intake-routing/visit-requests/1021/route'
    && route21Payload?.expectedRoutingTaskVersion === 1);
  await paginationPage.getByRole('navigation', { name: 'Routing queue pages' })
    .getByText('Page 1 of 1').waitFor();
  check('an emptied final routing page clamps back to the last remaining page', true);
  unclaimedEmptied = true;
  await paginationPage.getByRole('button', { name: 'Refresh' }).click();
  await paginationPage.getByText('No unclaimed Team work is currently visible in this scope.').waitFor();
  const emptyUnclaimedNavigation = paginationPage.getByRole('navigation', {
    name: 'Unclaimed Team work pages'
  });
  check('a zero-page unclaimed collection displays page index zero',
    (await emptyUnclaimedNavigation.innerText()).includes('Page 1 of 1'));
  await paginationPage.getByRole('button', { name: 'Refresh' }).click();
  await paginationPage.waitForTimeout(200);
  check('a zero-page unclaimed reference does not request the former page again',
    unclaimedRequests.at(-1) === 0, JSON.stringify(unclaimedRequests));
  await paginationContext.close();

  const pickerContext = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const pickerPage = await pickerContext.newPage();
  await seed(pickerPage, { role: 'ADMIN', userId: 801, activeTab: 'staff-permissions' });
  let releaseInitialCityA;
  const initialCityAGate = new Promise((resolve) => { releaseInitialCityA = resolve; });
  let cityACalls = 0;
  let teamAStarted;
  const teamAStartedPromise = new Promise((resolve) => { teamAStarted = resolve; });
  let pickerGrantCount = 0;
  let pickerGrantPayload = null;
  await pickerPage.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname === '/api/v1/staff/me/capabilities') {
      return json(route, {
        staffActive: true, serverTime: '2026-10-23T12:00:00Z',
        capabilities: [{ capability: 'STAFF_ADMIN', scopeType: 'GLOBAL', cityId: null,
          cityDisplayName: null, teamId: null, teamDisplayName: null }]
      });
    }
    if (url.pathname === '/api/v1/staff/admin/operating-teams/cities') {
      return json(route, { items: [
        { id: 5, code: 'indore', displayName: 'Indore', active: true, version: 0 },
        { id: 6, code: 'bhopal', displayName: 'Bhopal', active: true, version: 0 },
        { id: 7, code: 'empty', displayName: 'Empty Teams City', active: true, version: 0 },
        { id: 8, code: 'failed', displayName: 'Failed Teams City', active: true, version: 0 }
      ], totalCount: 4, page: 0, size: 100, totalPages: 1 });
    }
    if (url.pathname === '/api/v1/staff/admin/cities/5/operating-teams') {
      cityACalls += 1;
      if (cityACalls === 1) {
        teamAStarted();
        await initialCityAGate;
      }
      return json(route, { items: [
        { id: 11, cityId: 5, code: 'central', displayName: 'City A old Team', active: true, version: 1 },
        { id: 15, cityId: 5, code: 'west', displayName: 'City A current Team', active: true, version: 1 }
      ], totalCount: 2, page: 0, size: 100, totalPages: 1 });
    }
    if (url.pathname === '/api/v1/staff/admin/cities/6/operating-teams') {
      return json(route, { items: [
        { id: 12, cityId: 6, code: 'north', displayName: 'City B Team', active: true, version: 1 }
      ], totalCount: 1, page: 0, size: 100, totalPages: 1 });
    }
    if (url.pathname === '/api/v1/staff/admin/cities/7/operating-teams') {
      return json(route, { items: [], totalCount: 0, page: 0, size: 100, totalPages: 0 });
    }
    if (url.pathname === '/api/v1/staff/admin/cities/8/operating-teams') {
      return json(route, { message: 'controlled failure' }, 503);
    }
    if (url.pathname === '/api/v1/staff/admin/users' && request.method() === 'GET') {
      return json(route, { items: [{ userId: 110, email: 'picker@example.test',
        fullName: 'Picker User', profileExists: true, staffActive: true }],
      totalCount: 1, page: 0, size: 20, totalPages: 1 });
    }
    if (url.pathname === '/api/v1/staff/admin/users/110' && request.method() === 'GET') {
      const readbackCityId = pickerGrantPayload?.scopeType === 'TEAM'
        ? 6
        : pickerGrantPayload?.cityId;
      return json(route, { userId: 110, profileExists: true, staffActive: true, version: 1,
        staffActivatedAt: '2026-10-01T10:00:00Z', staffDeactivatedAt: null,
        grants: pickerGrantCount === 0 ? [] : [{ id: 91, capability: pickerGrantPayload.capability,
          scopeType: pickerGrantPayload.scopeType, cityId: readbackCityId,
          cityDisplayName: readbackCityId === 6 ? 'Bhopal' : 'Indore',
          teamId: pickerGrantPayload.teamId, teamDisplayName: pickerGrantPayload.teamId === 12
            ? 'City B Team' : 'City A current Team',
          effectiveAt: '2026-10-23T12:00:00Z', expiresAt: null, revokedAt: null,
          createdAt: '2026-10-23T12:00:00Z', provisioningSource: 'ADMIN_API' }]
      });
    }
    if (url.pathname === '/api/v1/staff/admin/users/110/grants' && request.method() === 'POST') {
      pickerGrantCount += 1;
      pickerGrantPayload = JSON.parse(request.postData() ?? '{}');
      const invalid = fixedGrantRequestError(pickerGrantPayload);
      if (invalid) return json(route, { message: invalid }, 400);
      const derivedCityId = pickerGrantPayload.scopeType === 'TEAM' ? 6 : pickerGrantPayload.cityId;
      return json(route, { id: 91, capability: pickerGrantPayload.capability,
        scopeType: pickerGrantPayload.scopeType, cityId: derivedCityId,
        cityDisplayName: derivedCityId === 6 ? 'Bhopal' : 'Indore',
        teamId: pickerGrantPayload.teamId, teamDisplayName: pickerGrantPayload.teamId === 12
          ? 'City B Team' : 'City A current Team',
        effectiveAt: '2026-10-23T12:00:00Z', expiresAt: null, revokedAt: null,
        createdAt: '2026-10-23T12:00:00Z', provisioningSource: 'ADMIN_API' });
    }
    return json(route, { message: 'Controlled endpoint not used' }, 404);
  });
  await pickerPage.goto(`${origin}/admin`, { waitUntil: 'domcontentloaded' });
  await pickerPage.getByRole('heading', { name: 'Staff permissions' }).waitFor();
  await teamAStartedPromise;
  await pickerPage.getByLabel('User ID, name, or email').fill('picker');
  await pickerPage.getByRole('button', { name: 'Search' }).click();
  await pickerPage.getByRole('button', { name: /Picker User/ }).click();
  await pickerPage.locator('#staff-city').waitFor();
  await pickerPage.locator('#staff-city').selectOption('6');
  check('changing City immediately clears the prior Team selection',
    (await pickerPage.locator('#staff-team').inputValue()) === '');
  await pickerPage.locator('#staff-team').locator('option')
    .filter({ hasText: 'City B Team' }).waitFor({ state: 'attached' });
  await pickerPage.getByLabel('Reason code').last().fill('PICKER_EXACT_SCOPE');
  await pickerPage.getByRole('button', { name: 'Review and confirm' }).click();
  await pickerPage.locator('.staff-confirm--assign p')
    .filter({ hasText: 'City B Team in Bhopal' }).waitFor();
  releaseInitialCityA();
  await pickerPage.waitForTimeout(150);
  check('a delayed old-City Team response cannot replace the current City B context',
    await pickerPage.locator('.staff-confirm--assign p')
      .filter({ hasText: 'City B Team in Bhopal' }).isVisible());
  await pickerPage.getByRole('button', { name: 'Confirm assignment' }).evaluate((button) => {
    button.click();
    button.click();
  });
  await pickerPage.waitForFunction(() => document.querySelector('.staff-permissions-notice')
    ?.textContent?.includes('is recorded and visible in current access'));
  check('rapid double confirmation dispatches exactly one grant command', pickerGrantCount === 1);
  check('TEAM command carries only its Team FK while reviewed City remains the Team context',
    pickerGrantPayload?.scopeType === 'TEAM'
    && pickerGrantPayload?.cityId === null
    && pickerGrantPayload?.teamId === 12);
  await pickerPage.locator('#staff-city').selectOption('7');
  await pickerPage.getByText('No Team is available in this City.').waitFor();
  check('an empty Team response blocks grant review',
    await pickerPage.getByRole('button', { name: 'Review and confirm' }).isDisabled());
  await pickerPage.locator('#staff-city').selectOption('8');
  await pickerPage.getByText('Team options could not be loaded for this City.').waitFor();
  check('a failed Team response cannot reuse an earlier Team',
    await pickerPage.getByRole('button', { name: 'Review and confirm' }).isDisabled());
  await pickerContext.close();

  const readbackContext = await browser.newContext({ viewport: { width: 1024, height: 900 } });
  const readbackPage = await readbackContext.newPage();
  await seed(readbackPage, { role: 'ADMIN', userId: 901, activeTab: 'staff-permissions' });
  await readbackPage.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname === '/api/v1/staff/me/capabilities') {
      return json(route, { staffActive: true, serverTime: '2026-10-23T12:00:00Z',
        capabilities: [{ capability: 'STAFF_ADMIN', scopeType: 'GLOBAL' }] });
    }
    if (url.pathname === '/api/v1/staff/admin/operating-teams/cities') {
      return json(route, { items: [{ id: 5, code: 'indore', displayName: 'Indore',
        active: true, version: 0 }], totalCount: 1, page: 0, size: 100, totalPages: 1 });
    }
    if (url.pathname === '/api/v1/staff/admin/cities/5/operating-teams') {
      return json(route, { items: [{ id: 9, cityId: 5, code: 'central',
        displayName: 'Central Homes Team', active: true, version: 1 }],
      totalCount: 1, page: 0, size: 100, totalPages: 1 });
    }
    if (url.pathname === '/api/v1/staff/admin/users' && request.method() === 'GET') {
      return json(route, { items: [{ userId: 120, email: 'readback@example.test',
        fullName: 'Readback User', profileExists: true, staffActive: true }],
      totalCount: 1, page: 0, size: 20, totalPages: 1 });
    }
    if (url.pathname === '/api/v1/staff/admin/users/120' && request.method() === 'GET') {
      return json(route, { userId: 120, profileExists: true, staffActive: true, version: 1,
        staffActivatedAt: '2026-10-01T10:00:00Z', staffDeactivatedAt: null, grants: [] });
    }
    if (url.pathname === '/api/v1/staff/admin/users/120/grants' && request.method() === 'POST') {
      const body = JSON.parse(request.postData() ?? '{}');
      const invalid = fixedGrantRequestError(body);
      if (invalid) return json(route, { message: invalid }, 400);
      return json(route, { id: 92, capability: body.capability, scopeType: body.scopeType,
        cityId: body.cityId, cityDisplayName: 'Indore', teamId: body.teamId,
        teamDisplayName: 'Central Homes Team', effectiveAt: '2026-10-23T12:00:00Z',
        expiresAt: body.expiresAt, revokedAt: null, createdAt: '2026-10-23T12:00:00Z',
        provisioningSource: 'ADMIN_API' });
    }
    return json(route, { message: 'Controlled endpoint not used' }, 404);
  });
  await readbackPage.goto(`${origin}/admin`, { waitUntil: 'domcontentloaded' });
  await readbackPage.getByRole('heading', { name: 'Staff permissions' }).waitFor();
  await readbackPage.getByLabel('User ID, name, or email').fill('readback');
  await readbackPage.getByRole('button', { name: 'Search' }).click();
  await readbackPage.getByRole('button', { name: /Readback User/ }).click();
  await readbackPage.getByLabel('Reason code').last().fill('READBACK_EMPTY_TEST');
  await readbackPage.getByRole('button', { name: 'Review and confirm' }).click();
  await readbackPage.getByRole('button', { name: 'Confirm assignment' }).click();
  await readbackPage.getByText(/authoritative readback did not show that exact fact/).waitFor();
  const emptyReadbackMessage = await readbackPage.locator('.staff-permissions-notice').innerText();
  check('empty authoritative readback never produces an active-success claim',
    emptyReadbackMessage.includes('did not show that exact fact')
    && !emptyReadbackMessage.includes('is recorded and visible'));
  await readbackContext.close();

  const wrongRevokeContext = await browser.newContext({ viewport: { width: 1024, height: 900 } });
  const wrongRevokePage = await wrongRevokeContext.newPage();
  await seed(wrongRevokePage, { role: 'ADMIN', userId: 921, activeTab: 'staff-permissions' });
  let wrongRevokeAttempted = false;
  await wrongRevokePage.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname === '/api/v1/staff/me/capabilities') {
      return json(route, { staffActive: true, serverTime: '2026-10-23T12:00:00Z',
        capabilities: [{ capability: 'STAFF_ADMIN', scopeType: 'GLOBAL' }] });
    }
    if (url.pathname === '/api/v1/staff/admin/operating-teams/cities') {
      return json(route, { items: [{ id: 5, code: 'indore', displayName: 'Indore',
        active: true, version: 0 }], totalCount: 1, page: 0, size: 100, totalPages: 1 });
    }
    if (url.pathname === '/api/v1/staff/admin/cities/5/operating-teams') {
      return json(route, { items: [], totalCount: 0, page: 0, size: 100, totalPages: 0 });
    }
    if (url.pathname === '/api/v1/staff/admin/users' && request.method() === 'GET') {
      return json(route, { items: [{ userId: 160, email: 'revoke-wrong@example.test',
        fullName: 'Wrong Revoke User', profileExists: true, staffActive: true }],
      totalCount: 1, page: 0, size: 20, totalPages: 1 });
    }
    if (url.pathname === '/api/v1/staff/admin/users/160' && request.method() === 'GET') {
      const reviewed = { id: 96, capability: 'OPS_ROUTING', scopeType: 'CITY',
        cityId: 5, cityDisplayName: 'Indore', teamId: null, teamDisplayName: null,
        effectiveAt: '2026-10-01T10:00:00Z', expiresAt: null,
        revokedAt: null, createdAt: '2026-10-01T10:00:00Z',
        provisioningSource: 'ADMIN_API' };
      const wrong = { ...reviewed, id: 97,
        revokedAt: '2026-10-23T12:01:00Z' };
      return json(route, { userId: 160, profileExists: true, staffActive: true, version: 1,
        staffActivatedAt: '2026-10-01T10:00:00Z', staffDeactivatedAt: null,
        grants: wrongRevokeAttempted ? [reviewed, wrong] : [reviewed] });
    }
    if (url.pathname === '/api/v1/staff/admin/grants/96/revoke' && request.method() === 'POST') {
      wrongRevokeAttempted = true;
      return json(route, { id: 97, capability: 'OPS_ROUTING', scopeType: 'CITY',
        cityId: 5, cityDisplayName: 'Indore', teamId: null, teamDisplayName: null,
        effectiveAt: '2026-10-01T10:00:00Z', expiresAt: null,
        revokedAt: '2026-10-23T12:01:00Z', createdAt: '2026-10-01T10:00:00Z',
        provisioningSource: 'ADMIN_API' });
    }
    return json(route, { message: 'Controlled endpoint not used' }, 404);
  });
  await wrongRevokePage.goto(`${origin}/admin`, { waitUntil: 'domcontentloaded' });
  await wrongRevokePage.getByRole('heading', { name: 'Staff permissions' }).waitFor();
  await wrongRevokePage.getByLabel('User ID, name, or email').fill('wrong');
  await wrongRevokePage.getByRole('button', { name: 'Search' }).click();
  await wrongRevokePage.getByRole('button', { name: /Wrong Revoke User/ }).click();
  await wrongRevokePage.getByRole('button', { name: 'Review revocation' }).click();
  await wrongRevokePage.locator('#staff-revoke-reason-96').fill('WRONG_ID_READBACK');
  await wrongRevokePage.getByRole('button', { name: 'Confirm revocation' }).click();
  await wrongRevokePage.getByText(/did not confirm that exact grant as revoked/).waitFor();
  check('a wrong returned grant ID cannot be confirmed as the reviewed revocation',
    !(await wrongRevokePage.locator('.staff-permissions-notice').innerText())
      .includes('is confirmed revoked'));
  await wrongRevokeContext.close();

  const staffSwitchContext = await browser.newContext({ viewport: { width: 1024, height: 900 } });
  const staffSwitchPage = await staffSwitchContext.newPage();
  await seed(staffSwitchPage, { role: 'ADMIN', userId: 111, activeTab: 'staff-permissions' });
  let staffSwitched = false;
  let releaseInspect;
  const inspectGate = new Promise((resolve) => { releaseInspect = resolve; });
  let inspectStarted;
  const inspectStartedPromise = new Promise((resolve) => { inspectStarted = resolve; });
  await staffSwitchPage.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname === '/api/v1/staff/me/capabilities') {
      return json(route, { staffActive: true, serverTime: '2026-10-23T12:00:00Z',
        capabilities: [{ capability: 'STAFF_ADMIN', scopeType: 'GLOBAL' }] });
    }
    if (url.pathname === '/api/v1/staff/admin/operating-teams/cities') {
      return json(route, { items: [{ id: 5, code: 'indore', displayName: 'Indore',
        active: true, version: 0 }], totalCount: 1, page: 0, size: 100, totalPages: 1 });
    }
    if (url.pathname === '/api/v1/staff/admin/cities/5/operating-teams') {
      return json(route, { items: [], totalCount: 0, page: 0, size: 100, totalPages: 0 });
    }
    if (url.pathname === '/api/v1/staff/admin/users' && request.method() === 'GET') {
      return json(route, { items: [{ userId: 140, email: 'switch@example.test',
        fullName: 'Switch User A', profileExists: true, staffActive: true }],
      totalCount: 1, page: 0, size: 20, totalPages: 1 });
    }
    if (url.pathname === '/api/v1/staff/admin/users/140' && request.method() === 'GET') {
      inspectStarted();
      await inspectGate;
      return json(route, { userId: 140, profileExists: true, staffActive: true, version: 1,
        staffActivatedAt: '2026-10-01T10:00:00Z', staffDeactivatedAt: null,
        grants: [{ id: 94, capability: 'OPS_COORDINATE', scopeType: 'TEAM',
          cityId: 5, cityDisplayName: 'Indore', teamId: 9,
          teamDisplayName: 'A-only Team', effectiveAt: '2026-10-01T10:00:00Z',
          expiresAt: null, revokedAt: null, createdAt: '2026-10-01T10:00:00Z',
          provisioningSource: 'ADMIN_API' }] });
    }
    return json(route, { message: 'Controlled endpoint not used' }, 404);
  });
  await staffSwitchPage.goto(`${origin}/admin`, { waitUntil: 'domcontentloaded' });
  await staffSwitchPage.getByRole('heading', { name: 'Staff permissions' }).waitFor();
  await staffSwitchPage.getByLabel('User ID, name, or email').fill('switch');
  await staffSwitchPage.getByRole('button', { name: 'Search' }).click();
  await staffSwitchPage.getByRole('button', { name: /Switch User A/ }).click();
  await inspectStartedPromise;
  staffSwitched = true;
  await staffSwitchPage.evaluate(() => {
    localStorage.setItem('pathome_auth_token', 'controlled-token-112');
    localStorage.setItem('pathome_user', JSON.stringify({
      id: 112, email: 'controlled-112@example.test', fullName: 'Controlled User 112',
      role: 'EMPLOYEE', freeVisitsUsed: 0, walletBalance: 0
    }));
    window.dispatchEvent(new Event('pathome_auth_changed'));
  });
  await staffSwitchPage.waitForTimeout(100);
  await staffSwitchPage.evaluate(() => {
    localStorage.setItem('pathome_auth_token', 'controlled-token-111');
    localStorage.setItem('pathome_user', JSON.stringify({
      id: 111, email: 'controlled-111@example.test', fullName: 'Controlled User 111',
      role: 'EMPLOYEE', freeVisitsUsed: 0, walletBalance: 0
    }));
    window.dispatchEvent(new Event('pathome_auth_changed'));
  });
  releaseInspect();
  await staffSwitchPage.waitForTimeout(250);
  check('held Owner permission response cannot revive after A to B to A',
    (await staffSwitchPage.getByText('A-only Team').count()) === 0
    && (await staffSwitchPage.getByText('Switch User A').count()) === 0);
  await staffSwitchContext.close();

  const searchRaceContext = await browser.newContext({ viewport: { width: 1024, height: 900 } });
  const searchRacePage = await searchRaceContext.newPage();
  await seed(searchRacePage, { role: 'ADMIN', userId: 121, activeTab: 'staff-permissions' });
  await searchRacePage.addInitScript(() => {
    const nativeFetch = window.fetch.bind(window);
    window.fetch = (input, init) => {
      const url = typeof input === 'string' ? input : input.url;
      if (url.includes('/staff/admin/users?') && (init?.method ?? 'GET') === 'GET') {
        const { signal, ...withoutAbort } = init ?? {};
        return nativeFetch(input, withoutAbort);
      }
      return nativeFetch(input, init);
    };
  });
  let searchAccount = 'A';
  let searchCalls = 0;
  let releaseOldSearch;
  let releaseNewSearch;
  let oldSearchStarted;
  let newSearchStarted;
  const oldSearchGate = new Promise((resolve) => { releaseOldSearch = resolve; });
  const newSearchGate = new Promise((resolve) => { releaseNewSearch = resolve; });
  const oldSearchStartedPromise = new Promise((resolve) => { oldSearchStarted = resolve; });
  const newSearchStartedPromise = new Promise((resolve) => { newSearchStarted = resolve; });
  await searchRacePage.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname === '/api/v1/staff/me/capabilities') {
      return json(route, { staffActive: true, serverTime: '2026-10-23T12:00:00Z',
        capabilities: [{ capability: 'STAFF_ADMIN', scopeType: 'GLOBAL' }] });
    }
    if (url.pathname === '/api/v1/staff/admin/operating-teams/cities') {
      return json(route, { items: [{ id: 5, code: 'indore', displayName: 'Indore',
        active: true, version: 0 }], totalCount: 1, page: 0, size: 100, totalPages: 1 });
    }
    if (url.pathname === '/api/v1/staff/admin/cities/5/operating-teams') {
      return json(route, { items: [], totalCount: 0, page: 0, size: 100, totalPages: 0 });
    }
    if (url.pathname === '/api/v1/staff/admin/users' && request.method() === 'GET') {
      searchCalls += 1;
      if (searchCalls === 1) {
        oldSearchStarted();
        await oldSearchGate;
        return json(route, { items: [{ userId: 140, email: 'search-a@example.test',
          fullName: 'Old Search A', profileExists: true, staffActive: true }],
        totalCount: 1, page: 0, size: 20, totalPages: 1 });
      }
      newSearchStarted();
      await newSearchGate;
      return json(route, { items: [{ userId: 150, email: 'search-b@example.test',
        fullName: 'Current Search B', profileExists: true, staffActive: true }],
      totalCount: 1, page: 0, size: 20, totalPages: 1 });
    }
    return json(route, { message: 'Controlled endpoint not used' }, 404);
  });
  await searchRacePage.goto(`${origin}/admin`, { waitUntil: 'domcontentloaded' });
  await searchRacePage.getByRole('heading', { name: 'Staff permissions' }).waitFor();
  await searchRacePage.getByLabel('User ID, name, or email').fill('old');
  await searchRacePage.getByRole('button', { name: 'Search' }).click();
  await oldSearchStartedPromise;
  searchAccount = 'B';
  await searchRacePage.evaluate(() => {
    localStorage.setItem('pathome_auth_token', 'controlled-token-122');
    localStorage.setItem('pathome_user', JSON.stringify({
      id: 122, fullName: 'Controlled User 122', role: 'EMPLOYEE'
    }));
    window.dispatchEvent(new Event('pathome_auth_changed'));
  });
  await searchRacePage.getByRole('button', { name: 'Search' }).waitFor();
  await searchRacePage.getByLabel('User ID, name, or email').fill('current');
  await searchRacePage.getByRole('button', { name: 'Search' }).click();
  await newSearchStartedPromise;
  releaseOldSearch();
  await searchRacePage.waitForTimeout(250);
  check('old search finalizer cannot release the newer search lock',
    await searchRacePage.getByRole('button', { name: 'Searching…' }).isDisabled());
  releaseNewSearch();
  await searchRacePage.getByRole('button', { name: /Current Search B/ }).waitFor();
  check('new search data and completion remain owned by the active operation', searchAccount === 'B');
  await searchRaceContext.close();

  const mutationRaceContext = await browser.newContext({ viewport: { width: 1024, height: 900 } });
  const mutationRacePage = await mutationRaceContext.newPage();
  await seed(mutationRacePage, { role: 'ADMIN', userId: 131, activeTab: 'staff-permissions' });
  await mutationRacePage.addInitScript(() => {
    const nativeFetch = window.fetch.bind(window);
    window.fetch = (input, init) => {
      const url = typeof input === 'string' ? input : input.url;
      if (url.includes('/staff/admin/users/140') && (init?.method ?? 'GET') === 'GET') {
        const { signal, ...withoutAbort } = init ?? {};
        return nativeFetch(input, withoutAbort);
      }
      return nativeFetch(input, init);
    };
  });
  let mutationAccount = 'A';
  let mutationGrantCreated = false;
  let mutationGrantPayload = null;
  let releaseOldInspect;
  let releaseGrant;
  let oldInspectStarted;
  let grantStarted;
  const oldInspectGate = new Promise((resolve) => { releaseOldInspect = resolve; });
  const grantGate = new Promise((resolve) => { releaseGrant = resolve; });
  const oldInspectStartedPromise = new Promise((resolve) => { oldInspectStarted = resolve; });
  const grantStartedPromise = new Promise((resolve) => { grantStarted = resolve; });
  await mutationRacePage.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname === '/api/v1/staff/me/capabilities') {
      return json(route, { staffActive: true, serverTime: '2026-10-23T12:00:00Z',
        capabilities: [{ capability: 'STAFF_ADMIN', scopeType: 'GLOBAL' }] });
    }
    if (url.pathname === '/api/v1/staff/admin/operating-teams/cities') {
      return json(route, { items: [{ id: 5, code: 'indore', displayName: 'Indore',
        active: true, version: 0 }], totalCount: 1, page: 0, size: 100, totalPages: 1 });
    }
    if (url.pathname === '/api/v1/staff/admin/cities/5/operating-teams') {
      return json(route, { items: [{ id: 9, cityId: 5, code: 'central',
        displayName: 'Central Homes Team', active: true, version: 1 }],
      totalCount: 1, page: 0, size: 100, totalPages: 1 });
    }
    if (url.pathname === '/api/v1/staff/admin/users' && request.method() === 'GET') {
      return json(route, { items: [mutationAccount === 'A'
        ? { userId: 140, email: 'mutation-a@example.test', fullName: 'Mutation User A',
          profileExists: true, staffActive: true }
        : { userId: 150, email: 'mutation-b@example.test', fullName: 'Mutation User B',
          profileExists: true, staffActive: true }],
      totalCount: 1, page: 0, size: 20, totalPages: 1 });
    }
    if (url.pathname === '/api/v1/staff/admin/users/140' && request.method() === 'GET') {
      oldInspectStarted();
      await oldInspectGate;
      return json(route, { userId: 140, profileExists: true, staffActive: true, version: 1,
        staffActivatedAt: '2026-10-01T10:00:00Z', staffDeactivatedAt: null,
        grants: [{ id: 94, capability: 'OPS_COORDINATE', scopeType: 'TEAM',
          cityId: 5, cityDisplayName: 'Indore', teamId: 9,
          teamDisplayName: 'Old Account Team', effectiveAt: '2026-10-01T10:00:00Z',
          expiresAt: null, revokedAt: null, createdAt: '2026-10-01T10:00:00Z',
          provisioningSource: 'ADMIN_API' }] });
    }
    if (url.pathname === '/api/v1/staff/admin/users/150' && request.method() === 'GET') {
      return json(route, { userId: 150, profileExists: true, staffActive: true, version: 1,
        staffActivatedAt: '2026-10-01T10:00:00Z', staffDeactivatedAt: null,
        grants: mutationGrantCreated ? [{ id: 95, capability: 'OPS_COORDINATE', scopeType: 'TEAM',
          cityId: 5, cityDisplayName: 'Indore', teamId: 9,
          teamDisplayName: 'Central Homes Team', effectiveAt: '2026-10-23T12:00:00Z',
          expiresAt: null, revokedAt: null, createdAt: '2026-10-23T12:00:00Z',
          provisioningSource: 'ADMIN_API' }] : [] });
    }
    if (url.pathname === '/api/v1/staff/admin/users/150/grants' && request.method() === 'POST') {
      mutationGrantPayload = JSON.parse(request.postData() ?? '{}');
      const invalid = fixedGrantRequestError(mutationGrantPayload);
      if (invalid) return json(route, { message: invalid }, 400);
      grantStarted();
      await grantGate;
      mutationGrantCreated = true;
      return json(route, { id: 95, capability: 'OPS_COORDINATE', scopeType: 'TEAM',
        cityId: 5, cityDisplayName: 'Indore', teamId: 9,
        teamDisplayName: 'Central Homes Team', effectiveAt: '2026-10-23T12:00:00Z',
        expiresAt: null, revokedAt: null, createdAt: '2026-10-23T12:00:00Z',
        provisioningSource: 'ADMIN_API' });
    }
    return json(route, { message: 'Controlled endpoint not used' }, 404);
  });
  await mutationRacePage.goto(`${origin}/admin`, { waitUntil: 'domcontentloaded' });
  await mutationRacePage.getByRole('heading', { name: 'Staff permissions' }).waitFor();
  await mutationRacePage.getByLabel('User ID, name, or email').fill('mutation-a');
  await mutationRacePage.getByRole('button', { name: 'Search' }).click();
  await mutationRacePage.getByRole('button', { name: /Mutation User A/ }).click();
  await oldInspectStartedPromise;
  mutationAccount = 'B';
  await mutationRacePage.evaluate(() => {
    localStorage.setItem('pathome_auth_token', 'controlled-token-132');
    localStorage.setItem('pathome_user', JSON.stringify({
      id: 132, fullName: 'Controlled User 132', role: 'EMPLOYEE'
    }));
    window.dispatchEvent(new Event('pathome_auth_changed'));
  });
  await mutationRacePage.getByRole('button', { name: 'Search' }).waitFor();
  await mutationRacePage.getByLabel('User ID, name, or email').fill('mutation-b');
  await mutationRacePage.getByRole('button', { name: 'Search' }).click();
  await mutationRacePage.getByRole('button', { name: /Mutation User B/ }).click();
  await mutationRacePage.getByLabel('Reason code').last().fill('RACE_OWNERSHIP_TEST');
  await mutationRacePage.getByRole('button', { name: 'Review and confirm' }).click();
  await mutationRacePage.getByRole('button', { name: 'Confirm assignment' }).click();
  await grantStartedPromise;
  releaseOldInspect();
  await mutationRacePage.waitForTimeout(250);
  check('old Account A inspection finalizer cannot clear Account B busy slot',
    await mutationRacePage.getByRole('button', { name: 'Assigning…' }).isDisabled());
  check('Account B mutation command remains the accepted TEAM shape',
    mutationGrantPayload?.scopeType === 'TEAM'
    && mutationGrantPayload?.cityId === null
    && mutationGrantPayload?.teamId === 9);
  releaseGrant();
  await mutationRacePage.getByText(/is recorded and visible in current access/).waitFor();
  check('Account B operation completes with its own controller and authoritative readback',
    await mutationRacePage.getByRole('button', { name: 'Review and confirm' }).isEnabled());
  await mutationRaceContext.close();

  const lostAckContext = await browser.newContext({ viewport: { width: 1024, height: 900 } });
  const lostAckPage = await lostAckContext.newPage();
  await seed(lostAckPage, { role: 'ADMIN', userId: 1001, activeTab: 'staff-permissions' });
  let grantAttempted = false;
  let revokeAttempted = false;
  let grantPosts = 0;
  let revokePosts = 0;
  await lostAckPage.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname === '/api/v1/staff/me/capabilities') {
      return json(route, { staffActive: true, serverTime: '2026-10-23T12:00:00Z',
        capabilities: [{ capability: 'STAFF_ADMIN', scopeType: 'GLOBAL' }] });
    }
    if (url.pathname === '/api/v1/staff/admin/operating-teams/cities') {
      return json(route, { items: [{ id: 5, code: 'indore', displayName: 'Indore',
        active: true, version: 0 }], totalCount: 1, page: 0, size: 100, totalPages: 1 });
    }
    if (url.pathname === '/api/v1/staff/admin/cities/5/operating-teams') {
      return json(route, { items: [{ id: 9, cityId: 5, code: 'central',
        displayName: 'Central Homes Team', active: true, version: 1 }],
      totalCount: 1, page: 0, size: 100, totalPages: 1 });
    }
    if (url.pathname === '/api/v1/staff/admin/users' && request.method() === 'GET') {
      return json(route, { items: [{ userId: 130, email: 'lost@example.test',
        fullName: 'Lost Ack User', profileExists: true, staffActive: true }],
      totalCount: 1, page: 0, size: 20, totalPages: 1 });
    }
    if (url.pathname === '/api/v1/staff/admin/users/130' && request.method() === 'GET') {
      return json(route, { userId: 130, profileExists: true, staffActive: true, version: 1,
        staffActivatedAt: '2026-10-01T10:00:00Z', staffDeactivatedAt: null,
        grants: grantAttempted ? [{ id: 93, capability: 'OPS_COORDINATE', scopeType: 'TEAM',
          cityId: 5, cityDisplayName: 'Indore', teamId: 9,
          teamDisplayName: 'Central Homes Team', effectiveAt: '2026-10-23T12:00:00Z',
          expiresAt: null, revokedAt: revokeAttempted ? '2026-10-23T12:01:00Z' : null,
          createdAt: '2026-10-23T12:00:00Z', provisioningSource: 'ADMIN_API' }] : []
      });
    }
    if (url.pathname === '/api/v1/staff/admin/users/130/grants' && request.method() === 'POST') {
      grantPosts += 1;
      const invalid = fixedGrantRequestError(JSON.parse(request.postData() ?? '{}'));
      if (invalid) return json(route, { message: invalid }, 400);
      grantAttempted = true;
      return route.abort('failed');
    }
    if (url.pathname === '/api/v1/staff/admin/grants/93/revoke' && request.method() === 'POST') {
      revokePosts += 1;
      revokeAttempted = true;
      return route.abort('failed');
    }
    return json(route, { message: 'Controlled endpoint not used' }, 404);
  });
  await lostAckPage.goto(`${origin}/admin`, { waitUntil: 'domcontentloaded' });
  await lostAckPage.getByRole('heading', { name: 'Staff permissions' }).waitFor();
  await lostAckPage.getByLabel('User ID, name, or email').fill('lost');
  await lostAckPage.getByRole('button', { name: 'Search' }).click();
  await lostAckPage.getByRole('button', { name: /Lost Ack User/ }).click();
  await lostAckPage.getByLabel('Reason code').last().fill('LOST_ACK_CREATE');
  await lostAckPage.getByRole('button', { name: 'Review and confirm' }).click();
  await lostAckPage.getByRole('button', { name: 'Confirm assignment' }).click();
  await lostAckPage.getByText(/assignment response was lost, but authoritative readback shows one new matching grant/).waitFor();
  check('lost assignment acknowledgement is reconciled by readback without replay', grantPosts === 1);
  await lostAckPage.getByRole('button', { name: 'Review revocation' }).click();
  await lostAckPage.locator('#staff-revoke-reason-93').fill('LOST_ACK_REVOKE');
  await lostAckPage.getByRole('button', { name: 'Confirm revocation' }).click();
  await lostAckPage.getByText(/confirmed revoked by authoritative readback after a lost response/).waitFor();
  check('lost revocation acknowledgement is reconciled by exact readback without replay',
    revokePosts === 1);
  await lostAckContext.close();
} finally {
  await browser.close();
  await new Promise((resolve) => server.close(resolve));
}

console.log(`\n${checks - failures}/${checks} controlled browser checks passed`);
if (failures > 0) process.exitCode = 1;
