/** Preserve the difference between an explicitly nil deposit and missing data. */
export const parseSecurityDeposit = (value: unknown): number | null => {
  if (value === null || value === undefined || value === '') return null;
  const amount = typeof value === 'number' || typeof value === 'string' ? Number(value) : NaN;
  return Number.isFinite(amount) && amount >= 0 ? amount : null;
};

export const formatSecurityDeposit = (value: number | null): string =>
  value === null || !Number.isFinite(value) || value < 0
    ? 'Deposit on Request'
    : value === 0 ? 'Nil Deposit' : `₹${value.toLocaleString('en-IN')}`;

export const formatPropertyArea = (value: number | null | undefined): string | null =>
  typeof value === 'number' && Number.isFinite(value) && value > 0
    ? `${value.toLocaleString('en-IN')} sq ft`
    : null;
