export interface RoutingPageReconciliation {
  page: number;
  reload: boolean;
}

/**
 * Reconciles a requested routing page with the authoritative page count.
 *
 * An empty population has no page 1: its canonical displayed and request reference is index 0.
 * A positive population clamps an overrun to its last valid page once; a later response for that
 * valid page produces no further reload.
 */
export const reconcileRoutingPage = (
  requestedPage: number,
  totalPages: number
): RoutingPageReconciliation => {
  if (!Number.isSafeInteger(requestedPage) || requestedPage < 0) {
    return { page: 0, reload: false };
  }
  if (!Number.isSafeInteger(totalPages) || totalPages <= 0) {
    return { page: 0, reload: false };
  }
  const lastPage = totalPages - 1;
  return requestedPage > lastPage
    ? { page: lastPage, reload: true }
    : { page: requestedPage, reload: false };
};
