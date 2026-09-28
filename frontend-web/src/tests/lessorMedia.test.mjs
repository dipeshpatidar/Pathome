import test from 'node:test';
import assert from 'node:assert/strict';
import { hasCoverImage, movedMediaIds } from '../utils/lessorMedia.ts';

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
