import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { build } from 'esbuild';
import { availableNowDate, EMPTY_LESSOR_DETAILS, isLessorFurnishingChoice,
  lessorDetailsCompletionError, lessorFurnishingLabel, LESSOR_FURNISHING_OPTIONS,
  restoreLessorDetails } from '../utils/lessorDetails.ts';

test('old partial details restore optional fields without replacing valid values', () => {
  const restored = restoreLessorDetails(JSON.parse(JSON.stringify({ furnishingStatus: 'SEMI_FURNISHED' })));
  assert.deepEqual(restored, { ...EMPTY_LESSOR_DETAILS, furnishingStatus: 'SEMI_FURNISHED' });
});

test('complete details, explicit nulls, and missing details restore safely', () => {
  const complete = { availableFrom: '2026-10-02', furnishingStatus: 'FULLY_FURNISHED',
    totalAreaSqFt: 850, floorNumber: 0, totalFloors: 4, amenities: 'Balcony, Lift', description: 'Bright flat' };
  assert.deepEqual(restoreLessorDetails(JSON.parse(JSON.stringify(complete))), complete);
  assert.deepEqual(restoreLessorDetails(complete, { furnishingStatus: 'UNFURNISHED' }),
    { ...complete, furnishingStatus: 'UNFURNISHED' });
  assert.deepEqual(restoreLessorDetails(null), EMPTY_LESSOR_DETAILS);
  assert.deepEqual(restoreLessorDetails(undefined), EMPTY_LESSOR_DETAILS);
  assert.deepEqual(restoreLessorDetails({ availableFrom: null, amenities: null, totalAreaSqFt: null }),
    EMPTY_LESSOR_DETAILS);
});

test('lessor furnishing controls use physical states, while available now uses local date', () => {
  assert.deepEqual(LESSOR_FURNISHING_OPTIONS.map(option => option.value),
    ['UNFURNISHED', 'SEMI_FURNISHED', 'FULLY_FURNISHED']);
  assert.equal(LESSOR_FURNISHING_OPTIONS.some(option => option.value === 'FURNISHED'), false);
  assert.equal(availableNowDate(new Date(2026, 9, 2, 23, 59)), '2026-10-02');
  for (const choice of LESSOR_FURNISHING_OPTIONS) {
    assert.equal(isLessorFurnishingChoice(choice.value), true);
    assert.equal(lessorFurnishingLabel(choice.value), choice.label);
    assert.equal(lessorDetailsCompletionError({ ...EMPTY_LESSOR_DETAILS, availableFrom: '2026-10-02',
      furnishingStatus: choice.value }, true), null);
  }
  assert.equal(isLessorFurnishingChoice('FURNISHED'), false);
  assert.equal(lessorFurnishingLabel(null), null);
  assert.equal(lessorDetailsCompletionError({ ...EMPTY_LESSOR_DETAILS, availableFrom: '2026-10-02' }, true),
    'Choose the furnishing for this home to continue.');
  assert.equal(lessorDetailsCompletionError({ ...EMPTY_LESSOR_DETAILS, availableFrom: '2026-10-02',
    furnishingStatus: 'FURNISHED' }, true), 'Choose the furnishing for this home to continue.');
  assert.equal(lessorDetailsCompletionError({ ...EMPTY_LESSOR_DETAILS, availableFrom: '2026-10-02' }, false), null);
  assert.equal(lessorDetailsCompletionError(EMPTY_LESSOR_DETAILS, true), 'Add the availability date to continue.');
});

test('production Details step renders the existing controls and available-now action', async () => {
  const output = await build({
    stdin: {
      contents: `import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { LessorDetailsStep } from '../components/LessorDetailsStep';
export const render = value => renderToStaticMarkup(<LessorDetailsStep value={value}
  onChange={() => {}} onBlur={() => {}} />);`,
      resolveDir: new URL('.', import.meta.url).pathname,
      sourcefile: 'lessor-details-harness.tsx',
      loader: 'tsx'
    },
    bundle: true, platform: 'node', format: 'cjs', write: false,
    external: ['react', 'react-dom', 'react-dom/server', 'lucide-react']
  });
  const bundled = { exports: {} };
  new Function('require', 'module', 'exports', output.outputFiles[0].text)(
    createRequire(import.meta.url), bundled, bundled.exports);
  const markup = bundled.exports.render(restoreLessorDetails({ furnishingStatus: 'SEMI_FURNISHED' }));
  assert.match(markup, /Available now/);
  assert.match(markup, /aria-pressed="false"/);
  const selectedRadio = markup.match(/<input[^>]*value="SEMI_FURNISHED"[^>]*>/)?.[0];
  assert.match(selectedRadio ?? '', /checked=""/);
  assert.match(markup, /value="FULLY_FURNISHED"/);
  assert.doesNotMatch(markup, /value="FURNISHED"/);
  assert.match(markup, /id="lessor-amenities"/);
  assert.ok(markup.indexOf('Furnishing') < markup.indexOf('<details'),
    'Furnishing must be visible before the optional disclosure');
  assert.match(markup, /Add more details.*optional/);
});
