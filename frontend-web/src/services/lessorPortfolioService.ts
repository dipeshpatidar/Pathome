import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError, notifySessionExpired } from './apiError';
import type { ResidentialType } from './lessorDraftService';
import type { LessorPreview } from './lessorSubmissionService';

export type ListingWorkflow = 'SUBMITTED' | 'UNDER_REVIEW' | 'CHANGES_REQUIRED' | 'PUBLISHED' | 'PAUSED' | 'ARCHIVED';
export interface LessorListingSummary { listingId: number; title: string; propertyType: ResidentialType;
  bhkCount: string; city: string; locality: string; monthlyRent: number; status: ListingWorkflow;
  updatedAt: string; coverUrl: string | null }
export interface LessorListingPage { items: LessorListingSummary[]; page: number; hasMore: boolean }
export interface LessorListingDetail { listingId: number; status: ListingWorkflow; preview: LessorPreview }

function authToken(): string {
  const token = localStorage.getItem('pathome_auth_token');
  if (!token) { notifySessionExpired(); throw new ApiRequestError('Sign in to continue.', 401); }
  return token;
}

export const lessorPortfolioService = {
  async list(page = 0): Promise<LessorListingPage> {
    const token = authToken();
    const response = await fetch(`${API_ROOT_URL}/lessor/properties?page=${page}`, {
      headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' }
    });
    if (!response.ok) throw await createApiRequestError(response, 'Unable to load your properties.');
    return response.json();
  },
  async get(listingId: number): Promise<LessorListingDetail> {
    const response = await fetch(`${API_ROOT_URL}/lessor/properties/${listingId}`, {
      headers: { Authorization: `Bearer ${authToken()}`, Accept: 'application/json' }
    });
    if (!response.ok) throw await createApiRequestError(response, 'Unable to open this property.');
    return response.json();
  }
};
