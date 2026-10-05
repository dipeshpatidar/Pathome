import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError } from './apiError';

export interface TenantVisitEntitlement {
  totalGrantedSessions: number | null;
  remainingSessions: number | null;
  reservedSessions: number | null;
  availableSessions: number | null;
  totalReconciliationRequired: boolean;
}

export const tenantVisitEntitlementService = {
  async getMine(signal?: AbortSignal): Promise<TenantVisitEntitlement> {
    const token = localStorage.getItem('pathome_auth_token');
    if (!token) throw new ApiRequestError('Please sign in to view your Visit Session balance.', 401);
    const response = await fetch(`${API_ROOT_URL}/tenant/visit-entitlement`, {
      headers: { Authorization: `Bearer ${token}` }, signal
    });
    if (!response.ok) {
      throw await createApiRequestError(response, 'Visit Session balance temporarily unavailable.', false, token);
    }
    const value = await response.json() as TenantVisitEntitlement;
    if (!value || typeof value.totalReconciliationRequired !== 'boolean') {
      throw new ApiRequestError('Visit Session balance temporarily unavailable.');
    }
    const count = (item: unknown): item is number => Number.isSafeInteger(item) && (item as number) >= 0;
    const complete = count(value.remainingSessions) && count(value.reservedSessions) && count(value.availableSessions);
    const empty = value.remainingSessions === null && value.reservedSessions === null && value.availableSessions === null;
    if (value.totalReconciliationRequired
      ? value.totalGrantedSessions !== null || (!complete && !empty)
      : !count(value.totalGrantedSessions) || !complete
        || value.remainingSessions !== (value.reservedSessions ?? 0) + (value.availableSessions ?? 0)
        || value.remainingSessions > value.totalGrantedSessions) {
      throw new ApiRequestError('Visit Session balance temporarily unavailable.');
    }
    if (complete && value.remainingSessions !== (value.reservedSessions ?? 0) + (value.availableSessions ?? 0)) {
      throw new ApiRequestError('Visit Session balance temporarily unavailable.');
    }
    return value;
  }
};
