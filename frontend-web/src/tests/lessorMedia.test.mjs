import test from 'node:test';
import assert from 'node:assert/strict';
import { hasCoverImage, mediaUrl, movedMediaIds } from '../utils/lessorMedia.ts';

const image = (id, cover = false) => ({ mediaId: id, contentType: 'image/jpeg', status: 'UPLOADED', cover });

test('video cannot satisfy photo cover requirement', () => {
  assert.equal(hasCoverImage([{ mediaId: 'v', contentType: 'video/mp4', status: 'UPLOADED', cover: true }]), false);
  assert.equal(hasCoverImage([image('a', true)]), true);
  assert.equal(hasCoverImage([{ ...image('a', true), status: 'FAILED' }]), false);
});

test('move controls preserve every uploaded media id exactly once', () => {
  const items = [image('a', true), image('b'), { ...image('failed'), status: 'FAILED' }];
  assert.deepEqual(movedMediaIds(items, 1, -1), ['b', 'a']);
  assert.equal(movedMediaIds(items, 0, -1), null);
  assert.equal(movedMediaIds(items, 1, 1), null);
});

test('privately staged guest photo can be the cover and keeps its order', () => {
  const staged = [{ ...image('a', true), status: 'STAGED' }, { ...image('b'), status: 'STAGED' }];
  assert.equal(hasCoverImage(staged), true);
  assert.deepEqual(movedMediaIds(staged, 1, -1), ['b', 'a']);
  assert.equal(hasCoverImage([{ ...staged[0], contentType: 'video/mp4' }]), false);
});

test('private guest media uses the configured API host when the frontend is separate', () => {
  const path = '/api/v1/lessor/guest/drafts/guest-1/media/photo-1/content';
  assert.equal(mediaUrl(path, 'http://localhost:8081/api/v1'),
    'http://localhost:8081/api/v1/lessor/guest/drafts/guest-1/media/photo-1/content');
  assert.equal(mediaUrl(path, '/api/v1'), path);
});

test('generateMediaId produces a valid RFC 4122 UUID', async () => {
  const { generateMediaId } = await import('../utils/lessorMedia.ts');
  const id = generateMediaId();
  assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
});

test('normalizeMediaType normalizes mobile mime types and resolves from extension', async () => {
  const { normalizeMediaType } = await import('../utils/lessorMedia.ts');
  assert.deepEqual(normalizeMediaType({ name: 'photo.jpg', type: 'image/jpg' }), {
    mime: 'image/jpeg',
    isVideo: false,
    supported: true
  });
  assert.deepEqual(normalizeMediaType({ name: 'photo.jpeg', type: 'image/jpeg; charset=UTF-8' }), {
    mime: 'image/jpeg',
    isVideo: false,
    supported: true
  });
  assert.deepEqual(normalizeMediaType({ name: 'IMG_001.JPG', type: '' }), {
    mime: 'image/jpeg',
    isVideo: false,
    supported: true
  });
  assert.deepEqual(normalizeMediaType({ name: 'clip.mp4', type: '' }), {
    mime: 'video/mp4',
    isVideo: true,
    supported: true
  });
  assert.deepEqual(normalizeMediaType({ name: 'doc.pdf', type: 'application/pdf' }), {
    mime: 'application/pdf',
    isVideo: false,
    supported: false
  });
});

test('classifyMediaError maps failures to truthful actionable user messages', async () => {
  const { classifyMediaError } = await import('../utils/lessorMedia.ts');
  // Size errors
  assert.deepEqual(classifyMediaError(null, { size: 11 * 1024 * 1024 }, false), {
    message: 'This image is too large. Maximum size is 10 MB.',
    retryable: false
  });
  assert.deepEqual(classifyMediaError(null, { size: 101 * 1024 * 1024 }, true), {
    message: 'This video is too large. Maximum size is 100 MB.',
    retryable: false
  });
  // Empty / corrupt
  assert.deepEqual(classifyMediaError(null, { size: 0 }, false), {
    message: "We couldn't read this file. Choose another file.",
    retryable: false
  });
  // Format error from backend 400
  assert.deepEqual(classifyMediaError({ status: 400, message: 'Choose a supported photo or video format' }, { size: 1000 }, false), {
    message: "This file type isn't supported. Choose another image.",
    retryable: false
  });
  assert.deepEqual(classifyMediaError({ status: 400, message: 'Unsupported format' }, { size: 1000 }, true), {
    message: "This video format isn't supported. Choose another video.",
    retryable: false
  });
  // Network interruption
  assert.deepEqual(classifyMediaError(new Error('Failed to fetch'), { size: 1000 }, false), {
    message: 'Upload was interrupted. Check your connection and try again.',
    retryable: true
  });
  // Server error / timeout
  assert.deepEqual(classifyMediaError({ status: 500, message: 'Internal error' }, { size: 1000 }, false), {
    message: "We couldn't upload this file right now. Try again.",
    retryable: true
  });
  // Session expired
  assert.deepEqual(classifyMediaError({ status: 401 }, { size: 1000 }, false), {
    message: 'Your session has expired. Sign in again to continue.',
    retryable: false
  });
  // Permission denied
  assert.deepEqual(classifyMediaError({ status: 403 }, { size: 1000 }, false), {
    message: "You don't have permission to upload media to this property.",
    retryable: false
  });
});
