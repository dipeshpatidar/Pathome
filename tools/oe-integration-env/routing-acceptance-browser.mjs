/**
 * Focused genuine React -> Spring -> PostgreSQL routing acceptance.
 *
 * Serves the production bundle and streams real backend responses through a loopback-only proxy.
 * It performs real UI login and does not fulfill/intercept API responses.
 */
import { createServer, request as httpRequest } from 'node:http';
import { readFile, realpath, writeFile } from 'node:fs/promises';
import { extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { verifyBinding } from './lib/runtime.mjs';
import {
  assertExpectedStatus,
  assertRequiredBody,
  awaitSettled,
  requireObservedResponse,
  RoutingHttpContractError,
  validateObservedResponses
} from './lib/routing-http-contract.mjs';

if (process.env.PATHOME_ROUTING_ACCEPTANCE_BROWSER !== 'true') {
  console.error('REFUSED: explicit genuine browser opt-in required');
  process.exit(90);
}

const run = verifyBinding();
const dist = await realpath(fileURLToPath(new URL('../../frontend-web/dist', import.meta.url)));
const retryable = new Set(['ECONNRESET', 'EPIPE', 'ECONNREFUSED']);
let server;
let browser;
let holdNextAuthority = false;
let authorityHeldResolve;
let authorityHeld = new Promise(resolveHeld => { authorityHeldResolve = resolveHeld; });
const checks = [];
const forwarded = [];
let apiRequests = 0;
const apiResponses = [];
const check = (name, condition, detail = null) => {
  checks.push({ name, result: condition ? 'PASS' : 'FAIL', ...(detail === null ? {} : { detail }) });
  if (!condition) throw Error(name);
};

server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://127.0.0.1');
    if (url.pathname.startsWith('/api/')) {
      verifyBinding();
      const upstream = httpRequest(run.origin + url.pathname + url.search, {
        method: req.method,
        headers: { ...req.headers, host: new URL(run.origin).host },
        timeout: 15000
      }, incoming => {
        forwarded.push({ method: req.method, path: url.pathname, status: incoming.statusCode });
        if (holdNextAuthority && url.pathname === '/api/v1/staff/me/capabilities') {
          holdNextAuthority = false;
          incoming.pause();
          authorityHeldResolve();
          setTimeout(() => {
            if (!res.destroyed) {
              res.writeHead(incoming.statusCode, incoming.headers);
              incoming.pipe(res);
            } else incoming.destroy();
          }, 1500);
          return;
        }
        res.writeHead(incoming.statusCode, incoming.headers);
        incoming.pipe(res);
      });
      upstream.on('timeout', () => upstream.destroy(Error('proxy timeout')));
      upstream.on('error', () => { if (!res.destroyed) res.destroy(); });
      req.pipe(upstream);
      return;
    }
    if (req.method !== 'GET') { res.destroy(); return; }
    let file;
    try {
      file = await realpath(resolve(dist, '.' + decodeURIComponent(url.pathname)));
      if (!file.startsWith(dist + '/')) throw Error('invalid asset');
    } catch {
      file = resolve(dist, 'index.html');
    }
    const types = { '.html':'text/html', '.js':'text/javascript', '.css':'text/css',
      '.svg':'image/svg+xml', '.png':'image/png', '.woff2':'font/woff2' };
    res.writeHead(200, { 'Content-Type': types[extname(file)] ?? 'application/octet-stream' });
    res.end(await readFile(file));
  } catch {
    if (!res.destroyed) res.destroy();
  }
});

try {
  const playwright = await import('/opt/homebrew/lib/node_modules/playwright/index.js');
  const chromium = playwright.chromium ?? playwright.default?.chromium;
  if (!chromium) throw Error('Playwright Chromium is unavailable');
  browser = await chromium.launch({ channel: 'chromium', timeout: 30000 });
  await new Promise((resolveReady, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolveReady);
  });
  const origin = 'http://127.0.0.1:' + server.address().port;
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, reducedMotion: 'reduce' });
  await context.route('**/*', route =>
    new URL(route.request().url()).origin === origin ? route.continue() : route.abort('blockedbyclient'));
  context.setDefaultTimeout(15000);
  const page = await context.newPage();
  page.on('response', response => {
    const url = new URL(response.url());
    if (url.origin === origin && url.pathname.startsWith('/api/')) {
      apiRequests += 1;
      apiResponses.push({ method: response.request().method(), path: url.pathname, status: response.status(),
        cacheControl: response.headers()['cache-control'] ?? null });
    }
  });
  const waitRequired = (method, path) => {
    const promise = page.waitForResponse(response =>
      response.request().method() === method
      && new URL(response.url()).pathname === new URL(path, origin).pathname);
    return awaitSettled(promise, `${method} ${path}`).then(async response => {
      assertExpectedStatus(method, path, response.status());
      if (response.status() !== 204) {
        const text = await response.text();
        let body;
        try {
          body = JSON.parse(text);
        } catch {
          throw new RoutingHttpContractError(`${method} ${path} did not return JSON`);
        }
        assertRequiredBody(method, path, body);
        return { response, body };
      }
      return { response, body: null };
    });
  };

  await page.goto(origin);
  await page.getByRole('button', { name: 'Sign in', exact: true }).first().click();
  const dialog = page.getByRole('dialog').first();
  await dialog.locator('input[type=email]').fill(run.secrets.OE_BOOTSTRAP_ADMIN_EMAIL);
  await dialog.locator('input[type=password]').fill(run.secrets.OE_BOOTSTRAP_ADMIN_PASSWORD);
  const capabilities = waitRequired('GET', '/api/v1/staff/me/capabilities');
  capabilities.catch(() => {});
  const login = waitRequired('POST', '/api/v1/auth/login');
  login.catch(() => {});
  await dialog.getByRole('button', { name: 'Sign In', exact: true }).click();
  await login;
  const capabilityFacts = (await capabilities).body;
  check('real authenticated staff capability read',
    capabilityFacts.staffActive === true && capabilityFacts.capabilities.some(entry =>
      entry.capability === 'STAFF_ADMIN' && entry.scopeType === 'GLOBAL'));

  const routingResponse = waitRequired('GET',
    '/api/v1/operations/automatic-routing/tasks');
  const unclaimedResponse = waitRequired('GET',
    '/api/v1/operations/automatic-routing/tasks/unclaimed');
  routingResponse.catch(() => {});
  unclaimedResponse.catch(() => {});
  await page.goto(origin + '/operations/routing');
  const realRoutingPage = (await routingResponse).response;
  const unclaimed = (await unclaimedResponse);
  check('City routing queue came from the real Spring/PostgreSQL route',
    realRoutingPage.status() === 200 && realRoutingPage.headers()['cache-control'] === 'no-store');
  check('unclaimed-work response settled as a valid 200 page',
    unclaimed.response.status() === 200 && Array.isArray(unclaimed.body.items));
  await page.getByRole('heading', { name: 'Routing queue' }).waitFor();
  check('routing queue and decision surfaces rendered',
    await page.getByRole('heading', { name: 'Routing decision' }).isVisible());

  const queueBeforeRefresh = await page.getByRole('heading', { name: 'Routing queue' }).isVisible();
  const focusCapability = waitRequired('GET', '/api/v1/staff/me/capabilities');
  focusCapability.catch(() => {});
  holdNextAuthority = true;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await Promise.race([
    authorityHeld,
    new Promise((_, reject) => setTimeout(() => reject(Error('focus revalidation was not observed')), 3000))
  ]);
  check('held focus revalidation leaves the routing queue visible',
    queueBeforeRefresh && await page.getByRole('heading', { name: 'Routing queue' }).isVisible());
  check('held focus revalidation does not replace the page with a skeleton',
    await page.locator('main[aria-label="Operations"]').isVisible());

  const widths = [320, 375, 390, 430, 768, 1024, 1280, 1440];
  const layout = [];
  for (const width of widths) {
    await page.setViewportSize({ width, height: 900 });
    await page.evaluate(() => new Promise(resolveFrame => requestAnimationFrame(() =>
      requestAnimationFrame(resolveFrame))));
    const measured = await page.evaluate(() => ({
      viewport: innerWidth,
      scrollWidth: document.documentElement.scrollWidth,
      smallTargets: [...document.querySelectorAll('button, a[href]')]
        .map(node => node.getBoundingClientRect())
        .filter(rect => rect.width > 0 && rect.height > 0 && (rect.width < 44 || rect.height < 44)).length,
      visibleHeading: document.querySelector('h2')?.textContent ?? ''
    }));
    layout.push({ width, ...measured });
    check(`no horizontal overflow at ${width}px`, measured.scrollWidth <= measured.viewport,
      measured.scrollWidth + ' <= ' + measured.viewport);
  }
  check('routing content remained visible across all responsive widths',
    layout.every(entry => entry.visibleHeading.includes('Routing')));

  await page.setViewportSize({ width: 1280, height: 900 });
  const focusable = page.locator('button:visible, a[href]:visible').first();
  await focusable.focus();
  check('visible keyboard focus remains available', await focusable.evaluate(node => {
    const style = getComputedStyle(node);
    return node === document.activeElement && style.outlineStyle !== 'none' && style.opacity !== '0';
  }));

  await focusCapability;
  await page.waitForTimeout(300);
  validateObservedResponses(apiResponses);
  requireObservedResponse(apiResponses, 'GET',
    '/api/v1/operations/automatic-routing/tasks/unclaimed');
  check('every observed API response had its exact expected status', true,
    `${apiResponses.length} API responses`);
  check('every browser API request reached the real loopback proxy',
    apiRequests > 0 && forwarded.length >= apiRequests
      && apiResponses.every(response => forwarded.some(entry =>
        entry.method === response.method && entry.path === response.path
        && entry.status === response.status)));
  check('required unclaimed response was not hidden by a late failure',
    apiResponses.filter(entry =>
      entry.path === '/api/v1/operations/automatic-routing/tasks/unclaimed'
      && entry.status !== 200).length === 0);
  const evidence = {
    kind: 'GENUINE_REACT_SPRING_POSTGRES_ROUTING_SMOKE',
    completedAtIST: new Date().toISOString(),
    checks,
    layout,
    apiRequests: apiResponses.map(entry => ({ ...entry })),
    forwarded: forwarded.map(entry => ({ ...entry }))
  };
  const evidenceFile = run.root + '/evidence/routing-connected-browser-'
    + new Date().toISOString().replace(/[:.]/g, '-') + '.json';
  await writeFile(evidenceFile, JSON.stringify(evidence, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
  console.log(`PASS ${checks.filter(entry => entry.result === 'PASS').length}/${checks.length} genuine browser checks`);
  console.log(`EVIDENCE ${evidenceFile}`);
} catch (error) {
  checks.push({ name: 'browser acceptance completed without an unexpected failure',
    result: 'FAIL', detail: error instanceof Error ? error.message : String(error) });
  const evidenceFile = run.root + '/evidence/routing-connected-browser-'
    + new Date().toISOString().replace(/[:.]/g, '-') + '.json';
  await writeFile(evidenceFile, JSON.stringify({
    kind: 'GENUINE_REACT_SPRING_POSTGRES_ROUTING_SMOKE',
    completedAtIST: new Date().toISOString(),
    result: 'FAIL',
    checks,
    apiRequests: apiResponses,
    forwarded,
    error: error instanceof Error ? error.message : String(error)
  }, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
  console.error(`FAIL ${error instanceof Error ? error.message : String(error)}`);
  console.error(`EVIDENCE ${evidenceFile}`);
  process.exitCode = 1;
} finally {
  await browser?.close().catch(() => {});
  await new Promise(resolveClose => server?.close(resolveClose) ?? resolveClose());
}
