import type { TenantVisitRequest } from '../services/tenantVisitService';

export type TenantVisitView = 'loading' | 'error' | 'empty' | 'populated';

export function tenantVisitSummary(view: TenantVisitView, totalCount: number): string {
  if (view === 'loading') return 'Loading requests…';
  if (view === 'error') return 'Requests unavailable';
  if (view === 'empty') return 'No requests yet';
  const count = Number.isSafeInteger(totalCount) && totalCount > 0 ? totalCount : 0;
  return `${count} ${count === 1 ? 'request' : 'requests'} sent`;
}

export function tenantVisitView(status: 'loading' | 'ready' | 'error', requests: TenantVisitRequest[]): TenantVisitView {
  if (status === 'loading') return 'loading';
  if (status === 'error') return 'error';
  return requests.length === 0 ? 'empty' : 'populated';
}

export function appendUniqueVisitRequests(current: TenantVisitRequest[], next: TenantVisitRequest[]): TenantVisitRequest[] {
  const existingIds = new Set(current.map(request => request.requestId));
  const combined = [...current];
  for (const request of next) {
    if (existingIds.has(request.requestId)) continue;
    existingIds.add(request.requestId);
    combined.push(request);
  }
  return combined;
}

/** A missing result on one history page is unknown to the UI; the POST resolves it idempotently. */
export function tenantRequestStatusForProperty(requests: TenantVisitRequest[], propertyId: number): string | null {
  return requests.find(request => request.propertyId === propertyId)?.status ?? null;
}

export function tenantVisitStatusLabel(status: string | null | undefined): string {
  if (typeof status !== 'string' || !status.trim()) return 'Status unavailable';
  if (status === 'RECEIVED') return 'Request received';
  if (status === 'COORDINATING') return 'Coordinating with property owner';
  if (status === 'SCHEDULED') return 'Added to Visit Session';
  if (status === 'UNAVAILABLE') return 'Owner unavailable for visits';
  if (status === 'CANCELLED') return 'Request cancelled';
  const readable = status.trim().replace(/[_-]+/g, ' ').toLowerCase();
  return readable.charAt(0).toUpperCase() + readable.slice(1);
}

export function tenantVisitCtaLabel(status: string | null | undefined): string | null {
  if (status === 'UNKNOWN') return 'View My Visits';
  if (status === 'RECEIVED') return 'Visit Requested';
  if (status === 'COORDINATING') return 'Visit Being Coordinated';
  if (status === 'SCHEDULED') return 'View Visit';
  if (status === 'UNAVAILABLE' || status === 'CANCELLED') return 'View My Visits';
  return null;
}

export function tenantVisitAcknowledgement(status: string | null | undefined, created: boolean): { title: string; detail: string } {
  if (created && status === 'RECEIVED') return {
    title: 'Visit request received',
    detail: 'We’ll coordinate availability before a visit is confirmed.'
  };
  return {
    title: 'Your existing request was found',
    detail: status === 'UNAVAILABLE' ? 'This property owner is unavailable for visits. Review My Visits for the current request status.'
      : status === 'CANCELLED' ? 'This request was cancelled. Review My Visits for its current status.'
      : status === 'SCHEDULED' ? 'This request is already part of a Visit Session. Review My Visits for the current appointment details.'
      : 'Review My Visits for the current request status.'
  };
}

export function isActiveTenantVisitRequest(status: string | null | undefined): boolean {
  return status === 'RECEIVED' || status === 'COORDINATING' || status === 'SCHEDULED';
}
