/**
 * Genuine C07 routing grant/account-switch acceptance.
 *
 * Real Chromium -> real React bundle -> loopback proxy -> Spring Boot -> PostgreSQL.
 * Accounts and grants are created through production authentication/Admin APIs. One A capability
 * response is held at the loopback proxy while B authenticates and starts its own read.
 */
import { createServer, request as httpRequest } from 'node:http';
import { readFile, realpath, writeFile } from 'node:fs/promises';
import { extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { verifyBinding } from './lib/runtime.mjs';

if (process.env.PATHOME_ROUTING_C07_BROWSER !== 'true') {
  console.error('REFUSED: explicit genuine C07 browser opt-in required');
  process.exit(90);
}

const run = verifyBinding();
const dist = await realpath(fileURLToPath(new URL('../../frontend-web/dist', import.meta.url)));
const random = () => crypto.randomUUID().replaceAll('-', '');
const checks = [];
const events = [];
const apiReceipts = [];
const check = (name, condition, detail = null) => {
  checks.push({ name, result: condition ? 'PASS' : 'FAIL', ...(detail === null ? {} : { detail }) });
  if (!condition) throw Error(name + (detail ? ': ' + detail : ''));
};
const record = (name, detail = {}) => events.push({ at: new Date().toISOString(), name, ...detail });
const receipt = (name, value) => {
  apiReceipts.push({ at: new Date().toISOString(), name, ...value });
  return value;
};

const tokenIdentity = (token) => {
  const payload = JSON.parse(Buffer.from(String(token).split('.')[1], 'base64url'));
  return { subject: payload.sub, userId: Number(payload.user_id) };
};

async function api(method, path, token = null, body = undefined) {
  const response = await fetch(run.origin + '/api/v1' + path, {
    method,
    headers: {
      Accept: 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' })
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    redirect: 'error',
    signal: AbortSignal.timeout(15000)
  });
  const text = await response.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* exact assertions below */ }
  return { status: response.status, json, text, cacheControl: response.headers.get('cache-control') };
}

async function login(email, password) {
  const response = await api('POST', '/auth/login', null, { email, password });
  check(`real login for ${email}`, response.status === 200 && typeof response.json?.token === 'string',
    `status=${response.status}`);
  return { ...response.json, password };
}

async function capabilityReceipt(account) {
  const identity = tokenIdentity(account.token);
  const response = await api('GET', '/staff/me/capabilities', account.token);
  check(`${account.label} capability read authenticates its current User`,
    response.status === 200 && identity.userId === account.userId
      && identity.subject === account.email,
    JSON.stringify({ identity, status: response.status }));
  check(`${account.label} capability response is structurally valid`,
    response.json?.staffActive === true && Array.isArray(response.json?.capabilities),
    response.text);
  return receipt(`capabilities:${account.label}`, {
    identity,
    status: response.status,
    cacheControl: response.cacheControl,
    body: response.json
  });
}

async function registerUser(label) {
  const suffix = random().slice(0, 12);
  const email = `codex.routing.c07.${label}.${suffix}@pathome.test`;
  const password = `Synthetic-${suffix}-Pass`;
  const response = await api('POST', '/auth/register', null, {
    email, password, fullName: `C07 ${label}`, phoneNumber: `+9198${suffix.slice(0, 8)}`
  });
  check(`real public registration for account ${label}`, response.status === 200
    && response.json?.role === 'ROLE_TENANT', `status=${response.status}`);
  const activation = await api('POST', `/staff/admin/users/${response.json.userId}/activate`,
    owner.token, { expectedVersion: null, reasonCode: 'C07_STAFF_ACTIVATION' });
  check(`supported Admin activation for account ${label}`, activation.status === 200
    && activation.json?.staffActive === true, activation.text);
  return { label, userId: response.json.userId, email, password, token: response.json.token };
}

async function grant(account, capability, cityId, teamId) {
  const body = {
    capability, scopeType: capability === 'OPS_ROUTING' ? 'CITY' : 'TEAM',
    cityId: capability === 'OPS_ROUTING' ? cityId : null,
    teamId: capability === 'OPS_COORDINATE' ? teamId : null,
    effectiveAt: null, expiresAt: null,
    reasonCode: `C07_${capability}_${account.label}`
  };
  const response = await api('POST', `/staff/admin/users/${account.userId}/grants`, owner.token, body);
  check(`Admin grants ${capability} to ${account.label}`, response.status === 200
    && response.json?.capability === capability && response.json?.revokedAt === null, response.text);
  return response.json.id;
}

async function revoke(grantId, reason) {
  const response = await api('POST', `/staff/admin/grants/${grantId}/revoke`,
    owner.token, { reasonCode: reason });
  check(`Admin revokes grant ${grantId}`, response.status === 200 && response.json?.revokedAt !== null,
    response.text);
}

const ownerLogin = await api('POST', '/auth/login', null, {
  email: run.secrets.OE_BOOTSTRAP_ADMIN_EMAIL,
  password: run.secrets.OE_BOOTSTRAP_ADMIN_PASSWORD
});
check('bootstrap Owner authenticates through the real login endpoint',
  ownerLogin.status === 200 && typeof ownerLogin.json?.token === 'string',
  `status=${ownerLogin.status}`);
const owner = ownerLogin.json;

const citiesResponse = await api('GET',
  '/staff/admin/operating-teams/cities?page=0&size=100', owner.token);
check('Owner reads active canonical Cities for C07 fixtures',
  citiesResponse.status === 200 && Array.isArray(citiesResponse.json?.items),
  citiesResponse.text);
const cityPair = citiesResponse.json.items.filter(city => city.active === true).slice(0, 2);
check('C07 has two active canonical Cities', cityPair.length === 2,
  JSON.stringify(cityPair.map(city => city.id)));

async function createTeam(city, label) {
  const suffix = random().slice(0, 10);
  const response = await api('POST', `/staff/admin/cities/${city.id}/operating-teams`,
    owner.token, {
      code: `c07-${label.toLowerCase()}-${suffix}`,
      displayName: `C07 ${label} Team ${suffix}`,
      expectedCityVersion: city.version,
      reasonCode: 'C07_SYNTHETIC_TEAM'
    });
  check(`Admin creates C07 ${label} Team through the governed API`,
    response.status === 201 && response.json?.cityId === city.id, response.text);
  return response.json;
}

async function createCoverage(city, team, label) {
  const options = await api('GET',
    `/operations/automatic-routing/coverage/options?cityId=${city.id}`, owner.token);
  check(`Owner reads canonical coverage options for City ${label}`,
    options.status === 200 && options.json?.localities?.length > 0, options.text);
  const locality = options.json.localities[0];
  const response = await api('POST', '/operations/automatic-routing/coverage', owner.token, {
    cityId: city.id, localityId: locality.id, teamId: team.id,
    reasonCode: `C07_COVERAGE_${label}`
  });
  check(`Owner creates genuine C07 ${label} coverage`,
    response.status === 200 && response.json?.cityId === city.id
      && response.json?.teamId === team.id, response.text);
  return response.json;
}

const teamA = await createTeam(cityPair[0], 'A');
const teamB = await createTeam(cityPair[1], 'B');
const coverageA = await createCoverage(cityPair[0], teamA, 'A');
const coverageB = await createCoverage(cityPair[1], teamB, 'B');
const first = { cityId: cityPair[0].id, teamId: teamA.id, privateLabel: teamA.displayName };
const second = { cityId: cityPair[1].id, teamId: teamB.id, privateLabel: teamB.displayName };

const accountA = await registerUser('A');
const accountB = await registerUser('B');
const accountC = await registerUser('C');
const accountD = await registerUser('D');
let aCityGrant = await grant(accountA, 'OPS_ROUTING', first.cityId, first.teamId);
let aTeamGrant = await grant(accountA, 'OPS_COORDINATE', first.cityId, first.teamId);
let bCityGrant = await grant(accountB, 'OPS_ROUTING', second.cityId, second.teamId);
let bTeamGrant = await grant(accountB, 'OPS_COORDINATE', second.cityId, second.teamId);
const cTeamGrant = await grant(accountC, 'OPS_COORDINATE', first.cityId, first.teamId);
const dCityGrant = await grant(accountD, 'OPS_ROUTING', second.cityId, second.teamId);

let caps = await capabilityReceipt(accountA);
check('A has independently current City routing and Team coordination grants',
  caps.body.capabilities.some(entry => entry.capability === 'OPS_ROUTING'
    && entry.scopeType === 'CITY' && entry.cityId === first.cityId)
  && caps.body.capabilities.some(entry => entry.capability === 'OPS_COORDINATE'
    && entry.scopeType === 'TEAM' && entry.teamId === first.teamId),
  JSON.stringify(caps.body.capabilities));
caps = await capabilityReceipt(accountB);
check('B starts with its own valid City routing and Team coordination grants',
  caps.body.capabilities.some(entry => entry.capability === 'OPS_ROUTING'
    && entry.scopeType === 'CITY' && entry.cityId === second.cityId)
  && caps.body.capabilities.some(entry => entry.capability === 'OPS_COORDINATE'
    && entry.scopeType === 'TEAM' && entry.teamId === second.teamId),
  JSON.stringify(caps.body.capabilities));
caps = await capabilityReceipt(accountC);
check('C is a valid synthetic actor with Team coordination and no City routing grant',
  caps.body.capabilities.some(entry => entry.capability === 'OPS_COORDINATE'
    && entry.scopeType === 'TEAM' && entry.teamId === first.teamId)
  && !caps.body.capabilities.some(entry => entry.capability === 'OPS_ROUTING'),
  JSON.stringify(caps.body.capabilities));
caps = await capabilityReceipt(accountD);
check('D is a same-role routing actor with only its independently scoped City grant',
  caps.body.capabilities.filter(entry => entry.capability === 'OPS_ROUTING').length === 1
  && caps.body.capabilities.some(entry => entry.capability === 'OPS_ROUTING'
    && entry.scopeType === 'CITY' && entry.cityId === second.cityId),
  JSON.stringify(caps.body.capabilities));

let status = await api('GET', '/operations/automatic-routing/tasks?page=0&size=100', accountA.token);
check('City routing grant admits a current scoped queue read', status.status === 200
  && status.cacheControl === 'no-store', status.text);
receipt('A genuine routing queue', { status: status.status, cacheControl: status.cacheControl, body: status.json });
status = await api('GET', '/operations/claimable-work?page=0&size=100', accountA.token);
check('Team coordination grant admits a current claimable-work read', status.status === 200, status.text);
receipt('A genuine Team claimable work', { status: status.status, body: status.json });
check('account B performs its own valid City routing read before revocation',
  (await api('GET', '/operations/automatic-routing/tasks?page=0&size=20',
    accountB.token)).status === 200);
check('account B performs its own valid Team claimability read before revocation',
  (await api('GET', '/operations/claimable-work?page=0&size=20',
    accountB.token)).status === 200);
status = await api('GET', '/operations/automatic-routing/tasks', accountC.token);
check('Team-only C is denied the City routing queue by the backend',
  status.status === 403 && status.json?.status === 403
    && !String(status.json?.message ?? '').toLowerCase().includes('exception'), status.text);
status = await api('GET', '/operations/claimable-work?page=0&size=20', accountC.token);
check('Team-only C retains independent Team duty authority',
  status.status === 200, status.text);
receipt('Team-only C independent claimable work', {
  status: status.status,
  cacheControl: status.cacheControl,
  body: status.json
});
status = await api('GET', '/operations/automatic-routing/tasks', accountD.token);
check('Wrong-City actor D can read only its own authorized City routing queue',
  status.status === 200 && status.cacheControl === 'no-store', status.text);
status = await api('GET',
  `/operations/automatic-routing/coverage?cityId=${first.cityId}`, accountD.token);
check('wrong-City coverage denial is a sanitized contracted 403',
  status.status === 403 && status.json?.status === 403
    && !String(status.json?.message ?? '').toLowerCase().includes('exception'), status.text);
receipt('D wrong-City coverage denial', {
  identity: tokenIdentity(accountD.token),
  requestedCityId: first.cityId,
  status: status.status,
  cacheControl: status.cacheControl,
  body: status.json
});
status = await api('GET',
  `/operations/automatic-routing/coverage/options?cityId=${first.cityId}`, accountD.token);
check('wrong-City coverage options are denied without partial protected data',
  status.status === 403 && (status.json?.items === undefined)
    && !JSON.stringify(status.json ?? {}).includes(teamA.displayName), status.text);
status = await api('GET', '/operations/automatic-routing/coverage/cities', accountD.token);
check('D canonical coverage City list contains only its authorized City',
  status.status === 200 && Array.isArray(status.json)
    && status.json.length > 0 && status.json.every(city => Number(city.id) === second.cityId),
  status.text);
receipt('D authorized coverage Cities', {
  identity: tokenIdentity(accountD.token),
  status: status.status,
  body: status.json
});
status = await api('GET', '/operations/automatic-routing/tasks', 'not-a-valid-session-token');
check('an unauthenticated routing read receives a real 401 denial',
  status.status === 401, status.text);

await revoke(aCityGrant, 'C07_REVOKE_CITY_FIRST');
check('City-first revoke denies City routing',
  (await api('GET', '/operations/automatic-routing/tasks', accountA.token)).status === 403);
check('City-first revoke preserves Team coordination',
  (await api('GET', '/operations/claimable-work?page=0&size=20', accountA.token)).status === 200);
aCityGrant = await grant(accountA, 'OPS_ROUTING', first.cityId, first.teamId);

await revoke(aTeamGrant, 'C07_REVOKE_TEAM_FIRST');
check('Team-first revoke denies Team coordination',
  (await api('GET', '/operations/claimable-work?page=0&size=20', accountA.token)).status === 403);
check('Team-first revoke preserves City routing',
  (await api('GET', '/operations/automatic-routing/tasks?page=0&size=20', accountA.token)).status === 200);
aTeamGrant = await grant(accountA, 'OPS_COORDINATE', first.cityId, first.teamId);
await revoke(bCityGrant, 'C07_B_CITY_REVOKED');
await revoke(bTeamGrant, 'C07_B_TEAM_REVOKED');

let server;
let browser;
let page;
let capabilityFault = null;
const browserRequests = [];
const browserResponses = [];

const requestIdentity = (req) => {
  const token = String(req.headers.authorization ?? '').replace(/^Bearer\s+/i, '');
  if (!token || token === 'not-a-valid-session-token') return null;
  try { return tokenIdentity(token); } catch { return null; }
};

const armCapabilityFault = (userId, kind) => {
  check('no previous controlled capability fault is still armed', capabilityFault === null);
  let markArmed;
  let release;
  const armed = new Promise(resolve => { markArmed = resolve; });
  const released = new Promise(resolve => { release = resolve; });
  const fault = {
    userId,
    kind,
    consumed: false,
    aborted: false,
    actualStatus: null,
    armed,
    markArmed,
    released,
    release(override) {
      release(override);
      if (capabilityFault === fault) capabilityFault = null;
    }
  };
  capabilityFault = fault;
  return fault;
};

const waitForFaultArmed = async fault => {
  await Promise.race([fault.armed, new Promise((_, reject) => setTimeout(
    () => reject(Error('controlled capability response was not intercepted')), 5000
  ))]);
};

server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://127.0.0.1');
    if (url.pathname.startsWith('/api/')) {
      verifyBinding();
      const identity = requestIdentity(req);
      browserRequests.push({
        at: new Date().toISOString(),
        method: req.method,
        path: url.pathname,
        query: Object.fromEntries(url.searchParams),
        identity
      });
      const upstream = httpRequest(run.origin + url.pathname + url.search, {
        method: req.method,
        headers: { ...req.headers, host: new URL(run.origin).host },
        timeout: 15000
      }, incoming => {
        browserResponses.push({
          at: new Date().toISOString(),
          method: req.method,
          path: url.pathname,
          query: Object.fromEntries(url.searchParams),
          identity,
          status: incoming.statusCode,
          cacheControl: incoming.headers['cache-control'] ?? null
        });
        const fault = capabilityFault;
        if (fault && !fault.consumed
            && url.pathname === '/api/v1/staff/me/capabilities'
            && identity?.userId === fault.userId) {
          fault.consumed = true;
          fault.actualStatus = incoming.statusCode;
          incoming.pause();
          record('CONTROLLED_CAPABILITY_INTERCEPTED', {
            kind: fault.kind,
            identity,
            actualStatus: incoming.statusCode
          });
          res.on('close', () => {
            if (!res.writableEnded) fault.aborted = true;
          });
          fault.markArmed();
          fault.released.then(override => {
            if (res.destroyed) {
              fault.aborted = true;
              record('CONTROLLED_CAPABILITY_RELEASED', {
                kind: fault.kind,
                identity,
                actualStatus: incoming.statusCode,
                deliveredStatus: null,
                aborted: true
              });
              incoming.destroy();
              return;
            }
            if (override?.destroy) {
              incoming.destroy();
              res.destroy();
              return;
            }
            const status = override?.status ?? incoming.statusCode;
            const headers = {
              ...incoming.headers,
              ...(override?.body === undefined ? {} : {
                'content-type': 'application/json',
                'content-length': String(Buffer.byteLength(JSON.stringify(override.body))),
                'cache-control': 'no-store'
              })
            };
            record('CONTROLLED_CAPABILITY_RELEASED', {
              kind: fault.kind,
              identity,
              actualStatus: incoming.statusCode,
              deliveredStatus: status
            });
            res.writeHead(status, headers);
            if (override?.body === undefined) incoming.pipe(res);
            else {
              incoming.destroy();
              res.end(JSON.stringify(override.body));
            }
          });
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
  check('Playwright Chromium is available', Boolean(chromium));
  browser = await chromium.launch({ channel: 'chromium', timeout: 30000 });
  await new Promise((ready, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', ready);
  });
  const origin = 'http://127.0.0.1:' + server.address().port;
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, reducedMotion: 'reduce' });
  await context.addInitScript(userId => {
    window.__c07ProtectedSeenForUser = false;
    const inspect = () => {
      try {
        const stored = JSON.parse(localStorage.getItem('pathome_user') || 'null');
        if (Number(stored?.id) !== Number(userId)) return;
        if (document.querySelector('.routing-row')
            || Array.from(document.querySelectorAll('h1,h2,h3'))
              .some(heading => heading.textContent?.trim() === 'City routing and coverage')) {
          window.__c07ProtectedSeenForUser = true;
        }
      } catch { /* account storage may be midway through a real transition */ }
    };
    window.addEventListener('DOMContentLoaded', () => {
      inspect();
      new MutationObserver(inspect).observe(document.documentElement, {
        childList: true,
        subtree: true,
        characterData: true
      });
    });
  }, accountB.userId);
  await context.route('**/*', route =>
    new URL(route.request().url()).origin === origin ? route.continue() : route.abort('blockedbyclient'));
  page = await context.newPage();
  const loginViaUi = async account => {
    await page.getByRole('button', { name: /Sign in/ }).first().click();
    const dialog = page.getByRole('dialog').first();
    await dialog.locator('input[type=email]').fill(account.email);
    await dialog.locator('input[type=password]').fill(account.password);
    await Promise.all([
      page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/auth/login'
        && response.status() === 200),
      dialog.getByRole('button', { name: 'Sign In', exact: true }).click()
    ]);
    await dialog.waitFor({ state: 'hidden', timeout: 15000 });
    await page.waitForFunction(name =>
      document.querySelector('button[aria-label^="Profile menu for"]')
        ?.getAttribute('aria-label')?.includes(name) === true,
    `C07 ${account.label}`);
  };
  const logoutViaUi = async () => {
    await page.getByRole('button', { name: /Profile menu for/ }).click();
    await page.getByRole('button', { name: 'Log Out Session' }).click();
    await page.waitForURL(origin + '/');
  };
  const waitWorkspace = () =>
    page.getByRole('heading', { name: 'City routing and coverage' })
      .waitFor({ timeout: 15000 });
  const waitRestricted = () =>
    page.getByRole('heading', { name: 'City routing is unavailable' })
      .waitFor({ timeout: 15000 });
  const waitUnavailable = () =>
    page.getByRole('heading', { name: 'City routing could not be verified' })
      .waitFor({ timeout: 15000 });
  const enterRouting = async account => {
    await page.goto(origin + '/operations/routing');
    if ([accountA, accountD].includes(account)) await waitWorkspace();
    else await waitRestricted();
  };
  const focus = () => page.evaluate(() => window.dispatchEvent(new Event('focus')));
  const waitCapabilityResponse = async action => {
    const response = page.waitForResponse(candidate =>
      new URL(candidate.url()).pathname === '/api/v1/staff/me/capabilities'
      && candidate.status() === 200);
    await action();
    return response;
  };
  let fault = null;
  await page.goto(origin);
  await loginViaUi(accountA);
  await enterRouting(accountA);
  await page.getByRole('heading', { name: 'Routing queue' }).waitFor();
  check('account A renders its genuine City-scoped routing work', true,
    `city=${first.cityId}`);
  const refresh = page.getByRole('button', { name: 'Refresh' }).first();
  await refresh.focus();
  const revalidationContextBefore = await page.evaluate(() => ({
    pathname: location.pathname,
    scrollY,
    activeText: document.activeElement?.textContent?.trim() ?? ''
  }));
  fault = armCapabilityFault(accountA.userId, 'same-account-revalidation');
  await focus();
  await waitForFaultArmed(fault);
  check('same-account revalidation keeps current protected content visible without a skeleton',
    await page.getByRole('heading', { name: 'City routing and coverage' }).isVisible());
  const revalidationContextDuring = await page.evaluate(() => ({
    pathname: location.pathname,
    scrollY,
    activeText: document.activeElement?.textContent?.trim() ?? ''
  }));
  fault.release();
  await page.waitForTimeout(200);
  const revalidationContextAfter = await page.evaluate(() => ({
    pathname: location.pathname,
    scrollY,
    activeText: document.activeElement?.textContent?.trim() ?? ''
  }));
  check('same-account revalidation preserves route, scroll and keyboard focus',
    JSON.stringify(revalidationContextBefore) === JSON.stringify(revalidationContextDuring)
      && JSON.stringify(revalidationContextBefore) === JSON.stringify(revalidationContextAfter),
    JSON.stringify({
      before: revalidationContextBefore,
      during: revalidationContextDuring,
      after: revalidationContextAfter
    }));

  await revoke(aCityGrant, 'C07_G05_LIVE_CITY_REVOKE');
  await waitCapabilityResponse(focus);
  await waitRestricted();
  check('C07-G05 genuine City revocation removes the admitted routing workspace',
    await page.getByRole('heading', { name: 'City routing is unavailable' }).isVisible()
    && await page.getByRole('heading', { name: 'City routing and coverage' }).count() === 0);
  status = await api('GET', '/operations/claimable-work?page=0&size=20', accountA.token);
  check('C07-G05 live City revocation leaves Team coordination independently valid',
    status.status === 200, status.text);
  receipt('C07-G05 post-City-revoke Team authority', {
    status: status.status,
    cacheControl: status.cacheControl,
    body: status.json
  });
  aCityGrant = await grant(accountA, 'OPS_ROUTING', first.cityId, first.teamId);
  await waitCapabilityResponse(focus);
  await waitWorkspace();

  await revoke(aTeamGrant, 'C07_G06_LIVE_TEAM_REVOKE');
  await waitCapabilityResponse(focus);
  await page.waitForTimeout(300);
  check('C07-G06 live Team revocation does not couple or deny City routing',
    await page.getByRole('heading', { name: 'City routing and coverage' }).isVisible());
  status = await api('GET', '/operations/claimable-work?page=0&size=20', accountA.token);
  check('C07-G06 revoked Team duty is denied while City routing remains authorized',
    status.status === 403, status.text);
  status = await api('GET', '/operations/automatic-routing/tasks?page=0&size=20', accountA.token);
  check('C07-G06 City routing remains a genuine independent backend authority',
    status.status === 200 && status.cacheControl === 'no-store', status.text);
  receipt('C07-G06 post-Team-revoke independent authority', {
    teamClaimableStatus: 403,
    cityRoutingStatus: status.status,
    cacheControl: status.cacheControl,
    body: status.json
  });
  aTeamGrant = await grant(accountA, 'OPS_COORDINATE', first.cityId, first.teamId);
  await waitCapabilityResponse(focus);

  await logoutViaUi();
  await loginViaUi(accountD);
  await enterRouting(accountD);
  const dReadyAt = new Date().toISOString();
  const dProtectedRequests = browserRequests.filter(entry =>
    entry.identity?.userId === accountD.userId
      && entry.path.startsWith('/api/v1/operations/'));
  check('C07-G10 same-role account switch performs fresh protected reads for D',
    dProtectedRequests.length > 0);
  check('C07-G10 D protected coverage reads never target A private City',
    dProtectedRequests.filter(entry => entry.path.includes('/coverage'))
      .every(entry => entry.query.cityId === undefined
        || Number(entry.query.cityId) === second.cityId),
    JSON.stringify(dProtectedRequests));
  check('C07-G10 same-role switch fires no protected read for A after D is current',
    browserRequests.filter(entry => entry.identity?.userId === accountA.userId
      && entry.path.startsWith('/api/v1/operations/')
      && entry.at > dReadyAt).length === 0,
    JSON.stringify(browserRequests.filter(entry => entry.identity?.userId === accountA.userId
      && entry.path.startsWith('/api/v1/operations/') && entry.at > dReadyAt)));

  await logoutViaUi();
  await loginViaUi(accountC);
  await enterRouting(accountC);
  check('C07-G03 Team-only C reaches explicit restricted routing after genuine login',
    await page.getByRole('heading', { name: 'City routing is unavailable' }).isVisible());
  check('C07-G03 Team-only C transmits no protected City routing read',
    browserRequests.every(entry => entry.identity?.userId !== accountC.userId
      || !entry.path.startsWith('/api/v1/operations/automatic-routing/')));

  await logoutViaUi();
  await loginViaUi(accountB);
  await enterRouting(accountB);
  caps = await capabilityReceipt(accountB);
  check('C07-G01 genuinely revoked B has a valid structural zero-grant capability response',
    caps.body.staffActive === true && caps.body.capabilities.length === 0,
    JSON.stringify(caps.body));
  check('C07-G01 revoked B reaches explicit restricted, not indefinite checking',
    await page.getByRole('heading', { name: 'City routing is unavailable' }).isVisible());
  check('C07-G01 zero-grant B never reads protected routing data',
    browserRequests.every(entry => entry.identity?.userId !== accountB.userId
      || !entry.path.startsWith('/api/v1/operations/automatic-routing/')));
  check('C07-G01 no protected routing content is left visible for B',
    await page.getByRole('heading', { name: 'City routing and coverage' }).count() === 0);

  await logoutViaUi();
  await loginViaUi(accountA);
  await enterRouting(accountA);
  check('C07-G08 B restricted to A granted removes stale B denial for A',
    await page.getByRole('heading', { name: 'City routing and coverage' }).isVisible());

  const beforeSwitch = await page.evaluate(() => ({
    url: location.pathname,
    scrollY,
    heading: document.querySelector('h2')?.textContent?.trim() ?? '',
    focusTag: document.activeElement?.tagName ?? ''
  }));
  fault = armCapabilityFault(accountA.userId, 'late-success');
  await focus();
  await waitForFaultArmed(fault);
  check('routing content stays visible while A authority is revalidating',
    await page.getByRole('heading', { name: 'Routing queue' }).isVisible());
  await logoutViaUi();
  await loginViaUi(accountB);
  const bBrowserToken = await page.evaluate(() => localStorage.getItem('pathome_auth_token'));
  check('account B UI login established a current browser credential',
    typeof bBrowserToken === 'string' && bBrowserToken.length > 20);
  const bRouteCapability = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/staff/me/capabilities'
    && response.request().headers().authorization === `Bearer ${bBrowserToken}`
    && response.status() === 200);
  await page.goto(origin + '/operations/routing');
  await bRouteCapability;
  fault.release();
  await waitRestricted();
  await page.waitForTimeout(400);
  check('C07-G11 late account-A success cannot restore A routing work into B',
    await page.getByRole('heading', { name: 'City routing is unavailable' }).isVisible()
    && await page.getByRole('heading', { name: 'City routing and coverage' }).count() === 0);
  check('account B remains in its own current denied capability state',
    await page.getByText('Your account does not have a current City routing permission.')
      .isVisible());
  check('account switch does not navigate away from the routing surface',
    new URL(page.url()).pathname === '/operations/routing');
  check('account switch preserves visible routing content without a skeleton',
    await page.locator('main[aria-label="Operations"]').isVisible());
  check('C07-G07 no A protected routing content flashes for restricted B',
    await page.evaluate(() => window.__c07ProtectedSeenForUser === false));

  await logoutViaUi();
  await loginViaUi(accountA);
  await enterRouting(accountA);
  check('C07-G09 A to B to A restores only current A routing authority',
    await page.getByRole('heading', { name: 'City routing and coverage' }).isVisible()
    && await page.getByRole('heading', { name: 'City routing is unavailable' }).count() === 0);
  const afterReturn = await page.evaluate(() => ({ url: location.pathname, scrollY }));
  check('C07-G09 A to B to A stays on routing and does not jump scroll',
    afterReturn.url === beforeSwitch.url && Math.abs(afterReturn.scrollY - beforeSwitch.scrollY) <= 40,
    JSON.stringify({ before: beforeSwitch, after: afterReturn }));

  fault = armCapabilityFault(accountA.userId, 'late-error');
  await focus();
  await waitForFaultArmed(fault);
  await logoutViaUi();
  await loginViaUi(accountD);
  await enterRouting(accountD);
  const dSettledAt = new Date().toISOString();
  fault.release({
    status: 500,
    body: { status: 500, message: 'controlled stale A failure' }
  });
  await page.waitForTimeout(400);
  check('C07-G12 late account-A error/finalizer cannot mutate current D workspace',
    await page.getByRole('heading', { name: 'City routing and coverage' }).isVisible()
    && await page.getByRole('heading', { name: 'City routing could not be verified' }).count() === 0);
  check('C07-G13 D capability settled before the controlled late-A response was released',
    dSettledAt <= events.find(entry => entry.name === 'CONTROLLED_CAPABILITY_RELEASED'
      && entry.kind === 'late-error')?.at,
    dSettledAt);
  check('C07-G12 late A finalizer does not revive A protected reads for D',
    browserRequests.filter(entry => entry.at > dSettledAt
      && entry.identity?.userId === accountA.userId
      && entry.path.startsWith('/api/v1/operations/')).length === 0);

  await logoutViaUi();
  await loginViaUi(accountC);
  await enterRouting(accountC);
  fault = armCapabilityFault(accountC.userId, 'malformed-required-field');
  await focus();
  await waitForFaultArmed(fault);
  fault.release({
    status: 200,
    body: { staffActive: true, serverTime: null }
  });
  await waitUnavailable();
  check('C07-G14 fault-injected malformed real response reaches unavailable, not restricted',
    await page.getByRole('heading', { name: 'City routing could not be verified' }).isVisible());
  check('C07-G14 malformed response exposes no protected routing content',
    await page.getByRole('heading', { name: 'City routing and coverage' }).count() === 0);
  check('C07-G14 malformed response leaves Retry available',
    await page.getByRole('button', { name: 'Retry access check' }).isVisible());
  await page.getByRole('button', { name: 'Retry access check' }).click();
  await waitRestricted();
  check('C07-G14 Retry restores the current genuine Team-only terminal state',
    await page.getByRole('heading', { name: 'City routing is unavailable' }).isVisible());

  await logoutViaUi();
  await loginViaUi(accountD);
  await enterRouting(accountD);
  const dCapabilityCallsBeforeTimeout = browserRequests.filter(entry =>
    entry.identity?.userId === accountD.userId
      && entry.path === '/api/v1/staff/me/capabilities').length;
  fault = armCapabilityFault(accountD.userId, 'timeout');
  const timeoutStartedAt = Date.now();
  await focus();
  await waitForFaultArmed(fault);
  await waitUnavailable();
  const timeoutElapsed = Date.now() - timeoutStartedAt;
  check('C07-G15 genuine current request settles unavailable at the 10-second timeout',
    timeoutElapsed >= 9000 && timeoutElapsed <= 15000, `elapsedMs=${timeoutElapsed}`);
  check('C07-G15 timeout aborts its owned browser transport', fault.aborted === true);
  await page.waitForTimeout(250);
  check('C07-G15 timeout performs one current capability check and no duplicate settlement',
    browserRequests.filter(entry => entry.identity?.userId === accountD.userId
      && entry.path === '/api/v1/staff/me/capabilities').length
      === dCapabilityCallsBeforeTimeout + 1);
  fault.release({ destroy: true });
  await page.getByRole('button', { name: 'Retry access check' }).click();
  await waitWorkspace();
  check('C07-G15 Retry after timeout reaches current D granted state',
    await page.getByRole('heading', { name: 'City routing and coverage' }).isVisible());

  await logoutViaUi();
  await loginViaUi(accountB);
  await enterRouting(accountB);
  fault = armCapabilityFault(accountB.userId, 'controlled-500');
  await focus();
  await waitForFaultArmed(fault);
  fault.release({
    status: 500,
    body: { status: 500, message: 'private synthetic transport detail' }
  });
  await waitUnavailable();
  check('C07-G16 controlled HTTP 5xx reaches unavailable without false access',
    await page.getByRole('heading', { name: 'City routing could not be verified' }).isVisible()
    && await page.getByRole('heading', { name: 'City routing and coverage' }).count() === 0);
  check('C07-G16 HTTP 5xx never discloses the synthetic internal detail',
    !(await page.getByText('private synthetic transport detail').isVisible().catch(() => false)));
  await page.getByRole('button', { name: 'Retry access check' }).click();
  await waitRestricted();
  check('C07-G16 Retry recovers to the genuine current denied state',
    await page.getByRole('heading', { name: 'City routing is unavailable' }).isVisible());

  await logoutViaUi();
  await loginViaUi(accountA);
  await enterRouting(accountA);
  const navigationBeforeRevalidation = await page.evaluate(
    () => performance.getEntriesByType('navigation').length
  );
  const retryContext = await page.evaluate(() => ({
    pathname: location.pathname,
    activeRole: document.activeElement?.getAttribute('role') ?? '',
    activeText: document.activeElement?.textContent?.trim() ?? ''
  }));
  check('connected routing revalidation/additions keep the approved current route',
    retryContext.pathname === '/operations/routing'
      && await page.evaluate(
        () => performance.getEntriesByType('navigation').length
      ) === navigationBeforeRevalidation,
    JSON.stringify(retryContext));
  await context.close();

  const responsiveResults = [];
  for (const width of [320, 390, 768, 1280]) {
    const responsiveContext = await browser.newContext({
      viewport: { width, height: 900 },
      reducedMotion: 'reduce'
    });
    await responsiveContext.addInitScript(userId => {
      window.__c07ProtectedSeenForUser = false;
      const inspect = () => {
        try {
          const stored = JSON.parse(localStorage.getItem('pathome_user') || 'null');
          if (Number(stored?.id) !== Number(userId)) return;
          if (document.querySelector('.routing-row')
              || Array.from(document.querySelectorAll('h1,h2,h3'))
                .some(heading => heading.textContent?.trim() === 'City routing and coverage')) {
            window.__c07ProtectedSeenForUser = true;
          }
        } catch { /* a real logout may clear storage between mutations */ }
      };
      window.addEventListener('DOMContentLoaded', () => {
        inspect();
        new MutationObserver(inspect).observe(document.documentElement, {
          childList: true,
          subtree: true,
          characterData: true
        });
      });
    }, accountB.userId);
    await responsiveContext.route('**/*', route =>
      new URL(route.request().url()).origin === origin
        ? route.continue()
        : route.abort('blockedbyclient'));
    page = await responsiveContext.newPage();
    await page.goto(origin);
    await loginViaUi(accountB);
    await enterRouting(accountB);
    await waitRestricted();
    const restrictedDimensions = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      innerWidth: window.innerWidth
    }));
    const restrictedShot = run.root + '/evidence/c07-restricted-' + width + 'px.png';
    await page.screenshot({ path: restrictedShot, fullPage: true });
    check(`${width}px connected restricted state remains explicitly visible`,
      await page.getByRole('heading', { name: 'City routing is unavailable' }).isVisible());
    check(`${width}px connected restricted state has no horizontal overflow`,
      restrictedDimensions.scrollWidth <= restrictedDimensions.innerWidth,
      JSON.stringify(restrictedDimensions));
    check(`${width}px restricted B never flashes protected A/B workspace content`,
      await page.evaluate(() => window.__c07ProtectedSeenForUser === false));

    fault = armCapabilityFault(accountB.userId, `responsive-malformed-${width}`);
    await focus();
    await waitForFaultArmed(fault);
    fault.release({
      status: 200,
      body: { staffActive: true, capabilities: null }
    });
    await waitUnavailable();
    const unavailableDimensions = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      innerWidth: window.innerWidth,
      navigationEntries: performance.getEntriesByType('navigation').length
    }));
    const retryButton = page.getByRole('button', { name: 'Retry access check' });
    const retryBox = await retryButton.boundingBox();
    await retryButton.focus();
    const focusVisible = await retryButton.evaluate(element => {
      const style = getComputedStyle(element);
      return document.activeElement === element
        && style.outlineStyle !== 'none'
        && parseFloat(style.outlineWidth) > 0;
    });
    const unavailableShot = run.root + '/evidence/c07-unavailable-' + width + 'px.png';
    await page.screenshot({ path: unavailableShot, fullPage: true });
    check(`${width}px connected unavailable state is visibly distinct`,
      await page.getByRole('heading', { name: 'City routing could not be verified' }).isVisible());
    check(`${width}px connected unavailable state has no horizontal overflow`,
      unavailableDimensions.scrollWidth <= unavailableDimensions.innerWidth,
      JSON.stringify(unavailableDimensions));
    check(`${width}px Retry meets the 44px touch target`,
      (retryBox?.height ?? 0) >= 44, `height=${retryBox?.height ?? 0}`);
    check(`${width}px Retry has a visible keyboard focus indicator`, focusVisible);
    await retryButton.click();
    await waitRestricted();
    const postRetryDimensions = await page.evaluate(() => ({
      pathname: location.pathname,
      navigationEntries: performance.getEntriesByType('navigation').length
    }));
    check(`${width}px Retry keeps the route and performs no document reload`,
      postRetryDimensions.pathname === '/operations/routing'
        && postRetryDimensions.navigationEntries === unavailableDimensions.navigationEntries,
      JSON.stringify({ after: postRetryDimensions, before: unavailableDimensions }));
    responsiveResults.push({
      width,
      restrictedDimensions,
      unavailableDimensions,
      retryHeight: retryBox?.height ?? 0,
      focusVisible,
      routeAfterRetry: postRetryDimensions.pathname,
      screenshots: [restrictedShot, unavailableShot]
    });
    await responsiveContext.close();
  }

  const evidence = {
    kind: 'GENUINE_CONNECTED_WITH_CONTROLLED_TRANSPORT_FAULTS',
    completedAtIST: new Date().toISOString(),
    checks,
    events,
    accountA: { userId: accountA.userId, cityId: first.cityId, teamId: first.teamId },
    accountB: { userId: accountB.userId, cityId: second.cityId, teamId: second.teamId },
    accountC: { userId: accountC.userId, cityId: first.cityId, teamId: first.teamId },
    accountD: { userId: accountD.userId, cityId: second.cityId, teamId: null },
    grantIds: { aCityGrant, aTeamGrant, bCityGrant, bTeamGrant, cTeamGrant, dCityGrant },
    coverage: { a: coverageA.id, b: coverageB.id },
    apiReceipts,
    browserRequests,
    browserResponses,
    responsiveResults,
    classifications: {
      genuineConnected: ['C07-G01','C07-G02','C07-G03','C07-G04','C07-G05','C07-G06',
        'C07-G07','C07-G08','C07-G09','C07-G10'],
      connectedWithControlledTransportFault: ['C07-G11','C07-G12','C07-G13',
        'C07-G14','C07-G15','C07-G16']
    },
    beforeSwitch,
    afterReturn
  };
  const evidenceFile = run.root + '/evidence/routing-p1-c07-browser-'
    + new Date().toISOString().replace(/[:.]/g, '-') + '.json';
  await writeFile(evidenceFile, JSON.stringify(evidence, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
  console.log(`PASS ${checks.length}/${checks.length} genuine C07 browser checks`);
  console.log(`EVIDENCE ${evidenceFile}`);
} catch (failure) {
  checks.push({ name: 'C07 browser completed without unexpected failure', result: 'FAIL',
    detail: failure instanceof Error ? failure.message : String(failure) });
  const evidenceFile = run.root + '/evidence/routing-p1-c07-browser-'
    + new Date().toISOString().replace(/[:.]/g, '-') + '.json';
  const pageState = page ? await page.evaluate(() => ({
    path: location.pathname,
    text: document.body.innerText.slice(0, 4000),
    storedUser: localStorage.getItem('pathome_user')
  })).catch(() => null) : null;
  await writeFile(evidenceFile, JSON.stringify({ result: 'FAIL', checks, events, pageState,
    apiReceipts, browserRequests, browserResponses,
    error: failure instanceof Error ? failure.message : String(failure) }, null, 2) + '\n',
  { mode: 0o600, flag: 'wx' });
  console.error(`FAIL ${failure instanceof Error ? failure.message : String(failure)}`);
  console.error(`EVIDENCE ${evidenceFile}`);
  process.exitCode = 1;
} finally {
  await browser?.close().catch(() => {});
  await new Promise(resolveClose => server?.close(resolveClose) ?? resolveClose());
}
