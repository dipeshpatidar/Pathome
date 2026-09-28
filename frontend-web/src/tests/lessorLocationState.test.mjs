import test from 'node:test';
import assert from 'node:assert/strict';
import { changeLocationCity, changeLocalityText, chooseLocalityOption,
  locationValidationError, useLocalityForReview } from '../utils/lessorLocationState.ts';

const base = { city: 'Indore', canonicalLocalityId: null, localityInput: 'rani pura',
  address: '10 Private Road', landmark: '' };

test('canonical and external suggestions preserve distinct resolution states', () => {
  const canonical = chooseLocalityOption(base, { id: 7, city: 'Indore', name: 'Rani Pura',
    match: 'canonical', selectionToken: null, provider: null, providerPlaceId: null });
  assert.equal(canonical.resolutionType, 'CANONICAL');
  assert.equal(canonical.canonicalLocalityId, 7);
  assert.equal(locationValidationError(canonical, ['Indore']), null);
  const external = chooseLocalityOption(base, { id: null, city: 'Indore', name: 'Rani Pura',
    match: 'external', selectionToken: 'signed', provider: 'MAPTILER', providerPlaceId: 'place.1' });
  assert.equal(external.resolutionType, 'EXTERNAL_RESOLVED');
  assert.equal(external.providerPlaceId, 'place.1');
  assert.equal(locationValidationError(external, ['Indore']), null);
  assert.equal(chooseLocalityOption(base, { id: 3, city: 'Pune', name: 'Baner',
    match: 'different_city', selectionToken: null, provider: null, providerPlaceId: null }), null);
});

test('manual choice continues, while city and text edits invalidate an earlier selection', () => {
  const manual = useLocalityForReview(base);
  assert.equal(manual.resolutionType, 'MANUAL_PENDING');
  assert.equal(locationValidationError(manual, ['Indore']), null);
  assert.equal(changeLocationCity(manual, 'Pune').resolutionType, null);
  assert.equal(changeLocationCity(manual, 'Pune').localityInput, '');
  assert.equal(changeLocalityText(manual, 'new text').resolutionType, null);
  assert.match(locationValidationError(changeLocalityText(manual, 'new text'), ['Indore']), /locality/);
});

test('validation identifies only the missing field', () => {
  const manual = useLocalityForReview(base);
  assert.match(locationValidationError({ ...manual, city: 'Mumbai' }, ['Indore']), /city/);
  assert.match(locationValidationError({ ...manual, address: '' }, ['Indore']), /street address/);
  assert.equal(locationValidationError(manual, ['Indore']), null);
});
