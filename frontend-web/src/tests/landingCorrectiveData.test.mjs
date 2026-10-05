import test from 'node:test';
import assert from 'node:assert/strict';
import { landingLocalities, landingLocality, landingPropertyTitle } from '../utils/landingPropertyData.ts';
import { getQuickViewMedia } from '../utils/quickViewMedia.ts';

const home = (overrides = {}) => ({
  id: 1, title: '4 BHK House in Vijay Nagar', propertyType: 'HOUSE', bhk: '4BHK',
  city: 'Indore', sector: 'Vijay Nagar', images: [], taggedMedia: [], videoUrl: '', ...overrides
});

test('landing locality uses only structured sector, excluding empty and headline-contaminated values', () => {
  assert.equal(landingLocality(home()), 'Vijay Nagar');
  assert.equal(landingLocality(home({ sector: '  ' })), null);
  assert.equal(landingLocality(home({ sector: '4 Bhk Independent Villa' })), null);
  assert.equal(landingLocality(home({ sector: 'House', title: 'House' })), null);
  assert.deepEqual(landingLocalities([
    home(), home({ id: 2, sector: ' vijay nagar ' }),
    home({ id: 3, sector: 'Palasia' }), home({ id: 4, sector: '4 Bhk Independent Villa' }),
    home({ id: 5, sector: '' }), home({ id: 6, city: 'Bhopal', sector: 'Arera Colony' })
  ], 'indore'), ['Palasia', 'Vijay Nagar']);
});

test('landing display keeps structured title and location separate', () => {
  const property = home({ title: '4 BHK House in 4 Bhk Independent Villa', sector: 'Vijay Nagar' });
  assert.equal(landingPropertyTitle(property), '4 BHK House');
  assert.equal(landingLocality(property), 'Vijay Nagar');
  assert.equal(property.city, 'Indore');
});

test('production media mapper keeps tagged order, untagged images, and real legacy video without fallback', () => {
  assert.deepEqual(getQuickViewMedia(home()), []);
  const media = getQuickViewMedia(home({
    images: ['https://example.com/cover.jpg', 'https://example.com/extra.jpg'],
    taggedMedia: [{ mediaUrl: 'https://example.com/cover.jpg', mediaType: 'IMAGE', roomTag: 'LIVING_ROOM' }],
    videoUrl: 'https://example.com/walkthrough.mp4'
  }));
  assert.deepEqual(media.map(item => item.url), [
    'https://example.com/cover.jpg', 'https://example.com/extra.jpg', 'https://example.com/walkthrough.mp4'
  ]);
  assert.equal(media[2].type, 'VIDEO');
});
