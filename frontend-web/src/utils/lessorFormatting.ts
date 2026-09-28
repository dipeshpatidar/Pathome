const MONTH_NAMES = [
  'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'
] as const;

/**
 * Formats a property/draft modification timestamp for lessor card display.
 *
 * Rules:
 * - Same local calendar day: "Updated today · 10:30 AM"
 * - Previous local calendar day: "Updated yesterday · 6:15 PM"
 * - Older: "Updated 21 Oct 2026 · 10:40 PM"
 * - Returns null if the timestamp is missing or unparseable.
 */
export function formatLastUpdated(
  rawDate: string | number | Date | null | undefined,
  now: Date = new Date()
): string | null {
  if (!rawDate) return null;
  const date = typeof rawDate === 'object' && rawDate instanceof Date ? rawDate : new Date(rawDate);
  if (isNaN(date.getTime())) return null;

  let hours = date.getHours();
  const minutes = date.getMinutes();
  const ampm = hours >= 12 ? 'PM' : 'AM';
  hours = hours % 12;
  hours = hours ? hours : 12;
  const minuteStr = minutes < 10 ? `0${minutes}` : `${minutes}`;
  const timeStr = `${hours}:${minuteStr} ${ampm}`;

  const isSameDay = (d1: Date, d2: Date) =>
    d1.getFullYear() === d2.getFullYear() &&
    d1.getMonth() === d2.getMonth() &&
    d1.getDate() === d2.getDate();

  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1);

  if (isSameDay(date, now)) {
    return `Updated today · ${timeStr}`;
  }

  if (isSameDay(date, yesterday)) {
    return `Updated yesterday · ${timeStr}`;
  }

  const day = date.getDate();
  const month = MONTH_NAMES[date.getMonth()];
  const year = date.getFullYear();

  return `Updated ${day} ${month} ${year} · ${timeStr}`;
}
