/**
 * E7 genuine tenant PUT runner for TenantVisitAvailabilityHttpPostgresTest.
 *
 * The Java test creates the real Spring context, synthetic City/Team/users and disposable schema,
 * then writes mode-0600 `genuine-private.json` into the evidence directory. This runner:
 *   - creates a real tenant Request through the production API;
 *   - mounts the built tenant dashboard in Chromium;
 *   - forwards availability PUTs to the real backend, then drops only the HTTP response;
 *   - reads recovery state through the real tenant history endpoint;
 *   - writes `genuine-done.json`, which the Java test verifies.
 *
 * Usage: node genuine-runner.mjs <evidence-directory>
 */
import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;
const EVIDENCE = process.argv[2];
if (!EVIDENCE) throw new Error('evidence directory is required');
const DIST = fileURLToPath(new URL('../../dist/', import.meta.url));
const APP_ORIGIN = 'http://r1-tenant.local';
const results = [];
let ok = true;
const check = (label, condition, detail = '') => {
  if (!condition) ok = false;
  results.push({ label, ok: Boolean(condition), detail });
};
const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const waitForFile = async (path, timeoutMs) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try { return JSON.parse(await readFile(path, 'utf8')); } catch { await sleep(200); }
  }
  throw new Error(`timed out waiting for ${path}`);
};
const contentType = (path) => ({
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.webp': 'image/webp',
  '.woff2': 'font/woff2'
})[extname(path)] ?? 'application/octet-stream';

const privatePath = join(EVIDENCE, 'genuine-private.json');
const donePath = join(EVIDENCE, 'genuine-done.json');
let browser;
try {
  const fixture = await waitForFile(privatePath, 120_000);
  const backend = fixture.origin;
  const tenantToken = fixture.tenant.token;
  const auth = { Authorization: `Bearer ${tenantToken}` };
  const created = await fetch(`${backend}/api/v1/properties/${fixture.propertyId}/visit-requests`, {
    method: 'POST',
    headers: { ...auth, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      preferredVisitTiming: 'Genuine R1 availability browser check',
      availabilityStartAt: fixture.start,
      availabilityEndAt: fixture.end,
      availabilityZoneId: 'Asia/Kolkata',
      preferredAt: null
    })
  });
  const createdBody = await created.json();
  check('real tenant Request creation', created.status === 201, `${created.status} ${JSON.stringify(createdBody)}`);
  const requestId = createdBody.requestId;
  const title = createdBody.propertyTitle ?? createdBody.listingTitle ?? 'Genuine R1 property';
  const current = await fetch(`${backend}/api/v1/tenant/visit-requests?page=0`, { headers: auth });
  const currentPage = await current.json();
  const row = currentPage.requests.find(item => item.requestId === requestId);
  check('real Request appears in tenant history', Boolean(row), JSON.stringify(row));

  const control = {
    mode: 'ok',
    failListOnce: false,
    displace: false,
    staleListVersion: null,
    puts: [],
    gets: 0,
    holdPut: null
  };
  const page = {
    requestId,
    version: row.version,
    title: row?.propertyTitle ?? createdBody.propertyTitle ?? 'Genuine R1 property'
  };

  const proxy = async (route, request, url) => {
    const path = url.pathname + url.search;
    if (!path.startsWith('/api/')) {
      const file = url.pathname === '/' ? '/index.html' : url.pathname;
      try {
        await route.fulfill({ status: 200, contentType: contentType(file), body: await readFile(join(DIST, file)) });
      } catch {
        await route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: await readFile(join(DIST, 'index.html')) });
      }
      return;
    }
    const headers = { ...(request.headers()), host: new URL(backend).host };
    let body = request.postData() ?? undefined;
    if (request.method() === 'PUT' && url.pathname.endsWith('/availability')) {
      control.puts.push(JSON.parse(body));
      // The rollback case sends the original authorized command unchanged. The test-owned PostgreSQL
      // trigger rejects the real persistence write, so this is not an early version-validation refusal.
    }
    const response = await fetch(`${backend}${path}`, {
      method: request.method(),
      headers,
      body: body === undefined ? undefined : body
    });
    if (request.method() === 'GET' && url.pathname === '/api/v1/tenant/visit-requests') {
      control.gets += 1;
      if (control.failListOnce) {
        control.failListOnce = false;
        await route.fulfill({ status: 503, contentType: 'application/json', body: '{"status":503,"message":"Controlled recovery read failure"}' });
        return;
      }
      if (control.displace || control.staleListVersion !== null) {
        const payload = await response.json();
        const wanted = payload.requests.filter(item => item.requestId === requestId);
        const pageNumber = Number(url.searchParams.get('page') ?? 0);
        if (control.displace) {
          const requests = pageNumber === 0 ? [] : wanted;
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              ...payload,
              requests,
              page: pageNumber,
              hasMore: pageNumber === 0
            })
          });
          return;
        }
        const stale = wanted.map(item => ({ ...item, version: control.staleListVersion }));
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ ...payload, requests: payload.requests.map(item => item.requestId === requestId ? stale[0] : item) })
        });
        return;
      }
    }
    if (request.method() === 'PUT' && url.pathname.endsWith('/availability') && control.mode !== 'ok') {
      // The backend has executed. Lose only the response.
      await route.abort('failed');
      return;
    }
    await route.fulfill({
      status: response.status,
      contentType: response.headers.get('content-type') ?? 'application/json',
      headers: { 'Cache-Control': response.headers.get('cache-control') ?? 'no-store' },
      body: await response.text()
    });
  };

  browser = await chromium.launch({ channel: 'chromium' });
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  await context.addInitScript(({ token, user }) => {
    localStorage.setItem('pathome_auth_token', token);
    localStorage.setItem('pathome_role', 'TENANT');
    localStorage.setItem('pathome_user', JSON.stringify(user));
  }, { token: tenantToken, user: { id: fixture.tenant.id, role: 'TENANT', fullName: 'Genuine R1 Tenant', email: null } });
  const tab = await context.newPage();
  await tab.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.origin === APP_ORIGIN) await proxy(route, route.request(), url);
    else await route.continue();
  });
  const openVisits = async () => {
    await tab.goto(`${APP_ORIGIN}/tenant#visit-history`, { waitUntil: 'domcontentloaded' });
    await tab.getByRole('heading', { name: /A look around/ }).waitFor({ state: 'visible', timeout: 15000 });
    await tab.getByRole('button', { name: 'Update available times' }).first().waitFor({ state: 'visible', timeout: 10000 });
  };
  const card = () => tab.locator('.tenant-v0-request-card').filter({ hasText: page.title }).first();
  const editor = () => card().locator('form');
  const date = fixture.start.slice(0, 10);
  const editWindow = async (startTime, endTime) => {
    const form = editor();
    await form.getByLabel('Start date').fill(date);
    await form.getByLabel('Start time').fill(startTime);
    await form.getByLabel('End date').fill(date);
    await form.getByLabel('End time').fill(endTime);
  };
  const wait = async (predicate, timeout = 12000) => {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      if (await predicate()) return true;
      await tab.waitForTimeout(100);
    }
    return false;
  };

  await openVisits();
  await card().getByRole('button', { name: 'Update available times' }).click();
  control.mode = 'commit-drop';
  await editWindow('11:00', '13:00');
  await editor().getByRole('button', { name: 'Save available times' }).click();
  await tab.getByText(/Current saved times match your earlier request/).waitFor({ state: 'visible', timeout: 15000 });
  check('real committed PUT, dropped response reconciles to the saved values',
    control.puts.length === 1 && control.gets >= 1, JSON.stringify({ puts: control.puts.length, gets: control.gets }));
  check('no second PUT follows the ambiguous outcome', control.puts.length === 1);
  const committedHistory = await fetch(`${backend}/api/v1/tenant/visit-requests?page=0`, { headers: auth });
  const committedRow = (await committedHistory.json()).requests.find(item => item.requestId === requestId);

  control.mode = 'rollback-drop';
  await editor().getByRole('button', { name: 'Discard editing' }).click();
  await card().getByRole('button', { name: 'Update available times' }).click();
  await editWindow('12:00', '13:00');
  await editor().getByRole('button', { name: 'Save available times' }).click();
  await tab.getByText(/The saved times differ from your earlier request/).waitFor({ state: 'visible', timeout: 15000 });
  check('real refused PUT, dropped response keeps a review-required draft',
    control.puts.length === 2
    && await editor().getByRole('button', { name: 'Save available times' }).isDisabled());
  const rollbackHistory = await fetch(`${backend}/api/v1/tenant/visit-requests?page=0`, { headers: auth });
  const rollbackRow = (await rollbackHistory.json()).requests.find(item => item.requestId === requestId);
  check('the original authorized rollback-origin PUT kept its expected version',
    control.puts[1]?.expectedRequestVersion === committedRow?.version,
    JSON.stringify({ sent: control.puts[1]?.expectedRequestVersion, committed: committedRow?.version }));
  check('the real persistence failure left the committed values and version unchanged',
    rollbackRow?.version === committedRow?.version
    && rollbackRow?.availabilityStartAt === committedRow?.availabilityStartAt
    && rollbackRow?.availabilityEndAt === committedRow?.availabilityEndAt
    && rollbackRow?.availabilityZoneId === committedRow?.availabilityZoneId
    && rollbackRow?.preferredAt === committedRow?.preferredAt,
    JSON.stringify({ committed: committedRow, afterRollback: rollbackRow }));

  control.mode = 'commit-drop';
  control.failListOnce = true;
  await tab.getByRole('button', { name: 'Recheck saved times' }).click();
  await tab.getByText(/latest saved times could not be read/i).waitFor({ state: 'visible', timeout: 15000 });
  check('real recovery read failure remains readable and unresolved',
    await tab.getByRole('button', { name: 'Recheck saved times' }).isVisible());
  check('failure does not resend the PUT', control.puts.length === 2);
  await editor().getByRole('button', { name: 'Use saved times as my starting point' }).click();

  control.mode = 'commit-drop';
  const beforeClear = control.puts.length;
  await editor().getByRole('button', { name: 'Clear saved times' }).click();
  await editor().getByRole('button', { name: 'Yes, clear times' }).click();
  await tab.getByText(/The saved times differ from your earlier request|Current saved times match/).first()
    .waitFor({ state: 'visible', timeout: 15000 });
  check('clear is one real PUT and no automatic retry', control.puts.length === beforeClear + 1);
  const cleared = await fetch(`${backend}/api/v1/tenant/visit-requests?page=0`, { headers: auth });
  const clearedPage = await cleared.json();
  const clearedRow = clearedPage.requests.find(item => item.requestId === requestId);
  check('clear persisted all four authoritative nulls',
    clearedRow.availabilityStartAt === null && clearedRow.availabilityEndAt === null
    && clearedRow.availabilityZoneId === null && clearedRow.preferredAt === null,
    JSON.stringify(clearedRow));

  // A delayed older history page must not regress the newer accepted facts.
  control.staleListVersion = clearedRow.version - 1;
  await tab.evaluate(() => window.dispatchEvent(new Event('pathome_visit_request_created')));
  await tab.waitForTimeout(1200);
  const visibleNull = await tab.getByText(/No structured availability is saved yet/).first().isVisible().catch(() => false);
  check('late older history cannot resurrect the cleared window', visibleNull);
  control.staleListVersion = null;

  // Displacement from page zero still finds the exact Request on a later existing page.
  if (await tab.locator('.tenant-v0-request-card form').count()) {
    await tab.locator('.tenant-v0-request-card form').getByRole('button', { name: 'Discard editing' }).click();
  }
  await card().getByRole('button', { name: 'Update available times' }).click();
  await editWindow('10:00', '13:00');
  control.displace = true;
  await tab.evaluate(() => window.dispatchEvent(new Event('pathome_visit_request_created')));
  await tab.getByRole('heading', { name: 'Availability updates in progress' }).waitFor({ state: 'visible', timeout: 12000 });
  check('displaced Request keeps the one active draft visible', await tab.locator('.tenant-v0-request-card form').isVisible());
  control.displace = false;
  await tab.evaluate(() => window.dispatchEvent(new Event('pathome_visit_request_created')));
  await card().waitFor({ state: 'visible', timeout: 12000 });
  if (await tab.locator('.tenant-v0-request-card form').count()) {
    await tab.locator('.tenant-v0-request-card form').getByRole('button', { name: 'Discard editing' }).click();
  }
  await card().getByRole('button', { name: 'Update available times' }).click();
  await editWindow('11:30', '13:00');

  // Account switch while a real PUT is in flight: no old-account publication into the next view.
  control.mode = 'commit-drop';
  const beforeSwitch = control.puts.length;
  const form = tab.locator('.tenant-v0-request-card form').first();
  if (await form.count()) {
    await form.getByRole('button', { name: 'Save available times' }).click();
  }
  await wait(() => control.puts.length > beforeSwitch);
  await tab.evaluate(({ token, user }) => {
    localStorage.setItem('pathome_auth_token', token);
    localStorage.setItem('pathome_role', 'TENANT');
    localStorage.setItem('pathome_user', JSON.stringify(user));
    window.dispatchEvent(new CustomEvent('pathome_auth_changed'));
    window.dispatchEvent(new StorageEvent('storage', {
      key: 'pathome_user',
      newValue: JSON.stringify(user),
      storageArea: localStorage
    }));
  }, { token: fixture.ge.token, user: { id: fixture.ge.id, role: 'GROUND_BOY', fullName: 'Genuine R1 GE', email: null } });
  await tab.waitForTimeout(1200);
  check('account switch publishes no old tenant draft or saved window',
    (await tab.getByText(/Current saved times match/).count()) === 0
    && (await tab.locator('.tenant-v0-request-card form').count()) === 0);

  await writeFile(donePath, JSON.stringify({ ok, checks: results }), { mode: 0o600 });
  await context.close();
} catch (error) {
  ok = false;
  results.push({ label: 'runner exception', ok: false, detail: error.stack ?? error.message });
  await writeFile(donePath, JSON.stringify({ ok: false, error: error.message, checks: results }), { mode: 0o600 });
} finally {
  await browser?.close();
}
