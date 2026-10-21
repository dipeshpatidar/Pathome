import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';
import {
  captureUploaderSession,
  isUploaderSessionChangedError
} from '../utils/uploaderSession.ts';

const frontendRoot = fileURLToPath(new URL('../..', import.meta.url));
const vite = await createServer({
  root: frontendRoot,
  configFile: false,
  appType: 'custom',
  logLevel: 'silent',
  server: { middlewareMode: true }
});
const { draftService } = await vite.ssrLoadModule('/src/services/draftService.ts');
const { ApiRequestError } = await vite.ssrLoadModule('/src/services/apiError.ts');
test.after(async () => {
  await vite.close();
});

const profile = (id, email) => ({ id, email, role: 'ADMIN', fullName: `Admin ${id}` });

function installSession(id = 11, token = 'token-a', email = 'a@pathome.test') {
  const values = new Map([
    ['pathome_auth_token', token],
    ['pathome_role', 'ADMIN'],
    ['pathome_user', JSON.stringify(profile(id, email))]
  ]);
  globalThis.localStorage = {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: key => values.delete(key),
    clear: () => values.clear(),
    get length() { return values.size; },
    key: index => [...values.keys()][index] ?? null
  };
}

const media = count => Array.from({ length: count }, (_, index) => ({
  mediaId: `media-${index}`,
  draftId: 'draft-single-denial',
  originalFilename: `photo-${index}.jpg`,
  fileSizeBytes: 4,
  contentType: 'image/jpeg',
  roomTag: 'LIVING_ROOM',
  isCover: index === 0,
  previewUrl: '/private/photo.jpg',
  createdAt: '2026-10-21T00:00:00Z'
}));

const successResponse = () => new Response(new Uint8Array([1, 2, 3, 4]), {
  status: 200,
  headers: { 'Content-Type': 'image/jpeg' }
});

const deniedResponse = status => new Response(JSON.stringify({ message: 'Denied' }), {
  status,
  headers: { 'Content-Type': 'application/json' }
});

const heldResponse = signal => new Promise((_resolve, reject) => {
  const abort = () => reject(new DOMException('Aborted', 'AbortError'));
  if (signal?.aborted) {
    abort();
    return;
  }
  signal?.addEventListener('abort', abort, { once: true });
});

const ownedOperation = () => {
  let active = true;
  const controller = new AbortController();
  return {
    signal: controller.signal,
    isActive: () => active,
    retire: (reason = 'permission-denied') => {
      active = false;
      controller.abort(reason);
    }
  };
};

const isRetired = error => error?.name === 'UploaderOperationRetiredError';

test('restore returns an empty ordered result without dispatching when there is no media', async () => {
  installSession();
  let calls = 0;
  globalThis.fetch = async () => {
    calls += 1;
    return successResponse();
  };

  const restored = await draftService.restoreMediaFiles([], undefined, captureUploaderSession());
  assert.deepEqual(restored.files, []);
  assert.equal(calls, 0);
  delete globalThis.fetch;
  delete globalThis.localStorage;
});

for (const total of [1, 3, 6]) {
  for (const status of [401, 403]) {
    test(`${total} restore workers stop on the first current-session ${status} denial`, async () => {
      installSession();
      const session = captureUploaderSession();
      const calls = [];
      const progress = [];
      let resolveDenial;
      const denialGate = new Promise(resolve => { resolveDenial = resolve; });

      globalThis.fetch = async (_url, options) => {
        const index = calls.length;
        calls.push(index);
        if (index === 0) {
          await denialGate;
          return deniedResponse(status);
        }
        return heldResponse(options.signal);
      };

      const restoration = draftService.restoreMediaFiles(
        media(total),
        (loaded, count) => progress.push([loaded, count]),
        session
      );
      await Promise.resolve();
      await Promise.resolve();
      assert.equal(calls.length, Math.min(total, 3));
      const progressBeforeDenial = progress.length;
      resolveDenial();

      await assert.rejects(restoration, error => {
        if (status === 401) {
          assert.ok(error instanceof ApiRequestError);
          assert.equal(error.status, status);
        } else {
          assert.ok(error instanceof ApiRequestError);
          assert.equal(error.status, status);
        }
        return true;
      });
      assert.equal(calls.length, Math.min(total, 3), 'no worker may claim work after terminal denial');
      await new Promise(resolve => setTimeout(resolve, 25));
      assert.equal(calls.length, Math.min(total, 3));
      assert.equal(progress.length, progressBeforeDenial, 'no progress callback may run after terminal denial');

      delete globalThis.fetch;
      delete globalThis.localStorage;
    });
  }
}

test('authorized restore preserves concurrency, original order, room tags and cover selection', async () => {
  installSession();
  const session = captureUploaderSession();
  const progress = [];
  globalThis.fetch = async url => {
    const index = Number(String(url).match(/media-(\d+)$/)?.[1] || 0);
    await new Promise(resolve => setTimeout(resolve, index % 2 === 0 ? 2 : 1));
    return successResponse();
  };

  const restored = await draftService.restoreMediaFiles(
    media(6),
    (loaded, total) => progress.push([loaded, total]),
    session
  );
  assert.equal(restored.files.length, 6);
  assert.deepEqual(restored.files.map(file => file.name),
    ['photo-0.jpg', 'photo-1.jpg', 'photo-2.jpg', 'photo-3.jpg', 'photo-4.jpg', 'photo-5.jpg']);
  assert.equal(restored.coverIndex, 0);
  assert.equal(restored.tags[0], 'LIVING_ROOM');
  assert.equal(restored.mediaIds[5], 'media-5');
  assert.equal(restored.byMediaId.size, 6);
  assert.deepEqual(progress.at(-1), [6, 6]);
  delete globalThis.fetch;
  delete globalThis.localStorage;
});

test('one ordinary transport failure retains legitimate partial recovery', async () => {
  installSession();
  const session = captureUploaderSession();
  globalThis.fetch = async url => {
    if (url.endsWith('media-2')) throw new TypeError('network interrupted');
    return successResponse();
  };

  const restored = await draftService.restoreMediaFiles(media(6), undefined, session);
  assert.equal(restored.files.length, 5);
  assert.equal(restored.byMediaId.has('media-2'), false);
  delete globalThis.fetch;
  delete globalThis.localStorage;
});

test('an abort-ignoring sibling success cannot commit after a terminal 403', async () => {
  installSession();
  const session = captureUploaderSession();
  const progress = [];
  const calls = [];
  const lateReleases = [];
  let releaseDenial;
  const denialGate = new Promise(resolve => { releaseDenial = resolve; });

  globalThis.fetch = async () => {
    const index = calls.length;
    calls.push(index);
    if (index === 0) {
      await denialGate;
      return deniedResponse(403);
    }
    return new Promise(resolve => lateReleases.push(() => resolve(successResponse())));
  };

  const restoration = draftService.restoreMediaFiles(
    media(6),
    (loaded, count) => progress.push([loaded, count]),
    session
  );
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(calls.length, 3);
  const progressBeforeDenial = progress.length;
  releaseDenial();
  await assert.rejects(restoration, error => error instanceof ApiRequestError && error.status === 403);

  lateReleases.forEach(release => release());
  await new Promise(resolve => setTimeout(resolve, 25));
  assert.equal(progress.length, progressBeforeDenial,
    'no sibling progress may commit after terminal denial');
  assert.equal(calls.length, 3, 'no worker may claim additional media after terminal denial');
  delete globalThis.fetch;
  delete globalThis.localStorage;
});

test('a replacement credential cancels old restore work without invalidating the replacement', async () => {
  installSession(11, 'token-a', 'a@pathome.test');
  const session = captureUploaderSession();
  const releaseHeldFetches = [];
  globalThis.fetch = async (_url, options) => new Promise((resolve, reject) => {
    const abort = () => reject(new DOMException('Aborted', 'AbortError'));
    options.signal?.addEventListener('abort', abort, { once: true });
    releaseHeldFetches.push(() => resolve(successResponse()));
  });
  const restoration = draftService.restoreMediaFiles(media(3), undefined, session);
  await Promise.resolve();

  installSession(11, 'token-b', 'a@pathome.test');
  releaseHeldFetches.forEach(release => release());
  await assert.rejects(restoration, error => {
    if (isUploaderSessionChangedError(error) || isRetired(error)) return true;
    assert.ok(error instanceof ApiRequestError);
    assert.equal(error.status, 401);
    return true;
  });
  assert.equal(globalThis.localStorage.getItem('pathome_auth_token'), 'token-b');
  delete globalThis.fetch;
  delete globalThis.localStorage;
});

for (const total of [1, 3, 6]) {
  test(`external uploader retirement stops ${total} restore workers before another queue claim`, async () => {
    installSession();
    const session = captureUploaderSession();
    const ownership = ownedOperation();
    const calls = [];
    const progress = [];
    const lateReleases = [];

    globalThis.fetch = async () => {
      const index = calls.length;
      calls.push(index);
      return new Promise(resolve => lateReleases.push(() => resolve(successResponse())));
    };

    const restoration = draftService.restoreMediaFiles(
      media(total),
      (loaded, count) => progress.push([loaded, count]),
      session,
      ownership
    );
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(calls.length, Math.min(total, 3));
    const progressBeforeRetirement = progress.length;
    ownership.retire();

    await assert.rejects(restoration, error => isRetired(error));
    lateReleases.forEach(release => release());
    await new Promise(resolve => setTimeout(resolve, 25));
    assert.equal(calls.length, Math.min(total, 3),
      'a retired uploader operation cannot claim another media item');
    assert.equal(progress.length, progressBeforeRetirement,
      'a retired uploader operation cannot emit late progress');

    delete globalThis.fetch;
    delete globalThis.localStorage;
  });
}

test('external retirement settles promptly when an abort-ignoring sibling never resolves', async () => {
  installSession();
  const session = captureUploaderSession();
  const ownership = ownedOperation();
  const calls = [];

  globalThis.fetch = async () => {
    calls.push(calls.length);
    return new Promise(() => {});
  };
  const restoration = draftService.restoreMediaFiles(media(6), undefined, session, ownership);
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(calls.length, 3);
  ownership.retire();

  await Promise.race([
    assert.rejects(restoration, error => isRetired(error)),
    new Promise((_, reject) => setTimeout(() => reject(new Error('restoration did not settle')), 200))
  ]);
  assert.equal(calls.length, 3);

  delete globalThis.fetch;
  delete globalThis.localStorage;
});

test('an earlier authorized result is retained while external retirement drops late siblings', async () => {
  installSession();
  const session = captureUploaderSession();
  const ownership = ownedOperation();
  const calls = [];
  const progress = [];
  const lateReleases = [];
  let releaseFirst;
  const firstGate = new Promise(resolve => { releaseFirst = resolve; });

  globalThis.fetch = async () => {
    const index = calls.length;
    calls.push(index);
    if (index === 0) {
      await firstGate;
      return successResponse();
    }
    return new Promise(resolve => lateReleases.push(() => resolve(successResponse())));
  };

  const restoration = draftService.restoreMediaFiles(
    media(6),
    (loaded, total) => progress.push([loaded, total]),
    session,
    ownership
  );
  await Promise.resolve();
  await Promise.resolve();
  releaseFirst();
  await new Promise(resolve => setTimeout(resolve, 5));
  assert.deepEqual(progress[0], [1, 6]);

  const callsAtRetirement = calls.length;
  ownership.retire();
  await assert.rejects(restoration, error => isRetired(error));
  const progressAtRetirement = progress.length;
  lateReleases.forEach(release => release());
  await new Promise(resolve => setTimeout(resolve, 25));
  assert.equal(progress.length, progressAtRetirement);
  assert.equal(calls.length, callsAtRetirement);

  delete globalThis.fetch;
  delete globalThis.localStorage;
});
