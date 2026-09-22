import test from 'node:test';
import assert from 'node:assert/strict';
import { countDetectedProperties } from '../utils/propertyPromptComposer.ts';

test('Two-Tier Property Detection Badge Suite', async (t) => {
  await t.test('1. Exact 4-property incident prompt returns exactly 4 (not 8)', () => {
    const incidentPrompt = `2 BHK Flat for rent in Bhawarkuan, Indore near DAVV.

Monthly rent is ₹21,000 and security deposit is ₹42,000.
Built-up area is 1100 sqft.
The flat is semi furnished and east facing.
It has 2 bedrooms, 2 bathrooms and 1 balcony.
Located on the 3rd floor of a 7-floor building.
Covered car parking is available.
Property is available immediately.
Preferred for family or working professionals.
Amenities include lift, security, CCTV and power backup.

Next property

3 BHK Flat for rent in Rau, Indore near IIM Indore.

Monthly rent is ₹28,000 and security deposit is ₹56,000.
Built-up area is 1550 sqft.
The flat is fully furnished and north facing.
It has 3 bedrooms, 3 bathrooms and 2 balconies.
Located on the 5th floor of a 10-floor building.
Covered car parking is available.
Property is available immediately.
Preferred for family.
Amenities include lift, security, CCTV, power backup, gym and clubhouse.

Next property

2 BHK Flat for rent near Mari Mata Square, Indore.

Monthly rent is ₹19,000 and security deposit is ₹38,000.
Built-up area is 1000 sqft.
The flat is unfurnished and west facing.
It has 2 bedrooms, 2 bathrooms and 1 balcony.
Located on the 2nd floor of a 6-floor building.
Car parking is available.
Property is available immediately.
Preferred for family.
Amenities include lift, security and CCTV.

Next property

3 BHK Flat for rent in Nipania, Indore near Phoenix Citadel.

Monthly rent is ₹36,000 and security deposit is ₹72,000.
Built-up area is 1700 sqft.
The flat is semi furnished and south facing.
It has 3 bedrooms, 3 bathrooms and 3 balconies.
Located on the 8th floor of a 14-floor building.
Covered car parking is available.
Property is available immediately.
Preferred for family.
Amenities include lift, power backup, security, CCTV, gym, swimming pool, clubhouse and children's play area.`;

    const count = countDetectedProperties(incidentPrompt);
    assert.equal(count, 4, '4-property incident prompt with internal blank lines must detect exactly 4 properties, not 8');
  });

  await t.test('2. Internal multiple blank lines with explicit delimiters do not increase count', () => {
    const prompt = `1. 2 BHK in Vijay Nagar

Rent 25000

Deposit 50000

Carpet area 1200 sqft

2. 3 BHK in Palasia

Rent 45000

Deposit 90000`;

    const count = countDetectedProperties(prompt);
    assert.equal(count, 2, 'Internal blank lines between attributes must not increase the detected property count');
  });

  await t.test('3. One property with normal paragraph breaks detects exactly 1', () => {
    const singlePropertyPrompt = `2 BHK Flat for rent in Bhawarkuan, Indore near DAVV.

Monthly rent is ₹21,000 and security deposit is ₹42,000.
Built-up area is 1100 sqft.
The flat is semi furnished and east facing.
It has 2 bedrooms, 2 bathrooms and 1 balcony.

Amenities include lift, security, CCTV and power backup.`;

    const count = countDetectedProperties(singlePropertyPrompt);
    assert.equal(count, 1, 'A single property written across multiple paragraphs must count as exactly 1 property');
  });

  await t.test('4. Existing blank-line-only multi-property fallback correctly counts distinct properties', () => {
    const fallbackPrompt = `2 BHK Flat in Vijay Nagar, Rent 20000

3 BHK Flat in Palasia, Rent 40000`;

    const count = countDetectedProperties(fallbackPrompt);
    assert.equal(count, 2, 'Two distinct properties separated only by blank lines must count as 2');
  });

  await t.test('5. Numbered property delimiters (1), 2., 3)) are recognized authoritatively', () => {
    const numberedPrompt = `1) 2 BHK in Vijay Nagar, Rent 18000
2) 3 BHK in Palasia, Rent 35000
3) 1 RK in Bhawarkua, Rent 8000`;

    const count = countDetectedProperties(numberedPrompt);
    assert.equal(count, 3, 'Numbered property prefixes must count as 3');
  });

  await t.test('6. "Next property" delimiter cue is recognized', () => {
    const nextPropertyPrompt = `2 BHK in Vijay Nagar rent 18000
Next property
3 BHK in Palasia rent 35000`;

    const count = countDetectedProperties(nextPropertyPrompt);
    assert.equal(count, 2, 'Next property delimiter must detect 2 properties');
  });

  await t.test('7. "Next flat" delimiter cue is recognized', () => {
    const nextFlatPrompt = `2 BHK in Vijay Nagar rent 18000
Next flat
3 BHK in Palasia rent 35000`;

    const count = countDetectedProperties(nextFlatPrompt);
    assert.equal(count, 2, 'Next flat delimiter must detect 2 properties');
  });

  await t.test('8. Hinglish delimiter cues ("agli property", "dusra flat") are recognized', () => {
    const hinglishPrompt = `2 BHK in Vijay Nagar rent 18000
agli property
3 BHK in Palasia rent 35000
dusra flat
1 BHK in Rau rent 10000`;

    const count = countDetectedProperties(hinglishPrompt);
    assert.equal(count, 3, 'Hinglish transition cues must detect 3 properties');
  });

  await t.test('9. Markdown separator line (---) is recognized as explicit boundary', () => {
    const markdownPrompt = `2 BHK Flat in Vijay Nagar, Rent 20000
---
3 BHK Flat in Palasia, Rent 40000`;

    const count = countDetectedProperties(markdownPrompt);
    assert.equal(count, 2, 'Markdown separator line must detect 2 properties');
  });

  await t.test('10. Empty and blank prompts return 0', () => {
    assert.equal(countDetectedProperties(''), 0);
    assert.equal(countDetectedProperties('   \n\n  \t  '), 0);
  });
});
