import assert from 'node:assert/strict';
import test from 'node:test';

import {
  buildPublicPropertyMetadata,
  GENERIC_PUBLIC_TITLE,
  resetPublicPropertyMetadata,
  syncPublicPropertyMetadata
} from '../utils/publicPropertyMetadata.ts';

class FakeElement {
  constructor(tagName) {
    this.tagName = tagName;
    this.attributes = new Map();
    this.removed = false;
  }

  setAttribute(name, value) {
    this.attributes.set(name, String(value));
  }

  getAttribute(name) {
    return this.attributes.get(name) ?? null;
  }

  remove() {
    this.removed = true;
  }
}

const fakeDocument = () => {
  const elements = [];
  const head = {
    appendChild: (element) => {
      elements.push(element);
      return element;
    },
    querySelectorAll: (selector) => {
      assert.equal(selector, '[data-pathome-public-metadata]');
      return elements.filter((element) =>
        !element.removed && element.getAttribute('data-pathome-public-metadata') === 'true');
    }
  };
  return {
    title: GENERIC_PUBLIC_TITLE,
    head,
    createElement: (tagName) => new FakeElement(tagName),
    elements
  };
};

const property = {
  id: 201,
  title: 'Fresh public home',
  city: 'Bhopal',
  sector: 'Arera Colony',
  bhk: '2 BHK',
  monthlyRent: 21000.01,
  totalAreaSqFt: 850,
  images: ['https://res.cloudinary.com/pathome/image/upload/public-cover.jpg'],
  taggedMedia: []
};

test('client metadata uses current public facts and a same-origin canonical route', () => {
  const metadata = buildPublicPropertyMetadata(property, 'https://pathome.test/');
  assert.equal(metadata.title, 'Fresh public home | Pathome');
  assert.equal(metadata.canonicalUrl, 'https://pathome.test/property/201');
  assert.match(metadata.description, /Arera Colony, Bhopal/);
  assert.match(metadata.description, /Rent ₹21,000\.01 per month/);
  assert.match(metadata.description, /850 sq ft/);
  assert.equal(metadata.imageUrl, property.images[0]);
});

test('client head synchronization replaces prior tags once and reset removes all property facts', () => {
  globalThis.window = {
    location: { origin: 'https://pathome.test' },
    document: null
  };
  const document = fakeDocument();
  globalThis.window.document = document;

  syncPublicPropertyMetadata(property, document);
  syncPublicPropertyMetadata({ ...property, id: 202, title: 'Second home' }, document);

  const active = document.elements.filter((element) => !element.removed);
  assert.equal(active.filter((element) => element.tagName === 'meta'
    && element.getAttribute('property') === 'og:url').length, 1);
  assert.equal(active.find((element) => element.getAttribute('property') === 'og:url')
    ?.getAttribute('content'), 'https://pathome.test/property/202');
  assert.equal(document.title, 'Second home | Pathome');

  resetPublicPropertyMetadata(document);
  assert.equal(document.title, GENERIC_PUBLIC_TITLE);
  assert.equal(document.elements.filter((element) =>
    !element.removed && element.getAttribute('data-pathome-public-metadata') === 'true').length, 0);
});
