import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError } from './apiError';

export interface TenantVisitRequest {
  requestId: number;
  version: number;
  propertyId: number;
  propertyTitle: string;
  propertyType: string | null;
  bhk: string | null;
  city: string | null;
  sector: string | null;
  propertyAvailable: boolean;
  coverImageUrl: string | null;
  preferredVisitTiming: string | null;
  status: string;
  requestedAt: string;
  availabilityStartAt: string | null;
  availabilityEndAt: string | null;
  availabilityZoneId: string | null;
  preferredAt: string | null;
}

export interface TenantAvailabilityCommand {
  expectedRequestVersion: number;
  availabilityStartAt: string | null;
  availabilityEndAt: string | null;
  availabilityZoneId: string | null;
  preferredAt: string | null;
}

export interface TenantAvailabilityView {
  requestId: number;
  version: number;
  availabilityStartAt: string | null;
  availabilityEndAt: string | null;
  availabilityZoneId: string | null;
  preferredAt: string | null;
}

export interface TenantVisitRequestPage {
  userId: number;
  requests: TenantVisitRequest[];
  totalCount: number;
  page: number;
  hasMore: boolean;
}

export const tenantVisitService = {
  async list(pageNumber = 0, signal?: AbortSignal): Promise<TenantVisitRequestPage> {
    const token = localStorage.getItem('pathome_auth_token');
    if (!token) throw new ApiRequestError('Please sign in to view your visit requests.', 401);
    const response = await fetch(`${API_ROOT_URL}/tenant/visit-requests?page=${pageNumber}`, {
      headers: { Authorization: `Bearer ${token}` },
      signal
    });
    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to load your visit requests. Please try again.', false, token);
    }
    const result = await response.json() as TenantVisitRequestPage;
    if (!result || !Number.isSafeInteger(result.userId) || result.userId <= 0
      || !Array.isArray(result.requests) || !Number.isSafeInteger(result.totalCount) || result.totalCount < 0
      || typeof result.hasMore !== 'boolean' || result.page !== pageNumber) {
      throw new ApiRequestError('Unable to load your visit requests. Please try again.');
    }
    return result;
  },

  /**
   * Updates only the signed-in tenant's own pre-booking availability. There is no automatic retry:
   * an ambiguous failure is surfaced to the caller, which then re-reads authoritative state.
   */
  async updateAvailability(
    requestId: number,
    command: TenantAvailabilityCommand,
    signal?: AbortSignal
  ): Promise<TenantAvailabilityView> {
    const token = localStorage.getItem('pathome_auth_token');
    if (!token) throw new ApiRequestError('Please sign in to update your available times.', 401);
    if (!Number.isSafeInteger(requestId) || requestId <= 0) {
      throw new ApiRequestError('This visit request is not available to update.');
    }
    const response = await fetch(`${API_ROOT_URL}/tenant/visit-requests/${requestId}/availability`, {
      method: 'PUT',
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: 'application/json',
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(command),
      cache: 'no-store',
      signal
    });
    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to update your available times. Please try again.', false, token);
    }
    const result = await response.json() as TenantAvailabilityView;
    const nullableDate = (value: unknown): value is string | null =>
      value === null || (typeof value === 'string' && Number.isFinite(Date.parse(value)));
    if (!result || result.requestId !== requestId || !Number.isSafeInteger(result.version)
      || result.version < 0
      || !nullableDate(result.availabilityStartAt)
      || !nullableDate(result.availabilityEndAt)
      || !(result.availabilityZoneId === null || typeof result.availabilityZoneId === 'string')
      || !nullableDate(result.preferredAt)) {
      throw new ApiRequestError('Your available times were sent, but the result could not be verified. Reload your visits.');
    }
    return result;
  }
};
