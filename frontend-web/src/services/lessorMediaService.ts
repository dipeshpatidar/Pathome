import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError, notifySessionExpired } from './apiError';

export interface LessorMediaItem {
  mediaId: string; filename: string; contentType: string; url: string | null;
  status: 'PENDING' | 'FAILED' | 'STAGED' | 'UPLOADED' | 'DELETING'; cover: boolean; sortOrder: number;
}
const base = (draftId: string, guest = false) => `${API_ROOT_URL}/lessor/${guest ? 'guest' : 'properties'}/drafts/${encodeURIComponent(draftId)}/media`;

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('pathome_auth_token');
  if (!token) { notifySessionExpired(); throw new ApiRequestError('Sign in to continue.', 401); }
  return { Authorization: `Bearer ${token}` };
}

async function request<T>(url: string, init: RequestInit = {}, guest = false): Promise<T> {
  const response = await fetch(url, { ...init, credentials: guest ? 'include' : 'same-origin',
    headers: { Accept: 'application/json', ...(guest ? {} : authHeaders()), ...init.headers } });
  if (!response.ok) throw await createApiRequestError(response, 'The media action could not be completed.', guest);
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export const lessorMediaService = {
  list: (draftId: string, guest = false) => request<LessorMediaItem[]>(base(draftId, guest), {}, guest),
  recover: (draftId: string, mediaId: string, guest = false) => request<LessorMediaItem>(`${base(draftId, guest)}/${mediaId}/recover`, { method: 'POST' }, guest),
  cover: (draftId: string, mediaId: string, guest = false) => request<LessorMediaItem[]>(`${base(draftId, guest)}/${mediaId}/cover`, { method: 'PUT' }, guest),
  reorder: (draftId: string, ids: string[], guest = false) => request<LessorMediaItem[]>(`${base(draftId, guest)}/order`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids) }, guest),
  remove: (draftId: string, mediaId: string, guest = false) => request<void>(`${base(draftId, guest)}/${mediaId}`, { method: 'DELETE' }, guest),
  promote: (draftId: string) => request<LessorMediaItem[]>(`${base(draftId)}/promote`, { method: 'POST' }),
  upload(draftId: string, mediaId: string, file: File, onProgress: (percent: number) => void, guest = false): Promise<LessorMediaItem> {
    const form = new FormData(); form.append('mediaId', mediaId); form.append('file', file);
    return new Promise((resolve, reject) => {
      let headers: Record<string, string>;
      try { headers = guest ? {} : authHeaders(); } catch (error) { reject(error); return; }
      const xhr = new XMLHttpRequest();
      xhr.open('POST', base(draftId, guest));
      xhr.withCredentials = guest;
      if (!guest) xhr.setRequestHeader('Authorization', headers.Authorization);
      xhr.setRequestHeader('Accept', 'application/json');
      xhr.upload.onprogress = event => { if (event.lengthComputable) onProgress(Math.min(99, Math.round(event.loaded * 100 / event.total))); };
      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          try { resolve(JSON.parse(xhr.responseText) as LessorMediaItem); }
          catch { reject(new ApiRequestError('The upload response could not be read. Retry to reconcile it.')); }
          return;
        }
        if (xhr.status === 401 && !guest) notifySessionExpired();
        let message = 'Upload failed. Retry to check the saved file.';
        try { message = JSON.parse(xhr.responseText)?.message || message; } catch {}
        reject(new ApiRequestError(message, xhr.status));
      };
      xhr.onerror = () => reject(new ApiRequestError('Connection lost. Retry to check whether your file was saved.'));
      xhr.send(form);
    });
  }
};
