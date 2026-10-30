/**
 * Connected Home-shell browser proof against the guarded Spring/PostgreSQL harness.
 *
 * The primary journey keeps one application document across identity changes. A real response is
 * captured through the controlled proxy, held, and released in a later identity. Mutant runs use a
 * temporary production-bundle copy only and prove the stale-response and append-dedup assertions
 * are discriminating.
 */
import {
  cp,
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  rm,
  writeFile
} from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { dirname, extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;
const RUNNER_PATH = fileURLToPath(import.meta.url);
const DEFAULT_DIST = fileURLToPath(new URL('../../dist/', import.meta.url));
const DIST = process.env.PATHOME_HOME_SHELL_BROWSER_DIST ?? DEFAULT_DIST;
const ORIGIN = 'http://home-shell.local';
const BACKEND = process.env.PATHOME_HOME_SHELL_BROWSER_BACKEND ?? 'http://127.0.0.1:18080';
const NEGATIVE_CONTROL = process.env.PATHOME_HOME_SHELL_NEGATIVE_CONTROL ?? '';
const statePath = process.env.PATHOME_HOME_SHELL_BROWSER_STATE_FILE;
const stopPath = process.env.PATHOME_HOME_SHELL_BROWSER_STOP_FILE;
const receiptPath = process.env.PATHOME_HOME_SHELL_BROWSER_RECEIPT;
const password = process.env.PATHOME_BROWSER_TEST_PASSWORD;
if (!statePath || !stopPath || !receiptPath || !password) {
  throw new Error('Home-shell state, stop, receipt and synthetic password must be explicit');
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
const failedLabels = [];
const receipt = {
  kind: NEGATIVE_CONTROL
    ? `connected-genuine-home-shell-browser-${NEGATIVE_CONTROL}-mutant`
    : 'connected-genuine-home-shell-browser',
  documentNavigationDuringIdentityJourneys: 0,
  checks: 0,
  failures: 0,
  gaps: 0,
  api: [],
  viewports: [],
  screenshots: [],
  negativeControls: [],
  authentication: [],
  source: {}
};
const controls = {
  failFavoritesOnce: false,
  failVisitsOnce: false,
  abortVisitsOnce: false,
  duplicateFavoritesPage: false,
  duplicateVisitsPage: false,
  activeHold: null,
  freezeAfterFirst: null
};

const check = (label, condition, detail = '') => {
  checks += 1;
  if (condition) console.log(`  PASS  ${label}`);
  else {
    failures += 1;
    failedLabels.push(label);
    console.log(`  FAIL  ${label}${detail ? ` (${detail})` : ''}`);
  }
};
const observeGap = (label, condition, detail = '') => {
  if (condition) {
    checks += 1;
    console.log(`  PASS  ${label}`);
  } else {
    receipt.gaps += 1;
    console.log(`  GAP   ${label}${detail ? ` (${detail})` : ''}`);
  }
};
const deferred = () => {
  let resolve;
  const promise = new Promise(resolver => { resolve = resolver; });
  return { promise, resolve };
};
const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const withTimeout = async (promise, milliseconds, label) => {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(`${label} timed out`)), milliseconds);
      })
    ]);
  } finally {
    clearTimeout(timer);
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
const poll = async (action, timeout = 15_000) => {
  const deadline = Date.now() + timeout;
  let value;
  while (Date.now() < deadline) {
    value = await action();
    if (value) return value;
    await delay(120);
  }
  return value;
};
const waitForApiWindowToSettle = async (quietMilliseconds = 700, timeout = 8_000) => {
  const deadline = Date.now() + timeout;
  let observedCount = -1;
  let stableSince = Date.now();
  while (Date.now() < deadline) {
    if (receipt.api.length !== observedCount) {
      observedCount = receipt.api.length;
      stableSince = Date.now();
    } else if ((Date.now() - stableSince) >= quietMilliseconds) {
      return true;
    }
    await delay(100);
  }
  return false;
};
const sameArray = (left, right) =>
  left.length === right.length && left.every((value, index) => value === right[index]);
const sameSavedCards = (actual, expected) =>
  sameArray(actual.map(card => card.id), expected.map(entry => entry.id))
  && sameArray(actual.map(card => card.title), expected.map(entry => entry.title));
const noOverflow = page => page.evaluate(() => ({
  scrollWidth: Math.max(document.documentElement.scrollWidth, document.body?.scrollWidth ?? 0),
  innerWidth: window.innerWidth
}));
const hasNoStore = cacheControl =>
  cacheControl?.split(',').map(value => value.trim()).includes('no-store') === true;
const expectedFavorites = (emailKey, first = 7) => {
  const ids = state[`${emailKey}FavoriteIds`];
  const titles = state[`${emailKey}FavoriteTitles`];
  return ids.slice(0, first).map((id, index) => ({ id, title: titles[index] }));
};
const expectedVisits = (emailKey, first = 9) =>
  state[`${emailKey}VisitEntries`].slice(0, first).map(entry => ({
    propertyId: entry.propertyId,
    title: entry.title
  }));

const beginHold = (kind, pageNumber, behavior, { size = null } = {}) => {
  const hold = {
    kind,
    page: pageNumber,
    behavior,
    size,
    handling: false,
    released: false,
    captured: deferred(),
    release: deferred()
  };
  controls.activeHold = hold;
  return hold;
};
const releaseHold = hold => {
  if (!hold || hold.released) return;
  hold.released = true;
  hold.release.resolve();
};

const directLogin = async email => {
  const response = await fetch(`${BACKEND}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ email, password }),
    signal: AbortSignal.timeout(20_000)
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`synthetic identity login failed with HTTP ${response.status}`);
  const payload = JSON.parse(text);
  const role = String(payload.role || '').replace(/^ROLE_/, '');
  if (!Number.isSafeInteger(payload.userId) || payload.userId <= 0 || !payload.token || !role) {
    throw new Error('synthetic identity login returned an invalid identity');
  }
  receipt.identityLogins = receipt.identityLogins ?? [];
  receipt.identityLogins.push({ email, userId: payload.userId, role, status: response.status });
  return {
    token: payload.token,
    user: {
      id: payload.userId,
      email: payload.email || email,
      fullName: payload.fullName || email.split('@')[0],
      role,
      freeVisitsUsed: 0,
      walletBalance: 0,
      hasLessorProfile: Boolean(payload.hasLessorProfile),
      employeeRoleType: payload.employeeRoleType ?? undefined
    }
  };
};

const freshContext = async (browser, { path = '/', disableAbort = false } = {}) => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  if (disableAbort) {
    await context.addInitScript(() => {
      AbortController.prototype.abort = function noAbortForFenceMutant() {};
    });
  }
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  await page.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.origin !== ORIGIN) return route.continue();
    if (url.pathname.startsWith('/api/')) {
      const request = route.request();
      const pageNumber = Number(url.searchParams.get('page') ?? 0);
      const favoritesPath = '/api/v1/favorites/properties';
      const visitsPath = '/api/v1/tenant/visit-requests';

      if (url.pathname === favoritesPath && controls.failFavoritesOnce) {
        controls.failFavoritesOnce = false;
        receipt.api.push({
          method: request.method(),
          path: url.pathname + url.search,
          status: 503,
          cacheControl: 'planned-fault'
        });
        return route.fulfill({
          status: 503,
          contentType: 'application/json',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ error: 'planned-test-failure' })
        });
      }
      if (url.pathname === visitsPath && controls.failVisitsOnce) {
        controls.failVisitsOnce = false;
        receipt.api.push({
          method: request.method(),
          path: url.pathname + url.search,
          status: 503,
          cacheControl: 'planned-fault'
        });
        return route.fulfill({
          status: 503,
          contentType: 'application/json',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ error: 'planned-test-failure' })
        });
      }
      if (url.pathname === visitsPath && controls.abortVisitsOnce) {
        controls.abortVisitsOnce = false;
        receipt.api.push({
          method: request.method(),
          path: url.pathname + url.search,
          status: 'ABORTED',
          cacheControl: 'planned-fault'
        });
        return route.abort('failed');
      }

      const hold = controls.activeHold;
      const holdKind = url.pathname === favoritesPath ? 'favorites'
        : url.pathname === visitsPath ? 'visits' : null;
      const holdSizeMatches = hold?.size === null
        || url.searchParams.get('size') === String(hold?.size);
      if (hold && !hold.handling && holdKind === hold.kind && hold.page === pageNumber
          && holdSizeMatches) {
        hold.handling = true;
        if (hold.behavior === 'error') {
          const capture = {
            method: request.method(),
            path: url.pathname + url.search,
            status: 503,
            cacheControl: 'planned-hold-error'
          };
          hold.captured.resolve(capture);
          await hold.release.promise;
          receipt.api.push(capture);
          return route.fulfill({
            status: 503,
            contentType: 'application/json',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ error: 'planned-held-error' })
          }).catch(() => undefined);
        }
        if (hold.behavior === 'abort') {
          const capture = {
            method: request.method(),
            path: url.pathname + url.search,
            status: 'ABORTED',
            cacheControl: 'planned-hold-abort'
          };
          hold.captured.resolve(capture);
          await hold.release.promise;
          receipt.api.push(capture);
          return route.abort('failed').catch(() => undefined);
        }

        const headers = { ...request.headers() };
        delete headers.host;
        delete headers['content-length'];
        let response = await fetch(`${BACKEND}${url.pathname}${url.search}`, {
          method: request.method(),
          headers,
          redirect: 'manual',
          signal: AbortSignal.timeout(20_000)
        });
        let text = await response.text();
        const cacheControl = response.headers.get('cache-control');
        const contentType = response.headers.get('content-type') ?? 'application/json';
        if (hold.behavior === 'empty-page' && url.pathname === visitsPath) {
          const payload = JSON.parse(text);
          payload.requests = [];
          payload.page = 0;
          payload.hasMore = true;
          text = JSON.stringify(payload);
        }
        if (hold.behavior === 'truncated-count' && url.pathname === favoritesPath) {
          const payload = JSON.parse(text);
          if (Array.isArray(payload.properties)) {
            payload.properties = payload.properties.slice(0, 1);
            payload.hasMore = false;
            text = JSON.stringify(payload);
          }
        }
        if (url.pathname === favoritesPath && pageNumber === 1
            && controls.duplicateFavoritesPage) {
          controls.duplicateFavoritesPage = false;
          const payload = JSON.parse(text);
          if (Array.isArray(payload.properties) && payload.properties.length > 0) {
            payload.properties.push({ ...payload.properties[0] });
            text = JSON.stringify(payload);
          }
        }
        if (url.pathname === visitsPath && pageNumber === 1
            && controls.duplicateVisitsPage) {
          controls.duplicateVisitsPage = false;
          const payload = JSON.parse(text);
          if (Array.isArray(payload.requests) && payload.requests.length > 0) {
            payload.requests.push({ ...payload.requests[0] });
            text = JSON.stringify(payload);
          }
        }
        const capture = {
          method: request.method(),
          path: url.pathname + url.search,
          status: response.status,
          cacheControl,
          heldUntilIdentityWasLive: true,
          responseIdentity: 'request',
          payloadState: hold.behavior === 'truncated-count'
            ? 'controlled-one-property'
            : hold.behavior === 'empty-page' ? 'controlled-empty-same-page' : 'authoritative'
        };
        hold.captured.resolve(capture);
        await hold.release.promise;
        receipt.api.push(capture);
        return route.fulfill({
          status: response.status,
          contentType,
          headers: {
            'Cache-Control': cacheControl ?? '',
            'Content-Type': contentType
          },
          body: text
        }).catch(() => undefined);
      }

      const freeze = controls.freezeAfterFirst;
      const freezePageMatches = freeze && holdKind === freeze.kind && pageNumber === freeze.page;
      const freezeSizeMatches = freeze?.kind !== 'favorites'
        || url.searchParams.get('size') === '6';
      if (freezePageMatches && freezeSizeMatches
          && request.headers().authorization === freeze.authorization) {
        if (freeze.allowed > 0) freeze.allowed -= 1;
        else {
          freeze.captured?.resolve();
          await freeze.release.promise;
        }
      }

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
      let text = await response.text();
      const contentType = response.headers.get('content-type') ?? 'application/json';
      if (url.pathname === favoritesPath && pageNumber === 1
          && controls.duplicateFavoritesPage) {
        controls.duplicateFavoritesPage = false;
        const payload = JSON.parse(text);
        if (Array.isArray(payload.properties) && payload.properties.length > 0) {
          payload.properties.push({ ...payload.properties[0] });
          text = JSON.stringify(payload);
        }
      }
      if (url.pathname === visitsPath && pageNumber === 1
          && controls.duplicateVisitsPage) {
        controls.duplicateVisitsPage = false;
        const payload = JSON.parse(text);
        if (Array.isArray(payload.requests) && payload.requests.length > 0) {
          payload.requests.push({ ...payload.requests[0] });
          text = JSON.stringify(payload);
        }
      }
      receipt.api.push({
        method: request.method(),
        path: url.pathname + url.search,
        status: response.status,
        cacheControl: response.headers.get('cache-control')
      });
      return route.fulfill({
        status: response.status,
        contentType,
        headers: {
          'Cache-Control': response.headers.get('cache-control') ?? '',
          'Content-Type': contentType
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

const authDialog = page => page.getByRole('dialog', {
  name: 'Sign in or create account',
  exact: true
});

const openAuthDialog = async page => {
  const dialog = authDialog(page);
  if (!(await dialog.isVisible().catch(() => false))) {
    const signInButton = page.getByRole('button', { name: 'Sign in', exact: true }).first();
    await signInButton.click({ timeout: 10_000 });
  }
  await dialog.waitFor({ state: 'visible', timeout: 15_000 });
  return dialog;
};

const signIn = async (page, email) => {
  const dialog = await openAuthDialog(page);
  await dialog.locator('input[type="email"]').fill(email);
  await dialog.locator('input[type="password"]').fill(password);
  const loginResponse = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/auth/login'
      && response.request().method() === 'POST', { timeout: 20_000 });
  await dialog.getByRole('button', { name: 'Sign In', exact: true }).click();
  const response = await loginResponse;
  if (response.status() !== 200) {
    throw new Error(`browser sign-in failed with HTTP ${response.status()}`);
  }
  const payload = await response.json();
  await dialog.waitFor({ state: 'detached', timeout: 20_000 });
  const session = await page.evaluate(() => {
    const rawUser = localStorage.getItem('pathome_user');
    let user = null;
    try {
      user = rawUser ? JSON.parse(rawUser) : null;
    } catch {
      user = null;
    }
    return {
      token: localStorage.getItem('pathome_auth_token'),
      tokenPresent: Boolean(localStorage.getItem('pathome_auth_token')),
      userId: user?.id ?? null,
      email: user?.email ?? null,
      fullName: user?.fullName ?? null,
      role: user?.role ?? null,
      hasLessorProfile: user?.hasLessorProfile ?? null,
      employeeRoleType: user?.employeeRoleType ?? null
    };
  });
  const expectedRole = String(payload.role || '').replace(/^ROLE_/, '').toUpperCase();
  const expectedEmail = typeof payload.email === 'string' && payload.email.length > 0
    ? payload.email : email;
  const expectedFullName = typeof payload.fullName === 'string' && payload.fullName.length > 0
    ? payload.fullName : 'User';
  const expectedEmployeeRoleType = typeof payload.employeeRoleType === 'string'
    ? payload.employeeRoleType : null;
  const identityMatches = session.token === payload.token
    && session.userId === payload.userId
    && String(session.email).toLowerCase() === expectedEmail.toLowerCase()
    && session.fullName === expectedFullName
    && session.role === expectedRole
    && session.hasLessorProfile === Boolean(payload.hasLessorProfile)
    && session.employeeRoleType === expectedEmployeeRoleType;
  if (!session.tokenPresent || !Number.isSafeInteger(session.userId)
    || session.userId <= 0 || !expectedRole || !identityMatches) {
    const safeSession = { ...session, token: session.tokenPresent ? '[present]' : null };
    throw new Error(`browser sign-in did not establish the authoritative response identity: ${
      JSON.stringify(safeSession)}`);
  }
  receipt.authentication.push({
    email,
    status: response.status(),
    userId: session.userId,
    role: session.role,
    dialogDetached: true,
    sessionIdentityMatches: true,
    comparedFields: ['user id', 'token', 'email', 'full name', 'role',
      'lessor capability', 'employee role type']
  });
  return { ...session, token: payload.token };
};

const rejectInvalidCredentials = async (page, email) => {
  const dialog = await openAuthDialog(page);
  await dialog.locator('input[type="email"]').fill(email);
  await dialog.locator('input[type="password"]').fill(`${password}-invalid`);
  const responsePromise = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/auth/login'
      && response.request().method() === 'POST', { timeout: 20_000 });
  await dialog.getByRole('button', { name: 'Sign In', exact: true }).click();
  const response = await responsePromise;
  const alert = dialog.getByRole('alert');
  await alert.waitFor({ state: 'visible', timeout: 15_000 });
  const facts = await page.evaluate(() => ({
    tokenPresent: Boolean(localStorage.getItem('pathome_auth_token')),
    userPresent: Boolean(localStorage.getItem('pathome_user'))
  }));
  const dialogVisible = await dialog.isVisible();
  const alertText = (await alert.textContent().catch(() => '')) ?? '';
  await dialog.getByRole('button', { name: 'Close sign in', exact: true }).click();
  await dialog.waitFor({ state: 'detached', timeout: 15_000 });
  return {
    responseStatus: response.status(),
    alertText,
    tokenPresent: facts.tokenPresent,
    userPresent: facts.userPresent,
    dialogVisible
  };
};

const surfaceObstructionFacts = (page, selector) => page.evaluate(selector => {
  const visible = element => Boolean(element && element.getClientRects().length > 0
    && getComputedStyle(element).visibility !== 'hidden'
    && Number(getComputedStyle(element).opacity) !== 0);
  const surface = document.querySelector(selector);
  const authDialogs = [...document.querySelectorAll('[role="dialog"]')]
    .filter(element => (element.getAttribute('aria-label') || '').toLowerCase()
      .startsWith('sign in'));
  const blockingDialogs = [...document.querySelectorAll('[role="dialog"][aria-modal="true"]')]
    .filter(visible);
  const probe = surface
    ? [...surface.querySelectorAll('a[href],button,input,select,textarea')].find(visible) ?? surface
    : null;
  let hitTestable = false;
  let hitFacts = null;
  if (visible(probe)) {
    probe.scrollIntoView({ behavior: 'instant', block: 'center', inline: 'nearest' });
    const rect = probe.getBoundingClientRect();
    const x = Math.min(window.innerWidth - 1, Math.max(0, rect.left + rect.width / 2));
    const y = Math.min(window.innerHeight - 1, Math.max(0, rect.top + rect.height / 2));
    const hit = document.elementFromPoint(x, y);
    hitTestable = Boolean(hit && (probe === hit || probe.contains(hit)));
    hitFacts = {
      probeTag: probe.tagName,
      probeClass: typeof probe.className === 'string' ? probe.className : null,
      point: [Math.round(x), Math.round(y)],
      rect: [Math.round(rect.left), Math.round(rect.top),
        Math.round(rect.width), Math.round(rect.height)],
      hitTag: hit?.tagName ?? null,
      hitClass: typeof hit?.className === 'string' ? hit.className : null
    };
  }
  return {
    surfaceVisible: visible(surface),
    visibleAuthDialogs: authDialogs.filter(visible).length,
    authDialogNodes: authDialogs.length,
    blockingDialogs: blockingDialogs.length,
    focusInsideDialog: Boolean(document.activeElement?.closest('[role="dialog"]')),
    hitTestable,
    hitFacts,
    bodyOverflow: document.body.style.overflow || 'visible'
  };
}, selector);

const surfaceIsUnobstructed = async (page, selector) => {
  const facts = await surfaceObstructionFacts(page, selector);
  return facts.surfaceVisible === true
    && facts.visibleAuthDialogs === 0
    && facts.authDialogNodes === 0
    && facts.blockingDialogs === 0
    && facts.focusInsideDialog === false
    && facts.hitTestable === true
    && facts.bodyOverflow !== 'hidden';
};

const installIdentity = async (page, identity) => {
  await page.evaluate(({ token, user }) => {
    localStorage.setItem('pathome_auth_token', token);
    localStorage.setItem('pathome_role', user.role);
    const serialized = JSON.stringify(user);
    localStorage.setItem('pathome_user', serialized);
    window.dispatchEvent(new StorageEvent('storage', {
      key: 'pathome_user',
      oldValue: null,
      newValue: serialized
    }));
  }, identity);
};

const rotateToken = async (page, identity) => {
  await page.evaluate(({ token, user }) => {
    localStorage.setItem('pathome_auth_token', token);
    localStorage.setItem('pathome_role', user.role);
    localStorage.setItem('pathome_user', JSON.stringify(user));
    window.dispatchEvent(new Event('pathome_auth_changed'));
  }, identity);
};
const removeToken = page => page.evaluate(() => {
  localStorage.removeItem('pathome_auth_token');
  window.dispatchEvent(new Event('pathome_auth_changed'));
});

const markDocument = page => page.evaluate(() => {
  window.__pathomeHomeShellDocumentMarker = `document-${Date.now()}-${Math.random()}`;
  return window.__pathomeHomeShellDocumentMarker;
});
const assertSameDocument = async (page, marker, label) => {
  const current = await page.evaluate(() => window.__pathomeHomeShellDocumentMarker ?? null);
  check(label, current === marker, `${marker} != ${current}`);
};

const clickNavigation = async (page, desktopTitle, mobileLabel) => {
  const index = await poll(() => page.evaluate(({ desktopTitle, mobileLabel }) => {
    const visible = element => element.getClientRects().length > 0;
    const matching = button => {
      const text = (button.textContent || '').replace(/\s+/g, ' ').trim();
      const label = button.getAttribute('aria-label') || '';
      return button.title === desktopTitle
        || label === mobileLabel
        || label.startsWith(`${mobileLabel},`)
        || label.startsWith(`${desktopTitle},`)
        || text === mobileLabel
        || text === desktopTitle
        || text.endsWith(` ${mobileLabel}`)
        || text.endsWith(mobileLabel);
    };
    const buttons = [...document.querySelectorAll('button')];
    const targetIndex = buttons.findIndex(button => visible(button) && matching(button));
    return targetIndex >= 0 ? targetIndex : null;
  }, { desktopTitle, mobileLabel }), 10_000);
  if (index === null) {
    throw new Error(`navigation target not found: ${desktopTitle}/${mobileLabel}`);
  }
  await page.locator('button').nth(index).click({ timeout: 10_000 });
};
const goSaved = async page => {
  if (await page.locator('#saved-homes-title').isVisible().catch(() => false)) return;
  await clickNavigation(page, 'Saved homes', 'Saved');
};
const goVisits = async page => {
  if (await page.locator('#tenant-visits-page-title').isVisible().catch(() => false)) return;
  await clickNavigation(page, 'Visit requests', 'Visits');
};

const savedCards = page => page.locator('#tenant-saved-home-grid [id^="tenant-property-"]')
  .evaluateAll(elements => elements.map(element => {
    const card = element.closest('.tenant-v0-card');
    return {
      id: Number(element.id.replace('tenant-property-', '')),
      title: card?.querySelector('h3')?.textContent?.trim() ?? ''
    };
  }));
const savedIds = async page => (await savedCards(page)).map(card => card.id);
const visitCards = page => page.locator('.tenant-v0-request-card').evaluateAll(cards =>
  cards.map(card => ({
    propertyId: Number((card.querySelector('a[href^="/property/"]')?.getAttribute('href') ?? '')
      .replace('/property/', '')),
    title: card.querySelector('h3')?.textContent?.trim() ?? ''
  })));
const waitSavedCards = (page, expected, timeout = 20_000) =>
  poll(async () => sameSavedCards(await savedCards(page), expected), timeout).then(Boolean);
const waitVisitCards = (page, expected, timeout = 20_000) =>
  poll(async () => {
    const actual = await visitCards(page);
    return sameArray(actual.map(card => card.propertyId),
      expected.map(entry => entry.propertyId))
      && sameArray(actual.map(card => card.title), expected.map(entry => entry.title));
  }, timeout).then(Boolean);

const visibleButton = (page, name) =>
  page.getByRole('button', { name, exact: true }).first();
const savedLoadMoreControl = async page => {
  const button = page.getByRole('button', {
    name: /^(Show more saved homes|Loading saved homes…)$/
  }).first();
  return button.evaluate(element => ({
    text: (element.textContent || '').replace(/\s+/g, ' ').trim(),
    disabled: element.disabled,
    visible: element.getClientRects().length > 0
  })).catch(() => ({ text: '', disabled: true, visible: false }));
};
const visitLoadMoreControl = async page => {
  const button = page.locator('#visit-history').getByRole('button', {
    name: /^(Load more requests|Loading more…)$/
  }).first();
  return button.evaluate(element => ({
    text: (element.textContent || '').replace(/\s+/g, ' ').trim(),
    disabled: element.disabled,
    visible: element.getClientRects().length > 0
  })).catch(() => ({ text: '', disabled: true, visible: false }));
};
const waitSavedDockCount = (page, expected, timeout = 15_000) =>
  poll(async () => page.getByRole('button', {
    name: `Saved homes, ${expected} ${expected === 1 ? 'property' : 'properties'}`,
    exact: true
  }).first().isVisible().catch(() => false), timeout).then(Boolean);
const logoutThroughUi = async page => {
  const profileButton = page.getByRole('button', { name: /^Profile menu for / }).first();
  await profileButton.waitFor({ state: 'visible', timeout: 10_000 });
  await profileButton.click({ timeout: 10_000 });
  const logoutButton = page.getByRole('button', { name: 'Log Out Session', exact: true });
  await logoutButton.waitFor({ state: 'visible', timeout: 10_000 });
  await logoutButton.click({ timeout: 10_000 });
  await page.waitForURL(url => url.origin === ORIGIN && url.pathname === '/', {
    timeout: 15_000
  });
  const signInVisible = await poll(() => page.getByRole('button', {
    name: 'Sign in', exact: true
  }).first().isVisible().catch(() => false), 10_000);
  const storage = await page.evaluate(() => ({
    tokenPresent: Boolean(localStorage.getItem('pathome_auth_token')),
    userPresent: Boolean(localStorage.getItem('pathome_user')),
    rolePresent: Boolean(localStorage.getItem('pathome_role'))
  }));
  return { signInVisible: Boolean(signInVisible), storage };
};
const focusAndHeight = async (page, name) => {
  const button = visibleButton(page, name);
  await page.waitForTimeout(450);
  await button.scrollIntoViewIfNeeded();
  await button.focus();
  const facts = await button.evaluate(element => {
    const rect = element.getBoundingClientRect();
    const x = rect.left + rect.width / 2;
    const y = rect.top + rect.height / 2;
    const hit = document.elementFromPoint(x, y);
    return {
      focused: document.activeElement === element,
      height: rect.height,
      hitTestable: Boolean(hit && (hit === element || element.contains(hit))),
      point: [Math.round(x), Math.round(y)],
      viewport: [window.innerWidth, window.innerHeight],
      hitTag: hit?.tagName ?? null,
      hitClass: typeof hit?.className === 'string' ? hit.className : null
    };
  });
  return facts;
};
const doubleClickNamedButton = async (page, name) => {
  const button = page.getByRole('button', { name, exact: true }).first();
  await button.dblclick({ timeout: 10_000, delay: 20 });
};
const apiPageRequestCount = (pageNumber, endpoint) => receipt.api.filter(entry =>
  entry.method === 'GET' && entry.path.startsWith(`${endpoint}?page=${pageNumber}`)).length;
const currentAlertText = page => page.locator('[role="alert"]').allTextContents();

const normalJourney = async browser => {
  const env = await freshContext(browser);
  const { page, pageErrors } = env;
  console.log('Connected genuine Home-shell browser verification');
  console.log(`backend: ${BACKEND}`);
  console.log(`bundle: ${DIST}`);

  const populated = await directLogin(state.populatedEmail);
  const alternate = await directLogin(state.alternateEmail);
  const denied = await directLogin(state.deniedEmail);
  const populatedFirstPage = expectedFavorites('populated', 6);
  const alternateFirstPage = expectedFavorites('alternate', 6);
  const populatedAllFavorites = expectedFavorites('populated', 7);
  const alternateAllFavorites = expectedFavorites('alternate', 7);
  const populatedFirstVisits = expectedVisits('populated', 8);
  const alternateFirstVisits = expectedVisits('alternate', 8);
  const populatedAllVisits = expectedVisits('populated', 9);
  const alternateAllVisits = expectedVisits('alternate', 9);

  const invalidCredentials = await rejectInvalidCredentials(page, state.populatedEmail);
  check('invalid credentials remain unauthenticated and expose no Home data',
    invalidCredentials.responseStatus === 401
      && invalidCredentials.tokenPresent === false
      && invalidCredentials.userPresent === false,
    JSON.stringify(invalidCredentials));
  check('invalid credentials keep the auth dialog open with an actionable error',
    invalidCredentials.dialogVisible === true
      && invalidCredentials.alertText.trim().length > 0,
    JSON.stringify(invalidCredentials));

  console.log('\n=== Populated tenant A: positive baseline and duplicate proof ===');
  await signIn(page, state.populatedEmail);
  check('successful sign-in removes the auth dialog before Home proof',
    await surfaceIsUnobstructed(page, 'body'), 'auth dialog or focus trap remains');
  await goSaved(page);
  check('saved-homes heading renders', await waitVisible(page, '#saved-homes-title'));
  check('tenant A first page renders exact authoritative IDs and titles',
    await waitSavedCards(page, populatedFirstPage));
  await page.waitForTimeout(450);
  check('saved-homes authenticated surface is visible and hit-testable',
    await surfaceIsUnobstructed(page, '#tenant-saved-home-grid'),
    JSON.stringify(await surfaceObstructionFacts(page, '#tenant-saved-home-grid')));
  check('tenant A first page contains six exact card IDs',
    (await savedIds(page)).length === 6);
  const favoritesRead = receipt.api.findLast(entry =>
    entry.method === 'GET' && entry.path.startsWith('/api/v1/favorites/properties?page=0'));
  check('saved homes GET reaches genuine backend with 200', favoritesRead?.status === 200);
  observeGap('saved homes GET is no-store', hasNoStore(favoritesRead?.cacheControl),
    favoritesRead?.cacheControl);
  const documentMarker = await markDocument(page);

  controls.duplicateFavoritesPage = true;
  const savedMoreFacts = await focusAndHeight(page, 'Show more saved homes');
  check('saved-home load-more has a visible focused 44px target',
    savedMoreFacts.focused && savedMoreFacts.hitTestable
      && savedMoreFacts.height >= 44, JSON.stringify(savedMoreFacts));
  const savedPageOneBefore = apiPageRequestCount(1, '/api/v1/favorites/properties');
  await doubleClickNamedButton(page, 'Show more saved homes');
  check('forced duplicate saved-home page appends one card per exact ID',
    await waitSavedCards(page, populatedAllFavorites));
  check('repeated saved-home Load More click issues exactly one page request',
    apiPageRequestCount(1, '/api/v1/favorites/properties') - savedPageOneBefore === 1);
  const savedDuplicateCounts = await savedIds(page);
  check('saved-home exact IDs remain unique after forced duplicate response',
    new Set(savedDuplicateCounts).size === savedDuplicateCounts.length,
    JSON.stringify(savedDuplicateCounts));
  check('saved-home last-page termination removes Load More',
    !(await visibleButton(page, 'Show more saved homes').isVisible().catch(() => false)));

  await goVisits(page);
  check('visit-history heading renders', await waitVisible(page, '#tenant-visits-page-title'));
  check('visit-history authenticated surface is visible and hit-testable',
    await surfaceIsUnobstructed(page, '.tenant-v0-request-card'));
  check('tenant A visit history renders exact first-page IDs and titles',
    await waitVisitCards(page, populatedFirstVisits));
  check('tenant A authoritative visit count is truthful',
    await waitVisible(page, 'text=9 requests sent'));
  const visitRead = receipt.api.findLast(entry =>
    entry.method === 'GET' && entry.path.startsWith('/api/v1/tenant/visit-requests?page=0'));
  check('tenant visit history GET reaches genuine backend with 200', visitRead?.status === 200);
  observeGap('tenant visit history GET is no-store', hasNoStore(visitRead?.cacheControl),
    visitRead?.cacheControl);

  controls.duplicateVisitsPage = true;
  const visitMoreFacts = await focusAndHeight(page, 'Load more requests');
  check('visit load-more has a visible focused 44px target',
    visitMoreFacts.focused && visitMoreFacts.hitTestable
      && visitMoreFacts.height >= 44, JSON.stringify(visitMoreFacts));
  const visitPageOneBefore = apiPageRequestCount(1, '/api/v1/tenant/visit-requests');
  await doubleClickNamedButton(page, 'Load more requests');
  check('forced duplicate visit page appends one card per exact property ID',
    await waitVisitCards(page, populatedAllVisits));
  check('repeated visit Load More click issues exactly one page request',
    apiPageRequestCount(1, '/api/v1/tenant/visit-requests') - visitPageOneBefore === 1);
  const duplicatedVisits = await visitCards(page);
  check('visit-history exact property IDs remain unique after forced duplicate response',
    new Set(duplicatedVisits.map(card => card.propertyId)).size === duplicatedVisits.length,
    JSON.stringify(duplicatedVisits));
  check('visit-history last-page termination removes Load More',
    !(await visibleButton(page, 'Load more requests').isVisible().catch(() => false)));

  console.log('\n=== Same-user token rotation and A → B held responses ===');
  await goSaved(page);
  const savedAfterNavigation = await poll(async () => {
    const ids = await savedIds(page);
    const firstPage = populatedFirstPage.map(entry => entry.id);
    const allRows = populatedAllFavorites.map(entry => entry.id);
    return sameArray(ids, firstPage) || sameArray(ids, allRows);
  }, 10_000);
  check('navigate back to saved homes without document replacement',
    Boolean(savedAfterNavigation));
  const rotatedA = await directLogin(state.populatedEmail);
  await rotateToken(page, rotatedA);
  check('same-User fresh token reloads only current first-page saved homes',
    await waitSavedCards(page, populatedFirstPage));
  const rotatedIds = await savedIds(page);
  check('same-User token rotation introduces no duplicate saved-home cards',
    new Set(rotatedIds).size === rotatedIds.length);

  const heldSaved = beginHold('favorites', 0, 'success', { size: 6 });
  await page.evaluate(() => window.dispatchEvent(new Event('pathome_auth_changed')));
  await withTimeout(heldSaved.captured.promise, 20_000, 'tenant A held saved-home response');
  const tenantBBaselineRelease = deferred();
  const tenantBBaselineCaptured = deferred();
  controls.freezeAfterFirst = {
    kind: 'favorites',
    page: 0,
    authorization: `Bearer ${alternate.token}`,
    allowed: 0,
    captured: tenantBBaselineCaptured,
    release: tenantBBaselineRelease
  };
  await installIdentity(page, alternate);
  await withTimeout(tenantBBaselineCaptured.promise, 20_000,
    'tenant B initial saved-home request while A is held');
  check('tenant B saved-homes initial load remains pending while tenant A is held',
    await waitVisible(page, '[role="status"][aria-label="Loading saved homes"]'));
  releaseHold(heldSaved);
  await delay(500);
  check('late tenant A success does not clear tenant B pending loading state',
    await page.locator('[role="status"][aria-label="Loading saved homes"]').isVisible()
      .catch(() => false));
  tenantBBaselineRelease.resolve();
  controls.freezeAfterFirst = null;
  check('tenant B saved-homes becomes actionable after its current request settles',
    await waitSavedCards(page, alternateFirstPage)
      && await surfaceIsUnobstructed(page, '#tenant-saved-home-grid'));
  check('tenant B retains only its saved-home IDs after late tenant A success',
    sameArray(await savedIds(page), alternateFirstPage.map(entry => entry.id)));
  check('tenant B saved homes contain no tenant A card ID',
    (await savedIds(page)).every(id => !populatedAllFavorites.some(entry => entry.id === id)));
  check('late tenant A success leaves truthful non-loading saved-home state',
    !(await currentAlertText(page)).some(text => text.includes('Saved homes couldn’t be loaded')));
  await assertSameDocument(page, documentMarker, 'A→B saved-home proof stays in one document');

  controls.duplicateFavoritesPage = true;
  await visibleButton(page, 'Show more saved homes').click();
  check('tenant B saved-home second page has exact IDs with no duplicates',
    await waitSavedCards(page, alternateAllFavorites));

  console.log('\n=== B → A held load-more responses ===');
  const alternateVisitBaseline = await directLogin(state.alternateEmail);
  await installIdentity(page, alternateVisitBaseline);
  await goVisits(page);
  check('tenant B visit history renders exact first-page IDs and titles',
    await waitVisitCards(page, alternateFirstVisits));
  const heldVisitPage = beginHold('visits', 1, 'success');
  await visibleButton(page, 'Load more requests').click();
  await withTimeout(heldVisitPage.captured.promise, 20_000, 'tenant B held visit page 1');
  await installIdentity(page, rotatedA);
  check('tenant A visit history renders its own first page while B page 1 is held',
    await waitVisitCards(page, populatedFirstVisits));
  const tenantAVisitRelease = deferred();
  const tenantAVisitCaptured = deferred();
  controls.freezeAfterFirst = {
    kind: 'visits',
    page: 1,
    authorization: `Bearer ${rotatedA.token}`,
    allowed: 0,
    captured: tenantAVisitCaptured,
    release: tenantAVisitRelease
  };
  await visibleButton(page, 'Load more requests').click();
  await withTimeout(tenantAVisitCaptured.promise, 20_000,
    'tenant A current visit page 1 while B finalizer is pending');
  releaseHold(heldVisitPage);
  await delay(500);
  const visitPendingDuringOldFinalizer = await visitLoadMoreControl(page);
  check('late tenant B finalizer cannot unlock tenant A pending visit Load More',
    visitPendingDuringOldFinalizer.visible
      && visitPendingDuringOldFinalizer.disabled
      && visitPendingDuringOldFinalizer.text.includes('Loading more'),
    JSON.stringify(visitPendingDuringOldFinalizer));
  check('tenant A retains only its current visit page while B finalizer settles',
    await waitVisitCards(page, populatedFirstVisits, 1_000));
  check('late tenant B load-more introduces no B visit property ID into tenant A',
    (await visitCards(page)).every(card =>
      !alternateAllVisits.some(entry => entry.propertyId === card.propertyId)));
  check('late tenant B finalizer introduces no stale visit alert',
    !(await currentAlertText(page)).some(text => text.includes('Could not load more requests')));
  tenantAVisitRelease.resolve();
  controls.freezeAfterFirst = null;
  controls.activeHold = null;
  check('tenant A current visit page settles after the isolated finalizer gate',
    await waitVisitCards(page, populatedAllVisits));
  check('tenant A visit pagination reaches terminal state once',
    !(await visibleButton(page, 'Load more requests').isVisible().catch(() => false)));
  await assertSameDocument(page, documentMarker, 'B→A visit load-more proof stays in one document');

  console.log('\n=== A → B → A, late error, retry and finalizer ===');
  const finalA = await directLogin(state.populatedEmail);
  await installIdentity(page, finalA);
  await goVisits(page);
  check('A→B→A restores tenant A visit history exact rows',
    await waitVisitCards(page, populatedFirstVisits));
  const heldABASuccess = beginHold('visits', 0, 'success');
  await page.evaluate(() => window.dispatchEvent(new Event('pathome_auth_changed')));
  await withTimeout(heldABASuccess.captured.promise, 20_000, 'A→B→A held visit response');
  await installIdentity(page, alternate);
  await installIdentity(page, finalA);
  releaseHold(heldABASuccess);
  await delay(500);
  check('A→B→A final tenant retains only current authoritative visit rows',
    await waitVisitCards(page, populatedFirstVisits));
  check('A→B→A late response does not replace the final A count',
    await waitVisible(page, 'text=9 requests sent'));

  const heldVisitError = beginHold('visits', 0, 'error');
  await page.evaluate(() => window.dispatchEvent(new Event('pathome_auth_changed')));
  await withTimeout(heldVisitError.captured.promise, 20_000, 'held visit error response');
  const tenantBVisitRelease = deferred();
  const tenantBVisitCaptured = deferred();
  controls.freezeAfterFirst = {
    kind: 'visits',
    page: 0,
    authorization: `Bearer ${alternateVisitBaseline.token}`,
    allowed: 0,
    captured: tenantBVisitCaptured,
    release: tenantBVisitRelease
  };
  await installIdentity(page, alternateVisitBaseline);
  await withTimeout(tenantBVisitCaptured.promise, 20_000,
    'tenant B initial visit request while stale A error is held');
  check('tenant B visit history remains pending while stale A error is held',
    await waitVisible(page, 'text=Loading visit requests…'));
  releaseHold(heldVisitError);
  await delay(500);
  check('late tenant A error does not clear tenant B pending visit loading state',
    await page.getByText('Loading visit requests…', { exact: true }).isVisible()
      .catch(() => false));
  check('late tenant A error leaves no stale visit alert in tenant B',
    !(await currentAlertText(page)).some(text => text.includes('Visit requests are unavailable.')));
  tenantBVisitRelease.resolve();
  controls.freezeAfterFirst = null;
  check('tenant B visit history becomes actionable after its current request settles',
    await waitVisitCards(page, alternateFirstVisits)
      && await surfaceIsUnobstructed(page, '.tenant-v0-request-card'));
  check('late error finalizer does not leave visit loading text',
    !(await page.getByText('Loading visit requests…', { exact: true }).isVisible().catch(() => false)));

  await installIdentity(page, finalA);
  await goSaved(page);
  check('tenant A restored before controlled failures',
    await waitSavedCards(page, populatedFirstPage));

  const staleSavedPage = beginHold('favorites', 1, 'success', { size: 6 });
  await visibleButton(page, 'Show more saved homes').click();
  await withTimeout(staleSavedPage.captured.promise, 20_000,
    'tenant A saved-home page 1 before same-User refresh');
  const savedPageZeroBefore = apiPageRequestCount(0, '/api/v1/favorites/properties');
  await page.evaluate(() => window.dispatchEvent(new Event('pathome_auth_changed')));
  await poll(() => apiPageRequestCount(0, '/api/v1/favorites/properties') > savedPageZeroBefore,
    20_000);
  releaseHold(staleSavedPage);
  await delay(500);
  check('stale saved-home page cannot replace or advance current page-zero state',
    await waitSavedCards(page, populatedFirstPage, 1_000)
      && (await savedCards(page)).length === 6);
  const savedAfterStalePage = await savedLoadMoreControl(page);
  check('stale saved-home page leaves the current terminal action actionable',
    savedAfterStalePage.visible && !savedAfterStalePage.disabled
      && savedAfterStalePage.text === 'Show more saved homes',
    JSON.stringify(savedAfterStalePage));
  check('stale saved-home page leaves no page error alert',
    !(await currentAlertText(page)).some(text => text.includes('Could not load more saved homes')));
  await visibleButton(page, 'Show more saved homes').click();
  check('current saved-home page-one retry reaches exact ordered terminal rows',
    await waitSavedCards(page, populatedAllFavorites));
  check('saved-home pagination terminal state removes Load More',
    !(await visibleButton(page, 'Show more saved homes').isVisible().catch(() => false)));

  controls.failFavoritesOnce = true;
  await page.evaluate(() => window.dispatchEvent(new Event('pathome_auth_changed')));
  const savedAlert = page.getByRole('alert').filter({
    hasText: 'Saved homes couldn’t be loaded right now.'
  });
  check('controlled saved-home 503 shows a truthful alert',
    await savedAlert.first().waitFor({ state: 'visible', timeout: 15_000 })
      .then(() => true).catch(() => false));
  const retrySaved = savedAlert.getByRole('button', { name: 'Retry', exact: true });
  await retrySaved.focus();
  check('saved-home Retry is keyboard focused', await retrySaved.evaluate(
    element => document.activeElement === element));
  await page.keyboard.press('Enter');
  check('saved-home keyboard Retry recovers exact current rows',
    await waitSavedCards(page, populatedFirstPage));

  await goVisits(page);
  controls.failVisitsOnce = true;
  await page.evaluate(() => window.dispatchEvent(new Event('pathome_auth_changed')));
  const visitAlert = page.getByRole('alert').filter({ hasText: 'Visit requests are unavailable.' });
  check('controlled visit-history 503 shows a truthful alert',
    await visitAlert.first().waitFor({ state: 'visible', timeout: 15_000 })
      .then(() => true).catch(() => false));
  const retryVisits = visitAlert.getByRole('button', { name: 'Retry', exact: true });
  await retryVisits.focus();
  await page.keyboard.press('Enter');
  check('visit-history keyboard Retry recovers exact count and rows',
    await waitVisitCards(page, populatedFirstVisits)
      && await waitVisible(page, 'text=9 requests sent'));

  controls.abortVisitsOnce = true;
  await page.evaluate(() => window.dispatchEvent(new Event('pathome_auth_changed')));
  const abortAlert = page.getByRole('alert').filter({ hasText: 'Visit requests are unavailable.' });
  check('aborted visit request shows truthful network-error recovery',
    await abortAlert.first().waitFor({ state: 'visible', timeout: 15_000 })
      .then(() => true).catch(() => false));
  await abortAlert.getByRole('button', { name: 'Retry', exact: true }).click();
  check('visit-history recovers after abort',
    await waitVisitCards(page, populatedFirstVisits));

  console.log('\n=== Invalid/absent token, wrong role and logout fencing ===');
  await goSaved(page);
  await page.evaluate(() => {
    localStorage.setItem('pathome_auth_token', 'not-a-valid-token');
    window.dispatchEvent(new Event('pathome_auth_changed'));
  });
  await poll(async () => (await savedIds(page)).length === 0, 20_000);
  check('invalid token removes saved-home cards',
    (await savedIds(page)).length === 0);
  check('invalid token is treated as invalid authentication, not a claimed expired JWT',
    !(await currentAlertText(page)).some(text => /expired jwt/i.test(text)));

  if (!(await page.getByRole('button', { name: 'Sign in', exact: true }).first()
    .isVisible().catch(() => false))) {
    await installIdentity(page, finalA);
  }
  if (await page.getByRole('button', { name: 'Sign in', exact: true }).first()
    .isVisible().catch(() => false)) {
    await signIn(page, state.populatedEmail);
  }
  await goSaved(page);
  await waitSavedCards(page, populatedFirstPage);

  await removeToken(page);
  await poll(async () => (await savedIds(page)).length === 0, 10_000);
  check('absent-token attack state exposes no saved-home card',
    (await savedIds(page)).length === 0);
  await installIdentity(page, finalA);
  await goSaved(page);
  await waitSavedCards(page, populatedFirstPage);

  await installIdentity(page, denied);
  await poll(async () => (await savedIds(page)).length === 0, 10_000);
  check('wrong-role identity exposes no tenant A saved-home card',
    (await savedIds(page)).length === 0);

  await installIdentity(page, finalA);
  await goSaved(page);
  await waitSavedCards(page, populatedFirstPage);
  const responseAfterLogout = beginHold('favorites', 0, 'success', { size: 6 });
  await page.evaluate(() => window.dispatchEvent(new Event('pathome_auth_changed')));
  await withTimeout(responseAfterLogout.captured.promise, 20_000,
    'tenant A response held across real UI logout');
  const logoutFacts = await logoutThroughUi(page);
  check('real UI logout clears authoritative session storage and exposes sign-in',
    logoutFacts.signInVisible
      && !logoutFacts.storage.tokenPresent
      && !logoutFacts.storage.userPresent
      && !logoutFacts.storage.rolePresent,
    JSON.stringify(logoutFacts));
  releaseHold(responseAfterLogout);
  await delay(500);
  check('pre-logout response cannot repopulate Home after the real logout control',
    (await savedIds(page)).length === 0
      && await page.getByRole('button', { name: 'Sign in', exact: true }).first()
        .isVisible().catch(() => false));
  controls.activeHold = null;
  await signIn(page, state.populatedEmail);
  await goSaved(page);
  check('tenant A can restore after logout in the same document',
    await waitSavedCards(page, populatedFirstPage));
  await assertSameDocument(page, documentMarker,
    'identity, token, role and logout transitions keep the same application document');

  console.log('\n=== Both Home surfaces at required viewports ===');
  for (const width of [375, 390, 768, 1440]) {
    await page.setViewportSize({ width, height: width < 768 ? 812 : 1000 });
    await goSaved(page);
    check(`saved homes render exact first page at ${width}px`,
      await waitSavedCards(page, populatedFirstPage));
    const savedViewportAction = await focusAndHeight(page, 'Show more saved homes');
    check(`saved homes remain focusable and hit-testable at ${width}px`,
      savedViewportAction.focused && savedViewportAction.hitTestable
        && savedViewportAction.height >= 44,
      JSON.stringify(savedViewportAction));
    check(`saved homes auth overlay is absent at ${width}px`,
      await surfaceIsUnobstructed(page, '#tenant-saved-home-grid'),
      JSON.stringify(await surfaceObstructionFacts(page, '#tenant-saved-home-grid')));
    await page.waitForTimeout(120);
    const savedDimensions = await noOverflow(page);
    check(`saved homes have no ${width}px horizontal overflow`,
      savedDimensions.scrollWidth <= savedDimensions.innerWidth,
      JSON.stringify(savedDimensions));
    receipt.viewports.push({ screen: 'saved-homes', width, ...savedDimensions });
    const savedScreenshot = `saved-homes-${width}.png`;
    await page.screenshot({ path: join(dirname(receiptPath), savedScreenshot), fullPage: true });
    receipt.screenshots.push(savedScreenshot);

    await goVisits(page);
    check(`visit history renders exact first page at ${width}px`,
      await waitVisitCards(page, populatedFirstVisits));
    const visitViewportAction = await focusAndHeight(page, 'Load more requests');
    check(`visit history remains focusable and hit-testable at ${width}px`,
      visitViewportAction.focused && visitViewportAction.hitTestable
        && visitViewportAction.height >= 44,
      JSON.stringify(visitViewportAction));
    check(`visit history auth overlay is absent at ${width}px`,
      await surfaceIsUnobstructed(page, '.tenant-v0-request-card'),
      JSON.stringify(await surfaceObstructionFacts(page, '.tenant-v0-request-card')));
    await page.waitForTimeout(120);
    const visitDimensions = await noOverflow(page);
    check(`visit history has no ${width}px horizontal overflow`,
      visitDimensions.scrollWidth <= visitDimensions.innerWidth,
      JSON.stringify(visitDimensions));
    receipt.viewports.push({ screen: 'visit-history', width, ...visitDimensions });
    const visitScreenshot = `visit-history-${width}.png`;
    await page.screenshot({ path: join(dirname(receiptPath), visitScreenshot), fullPage: true });
    receipt.screenshots.push(visitScreenshot);
  }
  check('primary journey causes no uncaught browser error', pageErrors.length === 0,
    pageErrors.join(' | '));

  await env.context.close();
  console.log('\n=== Test-only browser negative controls ===');
  await runPairedControl(browser, 'fence', [
    'tenant B Home retains its authoritative saved count after late tenant A payload'
  ], 'session ownership guard');
  await runPairedControl(browser, 'finalizer', [
    'previous same-User finalizer cannot unlock the newer pending visit Load More'
  ], 'old load-more finalizer controller ownership guard');
  await runMutant(browser, 'dedup', [
    'saved-home forced duplicate page remains one card per ID',
    'visit-history forced duplicate page remains one card per property ID'
  ]);
  await runOverlayControl(browser);
};

const negativeFenceJourney = async browser => {
  // Keep the already-fetched stale response alive so the identity continuation itself is tested.
  const env = await freshContext(browser, { disableAbort: true });
  const { page } = env;
  const alternate = await directLogin(state.alternateEmail);
  const alternateSaved = expectedFavorites('alternate', 6);

  await signIn(page, state.populatedEmail);
  await page.setViewportSize({ width: 375, height: 812 });
  await goSaved(page);
  check('paired fence fixture establishes tenant A current saved count',
    await waitSavedDockCount(page, 7));
  const documentMarker = await markDocument(page);
  const savedHold = beginHold('favorites', 0, 'truncated-count', { size: 24 });
  await page.evaluate(() => window.dispatchEvent(new Event('pathome_auth_changed')));
  await withTimeout(savedHold.captured.promise, 20_000, 'paired saved stale response');
  await installIdentity(page, alternate);
  const currentSaved = await waitSavedCards(page, alternateSaved);
  const currentSavedWindowSettled = await waitForApiWindowToSettle();
  check('paired fence sequence establishes tenant B current saved count before release',
    currentSaved && currentSavedWindowSettled
      && await waitSavedDockCount(page, 7)
      && await surfaceIsUnobstructed(page, '#tenant-saved-home-grid'));
  releaseHold(savedHold);
  await delay(700);
  const fenceCallbackMarker = await page.evaluate(() =>
    document.body.dataset.pathomeSessionFenceCallback ?? 'absent');
  console.log(`  INFO  session-fence callback marker: ${fenceCallbackMarker}`);
  const savedFacts = {
    cards: await savedCards(page),
    alerts: await currentAlertText(page),
    savedDockCount: await waitSavedDockCount(page, 7, 1_000)
  };
  check('tenant B Home retains its authoritative saved count after late tenant A payload',
    sameSavedCards(savedFacts.cards, alternateSaved)
      && savedFacts.cards.length === alternateSaved.length
      && savedFacts.savedDockCount
      && !savedFacts.alerts.some(text => text.includes('Saved homes couldn’t be loaded')),
    JSON.stringify(savedFacts));
  await assertSameDocument(page, documentMarker,
    'paired saved-home fence stays in one application document');

  controls.activeHold = null;
  await env.context.close();
};

const negativeFinalizerJourney = async browser => {
  // Keep the retired request alive until the newer request owns the load-more gate.
  const env = await freshContext(browser, { disableAbort: true });
  const { page } = env;
  const populatedFirst = expectedVisits('populated', 8);
  const populatedAll = expectedVisits('populated', 9);

  await signIn(page, state.populatedEmail);
  await goVisits(page);
  await waitVisitCards(page, populatedFirst);
  const stalePage = beginHold('visits', 1, 'empty-page');
  await visibleButton(page, 'Load more requests').click();
  await withTimeout(stalePage.captured.promise, 20_000,
    'previous same-User load-more response held across refresh');

  const currentToken = await page.evaluate(() => localStorage.getItem('pathome_auth_token'));
  const pageZeroBefore = apiPageRequestCount(0, '/api/v1/tenant/visit-requests');
  await page.evaluate(() => window.dispatchEvent(new Event('pathome_auth_changed')));
  await poll(() => apiPageRequestCount(0, '/api/v1/tenant/visit-requests') > pageZeroBefore,
    20_000);
  await waitVisitCards(page, populatedFirst);
  const currentPageRelease = deferred();
  const currentPageCaptured = deferred();
  controls.freezeAfterFirst = {
    kind: 'visits',
    page: 1,
    authorization: `Bearer ${currentToken}`,
    allowed: 0,
    captured: currentPageCaptured,
    release: currentPageRelease
  };
  await visibleButton(page, 'Load more requests').click();
  await withTimeout(currentPageCaptured.promise, 20_000,
    'current same-User load-more while previous finalizer is pending');
  const currentPendingMounted = await poll(async () => {
    const control = await visitLoadMoreControl(page);
    return control.visible && control.disabled && control.text.includes('Loading more');
  }, 10_000);
  check('newer same-User load-more owns the pending UI before old finalizer release',
    Boolean(currentPendingMounted));
  releaseHold(stalePage);
  await delay(700);
  const finalizerCallbackMarker = await page.evaluate(() =>
    document.body.dataset.pathomeFinalizerCallback ?? 'absent');
  console.log(`  INFO  finalizer callback marker: ${finalizerCallbackMarker}`);
  const pendingFacts = {
    control: await visitLoadMoreControl(page),
    cards: await visitCards(page)
  };
  check('previous same-User finalizer cannot unlock the newer pending visit Load More',
    pendingFacts.control.visible
      && pendingFacts.control.disabled
      && pendingFacts.control.text.includes('Loading more')
      && sameArray(pendingFacts.cards.map(card => card.propertyId),
        populatedFirst.map(entry => entry.propertyId)),
    JSON.stringify(pendingFacts));
  currentPageRelease.resolve();
  controls.freezeAfterFirst = null;
  controls.activeHold = null;
  check('same-User finalizer pair reaches exact ordered populated terminal state',
    await waitVisitCards(page, populatedAll)
      && !(await visibleButton(page, 'Load more requests').isVisible().catch(() => false)));
  await env.context.close();
};

const negativeDedupJourney = async browser => {
  const env = await freshContext(browser);
  const { page } = env;
  const populatedSaved = expectedFavorites('populated', 7);
  const populatedVisits = expectedVisits('populated', 9);
  await signIn(page, state.populatedEmail);
  await goSaved(page);
  await waitSavedCards(page, populatedSaved.slice(0, 6));
  controls.duplicateFavoritesPage = true;
  await visibleButton(page, 'Show more saved homes').click();
  await delay(800);
  const saved = await savedIds(page);
  check('saved-home forced duplicate page remains one card per ID',
    populatedSaved.every(entry => saved.filter(id => id === entry.id).length === 1)
      && saved.length === 7,
    JSON.stringify(saved));

  await goVisits(page);
  await waitVisitCards(page, populatedVisits.slice(0, 8));
  controls.duplicateVisitsPage = true;
  await visibleButton(page, 'Load more requests').click();
  await delay(800);
  const visits = await visitCards(page);
  check('visit-history forced duplicate page remains one card per property ID',
    populatedVisits.every(entry =>
      visits.filter(card => card.propertyId === entry.propertyId).length === 1)
      && visits.length === 9,
    JSON.stringify(visits));
  await env.context.close();
};

const negativeOverlayJourney = async browser => {
  const env = await freshContext(browser);
  const { page } = env;
  await signIn(page, state.populatedEmail);
  await goSaved(page);
  await waitSavedCards(page, expectedFavorites('populated', 6));
  await page.evaluate(() => {
    const overlay = document.createElement('div');
    overlay.setAttribute('role', 'dialog');
    overlay.setAttribute('aria-modal', 'true');
    overlay.setAttribute('aria-label', 'Sign in or create account');
    overlay.style.cssText = 'position:fixed;inset:0;z-index:2147483647;background:rgba(15,23,42,.72)';
    document.body.appendChild(overlay);
    document.body.style.overflow = 'hidden';
  });
  const facts = await surfaceObstructionFacts(page, '#tenant-saved-home-grid');
  check('forced login overlay cannot pass authenticated Home proof',
    await surfaceIsUnobstructed(page, '#tenant-saved-home-grid'), JSON.stringify(facts));
  await page.screenshot({
    path: join(dirname(receiptPath), 'forced-login-overlay.png'),
    fullPage: true
  });
  receipt.screenshots.push('forced-login-overlay.png');
  await env.context.close();
};

const runOverlayControl = async browser => {
  const negativeDirectory = join(dirname(receiptPath), 'negative-controls');
  await mkdir(negativeDirectory, { recursive: true });
  const mutantReceipt = join(negativeDirectory, 'overlay-receipt.json');
  const mutantStop = join(negativeDirectory, 'overlay-stop.txt');
  const mutantLog = join(negativeDirectory, 'overlay.log');
  const expectedLabel = 'forced login overlay cannot pass authenticated Home proof';
  const result = await runChild({
    PATHOME_HOME_SHELL_BROWSER_STOP_FILE: mutantStop,
    PATHOME_HOME_SHELL_BROWSER_RECEIPT: mutantReceipt,
    PATHOME_HOME_SHELL_NEGATIVE_CONTROL: 'overlay'
  }, mutantLog);
  const childReceipt = JSON.parse(await readFile(mutantReceipt, 'utf8'));
  const failed = childReceipt.failedLabels ?? [];
  const expectedFailureObserved = result.code !== 0
    && childReceipt.failures === 1
    && failed.length === 1
    && failed[0] === expectedLabel;
  check('overlay mutant fails the exact authenticated-surface control',
    expectedFailureObserved,
    `exit=${result.code} failures=${childReceipt.failures} observed=${failed.join(' | ')}`);
  receipt.negativeControls.push({
    mode: 'overlay',
    exitCode: result.code,
    checks: childReceipt.checks,
    failures: childReceipt.failures,
    failedLabels: failed,
    expectedFailedLabels: [expectedLabel],
    receipt: 'negative-controls/overlay-receipt.json',
    log: 'negative-controls/overlay.log',
    screenshot: 'negative-controls/forced-login-overlay.png'
  });
};

const bundleScript = async dist => {
  const html = await readFile(join(dist, 'index.html'), 'utf8');
  const match = html.match(/<script[^>]+src="([^"]+\.js)"/);
  if (!match) throw new Error('production bundle script was not found');
  return join(dist, match[1].replace(/^\//, ''));
};
const sha256File = async path =>
  createHash('sha256').update(await readFile(path)).digest('hex');
const recordSourceFingerprint = async () => {
  const bundle = await bundleScript(DIST);
  receipt.source = {
    runnerPath: RUNNER_PATH,
    runnerSha256: await sha256File(RUNNER_PATH),
    indexSha256: await sha256File(join(DIST, 'index.html')),
    bundlePath: bundle,
    bundleSha256: await sha256File(bundle)
  };
};
const mutateDist = async (sourceDist, destination, mode) => {
  await cp(sourceDist, destination, { recursive: true });
  const script = await bundleScript(destination);
  let code = await readFile(script, 'utf8');
  const mutations = [];
  const mutate = (before, after, label) => {
    const occurrences = code.split(before).length - 1;
    if (occurrences !== 1) {
      throw new Error(`${label} mutation requires exactly one target, found ${occurrences}`);
    }
    code = code.replace(before, after);
    mutations.push(label);
  };
  if (mode === 'fence') {
    mutate(
      'function is(e){var t;return((t=zs(e.userId))==null?void 0:t.key)===e.key}',
      "function is(e){document.body.dataset.pathomeSessionFenceCallback='observed';return true}",
      'current-session ownership check'
    );
  } else if (mode === 'finalizer') {
    mutate(
      'finally{Oe.current===k&&(Oe.current=null,it.current=!1,ae(!1))}',
      "finally{!0&&(document.body.dataset.pathomeFinalizerCallback='observed',"
        + 'Oe.current=null,it.current=!1,ae(!1))}',
      'visit load-more finalizer controller ownership'
    );
  } else if (mode === 'dedup') {
    mutate(
      'const v0=(e,t)=>{const n=new Set;return[...e,...t].filter(r=>n.has(r.id)?!1:(n.add(r.id),!0))}',
      'const v0=(e,t)=>[...e,...t]',
      'saved-home deduplication'
    );
    mutate(
      'function dU(e,t){const n=new Set(e.map(a=>a.requestId)),r=[...e];'
        + 'for(const a of t)n.has(a.requestId)||(n.add(a.requestId),r.push(a));return r}',
      'function dU(e,t){return[...e,...t]}',
      'visit-history deduplication'
    );
  } else {
    throw new Error(`unknown mutant mode: ${mode}`);
  }
  await writeFile(script, code);
  return { mutations, bundleSha256: createHash('sha256').update(code).digest('hex') };
};
const runChild = (environment, logPath) => new Promise(async (resolve, reject) => {
  const child = spawn(process.execPath, [RUNNER_PATH], {
    env: { ...process.env, ...environment },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  let output = '';
  child.stdout.on('data', chunk => { output += chunk.toString(); });
  child.stderr.on('data', chunk => { output += chunk.toString(); });
  child.on('error', reject);
  child.on('close', async code => {
    await writeFile(logPath, output);
    resolve({ code, output });
  });
});
const runMutant = async (browser, mode, expectedFailedLabels) => {
  const root = await mkdtemp(join(tmpdir(), `pathome-home-shell-${mode}-`));
  const mutantDist = join(root, 'dist');
  const negativeDirectory = join(dirname(receiptPath), 'negative-controls');
  await mkdir(negativeDirectory, { recursive: true });
  const mutantReceipt = join(negativeDirectory, `${mode}-receipt.json`);
  const mutantStop = join(negativeDirectory, `${mode}-stop.txt`);
  const mutantLog = join(negativeDirectory, `${mode}.log`);
  try {
    await mutateDist(DIST, mutantDist, mode);
    const result = await runChild({
      PATHOME_HOME_SHELL_BROWSER_DIST: mutantDist,
      PATHOME_HOME_SHELL_BROWSER_STOP_FILE: mutantStop,
      PATHOME_HOME_SHELL_BROWSER_RECEIPT: mutantReceipt,
      PATHOME_HOME_SHELL_NEGATIVE_CONTROL: mode
    }, mutantLog);
    const childReceipt = JSON.parse(await readFile(mutantReceipt, 'utf8'));
    const observedFailures = new Set(
      String(await readFile(mutantLog, 'utf8')).matchAll(/  FAIL  (.+?)(?: \(|$)/gm)
    );
    const failed = [...observedFailures].map(match => match[1].trim());
    const expectedFailuresObserved = childReceipt.failures === expectedFailedLabels.length
      && failed.length === expectedFailedLabels.length
      && expectedFailedLabels.every(label => failed.includes(label));
    check(`${mode} mutant fails the exact stale/dedup controls`,
      result.code !== 0 && expectedFailuresObserved,
      `exit=${result.code} failures=${childReceipt.failures} observed=${failed.join(' | ')}`);
    receipt.negativeControls.push({
      mode,
      exitCode: result.code,
      checks: childReceipt.checks,
      failures: childReceipt.failures,
      failedLabels: failed,
      expectedFailedLabels,
      receipt: `negative-controls/${mode}-receipt.json`,
      log: `negative-controls/${mode}.log`
    });
  } finally {
    await rm(root, { recursive: true, force: true });
  }
};
const runPairedControl = async (browser, mode, expectedFailedLabels, guard) => {
  const negativeDirectory = join(dirname(receiptPath), 'negative-controls');
  await mkdir(negativeDirectory, { recursive: true });
  const baselineReceiptPath = join(negativeDirectory, `${mode}-baseline-receipt.json`);
  const baselineStop = join(negativeDirectory, `${mode}-baseline-stop.txt`);
  const baselineLog = join(negativeDirectory, `${mode}-baseline.log`);
  const mutantReceiptPath = join(negativeDirectory, `${mode}-receipt.json`);
  const mutantStop = join(negativeDirectory, `${mode}-stop.txt`);
  const mutantLog = join(negativeDirectory, `${mode}.log`);
  const root = await mkdtemp(join(tmpdir(), `pathome-home-shell-paired-${mode}-`));
  const mutantDist = join(root, 'dist');
  try {
    const baselineResult = await runChild({
      PATHOME_HOME_SHELL_BROWSER_DIST: DIST,
      PATHOME_HOME_SHELL_BROWSER_STOP_FILE: baselineStop,
      PATHOME_HOME_SHELL_BROWSER_RECEIPT: baselineReceiptPath,
      PATHOME_HOME_SHELL_NEGATIVE_CONTROL: `${mode}-baseline`
    }, baselineLog);
    const baselineReceipt = JSON.parse(await readFile(baselineReceiptPath, 'utf8'));
    const baselineGreen = baselineResult.code === 0
      && baselineReceipt.failures === 0
      && baselineReceipt.gaps === 0
      && !baselineReceipt.exception
      && baselineReceipt.checks >= 2;
    check(`${mode} paired production baseline is GREEN with the identical journey`,
      baselineGreen,
      `exit=${baselineResult.code} checks=${baselineReceipt.checks} failures=${
        baselineReceipt.failures} gaps=${baselineReceipt.gaps}`);

    const mutation = await mutateDist(DIST, mutantDist, mode);
    const mutantResult = await runChild({
      PATHOME_HOME_SHELL_BROWSER_DIST: mutantDist,
      PATHOME_HOME_SHELL_BROWSER_STOP_FILE: mutantStop,
      PATHOME_HOME_SHELL_BROWSER_RECEIPT: mutantReceiptPath,
      PATHOME_HOME_SHELL_NEGATIVE_CONTROL: mode
    }, mutantLog);
    const mutantReceipt = JSON.parse(await readFile(mutantReceiptPath, 'utf8'));
    const failed = mutantReceipt.failedLabels ?? [];
    const expectedFailuresObserved = mutantResult.code !== 0
      && !mutantReceipt.exception
      && mutantReceipt.failures === expectedFailedLabels.length
      && failed.length === expectedFailedLabels.length
      && expectedFailedLabels.every(label => failed.includes(label));
    check(`${mode} single-guard mutant is targeted RED on the identical production journey`,
      baselineGreen && expectedFailuresObserved,
      `exit=${mutantResult.code} failures=${mutantReceipt.failures} observed=${
        failed.join(' | ')} exception=${mutantReceipt.exception ?? 'none'}`);
    receipt.negativeControls.push({
      mode,
      paired: true,
      isolatedGuard: guard,
      mutations: mutation.mutations,
      sameFixtureSha256: await sha256File(statePath),
      sameJourney: `negative${mode[0].toUpperCase()}${mode.slice(1)}Journey`,
      baseline: {
        exitCode: baselineResult.code,
        checks: baselineReceipt.checks,
        failures: baselineReceipt.failures,
        gaps: baselineReceipt.gaps,
        source: baselineReceipt.source,
        receipt: `negative-controls/${mode}-baseline-receipt.json`,
        log: `negative-controls/${mode}-baseline.log`
      },
      mutant: {
        exitCode: mutantResult.code,
        checks: mutantReceipt.checks,
        failures: mutantReceipt.failures,
        failedLabels: failed,
        expectedFailedLabels,
        source: mutantReceipt.source,
        bundleSha256AfterMutation: mutation.bundleSha256,
        receipt: `negative-controls/${mode}-receipt.json`,
        log: `negative-controls/${mode}.log`
      }
    });
  } finally {
    await rm(root, { recursive: true, force: true });
  }
};

const browser = await chromium.launch({ channel: 'chromium' });
try {
  await mkdir(dirname(receiptPath), { recursive: true });
  await recordSourceFingerprint();
  if (NEGATIVE_CONTROL === 'fence' || NEGATIVE_CONTROL === 'fence-baseline') {
    console.log(`Home-shell paired session-fence journey (${NEGATIVE_CONTROL})`);
    await negativeFenceJourney(browser);
  } else if (NEGATIVE_CONTROL === 'finalizer' || NEGATIVE_CONTROL === 'finalizer-baseline') {
    console.log(`Home-shell paired load-more-finalizer journey (${NEGATIVE_CONTROL})`);
    await negativeFinalizerJourney(browser);
  } else if (NEGATIVE_CONTROL === 'dedup') {
    console.log('Home-shell isolated append-deduplication mutant');
    await negativeDedupJourney(browser);
  } else if (NEGATIVE_CONTROL === 'overlay') {
    console.log('Home-shell forced login-overlay mutant');
    await negativeOverlayJourney(browser);
  } else if (NEGATIVE_CONTROL) {
    throw new Error(`unknown negative control: ${NEGATIVE_CONTROL}`);
  } else {
    await normalJourney(browser);
  }
} catch (error) {
  failures += 1;
  failedLabels.push('uncaught browser runner exception');
  receipt.exception = error instanceof Error ? error.stack ?? error.message : String(error);
  console.error(receipt.exception);
} finally {
  receipt.checks = checks;
  receipt.failures = failures;
  receipt.failedLabels = failedLabels;
  await writeFile(receiptPath, `${JSON.stringify(receipt, null, 2)}\n`);
  await writeFile(stopPath, failures === 0 ? 'complete\n' : 'failed\n');
  await browser.close();
}

console.log(`\n${checks - failures}/${checks} checks passed`);
if (failures > 0) process.exit(1);
