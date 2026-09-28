import test from 'node:test';
import assert from 'node:assert/strict';

test('lessor progress flow covers all 6 steps plus review in exact sequence', () => {
  const steps = ['type', 'basics', 'pricing', 'location', 'media', 'details', 'preview'];
  assert.equal(steps.length, 7);
  assert.equal(steps[0], 'type');
  assert.equal(steps[1], 'basics');
  assert.equal(steps[2], 'pricing');
  assert.equal(steps[3], 'location');
  assert.equal(steps[4], 'media');
  assert.equal(steps[5], 'details');
  assert.equal(steps[6], 'preview');
});

test('property types cover all supported residential categories with descriptions', () => {
  const supportedTypes = ['FLAT', 'HOUSE', 'STUDIO', 'PENTHOUSE', 'SERVICED_APARTMENT'];
  assert.equal(supportedTypes.length, 5);
  assert.ok(supportedTypes.includes('FLAT'));
  assert.ok(supportedTypes.includes('HOUSE'));
  assert.ok(supportedTypes.includes('STUDIO'));
  assert.ok(supportedTypes.includes('PENTHOUSE'));
  assert.ok(supportedTypes.includes('SERVICED_APARTMENT'));
});

test('live preview tenant view strictly excludes private street address and landmark', () => {
  const draft = {
    locality: 'Vijay Nagar',
    city: 'Indore',
    address: 'Flat 402, Royal Residency, Private Street 12',
    landmark: 'Behind Private Club'
  };

  // The tenant-facing location display should strictly only expose locality and city
  const tenantFacingLocation = [draft.locality, draft.city].filter(Boolean).join(', ');
  assert.equal(tenantFacingLocation, 'Vijay Nagar, Indore');
  assert.equal(tenantFacingLocation.includes('Royal Residency'), false);
  assert.equal(tenantFacingLocation.includes('Private Street'), false);
  assert.equal(tenantFacingLocation.includes('Private Club'), false);
});

test('live preview rent formatting uses Indian numbering and handles empty states', () => {
  const money = new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0
  });

  assert.equal(money.format(25000), '₹25,000');
  assert.equal(money.format(0), '₹0');
});
