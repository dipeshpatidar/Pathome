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
