import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
import { resolvePropertyVideoUrl } from '../utils/propertyVideo.ts';

test('video URL comes only from an explicitly typed video asset or videoUrl field', () => {
  const url = 'https://media.example.test/property/walkthrough.mp4';
  assert.equal(resolvePropertyVideoUrl({ taggedMedia: [{ mediaType: 'VIDEO_WALKTHROUGH', mediaUrl: url }] }), url);
  assert.equal(resolvePropertyVideoUrl({ taggedMedia: [], videoUrl: url }), url);
  assert.equal(resolvePropertyVideoUrl({ taggedMedia: [{ mediaType: 'IMAGE', mediaUrl: url }] }), null);
  assert.equal(resolvePropertyVideoUrl({ taggedMedia: [], videoUrl: '', _hasVideo: true }), null);
});

test('video URL rejects empty, unsafe, protocol-relative, and malformed sources', () => {
  for (const value of ['', '  ', 'javascript:alert(1)', 'data:video/mp4;base64,AA', '//media.example.test/a.mp4', '/\\evil.example/a.mp4', 'https://', 'not a url']) {
    assert.equal(resolvePropertyVideoUrl({ taggedMedia: [], videoUrl: value }), null);
  }
  assert.equal(resolvePropertyVideoUrl({ taggedMedia: [], videoUrl: '/media/property-video.mp4' }), '/media/property-video.mp4');
});

test('public property video play state resets when loading or changing the active media source', async () => {
  const source = await readFile(new URL('../components/PublicPropertyDetail.tsx', import.meta.url), 'utf8');
  const loadEffect = source.match(/useEffect\(\(\) => \{\s*if \(propertyId === null\)[\s\S]*?\}, \[propertyId, retryCount\]\);/);
  assert.ok(loadEffect, 'property load and retry effect exists');
  assert.match(loadEffect[0], /setIsVideoPlaying\(false\)/, 'a new load or retry clears stale play state');

  const sourceSyncEffect = source.match(/useEffect\(\(\) => \{\s*setIsVideoPlaying\(false\);\s*\}, \[activeMediaSource, activeMediaType\]\);/);
  assert.ok(sourceSyncEffect, 'active media URL and type changes clear stale play state');
  assert.match(source, /const activeMediaSource = media\[activeMedia\]\?\.url;/);
  assert.match(source, /const activeMediaType = media\[activeMedia\]\?\.type;/);
});
