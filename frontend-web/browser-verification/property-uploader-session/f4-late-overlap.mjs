/**
 * F4-LATE discriminator: a response dispatched before terminal denial must never revive the
 * denied uploader. This runner serves the production build and exercises the mounted React UI.
 */
import { readFile } from 'node:fs/promises';
import { extname, join } from 'node:path';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;
const DIST = new URL('../../dist/', import.meta.url).pathname;
const ORIGIN = 'http://f4-late.local';
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

const seed = (userId, token, email) => ({
  pathome_auth_token: token,
  pathome_role: 'ADMIN',
  pathome_user: JSON.stringify({ id: userId, email, role: 'ADMIN', fullName: `Admin ${userId}` }),
  pathome_active_admin_tab: 'media'
});

const json = (route, status, body) => route.fulfill({
  status,
  contentType: 'application/json',
  headers: { 'Cache-Control': 'no-store' },
  body: JSON.stringify(body)
});

const installRoutes = async (page, calls, options) => {
  const mediaGate = options.holdMedia
    ? new Promise(resolve => { options.releaseMedia = resolve; })
    : Promise.resolve();
  const saveGate = options.holdSave
    ? new Promise(resolve => { options.releaseSave = resolve; })
    : Promise.resolve();
  const parseGate = options.holdParse
    ? new Promise(resolve => { options.releaseParse = resolve; })
    : Promise.resolve();

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
      if (url.pathname === '/api/v1/properties/parse-prompt') {
        options.dispatchParse?.();
        await parseGate;
        if (options.parseMode === 'denied') return json(route, 403, { message: 'Forbidden' });
        return json(route, 200, {
          title: 'PRIVATE HELD PARSE TITLE',
          bhk: '2 BHK',
          type: 'Flat',
          city: 'Indore',
          sector: 'Vijay Nagar',
          ownerPhone: '+91 98260 12345',
          rentAmount: 22000,
          depositVal: '44000 Security Deposit',
          requiresReview: false
        });
      }
      if (url.pathname === '/api/v1/properties/parse-batch') {
        return json(route, 200, [{
          promptIndex: 1,
          rawPrompt: 'PRIVATE BATCH F4 TEXT',
          title: 'PRIVATE BATCH F4 CARD',
          description: 'PRIVATE BATCH F4 DESCRIPTION',
          bhk: '2 BHK',
          type: 'Flat',
          city: 'Indore',
          sector: 'Vijay Nagar',
          rentAmount: 22000,
          depositVal: '44000 Security Deposit',
          ownerPhone: '+91 98260 12345',
          missingFields: []
        }]);
      }
      if (url.pathname === '/api/v1/admin/drafts' && request.method() === 'GET') {
        return json(route, 200, options.savedDraft ? [{
          draftId: options.savedDraft.draftId,
          draftType: options.savedDraft.draftType || 'SINGLE',
          status: 'DRAFT',
          titleSummary: options.savedDraft.titleSummary,
          itemCount: 1,
          version: 4,
          mediaCount: 1,
          createdAt: '2026-10-21T00:00:00Z',
          updatedAt: '2026-10-21T00:05:00Z'
        }] : []);
      }
      if (url.pathname === '/api/v1/admin/drafts' && request.method() === 'POST') {
        if ((request.headers().authorization || '') === 'Bearer token-a') {
          options.dispatchSave?.();
          await saveGate;
        }
        if (options.saveMode === 'network') return route.abort('failed');
        if (options.saveMode === 'denied') {
          return json(route, 403, { message: 'Forbidden' });
        }
        if (options.saveMode === 'conflict') {
          return json(route, 409, { message: 'Draft was modified elsewhere.' });
        }
        if (options.saveMode === 'server') {
          return json(route, 500, { message: 'Temporary save failure.' });
        }
        return json(route, 200, {
          draftId: JSON.parse(request.postData() || '{}').draftId,
          draftType: options.savedDraft?.draftType || 'SINGLE',
          status: 'DRAFT',
          titleSummary: 'Late save',
          itemCount: 1,
          version: 5,
          payload: '{}',
          media: []
        });
      }
      if (options.savedDraft && url.pathname === `/api/v1/admin/drafts/${options.savedDraft.draftId}`) {
        return json(route, 200, {
          draftId: options.savedDraft.draftId,
          draftType: options.savedDraft.draftType || 'SINGLE',
          status: 'DRAFT',
          titleSummary: options.savedDraft.titleSummary,
          itemCount: 1,
          version: 4,
          payload: JSON.stringify(options.savedDraft.payload || {
            newBhkLabel: options.savedDraft.protectedText
          }),
          media: options.savedDraft.media,
          createdAt: '2026-10-21T00:00:00Z',
          updatedAt: '2026-10-21T00:05:00Z'
        });
      }
      if (options.savedDraft && options.savedDraft.media.some(media =>
        url.pathname.endsWith(`/media/${media.mediaId}`))) {
        await mediaGate;
        return json(route, 403, { message: 'Forbidden' });
      }
      if (url.pathname.endsWith('/failed-uploads/count')) return json(route, 200, { count: 0 });
      if (url.pathname === '/api/v1/admin/failed-uploads') return json(route, 200, []);
      if (url.pathname === '/api/v1/staff/me/capabilities') {
        return json(route, 200, { active: false, capabilities: [] });
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
};

const browser = await chromium.launch({ channel: 'chromium' });
try {
  const runLateResult = async (saveMode) => {
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    await context.addInitScript(values => {
      for (const [key, value] of Object.entries(values)) localStorage.setItem(key, value);
    }, seed(11, 'token-a', 'a@pathome.test'));
    const page = await context.newPage();
    const calls = [];
    const errors = [];
    let saveDispatched;
    const saveInFlight = new Promise(resolve => { saveDispatched = resolve; });
    const controls = {
      holdMedia: true,
      holdSave: true,
      saveMode,
      dispatchSave: saveDispatched,
      savedDraft: {
        draftId: 'draft-single-f4-late',
        titleSummary: 'F4 late saved draft',
        protectedText: 'PRIVATE F4 TEXT',
        mediaId: 'f4-media',
        media: [{
          draftId: 'draft-single-f4-late',
          mediaId: 'f4-media',
          originalFilename: 'f4-private.jpg',
          contentType: 'image/jpeg',
          roomTag: 'LIVING_ROOM',
          isCover: true
        }]
      }
    };
    page.on('pageerror', error => errors.push(error.stack || error.message));
    await installRoutes(page, calls, controls);
    await page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
    const prompt = page.getByPlaceholder(/Type or edit property details/);
    await prompt.waitFor({ timeout: 20000 });
    await page.getByRole('button', { name: /Drafts/ }).first().click();
    await page.getByText(controls.savedDraft.titleSummary, { exact: true }).click();

    await prompt.fill(`${controls.savedDraft.protectedText} edited while media is pending`);
    await Promise.race([
      saveInFlight,
      new Promise((_, reject) => setTimeout(() => reject(new Error('autosave was not dispatched')), 5000))
    ]);
    check(`[${saveMode}] autosave is in flight before terminal media denial`, true);

    const mediaResponse = page.waitForResponse(response =>
      new URL(response.url()).pathname.endsWith('/media/f4-media'));
    controls.releaseMedia();
    if (saveMode === 'denied') controls.releaseSave();
    await mediaResponse;
    await page.waitForTimeout(400);
    const callsAtDenial = calls.length;
    let callsBeforeReplacement = null;
    const denialBody = await page.locator('body').innerText();
    check(`[${saveMode}] terminal denial withdraws protected text before the late response`,
      await prompt.inputValue() === '' && !denialBody.includes(controls.savedDraft.protectedText));
    check(`[${saveMode}] terminal denial is visible before the late response`,
      /access denied|no longer have permission|do not have permission/i.test(denialBody));

    if (saveMode === 'success') {
      callsBeforeReplacement = calls.length;
      await page.evaluate(() => {
        localStorage.setItem('pathome_auth_token', 'token-b');
        window.dispatchEvent(new Event('pathome_auth_changed'));
      });
      await prompt.fill('REPLACEMENT AUTHORIZED TEXT');
      await page.waitForTimeout(1200);
      check('[success] replacement session can save after old denial',
        await page.getByText('Saved', { exact: true }).count() > 0);
      check('[success] replacement save uses its own credential',
        calls.some(call => call.method === 'POST'
          && call.auth === 'Bearer token-b'
          && call.body.includes('REPLACEMENT AUTHORIZED TEXT')));
    }

    const callsBeforeOldRelease = calls.length;
    controls.releaseSave();
    await page.waitForTimeout(900);
    const body = await page.locator('body').innerText();
    const postReleaseCalls = calls.slice(callsBeforeOldRelease);
    const lateCalls = calls.slice(callsAtDenial, callsBeforeReplacement ?? undefined);
    check(`[${saveMode}] late result cannot restore Saved without a replacement session`,
      saveMode === 'success' || await page.getByText('Saved', { exact: true }).count() === 0);
    check(`[${saveMode}] terminal permission feedback persists after the late response`,
      saveMode === 'success'
        || /access denied|no longer have permission|do not have permission/i.test(body));
    check(`[${saveMode}] late completion cannot dispatch a protected draft-list refresh`,
      postReleaseCalls.every(call => call.path !== '/api/v1/admin/drafts')
      && lateCalls.every(call => call.path !== '/api/v1/admin/drafts'));
    check(`[${saveMode}] protected state remains withdrawn after the late response`,
      saveMode === 'success' ? await prompt.inputValue() === 'REPLACEMENT AUTHORIZED TEXT'
        : await prompt.inputValue() === '' && !body.includes(controls.savedDraft.protectedText));
    check(`[${saveMode}] late callback leaves no uncaught page error`,
      errors.length === 0, errors.join(' | '));

    if (saveMode === 'server') {
      await page.reload({ waitUntil: 'domcontentloaded' });
      const reloadedPrompt = page.getByPlaceholder(/Type or edit property details/);
      await reloadedPrompt.waitFor({ timeout: 20000 });
      await page.waitForTimeout(500);
      const reloadedBody = await page.locator('body').innerText();
      check('[server] refresh cannot restore denied editor text or Saved state',
        await reloadedPrompt.inputValue() === ''
        && await page.getByText('Saved', { exact: true }).count() === 0
        && !reloadedBody.includes(controls.savedDraft.protectedText));

      await page.evaluate(() => {
        localStorage.setItem('pathome_auth_token', 'token-b');
        window.dispatchEvent(new Event('pathome_auth_changed'));
      });
      controls.saveMode = 'success';
      const replacementResponse = page.waitForResponse(response => {
        const url = new URL(response.url());
        return url.pathname === '/api/v1/admin/drafts'
          && response.request().method() === 'POST'
          && response.request().headers().authorization === 'Bearer token-b';
      }, { timeout: 5000 });
      await reloadedPrompt.fill('REPLACEMENT AFTER REFRESH');
      await replacementResponse;
      const replacementSaved = page.getByText('Saved', { exact: true });
      const replacementSucceeded = await replacementSaved.first()
        .waitFor({ timeout: 2500 }).then(() => true).catch(() => false);
      check('[server] legitimate replacement login works after refresh', replacementSucceeded);
    }
    await context.close();
  };

  for (const saveMode of ['success', 'denied', 'conflict', 'server', 'network']) {
    await runLateResult(saveMode);
  }

  {
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    await context.addInitScript(values => {
      for (const [key, value] of Object.entries(values)) localStorage.setItem(key, value);
    }, seed(11, 'token-a', 'a@pathome.test'));
    const page = await context.newPage();
    const calls = [];
    const errors = [];
    let saveDispatched;
    const saveInFlight = new Promise(resolve => { saveDispatched = resolve; });
    const controls = {
      holdMedia: true,
      holdSave: true,
      saveMode: 'success',
      dispatchSave: saveDispatched,
      savedDraft: {
        draftId: 'draft-batch-f4-late',
        draftType: 'BATCH',
        titleSummary: 'F4 late saved batch',
        protectedText: 'PRIVATE BATCH F4 TEXT',
        mediaId: 'f4-batch-media',
        payload: {
          rawPrompts: 'PRIVATE BATCH F4 TEXT',
          detectedCount: 1,
          activeCardId: 'f4-batch-card',
          mobileWorkspaceView: 'review',
          stagedCards: [{
            id: 'f4-batch-card',
            title: 'PRIVATE BATCH F4 CARD',
            description: 'PRIVATE BATCH F4 DESCRIPTION',
            bhk: '2 BHK',
            type: 'FLAT',
            city: 'Indore',
            sector: 'Vijay Nagar',
            rentAmount: 22000,
            isValid: true,
            stagedMedia: [{
              id: 'f4-batch-media',
              previewUrl: '/private-f4-batch.jpg',
              roomTag: 'LIVING_ROOM',
              isCover: true,
              originalFilename: 'PRIVATE-F4-BATCH.jpg',
              contentType: 'image/jpeg'
            }],
            localPhotoPreviews: ['/private-f4-batch.jpg']
          }]
        },
        media: [{
          draftId: 'draft-batch-f4-late',
          cardId: 'f4-batch-card',
          mediaId: 'f4-batch-media',
          originalFilename: 'PRIVATE-F4-BATCH.jpg',
          contentType: 'image/jpeg',
          roomTag: 'LIVING_ROOM',
          isCover: true
        }]
      }
    };
    page.on('pageerror', error => errors.push(error.stack || error.message));
    await installRoutes(page, calls, controls);
    await page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
    await page.getByPlaceholder(/Type or edit property details/).waitFor({ timeout: 20000 });
    await page.getByRole('button', { name: /Drafts/ }).first().click();
    await page.getByText(controls.savedDraft.titleSummary, { exact: true }).click();
    const description = page.getByRole('textbox', { name: 'Value included Description' });
    await description.waitFor({ timeout: 10000 });
    await Promise.race([
      saveInFlight,
      new Promise((_, reject) => setTimeout(() => reject(new Error('batch autosave was not dispatched')), 5000))
    ]);
    await description.fill('PRIVATE BATCH F4 EDITED DESCRIPTION');
    check('[batch] autosave is in flight before terminal media denial', true);

    const mediaResponse = page.waitForResponse(response =>
      new URL(response.url()).pathname.endsWith('/media/f4-batch-media'));
    controls.releaseMedia();
    await mediaResponse;
    await page.waitForTimeout(400);
    const callsAtDenial = calls.length;
    const denialBody = await page.locator('body').innerText();
    check('[batch] terminal denial withdraws protected cards and prompt',
      !denialBody.includes('PRIVATE BATCH F4 CARD')
      && !denialBody.includes('PRIVATE BATCH F4 EDITED DESCRIPTION')
      && !denialBody.includes('PRIVATE-F4-BATCH.jpg'));
    check('[batch] terminal denial is visible before the late response',
      /access denied|no longer have permission|do not have permission/i.test(denialBody));

    controls.releaseSave();
    await page.waitForTimeout(900);
    const body = await page.locator('body').innerText();
    check('[batch] late save cannot restore Saved after terminal denial',
      await page.getByText('Saved', { exact: true }).count() === 0);
    check('[batch] terminal permission feedback persists after the late response',
      /access denied|no longer have permission|do not have permission/i.test(body));
    check('[batch] late completion cannot dispatch a protected draft-list refresh',
      calls.slice(callsAtDenial).every(call => call.path !== '/api/v1/admin/drafts'));
    check('[batch] late save cannot restore protected cards or media',
      !body.includes('PRIVATE BATCH F4 CARD')
      && !body.includes('PRIVATE BATCH F4 EDITED DESCRIPTION')
      && !body.includes('PRIVATE-F4-BATCH.jpg'));
    check('[batch] late callback leaves no uncaught page error',
      errors.length === 0, errors.join(' | '));
    await context.close();
  }

  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  await context.addInitScript(values => {
    for (const [key, value] of Object.entries(values)) localStorage.setItem(key, value);
  }, seed(11, 'token-a', 'a@pathome.test'));
  const page = await context.newPage();
  const calls = [];
  const errors = [];
  let saveDispatched;
  let parseDispatched;
  const saveInFlight = new Promise(resolve => { saveDispatched = resolve; });
  const parseInFlight = new Promise(resolve => { parseDispatched = resolve; });
  const controls = {
    holdSave: true,
    holdParse: true,
    saveMode: 'denied',
    dispatchSave: saveDispatched,
    dispatchParse: parseDispatched
  };
  page.on('pageerror', error => errors.push(error.stack || error.message));
  await installRoutes(page, calls, controls);
  await page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
  const prompt = page.getByPlaceholder(/Type or edit property details/);
  await prompt.waitFor({ timeout: 20000 });
  await prompt.fill('2 BHK private parse race in Vijay Nagar');
  await Promise.race([
    saveInFlight,
    new Promise((_, reject) => setTimeout(() => reject(new Error('autosave was not dispatched')), 5000))
  ]);
  await page.getByRole('button', { name: /Review property details/i }).click();
  await Promise.race([
    parseInFlight,
    new Promise((_, reject) => setTimeout(() => reject(new Error('parse was not dispatched')), 5000))
  ]);
  const callsAtDenial = calls.length;
  controls.releaseSave();
  await page.waitForTimeout(500);
  const denialBody = await page.locator('body').innerText();
  check('[parse overlap] current-save denial wins before parse completion',
    /access denied|no longer have permission|do not have permission/i.test(denialBody));
  controls.releaseParse();
  await page.waitForTimeout(900);
  const body = await page.locator('body').innerText();
  check('[parse overlap] held private parse result cannot render after denial',
    !body.includes('PRIVATE HELD PARSE TITLE'));
  check('[parse overlap] held parse cannot clear the terminal denial',
    /access denied|no longer have permission|do not have permission/i.test(body));
  check('[parse overlap] no protected read is dispatched after terminal denial',
    calls.slice(callsAtDenial).every(call => call.path !== '/api/v1/admin/drafts'));
  check('[parse overlap] parse completion leaves no uncaught page error',
    errors.length === 0, errors.join(' | '));
  await context.close();
} finally {
  await browser.close();
}

console.log(`checks=${checks} failures=${failures}`);
if (failures > 0) process.exitCode = 1;
