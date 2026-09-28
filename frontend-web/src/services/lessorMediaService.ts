import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError, notifySessionExpired } from './apiError';

export interface LessorMediaItem {
  mediaId: string; filename: string; contentType: string; url: string | null;
  status: 'PENDING' | 'FAILED' | 'UPLOADED' | 'DELETING'; cover: boolean; sortOrder: number;
}
const base = (draftId: string) => `${API_ROOT_URL}/lessor/properties/drafts/${encodeURIComponent(draftId)}/media`;

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('pathome_auth_token');
  if (!token) { notifySessionExpired(); throw new ApiRequestError('Sign in to continue.', 401); }
  return { Authorization: `Bearer ${token}` };
}

async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(url, { ...init, headers: { Accept: 'application/json', ...authHeaders(), ...init.headers } });
  if (!response.ok) throw await createApiRequestError(response, 'The media action could not be completed.');
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export const lessorMediaService = {
  list: (draftId: string) => request<LessorMediaItem[]>(base(draftId)),
  recover: (draftId: string, mediaId: string) => request<LessorMediaItem>(`${base(draftId)}/${mediaId}/recover`, { method: 'POST' }),
  cover: (draftId: string, mediaId: string) => request<LessorMediaItem[]>(`${base(draftId)}/${mediaId}/cover`, { method: 'PUT' }),
  reorder: (draftId: string, ids: string[]) => request<LessorMediaItem[]>(`${base(draftId)}/order`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids) }),
  remove: (draftId: string, mediaId: string) => request<void>(`${base(draftId)}/${mediaId}`, { method: 'DELETE' }),
  upload(draftId: string, mediaId: string, file: File, onProgress: (percent: number) => void): Promise<LessorMediaItem> {
    const form = new FormData(); form.append('mediaId', mediaId); form.append('file', file);
    return new Promise((resolve, reject) => {
      let headers: Record<string, string>;
      try { headers = authHeaders(); } catch (error) { reject(error); return; }
      const xhr = new XMLHttpRequest();
      xhr.open('POST', base(draftId));
      xhr.setRequestHeader('Authorization', headers.Authorization);
      xhr.setRequestHeader('Accept', 'application/json');
      xhr.upload.onprogress = event => { if (event.lengthComputable) onProgress(Math.min(99, Math.round(event.loaded * 100 / event.total))); };
      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          try { resolve(JSON.parse(xhr.responseText) as LessorMediaItem); }
          catch { reject(new ApiRequestError('The upload response could not be read. Retry to reconcile it.')); }
          return;
        }
        if (xhr.status === 401) notifySessionExpired();
        let message = 'Upload failed. Retry to check the saved file.';
        try { message = JSON.parse(xhr.responseText)?.message || message; } catch {}
        reject(new ApiRequestError(message, xhr.status));
      };
      xhr.onerror = () => reject(new ApiRequestError('Connection lost. Retry to check whether your file was saved.'));
      xhr.send(form);
    });
  }
};
