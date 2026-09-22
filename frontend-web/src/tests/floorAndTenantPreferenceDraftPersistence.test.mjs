import test from 'node:test';
import assert from 'node:assert/strict';

// Normalization function matching PreferredTenantEditor in BatchPropertyIngestionStudio.tsx
function toggleTenantSelection(currentSelections, tenantValue) {
  const current = currentSelections || [];
  if (tenantValue === 'ANY') {
    if (current.includes('ANY')) {
      return [];
    }
    return ['ANY'];
  } else {
    if (current.includes(tenantValue)) {
      return current.filter(t => t !== tenantValue);
    } else {
      return [...current.filter(t => t !== 'ANY'), tenantValue];
    }
  }
}

// Restoration logic matching BatchPropertyIngestionStudio.tsx
function restoreStagedCardFromDraft(card) {
  return {
    id: card.id,
    title: card.title || '',
    bhk: card.bhk || '',
    type: card.type || 'Flat',
    rentAmount: card.rentAmount || 0,
    depositVal: card.depositVal || '',
    sector: card.sector || '',
    // Strict preservation of numeric 0 (Ground Floor)
    floor: typeof card.floor === 'number' ? card.floor : (card.floorNumber != null ? Number(card.floorNumber) : null),
    totalFloors: typeof card.totalFloors === 'number' ? card.totalFloors : null,
    preferredTenants: Array.isArray(card.preferredTenants) ? card.preferredTenants : [],
    bachelorAllowed: card.bachelorAllowed ?? null
  };
}

// Mapping logic from parsed DTO to StagedProperty
function mapParsedDTOToStagedProperty(dto, index) {
  return {
    id: `card-${index}`,
    title: dto.title || '',
    bhk: dto.bhk || '',
    type: dto.type || 'Flat',
    rentAmount: dto.rentAmount || 0,
    depositVal: dto.depositVal || '',
    sector: dto.sector || '',
    floor: typeof dto.floor === 'number' ? dto.floor : null,
    totalFloors: typeof dto.totalFloors === 'number' ? dto.totalFloors : null,
    preferredTenants: Array.isArray(dto.preferredTenants) ? [...dto.preferredTenants] : [],
    bachelorAllowed: dto.bachelorAllowed ?? null
  };
}

// Publish payload generator matching BatchPropertyIngestionStudio.tsx
function createPublishPayload(stagedCards) {
  return stagedCards.map(card => ({
    bhk: card.bhk,
    type: card.type,
    sector: card.sector,
    rentAmount: card.rentAmount,
    depositVal: card.depositVal,
    floor: typeof card.floor === 'number' ? card.floor : null,
    totalFloors: typeof card.totalFloors === 'number' ? card.totalFloors : null,
    preferredTenants: card.preferredTenants || [],
    bachelorAllowed: card.bachelorAllowed
  }));
}

// Property showcase formatting matching PropertyShowcase.tsx
const getOrdinal = (n) => {
  const s = ['th', 'st', 'nd', 'rd'];
  const v = n % 100;
  return n + (s[(v - 20) % 10] || s[v] || s[0]);
};

function formatFloorDisplay(floor, totalFloors) {
  if (floor === null || floor === undefined) {
    if (typeof totalFloors === 'number' && totalFloors > 0) {
      return `${totalFloors} Floors Building`;
    }
    return null;
  }
  const floorName = floor === 0 ? 'Ground Floor' : `${getOrdinal(floor)} Floor`;
  if (typeof totalFloors === 'number' && totalFloors > 0) {
    return `${floorName} of ${totalFloors}`;
  }
  return floorName;
}

function formatPreferredTenantDisplay(preferredTenant) {
  if (!preferredTenant || !preferredTenant.trim()) return null;
  const labels = {
    'FAMILY': 'Family',
    'WORKING_PROFESSIONALS': 'Working Professionals',
    'BACHELORS': 'Bachelors',
    'STUDENTS': 'Students',
    'ANY': 'No Preference'
  };
  const parts = preferredTenant.split(',').map(s => s.trim().toUpperCase()).filter(Boolean);
  if (parts.length === 0) return null;
  const displayParts = parts.map(p => labels[p] || p);
  return `Preferred: ${displayParts.join(', ')}`;
}

test('Floor and Preferred Tenant Frontend Integration Suite', async (t) => {
  await t.test('1. Parsed Property DTO mapping populates floor, totalFloors, and preferredTenants accurately', () => {
    const parsedDto = {
      title: '2 BHK in Bhawarkuan',
      bhk: '2 BHK',
      type: 'Flat',
      rentAmount: 21000,
      depositVal: '42000',
      sector: 'Bhawarkuan',
      floor: 3,
      totalFloors: 7,
      preferredTenants: ['FAMILY', 'WORKING_PROFESSIONALS']
    };

    const staged = mapParsedDTOToStagedProperty(parsedDto, 0);
    assert.strictEqual(staged.floor, 3);
    assert.strictEqual(staged.totalFloors, 7);
    assert.deepStrictEqual(staged.preferredTenants, ['FAMILY', 'WORKING_PROFESSIONALS']);
  });

  await t.test('2. Parsed Ground floor (0) correctly maps to floor: 0 (not null or dropped)', () => {
    const parsedDto = {
      title: '1 BHK in Vijay Nagar',
      bhk: '1 BHK',
      type: 'Flat',
      floor: 0,
      totalFloors: 4,
      preferredTenants: ['ANY']
    };

    const staged = mapParsedDTOToStagedProperty(parsedDto, 1);
    assert.strictEqual(staged.floor, 0);
    assert.strictEqual(staged.totalFloors, 4);
    assert.deepStrictEqual(staged.preferredTenants, ['ANY']);
  });

  await t.test('3. Ground Floor (floor === 0) survives draft serialization, hard refresh, and restoration', () => {
    const originalCard = {
      id: 'card-1',
      title: 'Ground Floor Flat',
      bhk: '2 BHK',
      type: 'Flat',
      rentAmount: 18000,
      depositVal: '36000',
      sector: 'Rau',
      floor: 0,
      totalFloors: 3,
      preferredTenants: ['FAMILY']
    };

    // Simulate draft JSON autosave
    const draftPayloadJson = JSON.stringify({ stagedCards: [originalCard] });
    assert.ok(draftPayloadJson.includes('"floor":0'));

    // Simulate browser hard refresh + restore
    const restoredPayload = JSON.parse(draftPayloadJson);
    const restoredCard = restoreStagedCardFromDraft(restoredPayload.stagedCards[0]);

    assert.strictEqual(restoredCard.floor, 0, 'floor 0 must be preserved as numeric 0, never converted to null');
    assert.strictEqual(restoredCard.totalFloors, 3);
    assert.deepStrictEqual(restoredCard.preferredTenants, ['FAMILY']);
  });

  await t.test('4. Preferred Tenant multi-select and ANY exclusivity behavior', () => {
    let tenants = [];

    // Select Family
    tenants = toggleTenantSelection(tenants, 'FAMILY');
    assert.deepStrictEqual(tenants, ['FAMILY']);

    // Select Working Professionals
    tenants = toggleTenantSelection(tenants, 'WORKING_PROFESSIONALS');
    assert.deepStrictEqual(tenants, ['FAMILY', 'WORKING_PROFESSIONALS']);

    // Select ANY -> must clear specific selections and become ['ANY']
    tenants = toggleTenantSelection(tenants, 'ANY');
    assert.deepStrictEqual(tenants, ['ANY'], 'ANY must be mutually exclusive with specific selections');

    // Selecting STUDENTS after ANY -> must remove ANY and keep STUDENTS
    tenants = toggleTenantSelection(tenants, 'STUDENTS');
    assert.deepStrictEqual(tenants, ['STUDENTS'], 'Selecting specific tenant must remove ANY');

    // Add BACHELORS
    tenants = toggleTenantSelection(tenants, 'BACHELORS');
    assert.deepStrictEqual(tenants, ['STUDENTS', 'BACHELORS']);

    // Toggle off STUDENTS
    tenants = toggleTenantSelection(tenants, 'STUDENTS');
    assert.deepStrictEqual(tenants, ['BACHELORS']);

    // Select ANY again -> removes BACHELORS and sets ANY
    tenants = toggleTenantSelection(tenants, 'ANY');
    assert.deepStrictEqual(tenants, ['ANY']);

    // Toggle off ANY
    tenants = toggleTenantSelection(tenants, 'ANY');
    assert.deepStrictEqual(tenants, []);
  });

  await t.test('5. Publish payload includes floor, totalFloors, and preferredTenants without touching bachelorAllowed', () => {
    const stagedCards = [
      {
        bhk: '2 BHK',
        type: 'Flat',
        sector: 'Bhawarkuan',
        rentAmount: 21000,
        depositVal: '42000',
        floor: 3,
        totalFloors: 7,
        preferredTenants: ['FAMILY', 'WORKING_PROFESSIONALS'],
        bachelorAllowed: true
      },
      {
        bhk: '1 BHK',
        type: 'Flat',
        sector: 'Palasia',
        rentAmount: 15000,
        depositVal: '30000',
        floor: 0,
        totalFloors: 5,
        preferredTenants: ['ANY'],
        bachelorAllowed: false
      }
    ];

    const payload = createPublishPayload(stagedCards);
    assert.strictEqual(payload[0].floor, 3);
    assert.strictEqual(payload[0].totalFloors, 7);
    assert.deepStrictEqual(payload[0].preferredTenants, ['FAMILY', 'WORKING_PROFESSIONALS']);
    assert.strictEqual(payload[0].bachelorAllowed, true, 'bachelorAllowed must remain independent');

    assert.strictEqual(payload[1].floor, 0);
    assert.strictEqual(payload[1].totalFloors, 5);
    assert.deepStrictEqual(payload[1].preferredTenants, ['ANY']);
    assert.strictEqual(payload[1].bachelorAllowed, false, 'bachelorAllowed must remain independent');
  });

  await t.test('6. Legacy draft with fields absent restores safely with null/empty defaults', () => {
    const legacyCard = {
      id: 'legacy-1',
      title: 'Old listing without floor',
      bhk: '3 BHK',
      type: 'Independent House',
      rentAmount: 30000,
      depositVal: '60000',
      sector: 'Saket'
      // floor, totalFloors, preferredTenants absent
    };

    const restored = restoreStagedCardFromDraft(legacyCard);
    assert.strictEqual(restored.floor, null);
    assert.strictEqual(restored.totalFloors, null);
    assert.deepStrictEqual(restored.preferredTenants, []);

    const publishPayload = createPublishPayload([restored]);
    assert.strictEqual(publishPayload[0].floor, null);
    assert.strictEqual(publishPayload[0].totalFloors, null);
    assert.deepStrictEqual(publishPayload[0].preferredTenants, []);
  });

  await t.test('7. PropertyShowcase formatting renders clean labels and omits empty/null fields', () => {
    // Ground floor with total floors
    assert.strictEqual(formatFloorDisplay(0, 5), 'Ground Floor of 5');
    // Ground floor standalone
    assert.strictEqual(formatFloorDisplay(0, null), 'Ground Floor');
    // 3rd floor of 7
    assert.strictEqual(formatFloorDisplay(3, 7), '3rd Floor of 7');
    // 1st floor of 8
    assert.strictEqual(formatFloorDisplay(1, 8), '1st Floor of 8');
    // 2nd floor standalone
    assert.strictEqual(formatFloorDisplay(2, null), '2nd Floor');
    // Standalone total floors
    assert.strictEqual(formatFloorDisplay(null, 10), '10 Floors Building');
    // Both null
    assert.strictEqual(formatFloorDisplay(null, null), null);

    // Preferred tenant display
    assert.strictEqual(
      formatPreferredTenantDisplay('FAMILY,WORKING_PROFESSIONALS'),
      'Preferred: Family, Working Professionals'
    );
    assert.strictEqual(
      formatPreferredTenantDisplay('ANY'),
      'Preferred: No Preference'
    );
    assert.strictEqual(
      formatPreferredTenantDisplay('BACHELORS'),
      'Preferred: Bachelors'
    );
    assert.strictEqual(formatPreferredTenantDisplay(null), null);
    assert.strictEqual(formatPreferredTenantDisplay(''), null);
  });
});
