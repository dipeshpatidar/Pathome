import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;
const root = fileURLToPath(new URL('../..', import.meta.url));
const vite = await createServer({
  root,
  configFile: false,
  appType: 'custom',
  logLevel: 'silent',
  server: { host: '127.0.0.1', port: 0 }
});
await vite.listen();
const address = vite.httpServer.address();
const origin = `http://127.0.0.1:${address.port}`;

const browser = await chromium.launch({ channel: 'chromium' });
let checks = 0;
let failures = 0;
const check = (label, ok, detail = '') => {
  checks += 1;
  if (ok) console.log(`  PASS  ${label}`);
  else {
    failures += 1;
    console.log(`  FAIL  ${label}${detail ? ` (${detail})` : ''}`);
  }
};

try {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  await context.addInitScript(() => {
    localStorage.setItem('pathome_auth_token', 'token-a');
    localStorage.setItem('pathome_role', 'ADMIN');
    localStorage.setItem(
      'pathome_user',
      JSON.stringify({ id: 11, email: 'a@pathome.test', role: 'ADMIN', fullName: 'Admin 11' })
    );
    window.__pathomeSessionExpiredEvents = [];
    window.addEventListener('pathome_session_expired', () => {
      window.__pathomeSessionExpiredEvents.push(true);
    });
  });
  const page = await context.newPage();
  await page.route(`${origin}/`, route => route.fulfill({
    status: 200,
    contentType: 'text/html; charset=utf-8',
    body: '<!doctype html><html><body></body></html>'
  }));
  await page.goto(origin);
  await page.evaluate(async () => {
    const host = document.createElement('div');
    host.id = 'f4-discard-harness';
    document.body.appendChild(host);
    const { mountDiscardOwnershipHarness } =
      await import('/src/tests/support/discardOwnershipBrowserHarness.ts');
    mountDiscardOwnershipHarness(host);
  });
  const harness = page.locator('#f4-discard-harness');
  await harness.getByRole('button', { name: /Drafts/ }).click();
  const draftCheckbox = page.locator(
    'input[aria-label="Select draft Replacement-owned draft"]'
  );
  await draftCheckbox.check({ force: true });
  await page.getByRole('button', { name: /Discard.*\(1\)/ }).click();
  const confirm = page.getByRole('button', { name: 'Discard 1 Draft', exact: true });
  await confirm.evaluate(button => button.click());
  await page.waitForFunction(() => typeof window.__resolveF4BulkResult === 'function');

  await page.evaluate(() => {
    localStorage.setItem('pathome_auth_token', 'token-b');
    localStorage.setItem(
      'pathome_user',
      JSON.stringify({ id: 12, email: 'b@pathome.test', role: 'ADMIN', fullName: 'Admin 12' })
    );
    window.__resolveF4BulkResult({
      succeeded: [],
      failed: ['stale-bulk-owner'],
      notAttempted: [],
      outcome: 'permission-denied',
      operationId: Symbol('old-bulk-owner'),
      isOwnerActive: false,
      canFinalize: false,
      isAuthError: false
    });
  });
  await page.waitForTimeout(250);

  const state = await page.evaluate(() => ({
    token: localStorage.getItem('pathome_auth_token'),
    events: window.__pathomeSessionExpiredEvents.length,
    dialogVisible: Boolean(document.querySelector('[role="dialog"]')),
    staleFeedback: document.body.innerText.includes('not discarded')
  }));
  check('stale bulk completion cannot expire the replacement credential', state.token === 'token-b');
  check('stale bulk completion emits no global expiry event', state.events === 0);
  check('stale bulk completion cannot mutate replacement dialog or notices',
    state.dialogVisible && !state.staleFeedback);
  await context.close();
} finally {
  await browser.close();
  await vite.close();
}

console.log(`checks=${checks} failures=${failures}`);
if (failures > 0) process.exitCode = 1;
