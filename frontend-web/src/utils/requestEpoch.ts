/**
 * Returns true only when an asynchronous response belongs to the latest request
 * for the current view or identity.
 */
export const isLatestRequest = (responseEpoch: number, currentEpoch: number): boolean =>
  responseEpoch === currentEpoch;
