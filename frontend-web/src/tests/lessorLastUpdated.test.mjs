import test from 'node:test';
import assert from 'node:assert/strict';
import { formatLastUpdated } from '../utils/lessorFormatting.ts';

test('formatLastUpdated formats same local calendar day as Updated today · hh:mm AM/PM', () => {
  // Reference now: 2026-10-21 14:00:00 (2:00 PM)
  const now = new Date(2026, 9, 21, 14, 0, 0);

  // Today at 10:30 AM
  const todayMorning = new Date(2026, 9, 21, 10, 30, 0);
  assert.equal(formatLastUpdated(todayMorning, now), 'Updated today · 10:30 AM');

  // Today at 11:45 PM
  const todayNight = new Date(2026, 9, 21, 23, 45, 0);
  assert.equal(formatLastUpdated(todayNight, now), 'Updated today · 11:45 PM');

  // Today at 12:05 AM (midnight)
  const todayMidnight = new Date(2026, 9, 21, 0, 5, 0);
  assert.equal(formatLastUpdated(todayMidnight, now), 'Updated today · 12:05 AM');

  // Today at 12:00 PM (noon)
  const todayNoon = new Date(2026, 9, 21, 12, 0, 0);
  assert.equal(formatLastUpdated(todayNoon, now), 'Updated today · 12:00 PM');
});

test('formatLastUpdated formats previous local calendar day as Updated yesterday · hh:mm AM/PM', () => {
  const now = new Date(2026, 9, 21, 14, 0, 0);

  // Yesterday at 6:15 PM
  const yesterdayEvening = new Date(2026, 9, 20, 18, 15, 0);
  assert.equal(formatLastUpdated(yesterdayEvening, now), 'Updated yesterday · 6:15 PM');

  // Yesterday at 9:00 AM
  const yesterdayMorning = new Date(2026, 9, 20, 9, 0, 0);
  assert.equal(formatLastUpdated(yesterdayMorning, now), 'Updated yesterday · 9:00 AM');
});

test('formatLastUpdated formats older date with short month name and 12-hour time', () => {
  const now = new Date(2026, 9, 25, 14, 0, 0);

  // 21 Oct 2026 at 10:40 PM
  const olderDate = new Date(2026, 9, 21, 22, 40, 0);
  assert.equal(formatLastUpdated(olderDate, now), 'Updated 21 Oct 2026 · 10:40 PM');

  // 5 Jan 2026 at 8:05 AM
  const janDate = new Date(2026, 0, 5, 8, 5, 0);
  assert.equal(formatLastUpdated(janDate, now), 'Updated 5 Jan 2026 · 8:05 AM');

  // String timestamp parsing
  assert.equal(formatLastUpdated(olderDate.toISOString(), now), 'Updated 21 Oct 2026 · 10:40 PM');
});

test('formatLastUpdated handles null, undefined, empty, and invalid timestamps gracefully', () => {
  assert.equal(formatLastUpdated(null), null);
  assert.equal(formatLastUpdated(undefined), null);
  assert.equal(formatLastUpdated(''), null);
  assert.equal(formatLastUpdated('invalid-date-string'), null);
});

test('My Properties card markup conditionally renders lastUpdated without badge/chip', () => {
  const renderCardMeta = (updatedAt, now) => {
    const lastUpdated = formatLastUpdated(updatedAt, now);
    if (!lastUpdated) return null;
    return `<p class="mt-2 text-xs text-slate-500">${lastUpdated}</p>`;
  };

  const now = new Date(2026, 9, 21, 14, 0, 0);
  const markup = renderCardMeta(new Date(2026, 9, 21, 10, 30, 0), now);
  assert.equal(markup, '<p class="mt-2 text-xs text-slate-500">Updated today · 10:30 AM</p>');

  // Null date should produce no element
  const nullMarkup = renderCardMeta(null, now);
  assert.equal(nullMarkup, null);
});
