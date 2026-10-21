import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError } from './apiError';
import { RoomTag } from '../types';
import {
  UploaderSession,
  UploaderOperationLifetime,
  UploaderOperationRetiredError,
  assertUploaderSessionActive,
  isUploaderSessionActive,
  isUploaderSessionChangedError,
  resolveUploaderSession
} from '../utils/uploaderSession';

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

const getAuthHeaders = (session: UploaderSession): Record<string, string> => {
  return {
    Authorization: `Bearer ${session.token}`
  };
};

export const draftService = {
  /**
   * Retrieves all active drafts for current authenticated admin, newest first.
   */
  async listDrafts(capturedSession?: UploaderSession | null): Promise<DraftSummary[]> {
    const session = resolveUploaderSession(capturedSession);
    const response = await fetch(BASE_URL, {
      headers: { Accept: 'application/json', ...getAuthHeaders(session) }
    });
    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to load saved drafts.', false, session.token);
    }
    const result = await response.json();
    assertUploaderSessionActive(session);
    return result;
  },

  /**
   * Retrieves full details and staged media for a draft.
   */
  async getDraft(draftId: string, capturedSession?: UploaderSession | null): Promise<DraftDetail> {
    const session = resolveUploaderSession(capturedSession);
    const response = await fetch(`${BASE_URL}/${encodeURIComponent(draftId)}`, {
      headers: { Accept: 'application/json', ...getAuthHeaders(session) }
    });
    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to load draft.', false, session.token);
    }
    const result = await response.json();
    assertUploaderSessionActive(session);
    return result;
  },

  /**
   * Creates or auto-saves a draft.
   */
  async saveDraft(data: SaveDraftPayload, capturedSession?: UploaderSession | null): Promise<DraftDetail> {
    const session = resolveUploaderSession(capturedSession);
    const response = await fetch(BASE_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        ...getAuthHeaders(session)
      },
      body: JSON.stringify(data)
    });
    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to auto-save draft.', false, session.token);
    }
    const result = await response.json();
    assertUploaderSessionActive(session);
    return result;
  },

  /**
   * Discards a draft explicitly, removing all its temporary media from cloud storage.
   */
  async discardDraft(
    draftId: string,
    capturedSession?: UploaderSession | null,
    options?: { signal?: AbortSignal }
  ): Promise<void> {
    const session = resolveUploaderSession(capturedSession);
    const response = await fetch(`${BASE_URL}/${encodeURIComponent(draftId)}`, {
      method: 'DELETE',
      headers: getAuthHeaders(session),
      signal: options?.signal
    });
    if (!response.ok && response.status !== 404) {
      throw await createApiRequestError(response, 'Unable to discard draft.', false, session.token);
    }
    assertUploaderSessionActive(session);
  },

  /**
   * Streams an unpublished draft photo/video to private staging storage.
   */
  async stageMedia(
    draftId: string,
    file: File,
    options?: { cardId?: string; roomTag?: RoomTag; isCover?: boolean; mediaId?: string },
    capturedSession?: UploaderSession | null
  ): Promise<DraftMedia> {
    const session = resolveUploaderSession(capturedSession);
    const formData = new FormData();
    formData.append('file', file);
    if (options?.cardId) formData.append('cardId', options.cardId);
    if (options?.roomTag) formData.append('roomTag', options.roomTag);
    if (options?.isCover !== undefined) formData.append('isCover', String(options.isCover));
    const effectiveMediaId = options?.mediaId || (file as any)?.draftMediaId;
    if (effectiveMediaId) formData.append('mediaId', effectiveMediaId);

    const response = await fetch(`${BASE_URL}/${encodeURIComponent(draftId)}/media`, {
      method: 'POST',
      headers: getAuthHeaders(session),
      body: formData
    });
    if (!response.ok) {
      throw await createApiRequestError(response, `Unable to upload ${file.name} to draft media staging.`, false, session.token);
    }
    const result: DraftMedia = await response.json();
    assertUploaderSessionActive(session);
    if (result && result.mediaId) {
      (file as any).draftMediaId = result.mediaId;
    }
    return result;
  },

  /**
   * Deletes a staged media file from draft storage.
   */
  async deleteMedia(draftId: string, mediaId: string, capturedSession?: UploaderSession | null): Promise<void> {
    const session = resolveUploaderSession(capturedSession);
    const response = await fetch(
      `${BASE_URL}/${encodeURIComponent(draftId)}/media/${encodeURIComponent(mediaId)}`,
      {
        method: 'DELETE',
        headers: getAuthHeaders(session)
      }
    );
    if (!response.ok && response.status !== 404) {
      throw await createApiRequestError(response, 'Unable to delete draft media file.', false, session.token);
    }
    assertUploaderSessionActive(session);
  },

  /**
   * Reassigns unassigned media items in a draft to a target card ID.
   */
  async reassignCardMedia(draftId: string, targetCardId: string, capturedSession?: UploaderSession | null): Promise<number> {
    const session = resolveUploaderSession(capturedSession);
    try {
      const response = await fetch(`${BASE_URL}/${encodeURIComponent(draftId)}/reassign-media`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json',
          ...getAuthHeaders(session)
        },
        body: JSON.stringify({ targetCardId })
      });
      if (!response.ok) {
        throw await createApiRequestError(response, 'Unable to reassign draft media.', false, session.token);
      }
      const data = await response.json();
      assertUploaderSessionActive(session);
      return typeof data?.reassignedCount === 'number' ? data.reassignedCount : 0;
    } catch (error) {
      if (error instanceof ApiRequestError) throw error;
      throw new ApiRequestError('Unable to reassign draft media.');
    }
  },

  /**
   * Reconciles a batch draft after partial publication: removes published cards and their media.
   */
  async reconcileBatch(
    draftId: string,
    publishedCardIds: string[],
    completedListings?: Array<{ cardId: string; listingId: number; title: string }>,
    capturedSession?: UploaderSession | null
  ): Promise<DraftDetail | null> {
    const session = resolveUploaderSession(capturedSession);
    const response = await fetch(`${BASE_URL}/${encodeURIComponent(draftId)}/reconcile-batch`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        ...getAuthHeaders(session)
      },
      body: JSON.stringify({ publishedCardIds, completedListings })
    });
    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to reconcile batch draft.', false, session.token);
    }
    const data = await response.json();
    assertUploaderSessionActive(session);
    if (data && data.cleared) return null;
    return data;
  },

  /**
   * Cleans up single draft after confirmed publication.
   */
  async markPublished(draftId: string, listingId?: number, capturedSession?: UploaderSession | null): Promise<void> {
    const session = resolveUploaderSession(capturedSession);
    try {
      const response = await fetch(`${BASE_URL}/${encodeURIComponent(draftId)}/published`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...getAuthHeaders(session)
        },
        body: listingId ? JSON.stringify({ listingId }) : undefined
      });
      if (!response.ok) {
        throw await createApiRequestError(response, 'Unable to finalize the published draft.', false, session.token);
      }
      assertUploaderSessionActive(session);
    } catch (error) {
      if (isUploaderSessionChangedError(error)
          || (error instanceof ApiRequestError && (error.status === 401 || error.status === 403))) {
        throw error;
      }
      // Network-only finalization failures are non-fatal; the authoritative listing already exists.
    }
  },

  /**
   * Returns authentication headers for draft API requests.
   */
  getAuthHeaders(capturedSession?: UploaderSession | null): Record<string, string> {
    return getAuthHeaders(resolveUploaderSession(capturedSession));
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
    onProgress?: (loaded: number, total: number) => void,
    capturedSession?: UploaderSession | null,
    operation?: UploaderOperationLifetime
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
    const session = resolveUploaderSession(capturedSession);
    if (operation?.signal.aborted || (operation && !operation.isActive())) {
      throw new UploaderOperationRetiredError();
    }

    const CONCURRENCY_LIMIT = 3;
    const total = mediaItems.length;
    let completedCount = 0;
    let terminalError: unknown = null;
    let resolveTerminal: () => void = () => {};
    const terminalSignal = new Promise<void>(resolve => {
      resolveTerminal = resolve;
    });
    const restorationController = new AbortController();

    // Slot-indexed results to guarantee preservation of original order
    const results: Array<{ file: File; roomTag?: RoomTag; isCover?: boolean; draftMediaId?: string } | null> = new Array(total).fill(null);

    let nextIndex = 0;
    const terminate = (error: unknown): void => {
      if (terminalError) return;
      terminalError = error;
      restorationController.abort();
      resolveTerminal();
    };
    const sessionFailure = (error: unknown): boolean =>
      isUploaderSessionChangedError(error)
      || !isUploaderSessionActive(session)
      || (error instanceof ApiRequestError && (error.status === 401 || error.status === 403));
    const operationActive = (): boolean =>
      !terminalError
      && isUploaderSessionActive(session)
      && (!operation || (!operation.signal.aborted && operation.isActive()));
    const settleRetiredOperation = (): void => {
      terminate(new UploaderOperationRetiredError());
    };
    if (operation) {
      operation.signal.addEventListener('abort', settleRetiredOperation, { once: true });
    }

    const worker = async () => {
      while (operationActive() && nextIndex < total) {
        const i = nextIndex;
        nextIndex += 1;
        const item = mediaItems[i];

        try {
          const streamUrl = `${BASE_URL}/${encodeURIComponent(item.draftId)}/media/${encodeURIComponent(item.mediaId)}`;
          if (!operationActive()) return;
          const res = await fetch(streamUrl, {
            headers: getAuthHeaders(session),
            signal: restorationController.signal
          });
          if (!operationActive()) return;
          if (res.ok) {
            const blob = await res.blob();
            if (!operationActive()) return;
            const file = new File([blob], item.originalFilename || `media_${i + 1}`, {
              type: item.contentType || 'image/jpeg'
            });
            (file as any).draftMediaId = item.mediaId;
            if (!operationActive()) return;
            results[i] = {
              file,
              roomTag: item.roomTag,
              isCover: item.isCover,
              draftMediaId: item.mediaId
            };
          } else {
            throw await createApiRequestError(
              res, 'A saved draft preview could not be restored.', false, session.token);
          }
        } catch (err) {
          if (!operationActive()) {
            if (!terminalError) settleRetiredOperation();
            return;
          }
          if (sessionFailure(err)) {
            terminate(err);
            return;
          }
          console.warn('Unable to restore staged draft media item:', item.mediaId, err);
        } finally {
          if (operationActive()) completedCount += 1;
          if (operationActive() && onProgress) {
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
    // Abort cannot retract a fetch implementation that ignores the signal. The terminal signal lets
    // the denied operation reject immediately; each worker still checks terminalError before commit.
    await Promise.race([
      Promise.allSettled(workers),
      terminalSignal
    ]);
    if (operation) {
      operation.signal.removeEventListener('abort', settleRetiredOperation);
    }
    if (terminalError) throw terminalError;
    if (!operationActive()) throw new UploaderOperationRetiredError();

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
