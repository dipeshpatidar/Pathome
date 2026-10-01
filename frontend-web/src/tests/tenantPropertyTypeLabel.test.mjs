import test from 'node:test';
import assert from 'node:assert/strict';
import { tenantPropertyTypeLabel } from '../utils/tenantPropertyTypeLabel.ts';

test('tenant property type labels cover every supported Pathome property type', () => {
  const cases = [
    ['FLAT', 'Flat'],
    ['HOUSE', 'House'],
    ['PLOT', 'Plot'],
    ['LAND', 'Land'],
    ['PENTHOUSE', 'Penthouse'],
    ['STUDIO', 'Studio'],
    ['SERVICED_APARTMENT', 'Serviced apartment']
  ];

  for (const [propertyType, expectedLabel] of cases) {
    assert.equal(tenantPropertyTypeLabel(propertyType), expectedLabel);
  }
});

test('unknown tenant property types retain the existing safe empty-label behavior', () => {
  assert.equal(tenantPropertyTypeLabel('FUTURE_TYPE'), null);
  assert.equal(tenantPropertyTypeLabel(null), null);
  assert.equal(tenantPropertyTypeLabel(undefined), null);
});
