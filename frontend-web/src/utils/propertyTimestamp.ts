/**
 * Shared formatter for property card update timestamps.
 * Preserves the authoritative backend timestamp without mutating it.
 */

export const parsePropertyTimestamp = (timestamp: unknown): Date | null => {
  if (timestamp === null || timestamp === undefined || timestamp === '') return null;
  if (typeof timestamp === 'object' && timestamp instanceof Date) {
    return Number.isFinite(timestamp.getTime()) ? timestamp : null;
  }
  if (typeof timestamp === 'number') {
    const date = new Date(timestamp);
    return Number.isFinite(date.getTime()) ? date : null;
  }
  const date = new Date(String(timestamp));
  return Number.isFinite(date.getTime()) ? date : null;
};

/**
 * Returns a natural, compact relative time string:
 * - < 60 seconds: "just now"
 * - < 60 minutes: "N minute(s) ago"
 * - < 24 hours: "N hour(s) ago"
 * - 1 day: "yesterday"
 * - 2-6 days: "N days ago"
 * - 1-4 weeks: "N week(s) ago"
 * - 1-11 months: "N month(s) ago"
 * - >= 1 year: "N year(s) ago"
 *
 * Returns null if timestamp is missing or invalid.
 */
export const formatPropertyRelativeTime = (
  timestamp: unknown,
  nowMs: number = Date.now()
): string | null => {
  const date = parsePropertyTimestamp(timestamp);
  if (!date) return null;

  const timeMs = date.getTime();
  const diffSeconds = Math.floor((nowMs - timeMs) / 1000);

  // If timestamp is in the future or under 60 seconds, treat as "just now"
  if (diffSeconds < 60) {
    return 'just now';
  }

  const diffMinutes = Math.floor(diffSeconds / 60);
  if (diffMinutes < 60) {
    return `${diffMinutes} ${diffMinutes === 1 ? 'minute' : 'minutes'} ago`;
  }

  const diffHours = Math.floor(diffMinutes / 60);
  if (diffHours < 24) {
    return `${diffHours} ${diffHours === 1 ? 'hour' : 'hours'} ago`;
  }

  const diffDays = Math.floor(diffHours / 24);
  if (diffDays === 1) {
    return 'yesterday';
  }
  if (diffDays < 7) {
    return `${diffDays} days ago`;
  }

  const diffWeeks = Math.floor(diffDays / 7);
  if (diffWeeks < 5) {
    return `${diffWeeks} ${diffWeeks === 1 ? 'week' : 'weeks'} ago`;
  }

  const diffMonths = Math.floor(diffDays / 30);
  if (diffMonths < 12) {
    return `${diffMonths} ${diffMonths === 1 ? 'month' : 'months'} ago`;
  }

  const diffYears = Math.floor(diffDays / 365);
  return `${diffYears} ${diffYears === 1 ? 'year' : 'years'} ago`;
};

/**
 * Returns exact timestamp formatted in Indian Standard Time (IST)
 * Example: "5 Oct 2026, 2:34 PM IST"
 *
 * Returns null if timestamp is missing or invalid.
 */
export const formatExactPropertyTimestampIST = (
  timestamp: unknown
): string | null => {
  const date = parsePropertyTimestamp(timestamp);
  if (!date) return null;

  const formatter = new Intl.DateTimeFormat('en-IN', {
    timeZone: 'Asia/Kolkata',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    hour12: true
  });

  const parts = formatter.formatToParts(date);
  const day = parts.find((p) => p.type === 'day')?.value;
  const month = parts.find((p) => p.type === 'month')?.value;
  const year = parts.find((p) => p.type === 'year')?.value;
  const hour = parts.find((p) => p.type === 'hour')?.value;
  const minute = parts.find((p) => p.type === 'minute')?.value;
  const dayPeriod = parts.find((p) => p.type === 'dayPeriod')?.value?.toUpperCase() ?? '';

  if (!day || !month || !year || !hour || !minute) {
    return `${formatter.format(date)} IST`;
  }

  return `${day} ${month} ${year}, ${hour}:${minute} ${dayPeriod} IST`.trim();
};
