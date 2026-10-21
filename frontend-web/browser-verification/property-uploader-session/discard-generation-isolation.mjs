/**
 * Production-module discriminator for F4-BULK stale 401 completion.
 *
 * The hook and DraftManagementBar are mounted from their actual TypeScript sources. Fetch responses
 * deliberately ignore AbortSignal so a retired request can settle late.
 */
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
const origin = `http://127.0.0.1:${vite.httpServer.address().port}`;
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

const profile = (id, email) => ({ id, email, role: 'ADMIN', fullName: `Admin ${id}` });

const mountScenario = async (page, mode) => {
  await page.evaluate(async ({ responseMode }) => {
    localStorage.setItem('pathome_auth_token', 'token-a');
    localStorage.setItem('pathome_role', 'ADMIN');
    localStorage.setItem('pathome_user', JSON.stringify({
      id: 11,
      email: 'a@pathome.test',
      role: 'ADMIN',
      fullName: 'Admin 11'
    }));

    const React = (await import('/node_modules/.vite/deps/react.js')).default;
    const { createRoot } = (await import('/node_modules/.vite/deps/react-dom_client.js')).default;
    const { usePropertyDraft } = await import('/src/hooks/usePropertyDraft.ts');
    const { DraftManagementBar } = await import('/src/components/DraftManagementBar.tsx');

    const drafts = [1, 2, 3].map((index) => ({
      draftId: `draft-${index}`,
      draftType: 'SINGLE',
      status: 'DRAFT',
      titleSummary: `Draft ${index}`,
      itemCount: 1,
      version: 1,
      mediaCount: 0,
      createdAt: '2026-10-21T00:00:00Z',
      updatedAt: '2026-10-21T00:00:00Z'
    }));
    window.__requests = [];
    window.__results = [];
    window.__expiryEvents = [];
    window.addEventListener('pathome_session_expired', (event) => {
      window.__expiryEvents.push(event.detail);
    });

    const responseFor = () => {
      if (responseMode === 'network') return Promise.reject(new TypeError('network failed'));
      const status = Number(responseMode);
      const body = status === 204 ? null : JSON.stringify({ message: status === 401 ? 'Unauthorized' : 'Failed' });
      return Promise.resolve(new Response(body, {
        status,
        headers: { 'Content-Type': 'application/json' }
      }));
    };

    const nativeFetch = window.fetch.bind(window);
    window.fetch = async (input, options = {}) => {
      const url = String(input);
      if (!url.includes('/api/v1/admin/drafts')) return nativeFetch(input, options);
      window.__requests.push({
        method: options.method || 'GET',
        url,
        token: options.headers?.Authorization
      });
      if ((options.method || 'GET') === 'GET') {
        return new Response(JSON.stringify(drafts), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }
      return new Promise((resolve, reject) => {
        window.__deleteReleases ||= [];
        window.__deleteReleases.push(() => responseFor().then(resolve, reject));
        window.__releaseDelete = () => {
          const releases = window.__deleteReleases.splice(0);
          releases.forEach((release) => release());
        };
      });
    };

    function Harness() {
      const hook = usePropertyDraft({
        draftType: 'SINGLE',
        onClearDraftState: () => {},
        onRestoreDraft: () => {}
      });
      window.__hook = hook;
      return React.createElement(DraftManagementBar, {
        currentDraftId: null,
        draftType: 'SINGLE',
        autosaveStatus: hook.autosaveStatus,
        lastSavedAt: null,
        drafts,
        onSelectDraft: () => {},
        onStartNewDraft: () => {},
        onDiscardDraft: async (draftId) => {
          const result = await hook.discardDraft(draftId);
          window.__results.push({
            ...result,
            operationId: String(result.operationId),
            kind: 'single'
          });
          return result;
        },
        onDiscardDrafts: async (ids) => {
          const result = await hook.discardMultipleDrafts(ids);
          window.__results.push({
            ...result,
            operationId: String(result.operationId),
            kind: 'bulk'
          });
          return result;
        }
      });
    }

    createRoot(document.getElementById('root')).render(React.createElement(Harness));
  }, { responseMode: mode });
  await page.getByRole('button', { name: /Drafts/ }).waitFor();
};

const selectDrafts = async (page, count = 3) => {
  const first = page.getByRole('checkbox', { name: 'Select draft Draft 1', exact: true });
  if (!(await first.isVisible().catch(() => false))) {
    await page.getByRole('button', { name: /Drafts/ }).first().click();
  }
  for (let index = 1; index <= count; index += 1) {
    await page.getByRole('checkbox', {
      name: `Select draft Draft ${index}`,
      exact: true
    }).check({ force: true });
  }
  await page.getByRole('button', { name: new RegExp(`Discard.*\\(${count}\\)`) }).click();
};

const confirmDiscard = async (page, count = 3) => {
  await page.getByRole('button', {
    name: count === 1 ? 'Discard 1 Draft' : `Discard ${count} Drafts`,
    exact: true
  }).evaluate((button) => button.click());
  await page.waitForFunction(() => typeof window.__releaseDelete === 'function');
};

const replaceSession = async (page, replacement) => {
  const other = await page.context().newPage();
  await other.route('**/*', (route) => route.fulfill({
    contentType: 'text/html',
    body: '<!doctype html><html><body></body></html>'
  }));
  await other.goto(origin);
  await other.evaluate(({ token, user }) => {
    localStorage.setItem('pathome_auth_token', token);
    localStorage.setItem('pathome_user', JSON.stringify(user));
    localStorage.setItem('pathome_role', 'ADMIN');
  }, replacement);
  await other.close();
  await page.waitForTimeout(80);
};

const runScenario = async ({ label, mode, replacements, expectedOutcome }) => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.route(`${origin}/`, (route) => route.fulfill({
    contentType: 'text/html; charset=utf-8',
    body: '<!doctype html><html><body><div id="root"></div></body></html>'
  }));
  await page.goto(origin);
  await mountScenario(page, mode);

  await selectDrafts(page);
  await confirmDiscard(page);
  for (const replacement of replacements) await replaceSession(page, replacement);
  await selectDrafts(page);
  const dialogBefore = await page.getByRole('dialog').count();
  const beforeRelease = await page.evaluate(() => ({
    requests: window.__requests,
    results: window.__results,
    release: typeof window.__releaseDelete
  }));
  console.log(`\n[${label}] before stale release`, JSON.stringify(beforeRelease));
  await page.evaluate(() => window.__releaseDelete());
  await page.waitForFunction(() => window.__results.length === 1, { timeout: 3000 }).catch(() => {});
  await page.waitForTimeout(700);

  const state = await page.evaluate(() => ({
    token: localStorage.getItem('pathome_auth_token'),
    user: JSON.parse(localStorage.getItem('pathome_user') || 'null'),
    dialog: document.querySelectorAll('[role="dialog"]').length,
    dialogText: document.querySelector('[role="dialog"]')?.textContent || '',
    results: window.__results,
    requests: window.__requests,
    expiryEvents: window.__expiryEvents
  }));
  const result = state.results[0];
  console.log(`\n[${label}]`, JSON.stringify({ dialogBefore, ...state, errors }));
  check(`${label}: replacement dialog remains open`, dialogBefore === 1 && state.dialog === 1);
  check(`${label}: stale result is inert`, result.outcome === expectedOutcome
    && result.isOwnerActive === false
    && result.canFinalize === false);
  check(`${label}: replacement selection and non-busy dialog survive`,
    /3 drafts selected/i.test(state.dialogText)
    && !/discarding/i.test(state.dialogText)
    && !/not discarded|access denied/i.test(state.dialogText));
  check(`${label}: no later protected dispatch`, state.requests.filter((item) => item.method === 'DELETE').length === 3);
  check(`${label}: no page error`, errors.length === 0);
  await context.close();
};

const runCurrentScenario = async ({ label, mode, expectedOutcome, expectedExpiryCount }) => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.route(`${origin}/`, (route) => route.fulfill({
    contentType: 'text/html; charset=utf-8',
    body: '<!doctype html><html><body><div id="root"></div></body></html>'
  }));
  await page.goto(origin);
  await mountScenario(page, mode);
  await selectDrafts(page);
  await confirmDiscard(page);
  await page.evaluate(() => window.__releaseDelete());
  await page.waitForFunction(() => window.__results.length === 1, { timeout: 3000 }).catch(() => {});
  await page.waitForTimeout(300);

  const state = await page.evaluate(() => ({
    results: window.__results,
    expiryEvents: window.__expiryEvents,
    dialog: document.querySelectorAll('[role="dialog"]').length
  }));
  const result = state.results[0];
  console.log(`\n[${label}]`, JSON.stringify({ ...state, errors }));
  check(`${label}: current outcome remains truthful`, result?.outcome === expectedOutcome);
  check(`${label}: expiry notification is deduplicated`,
    state.expiryEvents.length === expectedExpiryCount);
  check(`${label}: no page error`, errors.length === 0);
  await context.close();
};

const runSingleReplacementScenario = async () => {
  const label = 'C7 single discard old 401 after same-user replacement';
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.route(`${origin}/`, (route) => route.fulfill({
    contentType: 'text/html; charset=utf-8',
    body: '<!doctype html><html><body><div id="root"></div></body></html>'
  }));
  await page.goto(origin);
  await mountScenario(page, '401');
  await page.getByRole('button', { name: /Drafts/ }).click();
  await page.getByRole('button', { name: 'Discard draft Draft 1', exact: true }).click();
  await page.getByRole('button', { name: 'Confirm discard', exact: true }).evaluate((button) => button.click());
  await page.waitForFunction(() => typeof window.__releaseDelete === 'function');
  await replaceSession(page, {
    token: 'token-b',
    user: profile(11, 'a@pathome.test')
  });
  await page.getByRole('button', { name: /Drafts/ }).click();
  await page.getByRole('button', { name: 'Discard draft Draft 2', exact: true })
    .evaluate((button) => button.click());
  const dialogBefore = await page.getByRole('dialog').count();
  await page.evaluate(() => window.__releaseDelete());
  await page.waitForFunction(() => window.__results.length === 1, { timeout: 3000 }).catch(() => {});
  await page.waitForTimeout(700);
  const state = await page.evaluate(() => ({
    token: localStorage.getItem('pathome_auth_token'),
    dialog: document.querySelectorAll('[role="dialog"]').length,
    result: window.__results[0],
    expiryEvents: window.__expiryEvents
  }));
  console.log(`\n[${label}]`, JSON.stringify({ dialogBefore, ...state, errors }));
  check(`${label}: replacement dialog remains open`, dialogBefore === 1 && state.dialog === 1);
  check(`${label}: stale single result is inert`, state.result?.kind === 'single'
    && state.result.outcome === 'cancelled'
    && state.result.isOwnerActive === false
    && state.result.canFinalize === false);
  check(`${label}: no stale expiry event`, state.expiryEvents.length === 0);
  await context.close();
};

try {
  const sameUser = {
    token: 'token-b',
    user: profile(11, 'a@pathome.test')
  };
  const crossAccount = {
    token: 'token-b',
    user: profile(12, 'b@pathome.test')
  };
  const restoredA = {
    token: 'token-a',
    user: profile(11, 'a@pathome.test')
  };

  await runScenario({
    label: 'C2 same-user A to B old 401',
    mode: '401',
    replacements: [sameUser],
    expectedOutcome: 'cancelled'
  });
  await runScenario({
    label: 'C2 same-user A to B old 204',
    mode: '204',
    replacements: [sameUser],
    expectedOutcome: 'cancelled'
  });
  await runScenario({
    label: 'C2 same-user A to B old 403',
    mode: '403',
    replacements: [sameUser],
    expectedOutcome: 'cancelled'
  });
  await runScenario({
    label: 'C2 same-user A to B old network',
    mode: 'network',
    replacements: [sameUser],
    expectedOutcome: 'cancelled'
  });
  await runScenario({
    label: 'C2 same-user A to B old 500',
    mode: '500',
    replacements: [sameUser],
    expectedOutcome: 'cancelled'
  });
  await runScenario({
    label: 'C3 cross-account A to B old 401',
    mode: '401',
    replacements: [crossAccount],
    expectedOutcome: 'cancelled'
  });
  await runScenario({
    label: 'C3 generation A to B to A old 401',
    mode: '401',
    replacements: [sameUser, restoredA],
    expectedOutcome: 'cancelled'
  });
  await runCurrentScenario({
    label: 'C4/C6 current bulk 401 emits expiry once',
    mode: '401',
    expectedOutcome: 'session-expired',
    expectedExpiryCount: 1
  });
  await runCurrentScenario({
    label: 'C4 current bulk 403 stays local',
    mode: '403',
    expectedOutcome: 'permission-denied',
    expectedExpiryCount: 0
  });
  await runSingleReplacementScenario();
} finally {
  await browser.close();
  await vite.close();
}

console.log(`\nchecks=${checks} failures=${failures}`);
if (failures > 0) process.exitCode = 1;
