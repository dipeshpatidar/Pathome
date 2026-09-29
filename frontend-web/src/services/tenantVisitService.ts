import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError } from './apiError';

export interface TenantVisitRequest {
  requestId: number;
  propertyId: number;
  propertyTitle: string;
  city: string | null;
  sector: string | null;
  propertyAvailable: boolean;
  coverImageUrl: string | null;
  preferredVisitTiming: string | null;
  status: string;
  requestedAt: string;
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
  }
};
