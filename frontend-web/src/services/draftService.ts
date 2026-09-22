import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError, notifySessionExpired } from './apiError';
import { RoomTag } from '../types';

const BASE_URL = `${API_ROOT_URL}/admin/drafts`;

export interface DraftSummary {
  draftId: string;
  draftType: 'SINGLE' | 'BATCH';
  status: string;
  titleSummary: string;
  itemCount: number;
  version: number;
  mediaCount: number;
  publishedPropertyId?: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface DraftMedia {
  mediaId: string;
  draftId: string;
  cardId?: string;
  originalFilename: string;
  fileSizeBytes: number;
  contentType: string;
  roomTag?: RoomTag;
  isCover: boolean;
  previewUrl: string;
  createdAt: string;
}

export interface DraftDetail {
  draftId: string;
  draftType: 'SINGLE' | 'BATCH';
  status: string;
  titleSummary: string;
  itemCount: number;
  version: number;
  payload: string;
  media: DraftMedia[];
  publishedPropertyId?: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface SaveDraftPayload {
  draftId: string;
  draftType: 'SINGLE' | 'BATCH';
  status?: string;
  titleSummary?: string;
  itemCount?: number;
  version?: number;
  payload: string;
}

export const getCurrentAdminEmail = (): string => {
  try {
    const raw = localStorage.getItem('pathome_user');
    if (raw) {
      const parsed = JSON.parse(raw);
      if (parsed?.email) return parsed.email.trim().toLowerCase();
    }
  } catch {}
  return 'admin';
};

const getAuthHeaders = (): Record<string, string> => {
  const token = localStorage.getItem('pathome_auth_token');
  if (!token) {
    notifySessionExpired();
    throw new ApiRequestError('Your session has ended. Please sign in again to continue.', 401);
  }
  return {
    Authorization: `Bearer ${token}`
  };
};

export const draftService = {
  /**
   * Retrieves all active drafts for current authenticated admin, newest first.
   */
  async listDrafts(): Promise<DraftSummary[]> {
    const response = await fetch(BASE_URL, {
      headers: { Accept: 'application/json', ...getAuthHeaders() }
    });
    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to load saved drafts.');
    }
    return response.json();
  },

  /**
   * Retrieves full details and staged media for a draft.
   */
  async getDraft(draftId: string): Promise<DraftDetail> {
    const response = await fetch(`${BASE_URL}/${encodeURIComponent(draftId)}`, {
      headers: { Accept: 'application/json', ...getAuthHeaders() }
    });
    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to load draft.');
    }
    return response.json();
  },

  /**
   * Creates or auto-saves a draft.
   */
  async saveDraft(data: SaveDraftPayload): Promise<DraftDetail> {
    const response = await fetch(BASE_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        ...getAuthHeaders()
      },
      body: JSON.stringify(data)
    });
    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to auto-save draft.');
    }
    return response.json();
  },

  /**
   * Discards a draft explicitly, removing all its temporary media from cloud storage.
   */
  async discardDraft(draftId: string): Promise<void> {
    const response = await fetch(`${BASE_URL}/${encodeURIComponent(draftId)}`, {
      method: 'DELETE',
      headers: getAuthHeaders()
    });
    if (!response.ok && response.status !== 404) {
      throw await createApiRequestError(response, 'Unable to discard draft.');
    }
  },

  /**
   * Streams an unpublished draft photo/video to private staging storage.
   */
  async stageMedia(
    draftId: string,
    file: File,
    options?: { cardId?: string; roomTag?: RoomTag; isCover?: boolean; mediaId?: string }
  ): Promise<DraftMedia> {
    const formData = new FormData();
    formData.append('file', file);
    if (options?.cardId) formData.append('cardId', options.cardId);
    if (options?.roomTag) formData.append('roomTag', options.roomTag);
    if (options?.isCover !== undefined) formData.append('isCover', String(options.isCover));
    const effectiveMediaId = options?.mediaId || (file as any)?.draftMediaId;
    if (effectiveMediaId) formData.append('mediaId', effectiveMediaId);

    const response = await fetch(`${BASE_URL}/${encodeURIComponent(draftId)}/media`, {
      method: 'POST',
      headers: getAuthHeaders(),
      body: formData
    });
    if (!response.ok) {
      throw await createApiRequestError(response, `Unable to upload ${file.name} to draft media staging.`);
    }
    const result: DraftMedia = await response.json();
    if (result && result.mediaId) {
      (file as any).draftMediaId = result.mediaId;
    }
    return result;
  },

  /**
   * Deletes a staged media file from draft storage.
   */
  async deleteMedia(draftId: string, mediaId: string): Promise<void> {
    const response = await fetch(
      `${BASE_URL}/${encodeURIComponent(draftId)}/media/${encodeURIComponent(mediaId)}`,
      {
        method: 'DELETE',
        headers: getAuthHeaders()
      }
    );
    if (!response.ok && response.status !== 404) {
      throw await createApiRequestError(response, 'Unable to delete draft media file.');
    }
  },

  /**
   * Reassigns unassigned media items in a draft to a target card ID.
   */
  async reassignCardMedia(draftId: string, targetCardId: string): Promise<number> {
    try {
      const response = await fetch(`${BASE_URL}/${encodeURIComponent(draftId)}/reassign-media`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json',
          ...getAuthHeaders()
        },
        body: JSON.stringify({ targetCardId })
      });
      if (!response.ok) {
        return 0;
      }
      const data = await response.json();
      return typeof data?.reassignedCount === 'number' ? data.reassignedCount : 0;
    } catch {
      return 0;
    }
  },

  /**
   * Reconciles a batch draft after partial publication: removes published cards and their media.
   */
  async reconcileBatch(
    draftId: string,
    publishedCardIds: string[],
    completedListings?: Array<{ cardId: string; listingId: number; title: string }>
  ): Promise<DraftDetail | null> {
    const response = await fetch(`${BASE_URL}/${encodeURIComponent(draftId)}/reconcile-batch`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        ...getAuthHeaders()
      },
      body: JSON.stringify({ publishedCardIds, completedListings })
    });
    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to reconcile batch draft.');
    }
    const data = await response.json();
    if (data && data.cleared) return null;
    return data;
  },

  /**
   * Cleans up single draft after confirmed publication.
   */
  async markPublished(draftId: string, listingId?: number): Promise<void> {
    try {
      await fetch(`${BASE_URL}/${encodeURIComponent(draftId)}/published`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...getAuthHeaders()
        },
        body: listingId ? JSON.stringify({ listingId }) : undefined
      });
    } catch {
      // Non-fatal cleanup
    }
  },

  /**
   * Returns authentication headers for draft API requests.
   */
  getAuthHeaders(): Record<string, string> {
    return getAuthHeaders();
  },

  /**
   * Formats media stream URL for draft preview with authentication query/header where required.
   */
  getMediaStreamUrl(draftId: string, mediaId: string): string {
    return `${BASE_URL}/${encodeURIComponent(draftId)}/media/${encodeURIComponent(mediaId)}`;
  },

  /**
   * Restores File objects and tagging from staged draft media items with bounded concurrency (3 parallel fetches).
   * Preserves original media order, room tags, cover index, and resilience to single-file errors.
   */
  async restoreMediaFiles(
    mediaItems: DraftMedia[],
    onProgress?: (loaded: number, total: number) => void
  ): Promise<{
    files: File[];
    tags: Record<number, RoomTag>;
    coverIndex: number;
    mediaIds: Record<number, string>;
    byMediaId: Map<string, File>;
  }> {
    if (!mediaItems || mediaItems.length === 0) {
      return { files: [], tags: {}, coverIndex: 0, mediaIds: {}, byMediaId: new Map() };
    }

    const CONCURRENCY_LIMIT = 3;
    const total = mediaItems.length;
    let completedCount = 0;

    // Slot-indexed results to guarantee preservation of original order
    const results: Array<{ file: File; roomTag?: RoomTag; isCover?: boolean; draftMediaId?: string } | null> = new Array(total).fill(null);

    let nextIndex = 0;
    const worker = async () => {
      while (nextIndex < total) {
        const i = nextIndex;
        nextIndex += 1;
        const item = mediaItems[i];

        try {
          const streamUrl = `${BASE_URL}/${encodeURIComponent(item.draftId)}/media/${encodeURIComponent(item.mediaId)}`;
          const res = await fetch(streamUrl, {
            headers: getAuthHeaders()
          });
          if (res.ok) {
            const blob = await res.blob();
            const file = new File([blob], item.originalFilename || `media_${i + 1}`, {
              type: item.contentType || 'image/jpeg'
            });
            (file as any).draftMediaId = item.mediaId;
            results[i] = {
              file,
              roomTag: item.roomTag,
              isCover: item.isCover,
              draftMediaId: item.mediaId
            };
          } else {
            console.warn(`Staged media preview returned HTTP ${res.status} for item: ${item.mediaId}`);
          }
        } catch (err) {
          console.warn('Unable to restore staged draft media item:', item.mediaId, err);
        } finally {
          completedCount += 1;
          if (onProgress) {
            try {
              onProgress(completedCount, total);
            } catch {}
          }
        }
      }
    };

    const workerCount = Math.min(CONCURRENCY_LIMIT, total);
    const workers: Promise<void>[] = [];
    for (let w = 0; w < workerCount; w += 1) {
      workers.push(worker());
    }
    await Promise.all(workers);

    // Reconstruct files, tags, and mediaId map in original order for all successful items
    const files: File[] = [];
    const tags: Record<number, RoomTag> = {};
    const mediaIds: Record<number, string> = {};
    const byMediaId = new Map<string, File>();
    let coverIndex = 0;

    for (let i = 0; i < total; i += 1) {
      const res = results[i];
      if (res) {
        files.push(res.file);
        const finalIndex = files.length - 1;
        if (res.roomTag) {
          tags[finalIndex] = res.roomTag;
        }
        if (res.isCover) {
          coverIndex = finalIndex;
        }
        if (res.draftMediaId) {
          mediaIds[finalIndex] = res.draftMediaId;
          byMediaId.set(res.draftMediaId, res.file);
        }
      }
    }

    return { files, tags, coverIndex, mediaIds, byMediaId };
  }
};
