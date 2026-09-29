import type { TenantVisitRequest } from '../services/tenantVisitService';

export type TenantVisitView = 'loading' | 'error' | 'empty' | 'populated';

export function tenantVisitView(status: 'loading' | 'ready' | 'error', requests: TenantVisitRequest[]): TenantVisitView {
  if (status === 'loading') return 'loading';
  if (status === 'error') return 'error';
  return requests.length === 0 ? 'empty' : 'populated';
}

export function appendUniqueVisitRequests(current: TenantVisitRequest[], next: TenantVisitRequest[]): TenantVisitRequest[] {
  const existingIds = new Set(current.map(request => request.requestId));
  return [...current, ...next.filter(request => !existingIds.has(request.requestId))];
}

export function tenantVisitStatusLabel(status: string): string {
  return status === 'RECEIVED' ? 'Request received' : status.replace(/_/g, ' ').toLowerCase();
}
