import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError, notifySessionExpired } from './apiError';

export type LessorSubmissionProgressStatus =
  | 'PREPARING'
  | 'PROCESSING_MEDIA'
  | 'SAVING_PROPERTY'
  | 'COMPLETED'
  | 'FAILED';

export interface LessorSubmissionProgress {
  submissionId: string;
  status: LessorSubmissionProgressStatus;
  percent: number;
  processedMedia: number;
  totalMedia: number;
  messageCode: LessorSubmissionProgressStatus;
  completed: boolean;
  failed: boolean;
}

async function request(draftId: string, method: 'GET' | 'POST'): Promise<LessorSubmissionProgress> {
  const token = localStorage.getItem('pathome_auth_token');
  if (!token) {
    notifySessionExpired();
    throw new ApiRequestError('Sign in to continue.', 401);
  }
  const response = await fetch(
    `${API_ROOT_URL}/lessor/properties/drafts/${encodeURIComponent(draftId)}/submission-progress`,
    { method, credentials: 'same-origin', headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' } }
  );
  if (!response.ok) {
    throw await createApiRequestError(response, 'Submission progress could not be read.', false, token);
  }
  return response.json() as Promise<LessorSubmissionProgress>;
}

export const lessorSubmissionProgressService = {
  start: (draftId: string) => request(draftId, 'POST'),
  get: (draftId: string) => request(draftId, 'GET')
};
