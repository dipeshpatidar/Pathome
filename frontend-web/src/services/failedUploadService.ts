import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError } from './apiError';

const BASE_URL = `${API_ROOT_URL}/admin/failed-uploads`;

export interface FailedUpload {
  id: number;
  uploadRequestId: string | null;
  listingId: number | null;
  mediaType: 'IMAGE' | 'VIDEO_WALKTHROUGH' | string;
  originalFilename: string;
  fileSizeBytes: number | null;
  roomTag: string | null;
  failureStage: 'VALIDATION' | 'CLOUDINARY_UPLOAD' | 'DB_PERSIST' | string;
  failureReason: string;
  retryCount: number;
  status: 'FAILED' | 'RETRYING' | 'RESOLVED' | 'DISMISSED';
  resolvedMediaUrl: string | null;
  createdAt: string;
  updatedAt: string;
}

const getAuthHeaders = (): Record<string, string> => {
  const token = localStorage.getItem('pathome_auth_token');
  if (!token) {
    throw new ApiRequestError('Your session has ended. Please sign in again to continue.', 401);
  }
  return { Authorization: `Bearer ${token}` };
};

export const failedUploadService = {
  /**
   * Returns all unresolved failed uploads (FAILED + RETRYING), newest first.
   */
  async fetchUnresolved(): Promise<FailedUpload[]> {
    const response = await fetch(BASE_URL, {
      headers: { Accept: 'application/json', ...getAuthHeaders() }
    });
    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to load failed uploads. Please try again.');
    }
    return response.json();
  },

  /**
   * Returns the count of unresolved failures — used for the nav badge.
   */
  async fetchUnresolvedCount(): Promise<number> {
    try {
      const response = await fetch(`${BASE_URL}/count`, {
        headers: { Accept: 'application/json', ...getAuthHeaders() }
      });
      if (!response.ok) return 0;
      const data: { count: number } = await response.json();
      return data.count ?? 0;
    } catch {
      return 0;
    }
  },

  /**
   * Dismisses a failure record — it will no longer appear in the default view.
   */
  async dismiss(id: number): Promise<void> {
    const response = await fetch(`${BASE_URL}/${id}/dismiss`, {
      method: 'POST',
      headers: { Accept: 'application/json', ...getAuthHeaders() }
    });
    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to dismiss this entry. Please try again.');
    }
  },

  /**
   * Retries a failed upload. Optionally accepts a replacement file when the
   * original file is no longer in browser memory.
   */
  async retry(id: number, file?: File): Promise<void> {
    const formData = new FormData();
    if (file) formData.append('file', file);

    const response = await fetch(`${BASE_URL}/${id}/retry`, {
      method: 'POST',
      headers: getAuthHeaders(), // no Content-Type — let browser set multipart boundary
      body: formData
    });
    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to retry this upload. Please try again.');
    }
  }
};
