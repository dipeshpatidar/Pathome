/**
 * One connected Chromium journey against the guarded Spring Boot + PostgreSQL harness.
 * Static assets are served locally; every /api/** request is proxied to the real backend.
 */
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;
const DIST = fileURLToPath(new URL('../../dist/', import.meta.url));
const ORIGIN = 'http://removal.local';
const BACKEND = process.env.PATHOME_REMOVAL_BROWSER_BACKEND ?? 'http://127.0.0.1:18080';
const statePath = process.env.PATHOME_REMOVAL_BROWSER_STATE_FILE;
const stopPath = process.env.PATHOME_REMOVAL_BROWSER_STOP_FILE;
const receiptPath = process.env.PATHOME_REMOVAL_BROWSER_RECEIPT;
const password = process.env.PATHOME_BROWSER_TEST_PASSWORD;
if (!statePath || !stopPath || !receiptPath || !password) {
  throw new Error('Removal browser state, stop, receipt and synthetic password must be explicit');
}

const state = JSON.parse(await readFile(statePath, 'utf8'));
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
const receipt = {
  kind: 'connected-genuine-removal-browser',
  checks: 0,
  failures: 0,
  api: [],
  viewports: [],
  screenshots: []
};
const check = (label, condition, detail = '') => {
  checks += 1;
  if (condition) console.log(`  PASS  ${label}`);
  else {
    failures += 1;
    console.log(`  FAIL  ${label}${detail ? ` (${detail})` : ''}`);
  }
};
const waitVisible = async (page, selector, timeout = 15_000) => {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await page.locator(selector).first().isVisible().catch(() => false)) return true;
    await page.waitForTimeout(120);
  }
  return false;
};
const freshContext = async (browser, path = '/') => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  await page.route('**/*', async route => {
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
      const response = await fetch(`${BACKEND}${url.pathname}${url.search}`, {
        ...init,
        signal: AbortSignal.timeout(20_000)
      });
      const text = await response.text();
      receipt.api.push({
        method: request.method(),
        path: url.pathname + url.search,
        status: response.status,
        cacheControl: response.headers.get('cache-control')
      });
      return route.fulfill({
        status: response.status,
        contentType: response.headers.get('content-type') ?? 'application/json',
        headers: {
          'Cache-Control': response.headers.get('cache-control') ?? '',
          'Content-Type': response.headers.get('content-type') ?? 'application/json'
        },
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
  return { context, page, pageErrors };
};
const signIn = async (page, email) => {
  await page.getByRole('button', { name: 'Sign in', exact: true }).first().click();
  const dialog = page.getByRole('dialog').first();
  await dialog.waitFor({ state: 'visible', timeout: 15_000 });
  await dialog.locator('input[type="email"]').fill(email);
  await dialog.locator('input[type="password"]').fill(password);
  await dialog.getByRole('button', { name: 'Sign In', exact: true }).click();
  await dialog.waitFor({ state: 'hidden', timeout: 20_000 }).catch(() => {});
};
const clearSession = async page => {
  await page.evaluate(() => {
    localStorage.clear();
    sessionStorage.clear();
    window.dispatchEvent(new Event('pathome_auth_changed'));
  });
};
const noOverflow = async page => page.evaluate(() => ({
  scrollWidth: document.documentElement.scrollWidth,
  innerWidth: window.innerWidth
}));
const decide = async (page, title, decisionLabel, reason) => {
  await page.getByRole('button', { name: new RegExp(title) }).click();
  check(`detail opens for ${title}`, await waitVisible(page, '#removal-detail-title'));
  check('detail title matches the selected request',
    (await page.locator('#removal-detail-title').textContent())?.includes(title));
  await page.getByRole('button', { name: new RegExp(decisionLabel) }).click();
  check('decision dialog focuses its required reason',
    await page.evaluate(() => document.activeElement?.id === 'removal-decision-reason'));
  await page.locator('#removal-decision-reason').fill(reason);
  const confirm = decisionLabel === 'Approve removal'
    ? 'Confirm approval' : 'Confirm rejection';
  await page.getByRole('button', { name: confirm }).click();
};

const browser = await chromium.launch({ channel: 'chromium' });
try {
  await mkdir(dirname(receiptPath), { recursive: true });
  console.log('Connected genuine Removal browser verification');
  console.log(`backend: ${BACKEND}`);

  console.log('\n=== Visitor ===');
  {
    const { context, page, pageErrors } = await freshContext(browser, '/admin');
    check('visitor starts without a token',
      await page.evaluate(() => localStorage.getItem('pathome_auth_token') === null));
    check('visitor receives no Removal queue content',
      await page.getByText(state.approveTitle, { exact: true }).count() === 0);
    check('visitor causes no uncaught error', pageErrors.length === 0, pageErrors.join(' | '));
    await context.close();
  }

  console.log('\n=== Authorized Admin connected journey ===');
  const adminEnv = await freshContext(browser);
  const { page, pageErrors } = adminEnv;
  await signIn(page, state.adminEmail);
  check('real sign-in establishes a token',
    typeof await page.evaluate(() => localStorage.getItem('pathome_auth_token')) === 'string');
  await page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
  check('persisted Admin reaches /admin', page.url().endsWith('/admin'));
  check('Removal navigation appears only after live queue admission',
    await waitVisible(page, 'button:has-text("Removal requests")'));
  check('Admin Visit repair navigation remains available',
    await page.getByText('Visit repairs', { exact: true }).isVisible());
  check('Admin Visit outcome navigation remains available',
    await page.getByText('Visit outcome exceptions', { exact: true }).isVisible());

  await page.getByRole('button', { name: /Removal requests/ }).first().click();
  check('protected removal queue renders', await waitVisible(page, '#removal-queue-title'));
  check('approval fixture is present',
    await waitVisible(page, `button:has-text("${state.approveTitle}")`));
  check('rejection fixture is present',
    await waitVisible(page, `button:has-text("${state.rejectTitle}")`));
  const admission = receipt.api.findLast(entry =>
    entry.method === 'GET' && entry.path.startsWith('/api/v1/admin/property-removal-requests?'));
  check('queue admission is a real 200', admission?.status === 200, `${admission?.status}`);
  check('queue response is no-store', admission?.cacheControl === 'no-store',
    admission?.cacheControl);

  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(120);
  const queueMobile = await noOverflow(page);
  check('connected queue has no 390px horizontal overflow',
    queueMobile.scrollWidth <= queueMobile.innerWidth, JSON.stringify(queueMobile));
  receipt.viewports.push({ screen: 'queue', width: 390, ...queueMobile });
  await page.setViewportSize({ width: 1280, height: 900 });

  await decide(page, state.approveTitle, 'Approve removal', 'Connected approval reason');
  check('approve decision commits through genuine backend',
    await waitVisible(page, 'text=Removal approved.'));
  check('approved fixture leaves the pending queue',
    await page.getByRole('button', { name: new RegExp(state.approveTitle) }).count() === 0);

  await decide(page, state.rejectTitle, 'Reject request', 'Connected rejection reason');
  check('reject decision commits through genuine backend',
    await waitVisible(page, 'text=Removal rejected.'));
  check('rejected fixture leaves the pending queue',
    await page.getByRole('button', { name: new RegExp(state.rejectTitle) }).count() === 0);
  check('authorized journey causes no uncaught error',
    pageErrors.length === 0, pageErrors.join(' | '));
  await page.screenshot({
    path: join(dirname(receiptPath), 'connected-removal-complete-1280.png'),
    fullPage: true
  });
  receipt.screenshots.push('connected-removal-complete-1280.png');

  console.log('\n=== Session A to B to A ===');
  await clearSession(page);
  await page.goto(`${ORIGIN}/`, { waitUntil: 'domcontentloaded' });
  await signIn(page, state.deniedEmail);
  await page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
  check('non-Admin session never receives Removal navigation',
    await page.getByText('Removal requests', { exact: true }).count() === 0);
  check('non-Admin session never receives removal fixture content',
    await page.getByText(state.approveTitle, { exact: true }).count() === 0);
  await clearSession(page);
  await page.goto(`${ORIGIN}/`, { waitUntil: 'domcontentloaded' });
  await signIn(page, state.adminEmail);
  await page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
  check('first Admin identity is re-admitted on A→B→A',
    await waitVisible(page, 'button:has-text("Removal requests")'));
  await page.getByRole('button', { name: /Removal requests/ }).first().click();
  check('returned Admin receives the current protected queue',
    await waitVisible(page, '.backoffice-removal-list, .backoffice-empty'));
  check('returned Admin sees no stale pending request facts',
    await page.getByText(state.approveTitle, { exact: true }).count() === 0
      && await page.getByText(state.rejectTitle, { exact: true }).count() === 0);

  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(120);
  const returnedMobile = await noOverflow(page);
  check('returned Admin page has no 390px horizontal overflow',
    returnedMobile.scrollWidth <= returnedMobile.innerWidth, JSON.stringify(returnedMobile));
  receipt.viewports.push({ screen: 'returned-admin', width: 390, ...returnedMobile });
  await adminEnv.context.close();
} catch (error) {
  failures += 1;
  receipt.exception = error instanceof Error ? error.message : String(error);
  console.error(receipt.exception);
} finally {
  receipt.checks = checks;
  receipt.failures = failures;
  await writeFile(receiptPath, `${JSON.stringify(receipt, null, 2)}\n`);
  await writeFile(stopPath, 'complete\n');
  await browser.close();
}

console.log(`\n${checks - failures}/${checks} checks passed`);
if (failures > 0) process.exit(1);
