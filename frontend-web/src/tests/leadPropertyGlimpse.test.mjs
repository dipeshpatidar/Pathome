import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

import {
  publicPropertyCoverImage,
  publicPropertyIdFromLink
} from '../utils/operationsLeadPresentation.ts';

const property = {
  images: ['https://res.cloudinary.com/pathome/image/upload/fallback.jpg'],
  taggedMedia: [
    {
      mediaUrl: 'https://res.cloudinary.com/pathome/image/upload/fallback.jpg',
      mediaType: 'IMAGE',
      isPrimaryCover: false
    },
    {
      mediaUrl: 'https://res.cloudinary.com/pathome/image/upload/primary.jpg',
      mediaType: 'IMAGE',
      isPrimaryCover: true
    }
  ]
};

test('only exact HTTPS public property links resolve to a numeric preview target', () => {
  assert.equal(publicPropertyIdFromLink('https://pathome.test/property/201'), 201);
  assert.equal(publicPropertyIdFromLink('https://pathome.test:443/property/201'), 201);
  assert.equal(publicPropertyIdFromLink('http://pathome.test/property/201'), null);
  assert.equal(publicPropertyIdFromLink('https://pathome.test/property/201?phone=999'), null);
  assert.equal(publicPropertyIdFromLink('https://pathome.test/property/201#visit=secret'), null);
  assert.equal(publicPropertyIdFromLink('https://user:secret@pathome.test/property/201'), null);
  assert.equal(publicPropertyIdFromLink('https://pathome.test/properties/201'), null);
  assert.equal(publicPropertyIdFromLink('https://pathome.test/property/0'), null);
  assert.equal(publicPropertyIdFromLink('not a url'), null);
});

test('preview cover prefers the public primary cover and never invents an image', () => {
  assert.equal(
    publicPropertyCoverImage(property),
    'https://res.cloudinary.com/pathome/image/upload/primary.jpg'
  );
  assert.equal(
    publicPropertyCoverImage({
      images: property.images,
      taggedMedia: property.taggedMedia.map((item) => ({
        ...item,
        isPrimaryCover: false
      }))
    }),
    property.images[0]
  );
  assert.equal(publicPropertyCoverImage({ images: [], taggedMedia: [] }), null);
  assert.equal(publicPropertyCoverImage({
    images: ['data:image/svg+xml,fake'],
    taggedMedia: []
  }), null);
});

test('preview card has truthful fallback, accessible image semantics and no private property fields', () => {
  const source = readFileSync(
    new URL('../components/operations/LeadSharePreviewCard.tsx', import.meta.url),
    'utf8'
  );
  assert.match(source, /No public cover photo available/);
  assert.match(source, /aria-labelledby=\{headingId\}/);
  assert.match(source, /role="status"/);
  assert.match(source, /alt=\{imageAlt\}/);
  assert.match(source, /referrerPolicy="no-referrer"/);
  assert.match(source, /target="_blank"/);
  assert.match(source, /rel="noopener noreferrer"/);
  assert.doesNotMatch(source, /ownerPhone|latitude|longitude|landmark|pincode|description/);
});
