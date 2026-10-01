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

export function tenantVisitStatusLabel(status: string | null | undefined): string {
  if (typeof status !== 'string' || !status.trim()) return 'Status unavailable';
  if (status === 'RECEIVED') return 'Request received';
  const readable = status.trim().replace(/[_-]+/g, ' ').toLowerCase();
  return readable.charAt(0).toUpperCase() + readable.slice(1);
}
