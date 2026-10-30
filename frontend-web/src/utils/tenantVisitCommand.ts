export const canUseCanonicalTenantVisitCommand = (
  operationsSessionActive: boolean,
  listingVersion: number | null | undefined
): boolean =>
  operationsSessionActive
  && typeof listingVersion === 'number'
  && Number.isSafeInteger(listingVersion)
  && listingVersion >= 0;
