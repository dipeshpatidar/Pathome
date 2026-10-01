import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError, notifySessionExpired } from './apiError';
import type { ResidentialType } from './lessorDraftService';
import type { LessorMediaItem } from './lessorMediaService';

export interface LessorPreview {
  title: string; propertyType: ResidentialType | null; bhkCount: string | null;
  city: string | null; locality: string | null; monthlyRent: number | null;
  securityDeposit: number | null; availableFrom: string | null;
  furnishingStatus: string | null; totalAreaSqFt: number | null;
  amenities: string | null; description: string | null; media: LessorMediaItem[]; missingRequirements: string[];
}
export interface LessorSubmission { listingId: number; draftId: string; title: string; status: string; submittedAt: string }

async function request<T>(draftId: string, action: 'preview' | 'submit', method: 'GET' | 'POST', guest = false): Promise<T> {
  const token = localStorage.getItem('pathome_auth_token');
  if (!token && !guest) { notifySessionExpired(); throw new ApiRequestError('Sign in to continue.', 401); }
  const response = await fetch(`${API_ROOT_URL}/lessor/${guest ? 'guest' : 'properties'}/drafts/${encodeURIComponent(draftId)}/${action}`,
    { method, credentials: guest ? 'include' : 'same-origin', headers: { ...(guest ? {} : { Authorization: `Bearer ${token}` }), Accept: 'application/json' } });
  if (!response.ok) throw await createApiRequestError(response, 'Your property could not be submitted right now.', guest, token);
  return response.json() as Promise<T>;
}

export const lessorSubmissionService = {
  preview: (draftId: string, guest = false) => request<LessorPreview>(draftId, 'preview', 'GET', guest),
  submit: (draftId: string) => request<LessorSubmission>(draftId, 'submit', 'POST')
};
