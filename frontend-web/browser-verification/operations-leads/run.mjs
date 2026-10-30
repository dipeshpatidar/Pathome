/**
 * D-B G10 genuine browser integration.
 *
 * Approved genuine browser tests launch this script only after seeding real Staff/City/Team/public-
 * listing fixtures in the designated PostgreSQL target. It serves the production Vite build
 * in-process, proxies every /api request verbatim to that random-port Spring Boot instance, and
 * drives the app with real authentication tokens and persisted APIs.
 *
 * Controlled mode may delay pass-through API requests for deterministic ordering checks. Strict
 * genuine mode registers no API interception. Both modes fulfill only the final wa.me navigation
 * locally, so verification never contacts WhatsApp.
 */
import { accessSync } from 'node:fs';
import { readFile, writeFile } from 'node:fs/promises';
import { createServer, request as proxyRequest } from 'node:http';
import { extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';
import { claimEvidenceDirectory } from './evidence-directory.mjs';
import {
  allowsApiInterception,
  browserTransportMode,
  installTransportRoutes
} from './browser-transport-mode.mjs';

const { chromium } = playwright;
const DIST = fileURLToPath(new URL('../../dist/', import.meta.url));
const BACKEND_PORT = Number(process.env.PATHOME_BROWSER_BACKEND_PORT);
const OWNER = {
  token: process.env.PATHOME_BROWSER_OWNER_TOKEN,
  id: Number(process.env.PATHOME_BROWSER_OWNER_ID)
};
const PEER = {
  token: process.env.PATHOME_BROWSER_PEER_TOKEN,
  id: Number(process.env.PATHOME_BROWSER_PEER_ID)
};
const SUPERVISOR = {
  token: process.env.PATHOME_BROWSER_SUPERVISOR_TOKEN,
  id: Number(process.env.PATHOME_BROWSER_SUPERVISOR_ID)
};
const ADMIN = {
  token: process.env.PATHOME_BROWSER_ADMIN_TOKEN,
  id: Number(process.env.PATHOME_BROWSER_ADMIN_ID)
};
const INTAKE = {
  token: process.env.PATHOME_BROWSER_INTAKE_TOKEN,
  id: Number(process.env.PATHOME_BROWSER_INTAKE_ID)
};
const INTAKE_LEAD_ID = Number(process.env.PATHOME_BROWSER_INTAKE_LEAD_ID);
const INTAKE_ENABLED = Boolean(INTAKE.token) && Number.isSafeInteger(INTAKE.id)
  && Number.isSafeInteger(INTAKE_LEAD_ID) && INTAKE_LEAD_ID > 0;
const OWNER_GRANT_ID = Number(process.env.PATHOME_BROWSER_OWNER_GRANT_ID);
const GRANT_REVOKE_URL = process.env.PATHOME_BROWSER_GRANT_REVOKE_URL;
const GRANT_REVOKE_TOKEN = process.env.PATHOME_BROWSER_GRANT_REVOKE_TOKEN;
const EVIDENCE = process.env.PATHOME_BROWSER_EVIDENCE_DIR;
const EXPECTED_PROPERTY_ID = Number(process.env.PATHOME_BROWSER_EXPECTED_PROPERTY_ID);
const TRANSPORT_MODE = browserTransportMode(process.env);
const API_INTERCEPTION_ENABLED = allowsApiInterception(TRANSPORT_MODE);

if (!Number.isSafeInteger(BACKEND_PORT) || BACKEND_PORT <= 0
  || !OWNER.token || !PEER.token || !SUPERVISOR.token || !ADMIN.token
  || !Number.isSafeInteger(OWNER.id) || !Number.isSafeInteger(PEER.id)
  || !Number.isSafeInteger(SUPERVISOR.id) || !Number.isSafeInteger(ADMIN.id)
  || !Number.isSafeInteger(OWNER_GRANT_ID) || OWNER_GRANT_ID <= 0
  || !GRANT_REVOKE_URL || !GRANT_REVOKE_TOKEN
  || !EVIDENCE || !Number.isSafeInteger(EXPECTED_PROPERTY_ID)) {
  throw new Error('genuine browser fixture environment is incomplete');
}
await claimEvidenceDirectory(EVIDENCE);

const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2'
};

let heldDetailResponses = [];
let holdNextDetail = false;
let holdNextDiscoveryPage = false;
let heldDiscoveryPage = null;
let holdNextDuplicateLookup = false;
let heldDuplicateLookup = null;
let holdNextGrantLossCommand = false;
let heldGrantLossCommand = null;
const networkTrace = [];
const waNavigations = [];

const server = createServer(async (incoming, outgoing) => {
  const url = new URL(incoming.url, 'http://g10.local');
  if (url.pathname.startsWith('/api/')) {
    networkTrace.push({ method: incoming.method, path: url.pathname });
    const proxy = proxyRequest({
      hostname: '127.0.0.1',
      port: BACKEND_PORT,
      path: incoming.url,
      method: incoming.method,
      headers: incoming.headers
    }, (upstream) => {
      const send = () => {
        outgoing.writeHead(upstream.statusCode || 502, upstream.headers);
        upstream.pipe(outgoing);
      };
      if (API_INTERCEPTION_ENABLED && holdNextDetail
        && /^\/api\/v1\/operations\/leads\/\d+$/.test(url.pathname)) {
        holdNextDetail = false;
        heldDetailResponses.push(send);
      } else {
        send();
      }
    });
    proxy.on('error', (error) => {
      outgoing.writeHead(502, { 'Content-Type': 'application/json' });
      outgoing.end(JSON.stringify({ message: `proxy failed: ${error.message}` }));
    });
    incoming.pipe(proxy);
    return;
  }

  const file = url.pathname === '/' ? '/index.html' : url.pathname;
  try {
    const contents = await readFile(join(DIST, file));
    outgoing.writeHead(200, {
      'Content-Type': CONTENT_TYPES[extname(file)] || 'application/octet-stream'
    });
    outgoing.end(contents);
  } catch {
    const shell = await readFile(join(DIST, 'index.html'));
    outgoing.writeHead(200, { 'Content-Type': CONTENT_TYPES['.html'] });
    outgoing.end(shell);
  }
});

await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const BASE = `http://127.0.0.1:${server.address().port}`;
const executableCandidates = [
  process.env.PATHOME_BROWSER_CHROMIUM_EXECUTABLE,
  '/Users/dipeshpatidar/Library/Caches/ms-playwright/chromium-1200/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
].filter(Boolean);
const executablePath = executableCandidates.find((candidate) => {
  try {
    accessSync(candidate);
    return true;
  } catch {
    return false;
  }
});
const browser = await chromium.launch({
  headless: true,
  ...(executablePath ? { executablePath } : {})
});
const context = await browser.newContext({
  viewport: { width: 1280, height: 900 },
  permissions: ['clipboard-read', 'clipboard-write']
});

const checks = [];
const browserErrors = [];
const check = (name, passed, detail = '') => {
  checks.push({ name, passed: Boolean(passed), detail });
  process.stdout.write(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}\n`);
  if (!passed) throw new Error(`${name}${detail ? `: ${detail}` : ''}`);
};

const identityStorage = (identity, label) => [
  ['pathome_auth_token', identity.token],
  ['pathome_role', 'TENANT'],
  ['pathome_user', JSON.stringify({
    id: identity.id,
    role: 'TENANT',
    fullName: `G10 ${label}`,
    email: `g10-${label.toLowerCase()}-${identity.id}@pathome.test`
  })]
];

const setIdentity = async (page, identity, label, path) => {
  await page.evaluate(({ entries }) => {
    entries.forEach(([key, value]) => window.localStorage.setItem(key, value));
    window.dispatchEvent(new Event('pathome_auth_changed'));
  }, { entries: identityStorage(identity, label) });
  await page.goto(`${BASE}${path}`);
};

const page = await context.newPage();
const unexpectedServerErrors = [];
const failedApiResponses = [];
page.on('response', async (response) => {
  if (!response.url().includes('/api/') || response.status() < 400) return;
  const path = new URL(response.url()).pathname;
  let safeMessage = null;
  try {
    const payload = await response.json();
    safeMessage = typeof payload?.message === 'string' ? payload.message.slice(0, 240) : null;
  } catch {
    // A proxy response without JSON has no application message to retain.
  }
  failedApiResponses.push({ status: response.status(), path, message: safeMessage });
  if (response.status() >= 500) {
    unexpectedServerErrors.push(`${response.status()} ${path}${safeMessage ? ` — ${safeMessage}` : ''}`);
  }
});
await page.addInitScript(() => {
  Object.defineProperty(window, '__pathomeFailClipboard', {
    configurable: true,
    writable: true,
    value: false
  });
  const clipboard = navigator.clipboard;
  if (!clipboard) return;
  const writeText = clipboard.writeText.bind(clipboard);
  try {
    Object.defineProperty(clipboard, 'writeText', {
      configurable: true,
      value: async (text) => {
        if (window.__pathomeFailClipboard) throw new Error('controlled clipboard denial');
        return writeText(text);
      }
    });
  } catch {
    // Permission revocation below remains the browser-level fallback.
  }
});
page.on('console', (message) => {
  if (message.type() === 'error'
    && !message.text().startsWith('Failed to load resource:')) {
    browserErrors.push(message.text());
  }
});
page.on('pageerror', (error) => browserErrors.push(error.message));
await page.addInitScript((entries) => {
  if (!window.localStorage.getItem('pathome_auth_token')) {
    entries.forEach(([key, value]) => window.localStorage.setItem(key, value));
  }
}, identityStorage(OWNER, 'Owner'));
const apiRoutes = [];
if (API_INTERCEPTION_ENABLED) {
  apiRoutes.push(['**/api/v1/properties?*', async (route) => {
    const url = new URL(route.request().url());
    if (holdNextDiscoveryPage && url.searchParams.get('page') === '1') {
      holdNextDiscoveryPage = false;
      const gate = new Promise((resolve) => {
        heldDiscoveryPage = { route, release: resolve };
      });
      await gate;
    }
    await route.continue();
  }]);
  apiRoutes.push(['**/api/v1/operations/leads/duplicate-candidates', async (route) => {
    if (!holdNextDuplicateLookup) {
      await route.continue();
      return;
    }
    holdNextDuplicateLookup = false;
    const gate = new Promise((resolve) => {
      heldDuplicateLookup = { route, release: resolve };
    });
    await gate;
    await route.continue();
  }]);
  apiRoutes.push(['**/api/v1/operations/leads/*', async (route) => {
    const url = new URL(route.request().url());
    if (!holdNextGrantLossCommand || route.request().method() !== 'PATCH'
      || !/^\/api\/v1\/operations\/leads\/\d+$/.test(url.pathname)) {
      await route.fallback();
      return;
    }
    holdNextGrantLossCommand = false;
    const gate = new Promise((resolve) => {
      heldGrantLossCommand = { route, release: resolve };
    });
    await gate;
    await route.continue();
  }]);
}
const installedApiRoutes = await installTransportRoutes({
  mode: TRANSPORT_MODE,
  page,
  context,
  apiRoutes,
  whatsappHandler: (route) => {
    waNavigations.push(route.request().url());
    return route.fulfill({
      status: 200,
      contentType: 'text/html',
      body: '<!doctype html><title>Controlled WhatsApp destination</title><p>No WhatsApp network call was made.</p>'
    });
  }
});

let leadId = null;
let leadPath = null;
let shareUrl = null;
try {
  await page.goto(`${BASE}/operations/leads`);
  await page.getByRole('heading', { name: 'Leads', exact: true }).waitFor();
  check('real Owner session reaches the scoped Lead queue', await page.getByRole('button', { name: 'New enquiry' }).isVisible());
  check('queue is backed by the server list contract',
    networkTrace.some((entry) => entry.path === '/api/v1/operations/leads'));

  await page.getByRole('button', { name: 'New enquiry' }).click();
  await page.getByRole('heading', { name: 'Capture an enquiry' }).waitFor();
  await page.getByLabel(/Phone number/).fill('+91 98811 22447');
  await page.getByLabel('Customer name').fill('G10 Browser Customer');
  await page.getByLabel(/Source/).fill('Phone call');
  await page.getByLabel('What the customer said').fill('Needs two bedrooms near the office.');
  await page.getByLabel('Intent').selectOption('RENT');
  await page.getByLabel('Property type').fill('Flat');
  await page.getByLabel('BHK').fill('2 BHK');
  await page.getByLabel('Budget up to (INR)').fill('25000');
  await page.getByRole('button', { name: 'Review and save enquiry' }).click();
  await page.waitForURL((url) => /^\/operations\/leads\/\d+$/.test(url.pathname));
  leadId = Number(new URL(page.url()).pathname.split('/').pop());
  leadPath = `/operations/leads/${leadId}`;
  check('real capture persisted and opened the owned Lead', Number.isSafeInteger(leadId) && leadId > 0);
  await page.getByRole('heading', { name: 'G10 Browser Customer' }).waitFor();
  check('owner can see owner-only contact and sharing controls',
    await page.getByRole('button', { name: 'Show phone number' }).isVisible()
    && await page.getByRole('button', { name: 'Share properties' }).isVisible());

  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await page.getByLabel('Tenant profile').fill('Working professionals');
  await page.getByRole('button', { name: /Save version/ }).click();
  await page.getByText('Requirements saved', { exact: true }).waitFor();
  check('versioned requirement save persisted', await page.getByText('Working professionals', { exact: true }).isVisible());

  await page.getByRole('button', { name: 'Back to Leads' }).click();
  await page.getByRole('heading', { name: 'Leads', exact: true }).waitFor();
  const genuineSearch = page.getByRole('searchbox', { name: 'Search Leads' });
  const genuineFollowUp = page.getByRole('combobox', { name: /Follow-up/ });
  await genuineSearch.fill('G10 Browser Customer');
  await page.locator('.ld-count').filter({ hasText: '1 Lead in the current server result' }).waitFor();
  check('real server search finds the captured customer by name',
    await page.locator('.ld-row').count() === 1);
  await genuineSearch.fill(`Lead #${leadId}`);
  await page.locator('.ld-count').filter({ hasText: '1 Lead in the current server result' }).waitFor();
  check('real server search supports the approved Lead reference form',
    await page.locator('.ld-row').count() === 1);
  await genuineSearch.fill('2447');
  await page.locator('.ld-count').filter({ hasText: '1 Lead in the current server result' }).waitFor();
  check('real server search supports exact last-four contact matching',
    await page.locator('.ld-row').count() === 1);
  await genuineFollowUp.selectOption('NONE');
  await page.locator('.ld-count').filter({ hasText: '1 Lead in the current server result' }).waitFor();
  await genuineFollowUp.selectOption('OVERDUE');
  await page.getByText('No Leads match this scope', { exact: true }).waitFor();
  check('real follow-up filters distinguish no-follow-up from overdue Leads',
    await page.locator('.ld-row').count() === 0);
  await genuineSearch.fill(`Lead #${leadId}`);
  await genuineFollowUp.selectOption('ANY');
  await page.locator('.ld-count').filter({ hasText: '1 Lead in the current server result' }).waitFor();
  await page.getByText('G10 Browser Customer', { exact: true }).click();
  await page.getByRole('heading', { name: 'G10 Browser Customer' }).waitFor();

  await page.screenshot({
    path: join(EVIDENCE, 'lead-workspace-1440.png'),
    fullPage: true
  });

  await page.getByRole('button', { name: 'Share properties' }).click();
  await page.waitForURL((url) => /^\/operations\/leads\/\d+\/share$/.test(url.pathname));
  const expectedPropertyCard = page.locator('.ld-property-card').filter({
    has: page.locator(`a[href="/property/${EXPECTED_PROPERTY_ID}"]`)
  });
  await expectedPropertyCard.waitFor();
  if (API_INTERCEPTION_ENABLED) {
    holdNextDiscoveryPage = true;
    await page.getByRole('button', { name: 'Load more public homes' }).click();
    for (let attempt = 0; attempt < 50 && heldDiscoveryPage === null; attempt += 1) {
      await page.waitForTimeout(50);
    }
    check('controlled Smart Search held one old pagination response', heldDiscoveryPage !== null);
    await page.getByLabel('Locality or property').fill('controlled no-match query');
    await page.getByText('No public rentals match', { exact: true }).waitFor();
    heldDiscoveryPage?.release();
    await page.waitForTimeout(300);
    heldDiscoveryPage = null;
    check('held old pagination cannot append results to the new Smart Search',
      await page.locator('.ld-property-card').count() === 0);
  } else {
    const nextPage = page.waitForResponse((response) => {
      const url = new URL(response.url());
      return url.pathname === '/api/v1/properties'
        && url.searchParams.get('page') === '1'
        && response.request().method() === 'GET';
    });
    await page.getByRole('button', { name: 'Load more public homes' }).click();
    check('strict genuine Smart Search pagination uses the real backend',
      (await nextPage).ok());
  }
  await page.getByLabel('Locality or property').fill('controlled no-match query');
  await page.getByText('No public rentals match', { exact: true }).waitFor();
  await page.getByRole('button', { name: 'Clear filters' }).click();
  await expectedPropertyCard.waitFor();
  await page.getByLabel('BHK').selectOption('4');
  await page.getByRole('button', { name: 'Search public homes' }).click();
  await page.getByText('No public rentals match', { exact: true }).waitFor();
  check('zero eligible property results use a truthful empty state', true);
  await page.getByRole('button', { name: 'Clear filters' }).click();
  await expectedPropertyCard.waitFor();
  const addProperty = expectedPropertyCard.getByRole('button', { name: 'Add', exact: true });
  await addProperty.click();
  await page.getByRole('button', { name: 'Save shortlist and continue' }).click();
  await page.getByRole('heading', { name: 'Confirm the customer’s WhatsApp number' }).waitFor();
  check('shortlist persisted and advanced to destination confirmation',
    networkTrace.some((entry) => entry.method === 'POST' && entry.path.endsWith('/shortlists')));

  await page.getByText('Same as the customer’s contact number', { exact: true }).click();
  await page.getByRole('checkbox', { name: /customer clearly gave this agreement/i }).check();
  await page.getByText('Verbal on this contact', { exact: true }).click();
  await page.getByRole('button', { name: 'Save permission and continue' }).click();
  await page.getByRole('heading', { name: 'Prepare the property message' }).waitFor();
  check('PROPERTY_SHARING consent revision was stored',
    networkTrace.some((entry) => entry.method === 'POST'
      && entry.path === `/api/v1/operations/leads/${leadId}/contact-preferences`));

  await page.getByRole('button', { name: 'Prepare property message' }).click();
  await page.getByRole('heading', { name: 'Open WhatsApp and send it yourself' }).waitFor();
  const snapshot = await page.locator('.ld-snapshot pre').textContent();
  check('server-authoritative prepared snapshot is displayed',
    snapshot?.includes(`https://pathome.test/property/${EXPECTED_PROPERTY_ID}`));

  await page.getByRole('button', { name: 'Copy message' }).click();
  await page.getByText(/Prepared message copied and the successful copy was recorded/).waitFor();
  check('clipboard success was recorded as persisted share evidence',
    await page.getByRole('list', { name: 'Saved property message actions' })
      .getByText('Links copied', { exact: true }).isVisible());

  await page.locator('.ld-handoff-actions').getByRole('button', { name: 'Open WhatsApp' }).click();
  let handoffDialog = page.getByRole('dialog', { name: 'Confirm the exact WhatsApp recipient' });
  await handoffDialog.waitFor();
  await handoffDialog.getByRole('button', { name: 'Verify current conditions' }).click();
  const launchButton = handoffDialog.getByRole('button', {
    name: 'Open WhatsApp',
    exact: true
  });
  await launchButton.waitFor();
  check('verified handoff exposes an action control rather than a cached navigation target',
    await handoffDialog.locator('a[href^="https://wa.me/"]').count() === 0);
  const popupPromise = context.waitForEvent('page');
  await launchButton.dblclick();
  const popup = await popupPromise;
  await popup.waitForURL(/^https:\/\/wa\.me\//, { timeout: 10_000 });
  await popup.waitForLoadState('domcontentloaded');
  const popupUrl = popup.url();
  shareUrl = popupUrl;
  const attemptEvent = page.getByRole('list', { name: 'Saved property message actions' })
    .getByText('Attempted to open WhatsApp', { exact: true });
  await attemptEvent.waitFor();
  const attemptRecorded = await attemptEvent.isVisible();
  check('manual WhatsApp launch opened a new controlled tab without provider delivery claims',
    popupUrl.startsWith('https://wa.me/') && attemptRecorded
      && waNavigations.length === 1
      && await popup.evaluate(() => window.opener === null),
    `popup=${popupUrl.replace(/(wa\.me\/\d{6})\d{4}/, '$1••••')} attemptRecorded=${attemptRecorded}`);
  await popup.close();

  await page.getByRole('button', { name: 'Yes, I sent it' }).click();
  const recordedEvents = page.getByRole('list', { name: 'Saved property message actions' });
  await recordedEvents.getByText('You reported sending it', { exact: true }).waitFor();
  const eventText = (await recordedEvents.allTextContents()).join('\n');
  check('operator send report is distinct from delivery truth',
    !/\b(delivered|read receipt)\b/i.test(eventText));
  await page.screenshot({
    path: join(EVIDENCE, 'manual-whatsapp-history-1440.png'),
    fullPage: true
  });

  await context.clearPermissions();
  await page.evaluate(() => { window.__pathomeFailClipboard = true; });
  const clipboardProbe = await page.evaluate(async () => {
    try {
      await navigator.clipboard.writeText('Pathome clipboard denial probe');
      return 'copied';
    } catch {
      return 'denied';
    }
  });
  check('controlled clipboard denial is active before fallback verification',
    clipboardProbe === 'denied', clipboardProbe);
  await page.getByRole('button', { name: 'Copy property links' }).click();
  await page.getByText(
    'Clipboard access failed. The prepared text is shown below so you can select and copy it manually.',
    { exact: true }
  ).waitFor();
  check('clipboard denial exposes manual-copy fallback without recording a false copy event',
    await page.getByLabel('Message to copy').isVisible()
    && await page.getByRole('list', { name: 'Saved property message actions' })
      .getByText('Links copied', { exact: true }).count() === 1);

  const shareHistoryBeforeDenial = await fetch(
    `${BASE}/api/v1/operations/leads/${leadId}/shares?page=0&size=10`,
    { headers: { Authorization: `Bearer ${OWNER.token}` } }
  ).then((response) => response.json());
  const shareBeforeDenial = shareHistoryBeforeDenial.items[0];
  const eventsBeforeDenial = shareBeforeDenial.events.length;
  await page.locator('.ld-handoff-actions')
    .getByRole('button', { name: 'Open WhatsApp', exact: true }).click();
  handoffDialog = page.getByRole('dialog', { name: 'Confirm the exact WhatsApp recipient' });
  await handoffDialog.waitFor();
  await handoffDialog.getByRole('button', { name: 'Verify current conditions' }).click();
  await handoffDialog.getByRole('button', { name: 'Open WhatsApp', exact: true }).waitFor();
  const currentPreferences = await fetch(
    `${BASE}/api/v1/operations/leads/${leadId}/contact-preferences`,
    { headers: { Authorization: `Bearer ${OWNER.token}` } }
  ).then((response) => response.json());
  const currentPreference = currentPreferences[0];
  const revokeResponse = await fetch(
    `${BASE}/api/v1/operations/leads/${leadId}/contact-preferences`,
    {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${OWNER.token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        expectedLeadVersion: currentPreference.leadVersion,
        expectedPreferenceRevision: currentPreference.revision,
        channel: 'WHATSAPP',
        destinationChoice: 'NONE',
        consentStatus: 'REVOKED',
        consentWordingVersion: 'property-sharing-v1',
        evidenceMethod: 'CUSTOMER_VERBAL'
      })
    }
  );
  check('genuine withdrawal committed after recipient verification',
    revokeResponse.status === 201, `status=${revokeResponse.status}`);
  const waBeforeDenial = waNavigations.length;
  const deniedFinalResponse = page.waitForResponse((response) =>
    new URL(response.url()).pathname
      === `/api/v1/operations/leads/${leadId}/shares/${shareBeforeDenial.id}/events`
    && response.request().method() === 'POST',
  { timeout: 10_000 });
  await handoffDialog.getByRole('button', { name: 'Open WhatsApp', exact: true }).click();
  const deniedFinal = await deniedFinalResponse;
  await page.waitForTimeout(500);
  const shareHistoryAfterDenial = await fetch(
    `${BASE}/api/v1/operations/leads/${leadId}/shares?page=0&size=10`,
    { headers: { Authorization: `Bearer ${OWNER.token}` } }
  ).then((response) => response.json());
  check('genuine final activation rejects revoked consent and opens no WhatsApp destination',
    deniedFinal.status() === 409
      && waNavigations.length === waBeforeDenial
      && shareHistoryAfterDenial.items[0].events.length === eventsBeforeDenial,
    `status=${deniedFinal.status()} navigations=${waNavigations.length - waBeforeDenial}`);

  await page.getByRole('tab', { name: /History/ }).click();
  await page.getByRole('tab', { name: /History/ }).waitFor();
  await page.locator('.ld-history-card').first().waitFor();
  const persistedHistoryId = shareHistoryAfterDenial.items[0].id;
  check('ordinary history hides the stored share identifier',
    !(await page.locator('body').innerText()).includes(persistedHistoryId));
  await page.reload();
  await page.locator('.ld-history-card').first().waitFor();
  const historyAfterReload = await fetch(
    `${BASE}/api/v1/operations/leads/${leadId}/shares?page=0&size=10`,
    { headers: { Authorization: `Bearer ${OWNER.token}` } }
  ).then((response) => response.json());
  check('persisted share history survives reload and status reads create no share',
    historyAfterReload.totalElements === shareHistoryAfterDenial.totalElements
    && historyAfterReload.items[0]?.id === persistedHistoryId
    && await page.locator('.ld-history-card').count() === historyAfterReload.items.length);

  await page.goto(`${BASE}/operations/leads/source-exceptions`);
  await page.getByText('You don’t have access to these Leads', { exact: true }).waitFor();
  check('ordinary OE cannot load raw source diagnostics', true);

  await setIdentity(page, SUPERVISOR, 'Supervisor', leadPath);
  await page.getByRole('heading', { name: 'G10 Browser Customer' }).waitFor();
  check('same-Team supervisor has read visibility but no owner-only actions',
    await page.getByText('View only with your current access', { exact: true }).isVisible()
    && await page.getByRole('button', { name: 'Show phone number' }).count() === 0
    && await page.getByRole('button', { name: 'Share properties' }).count() === 0);

  await setIdentity(page, OWNER, 'Owner', leadPath);
  if (API_INTERCEPTION_ENABLED) {
    holdNextDetail = true;
    await page.goto(`${BASE}${leadPath}`);
    for (let attempt = 0; attempt < 50 && heldDetailResponses.length === 0; attempt += 1) {
      await page.waitForTimeout(50);
    }
    check('late-read control captured one held Lead detail', heldDetailResponses.length === 1);
    await setIdentity(page, PEER, 'Peer', '/operations/leads');
    heldDetailResponses.splice(0).forEach((release) => release());
    await page.waitForTimeout(500);
    check('late foreign-owner response cannot populate the new account',
      !(await page.locator('body').innerText()).includes('G10 Browser Customer'));
  } else {
    await page.goto(`${BASE}${leadPath}`);
    await page.getByRole('heading', { name: 'G10 Browser Customer' }).waitFor();
    await setIdentity(page, PEER, 'Peer', '/operations/leads');
  }

  await page.goto(`${BASE}${leadPath}`);
  await page.getByText('This Lead isn’t available', { exact: true }).waitFor();
  check('same-Team peer receives generic Lead concealment', true);

  if (INTAKE_ENABLED) {
    const intakeLeadPath = `/operations/leads/${INTAKE_LEAD_ID}`;
    await setIdentity(page, INTAKE, 'City intake', intakeLeadPath);
    await page.getByRole('heading', { name: 'G10 Browser Customer' }).waitFor();
    check('City intake can read the legitimate unassigned Lead without owner-only actions',
      await page.getByRole('button', { name: 'Show phone number' }).count() === 0
      && await page.getByRole('button', { name: 'Share properties' }).count() === 0);
    const directDenial = await fetch(
      `${BASE}/api/v1/operations/leads/${INTAKE_LEAD_ID}/contact-preferences`,
      {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${INTAKE.token}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          expectedLeadVersion: 0,
          expectedPreferenceRevision: 0,
          channel: 'WHATSAPP',
          destinationChoice: 'SAME',
          consentStatus: 'GRANTED',
          consentWordingVersion: 'property-sharing-v1',
          evidenceMethod: 'CUSTOMER_VERBAL'
        })
      }
    );
    check('City intake direct consent command is denied by current-owner authority',
      directDenial.status === 404, `status=${directDenial.status}`);
  }

  await setIdentity(page, ADMIN, 'Staff Admin', '/operations/leads/source-exceptions');
  await page.getByRole('heading', { name: 'Import issues', exact: true }).waitFor();
  check('global Staff Admin reaches the restricted diagnostic surface', true);

  await page.goto(`${BASE}/operations/leads`);
  await page.getByRole('heading', { name: 'Leads', exact: true }).waitFor();
  check('diagnostic-only Staff Admin receives zero ordinary named Lead rows',
    await page.locator('.ld-row').count() === 0
    && await page.getByText('Import issues are available to Staff Admin', { exact: true }).isVisible());

  await setIdentity(page, OWNER, 'Owner', '/operations/leads/new');
  await page.getByRole('heading', { name: 'Capture an enquiry' }).waitFor();
  await page.getByLabel('Phone number').fill('+91 98811 22447');
  await page.getByLabel(/Source/).fill('Phone call');
  if (API_INTERCEPTION_ENABLED) {
    const capturesBeforeRace = networkTrace.filter((entry) =>
      entry.method === 'POST' && entry.path === '/api/v1/operations/leads').length;
    holdNextDuplicateLookup = true;
    await page.getByRole('button', { name: 'Review and save enquiry' }).click();
    for (let attempt = 0; attempt < 50 && heldDuplicateLookup === null; attempt += 1) {
      await page.waitForTimeout(50);
    }
    check('controlled duplicate lookup was held before Clear', heldDuplicateLookup !== null);
    await page.getByRole('button', { name: 'Clear form' }).click();
    heldDuplicateLookup?.release();
    await page.waitForTimeout(500);
    heldDuplicateLookup = null;
    const capturesAfterRace = networkTrace.filter((entry) =>
      entry.method === 'POST' && entry.path === '/api/v1/operations/leads').length;
    check('Clear during duplicate lookup sends zero stale capture and clears the draft',
      capturesAfterRace === capturesBeforeRace
      && await page.getByLabel('Phone number').inputValue() === ''
      && await page.getByLabel(/Source/).inputValue() === '');
  }

  await page.goto(`${BASE}/operations/leads`);
  await page.getByRole('heading', { name: 'Leads', exact: true }).waitFor();
  const filters = page.locator('.ld-filters select');
  await filters.nth(0).selectOption({ index: 1 });
  await page.waitForTimeout(250);
  await filters.nth(1).selectOption({ index: 1 });
  await filters.nth(2).selectOption('QUALIFYING');
  await page.waitForTimeout(250);

  for (const width of [320, 375, 390, 430, 768, 1024, 1280, 1440]) {
    await page.setViewportSize({ width, height: width < 600 ? 760 : 900 });
    await page.waitForTimeout(100);
    const layout = await page.evaluate(() => ({
      overflow: document.documentElement.scrollWidth > window.innerWidth,
      filterColumns: new Set([...document.querySelectorAll('.ld-filters > label')]
        .map((label) => Math.round(label.getBoundingClientRect().left))).size,
      filterValuesReadable: [...document.querySelectorAll('.ld-filter-selected')]
        .every((value) => value.scrollWidth <= value.clientWidth + 1),
      filterValues: [...document.querySelectorAll('.ld-filter-selected')]
        .map((value) => value.textContent?.trim()),
      tinyButtons: [...document.querySelectorAll('.ld-shell button')]
        .filter((button) => {
          const rect = button.getBoundingClientRect();
          const style = getComputedStyle(button);
          return style.visibility !== 'hidden' && style.display !== 'none'
            && rect.width > 0 && rect.height > 0
            && (rect.width < 44 || rect.height < 44);
        })
        .map((button) => button.textContent?.trim().slice(0, 40))
    }));
    check(`responsive layout at ${width}px has no page overflow`,
      !layout.overflow, JSON.stringify(layout));
    check(`visible controls at ${width}px meet the 44px target`,
      layout.tinyButtons.length === 0, layout.tinyButtons.join(', '));
    if (width <= 430) {
      check(`filter values remain readable in one column at ${width}px`,
        layout.filterColumns === 1 && layout.filterValuesReadable
        && layout.filterValues.some((value) => value?.includes('Bhopal Municipal Central'))
        && layout.filterValues.some((value) => value?.includes('North Bhopal Residential')),
        JSON.stringify(layout));
    }
    if ([320, 375, 430, 768, 1440].includes(width)) {
      await page.screenshot({
        path: join(EVIDENCE, `leads-${width}.png`),
        fullPage: true
      });
    }
  }

  await setIdentity(page, OWNER, 'Owner', leadPath);
  await page.getByRole('heading', { name: 'G10 Browser Customer' }).waitFor();
  if (API_INTERCEPTION_ENABLED) {
    holdNextDetail = true;
    const reloadAcrossLogout = page.reload({ waitUntil: 'domcontentloaded' });
    for (let attempt = 0; attempt < 50 && heldDetailResponses.length === 0; attempt += 1) {
      await page.waitForTimeout(50);
    }
    check('logout witness captured one authenticated held detail response',
      heldDetailResponses.length === 1);
    await page.evaluate(() => {
      window.localStorage.clear();
      window.dispatchEvent(new Event('pathome_auth_changed'));
    });
    heldDetailResponses.splice(0).forEach((release) => release());
    await reloadAcrossLogout.catch(() => {});
    await page.waitForTimeout(300);
  } else {
    await page.evaluate(() => {
      window.localStorage.clear();
      window.dispatchEvent(new Event('pathome_auth_changed'));
    });
  }
  check('logged-out browser discards the late owner detail and retains no account token',
    await page.evaluate(() => window.localStorage.getItem('pathome_auth_token') === null)
      && !(await page.locator('body').innerText()).includes('G10 Browser Customer'));

  await setIdentity(page, OWNER, 'Owner', leadPath);
  await page.getByRole('heading', { name: 'G10 Browser Customer' }).waitFor();
  const staleBrowserEdit = await fetch(
    `${BASE}/api/v1/operations/leads/${leadId}`,
    {
      method: 'PATCH',
      headers: {
        Authorization: `Bearer ${OWNER.token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        expectedVersion: -1,
        changes: { tenantProfile: 'Stale browser command' }
      })
    }
  );
  const foreignDetail = await fetch(
    `${BASE}/api/v1/operations/leads/${leadId}`,
    { headers: { Authorization: `Bearer ${PEER.token}` } }
  );
  const missingAuthDetail = await fetch(
    `${BASE}/api/v1/operations/leads/${leadId}`
  );
  check('browser-observed status boundaries distinguish stale, foreign and unauthenticated reads',
    staleBrowserEdit.status === 409
      && foreignDetail.status === 404
      && missingAuthDetail.status === 401,
    `stale=${staleBrowserEdit.status} foreign=${foreignDetail.status} unauth=${missingAuthDetail.status}`);

  if (API_INTERCEPTION_ENABLED) {
    const activitiesBeforeGrantLoss = await fetch(
      `${BASE}/api/v1/operations/leads/${leadId}/activities?page=0&size=20`,
      { headers: { Authorization: `Bearer ${SUPERVISOR.token}` } }
    ).then((response) => response.json());
    await page.getByRole('button', { name: 'Edit', exact: true }).click();
    await page.getByLabel('Tenant profile').fill('Grant-loss blocked edit');
    holdNextGrantLossCommand = true;
    const heldEditResponse = page.waitForResponse((response) =>
      new URL(response.url()).pathname === `/api/v1/operations/leads/${leadId}`
        && response.request().method() === 'PATCH',
    { timeout: 15_000 });
    await page.getByRole('button', { name: /Save version/ }).click();
    for (let attempt = 0; attempt < 50 && heldGrantLossCommand === null; attempt += 1) {
      await page.waitForTimeout(50);
    }
    check('one browser edit command is held before upstream while the owner grant is current',
      heldGrantLossCommand !== null);
    const revokeGrant = await fetch(GRANT_REVOKE_URL, {
      method: 'POST',
      headers: { 'X-Pathome-Test-Control': GRANT_REVOKE_TOKEN }
    });
    const revokeGrantBody = await revokeGrant.json();
    heldGrantLossCommand?.release();
    const deniedHeldEdit = await heldEditResponse;
    heldGrantLossCommand = null;
    check('test-controlled migrator revoked exactly the owner grant while the edit remained held',
      revokeGrant.status === 200 && revokeGrantBody.updated === 1
        && OWNER_GRANT_ID > 0,
      `status=${revokeGrant.status} updated=${revokeGrantBody.updated}`);
    check('held browser edit denied after grant loss without opening a private destination',
      deniedHeldEdit.status() === 403
        && waNavigations.length === 1,
      `status=${deniedHeldEdit.status()} navigations=${waNavigations.length}`);

    const ownerAfterGrantLoss = await fetch(
      `${BASE}/api/v1/operations/leads/${leadId}`,
      { headers: { Authorization: `Bearer ${OWNER.token}` } }
    );
    check('fresh owner readback reports current authority loss', ownerAfterGrantLoss.status === 403,
      `status=${ownerAfterGrantLoss.status}`);
    const supervisorDetailAfterGrantLoss = await fetch(
      `${BASE}/api/v1/operations/leads/${leadId}`,
      { headers: { Authorization: `Bearer ${SUPERVISOR.token}` } }
    ).then((response) => response.text());
    const activitiesAfterGrantLoss = await fetch(
      `${BASE}/api/v1/operations/leads/${leadId}/activities?page=0&size=20`,
      { headers: { Authorization: `Bearer ${SUPERVISOR.token}` } }
    ).then((response) => response.json());
    check('denied held edit changed neither the Lead facts nor its activity history',
      supervisorDetailAfterGrantLoss.includes('Working professionals')
        && !supervisorDetailAfterGrantLoss.includes('Grant-loss blocked edit')
        && activitiesAfterGrantLoss.totalElements === activitiesBeforeGrantLoss.totalElements);
    const shareAuthorityAfterGrantLoss = await fetch(
      `${BASE}/api/v1/operations/leads/${leadId}/shares?page=0&size=10`,
      { headers: { Authorization: `Bearer ${SUPERVISOR.token}` } }
    ).then((response) => response.json());
    check('share history reports current owner authority absent after the grant-loss race',
      shareAuthorityAfterGrantLoss.items.every((item) => item.currentAuthorityMatches === false
        && item.actionReady === false));

    await setIdentity(page, PEER, 'Peer', '/operations/leads');
    await page.getByRole('heading', { name: 'Leads', exact: true }).waitFor();
    await setIdentity(page, OWNER, 'Owner', leadPath);
    await page.getByText('You don’t have access to these Leads', { exact: true }).waitFor();
    check('A to B to A after grant loss retains the current denied state and no private Lead content',
      !(await page.locator('body').innerText()).includes('G10 Browser Customer'));
  }

  check('no browser console or page exception occurred', browserErrors.length === 0, browserErrors.join(' | '));
  check('no real API request returned an unexpected server error',
    unexpectedServerErrors.length === 0, unexpectedServerErrors.join(' | '));
} catch (error) {
  checks.push({ name: 'journey completed', passed: false, detail: error.message });
  await page.screenshot({ path: join(EVIDENCE, 'failure.png'), fullPage: true }).catch(() => {});
  throw error;
} finally {
  await writeFile(join(EVIDENCE, 'network-redacted.json'),
    JSON.stringify(networkTrace, null, 2));
  await writeFile(join(EVIDENCE, 'browser-results.json'), JSON.stringify({
    executedAt: new Date().toISOString(),
    transportMode: TRANSPORT_MODE,
    apiInterceptionEnabled: API_INTERCEPTION_ENABLED,
    installedApiRoutes,
    backendPort: BACKEND_PORT,
    leadId,
    leadPath,
    shareUrl: shareUrl ? shareUrl.replace(/(\d{6})\d{4}/, '$1••••') : null,
    checks,
    browserErrors,
    failedApiResponses
  }, null, 2));
  await browser.close();
  await new Promise((resolve) => server.close(resolve));
}
