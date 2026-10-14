/**
 * Package 3A genuine admission verification: fresh Chromium contexts, real login form, real backend.
 *
 * Nothing is seeded. Each scenario opens a brand-new browser context with **empty** local storage and
 * authenticates through the application's own sign-in form; every `/api/**` call is forwarded
 * verbatim to the live Spring Boot backend on PostgreSQL, so the capability decision, the protected
 * read and the 401/403 answers all come from the real application and the real database.
 *
 * The identities are created only by `tools/oe-integration-env/package3.sh provision` (governed Team
 * API + Staff Administration API). Credentials arrive through the environment and are never printed.
 */
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;

const DIST = fileURLToPath(new URL('../../dist/', import.meta.url));
const ORIGIN = 'http://oe.local';
const BACKEND = process.env.P3A_BACKEND_URL ?? 'http://localhost:8080';

const IDENTITY = {
  oeAlphaEmail: process.env.oe_alpha_EMAIL,
  oeAlphaPassword: process.env.oe_alpha_PASSWORD,
  oeBravoEmail: process.env.oe_bravo_EMAIL,
  nograntEmail: process.env.staff_nogrant_EMAIL,
  cityAdminEmail: process.env.city_admin_EMAIL,
  password: process.env.oe_alpha_PASSWORD,
  teamAlphaId: Number(process.env.P3A_TEAM_ALPHA_ID),
  teamBravoId: Number(process.env.P3A_TEAM_BRAVO_ID)
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
const operationsReads = (network) =>
  network.filter((record) => record.path.startsWith('/api/v1/operations/'));
const callsTo = (network, fragment) =>
  network.filter((record) => record.path.includes(fragment));
const settle = (page, ms = 1200) => page.waitForTimeout(ms);
const operationsEntry = (page) => page.getByRole('button', { name: 'Operations', exact: true }).first();
const queuesNav = (page) => page.getByRole('navigation', { name: 'Operations queues' });
const restrictedNotice = (page) => page.getByText(/don.t have access to Operations/i).first();

/** A context with no `storageState` and no injected keys: the app starts as a genuine visitor. */
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
        auth: fingerprint(headers.authorization?.replace(/^Bearer\s+/, '')),
        body: text
      });
      return route.fulfill({
        status: response.status,
        contentType: response.headers.get('content-type') ?? 'application/json',
        headers: { 'Cache-Control': 'no-store' },
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

const storedToken = (page) => page.evaluate(() => window.localStorage.getItem('pathome_auth_token'));

/** Opens the app's own authentication modal from the visitor header. */
const openAuthModal = async (page) => {
  await page.getByRole('button', { name: 'Sign in', exact: true }).first().click();
  const dialog = page.getByRole('dialog').first();
  await dialog.waitFor({ state: 'visible', timeout: 15000 });
  return dialog;
};

/** Signs in through the real sign-in form on the home page. */
const signInThroughTheForm = async (page, email, password) => {
  const dialog = await openAuthModal(page);
  await dialog.locator('input[type="email"]').fill(email);
  await dialog.locator('input[type="password"]').fill(password);
  await dialog.getByRole('button', { name: 'Sign In', exact: true }).click();
  await dialog.waitFor({ state: 'hidden', timeout: 20000 }).catch(() => {});
};

/** Registers a brand-new ordinary account through the same form. */
const registerThroughTheForm = async (page, email, password, fullName) => {
  const dialog = await openAuthModal(page);
  await dialog.getByRole('button', { name: /Register$/ }).first().click();
  await dialog.locator('input[placeholder="Full name"]').fill(fullName);
  await dialog.locator('input[type="email"]').fill(email);
  await dialog.locator('input[type="password"]').fill(password);
  await dialog.getByRole('button', { name: 'Register Account', exact: true }).click();
  await dialog.waitFor({ state: 'hidden', timeout: 20000 }).catch(() => {});
};

const browser = await chromium.launch({ channel: 'chromium' });
try {
  console.log('\n=== 1. authorised OE admits itself from a fresh, empty browser ===');
  {
    const { context, page, network, pageErrors } = await freshContext(browser);
    check('the browser starts with no Operations credential at all', (await storedToken(page)) === null);
    await signInThroughTheForm(page, IDENTITY.oeAlphaEmail, IDENTITY.oeAlphaPassword);
    const token = await storedToken(page);
    check('the real sign-in form established a session token', typeof token === 'string' && token.length > 20);
    check('sign-in went to the real authentication endpoint', callsTo(network, '/auth/login')[0]?.status === 200);

    await operationsEntry(page).waitFor({ state: 'visible', timeout: 20000 });
    const capability = callsTo(network, '/staff/me/capabilities').at(-1);
    check('the entry was offered only after a live capability read', capability?.status === 200);
    check(
      'the capability read carried the signed-in credential',
      capability?.auth === fingerprint(token),
      `${capability?.auth} vs ${fingerprint(token)}`
    );
    const grants = JSON.parse(capability.body).capabilities ?? [];
    check(
      'the live capability truth holds OPS_COORDINATE on this Team',
      grants.some(
        (grant) =>
          grant.capability === 'OPS_COORDINATE' &&
          grant.scopeType === 'TEAM' &&
          grant.teamId === IDENTITY.teamAlphaId
      )
    );
    const afterSignInPath = new URL(page.url()).pathname;
    check(
      'the app never enters the Operations destination on its own',
      afterSignInPath !== '/operations',
      afterSignInPath
    );

    await operationsEntry(page).click();
    await queuesNav(page).waitFor({ state: 'visible', timeout: 20000 });
    check('choosing the entry reaches the /operations destination', new URL(page.url()).pathname === '/operations');
    check('the admitted workspace rendered its queue navigation', await queuesNav(page).isVisible());
    check(
      'no access-restricted notice is shown to an admitted OE',
      !(await restrictedNotice(page).isVisible().catch(() => false))
    );

    await settle(page);
    const work = callsTo(network, '/operations/claimable-work').at(-1);
    check('the admitted workspace read protected work', work?.status === 200, `${work?.status}`);
    check(
      'every protected read carried the same admitted credential',
      operationsReads(network).length >= 1 &&
        operationsReads(network).every((entry) => entry.auth === fingerprint(token))
    );
    check('the protected read disclosed no fabricated Team-routed work', JSON.parse(work.body).totalCount === 0);
    check('no uncaught page error occurred', pageErrors.length === 0, pageErrors.join(' | '));

    // The Operations surface has no profile menu of its own, so logout is exercised from the shell
    // the verified account landed on before deliberately entering Operations.
    await page.goto(`${ORIGIN}${afterSignInPath}`, { waitUntil: 'domcontentloaded' });
    await page.getByRole('button', { name: /Profile menu for/i }).first().click({ timeout: 20000 });
    await page.getByRole('button', { name: 'Log Out Session' }).first().click();
    await settle(page);
    check('logging out clears the session credential', (await storedToken(page)) === null);
    check('the Operations entry is withdrawn after logout', !(await operationsEntry(page).isVisible().catch(() => false)));
    await context.close();
  }

  console.log('\n=== 2. an OE of another Team sees only its own scope ===');
  {
    const { context, page, network } = await freshContext(browser);
    await signInThroughTheForm(page, IDENTITY.oeBravoEmail, IDENTITY.password);
    await operationsEntry(page).waitFor({ state: 'visible', timeout: 20000 });
    const capability = callsTo(network, '/staff/me/capabilities').at(-1);
    const grants = JSON.parse(capability.body).capabilities ?? [];
    check(
      'the second OE is admitted on its own Team grant',
      grants.some((grant) => grant.capability === 'OPS_COORDINATE' && grant.teamId === IDENTITY.teamBravoId)
    );
    check(
      'the second OE cannot see the other Team in its capability truth',
      !grants.some((grant) => grant.teamId === IDENTITY.teamAlphaId)
    );
    await operationsEntry(page).click();
    await queuesNav(page).waitFor({ state: 'visible', timeout: 20000 });
    await settle(page);
    const work = callsTo(network, '/operations/claimable-work').at(-1);
    check('the second OE reads its own scope', work?.status === 200);
    check('the second OE sees no work it does not own', JSON.parse(work.body).totalCount === 0);
    await context.close();
  }

  console.log('\n=== 3. negative controls (new tenant, staff without a grant, City Admin) ===');
  {
    const { context, page, network } = await freshContext(browser);
    const email = `codex.p3a.browser.tenant.${Date.now()}@pathome.test`;
    await registerThroughTheForm(page, email, 'P3aBrowserTenant2026', 'P3A Browser Tenant');
    check('the real registration form established a session', typeof (await storedToken(page)) === 'string');
    check('registration used the real registration endpoint', callsTo(network, '/auth/register')[0]?.status === 200);
    await settle(page, 2500);
    check('a brand-new tenant is never offered Operations', !(await operationsEntry(page).isVisible().catch(() => false)));
    check('a brand-new tenant triggers no protected Operations read', operationsReads(network).length === 0);
    await context.close();
  }
  {
    const { context, page, network } = await freshContext(browser);
    await signInThroughTheForm(page, IDENTITY.nograntEmail, IDENTITY.password);
    await settle(page, 2500);
    const capability = callsTo(network, '/staff/me/capabilities').at(-1);
    check('active staff without a grant still reads its own capability state', capability?.status === 200);
    check(
      'active staff without a grant holds no Team coordination capability',
      (JSON.parse(capability.body).capabilities ?? []).every((grant) => grant.capability !== 'OPS_COORDINATE')
    );
    check('staff without a grant is never offered Operations', !(await operationsEntry(page).isVisible().catch(() => false)));
    check('staff without a grant triggers no protected Operations read', operationsReads(network).length === 0);
    await context.close();
  }
  {
    const { context, page, network } = await freshContext(browser);
    await signInThroughTheForm(page, IDENTITY.cityAdminEmail, IDENTITY.password);
    await settle(page, 2500);
    const capability = callsTo(network, '/staff/me/capabilities').at(-1);
    check('the City Team Admin is active staff', JSON.parse(capability.body).staffActive === true);
    check(
      'City Team Admin authority is not Operations admission',
      (JSON.parse(capability.body).capabilities ?? []).every((grant) => grant.capability !== 'OPS_COORDINATE')
    );
    check('the City Team Admin is never offered Operations', !(await operationsEntry(page).isVisible().catch(() => false)));
    check('the City Team Admin triggers no protected Operations read', operationsReads(network).length === 0);
    await context.close();
  }
} finally {
  await browser.close();
}

console.log(`\n${checks - failures}/${checks} checks passed`);
if (failures > 0) {
  console.error(`Package 3A browser verification: ${failures} failure(s)`);
  process.exit(1);
}
console.log('Package 3A browser verification: all checks passed');
