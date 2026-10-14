/**
 * Package 2 real-backend verification — natural login, capability-gated navigation and the
 * HTTP 401/403 contract.
 *
 * The real built app (`frontend-web/dist`) runs in Chromium against a live Spring Boot backend on
 * PostgreSQL. Static assets are served in-process at a synthetic origin; every `/api/**` call is
 * forwarded verbatim to http://localhost:8080, so authentication, the capability contract and every
 * status code are decided by the real application and the real database. No backend response is
 * fabricated, and nothing is stubbed in the page.
 *
 * Unlike `../operations-workspace-real-backend/run.mjs`, this suite drives the application's **own
 * login form** and the **real navigation** — localStorage is never seeded, so the `ROLE_TENANT` →
 * `TENANT` normalisation the app performs is genuinely exercised.
 *
 * Blocked scenario: with no Operating Team provisioned there is no way to hold an effective
 * `OPS_COORDINATE@TEAM` grant, so the *positive* admitted path cannot be produced against this
 * backend. It is marked BLOCKED — PACKAGE 3 DEPENDENCY below and is exercised in the controlled
 * Chromium suite (`../operations-destination/run.mjs`), which is clearly labelled as simulated.
 *
 * Usage:
 *   npm --prefix frontend-web run build
 *   bash tools/oe-integration-env/run.sh backend      # disposable database, port 8080
 *   node frontend-web/browser-verification/operations-destination-real-backend/run.mjs
 */
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;

const DIST = fileURLToPath(new URL('../../dist/', import.meta.url));
const ORIGIN = 'http://oe.local';
const BACKEND = 'http://localhost:8080';
const SECRETS_PATH = process.env.PATHOME_OE_TEST_SECRETS || '/private/tmp/pathome-oe-test/secrets.env';

const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.json': 'application/json; charset=utf-8'
};

/* ------------------------------------------------------------------ *
 * Checks
 * ------------------------------------------------------------------ */

let failures = 0;
let checks = 0;
const check = (label, passed, detail = '') => {
  checks += 1;
  if (!passed) failures += 1;
  process.stdout.write(`${passed ? 'PASS' : 'FAIL'}  ${label}${detail ? `  (${detail})` : ''}\n`);
};
const checkEqual = (label, actual, expected) =>
  check(label, actual === expected, `expected=${JSON.stringify(expected)} actual=${JSON.stringify(actual)}`);

/** Short, non-reversible label so credential continuity is asserted without ever printing a token. */
const fingerprint = (value) => (value ? createHash('sha256').update(value).digest('hex').slice(0, 12) : 'none');

/* ------------------------------------------------------------------ *
 * Disposable environment credentials (never printed)
 * ------------------------------------------------------------------ */

const parseEnv = (text) =>
  Object.fromEntries(
    text
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line && !line.startsWith('#') && line.includes('='))
      .map((line) => {
        const at = line.indexOf('=');
        return [line.slice(0, at).trim(), line.slice(at + 1).trim()];
      })
  );

let secrets;
try {
  secrets = parseEnv(await readFile(SECRETS_PATH, 'utf8'));
} catch (cause) {
  console.error(`Cannot read the disposable environment credentials at ${SECRETS_PATH}.`);
  console.error('Run `bash tools/oe-integration-env/run.sh provision` first.');
  process.exit(2);
}

const ADMIN_EMAIL = secrets.OE_BOOTSTRAP_ADMIN_EMAIL;
const ADMIN_PASSWORD = secrets.OE_BOOTSTRAP_ADMIN_PASSWORD;
const STAFF_PASSWORD = secrets.OE_STAFF_PASSWORD;
const CITY_TEAM_ADMIN_EMAIL = 'codex.oe.env.cityadmin@pathome.test';
const OPS_INTAKE_EMAIL = 'codex.oe.env.intake@pathome.test';
const NO_GRANT_EMAIL = 'codex.oe.env.nogrant@pathome.test';
const PLAIN_TENANT_EMAIL = 'codex.oe.env.plaintenant@pathome.test';

/* ------------------------------------------------------------------ *
 * Real backend client (out-of-band setup and assertion of the same contract)
 * ------------------------------------------------------------------ */

const api = async (method, path, token, body) => {
  const headers = { Accept: 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(`${BACKEND}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const text = await response.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  return { status: response.status, json };
};

const signInApi = async (email, password) => {
  const { status, json } = await api('POST', '/api/v1/auth/login', null, { email, password });
  if (status !== 200 || !json?.token) throw new Error(`Sign-in failed for ${email} (HTTP ${status})`);
  return json;
};

/* ------------------------------------------------------------------ *
 * Static origin with a verbatim API proxy
 * ------------------------------------------------------------------ */

const reached = [];
let recording = false;
const record = (method, path, status) => {
  if (recording) reached.push({ method, path, status });
};

const server = createServer(async (request, response) => {
  const url = new URL(request.url, ORIGIN);
  const path = url.pathname;

  if (path.startsWith('/api/')) {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const body = Buffer.concat(chunks);
    const headers = { Accept: 'application/json' };
    if (request.headers.authorization) headers.Authorization = request.headers.authorization;
    if (request.headers['content-type']) headers['Content-Type'] = request.headers['content-type'];

    let upstream;
    try {
      upstream = await fetch(`${BACKEND}${path}${url.search}`, {
        method: request.method,
        headers,
        body: ['GET', 'HEAD'].includes(request.method) ? undefined : body
      });
    } catch (cause) {
      record(request.method, path, 0);
      response.writeHead(502, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ error: 'backend unreachable', message: String(cause) }));
      return;
    }
    const text = await upstream.text();
    record(request.method, path, upstream.status);
    response.writeHead(upstream.status, {
      'Content-Type': upstream.headers.get('content-type') || 'application/json',
      'Cache-Control': 'no-store'
    });
    response.end(text);
    return;
  }

  const file = path === '/' ? '/index.html' : path;
  try {
    const contents = await readFile(join(DIST, file));
    response.writeHead(200, { 'Content-Type': CONTENT_TYPES[extname(file)] || 'application/octet-stream' });
    response.end(contents);
  } catch {
    // Single-page application: every unknown path is resolved by the client router.
    const shell = await readFile(join(DIST, 'index.html'));
    response.writeHead(200, { 'Content-Type': CONTENT_TYPES['.html'] });
    response.end(shell);
  }
});

await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const port = server.address().port;

const operationsAttempts = (log) =>
  log.filter((entry) => entry.url().includes('/api/v1/operations/')).length;

const requests = [];
const browser = await chromium.launch({ channel: 'chromium' });
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await context.newPage();
page.on('request', (request) => requests.push(request));
page.on('pageerror', (error) => check('no uncaught page error', false, String(error).slice(0, 160)));

const BASE = `http://127.0.0.1:${port}`;
const appUrl = (path) => `${BASE}${path}`;

const readSession = () =>
  page.evaluate(() => ({
    token: localStorage.getItem('pathome_auth_token'),
    user: JSON.parse(localStorage.getItem('pathome_user') || 'null'),
    role: localStorage.getItem('pathome_role')
  }));

const resetBrowserState = async () => {
  await page.goto(appUrl('/'));
  await page.evaluate(() => {
    localStorage.clear();
    sessionStorage.clear();
  });
};

/** Signs in through the application's own login form. No storage key is ever seeded. */
const signInThroughForm = async (email, password) => {
  await resetBrowserState();
  // Reaching a protected route signed out is the app's own path to the sign-in prompt: the route
  // guard clears the empty session, returns to the public entry and opens the form.
  await page.goto(appUrl('/tenant'));
  await page.getByRole('dialog', { name: /Sign in or create account/i }).waitFor({ state: 'visible', timeout: 20000 });
  await page.locator('#pathome-auth-email').fill(email);
  await page.locator('#pathome-auth-password').fill(password);
  await page.getByRole('button', { name: 'Sign In', exact: true }).first().click();
  await page.waitForFunction(() => Boolean(localStorage.getItem('pathome_auth_token')), null, { timeout: 20000 });
  await page.waitForTimeout(400);
};

/* ================================================================== *
 * 1. Natural login through the real form
 * ================================================================== */

process.stdout.write('\n=== 1. natural login through the real form ===\n');

const cityAdminSession = await signInApi(CITY_TEAM_ADMIN_EMAIL, STAFF_PASSWORD);
recording = true;
reached.length = 0;
await signInThroughForm(CITY_TEAM_ADMIN_EMAIL, STAFF_PASSWORD);
let session = await readSession();

checkEqual('the real login form establishes a session', session.token !== null, true);
checkEqual('the form used the server-issued identity, not a placeholder', session.user?.id, cityAdminSession.userId);
check('the identity is a real positive User id', Number.isSafeInteger(session.user?.id) && session.user.id > 0, `id=${session.user?.id}`);
checkEqual('the backend ROLE_TENANT token normalises to the TENANT client role', session.role, 'TENANT');
checkEqual('sign-in lands on the tenant dashboard, never on /operations', new URL(page.url()).pathname, '/tenant');

/* ================================================================== *
 * 2. Capability-gated entry for every non-OE staff shape
 * ================================================================== */

process.stdout.write('\n=== 2. no Operations entry without OPS_COORDINATE@TEAM ===\n');

const entryCount = async () =>
  (await page.getByRole('button', { name: 'Operations', exact: true }).count()) +
  (await page.getByRole('button', { name: /Operations workspace/i }).count());

checkEqual('the CITY_TEAM_ADMIN@CITY account is offered no Operations entry', await entryCount(), 0);
checkEqual(
  'the gate consulted the live capability endpoint, which answered 200',
  reached.filter((entry) => entry.path === '/api/v1/staff/me/capabilities' && entry.status === 200).length > 0,
  true
);
recording = false;

/* ================================================================== *
 * 3. Direct /operations navigation fails closed
 * ================================================================== */

process.stdout.write('\n=== 3. direct /operations refuses to disclose anything ===\n');

requests.length = 0;
recording = true;
reached.length = 0;

await page.goto(appUrl('/operations'));
await page.waitForTimeout(1200);
recording = false;

const restrictedVisible = await page.getByText(/don.t have access to Operations/i).first().isVisible();
check('the destination reports explicitly that there is no access', restrictedVisible, 'restricted state shown');
checkEqual('the URL is preserved rather than silently redirected', new URL(page.url()).pathname, '/operations');
checkEqual('the browser attempted no protected operations request', operationsAttempts(requests), 0);
checkEqual(
  'no protected operations request reached the backend',
  reached.filter((entry) => entry.path.startsWith('/api/v1/operations/')).length,
  0
);
session = await readSession();
checkEqual('the refused destination does not end the session', session.token !== null, true);

/* ================================================================== *
 * 4. Refresh re-verifies and still fails closed
 * ================================================================== */

process.stdout.write('\n=== 4. browser refresh on the destination ===\n');

requests.length = 0;
recording = true;
reached.length = 0;
await page.reload();
await page.waitForTimeout(1200);
recording = false;

check(
  'a refresh still shows the explicit refusal',
  await page.getByText(/don.t have access to Operations/i).first().isVisible(),
  'restricted state restored'
);
checkEqual('a refresh still discloses no protected work', operationsAttempts(requests), 0);
checkEqual(
  'a refresh still re-verifies the capability with the backend',
  reached.filter((entry) => entry.path === '/api/v1/staff/me/capabilities').length > 0,
  true
);

/* ================================================================== *
 * 5. The reported 401/403 defect, corrected
 * ================================================================== */

process.stdout.write('\n=== 5. permission denial answers 403, never 401 ===\n');

const asPage = async (path) =>
  page.evaluate(async (target) => {
    const token = localStorage.getItem('pathome_auth_token');
    const response = await fetch(target, { headers: { Authorization: `Bearer ${token}` } });
    const text = await response.text();
    let body = null;
    try {
      body = text ? JSON.parse(text) : null;
    } catch {
      body = null;
    }
    return { status: response.status, body };
  }, path);

const groundProbe = await asPage('/api/v1/ground/visit-sessions?page=0&size=20');
checkEqual('an authenticated OE denied the GE console answers 403', groundProbe.status, 403);
checkEqual('the denial carries the standard error contract', groundProbe.body?.status, 403);

const adminProbe = await asPage('/api/v1/admin/failed-uploads?page=0&size=20');
checkEqual('an authenticated account denied /admin/** answers 403', adminProbe.status, 403);

const hiddenProbe = await asPage('/api/v1/properties/999999/tagged-media');
checkEqual('a concealed privileged resource answers 403, not 401', hiddenProbe.status, 403);

const allowedProbe = await asPage('/api/v1/staff/me/capabilities');
checkEqual('a permitted call is unaffected', allowedProbe.status, 200);

const afterDenials = await readSession();
checkEqual(
  'the permission denials did not end the session',
  fingerprint(afterDenials.token),
  fingerprint(session.token)
);
check('no sign-in prompt was raised by a permission denial', !(await page.getByRole('dialog').isVisible().catch(() => false)));

/* ================================================================== *
 * 6. Genuine authentication failure still ends the session (401 preserved)
 * ================================================================== */

process.stdout.write('\n=== 6. a genuine 401 still means the session has ended ===\n');

await page.evaluate(() => localStorage.setItem('pathome_auth_token', 'not-a-valid-token'));
await page.evaluate(() => {
  // A real focus event makes the shared authority revalidate with the tampered credential.
  window.dispatchEvent(new Event('focus'));
});
await page.waitForFunction(() => !localStorage.getItem('pathome_auth_token'), null, { timeout: 20000 }).catch(() => {});

const afterExpiry = await readSession();
checkEqual('the invalid credential ended the session', afterExpiry.token, null);
checkEqual('the identity was cleared with it', afterExpiry.user, null);
check(
  'the sign-in prompt is offered again',
  await page.getByRole('dialog').isVisible().catch(() => false),
  'auth dialog visible'
);

/* ================================================================== *
 * 7. OPS_INTAKE and active-staff-without-grant are refused too
 * ================================================================== */

process.stdout.write('\n=== 7. other staff capability shapes are refused ===\n');

for (const [label, email, password] of [
  ['OPS_INTAKE@CITY (a different capability on the same scope)', OPS_INTAKE_EMAIL, STAFF_PASSWORD],
  ['active staff with no grant', NO_GRANT_EMAIL, STAFF_PASSWORD],
  ['STAFF_ADMIN@GLOBAL (platform authority, not operational)', ADMIN_EMAIL, ADMIN_PASSWORD]
]) {
  await signInThroughForm(email, password);
  checkEqual(`${label}: no Operations entry is offered`, await entryCount(), 0);

  requests.length = 0;
  recording = true;
  reached.length = 0;
  await page.goto(appUrl('/operations'));
  await page.waitForTimeout(1000);
  recording = false;

  check(`${label}: the destination refuses explicitly`, await page.getByText(/don.t have access to Operations/i).first().isVisible());
  checkEqual(`${label}: no protected request is attempted`, operationsAttempts(requests), 0);
}

/* ================================================================== *
 * 8. A plain tenant keeps its own journey
 * ================================================================== */

process.stdout.write('\n=== 8. tenant navigation and dashboard preserved ===\n');

const registerProbe = await api('POST', '/api/v1/auth/register', null, {
  email: PLAIN_TENANT_EMAIL,
  password: STAFF_PASSWORD,
  fullName: 'Codex OE Env Plain Tenant'
});
check(
  'a genuine non-staff tenant identity is available',
  registerProbe.status === 200 || registerProbe.json?.error === 'EMAIL_ALREADY_REGISTERED',
  `HTTP ${registerProbe.status}`
);

await signInThroughForm(PLAIN_TENANT_EMAIL, STAFF_PASSWORD);
checkEqual('a non-staff tenant lands on the tenant dashboard', new URL(page.url()).pathname, '/tenant');
checkEqual('a non-staff tenant is offered no Operations entry', await entryCount(), 0);
check(
  'the tenant dashboard renders its own surface',
  await page.getByRole('heading', { name: /to belong/i }).first().isVisible().catch(() => false),
  'tenant dashboard heading visible'
);

await page.goto(appUrl('/property/1'));
await page.waitForTimeout(800);
check('the public property route still renders', (await page.locator('main, [role="main"]').count()) > 0);
checkEqual('the property route stays on its own path', new URL(page.url()).pathname, '/property/1');

/* ================================================================== *
 * 9. Logout clears the session and re-protects the destination
 * ================================================================== */

process.stdout.write('\n=== 9. logout re-protects the destination ===\n');

await page.goto(appUrl('/tenant'));
await page.waitForTimeout(500);
await page.getByRole('button', { name: /Profile menu for/i }).first().click();
await page.getByRole('button', { name: /Log Out Session/i }).first().click();
await page.waitForTimeout(800);

const afterLogout = await readSession();
checkEqual('logout removed the session token', afterLogout.token, null);
checkEqual('logout removed the stored identity', afterLogout.user, null);

requests.length = 0;
recording = true;
await page.goto(appUrl('/operations'));
await page.waitForTimeout(1200);
recording = false;
checkEqual('a signed-out visitor is returned to the public entry', new URL(page.url()).pathname, '/');
checkEqual('a signed-out visitor is asked to sign in', await page.getByRole('dialog').isVisible().catch(() => false), true);
checkEqual('a signed-out visitor receives no protected data', operationsAttempts(requests), 0);

/* ================================================================== *
 * 10. Guest navigation and responsive integrity
 * ================================================================== */

process.stdout.write('\n=== 10. guest navigation and layout integrity ===\n');

await page.goto(appUrl('/'));
await page.waitForTimeout(900);
checkEqual('a guest still reaches the public landing page', new URL(page.url()).pathname, '/');
check('the landing page renders its discovery surface', (await page.locator('#homes, [id*="homes"]').count()) > 0);
checkEqual('a guest is offered no Operations entry', await entryCount(), 0);

for (const width of [320, 390, 768, 1440]) {
  await page.setViewportSize({ width, height: 900 });
  await page.goto(appUrl('/operations'));
  await page.waitForTimeout(700);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  check(`no horizontal overflow on the refused destination at ${width}px`, overflow <= 1, `overflow=${overflow}px`);
  await page.goto(appUrl('/'));
  await page.waitForTimeout(500);
  const landingOverflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  check(`no horizontal overflow on the landing page at ${width}px`, landingOverflow <= 1, `overflow=${landingOverflow}px`);
}

/* ================================================================== *
 * Blocked: the positive admitted path
 * ================================================================== */

process.stdout.write('\n=== 11. blocked scenarios ===\n');
process.stdout.write(
  'BLOCKED  PACKAGE 3 DEPENDENCY: no Operating Team exists, so no account can hold an effective\n' +
    '         OPS_COORDINATE@TEAM grant and the admitted path cannot be produced against this\n' +
    '         backend. Authority was NOT bypassed. Covered by the controlled Chromium suite in\n' +
    '         ../operations-destination/run.mjs (simulated backend).\n'
);

await browser.close();
server.close();

process.stdout.write(`\n${checks} checks, ${failures} failure(s)\n`);
process.exit(failures === 0 ? 0 : 1);
