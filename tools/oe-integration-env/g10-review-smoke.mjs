import { accessSync } from 'node:fs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const FRONTEND = 'http://127.0.0.1:5173';
const BACKEND = 'http://127.0.0.1:8080/api/v1';
const stateFile = '/private/tmp/pathome-oe-g10-20261022/review-state.json';
const evidence = process.env.PATHOME_G10_EVIDENCE_DIR
  ?? '/private/tmp/pathome-oe-g10-20261022/evidence/g10-final-evidence-20261023';
const password = process.env.OE_STAFF_PASSWORD;
if (!password) throw new Error('OE_STAFF_PASSWORD is required');
await mkdir(evidence, { recursive: true, mode: 0o700 });

const state = JSON.parse(await readFile(stateFile, 'utf8'));
const owner = state.users.owner;
const loginResponse = await fetch(`${BACKEND}/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
  body: JSON.stringify({ email: owner.email, password })
});
if (loginResponse.status !== 200) throw new Error(`Owner login failed: ${loginResponse.status}`);
const session = await loginResponse.json();
const api = async (path) => {
  const response = await fetch(`${BACKEND}${path}`, {
    headers: { Authorization: `Bearer ${session.token}`, Accept: 'application/json' },
    cache: 'no-store'
  });
  const payload = await response.json().catch(() => null);
  return { response, payload };
};

const candidates = [
  process.env.PATHOME_BROWSER_CHROMIUM_EXECUTABLE,
  '/Users/dipeshpatidar/Library/Caches/ms-playwright/chromium-1200/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
].filter(Boolean);
const executablePath = candidates.find((candidate) => {
  try {
    accessSync(candidate);
    return true;
  } catch {
    return false;
  }
});

const browser = await playwright.chromium.launch({
  headless: true,
  ...(executablePath ? { executablePath } : {})
});
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const checks = [];
const unexpectedServerErrors = [];
const browserExceptions = [];
const consoleErrors = [];
page.on('response', (response) => {
  if (response.url().includes('/api/') && response.status() >= 500) {
    unexpectedServerErrors.push(`${response.status()} ${new URL(response.url()).pathname}`);
  }
});
page.on('pageerror', (error) => browserExceptions.push(error.message));
page.on('console', (message) => {
  if (message.type() === 'error') consoleErrors.push(message.text());
});
const check = (name, passed, detail = '') => {
  checks.push({ name, passed: Boolean(passed), detail });
  if (!passed) throw new Error(`${name}${detail ? `: ${detail}` : ''}`);
};

try {
  await page.goto(FRONTEND);
  await page.evaluate(({ token, userId, email }) => {
    localStorage.setItem('pathome_auth_token', token);
    localStorage.setItem('pathome_role', 'TENANT');
    localStorage.setItem('pathome_user', JSON.stringify({
      id: userId,
      role: 'TENANT',
      fullName: 'G10 Review Owner',
      email
    }));
  }, { token: session.token, userId: session.userId, email: owner.email });
  await page.goto(`${FRONTEND}/operations/leads`);
  await page.getByRole('heading', { name: 'Leads', exact: true }).waitFor();
  await page.waitForFunction(
    (minimum) => document.querySelectorAll('.ld-row').length >= minimum,
    state.leads.length
  );
  const capabilities = await api('/staff/me/capabilities');
  check('authenticated OE capability is active and correctly Team-scoped',
    capabilities.response.status === 200
      && capabilities.payload?.staffActive === true
      && capabilities.payload.capabilities.some((capability) =>
        capability.capability === 'OPS_COORDINATE'
        && capability.scopeType === 'TEAM'
        && capability.teamId === state.teamId)
      && capabilities.payload.capabilities.every((capability) =>
        capability.capability !== 'STAFF_ADMIN'),
    JSON.stringify(capabilities.payload?.capabilities ?? []));
  check('authenticated owner sees the real review queue',
    await page.locator('.ld-row').count() >= state.leads.length);
  check('shared Operations sidebar is present', await page.locator('.oe-sidebar').isVisible());
  check('diagnostic-only navigation is absent for the OE',
    await page.locator('.oe-sidebar-nav').getByText('Import issues', { exact: true }).count() === 0);

  await page.getByRole('searchbox', { name: 'Search Leads' }).fill('Ananya Iyer');
  await page.locator('.ld-count').filter({ hasText: '1 Lead in the current server result' }).waitFor();
  check('owner search returns the persisted matching Lead', await page.locator('.ld-row').count() === 1);
  await page.locator('.ld-row').filter({ hasText: 'Ananya Iyer' }).click();
  await page.getByRole('heading', { name: 'Ananya Iyer' }).waitFor();
  check('Lead detail is backed by persisted data',
    await page.getByText('Synthetic owner-review requirement. No customer contact is performed.',
      { exact: true }).isVisible());
  check('Next action remains present', await page.getByRole('heading', { name: 'Next action' }).isVisible());

  const referenceSearch = await api(`/operations/leads?page=0&size=20&q=${encodeURIComponent(`ld-${state.leads[0].id}`)}`);
  const suffixSource = await api('/operations/leads?page=0&size=20');
  const selectedSummary = suffixSource.payload?.items?.find((item) => item.customerName === 'Ananya Iyer');
  const phoneSuffix = selectedSummary?.maskedContact?.match(/(\d{4})$/)?.[1];
  const suffixSearch = phoneSuffix
    ? await api(`/operations/leads?page=0&size=20&q=${encodeURIComponent(phoneSuffix)}`)
    : null;
  const followUp = await api('/operations/leads?page=0&size=20&followUp=NONE&followUpZone=Asia%2FKolkata');
  check('reference, exact last-four and follow-up filters use current authorized server results',
    referenceSearch.response.status === 200
      && referenceSearch.payload?.items?.length === 1
      && referenceSearch.payload.items[0].id === state.leads[0].id
      && Boolean(phoneSuffix)
      && suffixSearch?.response.status === 200
      && suffixSearch.payload?.items?.some((item) => item.id === selectedSummary.id)
      && followUp.response.status === 200
      && followUp.payload?.items?.every((item) => item.followUpAt === null)
      && referenceSearch.response.headers.get('cache-control') === 'no-store'
      && suffixSearch.response.headers.get('cache-control') === 'no-store'
      && followUp.response.headers.get('cache-control') === 'no-store',
    `reference=${referenceSearch.response.status} suffix=${suffixSearch?.response.status} `
      + `followUp=${followUp.response.status}`);

  const consentRead = await api(`/operations/leads/${state.leads[0].id}/contact-preferences`);
  const historyRead = await api(`/operations/leads/${state.leads[0].id}/shares?page=0&size=10`);
  check('authenticated consent and share-history reads are successful, read-only and non-cacheable',
    consentRead.response.status === 200
      && Array.isArray(consentRead.payload)
      && historyRead.response.status === 200
      && Array.isArray(historyRead.payload?.items)
      && consentRead.response.headers.get('cache-control') === 'no-store'
      && historyRead.response.headers.get('cache-control') === 'no-store',
    `consent=${consentRead.response.status} history=${historyRead.response.status}`);

  await page.getByRole('button', { name: 'Share properties' }).click();
  await page.locator('.ld-property-card').first().waitFor();
  check('published catalog property is available to the real Smart Search',
    await page.locator('.ld-property-card').count() >= 1
    && unexpectedServerErrors.length === 0);
  check('review browser completed without page exceptions, console errors or API 5xx',
    browserExceptions.length === 0
      && consoleErrors.length === 0
      && unexpectedServerErrors.length === 0,
    JSON.stringify({ browserExceptions, consoleErrors, unexpectedServerErrors }));

  await page.screenshot({
    path: `${evidence}/owner-review-1440.png`,
    fullPage: true
  });
  await writeFile(`${evidence}/owner-review-smoke.json`, JSON.stringify({
    checkedAt: new Date().toISOString(),
    frontend: FRONTEND,
    backend: BACKEND,
    database: state.database,
    leadCount: state.leads.length,
    listingId: state.publishedHome.listingId,
    browserExceptions,
    consoleErrors,
    unexpectedServerErrors,
    checks
  }, null, 2) + '\n', { mode: 0o600 });
  console.log(`OWNER REVIEW READY checks=${checks.length}`);
} finally {
  await browser.close();
}
