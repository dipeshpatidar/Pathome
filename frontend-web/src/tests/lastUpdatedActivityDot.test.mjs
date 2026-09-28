import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { formatLastUpdated, getLastUpdatedInfo } from '../utils/lessorFormatting.ts';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

test('today renders pulsing-dot state (isToday is true)', () => {
  const referenceNow = new Date(2026, 9, 28, 14, 0, 0); // 28 Oct 2026 2:00 PM
  const todayMorning = new Date(2026, 9, 28, 10, 30, 0);

  const info = getLastUpdatedInfo(todayMorning, referenceNow);
  assert.ok(info !== null);
  assert.equal(info.formatted, 'Updated today · 10:30 AM');
  assert.equal(info.isToday, true, 'Listings updated today must have isToday=true for subtle pulse halo');
});

test('yesterday renders static-dot state (isToday is false, no pulse)', () => {
  const referenceNow = new Date(2026, 9, 28, 14, 0, 0);
  const yesterdayEvening = new Date(2026, 9, 27, 18, 15, 0);

  const info = getLastUpdatedInfo(yesterdayEvening, referenceNow);
  assert.ok(info !== null);
  assert.equal(info.formatted, 'Updated yesterday · 6:15 PM');
  assert.equal(info.isToday, false, 'Listings updated yesterday must have isToday=false so dot remains static');
});

test('older date renders static-dot state (isToday is false, no pulse)', () => {
  const referenceNow = new Date(2026, 9, 28, 14, 0, 0);
  const olderDate = new Date(2026, 9, 21, 22, 40, 0);

  const info = getLastUpdatedInfo(olderDate, referenceNow);
  assert.ok(info !== null);
  assert.equal(info.formatted, 'Updated 21 Oct 2026 · 10:40 PM');
  assert.equal(info.isToday, false, 'Older dates must have isToday=false so dot remains static');
});

test('null, empty, or invalid timestamp renders nothing', () => {
  assert.equal(getLastUpdatedInfo(null), null);
  assert.equal(getLastUpdatedInfo(undefined), null);
  assert.equal(getLastUpdatedInfo(''), null);
  assert.equal(getLastUpdatedInfo('invalid-date'), null);
});

test('reduced-motion CSS and markup rules exist for activity dot', () => {
  const cssPath = path.resolve(__dirname, '../index.css');
  const cssContent = fs.readFileSync(cssPath, 'utf8');

  // Verify keyframes and breathing timing
  assert.ok(cssContent.includes('@keyframes activityHalo'), 'index.css must define @keyframes activityHalo');
  assert.ok(cssContent.includes('.activity-dot-halo'), 'index.css must define .activity-dot-halo utility');
  assert.ok(cssContent.includes('2.8s'), 'Animation should run on subtle breathing cycle (~2.5-3s)');

  // Verify reduced-motion support in CSS
  assert.ok(cssContent.includes('@media (prefers-reduced-motion: reduce)'), 'index.css must include prefers-reduced-motion rule');
  assert.ok(cssContent.includes('animation: none !important'), 'prefers-reduced-motion must disable activity halo animation');

  // Verify LastUpdatedMeta component source
  const componentPath = path.resolve(__dirname, '../components/LastUpdatedMeta.tsx');
  const componentContent = fs.readFileSync(componentPath, 'utf8');

  assert.ok(componentContent.includes('aria-hidden="true"'), 'Activity dot must be decorative with aria-hidden="true"');
  assert.ok(componentContent.includes('motion-reduce:hidden'), 'Pulse halo must have motion-reduce:hidden');
  assert.ok(componentContent.includes('bg-emerald-600'), 'Core dot must use Pathome-green emerald color');
});

test('both public discovery cards and lessor portfolio use LastUpdatedMeta', () => {
  const showcasePath = path.resolve(__dirname, '../components/PropertyShowcase.tsx');
  const showcaseContent = fs.readFileSync(showcasePath, 'utf8');
  assert.ok(showcaseContent.includes('<LastUpdatedMeta'), 'PropertyShowcase must render LastUpdatedMeta');

  const portfolioPath = path.resolve(__dirname, '../components/LessorPortfolio.tsx');
  const portfolioContent = fs.readFileSync(portfolioPath, 'utf8');
  assert.ok(portfolioContent.includes('<LastUpdatedMeta'), 'LessorPortfolio must render LastUpdatedMeta');
});
