/**
 * Controlled Chromium verification for the tail of the genuine milestone journey: a real Operations
 * Executive, signed in through the application's own sign-in form, reaching the Operations workspace and
 * claiming the Visit Request that the genuine journey actually created, routed and published.
 *
 * Nothing is seeded in the browser: every context starts with empty local storage, the sign-in form is the
 * real one, and every `/api/**` call is proxied verbatim to the live Spring Boot backend on the designated
 * disposable PostgreSQL database. The workspace rows under test are the ones the journey produced from a
 * real lessor submission and a real staff publication - not browser-side fixtures.
 *
 * Usage (see package4.sh journey-browser):
 *   P4_STATE_FILE=<path to journey-state.json> node run.mjs
 */
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;

const DIST = fileURLToPath(new URL('../../dist/', import.meta.url));
const ORIGIN = 'http://oe.local';
const STATE_FILE = process.env.P4_STATE_FILE;
if (!STATE_FILE) throw new Error('P4_STATE_FILE must point at the journey state file');

const state = JSON.parse(await readFile(STATE_FILE, 'utf8'));
const BACKEND = process.env.P4_BACKEND_URL ?? state.backend ?? 'http://localhost:8080';
const REQUEST_KEY = `REQUEST-${state.requestId}`;

const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.webp': 'image/webp',
  '.jpg': 'image/jpeg'
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
const settle = (page, ms = 1500) => page.waitForTimeout(ms);
const fingerprint = (value) => (value ? createHash('sha256').update(value).digest('hex').slice(0, 12) : 'none');
const storedToken = (page) => page.evaluate(() => window.localStorage.getItem('pathome_auth_token'));
const calls = (network, method, fragment) =>
  network.filter((record) => record.method === method && record.path.includes(fragment));
const waitFor = async (page, selector, timeout = 15000) => {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await page.locator(selector).first().isVisible().catch(() => false)) return true;
    await page.waitForTimeout(120);
  }
  return false;
};
const signInThroughTheForm = async (page, email, password) => {
  await page.getByRole('button', { name: 'Sign in', exact: true }).first().click();
  const dialog = page.getByRole('dialog').first();
  await dialog.waitFor({ state: 'visible', timeout: 15000 });
  await dialog.locator('input[type="email"]').fill(email);
  await dialog.locator('input[type="password"]').fill(password);
  await dialog.getByRole('button', { name: 'Sign In', exact: true }).click();
  await dialog.waitFor({ state: 'hidden', timeout: 20000 }).catch(() => {});
};

const freshContext = async (browser, { path = '/' } = {}) => {
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
        auth: fingerprint(headers.authorization?.replace(/^Bearer\s+/, ''))
      });
      return route.fulfill({
        status: response.status,
        contentType: response.headers.get('content-type') ?? 'application/json',
        body: text
      });
    }
    const file = join(DIST, url.pathname === '/' ? '/index.html' : url.pathname);
    try {
      await route.fulfill({
        status: 200,
        contentType: CONTENT_TYPES[extname(file)] ?? 'application/octet-stream',
        body: await readFile(file)
      });
    } catch {
      await route.fulfill({
        status: 200,
        contentType: 'text/html; charset=utf-8',
        body: await readFile(join(DIST, 'index.html'))
      });
    }
  });
  await page.goto(`${ORIGIN}${path}`, { waitUntil: 'domcontentloaded' });
  return { context, page, network, pageErrors };
};

const operationsEntryOffered = (page) =>
  page.getByRole('button', { name: /^Operations$/ }).count().then((count) => count > 0);

const browser = await chromium.launch({ channel: 'chromium' });
try {
  console.log('Genuine journey browser verification');
  console.log(`backend: ${BACKEND}`);
  console.log(`journey: listing ${state.listingId}, request ${state.requestId}, team ${state.teamId}\n`);

  console.log('=== A. a visitor holds no operational surface ===');
  {
    const { context, page, network, pageErrors } = await freshContext(browser, { path: '/operations' });
    check('the browser starts with no session credential at all', (await storedToken(page)) === null);
    check('no Operations entry point is offered to a visitor', !(await operationsEntryOffered(page)));
    check('no claimable work was read for a visitor',
      calls(network, 'GET', '/operations/claimable-work').length === 0,
      JSON.stringify(calls(network, 'GET', '/operations/claimable-work')));
    check('no uncaught page error occurred', pageErrors.length === 0, pageErrors.join(' | '));
    await context.close();
  }

  console.log('\n=== B. the real Operations Executive reaches the workspace ===');
  let oe = null;
  {
    const env = await freshContext(browser);
    oe = env;
    await signInThroughTheForm(env.page, state.identities.oe.email, state.identities.oe.password);
    const token = await storedToken(env.page);
    check('the real sign-in form established a session token', typeof token === 'string' && token.length > 20);
    check('sign-in went to the real authentication endpoint',
      calls(env.network, 'POST', '/auth/login')[0]?.status === 200);
    const capabilities = calls(env.network, 'GET', '/staff/me/capabilities').at(-1);
    check('the workspace entry is offered on verified live capability', await operationsEntryOffered(env.page));
    check('the capability read is a real 200', capabilities?.status === 200, `${capabilities?.status}`);
    check('the capability read carried the signed-in credential', capabilities?.auth === fingerprint(token),
      `${capabilities?.auth} vs ${fingerprint(token)}`);
    await env.page.getByRole('button', { name: /^Operations$/ }).first().click();
    check('the Operations workspace renders on the dedicated destination',
      await waitFor(env.page, '.oe-subnav'));
    check('the claimable queue renders', await waitFor(env.page, '[data-row-key]'));
    const claimableRead = calls(env.network, 'GET', '/operations/claimable-work').at(-1);
    check('the claimable read is a real 200', claimableRead?.status === 200, `${claimableRead?.status}`);
    check('the claimable read is never cached', String(claimableRead?.cacheControl).includes('no-store'),
      claimableRead?.cacheControl);
    check('the genuinely routed Request is claimable work in the workspace',
      await waitFor(env.page, `[data-row-key="${REQUEST_KEY}"]`),
      REQUEST_KEY);
    check('no uncaught page error occurred', env.pageErrors.length === 0, env.pageErrors.join(' | '));
  }

  console.log('\n=== C. the Executive claims the routed Request through the UI ===');
  {
    const { page, network, pageErrors } = oe;
    await page.locator(`[data-row-key="${REQUEST_KEY}"]`).click();
    check('the claim preview opens for the routed Request',
      await waitFor(page, 'button:has-text("Claim request")'));
    await page.getByRole('button', { name: /^Claim request$/ }).click();
    await settle(page);
    const claimPosts = calls(network, 'POST', `/operations/visit-requests/${state.requestId}/claim`);
    check('exactly one claim command was transmitted', claimPosts.length === 1, `${claimPosts.length}`);
    check('the backend answered the claim with a real 204', claimPosts[0]?.status === 204,
      `${claimPosts[0]?.status}`);
    await page.getByRole('button', { name: /My work/i }).first().click();
    await settle(page);
    check('My work renders the newly owned Request', await waitFor(page, `[data-row-key="${REQUEST_KEY}"]`));
    const myWorkRead = calls(network, 'GET', '/operations/my-work').at(-1);
    check('the My Work read is a real 200', myWorkRead?.status === 200, `${myWorkRead?.status}`);
    const rows = await page.locator('.oe-list [data-row-key]')
      .evaluateAll((nodes) => nodes.map((node) => node.getAttribute('data-row-key')));
    check('My work contains exactly the claimed Request', JSON.stringify(rows) === JSON.stringify([REQUEST_KEY]),
      JSON.stringify(rows));
    check('no uncaught page error occurred', pageErrors.length === 0, pageErrors.join(' | '));

    const desktop = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      innerWidth: window.innerWidth
    }));
    check('the owned workspace causes no horizontal overflow at the desktop viewport',
      desktop.scrollWidth <= desktop.innerWidth, `${desktop.scrollWidth} > ${desktop.innerWidth}`);
    await page.setViewportSize({ width: 390, height: 844 });
    await settle(page, 800);
    const narrow = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      innerWidth: window.innerWidth
    }));
    check('the owned workspace causes no horizontal overflow at 390px',
      narrow.scrollWidth <= narrow.innerWidth, `${narrow.scrollWidth} > ${narrow.innerWidth}`);
    check('the owned Request is still on screen at 390px', await waitFor(page, `[data-row-key="${REQUEST_KEY}"]`));
    check('no uncaught page error occurred after the resize', pageErrors.length === 0, pageErrors.join(' | '));
    await page.context().close();
  }

  console.log('\n=== D. a plain tenant and the review administrator hold no operational surface ===');
  {
    for (const [label, identity] of [
      ['a plain tenant', state.identities.tenant],
      ['the staff review administrator', state.identities.reviewer]
    ]) {
      const { context, page, pageErrors } = await freshContext(browser);
      await signInThroughTheForm(page, identity.email, identity.password);
      await settle(page);
      check(`${label} is offered no Operations entry point`, !(await operationsEntryOffered(page)));
      check(`${label} causes no uncaught page error`, pageErrors.length === 0, pageErrors.join(' | '));
      await context.close();
    }
  }
} finally {
  await browser.close();
}

console.log(`\n${checks - failures}/${checks} checks passed`);
if (failures > 0) {
  console.error(`Genuine journey browser verification: ${failures} failure(s)`);
  process.exit(1);
}
console.log('Genuine journey browser verification: all checks passed');
