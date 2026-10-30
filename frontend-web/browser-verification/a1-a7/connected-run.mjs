/**
 * Connected A1-A7 React browser journey. Static assets are local; every API call is proxied to the
 * attested real Spring Boot application. No API response is fabricated.
 */
import { accessSync, mkdirSync, writeFileSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;
const DIST = fileURLToPath(new URL('../../dist/', import.meta.url));
const ORIGIN = 'http://a1-a7.local';
const required = name => {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
};
const backendPort = required('PATHOME_A1_A7_BROWSER_BACKEND_PORT');
const backend = `http://127.0.0.1:${backendPort}`;
const staffToken = required('PATHOME_A1_A7_BROWSER_STAFF_TOKEN');
const staffId = required('PATHOME_A1_A7_BROWSER_STAFF_ID');
const leadId = required('PATHOME_A1_A7_BROWSER_LEAD_ID');
const listingId = required('PATHOME_A1_A7_BROWSER_LISTING_ID');
const listingTitle = required('PATHOME_A1_A7_BROWSER_LISTING_TITLE');
const teamName = required('PATHOME_A1_A7_BROWSER_TEAM_NAME');
const teamId = required('PATHOME_A1_A7_BROWSER_TEAM_ID');
const shortlistId = required('PATHOME_A1_A7_BROWSER_SHORTLIST_ID');
const phoneToken = required('PATHOME_A1_A7_BROWSER_PHONE_TOKEN');
const handoffToken = required('PATHOME_A1_A7_BROWSER_HANDOFF_TOKEN');
const evidence = required('PATHOME_A1_A7_BROWSER_EVIDENCE_DIR');
const tenantEmail = required('PATHOME_A1_A7_BROWSER_TENANT_EMAIL');
const tenantPassword = required('PATHOME_A1_A7_BROWSER_TENANT_PASSWORD');
const tenantId = required('PATHOME_A1_A7_BROWSER_TENANT_ID');
const amitEmail = required('PATHOME_A1_A7_BROWSER_AMIT_EMAIL');
const amitPassword = required('PATHOME_A1_A7_BROWSER_AMIT_PASSWORD');
const amitId = required('PATHOME_A1_A7_BROWSER_AMIT_ID');
const plainEmail = required('PATHOME_A1_A7_BROWSER_PLAIN_EMAIL');
const plainPassword = required('PATHOME_A1_A7_BROWSER_PLAIN_PASSWORD');
const plainId = required('PATHOME_A1_A7_BROWSER_PLAIN_ID');
const expiredToken = required('PATHOME_A1_A7_BROWSER_EXPIRED_TOKEN');
const plainListingId = required('PATHOME_A1_A7_BROWSER_PLAIN_LISTING_ID');
const plainListingTitle = required('PATHOME_A1_A7_BROWSER_PLAIN_LISTING_TITLE');
const adminEmail = required('PATHOME_A1_A7_BROWSER_ADMIN_EMAIL');
const adminPassword = required('PATHOME_A1_A7_BROWSER_ADMIN_PASSWORD');
const adminId = required('PATHOME_A1_A7_BROWSER_ADMIN_ID');
const staffEmail = required('PATHOME_A1_A7_BROWSER_STAFF_EMAIL');
const staffPassword = required('PATHOME_A1_A7_BROWSER_STAFF_PASSWORD');
const contentTypes = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.webp': 'image/webp',
  '.woff2': 'font/woff2'
};

const checks = [];
const api = [];
const screenshots = [];
const check = (name, pass, detail = '') => {
  checks.push({ name, pass: Boolean(pass), detail });
  if (!pass) throw new Error(`${name}${detail ? `: ${detail}` : ''}`);
};
const server = createServer(async (request, response) => {
  const url = new URL(request.url, ORIGIN);
  if (url.pathname.startsWith('/api/')) {
    const headers = { ...request.headers };
    delete headers.host;
    delete headers['content-length'];
    const body = request.method === 'GET' ? undefined : request;
    try {
      const proxied = await fetch(`${backend}${url.pathname}${url.search}`, {
        method: request.method,
        headers,
        body,
        duplex: body ? 'half' : undefined,
        redirect: 'manual',
        signal: AbortSignal.timeout(20_000)
      });
      const responseBody = Buffer.from(await proxied.arrayBuffer());
      const responseHeaders = {
        'Content-Type': proxied.headers.get('content-type') ?? 'application/json',
        'Cache-Control': proxied.headers.get('cache-control') ?? '',
        'Referrer-Policy': proxied.headers.get('referrer-policy') ?? ''
      };
      const cookie = proxied.headers.get('set-cookie');
      if (cookie) responseHeaders['Set-Cookie'] = cookie;
      api.push({
        method: request.method,
        path: url.pathname + url.search,
        status: proxied.status,
        cacheControl: proxied.headers.get('cache-control')
      });
      response.writeHead(proxied.status, responseHeaders);
      response.end(responseBody);
    } catch (error) {
      response.writeHead(503, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ message: 'Connected backend unavailable' }));
    }
    return;
  }
  const file = url.pathname === '/' ? '/index.html' : url.pathname;
  try {
    const body = await readFile(join(DIST, file));
    response.writeHead(200, {
      'Content-Type': contentTypes[extname(file)] || 'application/octet-stream'
    });
    response.end(body);
  } catch {
    const body = await readFile(join(DIST, 'index.html'));
    response.writeHead(200, { 'Content-Type': contentTypes['.html'] });
    response.end(body);
  }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;

const candidates = [
  process.env.PATHOME_BROWSER_CHROMIUM_EXECUTABLE,
  '/Users/dipeshpatidar/Library/Caches/ms-playwright/chromium-1200/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
].filter(Boolean);
const executablePath = candidates.find(candidate => {
  try { accessSync(candidate); return true; } catch { return false; }
});
const browser = await chromium.launch({
  headless: true,
  ...(executablePath ? { executablePath } : {})
});
try {
  mkdirSync(evidence, { recursive: true });
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  await context.addInitScript(({ token, id }) => {
    localStorage.setItem('pathome_auth_token', token);
    localStorage.setItem('pathome_role', 'EMPLOYEE');
    localStorage.setItem('pathome_user', JSON.stringify({
      id: Number(id), role: 'EMPLOYEE', fullName: 'Ravi Synthetic',
      email: 'a1a7-browser-ravi@example.test'
    }));
  }, { token: staffToken, id: staffId });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.message));

  await page.goto(`${origin}/property/${listingId}`, { waitUntil: 'domcontentloaded' });
  await page.getByText(/2 BHK Flat/i).first().waitFor();
  const publicText = await page.locator('body').innerText();
  check('genuine public property detail renders', /2 BHK/i.test(publicText));
  check('public detail hides lessor and Lead PII',
    !publicText.includes('+919812345678') && !publicText.includes('private.lessor.a1a7@example.test')
    && !publicText.includes('Rahul Browser'));
  const propertyApi = api.find(entry => entry.path === `/api/v1/properties/${listingId}`);
  check('public property came from the connected backend', propertyApi?.status === 200);

  for (const width of [320, 375, 390, 414, 430, 768, 1024, 1280, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    const overflow = await page.evaluate(() =>
      document.documentElement.scrollWidth - window.innerWidth);
    check(`public property has no overflow at ${width}px`, overflow <= 1, `${overflow}px`);
  }

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${origin}/verify-enquiry-phone#token=${phoneToken}`,
    { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: 'Confirm your enquiry number.' }).waitFor();
  check('enquiry token is removed before exchange', !new URL(page.url()).hash);
  check('enquiry exchange reached genuine backend',
    api.some(entry => entry.path === '/api/v1/lead-phone-verifications/exchange'
      && entry.status === 200));
  const otpText = await page.locator('body').innerText();
  check('OTP page does not render the secret token or code',
    !otpText.includes(phoneToken) && !/\b\d{6}\b/.test(otpText));
  const controls = await page.evaluate(() => ({
    fontSize: Number.parseFloat(getComputedStyle(
      document.querySelector('input[autocomplete="one-time-code"]')).fontSize),
    buttonHeight: document.querySelector('button[type="submit"]').getBoundingClientRect().height
  }));
  check('OTP input is at least 16px', controls.fontSize >= 16);
  check('OTP action is at least 44px', controls.buttonHeight >= 44);

  await page.goto(`${origin}/operations/leads/${leadId}`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: 'Rahul Browser' }).waitFor();
  await page.getByRole('heading', { name: 'Enquiry phone proof' }).waitFor();
  check('genuine OE Lead workspace renders current backend Lead',
    (await page.locator('body').innerText()).includes('Rahul Browser'));
  check('Lead workspace hides full contact in its normal metadata',
    !(await page.locator('body').innerText()).includes('+919700007777'));
  await page.screenshot({ path: join(evidence, 'a1-a7-lead-390.png'), fullPage: true });
  screenshots.push('a1-a7-lead-390.png');

  await page.goto(`${origin}/operations/leads/${leadId}/share?shortlist=${shortlistId}`,
    { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: 'Lessor availability' }).waitFor();
  await page.getByText(
    'The customer has a current, genuine viewing-availability question').click();
  await page.getByRole('button', { name: 'Check lessor availability' }).click();
  await page.getByText('Synthetic Lessor').waitFor();
  check('real availability create/reveal persisted and rendered',
    api.some(entry => entry.method === 'POST'
      && entry.path === `/api/v1/operations/leads/${leadId}/availability-enquiries`
      && entry.status === 201)
    && api.some(entry => entry.method === 'POST'
      && entry.path.endsWith('/lessor-contact') && entry.status === 200));
  const availabilityText = await page.locator('body').innerText();
  check('lessor outcome copy does not claim booking',
    /creates no Visit Request,\s*reservation/i.test(availabilityText)
    && /not a booking/i.test(availabilityText));
  for (const width of [320, 375, 390, 414, 430, 768, 1024, 1280, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    const overflow = await page.evaluate(() =>
      document.documentElement.scrollWidth - window.innerWidth);
    check(`availability workspace has no overflow at ${width}px`, overflow <= 1, `${overflow}px`);
  }

  const realStaffContext = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const realStaffPage = await realStaffContext.newPage();
  await realStaffPage.goto(origin, { waitUntil: 'domcontentloaded' });
  await realStaffPage.locator('button.lp-nav-signin').click();
  await realStaffPage.getByRole('dialog', { name: 'Sign in or create account' }).waitFor();
  await realStaffPage.getByLabel('Email Address').fill(staffEmail);
  await realStaffPage.getByLabel('Password').fill(staffPassword);
  const realStaffLoginPromise = realStaffPage.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/auth/login');
  await realStaffPage.getByRole('button', { name: 'Sign In', exact: true }).click();
  const realStaffLogin = await realStaffLoginPromise;
  check('real OE staff login reaches Spring authentication', realStaffLogin.status() === 200);
  await realStaffPage.goto(`${origin}/operations/leads/${leadId}`,
    { waitUntil: 'domcontentloaded' });
  await realStaffPage.getByRole('heading', { name: 'Rahul Browser' }).waitFor();
  check('real staff session receives current capability-authorized Lead access',
    api.some(entry => entry.path === `/api/v1/operations/leads/${leadId}`
      && entry.status === 200));
  await realStaffContext.close();

  const tenantContext = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const tenantPage = await tenantContext.newPage();
  const tenantErrors = [];
  const tenantConsole = [];
  const tenantNetwork = [];
  tenantPage.on('pageerror', error => tenantErrors.push(error.message));
  tenantPage.on('console', message => tenantConsole.push(
    `console:${message.type()}:${message.text()}`));
  tenantPage.on('request', request => {
    if (new URL(request.url()).pathname.startsWith('/api/v1/')) {
      tenantNetwork.push({ method: request.method(), path: new URL(request.url()).pathname });
    }
  });
  await tenantPage.goto(`${origin}/property/${listingId}#visit=${handoffToken}`,
    { waitUntil: 'domcontentloaded' });
  await tenantPage.getByText('This is an exact-property Visit invitation').waitFor();
  await tenantPage.getByText(/2 BHK Flat/i).first().waitFor();
  check('forwarded invitation is exchanged before sign-in',
    api.some(entry => entry.path === '/api/v1/lead-visit-handoffs/exchange'
      && entry.status === 200));
  check('invitation fragment is stripped before authentication',
    !new URL(tenantPage.url()).hash
    && new URL(tenantPage.url()).pathname === `/property/${listingId}`);
  check('forwarded invitation creates no Request before explicit acceptance',
    !api.some(entry => entry.method === 'POST'
      && (entry.path === '/api/v1/tenant/property-visit-commands'
        || entry.path.endsWith('/visit-requests'))));
  const sessionBeforeLogin = await tenantPage.evaluate(() => ({
    token: localStorage.getItem('pathome_auth_token'),
    user: localStorage.getItem('pathome_user')
  }));
  check('real Tenant context starts without an injected session',
    sessionBeforeLogin.token === null && sessionBeforeLogin.user === null);
  await tenantPage.locator('button:visible').filter({ hasText: 'Request a Visit' }).first().click();
  await tenantPage.getByRole('dialog', { name: 'Sign in or create account' }).waitFor();
  await tenantPage.getByLabel('Email Address').fill(tenantEmail);
  await tenantPage.getByLabel('Password').fill(tenantPassword);
  const loginResponsePromise = tenantPage.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/auth/login');
  await tenantPage.getByRole('button', { name: 'Sign In', exact: true }).click();
  let loginResponse;
  try {
    loginResponse = await loginResponsePromise;
  } catch (error) {
    writeFileSync(join(evidence, 'tenant-login-failure.html'), await tenantPage.content());
    writeFileSync(join(evidence, 'tenant-login-network.json'),
      JSON.stringify({ network: tenantNetwork, pageErrors: tenantErrors,
        consoleMessages: tenantConsole }, null, 2));
    await tenantPage.screenshot({ path: join(evidence, 'tenant-login-failure.png'), fullPage: true });
    throw error;
  }
  check('real Tenant login form reaches Spring authentication', loginResponse.status() === 200,
    String(loginResponse.status()));
  await tenantPage.getByRole('dialog', { name: 'Sign in or create account' })
    .waitFor({ state: 'detached' });
  const authenticatedSession = await tenantPage.evaluate(() => ({
    token: localStorage.getItem('pathome_auth_token'),
    user: JSON.parse(localStorage.getItem('pathome_user') || 'null')
  }));
  check('real login response establishes the server-derived Tenant identity',
    typeof authenticatedSession.token === 'string' && authenticatedSession.token.length > 40
    && authenticatedSession.user?.id === Number(tenantId)
    && authenticatedSession.user?.role === 'TENANT');

  await tenantPage.getByRole('heading', { name: 'Request a visit.' }).waitFor();
  check('ordinary login returns to the exact intended property and reopens Visit intent',
    new URL(tenantPage.url()).pathname === `/property/${listingId}`);
  const minimumDate = await tenantPage.locator('input[type="date"]').getAttribute('min');
  check('Visit Request UI exposes a future-date constraint', Boolean(minimumDate));
  await tenantPage.locator('input[type="date"]').fill(minimumDate);
  await tenantPage.locator('input[name="preferredVisitWindow"]').first().check();
  const requestResponsePromise = tenantPage.waitForResponse(response => {
    const path = new URL(response.url()).pathname;
    return path === '/api/v1/tenant/property-visit-commands'
      || path === `/api/v1/properties/${listingId}/visit-requests`;
  });
  await tenantPage.getByRole('button', { name: 'Request visit', exact: true }).click();
  const requestResponse = await requestResponsePromise;
  const requestPayload = await requestResponse.json();
  check('authenticated Tenant request is recorded through real React/Spring/PostgreSQL',
    requestResponse.status() === 200 || requestResponse.status() === 201);
  check('request result reflects a committed own-account Request',
    typeof requestPayload.requestId === 'number'
    && (requestPayload.created === true || requestResponse.status() === 201)
    && ['RECEIVED', 'COORDINATING', 'SCHEDULED'].includes(requestPayload.status));
  await tenantPage.getByRole('heading', {
    name: /Visit Request (?:recorded|received)/i
  }).waitFor();
  const sessionAfterRequest = await tenantPage.evaluate(() =>
    localStorage.getItem('pathome_auth_token'));
  check('request flow retains the same real authenticated Tenant session',
    sessionAfterRequest === authenticatedSession.token);
  check('real Tenant journey has no uncaught page error', tenantErrors.length === 0,
    tenantErrors.join(' | '));
  await tenantContext.close();

  const amitContext = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const amitPage = await amitContext.newPage();
  const amitErrors = [];
  amitPage.on('pageerror', error => amitErrors.push(error.message));
  await amitPage.goto(`${origin}/property/${listingId}#visit=${handoffToken}`,
    { waitUntil: 'domcontentloaded' });
  await amitPage.getByText('This is an exact-property Visit invitation').waitFor();
  await amitPage.getByText(/2 BHK Flat/i).first().waitFor();
  await amitPage.locator('button:visible').filter({ hasText: 'Request a Visit' }).first().click();
  await amitPage.getByRole('dialog', { name: 'Sign in or create account' }).waitFor();
  await amitPage.getByLabel('Email Address').fill(amitEmail);
  await amitPage.getByLabel('Password').fill(amitPassword);
  const amitLoginPromise = amitPage.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/auth/login');
  await amitPage.getByRole('button', { name: 'Sign In', exact: true }).click();
  const amitLogin = await amitLoginPromise;
  check('second forwarded-recipient Tenant signs in through real authentication',
    amitLogin.status() === 200);
  const amitSession = await amitPage.evaluate(() => ({
    token: localStorage.getItem('pathome_auth_token'),
    user: JSON.parse(localStorage.getItem('pathome_user') || 'null')
  }));
  check('second Tenant keeps its own server-derived identity',
    amitSession.user?.id === Number(amitId) && amitSession.user?.role === 'TENANT'
    && amitSession.token !== authenticatedSession.token);
  await amitPage.getByRole('heading', { name: 'Request a visit.' }).waitFor();
  check('second forwarded recipient returns to the same exact property under its own account',
    new URL(amitPage.url()).pathname === `/property/${listingId}`);
  const amitMinimumDate = await amitPage.locator('input[type="date"]').getAttribute('min');
  await amitPage.locator('input[type="date"]').fill(amitMinimumDate);
  await amitPage.locator('input[name="preferredVisitWindow"]').first().check();
  const amitRequestPromise = amitPage.waitForResponse(response => {
    const path = new URL(response.url()).pathname;
    return path === '/api/v1/tenant/property-visit-commands'
      || path === `/api/v1/properties/${listingId}/visit-requests`;
  });
  await amitPage.getByRole('button', { name: 'Request visit', exact: true }).click();
  const amitRequest = await amitRequestPromise;
  const amitPayload = await amitRequest.json();
  check('forwarded-recipient Tenant creates an independent own-account Request',
    (amitRequest.status() === 200 || amitRequest.status() === 201)
    && typeof amitPayload.requestId === 'number'
    && amitPayload.requestId !== requestPayload.requestId);
  check('second real Tenant journey has no uncaught page error', amitErrors.length === 0,
    amitErrors.join(' | '));
  await amitContext.close();

  const plainContext = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const plainPage = await plainContext.newPage();
  const plainErrors = [];
  plainPage.on('pageerror', error => plainErrors.push(error.message));
  const plainApiStart = api.length;
  await plainPage.emulateMedia({ reducedMotion: 'reduce' });
  await plainPage.goto(`${origin}/property/${plainListingId}`, { waitUntil: 'domcontentloaded' });
  await plainPage.getByText(/2 BHK Flat/i).first().waitFor();
  check('plain shared property link remains public without an invitation or Lead proof',
    !new URL(plainPage.url()).hash
      && new URL(plainPage.url()).pathname === `/property/${plainListingId}`);
  check('plain shared property creates no Request before explicit customer action',
    !api.slice(plainApiStart).some(entry => entry.method === 'POST'
      && (entry.path === '/api/v1/tenant/property-visit-commands'
        || entry.path.endsWith('/visit-requests'))));
  await plainPage.locator('button:visible').filter({ hasText: 'Request a Visit' }).first().click();
  await plainPage.getByRole('dialog', { name: 'Sign in or create account' }).waitFor();
  await plainPage.getByLabel('Email Address').fill(plainEmail);
  await plainPage.getByLabel('Password').fill(plainPassword);
  const plainLoginPromise = plainPage.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/auth/login');
  await plainPage.getByRole('button', { name: 'Sign In', exact: true }).click();
  const plainLogin = await plainLoginPromise;
  check('plain-link customer reaches real Spring authentication', plainLogin.status() === 200);
  const plainSession = await plainPage.evaluate(() => ({
    token: localStorage.getItem('pathome_auth_token'),
    user: JSON.parse(localStorage.getItem('pathome_user') || 'null')
  }));
  check('plain-link customer receives its own server-derived Tenant identity',
    plainSession.user?.id === Number(plainId) && plainSession.user?.role === 'TENANT');
  await plainPage.getByRole('heading', { name: 'Request a visit.' }).waitFor();
  check('plain-link login returns to the exact requested property',
    new URL(plainPage.url()).pathname === `/property/${plainListingId}`);
  check('ordinary property flow does not offer an unrelated invitation association',
    await plainPage.getByText(/property enquiry from my invitation/i).count() === 0);
  for (const width of [320, 375, 390, 414, 430, 768, 1024, 1280, 1440]) {
    await plainPage.setViewportSize({ width, height: 900 });
    const overflow = await plainPage.evaluate(() =>
      document.documentElement.scrollWidth - window.innerWidth);
    check(`Request dialog has no overflow at ${width}px`, overflow <= 1, `${overflow}px`);
  }
  await plainPage.setViewportSize({ width: 390, height: 844 });
  const dialogControls = await plainPage.evaluate(() => {
    const dialog = document.querySelector('[role="dialog"][aria-labelledby="visit-request-title"]');
    const submit = dialog?.querySelector('button[type="submit"]');
    const close = dialog?.querySelector('button[aria-label="Close visit request"]');
    return {
      modal: Boolean(dialog),
      closeHeight: close?.getBoundingClientRect().height ?? 0,
      submitHeight: submit?.getBoundingClientRect().height ?? 0
    };
  });
  check('ordinary Visit Request opens an accessible modal with 44px actions',
    dialogControls.modal && dialogControls.closeHeight >= 44
      && dialogControls.submitHeight >= 44, JSON.stringify(dialogControls));
  const focusTrap = async () => plainPage.evaluate(() => {
    const dialog = document.querySelector('[role="dialog"][aria-labelledby="visit-request-title"]');
    if (!dialog) return null;
    const focusable = Array.from(dialog.querySelectorAll(
      'button:not([disabled]), input:not([disabled]), textarea:not([disabled]), summary, [href], [tabindex]:not([tabindex="-1"])'
    )).filter(element => element.getClientRects().length > 0
      && (!element.closest('details:not([open])') || element.matches('summary')));
    return {
      contained: dialog.contains(document.activeElement),
      first: document.activeElement === focusable[0],
      last: document.activeElement === focusable[focusable.length - 1],
      active: {
        tag: document.activeElement?.tagName,
        text: document.activeElement?.textContent?.trim().slice(0, 60),
        ariaLabel: document.activeElement?.getAttribute('aria-label')
      },
      focusables: focusable.map(element => ({
        tag: element.tagName,
        type: element.getAttribute('type'),
        text: element.textContent?.trim().slice(0, 60),
        ariaLabel: element.getAttribute('aria-label')
      }))
    };
  });
  await plainPage.getByRole('button', { name: 'Close visit request' }).focus();
  await plainPage.evaluate(() => document.activeElement?.dispatchEvent(
    new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true })
  ));
  const backwardWrap = await focusTrap();
  check('Visit Request modal traps backward keyboard focus',
    backwardWrap?.contained === true && backwardWrap?.last === true,
    JSON.stringify(backwardWrap));
  await plainPage.evaluate(() => document.activeElement?.dispatchEvent(
    new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true })
  ));
  const forwardWrap = await focusTrap();
  check('Visit Request modal traps forward keyboard focus',
    forwardWrap?.contained === true && forwardWrap?.first === true,
    JSON.stringify(forwardWrap));
  const reducedMotion = await plainPage.evaluate(() => {
    const dialog = document.querySelector('[role="dialog"][aria-labelledby="visit-request-title"]');
    const style = dialog ? getComputedStyle(dialog) : null;
    const durations = [style?.transitionDuration ?? '', style?.animationDuration ?? '']
      .flatMap(value => value.split(','))
      .filter(Boolean)
      .map(value => Number.parseFloat(value));
    return {
      hasDialog: Boolean(dialog),
      mediaMatches: matchMedia('(prefers-reduced-motion: reduce)').matches,
      durations,
      effectivelyImmediate: durations.every(value => value === 0)
    };
  });
  check('Visit Request modal honors reduced-motion preference',
    reducedMotion.hasDialog && reducedMotion.mediaMatches && reducedMotion.effectivelyImmediate,
    JSON.stringify(reducedMotion));
  const plainMinimumDate = await plainPage.locator('input[type="date"]').getAttribute('min');
  await plainPage.locator('input[type="date"]').fill(plainMinimumDate);
  await plainPage.locator('input[name="preferredVisitWindow"]').first().check();
  const plainRequestPromise = plainPage.waitForResponse(response => {
    const path = new URL(response.url()).pathname;
    return path === '/api/v1/tenant/property-visit-commands'
      || path === `/api/v1/properties/${plainListingId}/visit-requests`;
  });
  await plainPage.getByRole('button', { name: 'Request visit', exact: true }).click();
  const plainRequest = await plainRequestPromise;
  const plainPayload = await plainRequest.json();
  check('ordinary signed-in Request uses the canonical version-guarded command',
    new URL(plainRequest.url()).pathname === '/api/v1/tenant/property-visit-commands',
    new URL(plainRequest.url()).pathname);
  check('ordinary canonical Request commits for the exact property and account',
    (plainRequest.status() === 200 || plainRequest.status() === 201)
      && typeof plainPayload.requestId === 'number'
      && plainPayload.created === true
      && plainPayload.propertyId === Number(plainListingId)
      && plainPayload.status === 'RECEIVED');
  const requestPostsBeforeRefresh = api.filter(entry => entry.method === 'POST'
    && entry.path === '/api/v1/tenant/property-visit-commands').length;
  await plainPage.reload({ waitUntil: 'domcontentloaded' });
  await plainPage.getByText(/2 BHK Flat/i).first().waitFor();
  await plainPage.getByRole('button', {
    name: /Visit Requested|Visit Being Coordinated|View Visit|View My Visits/i
  }).first().waitFor();
  const refreshedSessionToken = await plainPage.evaluate(() =>
    localStorage.getItem('pathome_auth_token'));
  check('refresh retains the same authenticated Tenant and current Request state',
    refreshedSessionToken === plainSession.token
      && new URL(plainPage.url()).pathname === `/property/${plainListingId}`);
  check('refresh does not replay the Visit Request command',
    api.filter(entry => entry.method === 'POST'
      && entry.path === '/api/v1/tenant/property-visit-commands').length
      === requestPostsBeforeRefresh);

  await plainPage.evaluate(token =>
    localStorage.setItem('pathome_auth_token', token), expiredToken);
  const expiryApiStart = api.length;
  const expiredSessionPromise = plainPage.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/tenant/visit-requests'
      && response.status() === 401);
  await plainPage.reload({ waitUntil: 'domcontentloaded' });
  const expiredSessionResponse = await expiredSessionPromise;
  await plainPage.getByRole('dialog', { name: 'Sign in or create account' }).waitFor();
  const expiredSession = await plainPage.evaluate(() => ({
    token: localStorage.getItem('pathome_auth_token'),
    user: localStorage.getItem('pathome_user')
  }));
  check('expired Tenant authentication is rejected by the genuine backend',
    expiredSessionResponse.status() === 401);
  check('expired Tenant session is cleared and prompts sign-in without stale user state',
    expiredSession.token === null && expiredSession.user === null);
  check('expired Tenant session cannot replay or mutate the Visit Request',
    !api.slice(expiryApiStart).some(entry => entry.method === 'POST'
      && (entry.path === '/api/v1/tenant/property-visit-commands'
        || entry.path.endsWith('/visit-requests'))));
  check('plain-link Tenant journey has no uncaught page error', plainErrors.length === 0,
    plainErrors.join(' | '));
  await plainContext.close();

  const adminContext = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const adminPage = await adminContext.newPage();
  const adminErrors = [];
  adminPage.on('pageerror', error => adminErrors.push(error.message));
  await adminPage.goto(origin, { waitUntil: 'domcontentloaded' });
  await adminPage.locator('button.lp-nav-signin').click();
  await adminPage.getByRole('dialog', { name: 'Sign in or create account' }).waitFor();
  await adminPage.getByLabel('Email Address').fill(adminEmail);
  await adminPage.getByLabel('Password').fill(adminPassword);
  const adminLoginPromise = adminPage.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/auth/login');
  await adminPage.getByRole('button', { name: 'Sign In', exact: true }).click();
  const adminLogin = await adminLoginPromise;
  check('real Admin login reaches Spring authentication', adminLogin.status() === 200);
  const adminSession = await adminPage.evaluate(() => ({
    token: localStorage.getItem('pathome_auth_token'),
    user: JSON.parse(localStorage.getItem('pathome_user') || 'null')
  }));
  check('Admin browser receives its own server-derived identity',
    adminSession.user?.id === Number(adminId) && adminSession.user?.role === 'TENANT');
  await adminPage.goto(`${origin}/operations/routing`, { waitUntil: 'domcontentloaded' });
  await adminPage.getByRole('heading', { name: 'City routing and coverage' }).waitFor();
  const routingRow = adminPage.locator('button.routing-row')
    .filter({ hasText: plainListingTitle }).first();
  await routingRow.waitFor();
  await routingRow.click();
  await adminPage.getByRole('heading', { name: 'Routing decision' }).waitFor();
  await adminPage.locator('#routing-team').selectOption({ label: teamName });
  await adminPage.getByRole('button', { name: 'Review Team selection' }).click();
  await adminPage.getByText('Confirm Team routing', { exact: true }).first().waitFor();
  const routeResponsePromise = adminPage.waitForResponse(response =>
    response.request().method() === 'POST'
    && /\/api\/v1\/operations\/intake-routing\/visit-requests\/\d+\/route$/
      .test(new URL(response.url()).pathname));
  await adminPage.getByRole('button', { name: 'Confirm Team routing' }).click();
  const routeResponse = await routeResponsePromise;
  check('Admin UI routes the exact Request to the selected Team',
    routeResponse.status() === 204, String(routeResponse.status()));
  check('Admin browser receives the server-confirmed Team routing result',
    /Team can now claim the work/i.test(await adminPage.locator('body').innerText()));
  check('Admin routing journey has no uncaught page error', adminErrors.length === 0,
    adminErrors.join(' | '));
  await adminPage.screenshot({ path: join(evidence, 'a1-a7-routing-admin-1280.png'), fullPage: true });
  screenshots.push('a1-a7-routing-admin-1280.png');
  await adminContext.close();

  const oeContext = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const oePage = await oeContext.newPage();
  const oeErrors = [];
  oePage.on('pageerror', error => oeErrors.push(error.message));
  await oePage.goto(origin, { waitUntil: 'domcontentloaded' });
  await oePage.locator('button.lp-nav-signin').click();
  await oePage.getByRole('dialog', { name: 'Sign in or create account' }).waitFor();
  await oePage.getByLabel('Email Address').fill(staffEmail);
  await oePage.getByLabel('Password').fill(staffPassword);
  const oeLoginPromise = oePage.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/auth/login');
  await oePage.getByRole('button', { name: 'Sign In', exact: true }).click();
  const oeLogin = await oeLoginPromise;
  check('real OE login reaches Spring authentication', oeLogin.status() === 200);
  await oePage.goto(
    `${origin}/operations/queues/claimable?type=REQUEST&page=1&ref=${plainPayload.requestId}`,
    { waitUntil: 'domcontentloaded' }
  );
  await oePage.getByRole('heading', { name: 'Available work' }).waitFor();
  const claimRow = oePage.locator('li.oe-row--claim')
    .filter({ hasText: `#${plainPayload.requestId}` }).first();
  await claimRow.waitFor();
  const claimApiStart = api.length;
  await claimRow.getByRole('button', { name: 'Claim', exact: true }).click();
  await oePage.waitForURL(
    url => url.pathname === `/operations/requests/${plainPayload.requestId}`,
    { timeout: 20_000 }
  );
  const claimResponse = api.slice(claimApiStart).find(entry =>
    entry.method === 'POST'
    && entry.path === `/api/v1/operations/visit-requests/${plainPayload.requestId}/claim`);
  check('authorized OE UI claims the routed canonical Request',
    claimResponse?.status === 204, JSON.stringify(claimResponse));
  check('OE browser opens the claimed Request from authoritative ownership',
    new URL(oePage.url()).pathname === `/operations/requests/${plainPayload.requestId}`,
    oePage.url());
  check('OE routing and claim journey has no uncaught page error', oeErrors.length === 0,
    oeErrors.join(' | '));
  await oePage.screenshot({ path: join(evidence, 'a1-a7-routing-oe-1280.png'), fullPage: true });
  screenshots.push('a1-a7-routing-oe-1280.png');
  await oeContext.close();

  check('connected browser journey has no uncaught page error', pageErrors.length === 0,
    pageErrors.join(' | '));
  writeFileSync(join(evidence, 'connected-browser-receipt.json'), JSON.stringify({
    kind: 'connected-a1-a7-react-spring-postgresql',
    backendPort: Number(backendPort),
    checks: checks.length,
    passed: checks.filter(entry => entry.pass).length,
    tenantAuthentication: {
      flow: 'real-login-form',
      injectedToken: false,
      loginStatus: loginResponse.status(),
      requestStatus: requestResponse.status(),
      secondTenantLoginStatus: amitLogin.status(),
      secondRequestStatus: amitRequest.status(),
      plainLinkLoginStatus: plainLogin.status(),
      plainLinkRequestStatus: plainRequest.status(),
      adminLoginStatus: adminLogin.status(),
      adminRouteStatus: routeResponse.status(),
      oeLoginStatus: oeLogin.status(),
      oeClaimStatus: claimResponse?.status ?? null,
      staffLoginStatus: realStaffLogin.status()
    },
    api,
    checks,
    screenshots
  }, null, 2));
  console.log(JSON.stringify({
    checks: checks.length,
    passed: checks.filter(entry => entry.pass).length,
    apiCalls: api.length
  }, null, 2));
} finally {
  await browser.close();
  server.close();
}
