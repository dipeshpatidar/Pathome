/**
 * F4 callback-ownership matrix for bulk discard and BATCH media restoration.
 *
 * This serves the production build and stubs only HTTP responses. It exercises the real mounted
 * React components, fetch, localStorage/IndexedDB paths and request ordering.
 */
import { readFile } from 'node:fs/promises';
import { extname, join } from 'node:path';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;
const DIST = new URL('../../dist/', import.meta.url).pathname;
const ORIGIN = 'http://f4-callback.local';
const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2'
};

let checks = 0;
let failures = 0;
const check = (label, ok, detail = '') => {
  checks += 1;
  if (ok) {
    console.log(`  PASS  ${label}`);
  } else {
    failures += 1;
    console.log(`  FAIL  ${label}${detail ? ` (${detail})` : ''}`);
  }
};

const seed = (userId = 11, token = 'token-a', email = 'a@pathome.test') => ({
  pathome_auth_token: token,
  pathome_role: 'ADMIN',
  pathome_user: JSON.stringify({ id: userId, email, role: 'ADMIN', fullName: `Admin ${userId}` }),
  pathome_active_admin_tab: 'media'
});

const summary = index => ({
  draftId: `draft-bulk-${index}`,
  draftType: 'SINGLE',
  status: 'DRAFT',
  titleSummary: `Stored draft ${index}`,
  itemCount: 1,
  version: 1,
  mediaCount: 0,
  createdAt: '2026-10-21T00:00:00Z',
  updatedAt: '2026-10-21T00:05:00Z'
});

const json = (route, status, body) => route.fulfill({
  status,
  contentType: 'application/json',
  headers: { 'Cache-Control': 'no-store' },
  body: JSON.stringify(body)
});

const image = route => route.fulfill({
  status: 200,
  contentType: 'image/png',
  headers: { 'Cache-Control': 'no-store' },
  body: Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=',
    'base64'
  )
});

const createContext = async (browser, viewport = { width: 1280, height: 900 }) => {
  const context = await browser.newContext({ viewport });
  await context.addInitScript(values => {
    for (const [key, value] of Object.entries(values)) localStorage.setItem(key, value);
    window.__pathomeSessionExpiredEvents = [];
    window.addEventListener('pathome_session_expired', event => {
      window.__pathomeSessionExpiredEvents.push(event.detail || null);
    });
  }, seed());
  const page = await context.newPage();
  const calls = [];
  const errors = [];
  page.on('pageerror', error => errors.push(error.stack || error.message));
  return { context, page, calls, errors };
};

const installRoutes = async ({ page, calls }, options = {}) => {
  let releaseSibling;
  const siblingGate = new Promise(resolve => { releaseSibling = resolve; });
  let releaseSave;
  const saveGate = new Promise(resolve => { releaseSave = resolve; });
  let releaseMedia;
  const mediaGate = new Promise(resolve => { releaseMedia = resolve; });
  let heldSave = null;

  await page.route('**/*', async route => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.origin !== ORIGIN) return route.continue();

    if (url.pathname.startsWith('/api/')) {
      calls.push({
        method: request.method(),
        path: url.pathname,
        auth: request.headers().authorization || '',
        body: request.postData() || ''
      });

      if (url.pathname === '/api/v1/admin/drafts' && request.method() === 'GET') {
        return json(route, 200, options.drafts || []);
      }
      if (url.pathname === '/api/v1/admin/drafts' && request.method() === 'POST') {
        heldSave = request.postDataJSON();
        if (options.holdSave) await saveGate;
        if (options.saveStatus === 403) return json(route, 403, { message: 'Forbidden' });
        return json(route, 200, {
          draftId: heldSave.draftId,
          draftType: heldSave.draftType || 'SINGLE',
          status: 'DRAFT',
          titleSummary: heldSave.titleSummary || 'Draft',
          itemCount: heldSave.itemCount || 1,
          version: Number(heldSave.version || 0) + 1,
          payload: heldSave.payload,
          media: []
        });
      }
      if (options.batchDraft && url.pathname === `/api/v1/admin/drafts/${options.batchDraft.draftId}`) {
        return json(route, 200, options.batchDraft);
      }
      if (options.batchDraft && options.batchDraft.media.some(item =>
        url.pathname === `/api/v1/admin/drafts/${item.draftId}/media/${item.mediaId}`)) {
        await mediaGate;
        return image(route);
      }

      const deleteMatch = url.pathname.match(/^\/api\/v1\/admin\/drafts\/(draft-bulk-\d+)$/);
      if (deleteMatch && request.method() === 'DELETE') {
        const draftId = deleteMatch[1];
        const status = typeof options.deleteStatus === 'function'
          ? options.deleteStatus(draftId, calls)
          : 204;
        if (options.holdDraftId === draftId) await siblingGate;
        if (status === 'network') return route.abort('failed');
        if (status === 204) return route.fulfill({ status: 204 });
        return json(route, status, { message: status === 401 ? 'Unauthorized' : 'Denied' });
      }

      if (url.pathname.endsWith('/failed-uploads/count')) return json(route, 200, { count: 0 });
      if (url.pathname === '/api/v1/admin/failed-uploads') return json(route, 200, []);
      if (url.pathname === '/api/v1/staff/me/capabilities') {
        return json(route, 200, { active: false, capabilities: [] });
      }
      if (url.pathname.includes('/lessor') && url.pathname.includes('capabilit')) {
        return json(route, 200, { enabled: false, activated: false });
      }
      if (url.pathname === '/api/v1/notifications') {
        return json(route, 200, { notifications: [], unreadCount: 0 });
      }
      if (url.pathname === '/api/v1/properties') {
        return json(route, 200, { properties: [], hasMore: false, page: 0 });
      }
      if (url.pathname === '/api/v1/favorites') return json(route, 200, []);
      return json(route, 200, {});
    }

    const file = join(DIST, url.pathname === '/' ? '/index.html' : url.pathname);
    try {
      await route.fulfill({
        status: 200,
        contentType: CONTENT_TYPES[extname(file)] || 'application/octet-stream',
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

  return {
    releaseSibling: () => releaseSibling(),
    releaseSave: () => releaseSave(),
    releaseMedia: () => releaseMedia(),
    heldSave: () => heldSave
  };
};

const waitFor = async (page, predicate, message, attempts = 120) => {
  for (let i = 0; i < attempts; i += 1) {
    if (await predicate()) return;
    await page.waitForTimeout(50);
  }
  throw new Error(message);
};

const openBulkSelection = async (env, controls, count, keyboard = false) => {
  const { page } = env;
  await page.getByRole('button', { name: /Drafts/ }).first().click();
  for (let index = 1; index <= count; index += 1) {
    await page.getByRole('checkbox', { name: `Select draft Stored draft ${index}`, exact: true }).check();
  }
  const openButton = page.getByRole('button', { name: new RegExp(`Discard.*\\(${count}\\)`) });
  if (keyboard) {
    await openButton.focus();
    await page.keyboard.press('Enter');
  } else {
    await openButton.click();
  }
  const confirm = page.getByRole('button', {
    name: count === 1 ? 'Discard 1 Draft' : `Discard ${count} Drafts`,
    exact: true
  });
  return { confirm, controls };
};

const runBulkCase = async (browser, {
  count,
  statuses,
  holdDraftId,
  expectedRequests,
  label,
  viewport,
  keyboard = false
}) => {
  const env = await createContext(browser, viewport);
  const controls = await installRoutes(env, {
    drafts: Array.from({ length: count }, (_, index) => summary(index + 1)),
    holdDraftId,
    deleteStatus: draftId => statuses[draftId] ?? 204
  });
  try {
    const { page, calls, errors } = env;
    const prompt = page.getByPlaceholder(/Type or edit property details/);
    await page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
    await prompt.waitFor({ timeout: 20000 });
    await prompt.fill(`PRIVATE ${label} EDIT`);
    await waitFor(page, () => controls.heldSave() !== null || statuses[`draft-bulk-${count}`] === 401,
      `${label}: autosave was not dispatched`);

    const action = await openBulkSelection(env, controls, count, keyboard);
    if (keyboard) {
      await action.confirm.focus();
      await page.keyboard.press('Enter');
    } else {
      await action.confirm.click();
    }
    const expectedPermissionDenial = Object.values(statuses).some(status => status === 403);
    try {
      await waitFor(page, async () => {
        const deleteCount = calls.filter(call => call.method === 'DELETE').length;
        const body = await page.locator('body').innerText();
        return deleteCount > 0
          && (!expectedPermissionDenial || /access denied|session has ended|no longer have permission/i.test(body));
      }, `${label}: terminal feedback was not reached`, 160);
    } catch (error) {
      console.log(`DEBUG ${label}`, JSON.stringify({
        calls,
        body: await page.locator('body').innerText()
      }));
      throw error;
    }
    await page.waitForTimeout(350);

    const deleteCalls = calls.filter(call => call.method === 'DELETE');
    const sessionEvents = await page.evaluate(() => window.__pathomeSessionExpiredEvents.length);
    const body = await page.locator('body').innerText();
    check(`[${label}] denial or completion stops bulk dispatch at the owned boundary`,
      deleteCalls.length <= expectedRequests, `${deleteCalls.length} > ${expectedRequests}`);
    check(`[${label}] current 403 is not converted into global session expiry`,
      !Object.values(statuses).includes(403) || sessionEvents === 0,
      `${sessionEvents} expiry events`);
    check(`[${label}] current 401 uses the existing token-bound expiry event`,
      !Object.values(statuses).includes(401) || sessionEvents <= 1,
      `${sessionEvents} expiry events`);
    check(`[${label}] terminal permission feedback remains visible`,
      !expectedPermissionDenial || /access denied|session has ended|no longer have permission/i.test(body));
    check(`[${label}] no uncaught callback error`, errors.length === 0, errors.join(' | '));
    if (viewport?.width <= 430) {
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
      check(`[${label}] compact feedback has no horizontal overflow`, !overflow);
    }
    await controls.releaseSibling();
    return { calls, sessionEvents };
  } finally {
    try { controls.releaseSibling(); } catch {}
    try { controls.releaseSave(); } catch {}
    await env.context.close();
  }
};

const runOrdinaryBulkFailure = async browser => {
  const env = await createContext(browser);
  const variants = new Map([
    ['draft-bulk-1', 'network'],
    ['draft-bulk-2', 500],
    ['draft-bulk-3', 204]
  ]);
  const controls = await installRoutes(env, {
    drafts: [summary(1), summary(2), summary(3)],
    deleteStatus: draftId => variants.get(draftId)
  });
  try {
    const { page, calls, errors } = env;
    await page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
    await page.getByPlaceholder(/Type or edit property details/).waitFor({ timeout: 20000 });
    const action = await openBulkSelection(env, controls, 3);
    await action.confirm.click();
    await waitFor(page, () => calls.filter(call => call.method === 'DELETE').length === 3,
      '[ordinary mixed] not all deletes were attempted');
    await page.waitForTimeout(500);
    const body = await page.locator('body').innerText();
    check('[ordinary mixed] network and 500 failures remain recoverable partial outcomes',
      /1 discarded, 2 not discarded/i.test(body), body);
    check('[ordinary mixed] ordinary failures do not withdraw the editor',
      !/access denied/i.test(body));
    check('[ordinary mixed] all three batch items were attempted',
      calls.filter(call => call.method === 'DELETE').length === 3);
    check('[ordinary mixed] no uncaught callback error', errors.length === 0, errors.join(' | '));
  } finally {
    await env.context.close();
  }
};

const runDuplicateGuard = async browser => {
  const env = await createContext(browser);
  const controls = await installRoutes(env, {
    drafts: [summary(1), summary(2)],
    holdDraftId: 'draft-bulk-1'
  });
  try {
    const { page, calls } = env;
    await page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
    await page.getByPlaceholder(/Type or edit property details/).waitFor({ timeout: 20000 });
    const action = await openBulkSelection(env, controls, 2);
    await action.confirm.click();
    await waitFor(page, () => calls.filter(call => call.method === 'DELETE').length === 2,
      '[duplicate] initial delete dispatch missing');
    const inFlightConfirm = page.getByRole('button', {
      name: /^(Discard 2 Drafts|Discarding…)$/,
      exact: true
    });
    check('[duplicate] confirm action is disabled while the bulk owner is in flight',
      await inFlightConfirm.isDisabled());
    await inFlightConfirm.evaluate(button => button.click());
    await page.waitForTimeout(250);
    check('[duplicate] a repeated programmatic click dispatches no duplicate delete',
      calls.filter(call => call.method === 'DELETE').length === 2);
    controls.releaseSibling();
  } finally {
    try { controls.releaseSibling(); } catch {}
    await env.context.close();
  }
};

const runBatchExternalDenial = async browser => {
  const env = await createContext(browser);
  const draftId = 'draft-batch-external-denial';
  const media = Array.from({ length: 6 }, (_, index) => ({
    draftId,
    cardId: 'batch-card-1',
    mediaId: `batch-media-${index}`,
    originalFilename: `PRIVATE-BATCH-${index}.png`,
    fileSizeBytes: 4,
    contentType: 'image/png',
    roomTag: 'LIVING_ROOM',
    isCover: index === 0,
    previewUrl: `/private-batch-${index}.png`,
    createdAt: '2026-10-21T00:00:00Z'
  }));
  const controls = await installRoutes(env, {
    holdSave: true,
    saveStatus: 403,
    drafts: [{
      ...summary(1),
      draftId,
      draftType: 'BATCH',
      titleSummary: 'External denial batch',
      mediaCount: 6
    }],
    batchDraft: {
      draftId,
      draftType: 'BATCH',
      status: 'DRAFT',
      titleSummary: 'External denial batch',
      itemCount: 1,
      version: 4,
      payload: JSON.stringify({
        rawPrompts: 'PRIVATE EXTERNAL BATCH PROMPT',
        detectedCount: 1,
        activeCardId: 'batch-card-1',
        mobileWorkspaceView: 'review',
        stagedCards: [{
          id: 'batch-card-1',
          title: 'PRIVATE EXTERNAL BATCH CARD',
          description: 'PRIVATE EXTERNAL BATCH DESCRIPTION',
          bhk: '2 BHK',
          type: 'FLAT',
          city: 'Indore',
          sector: 'Vijay Nagar',
          rentAmount: 22000,
          isValid: true,
          stagedMedia: media.map((item, index) => ({
            id: item.mediaId,
            previewUrl: `/private-batch-${index}.png`,
            roomTag: 'LIVING_ROOM',
            isCover: index === 0,
            originalFilename: item.originalFilename,
            contentType: 'image/png'
          })),
          localPhotoPreviews: media.map((_, index) => `/private-batch-${index}.png`)
        }]
      }),
      media,
      createdAt: '2026-10-21T00:00:00Z',
      updatedAt: '2026-10-21T00:05:00Z'
    }
  });
  try {
    const { page, calls, errors } = env;
    await page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
    await page.getByPlaceholder(/Type or edit property details/).waitFor({ timeout: 20000 });
    await page.getByRole('button', { name: /Drafts/ }).first().click();
    await page.getByText('External denial batch', { exact: true }).click();
    const mediaCalls = () => calls.filter(call => call.path.includes('/media/batch-media-'));
    await waitFor(page, () => mediaCalls().length === 3, '[batch external] first media wave missing');
    const description = page.getByRole('textbox', { name: 'Value included Description' });
    await description.waitFor({ timeout: 10000 });
    await description.fill('PRIVATE EXTERNAL BATCH EDITED');
    await waitFor(page, () => controls.heldSave() !== null, '[batch external] autosave was not dispatched');
    controls.releaseSave();
    await waitFor(page, async () => !(await description.isVisible().catch(() => false)),
      '[batch external] denial did not withdraw the card');
    const callsAtDenial = mediaCalls().length;
    const denialBody = await page.locator('body').innerText();
    check('[batch external] terminal 403 immediately clears protected BATCH cards',
      !denialBody.includes('PRIVATE EXTERNAL BATCH CARD')
      && !denialBody.includes('PRIVATE EXTERNAL BATCH EDITED'));
    check('[batch external] terminal permission feedback is visible before held media release',
      /access denied|no longer have permission|do not have permission/i.test(denialBody));
    controls.releaseMedia();
    await page.waitForTimeout(700);
    const finalBody = await page.locator('body').innerText();
    check('[batch external] retired BATCH restore claims no further protected media',
      mediaCalls().length === callsAtDenial, `${mediaCalls().length} vs ${callsAtDenial}`);
    check('[batch external] late media cannot revive cards or private previews',
      !finalBody.includes('PRIVATE EXTERNAL BATCH CARD')
      && !finalBody.includes('PRIVATE-BATCH-'));
    check('[batch external] no uncaught callback error', errors.length === 0, errors.join(' | '));
  } finally {
    try { controls.releaseSave(); } catch {}
    try { controls.releaseMedia(); } catch {}
    await env.context.close();
  }
};

const browser = await chromium.launch({ channel: 'chromium' });
try {
  const only = process.env.F4_ONLY || 'all';
  if (only === 'all' || only === 'bulk') {
    await runBulkCase(browser, {
      count: 1,
      statuses: { 'draft-bulk-1': 403 },
      expectedRequests: 1,
      label: 'one 403'
    });
    await runBulkCase(browser, {
      count: 3,
      statuses: {
        'draft-bulk-1': 403,
        'draft-bulk-2': 403,
        'draft-bulk-3': 403
      },
      expectedRequests: 3,
      label: 'simultaneous 403'
    });
    await runBulkCase(browser, {
      count: 7,
      statuses: { 'draft-bulk-4': 403 },
      holdDraftId: 'draft-bulk-5',
      expectedRequests: 6,
      label: 'middle-chunk 403'
    });
    await runBulkCase(browser, {
      count: 1,
      statuses: { 'draft-bulk-1': 401 },
      expectedRequests: 1,
      label: 'one 401'
    });
    await runBulkCase(browser, {
      count: 1,
      statuses: { 'draft-bulk-1': 403 },
      expectedRequests: 1,
      label: 'compact keyboard 403',
      viewport: { width: 375, height: 812 },
      keyboard: true
    });
  }
  if (only === 'middle') {
    await runBulkCase(browser, {
      count: 7,
      statuses: { 'draft-bulk-4': 403 },
      expectedRequests: 6,
      label: 'middle-chunk 403'
    });
  }
  if (only === 'all' || only === 'ordinary') await runOrdinaryBulkFailure(browser);
  if (only === 'all' || only === 'duplicate') await runDuplicateGuard(browser);
  if (only === 'all' || only === 'batch') await runBatchExternalDenial(browser);
} finally {
  await browser.close();
}

console.log(`checks=${checks} failures=${failures}`);
if (failures > 0) process.exitCode = 1;
