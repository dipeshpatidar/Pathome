export const operationalVisitTime = (value: string | null | undefined, zoneId: string | null | undefined = null) => {
  if (!value) return 'Time unavailable';
  const instant = new Date(value);
  if (Number.isNaN(instant.getTime())) return 'Time unavailable';
  const zone = zoneId || 'UTC';
  try {
    return `${new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short', timeZone: zone }).format(instant)} · ${zone}`;
  } catch {
    return 'Time unavailable';
  }
};

export const visitStartFeedback = (status: string, resultCode: string) => {
  if (status === 'STARTED') return 'Visit started. The planned duration is now being tracked from the actual start.';
  if (resultCode === 'ALTERNATE_GE_ASSIGNED') return 'This visit moved to another Ground Executive. You did not start it.';
  if (resultCode === 'REPAIR_REQUIRED') return 'The visit did not start. Operations is arranging a safe recovery.';
  return 'The visit did not start. Refresh the latest visit state before retrying.';
};

export const boundedVisitPage = (page: number, totalPages: number) =>
  totalPages > 0 ? Math.min(page, totalPages - 1) : 0;

export const secondsUntilVisitCodeTime = (timestamp: string | null | undefined, now: number): number | null => {
  if (!timestamp) return null;
  const target = Date.parse(timestamp);
  return Number.isFinite(target) ? Math.max(0, Math.ceil((target - now) / 1000)) : null;
};
