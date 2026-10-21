/**
 * Focused real-browser IndexedDB/localStorage ownership checks for terminal uploader cleanup.
 * These exercise the production storage module through Vite, not a mocked storage implementation.
 */
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;
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

const root = fileURLToPath(new URL('../..', import.meta.url));
const vite = await createServer({
  root,
  configFile: false,
  appType: 'spa',
  logLevel: 'silent',
  server: { host: '127.0.0.1', port: 0 }
});
await vite.listen();
const address = vite.httpServer.address();
const origin = `http://127.0.0.1:${address.port}`;

const browser = await chromium.launch({ channel: 'chromium' });
try {
  {
    const page = await browser.newPage();
    await page.goto(`${origin}/@vite/client`);
    const result = await page.evaluate(async () => {
      const { localDraftStorage } = await import('/src/services/localDraftStorage.ts');
      const email = 'owner@pathome.test';
      const draftId = 'draft-delayed-owner';
      await localDraftStorage.remove(email, draftId);
      await localDraftStorage.save(email, {
        draftId,
        adminEmail: email,
        draftType: 'SINGLE',
        titleSummary: 'Old denied record',
        itemCount: 1,
        version: 1,
        payload: { text: 'DENIED PRIVATE RECORD' },
        updatedAt: Date.now()
      });

      let ownerIsCurrent = true;
      const delayedCleanup = localDraftStorage.removeIf(email, draftId, () => ownerIsCurrent);
      ownerIsCurrent = false;
      await delayedCleanup;
      const afterReplacement = await localDraftStorage.get(email, draftId);

      await localDraftStorage.removeIf(email, draftId, () => true);
      const afterOwnedCleanup = await localDraftStorage.get(email, draftId);
      return {
        replacementRecordSurvives: afterReplacement?.payload?.text === 'DENIED PRIVATE RECORD',
        ownedCleanupRemovesRecord: afterOwnedCleanup === null
      };
    });
    check('delayed IndexedDB cleanup cannot delete replacement-owned data',
      result.replacementRecordSurvives);
    check('same-generation IndexedDB cleanup still removes the denied record',
      result.ownedCleanupRemovesRecord);
    await page.close();
  }

  {
    const context = await browser.newContext();
    const page = await context.newPage();
    await page.goto(`${origin}/@vite/client`);
    const result = await page.evaluate(async () => {
      const dbName = 'PathomeAdminDraftsDB';
      const storeName = 'drafts';
      const openDatabase = () => new Promise((resolve, reject) => {
        const request = indexedDB.open(dbName, 1);
        request.onupgradeneeded = () => {
          const db = request.result;
          if (!db.objectStoreNames.contains(storeName)) {
            const store = db.createObjectStore(storeName, { keyPath: 'storageKey' });
            store.createIndex('by_admin', 'adminEmail', { unique: false });
            store.createIndex('by_updated', 'updatedAt', { unique: false });
          }
        };
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
      });
      const writeRecord = async (db, record) => {
        await new Promise((resolve, reject) => {
          const tx = db.transaction(storeName, 'readwrite');
          tx.objectStore(storeName).put(record);
          tx.oncomplete = () => resolve();
          tx.onerror = () => reject(tx.error);
        });
      };

      const email = 'delayed-open@pathome.test';
      const draftId = 'draft-delayed-same-key';
      const storageKey = `v1:${email}:${draftId}`;
      const setupDb = await openDatabase();
      await writeRecord(setupDb, {
        storageKey,
        adminEmail: email,
        draftId,
        draftType: 'SINGLE',
        titleSummary: 'Original denied draft',
        itemCount: 1,
        version: 1,
        payload: { text: 'ORIGINAL DENIED DATA' },
        updatedAt: Date.now()
      });
      setupDb.close();

      const nativeIndexedDB = window.indexedDB;
      let releaseDelayedOpen = null;
      const nativeOpen = nativeIndexedDB.open.bind(nativeIndexedDB);
      const openNativeDatabase = () => new Promise((resolve, reject) => {
        const request = nativeOpen(dbName, 1);
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
      });
      const delayedIndexedDB = new Proxy(nativeIndexedDB, {
        get(target, property) {
          if (property === 'open') {
            return (...args) => {
              const request = nativeOpen(...args);
              return new Proxy(request, {
                get(requestTarget, requestProperty) {
                  const value = Reflect.get(requestTarget, requestProperty, requestTarget);
                  return typeof value === 'function' ? value.bind(requestTarget) : value;
                },
                set(requestTarget, requestProperty, value) {
                  if (requestProperty === 'onsuccess') {
                    requestTarget.onsuccess = event => {
                      releaseDelayedOpen = () => value.call(requestTarget, event);
                    };
                    return true;
                  }
                  return Reflect.set(requestTarget, requestProperty, value, requestTarget);
                }
              });
            };
          }
          const value = Reflect.get(target, property, target);
          return typeof value === 'function' ? value.bind(target) : value;
        }
      });
      Object.defineProperty(window, 'indexedDB', {
        configurable: true,
        value: delayedIndexedDB
      });
      if (window.indexedDB !== delayedIndexedDB) {
        throw new Error('IndexedDB proxy replacement failed');
      }

      const { localDraftStorage } = await import('/src/services/localDraftStorage.ts?delayed-open=1');
      let oldOwnerActive = true;
      const oldCleanup = localDraftStorage.remove(email, draftId, () => oldOwnerActive);
      for (let i = 0; i < 100 && !releaseDelayedOpen; i += 1) {
        await new Promise(resolve => setTimeout(resolve, 5));
      }
      if (!releaseDelayedOpen) throw new Error('delayed IndexedDB open was not installed');

      const replacementDb = await openNativeDatabase();
      await writeRecord(replacementDb, {
        storageKey,
        adminEmail: email,
        draftId,
        draftType: 'SINGLE',
        titleSummary: 'Replacement draft',
        itemCount: 1,
        version: 2,
        payload: { text: 'REPLACEMENT OWNED DATA' },
        updatedAt: Date.now() + 1
      });
      replacementDb.close();

      oldOwnerActive = false;
      releaseDelayedOpen();
      await oldCleanup;

      const verificationDb = await openNativeDatabase();
      const replacement = await new Promise((resolve, reject) => {
        const tx = verificationDb.transaction(storeName, 'readonly');
        const request = tx.objectStore(storeName).get(storageKey);
        request.onsuccess = () => resolve(request.result || null);
        request.onerror = () => reject(request.error);
      });
      verificationDb.close();
      return {
        replacementSurvivesDelayedOpen: replacement?.payload?.text === 'REPLACEMENT OWNED DATA'
      };
    });
    check('cleanup cannot erase replacement data after a held IndexedDB open',
      result.replacementSurvivesDelayedOpen);
    await context.close();
  }

  {
    const context = await browser.newContext();
    await context.addInitScript(() => {
      Object.defineProperty(window, 'indexedDB', {
        configurable: true,
        value: undefined
      });
    });
    const page = await context.newPage();
    await page.goto(origin);
    const result = await page.evaluate(async () => {
      const { localDraftStorage } = await import('/src/services/localDraftStorage.ts?fallback=1');
      const email = 'fallback@pathome.test';
      const draftId = 'draft-fallback-owner';
      localStorage.removeItem(`pathome_local_draft_v1:${email}:${draftId}`);
      await localDraftStorage.save(email, {
        draftId,
        adminEmail: email,
        draftType: 'SINGLE',
        titleSummary: 'Fallback record',
        itemCount: 1,
        version: 1,
        payload: { text: 'FALLBACK PRIVATE' },
        updatedAt: Date.now()
      });

      let ownerIsCurrent = true;
      const delayedCleanup = localDraftStorage.removeIf(email, draftId, () => ownerIsCurrent);
      ownerIsCurrent = false;
      await delayedCleanup;
      const fallbackRecordSurvives = Boolean(
        localStorage.getItem(`pathome_local_draft_v1:${email}:${draftId}`)
      );

      await localDraftStorage.removeIf(email, draftId, () => true);
      const fallbackRecordRemoved = localStorage.getItem(
        `pathome_local_draft_v1:${email}:${draftId}`
      ) === null;
      return { fallbackRecordSurvives, fallbackRecordRemoved };
    });
    check('delayed localStorage fallback cleanup preserves replacement-owned data',
      result.fallbackRecordSurvives);
    check('owned localStorage fallback cleanup still removes the denied record',
      result.fallbackRecordRemoved);
    await context.close();
  }
} finally {
  await browser.close();
  await vite.close();
}

console.log(`checks=${checks} failures=${failures}`);
if (failures > 0) process.exitCode = 1;
