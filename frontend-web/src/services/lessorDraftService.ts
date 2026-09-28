import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError, notifySessionExpired } from './apiError';

export type ResidentialType = 'FLAT' | 'HOUSE' | 'STUDIO' | 'PENTHOUSE' | 'SERVICED_APARTMENT';
export interface LessorBasics { propertyType: ResidentialType; rentalMode: 'LONG_TERM_RENTAL'; bhkCount: string | null }
export interface LessorPricing { monthlyRent: number | null; securityDeposit: number | null }
export interface LessorLocation { city: string; canonicalLocalityId: number | null; localityInput: string; address: string; landmark: string;
  resolutionType?: 'CANONICAL' | 'EXTERNAL_RESOLVED' | 'MANUAL_PENDING' | null;
  provider?: string | null; providerPlaceId?: string | null; selectionToken?: string | null }
export interface LessorDetails { availableFrom: string | null; furnishingStatus: string; totalAreaSqFt: number | null; floorNumber: number | null; totalFloors: number | null; amenities: string; description: string }
export interface LessorDraftData { basics: LessorBasics; pricing: LessorPricing | null; location: LessorLocation | null; details: LessorDetails | null }
export interface LessorDraft { draftId: string; status: string; version: number; completionPercent: number; data: LessorDraftData; createdAt: string; updatedAt: string;
  revisionOfListingId: number | null; reviewNote: string | null }
export interface LessorDraftSummary { draftId: string; title: string; status: string; completionPercent: number; updatedAt: string;
  propertyType: ResidentialType | null; bhkCount: string | null; city: string | null; locality: string | null;
  monthlyRent: number | null; coverUrl: string | null }
export interface LessorDraftPage { items: LessorDraftSummary[]; page: number; hasMore: boolean }
export type DraftSection = 'basics' | 'pricing' | 'location' | 'details';
export type DraftSectionValue = LessorBasics | LessorPricing | LessorLocation | LessorDetails;

const BASE = `${API_ROOT_URL}/lessor/properties/drafts`;
const GUEST_BASE = `${API_ROOT_URL}/lessor/guest/drafts`;

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('pathome_auth_token');
  if (!token) {
    notifySessionExpired();
    throw new ApiRequestError('Sign in to continue your property draft.', 401);
  }
  return { Authorization: `Bearer ${token}`, Accept: 'application/json' };
}

async function request<T>(url: string, init: RequestInit = {}, guest = false, authenticatedClaim = false): Promise<T> {
  const response = await fetch(url, { ...init, credentials: guest ? 'include' : 'same-origin',
    headers: { ...(guest ? { Accept: 'application/json' } : authHeaders()), ...init.headers } });
  if (!response.ok) throw await createApiRequestError(response, 'Unable to save your property right now.', guest && !authenticatedClaim);
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export const lessorDraftService = {
  create(basics: LessorBasics, guest = false) {
    return request<LessorDraft>(guest ? GUEST_BASE : BASE, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(basics) }, guest);
  },
  resumeGuest: async (): Promise<LessorDraft | null> => {
    const response = await fetch(`${GUEST_BASE}/resume`, { credentials: 'include', headers: { Accept: 'application/json' } });
    if (response.status === 204) return null;
    if (!response.ok) throw await createApiRequestError(response, 'Could not check your guest draft.', true);
    return response.json() as Promise<LessorDraft>;
  },
  claimGuest(draftId: string) {
    return request<LessorDraft>(`${GUEST_BASE}/${encodeURIComponent(draftId)}/claim`, { method: 'POST', headers: authHeaders() }, true, true);
  },
  get(draftId: string, guest = false) { return request<LessorDraft>(`${guest ? GUEST_BASE : BASE}/${encodeURIComponent(draftId)}`, {}, guest); },
  list(page = 0) { return request<LessorDraftPage>(`${BASE}?page=${page}`); },
  discard(draftId: string, guest = false) {
    return request<void>(`${guest ? GUEST_BASE : BASE}/${encodeURIComponent(draftId)}`, { method: 'DELETE' }, guest);
  },
  save(draftId: string, section: DraftSection, version: number, value: DraftSectionValue, guest = false) {
    return request<LessorDraft>(`${guest ? GUEST_BASE : BASE}/${encodeURIComponent(draftId)}/sections/${section}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', 'If-Match': String(version) },
      body: JSON.stringify(value)
    }, guest);
  }
};
