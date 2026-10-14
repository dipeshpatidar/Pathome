/**
 * Package 3B genuine browser verification: fresh Chromium contexts, real sign-in form, real backend.
 *
 * Nothing is seeded. Every scenario opens a brand-new context with **empty** local storage and, where
 * an identity is needed, authenticates through the application's own sign-in form. Every `/api/**`
 * call the page makes is proxied verbatim to the live Spring Boot backend on the designated disposable
 * PostgreSQL database, so the capability decision, the routed reads and the real 401/403/404 answers
 * all come from the real application and the real database.
 *
 * The narrow listing-review authority extension is NOT approved, so this harness does not fabricate a
 * canonical published listing and does not claim the genuine fresh-publication intake flow. It proves
 * the approved routing surface: authentication, source-scope authorization, concealment, cache policy
 * and the truthful absence of any new routing affordance in the existing tenant/Operations UI.
 */
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;

const DIST = fileURLToPath(new URL('../../dist/', import.meta.url));
const ORIGIN = 'http://oe.local';
const BACKEND = process.env.P3B_BACKEND_URL ?? 'http://localhost:8080';
const ROUTING = '/api/v1/operations/intake-routing/visit-requests';

const IDENTITY = {
  adminEmail: process.env.P3B_ADMIN_EMAIL,
  adminPassword: process.env.P3B_ADMIN_PASSWORD,
  oeEmail: process.env.oe_alpha_EMAIL,
  oePassword: process.env.oe_alpha_PASSWORD,
  cityAdminEmail: process.env.city_admin_EMAIL,
  cityAdminPassword: process.env.city_admin_password ?? process.env.oe_alpha_PASSWORD,
  cityId: Number(process.env.P3B_CITY_ID)
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

const fingerprint = (value) =>
  value ? createHash('sha256').update(value).digest('hex').slice(0, 12) : 'none';
const settle = (page, ms = 1500) => page.waitForTimeout(ms);
const storedToken = (page) => page.evaluate(() => window.localStorage.getItem('pathome_auth_token'));
const openAuthModal = async (page) => {
  await page.getByRole('button', { name: 'Sign in', exact: true }).first().click();
  const dialog = page.getByRole('dialog').first();
  await dialog.waitFor({ state: 'visible', timeout: 15000 });
  return dialog;
};
const signInThroughTheForm = async (page, email, password) => {
  const dialog = await openAuthModal(page);
  await dialog.locator('input[type="email"]').fill(email);
  await dialog.locator('input[type="password"]').fill(password);
  await dialog.getByRole('button', { name: 'Sign In', exact: true }).click();
  await dialog.waitFor({ state: 'hidden', timeout: 20000 }).catch(() => {});
};

/**
 * A context with no `storageState` and no injected keys. Same-origin `/api/**` calls are proxied
 * verbatim to the live backend, and each real response (status, cache header, credential fingerprint)
 * is recorded so assertions read the backend's own answer rather than a browser-side guess.
 */
const freshContext = async (browser) => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  const network = [];
  const pageErrors = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));
  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url());
    if (url.origin !== ORIGIN) return route.continue();
    if (url.pathname.startsWith('/api/')) {
      const request = route.request();
      const headers = { ...request.headers() };
      delete headers.host;
      delete headers['content-length'];
      const body = request.postData();
      const init = { method: request.method(), headers, redirect: 'manual' };
      if (body !== null && body !== undefined && request.method() !== 'GET') init.body = body;
      const response = await fetch(`${BACKEND}${url.pathname}${url.search}`, init);
      const text = await response.text();
      network.push({
        method: request.method(),
        path: url.pathname + url.search,
        status: response.status,
        cacheControl: response.headers.get('cache-control'),
        auth: fingerprint(headers.authorization?.replace(/^Bearer\s+/, '')),
        body: text
      });
      return route.fulfill({
        status: response.status,
        contentType: response.headers.get('content-type') ?? 'application/json',
        body: text
      });
    }
    const file = join(DIST, url.pathname === '/' ? '/index.html' : url.pathname);
    try {
      const body = await readFile(file);
      await route.fulfill({
        status: 200,
        contentType: CONTENT_TYPES[extname(file)] ?? 'application/octet-stream',
        body
      });
    } catch {
      await route.fulfill({
        status: 200,
        contentType: 'text/html; charset=utf-8',
        body: await readFile(join(DIST, 'index.html'))
      });
    }
  });
  await page.goto(`${ORIGIN}/`, { waitUntil: 'domcontentloaded' });
  return { context, page, network, pageErrors };
};

/** Calls the app's own API with the browser's stored credential and returns the backend's answer. */
const callApi = async (page, network, method, path, payload) => {
  const result = await page.evaluate(
    async ([verb, target, body]) => {
      const token = window.localStorage.getItem('pathome_auth_token');
      const response = await fetch(target, {
        method: verb,
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {})
        },
        ...(body === null ? {} : { body: JSON.stringify(body) })
      });
      let parsed = null;
      try {
        parsed = await response.json();
      } catch {
        parsed = null;
      }
      return { status: response.status, body: parsed };
    },
    [method, path, payload === undefined ? null : payload]
  );
  const record = network.filter((entry) => entry.path === path).at(-1);
  return {
    status: result.status,
    body: result.body,
    cacheControl: record?.cacheControl,
    auth: record?.auth
  };
};

const noRoutingAffordance = async (page) =>
  (await page.getByText(/Intake Routing/i).count()) === 0 &&
  (await page.getByRole('button', { name: /Intake Routing/i }).count()) === 0;

const browser = await chromium.launch({ channel: 'chromium' });
try {
  console.log('\n=== 1. the routing surface refuses an anonymous browser ===');
  {
    const { context, page, network, pageErrors } = await freshContext(browser);
    check('the browser starts with no session credential at all', (await storedToken(page)) === null);
    const anonymous = await callApi(page, network, 'GET', ROUTING);
    check('an anonymous calling browser receives 401', anonymous.status === 401, `${anonymous.status}`);
    check('the anonymous call carried no credential', anonymous.auth === 'none');
    check('no routing affordance is advertised to a visitor', await noRoutingAffordance(page));
    check('no uncaught page error occurred', pageErrors.length === 0, pageErrors.join(' | '));
    await context.close();
  }

  console.log('\n=== 2. the global Staff Admin reads a truthful, uncached collection ===');
  {
    const { context, page, network, pageErrors } = await freshContext(browser);
    await signInThroughTheForm(page, IDENTITY.adminEmail, IDENTITY.adminPassword);
    const token = await storedToken(page);
    check('the real sign-in form established a session token', typeof token === 'string' && token.length > 20);
    check('sign-in went to the real authentication endpoint',
      network.filter((entry) => entry.path === '/api/v1/auth/login')[0]?.status === 200);
    const list = await callApi(page, network, 'GET', `${ROUTING}?page=0&size=20`);
    check('the global Staff Admin reads the routing collection (200)', list.status === 200, `${list.status}`);
    check('the routing read is never cached', String(list.cacheControl).includes('no-store'), list.cacheControl);
    check('the protected read carried the signed-in credential', list.auth === fingerprint(token),
      `${list.auth} vs ${fingerprint(token)}`);
    check(
      'the collection reports truthful totals for its own items',
      Boolean(list.body) &&
        Array.isArray(list.body.items) &&
        list.body.totalCount === list.body.items.length &&
        list.body.page === 0 &&
        list.body.size === 20
    );
    check(
      'no item is reported as Team-ready or already routed without a persisted Team',
      (list.body?.items ?? []).every((item) => item.teamId === null && item.teamReady === false)
    );
    check('no routing affordance was invented in the existing UI', await noRoutingAffordance(page));
    check('no uncaught page error occurred', pageErrors.length === 0, pageErrors.join(' | '));
    await context.close();
  }

  console.log('\n=== 3. an ordinary OE has no routing permission ===');
  {
    const { context, page, network, pageErrors } = await freshContext(browser);
    await signInThroughTheForm(page, IDENTITY.oeEmail, IDENTITY.oePassword);
    await settle(page);
    const read = await callApi(page, network, 'GET', `${ROUTING}?page=0&size=20`);
    check('an OE cannot read the routing collection (403)', read.status === 403, `${read.status}`);
    const candidates = await callApi(page, network, 'GET',
      `${ROUTING}/1/candidates?cityId=${IDENTITY.cityId}`);
    check('an OE cannot read routing candidates (403)', candidates.status === 403, `${candidates.status}`);
    const command = await callApi(page, network, 'POST', `${ROUTING}/1/route`, {
      expectedRequestVersion: 0,
      cityId: IDENTITY.cityId,
      expectedCityVersion: 0,
      teamId: 1,
      expectedTeamVersion: 0,
      reasonCode: 'INITIAL_ROUTING'
    });
    check('an OE cannot route work (403)', command.status === 403, `${command.status}`);
    const claimable = await callApi(page, network, 'GET', '/api/v1/operations/claimable-work?page=0&size=20');
    check('the OE keeps its existing claimable-work read (200)', claimable.status === 200, `${claimable.status}`);
    check('no uncaught page error occurred', pageErrors.length === 0, pageErrors.join(' | '));
    await context.close();
  }

  console.log('\n=== 4. a City Team Admin reads only its own authorized City ===');
  {
    const { context, page, network, pageErrors } = await freshContext(browser);
    await signInThroughTheForm(page, IDENTITY.cityAdminEmail, IDENTITY.cityAdminPassword);
    await settle(page);
    const list = await callApi(page, network, 'GET', `${ROUTING}?page=0&size=20`);
    check('the City Team Admin reads its City-scoped collection (200)', list.status === 200, `${list.status}`);
    check(
      'every visible item belongs to the authorized City only',
      (list.body?.items ?? []).every((item) => item.cityId === IDENTITY.cityId)
    );
    const foreign = await callApi(page, network, 'GET', `${ROUTING}/1/candidates?cityId=999999`);
    check('selecting an unauthorized City is concealed as 404', foreign.status === 404, `${foreign.status}`);
    check('no routing affordance was invented for a City Team Admin', await noRoutingAffordance(page));
    check('no uncaught page error occurred', pageErrors.length === 0, pageErrors.join(' | '));
    await context.close();
  }
} finally {
  await browser.close();
}

console.log(`\n${checks - failures}/${checks} checks passed`);
if (failures > 0) {
  console.error(`Package 3B browser verification: ${failures} failure(s)`);
  process.exit(1);
}
console.log('Package 3B browser verification: all checks passed');
