import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  parsePropertyTimestamp,
  formatPropertyRelativeTime,
  formatExactPropertyTimestampIST
} from '../utils/propertyTimestamp.ts';

const dashboardSource = readFileSync(new URL('../components/TenantDashboard.tsx', import.meta.url), 'utf8');
const styles = readFileSync(new URL('../tenantV0.css', import.meta.url), 'utf8');

test('parsePropertyTimestamp safely validates authoritative timestamps and rejects missing/invalid values', () => {
  assert.equal(parsePropertyTimestamp(null), null);
  assert.equal(parsePropertyTimestamp(undefined), null);
  assert.equal(parsePropertyTimestamp(''), null);
  assert.equal(parsePropertyTimestamp('not-a-date'), null);

  const iso = '2026-10-05T10:30:00Z';
  const parsed = parsePropertyTimestamp(iso);
  assert.ok(parsed instanceof Date);
  assert.equal(parsed.toISOString(), new Date(iso).toISOString());

  const timestampMs = 1791196200000;
  const parsedFromMs = parsePropertyTimestamp(timestampMs);
  assert.ok(parsedFromMs instanceof Date);
  assert.equal(parsedFromMs.getTime(), timestampMs);
});

test('formatPropertyRelativeTime produces natural, compact labels without misleading precision', () => {
  const baseTime = new Date('2026-10-05T12:00:00Z').getTime();

  // Sub-minute -> "just now"
  assert.equal(formatPropertyRelativeTime(new Date(baseTime - 15 * 1000), baseTime), 'just now');
  // Clock drift/future -> "just now"
  assert.equal(formatPropertyRelativeTime(new Date(baseTime + 5000), baseTime), 'just now');

  // Minutes
  assert.equal(formatPropertyRelativeTime(new Date(baseTime - 60 * 1000), baseTime), '1 minute ago');
  assert.equal(formatPropertyRelativeTime(new Date(baseTime - 5 * 60 * 1000), baseTime), '5 minutes ago');

  // Hours
  assert.equal(formatPropertyRelativeTime(new Date(baseTime - 3600 * 1000), baseTime), '1 hour ago');
  assert.equal(formatPropertyRelativeTime(new Date(baseTime - 3 * 3600 * 1000), baseTime), '3 hours ago');

  // Days
  assert.equal(formatPropertyRelativeTime(new Date(baseTime - 24 * 3600 * 1000), baseTime), 'yesterday');
  assert.equal(formatPropertyRelativeTime(new Date(baseTime - 4 * 24 * 3600 * 1000), baseTime), '4 days ago');

  // Weeks
  assert.equal(formatPropertyRelativeTime(new Date(baseTime - 14 * 24 * 3600 * 1000), baseTime), '2 weeks ago');

  // Missing or unparseable timestamps return null
  assert.equal(formatPropertyRelativeTime(null, baseTime), null);
  assert.equal(formatPropertyRelativeTime(undefined, baseTime), null);
  assert.equal(formatPropertyRelativeTime('', baseTime), null);
  assert.equal(formatPropertyRelativeTime('invalid-date', baseTime), null);
});

test('formatExactPropertyTimestampIST formats authoritative timestamps in IST with timezone conversion', () => {
  // 2026-10-05 08:45:00 UTC == 2026-10-05 14:15:00 IST (UTC+5:30)
  const utcDate = '2026-10-05T08:45:00Z';
  const istFormatted = formatExactPropertyTimestampIST(utcDate);
  assert.equal(istFormatted, '5 Oct 2026, 2:15 PM IST');

  // Preserves non-mutation of input string
  assert.equal(utcDate, '2026-10-05T08:45:00Z');

  // Rejects invalid timestamps cleanly
  assert.equal(formatExactPropertyTimestampIST(null), null);
  assert.equal(formatExactPropertyTimestampIST(''), null);
  assert.equal(formatExactPropertyTimestampIST('invalid'), null);
});

test('SupportingPropertyCard consumes real property.updatedAt and renders approved card treatment', () => {
  // Uses real backend property.updatedAt
  assert.match(dashboardSource, /const parsedUpdatedDate = parsePropertyTimestamp\(property\.updatedAt\);/);
  assert.match(dashboardSource, /const relativeUpdated = formatPropertyRelativeTime\(parsedUpdatedDate\);/);
  assert.match(dashboardSource, /const exactUpdatedIST = formatExactPropertyTimestampIST\(parsedUpdatedDate\);/);

  // Renders <time> element with clock icon and exact IST title
  assert.match(dashboardSource, /<time className="tenant-v0-card-updated" dateTime=\{parsedUpdatedDate\.toISOString\(\)\} title=\{exactUpdatedIST \? `Updated \$\{exactUpdatedIST\}` : undefined\}>/);
  assert.match(dashboardSource, /<Clock3 size=\{12\} aria-hidden="true" \/>/);
  assert.match(dashboardSource, /<span>Updated \{relativeUpdated\}<\/span>/);

  // Does NOT contain prototype hardcoded timestamps
  assert.doesNotMatch(dashboardSource, /2026-10-05T08:45:00/);
  assert.doesNotMatch(dashboardSource, /2026-10-04T16:20:00/);
  assert.doesNotMatch(dashboardSource, /Updated 12 min ago/);
  assert.doesNotMatch(dashboardSource, /Updated 3 days ago/);

  // Missing timestamp cleanly omits the row
  assert.match(dashboardSource, /\{relativeUpdated && parsedUpdatedDate && <time/);
});

test('SavedHomeCard reuses SupportingPropertyCard, ensuring Explore and Saved cards share identical treatment', () => {
  // SavedHomeCard delegates directly to SupportingPropertyCard
  assert.match(dashboardSource, /const SavedHomeCard: React\.FC<[\s\S]*?> = props => \{\s*const reduceMotion = useReducedMotion\(\) === true;\s*return <SupportingPropertyCard \{\.\.\.props\} revealIndex=\{0\} reduceMotion=\{reduceMotion\} \/>;\s*\};/);
});

test('tenantV0.css defines approved styling for tenant-v0-card-updated across desktop and mobile', () => {
  // Base desktop style
  assert.match(styles, /\.tenant-v0-card-updated \{ display: flex; align-items: center; gap: 5px; margin-top: 9px; color: #85877f; font-size: 9px; line-height: 1\.4; \}/);
  assert.match(styles, /\.tenant-v0-card-updated svg \{ flex: 0 0 auto; color: #8b9688; \}/);
  assert.match(styles, /\.tenant-v0-card-updated span \{ min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; \}/);

  // Responsive tablet/compact
  assert.match(styles, /\.tenant-v0-card-updated \{ margin-top: 7px; font-size: 8px; \}/);

  // Responsive mobile <=600px
  assert.match(styles, /\.tenant-v0-card-updated \{ gap: 3\.5px; margin-top: 6px; font-size: 7\.5px; \}/);
  assert.match(styles, /\.tenant-v0-card-updated svg \{ width: 10px; height: 10px; \}/);
});
