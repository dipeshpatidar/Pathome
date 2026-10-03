import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError } from './apiError';

export interface VisitRepairItem {
  sessionId: number;
  version: number;
  city: string;
  previouslyScheduledAt: string | null;
  zoneId: string | null;
  assignedGroundExecutiveUserId: number | null;
  tenantConfirmationState: string;
  repairOperationId: string | null;
}
export interface VisitRepairQueuePage { items: VisitRepairItem[]; totalElements: number; page: number; size: number; totalPages: number }
export interface VisitRecommendationCandidate {
  groundExecutiveUserId: number;
  scheduledAt: string;
  reservedEndAt: string;
  durationMinutes: number;
  zoneId: string;
  rank: number;
  feasibilityStatus: string;
  travelConfidence: string;
  reasons: string[];
}
export interface VisitRecommendationView {
  sessionId: number;
  sessionVersion: number;
  status: string;
  diagnostics: string[];
  candidates: VisitRecommendationCandidate[];
}

async function request<T>(path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const authToken = localStorage.getItem('pathome_auth_token');
  if (!authToken) throw new ApiRequestError('Please sign in to review visit repairs.', 401);
  const response = await fetch(`${API_ROOT_URL}${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { Authorization: `Bearer ${authToken}`, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body), signal
  });
  if (!response.ok) throw await createApiRequestError(response, 'Visit repair information is unavailable. Please try again.', false, authToken);
  if (response.status === 204) return undefined as T;
  return await response.json() as T;
}

export const operationsVisitRepairService = {
  list(page = 0, signal?: AbortSignal) { return request<VisitRepairQueuePage>(`/operations/visit-repairs?page=${page}&size=20`, undefined, signal); },
  async recommendations(sessionId: number, expectedSessionVersion: number) {
    return request<VisitRecommendationView>(`/operations/visit-sessions/${sessionId}/recommendations`, {
      expectedSessionVersion
    });
  },
  approve(sessionId: number, candidate: VisitRecommendationCandidate, expectedSessionVersion: number) {
    return request<unknown>(`/operations/visit-sessions/${sessionId}/recommendations/approval`, {
      expectedSessionVersion,
      groundExecutiveUserId: candidate.groundExecutiveUserId,
      scheduledAt: candidate.scheduledAt,
      zoneId: candidate.zoneId,
      overrideReason: null
    });
  }
};
