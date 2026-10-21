/**
 * Controlled Chromium regression for the uploader P4 session boundary.
 *
 * This runner uses the real built React app, real fetch/localStorage/event handling, and a deterministic
 * API stub. It verifies account-bound requests and UI withdrawal under held responses. It does not claim
 * genuine backend business success; listing creation is covered separately by backend tests.
 */
import { readFile } from 'node:fs/promises';
import { extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;
const DIST = fileURLToPath(new URL('../../dist/', import.meta.url));
const ORIGIN = 'http://uploader.local';
const BUDGET_MS = 8 * 60 * 1000;
const STARTED_AT = Date.now();
const budgetLeft = () => BUDGET_MS - (Date.now() - STARTED_AT);

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
  if (ok) console.log(`  PASS  ${label}`);
  else {
    failures += 1;
    console.log(`  FAIL  ${label}${detail ? ` (${detail})` : ''}`);
  }
};

const profile = (id, email) => ({ id, email, role: 'ADMIN', fullName: `Admin ${id}` });
const seed = (id, token, email) => ({
  pathome_auth_token: token,
  pathome_role: 'ADMIN',
  pathome_user: JSON.stringify(profile(id, email)),
  pathome_active_admin_tab: 'media'
});

const json = (route, status, body, headers = {}) => route.fulfill({
  status,
  contentType: 'application/json',
  headers: { 'Cache-Control': 'no-store', ...headers },
  body: JSON.stringify(body)
});

const prepareContext = async (browser, session, viewport = { width: 1280, height: 900 }) => {
  const context = await browser.newContext({ viewport });
  await context.addInitScript((values) => {
    for (const [key, value] of Object.entries(values)) localStorage.setItem(key, value);
  }, session);
  const page = await context.newPage();
  const network = [];
  const errors = [];
  page.on('pageerror', error => errors.push(error.stack || error.message));
  return { context, page, network, errors };
};

const installRoutes = async ({ page, network }, options = {}) => {
  let parseResolve;
  let parseMode = options.parseMode || 'success';
  const parseGate = new Promise(resolve => { parseResolve = resolve; });
  let draftSaveResolve;
  const draftSaveGate = new Promise(resolve => { draftSaveResolve = resolve; });
  let newDraftSaveResolve;
  const newDraftSaveGate = new Promise(resolve => { newDraftSaveResolve = resolve; });
  let heldDraftSaveRequest = null;
  let draftSaveMode = options.draftSaveMode || 'success';

  await page.route('**/*', async route => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.origin !== ORIGIN) return route.continue();

    if (url.pathname.startsWith('/api/')) {
      const auth = request.headers().authorization || '';
      network.push({ method: request.method(), path: url.pathname, auth, body: request.postData() || '' });

      if (url.pathname === '/api/v1/properties/parse-prompt') {
        if (options.holdParse) await parseGate;
        if (parseMode === 'stale-401') {
          return json(route, 401, { message: 'Unauthorized' });
        }
        if (parseMode === 'denied') {
          return json(route, 403, { message: 'Forbidden' });
        }
        return json(route, 200, {
          title: 'PRIVATE ACCOUNT A TITLE',
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
      if (url.pathname === '/api/v1/admin/drafts' && request.method() === 'GET') {
        return json(route, 200, options.savedDraft ? [{
          draftId: options.savedDraft.draftId,
          draftType: options.savedDraft.draftType || 'SINGLE',
          status: 'DRAFT',
          titleSummary: options.savedDraft.titleSummary,
          itemCount: 1,
          version: 4,
          mediaCount: options.savedDraft.media.length,
          createdAt: '2026-10-21T00:00:00Z',
          updatedAt: '2026-10-21T00:05:00Z'
        }] : []);
      }
      if (url.pathname === '/api/v1/admin/drafts' && request.method() === 'POST') {
        if (options.holdDraftSave && auth === 'Bearer token-a') {
          heldDraftSaveRequest = request.postDataJSON();
          await draftSaveGate;
        }
        if (options.holdNewDraftSave && auth === 'Bearer token-b') {
          await newDraftSaveGate;
        }
        if (draftSaveMode === 'stale-401') {
          return json(route, 401, { message: 'Unauthorized' });
        }
        if (draftSaveMode === 'denied') {
          return json(route, 403, { message: 'Forbidden' });
        }
        if (draftSaveMode === 'conflict') {
          return json(route, 409, { message: 'Draft was modified elsewhere.' });
        }
        if (draftSaveMode === 'network') {
          return route.abort('failed');
        }
        const body = request.postDataJSON() || {};
        return json(route, 200, {
          draftId: body.draftId,
          draftType: body.draftType || 'SINGLE',
          status: body.status || 'DRAFT',
          titleSummary: body.titleSummary || 'Property draft',
          itemCount: body.itemCount || 1,
          version: Number(body.version || 0) + 1,
          payload: body.payload || '{}',
          media: []
        });
      }
      if (options.savedDraft
          && url.pathname === `/api/v1/admin/drafts/${options.savedDraft.draftId}`
          && request.method() === 'GET') {
        return json(route, 200, {
          draftId: options.savedDraft.draftId,
          draftType: options.savedDraft.draftType || 'SINGLE',
          status: 'DRAFT',
          titleSummary: options.savedDraft.titleSummary,
          itemCount: 1,
          version: 4,
          payload: JSON.stringify(options.savedDraft.payload || {
            newBhkLabel: options.savedDraft.protectedText,
            attachedMediaTags: { 0: 'LIVING_ROOM' },
            coverPhotoIndex: 0
          }),
          media: options.savedDraft.media,
          createdAt: '2026-10-21T00:00:00Z',
          updatedAt: '2026-10-21T00:05:00Z'
        });
      }
      if (options.draftGetMode === 'denied'
          && /^\/api\/v1\/admin\/drafts\/[^/]+$/.test(url.pathname)
          && request.method() === 'GET') {
        return json(route, 403, { message: 'Forbidden' });
      }
      if (options.savedDraft
          && options.savedDraft.media.some(item =>
            url.pathname === `/api/v1/admin/drafts/${item.draftId}/media/${item.mediaId}`)) {
        return json(route, options.savedDraft.mediaDenialStatus || 403, { message: 'Denied' });
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
    releaseParse: () => parseResolve(),
    releaseDraftSave: () => draftSaveResolve(),
    releaseNewDraftSave: () => newDraftSaveResolve(),
    heldDraftSaveRequest: () => heldDraftSaveRequest,
    setParseMode: mode => { parseMode = mode; },
    setDraftSaveMode: mode => { draftSaveMode = mode; }
  };
};

const openUploader = async (env) => {
  const controls = await installRoutes(env);
  await env.page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
  await env.page.getByPlaceholder(/Type or edit property details/).waitFor({ timeout: 20000 });
  return controls;
};

const browser = await chromium.launch({ channel: 'chromium' });
try {
  console.log('Property uploader controlled session verification');

  console.log('\n=== A. held parse cannot render inside the next account ===');
  {
    const env = await prepareContext(browser, seed(11, 'token-a', 'a@pathome.test'));
    const controls = await installRoutes(env, { holdParse: true });
    await env.page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
    const prompt = env.page.getByPlaceholder(/Type or edit property details/);
    await prompt.waitFor({ timeout: 20000 });
    const text = '2 BHK in Vijay Nagar, private account A details';
    await prompt.fill(text);
    await env.page.getByRole('button', { name: /Review property details/ }).click();
    await env.page.waitForTimeout(250);
    const parseRequest = env.network.find(call => call.path === '/api/v1/properties/parse-prompt');
    check('parse request used account A credential', parseRequest?.auth === 'Bearer token-a', parseRequest?.auth);

    await env.page.evaluate((next) => {
      for (const [key, value] of Object.entries(next)) localStorage.setItem(key, value);
      window.dispatchEvent(new Event('pathome_auth_changed'));
    }, seed(22, 'token-b', 'b@pathome.test'));
    await env.page.waitForTimeout(400);
    controls.releaseParse();
    await env.page.waitForTimeout(800);

    const body = await env.page.locator('body').innerText();
    check('account A private parse result is not rendered for account B', !body.includes('PRIVATE ACCOUNT A TITLE'));
    check('account A prompt buffer is withdrawn from account B', !(await prompt.inputValue()).includes('account A details'));
    check('no later draft request borrowed account A data with account B token',
      env.network.filter(call => call.auth === 'Bearer token-b' && call.body.includes('account A details')).length === 0);
    check('no uncaught page error', env.errors.length === 0, env.errors.join(' | '));
    await env.context.close();
  }

  console.log('\n=== B. stale 401 cannot expire the current account ===');
  {
    const env = await prepareContext(browser, seed(11, 'token-a', 'a@pathome.test'));
    const controls = await installRoutes(env, { holdParse: true, parseMode: 'stale-401' });
    await env.page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
    const prompt = env.page.getByPlaceholder(/Type or edit property details/);
    await prompt.waitFor({ timeout: 20000 });
    await prompt.fill('2 BHK private account A');
    await env.page.getByRole('button', { name: /Review property details/ }).click();
    await env.page.evaluate((next) => {
      for (const [key, value] of Object.entries(next)) localStorage.setItem(key, value);
      window.dispatchEvent(new Event('pathome_auth_changed'));
    }, seed(22, 'token-b', 'b@pathome.test'));
    controls.releaseParse();
    await env.page.waitForTimeout(800);
    check('stale account A 401 leaves account B token intact',
      await env.page.evaluate(() => localStorage.getItem('pathome_auth_token')) === 'token-b');
    check('stale account A 401 leaves account B identity intact',
      JSON.parse(await env.page.evaluate(() => localStorage.getItem('pathome_user')))?.id === 22);
    await env.context.close();
  }

  console.log('\n=== C. denied parse is not presented as offline ===');
  {
    const env = await prepareContext(browser, seed(11, 'token-a', 'a@pathome.test'));
    const controls = await installRoutes(env, { parseMode: 'denied' });
    await env.page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
    const prompt = env.page.getByPlaceholder(/Type or edit property details/);
    await prompt.waitFor({ timeout: 20000 });
    await prompt.fill('2 BHK denied request');
    await env.page.waitForTimeout(150);
    const deniedResponse = env.page.waitForResponse(response =>
      new URL(response.url()).pathname === '/api/v1/properties/parse-prompt');
    await env.page.getByRole('button', { name: /Review property details/ }).click();
    await deniedResponse;
    await env.page.getByText(/You do not have permission to complete this action/i)
      .first().waitFor({ timeout: 5000 }).catch(() => {});
    await env.page.waitForTimeout(300);
    const body = await env.page.locator('body').innerText();
    check('403 denial request reached the API', env.network.some(call =>
      call.path === '/api/v1/properties/parse-prompt' && call.auth === 'Bearer token-a'));
    check('403 presents a permission denial', /do not have permission/i.test(body), body.slice(-700));
    check('403 is never labelled as offline recovery', !/Offline.*saved locally/i.test(body));
    check('403 keeps the current account credential', await env.page.evaluate(() => localStorage.getItem('pathome_auth_token')) === 'token-a');
    controls.setParseMode('success');
    await env.context.close();
  }

  for (const denialStatus of [403, 401]) {
    console.log(`\n=== C${denialStatus}. current-session media ${denialStatus} withdraws protected draft state ===`);
    const draftId = `draft-single-media-denial-${denialStatus}`;
    const protectedText = `PRIVATE RESTORED TEXT MUST WITHDRAW ON CURRENT MEDIA ${denialStatus}`;
    const titleSummary = `Protected denied draft ${denialStatus}`;
    const activePointerKey = 'pathome_active_draft_single_a@pathome.test';
    const savedDraft = {
      draftId,
      titleSummary,
      protectedText,
      mediaDenialStatus: denialStatus,
      media: [{
        mediaId: `media-denial-${denialStatus}`,
        draftId,
        originalFilename: 'PRIVATE-DENIED-PHOTO.jpg',
        fileSizeBytes: 4,
        contentType: 'image/jpeg',
        roomTag: 'LIVING_ROOM',
        isCover: true,
        previewUrl: '/private-denied-preview',
        createdAt: '2026-10-21T00:00:00Z'
      }]
    };
    const env = await prepareContext(browser, {
      ...seed(11, 'token-a', 'a@pathome.test'),
      [activePointerKey]: draftId
    });
    await installRoutes(env, { savedDraft });
    await env.page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
    await env.page.getByPlaceholder(/Type or edit property details/).waitFor({ timeout: 20000 });

    await env.page.evaluate(async ({ record, storageKey }) => {
      await new Promise((resolve, reject) => {
        const request = indexedDB.open('PathomeAdminDraftsDB', 1);
        request.onupgradeneeded = () => {
          const db = request.result;
          if (!db.objectStoreNames.contains('drafts')) {
            const store = db.createObjectStore('drafts', { keyPath: 'storageKey' });
            store.createIndex('by_admin', 'adminEmail', { unique: false });
            store.createIndex('by_updated', 'updatedAt', { unique: false });
          }
        };
        request.onerror = () => reject(request.error);
        request.onsuccess = () => {
          const tx = request.result.transaction('drafts', 'readwrite');
          tx.objectStore('drafts').put({ ...record, storageKey });
          tx.oncomplete = () => resolve();
          tx.onerror = () => reject(tx.error);
        };
      });
    }, {
      record: {
        draftId,
        adminEmail: 'a@pathome.test',
        draftType: 'SINGLE',
        titleSummary,
        itemCount: 1,
        version: 4,
        payload: { newBhkLabel: protectedText },
        updatedAt: Date.now()
      },
      storageKey: `v1:a@pathome.test:${draftId}`
    });

    await env.page.getByRole('button', { name: /Drafts/ }).first().click();
    const mediaResponse = env.page.waitForResponse(response =>
      new URL(response.url()).pathname.endsWith(`/media/media-denial-${denialStatus}`));
    await env.page.getByText(titleSummary, { exact: true }).click();
    await mediaResponse;
    await env.page.waitForTimeout(900);

    const body = await env.page.locator('body').innerText();
    const localTruth = await env.page.evaluate(async ({ pointerKey, storageKey }) => {
      const record = await new Promise(resolve => {
        const request = indexedDB.open('PathomeAdminDraftsDB', 1);
        request.onsuccess = () => {
          const tx = request.result.transaction('drafts', 'readonly');
          const get = tx.objectStore('drafts').get(storageKey);
          get.onsuccess = () => resolve(get.result || null);
          get.onerror = () => resolve('lookup-error');
        };
        request.onerror = () => resolve('open-error');
      });
      return {
        pointer: localStorage.getItem(pointerKey),
        record
      };
    }, {
      pointerKey: activePointerKey,
      storageKey: `v1:a@pathome.test:${draftId}`
    });

    check(`media ${denialStatus}: protected draft text is withdrawn`, !body.includes(protectedText));
    check(`media ${denialStatus}: protected filename and preview are withdrawn`,
      !body.includes('PRIVATE-DENIED-PHOTO.jpg'));
    check(`media ${denialStatus}: active local pointer is removed`, localTruth.pointer === null,
      String(localTruth.pointer));
    check(`media ${denialStatus}: matching local protected record is inaccessible`,
      localTruth.record === null, JSON.stringify(localTruth.record));
    check(`media ${denialStatus}: misleading Saved state is withdrawn`,
      await env.page.getByText('Saved', { exact: true }).count() === 0, body.slice(-700));
    if (denialStatus === 403) {
      check('media 403: accessible permission feedback remains',
        /access denied|no longer have permission|do not have permission/i.test(body), body.slice(-900));
    } else {
      check('media 401: session-expired feedback remains',
        /session has ended|sign in again|welcome back/i.test(body), body.slice(-900));
    }
    check(`media ${denialStatus}: no uncaught page error`, env.errors.length === 0, env.errors.join(' | '));
    await env.context.close();
  }

  console.log('\n=== C-409. nested 409 reconciliation denial follows terminal withdrawal ===');
  {
    const env = await prepareContext(browser, seed(11, 'token-a', 'a@pathome.test'));
    await installRoutes(env, { draftSaveMode: 'conflict', draftGetMode: 'denied' });
    await env.page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
    const prompt = env.page.getByPlaceholder(/Type or edit property details/);
    await prompt.waitFor({ timeout: 20000 });
    const reconciliationResponse = env.page.waitForResponse(response =>
      new URL(response.url()).pathname.startsWith('/api/v1/admin/drafts/')
      && response.request().method() === 'GET');
    await prompt.fill('PRIVATE 409 RECOVERY TEXT MUST WITHDRAW');
    const reconciliation = await reconciliationResponse;
    await env.page.waitForTimeout(700);
    const body = await env.page.locator('body').innerText();
    check('nested recovery GET reached the current-session denial', reconciliation.status() === 403,
      String(reconciliation.status()));
    check('nested recovery denial withdraws old editor text',
      !(await prompt.inputValue()).includes('PRIVATE 409 RECOVERY TEXT'));
    check('nested recovery denial removes the active local pointer',
      await env.page.evaluate(() =>
        localStorage.getItem('pathome_active_draft_single_a@pathome.test')) === null);
    check('nested recovery denial does not publish a misleading conflict or offline state',
      !/modified elsewhere|offline.*saved locally/i.test(body), body.slice(-800));
    check('nested recovery denial presents permission feedback',
      /access denied|no longer have permission|do not have permission/i.test(body), body.slice(-800));
    check('nested recovery denial preserves the current credential',
      await env.page.evaluate(() => localStorage.getItem('pathome_auth_token')) === 'token-a');
    check('nested recovery denial leaves no uncaught page error',
      env.errors.length === 0, env.errors.join(' | '));
    await env.context.close();
  }

  console.log('\n=== C-BATCH. background batch restoration denial withdraws cards, pointer and local record ===');
  {
    const draftId = 'draft-batch-media-denial-403';
    const protectedText = 'PRIVATE BATCH TEXT MUST WITHDRAW ON BACKGROUND MEDIA 403';
    const protectedCardTitle = 'PRIVATE BATCH CARD MUST WITHDRAW';
    const titleSummary = 'Protected denied batch draft';
    const activePointerKey = 'pathome_active_draft_batch_a@pathome.test';
    const savedDraft = {
      draftId,
      draftType: 'BATCH',
      titleSummary,
      payload: {
        rawPrompts: protectedText,
        detectedCount: 1,
        activeCardId: 'batch-card-1',
        mobileWorkspaceView: 'review',
        stagedCards: [{
          id: 'batch-card-1',
          title: protectedCardTitle,
          bhk: '2 BHK',
          city: 'Indore',
          sector: 'Vijay Nagar',
          rentAmount: 22000,
          isValid: true,
          stagedMedia: [{
            id: 'batch-media-denial',
            previewUrl: '/private-batch-denied-preview',
            roomTag: 'LIVING_ROOM',
            isCover: true,
            originalFilename: 'PRIVATE-BATCH-DENIED.jpg',
            contentType: 'image/jpeg'
          }],
          localPhotoPreviews: ['/private-batch-denied-preview']
        }]
      },
      mediaDenialStatus: 403,
      media: [{
        mediaId: 'batch-media-denial',
        draftId,
        cardId: 'batch-card-1',
        originalFilename: 'PRIVATE-BATCH-DENIED.jpg',
        fileSizeBytes: 4,
        contentType: 'image/jpeg',
        roomTag: 'LIVING_ROOM',
        isCover: true,
        previewUrl: '/private-batch-denied-preview',
        createdAt: '2026-10-21T00:00:00Z'
      }]
    };
    const env = await prepareContext(browser, {
      ...seed(11, 'token-a', 'a@pathome.test'),
      [activePointerKey]: draftId
    });
    await installRoutes(env, { savedDraft });
    await env.page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
    await env.page.getByPlaceholder(/Type or edit property details/).waitFor({ timeout: 20000 });
    await env.page.evaluate(async ({ record, storageKey }) => {
      await new Promise((resolve, reject) => {
        const request = indexedDB.open('PathomeAdminDraftsDB', 1);
        request.onupgradeneeded = () => {
          const db = request.result;
          if (!db.objectStoreNames.contains('drafts')) {
            const store = db.createObjectStore('drafts', { keyPath: 'storageKey' });
            store.createIndex('by_admin', 'adminEmail', { unique: false });
            store.createIndex('by_updated', 'updatedAt', { unique: false });
          }
        };
        request.onsuccess = () => {
          const tx = request.result.transaction('drafts', 'readwrite');
          tx.objectStore('drafts').put({ ...record, storageKey });
          tx.oncomplete = () => resolve();
          tx.onerror = () => reject(tx.error);
        };
        request.onerror = () => reject(request.error);
      });
    }, {
      record: {
        draftId,
        adminEmail: 'a@pathome.test',
        draftType: 'BATCH',
        titleSummary,
        itemCount: 1,
        version: 4,
        payload: { rawPrompts: protectedText },
        updatedAt: Date.now()
      },
      storageKey: `v1:a@pathome.test:${draftId}`
    });

    await env.page.getByRole('button', { name: /Drafts/ }).first().click();
    const mediaResponse = env.page.waitForResponse(response =>
      new URL(response.url()).pathname.endsWith('/media/batch-media-denial'));
    await env.page.getByText(titleSummary, { exact: true }).click();
    await mediaResponse;
    await env.page.waitForTimeout(900);
    const body = await env.page.locator('body').innerText();
    const localTruth = await env.page.evaluate(async ({ pointerKey, storageKey }) => {
      const record = await new Promise(resolve => {
        const request = indexedDB.open('PathomeAdminDraftsDB', 1);
        request.onsuccess = () => {
          const tx = request.result.transaction('drafts', 'readonly');
          const get = tx.objectStore('drafts').get(storageKey);
          get.onsuccess = () => resolve(get.result || null);
          get.onerror = () => resolve('lookup-error');
        };
        request.onerror = () => resolve('open-error');
      });
      return { pointer: localStorage.getItem(pointerKey), record };
    }, {
      pointerKey: activePointerKey,
      storageKey: `v1:a@pathome.test:${draftId}`
    });
    check('batch background denial withdraws protected prompt text', !body.includes(protectedText));
    check('batch background denial withdraws protected cards and filenames',
      !body.includes(protectedCardTitle) && !body.includes('PRIVATE-BATCH-DENIED.jpg'));
    check('batch background denial removes active local pointer', localTruth.pointer === null,
      String(localTruth.pointer));
    check('batch background denial makes local protected record inaccessible',
      localTruth.record === null, JSON.stringify(localTruth.record));
    check('batch background denial withdraws misleading Saved state',
      await env.page.getByText('Saved', { exact: true }).count() === 0, body.slice(-700));
    check('batch background denial presents permission feedback',
      /access denied|no longer have permission|do not have permission/i.test(body), body.slice(-900));
    check('batch background denial leaves no uncaught page error',
      env.errors.length === 0, env.errors.join(' | '));
    await env.context.close();
  }

  console.log('\n=== D. responsive uploader surfaces remain bounded ===');
  {
    const env = await prepareContext(browser, seed(11, 'token-a', 'a@pathome.test'));
    await installRoutes(env);
    await env.page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
    await env.page.getByPlaceholder(/Type or edit property details/).waitFor({ timeout: 20000 });
    for (const width of [320, 375, 390, 414, 430, 768, 1024, 1280, 1440]) {
      await env.page.setViewportSize({ width, height: 900 });
      await env.page.waitForTimeout(120);
      const metrics = await env.page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        innerWidth: window.innerWidth
      }));
      check(`no horizontal overflow at ${width}px`, metrics.scrollWidth <= metrics.innerWidth,
        `${metrics.scrollWidth} > ${metrics.innerWidth}`);
    }
    check('uploader prompt remains visible at 320px',
      await env.page.getByPlaceholder(/Type or edit property details/).isVisible());
    await env.context.close();
  }

  console.log('\n=== E. pending autosave is cancelled before the next account can send it ===');
  {
    const env = await prepareContext(browser, seed(11, 'token-a', 'a@pathome.test'));
    await installRoutes(env);
    await env.page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
    const prompt = env.page.getByPlaceholder(/Type or edit property details/);
    await prompt.waitFor({ timeout: 20000 });
    await prompt.fill('PRIVATE PENDING ACCOUNT A AUTOSAVE');
    await env.page.evaluate((next) => {
      for (const [key, value] of Object.entries(next)) localStorage.setItem(key, value);
      window.dispatchEvent(new Event('pathome_auth_changed'));
    }, seed(22, 'token-b', 'b@pathome.test'));
    await env.page.waitForTimeout(1300);
    const borrowedWrites = env.network.filter(call =>
      call.method === 'POST'
      && call.path === '/api/v1/admin/drafts'
      && call.auth === 'Bearer token-b'
      && call.body.includes('PRIVATE PENDING ACCOUNT A AUTOSAVE'));
    check('no pending account A save is transmitted with account B credential', borrowedWrites.length === 0,
      JSON.stringify(borrowedWrites));
    check('next-account token remains active after cleanup', await env.page.evaluate(() =>
      localStorage.getItem('pathome_auth_token')) === 'token-b');
    await env.context.close();
  }

  console.log('\n=== F. protected preview buffers are withdrawn on account change ===');
  {
    const env = await prepareContext(browser, seed(11, 'token-a', 'a@pathome.test'));
    await installRoutes(env);
    await env.page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
    await env.page.getByPlaceholder(/Type or edit property details/).waitFor({ timeout: 20000 });
    await env.page.getByRole('button', { name: /Add photos or video/ }).click();
    const fileInput = env.page.locator('input[type="file"]').last();
    await fileInput.setInputFiles({
      name: 'private-account-a.png',
      mimeType: 'image/png',
      buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl8Z2sAAAAASUVORK5CYII=', 'base64')
    });
    await env.page.waitForTimeout(500);
    check('account A preview buffer is present before switching',
      await env.page.locator('img[src^="blob:"]').count() > 0);
    await env.page.evaluate((next) => {
      for (const [key, value] of Object.entries(next)) localStorage.setItem(key, value);
      window.dispatchEvent(new Event('pathome_auth_changed'));
    }, seed(22, 'token-b', 'b@pathome.test'));
    await env.page.waitForTimeout(700);
    check('account A object URL is revoked after account B becomes active',
      await env.page.locator('img[src^="blob:"]').count() === 0);
    check('account A private filename is not visible in account B workspace',
      !(await env.page.locator('body').innerText()).includes('private-account-a.png'));
    await env.context.close();
  }

  console.log('\n=== F2. real cross-tab storage replacement cannot resurrect account A work ===');
  {
    const env = await prepareContext(browser, seed(11, 'token-a', 'a@pathome.test'));
    await installRoutes(env);
    await env.page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
    const prompt = env.page.getByPlaceholder(/Type or edit property details/);
    await prompt.waitFor({ timeout: 20000 });
    await prompt.fill('PRIVATE CROSS TAB ACCOUNT A WORK');

    const secondTab = await env.context.newPage();
    const secondNetwork = [];
    const secondErrors = [];
    await installRoutes({ context: env.context, page: secondTab, network: secondNetwork, errors: secondErrors });
    await secondTab.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
    await secondTab.evaluate(values => {
      for (const [key, value] of Object.entries(values)) localStorage.setItem(key, value);
    }, seed(22, 'token-b', 'b@pathome.test'));
    await env.page.waitForTimeout(800);

    check('storage-event account replacement withdraws account A prompt from the first tab',
      !(await prompt.inputValue()).includes('PRIVATE CROSS TAB ACCOUNT A WORK'));
    await prompt.fill('NEW CROSS TAB ACCOUNT B WORK');
    await env.page.waitForTimeout(1300);
    check('replacement tab generation saves only its own editor text',
      env.network.some(call => call.method === 'POST'
        && call.path === '/api/v1/admin/drafts'
        && call.auth === 'Bearer token-b'
        && call.body.includes('NEW CROSS TAB ACCOUNT B WORK')));
    check('old cross-tab editor text is never sent with the replacement credential',
      env.network.filter(call => call.method === 'POST'
        && call.path === '/api/v1/admin/drafts'
        && call.auth === 'Bearer token-b'
        && call.body.includes('PRIVATE CROSS TAB ACCOUNT A WORK')).length === 0);
    check('cross-tab replacement leaves the new account credential active',
      await env.page.evaluate(() => localStorage.getItem('pathome_auth_token')) === 'token-b');
    check('cross-tab replacement leaves no first-tab page error',
      env.errors.length === 0, env.errors.join(' | '));
    await secondTab.close();
    await env.context.close();
  }

  console.log('\n=== G. same-User token rotation reactivates autosave and fences old completion ===');
  {
    const env = await prepareContext(browser, seed(11, 'token-a', 'a@pathome.test'));
    const controls = await installRoutes(env, { holdDraftSave: true });
    await env.page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
    const prompt = env.page.getByPlaceholder(/Type or edit property details/);
    await prompt.waitFor({ timeout: 20000 });

    await prompt.fill('PRIVATE OLD GENERATION EDIT');
    await env.page.waitForTimeout(1100);
    check('old generation autosave was dispatched before rotation',
      env.network.some(call => call.method === 'POST'
        && call.path === '/api/v1/admin/drafts'
        && call.auth === 'Bearer token-a'
        && call.body.includes('PRIVATE OLD GENERATION EDIT')));

    await env.page.evaluate((next) => {
      for (const [key, value] of Object.entries(next)) localStorage.setItem(key, value);
      window.dispatchEvent(new Event('pathome_auth_changed'));
    }, seed(11, 'token-b', 'a@pathome.test'));
    await env.page.waitForTimeout(300);

    await prompt.fill('NEW GENERATION EDIT MUST SURVIVE');
    await env.page.waitForTimeout(1300);
    check('new generation autosave uses the rotated credential',
      env.network.some(call => call.method === 'POST'
        && call.path === '/api/v1/admin/drafts'
        && call.auth === 'Bearer token-b'
        && call.body.includes('NEW GENERATION EDIT MUST SURVIVE')));

    controls.releaseDraftSave();
    await env.page.waitForTimeout(500);
    check('old-generation completion cannot erase new editor text',
      (await prompt.inputValue()).includes('NEW GENERATION EDIT MUST SURVIVE'),
      await prompt.inputValue());
    check('old-generation payload was held against its original credential',
      controls.heldDraftSaveRequest()?.payload?.includes('PRIVATE OLD GENERATION EDIT') === true);
    check('no old payload is submitted with the rotated token',
      env.network.filter(call => call.method === 'POST'
        && call.path === '/api/v1/admin/drafts'
        && call.auth === 'Bearer token-b'
        && call.body.includes('PRIVATE OLD GENERATION EDIT')).length === 0);
    await env.context.close();
  }

  for (const [mode, label] of [
    ['stale-401', 'stale 401'],
    ['denied', 'stale 403'],
    ['network', 'stale network failure']
  ]) {
    console.log(`\n=== H. ${label} from the old generation cannot alter new-session work ===`);
    const env = await prepareContext(browser, seed(11, 'token-a', 'a@pathome.test'));
    const controls = await installRoutes(env, { holdDraftSave: true });
    await env.page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
    const prompt = env.page.getByPlaceholder(/Type or edit property details/);
    await prompt.waitFor({ timeout: 20000 });
    await prompt.fill(`OLD ${mode.toUpperCase()} GENERATION`);
    await env.page.waitForTimeout(1100);

    await env.page.evaluate((next) => {
      for (const [key, value] of Object.entries(next)) localStorage.setItem(key, value);
      window.dispatchEvent(new Event('pathome_auth_changed'));
    }, seed(11, 'token-b', 'a@pathome.test'));
    await env.page.waitForTimeout(300);
    await prompt.fill(`NEW ${mode.toUpperCase()} GENERATION`);
    await env.page.waitForTimeout(1200);
    check(`${label}: rotated autosave is dispatched`,
      env.network.some(call => call.method === 'POST'
        && call.path === '/api/v1/admin/drafts'
        && call.auth === 'Bearer token-b'
        && call.body.includes(`NEW ${mode.toUpperCase()} GENERATION`)));

    controls.setDraftSaveMode(mode);
    controls.releaseDraftSave();
    await env.page.waitForTimeout(600);
    check(`${label}: new editor text survives`,
      (await prompt.inputValue()).includes(`NEW ${mode.toUpperCase()} GENERATION`),
      await prompt.inputValue());
    check(`${label}: stale denial does not expire token2`,
      await env.page.evaluate(() => localStorage.getItem('pathome_auth_token')) === 'token-b');
    check(`${label}: no new-session permission or offline notice is fabricated`,
      !/Offline.*saved locally|no longer have permission/i.test(await env.page.locator('body').innerText()));
    await env.context.close();
  }

  console.log('\n=== I. old finalizer cannot clear a new-generation save in flight ===');
  {
    const env = await prepareContext(browser, seed(11, 'token-a', 'a@pathome.test'));
    const controls = await installRoutes(env, { holdDraftSave: true, holdNewDraftSave: true });
    await env.page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
    const prompt = env.page.getByPlaceholder(/Type or edit property details/);
    await prompt.waitFor({ timeout: 20000 });
    await prompt.fill('OLD GENERATION FINALIZER');
    await env.page.waitForTimeout(1100);
    await env.page.evaluate((next) => {
      for (const [key, value] of Object.entries(next)) localStorage.setItem(key, value);
      window.dispatchEvent(new Event('pathome_auth_changed'));
    }, seed(11, 'token-b', 'a@pathome.test'));
    await env.page.waitForTimeout(300);
    await prompt.fill('NEW GENERATION FIRST SAVE');
    await env.page.waitForTimeout(1200);
    check('new-generation first save is held in flight',
      env.network.filter(call => call.method === 'POST'
        && call.auth === 'Bearer token-b'
        && call.body.includes('NEW GENERATION FIRST SAVE')).length === 1);

    controls.releaseDraftSave();
    await env.page.waitForTimeout(500);
    await prompt.fill('NEW GENERATION SECOND EDIT');
    await env.page.waitForTimeout(1200);
    check('old completion does not start a concurrent new-generation save',
      env.network.filter(call => call.method === 'POST'
        && call.auth === 'Bearer token-b').length === 1);

    controls.releaseNewDraftSave();
    await env.page.waitForTimeout(1700);
    check('new-generation follow-up save completes after the held first save',
      env.network.some(call => call.method === 'POST'
        && call.auth === 'Bearer token-b'
        && call.body.includes('NEW GENERATION SECOND EDIT')));
    check('new-generation second edit remains intact',
      (await prompt.inputValue()).includes('NEW GENERATION SECOND EDIT'));
    await env.context.close();
  }
} finally {
  console.log(`\nchecks=${checks} failures=${failures} budget_left_ms=${budgetLeft()}`);
  await browser.close();
}

if (failures > 0) process.exitCode = 1;
