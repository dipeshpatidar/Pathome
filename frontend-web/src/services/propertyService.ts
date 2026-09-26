import { Property, PropertyMediaAsset, RoomTag } from '../types';
import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError, notifySessionExpired } from './apiError';
import { prepareMediaForUpload } from '../utils/imageOptimizer';
import { parseSecurityDeposit } from '../utils/discoveryCardData';
import { mapRentalSuggestion, RentalSearchFilters, RentalSuggestion, SuggestionRequestError } from '../utils/rentalSearch';

const API_BASE_URL = `${API_ROOT_URL}/properties`;
const MEDIA_UPLOAD_ATTEMPTS = 3;
const MEDIA_RETRY_DELAYS_MS = [800, 1_800];

export interface MediaUploadProgress {
  stage: 'preparing' | 'uploading' | 'retrying' | 'complete';
  percent: number;
  attempt: number;
  maxAttempts: number;
}

export interface MediaUploadOptions {
  onProgress?: (progress: MediaUploadProgress) => void;
  signal?: AbortSignal;
}

export interface SearchFeedbackPayload {
  eventType?: 'SUGGESTION_SELECTED' | 'SEARCH_EXECUTED';
  candidateTerm?: string;
  canonicalLocality?: string;
  canonicalCity?: string;
  selectedType?: string;
  selectedRank?: number;
  resolutionMethod?: string;
  fuzzyConfidence?: number;
  sessionId?: string;
  bhkKey?: string;
  propertyTypeKey?: string;
  furnishingKey?: string;
  searchExecuted?: boolean;
  resultCount?: number;
  zeroResult?: boolean;
}

export const getSearchSessionId = (): string => {
  try {
    const key = 'pathome_search_session_id';
    let id = sessionStorage.getItem(key);
    if (!id) {
      id = typeof crypto !== 'undefined' && crypto.randomUUID
        ? crypto.randomUUID()
        : `sess_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`;
      sessionStorage.setItem(key, id);
    }
    return id;
  } catch {
    return 'fallback_session';
  }
};

const getAdminAuthorizationHeader = (): Record<string, string> => {
  const token = localStorage.getItem('pathome_auth_token');
  if (!token) {
    notifySessionExpired();
    throw new ApiRequestError('Your session has ended. Please sign in again to continue.', 401);
  }
  return { Authorization: `Bearer ${token}` };
};

const wait = (durationMs: number): Promise<void> =>
  new Promise((resolve) => window.setTimeout(resolve, durationMs));

type PublicMediaPayload = {
  mediaUrl?: unknown;
  mediaType?: unknown;
  roomTag?: unknown;
  primaryCover?: unknown;
};

const isVideoMedia = (media: PublicMediaPayload): boolean => {
  const mediaType = typeof media.mediaType === 'string' ? media.mediaType.toUpperCase() : '';
  const url = typeof media.mediaUrl === 'string' ? media.mediaUrl.toLowerCase() : '';
  return mediaType === 'VIDEO_WALKTHROUGH' || mediaType === 'VIDEO' || /\.(mp4|webm|mov)(?:[?#]|$)/.test(url);
};

/** Maps the full public detail projection; uses `media` array from PublicPropertyResponse. */
const mapPublicProperty = (item: any): Property => {
  const media = Array.isArray(item?.media)
    ? item.media.filter((entry: PublicMediaPayload) => typeof entry?.mediaUrl === 'string' && entry.mediaUrl.trim())
    : [];
  const imageUrls = media.filter((entry: PublicMediaPayload) => !isVideoMedia(entry))
    .map((entry: PublicMediaPayload) => entry.mediaUrl as string);
  const videoUrl = media.find(isVideoMedia)?.mediaUrl as string | undefined;

  return {
    id: item.id,
    title: item.title || '',
    listingType: item.listingType || 'RENT',
    propertyType: item.propertyType || 'FLAT',
    city: item.city || '',
    sector: item.sector || '',
    bhk: item.bhk || item.bhkCount || '',
    monthlyRent: item.monthlyRent ? Number(item.monthlyRent) : 0,
    securityDeposit: parseSecurityDeposit(item.securityDeposit),
    maintenanceCharge: item.maintenanceCharge ? Number(item.maintenanceCharge) : undefined,
    askingPrice: item.askingPrice ? Number(item.askingPrice) : undefined,
    totalAreaSqFt: item.totalAreaSqFt ? Number(item.totalAreaSqFt) : 0,
    images: imageUrls,
    videoUrl: videoUrl || '',
    taggedMedia: media.map((entry: PublicMediaPayload) => ({
      listingId: item.id,
      mediaUrl: entry.mediaUrl as string,
      mediaType: entry.mediaType as any,
      roomTag: entry.roomTag as RoomTag,
      isPrimaryCover: Boolean(entry.primaryCover)
    })),
    verified: true,
    description: item.description || undefined,
    furnishingStatus: item.furnishingStatus || undefined,
    amenities: item.amenities || undefined,
    bathroomCount: typeof item.bathroomCount === 'number' ? item.bathroomCount : null,
    availableFrom: item.availableFrom || null,
    vastuFacing: item.vastuFacing || undefined,
    bachelorAllowed: typeof item.bachelorAllowed === 'boolean' ? item.bachelorAllowed : undefined,
    floor: typeof item.floorNumber === 'number' ? item.floorNumber : (typeof item.floor === 'number' ? item.floor : null),
    totalFloors: typeof item.totalFloors === 'number' ? item.totalFloors : null,
    preferredTenant: item.preferredTenant || null
  };
};

/**
 * Maps a single PublicDiscoveryResponse item (paginated list endpoint).
 * Each item carries only `coverImageUrl` for the landing card; no full gallery.
 */
const mapDiscoveryProperty = (item: any): Property => ({
  id: item.id,
  title: item.title || '',
  listingType: item.listingType || 'RENT',
  propertyType: item.propertyType || 'FLAT',
  city: item.city || '',
  sector: item.sector || '',
  bhk: item.bhk || item.bhkCount || '',
  monthlyRent: item.monthlyRent ? Number(item.monthlyRent) : 0,
  securityDeposit: parseSecurityDeposit(item.securityDeposit),
  maintenanceCharge: item.maintenanceCharge ? Number(item.maintenanceCharge) : undefined,
  totalAreaSqFt: item.totalAreaSqFt ? Number(item.totalAreaSqFt) : 0,
  // Discovery: a single cover URL in images[0], or [] if no media exists
  images: typeof item.coverImageUrl === 'string' && item.coverImageUrl.trim() ? [item.coverImageUrl.trim()] : [],
  coverRoomTag: typeof item.coverImageUrl === 'string' && item.coverImageUrl.trim()
    ? item.coverRoomTag ?? null : null,
  videoUrl: '',
  taggedMedia: [],
  verified: true,
  furnishingStatus: item.furnishingStatus || undefined,
  amenities: item.amenities || undefined,
  vastuFacing: item.vastuFacing || undefined,
  bachelorAllowed: typeof item.bachelorAllowed === 'boolean' ? item.bachelorAllowed : undefined,
  availableFrom: item.availableFrom || null,
  floor: typeof item.floorNumber === 'number' ? item.floorNumber : null,
  totalFloors: typeof item.totalFloors === 'number' ? item.totalFloors : null,
  preferredTenant: item.preferredTenant || null,
  // Carry mediaCount and hasVideo for badge display in the card
  _mediaCount: typeof item.mediaCount === 'number' ? item.mediaCount : undefined,
  _hasVideo: typeof item.hasVideo === 'boolean' ? item.hasVideo : false,
} as any);

export const createStableUploadRequestId = (
  propertyId: number,
  file?: File | null,
  draftMediaId?: string | null
): string => {
  const effectiveDraftMediaId = draftMediaId || (file as any)?.draftMediaId;
  if (effectiveDraftMediaId && typeof effectiveDraftMediaId === 'string' && effectiveDraftMediaId.trim()) {
    const cleanId = effectiveDraftMediaId.trim().replace(/[^a-zA-Z0-9_-]/g, '_');
    return `media-${propertyId}-${cleanId}`;
  }

  if (file) {
    const source = `${propertyId}:${file.name}:${file.size}:${file.lastModified}`;
    let hash = 2166136261;
    for (let index = 0; index < source.length; index += 1) {
      hash ^= source.charCodeAt(index);
      hash = Math.imul(hash, 16777619);
    }
    return `media-${propertyId}-${(hash >>> 0).toString(16)}`;
  }

  return `media-${propertyId}-${Date.now()}`;
};

const parseXhrError = (xhr: XMLHttpRequest, fallback: string): ApiRequestError => {
  let serverMessage = '';
  try {
    const payload = JSON.parse(xhr.responseText || '{}') as { message?: string };
    serverMessage = payload.message || '';
  } catch {
    // Proxies can return an empty or non-JSON error body.
  }

  if (xhr.status === 401) {
    notifySessionExpired();
    return new ApiRequestError('Your session has ended. Please sign in again to continue.', 401);
  }
  if (xhr.status === 403) return new ApiRequestError('You do not have permission to upload property media.', 403);
  if (xhr.status === 413) {
    return new ApiRequestError(
      serverMessage || 'The file exceeds the allowed size. Images must be 10 MB or smaller and videos 100 MB or smaller.',
      413
    );
  }
  return new ApiRequestError(serverMessage || fallback, xhr.status || undefined);
};

const shouldRetryUpload = (error: ApiRequestError): boolean =>
  !error.status || error.status === 408 || error.status === 429 || error.status >= 500;

const uploadFormData = <T>(
  url: string,
  formData: FormData,
  attempt: number,
  options: MediaUploadOptions,
  fallbackMessage: string
): Promise<T> => new Promise((resolve, reject) => {
  if (options.signal?.aborted) {
    reject(new ApiRequestError('Upload was cancelled because the network disconnected.', 0));
    return;
  }

  const xhr = new XMLHttpRequest();
  let settled = false;

  const onAbort = () => {
    if (settled) return;
    settled = true;
    try { xhr.abort(); } catch {}
    reject(new ApiRequestError('Upload was cancelled because the network disconnected.', 0));
  };

  if (options.signal) {
    options.signal.addEventListener('abort', onAbort, { once: true });
  }

  const cleanup = () => {
    settled = true;
    if (options.signal) {
      options.signal.removeEventListener('abort', onAbort);
    }
  };

  xhr.open('POST', url);
  xhr.timeout = 10 * 60 * 1000;
  Object.entries(getAdminAuthorizationHeader()).forEach(([name, value]) => xhr.setRequestHeader(name, value));

  xhr.upload.onprogress = (event) => {
    if (!event.lengthComputable || settled) return;
    options.onProgress?.({
      stage: 'uploading',
      percent: Math.min(99, Math.round((event.loaded / event.total) * 100)),
      attempt,
      maxAttempts: MEDIA_UPLOAD_ATTEMPTS
    });
  };
  xhr.onload = () => {
    cleanup();
    if (xhr.status >= 200 && xhr.status < 300) {
      try {
        resolve(JSON.parse(xhr.responseText) as T);
      } catch {
        reject(new ApiRequestError('The media was uploaded, but the server response could not be read.', xhr.status));
      }
      return;
    }
    reject(parseXhrError(xhr, fallbackMessage));
  };
  xhr.onerror = () => {
    cleanup();
    reject(new ApiRequestError('The media connection was interrupted.', 0));
  };
  xhr.ontimeout = () => {
    cleanup();
    reject(new ApiRequestError('The media upload took too long and was stopped.', 408));
  };
  xhr.send(formData);
});

const uploadWithRetry = async <T>(
  url: string,
  createFormData: () => FormData,
  options: MediaUploadOptions,
  fallbackMessage: string
): Promise<T> => {
  let lastError: ApiRequestError | null = null;
  for (let attempt = 1; attempt <= MEDIA_UPLOAD_ATTEMPTS; attempt += 1) {
    if (options.signal?.aborted || (typeof navigator !== 'undefined' && !navigator.onLine)) {
      throw new ApiRequestError('Upload was interrupted because the network disconnected.', 0);
    }
    try {
      const result = await uploadFormData<T>(url, createFormData(), attempt, options, fallbackMessage);
      options.onProgress?.({ stage: 'complete', percent: 100, attempt, maxAttempts: MEDIA_UPLOAD_ATTEMPTS });
      return result;
    } catch (error) {
      lastError = error instanceof ApiRequestError ? error : new ApiRequestError(fallbackMessage);
      if (options.signal?.aborted || (typeof navigator !== 'undefined' && !navigator.onLine)) {
        break;
      }
      if (attempt === MEDIA_UPLOAD_ATTEMPTS || !shouldRetryUpload(lastError)) break;
      options.onProgress?.({
        stage: 'retrying',
        percent: 0,
        attempt: attempt + 1,
        maxAttempts: MEDIA_UPLOAD_ATTEMPTS
      });
      await wait(MEDIA_RETRY_DELAYS_MS[attempt - 1]);
    }
  }
  throw lastError || new ApiRequestError(fallbackMessage);
};

export const propertyService = {
  /**
   * Fetches a single page of active properties from the paginated public discovery endpoint.
   * Returns at most 6 properties per page ordered latest-published first (id DESC).
   * Each property carries only the single best cover image URL.
   */
  async fetchDiscoveryPage(
    page: number,
    sector?: string,
    city?: string,
    signal?: AbortSignal,
    search?: Pick<RentalSearchFilters, 'q' | 'bhk' | 'propertyType' | 'furnishing' | 'minRent' | 'maxRent' | 'rentalOnly'>
  ): Promise<{ properties: Property[]; hasMore: boolean; page: number }> {
    const url = new URL(API_BASE_URL, window.location.origin);
    url.searchParams.set('page', String(page));
    if (sector) url.searchParams.append('sector', sector);
    if (city) url.searchParams.append('city', city);
    if (search?.q) url.searchParams.set('q', search.q);
    if (search?.bhk) url.searchParams.set('bhk', search.bhk);
    if (search?.propertyType) url.searchParams.set('propertyType', search.propertyType);
    if (search?.furnishing) url.searchParams.set('furnishing', search.furnishing);
    if (search?.minRent) url.searchParams.set('minRent', String(search.minRent));
    if (search?.maxRent) url.searchParams.set('maxRent', String(search.maxRent));
    if (search?.rentalOnly) url.searchParams.set('rentalOnly', 'true');

    const response = await fetch(url.toString(), {
      signal,
      headers: { 'Accept': 'application/json' }
    });
    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to load properties. Please try again.');
    }
    const body = await response.json();
    const items: Property[] = Array.isArray(body?.properties)
      ? body.properties.map(mapDiscoveryProperty)
      : [];
    return { properties: items, hasMore: Boolean(body?.hasMore), page: body?.page ?? page };
  },

  async fetchRentalSuggestions(query: string, city: string | undefined, signal: AbortSignal): Promise<RentalSuggestion[]> {
    const url = new URL(`${API_BASE_URL}/search-suggestions`, window.location.origin);
    url.searchParams.set('q', query);
    if (city) url.searchParams.set('city', city);
    url.searchParams.set('limit', '8');
    let response: Response;
    try {
      response = await fetch(url.toString(), { signal, headers: { Accept: 'application/json' } });
    } catch (error) {
      if (signal.aborted || (error instanceof Error && error.name === 'AbortError')) throw error;
      if (error instanceof TypeError) throw new SuggestionRequestError('network');
      throw error;
    }
    if (!response.ok) {
      await createApiRequestError(response, 'Search suggestions are unavailable.');
      throw new SuggestionRequestError('http', response.status);
    }
    let body: unknown;
    try {
      body = await response.json();
    } catch (error) {
      if (signal.aborted || (error instanceof Error && error.name === 'AbortError')) throw error;
      throw new SuggestionRequestError('json');
    }
    if (!body || typeof body !== 'object' || typeof (body as { query?: unknown }).query !== 'string'
        || !Array.isArray((body as { suggestions?: unknown }).suggestions)) {
      throw new SuggestionRequestError('contract');
    }
    const suggestions = (body as { suggestions: unknown[] }).suggestions
      .map(mapRentalSuggestion).filter((item: RentalSuggestion | null): item is RentalSuggestion => item !== null);
    if (suggestions.length !== (body as { suggestions: unknown[] }).suggestions.length) {
      throw new SuggestionRequestError('contract');
    }
    return suggestions;
  },

  /**
   * Reports lightweight, privacy-safe search feedback (fire-and-forget).
   */
  reportSearchFeedback(payload: SearchFeedbackPayload): void {
    try {
      const url = `${API_BASE_URL}/search-feedback`;
      const body = JSON.stringify({
        ...payload,
        sessionId: payload.sessionId || getSearchSessionId()
      });
      if (typeof navigator !== 'undefined' && navigator.sendBeacon) {
        const blob = new Blob([body], { type: 'application/json' });
        navigator.sendBeacon(url, blob);
      } else {
        fetch(url, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body,
          keepalive: true
        }).catch(() => {});
      }
    } catch {
      // Best-effort telemetry must never throw or affect UX
    }
  },

  /**
   * Fetches active properties from Spring Boot backend REST API
   * @deprecated Use fetchDiscoveryPage for paginated public discovery.
   */
  async fetchProperties(sector?: string, city?: string, signal?: AbortSignal): Promise<Property[]> {
    const result = await this.fetchDiscoveryPage(0, sector, city, signal);
    return result.properties;
  },

  async getPublicProperty(propertyId: number): Promise<Property> {
    const response = await fetch(`${API_BASE_URL}/${propertyId}`, {
      headers: { 'Accept': 'application/json' }
    });
    if (!response.ok) {
      throw await createApiRequestError(response, response.status === 404
        ? 'This property is no longer available.'
        : 'Unable to load this property. Please try again.');
    }
    return mapPublicProperty(await response.json());
  },

  async createVisitRequest(propertyId: number, payload: {
    budgetMin?: number;
    budgetMax?: number;
    preferredAreas?: string;
    moveInTiming?: string;
    preferredVisitTiming: string;
    note?: string;
  }): Promise<{ requestId: number; propertyId: number; status: string; message: string; receivedAt: string }> {
    const token = localStorage.getItem('pathome_auth_token');
    const response = await fetch(`${API_BASE_URL}/${propertyId}/visit-requests`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      },
      body: JSON.stringify(payload)
    });
    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to send your visit request. Please try again.');
    }
    return response.json();
  },

  /**
   * Admin Creates Property Directly in PostgreSQL Database
   */
  async createProperty(payload: any): Promise<any> {
    const response = await fetch(API_BASE_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
        ...getAdminAuthorizationHeader()
      },
      body: JSON.stringify(payload)
    });

    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to create this property. Please try again.');
    }

    return await response.json();
  },

  /**
   * Admin Uploads Single Tagged Photo/Video with Metadata to Cloudinary
   */
  async uploadTaggedMedia(
    propertyId: number, 
    file: File, 
    metadata: {
      roomTag: RoomTag;
      mediaType?: 'IMAGE' | 'VIDEO_WALKTHROUGH';
      caption?: string;
      isPrimaryCover?: boolean;
      sector?: string;
      priceTag?: string;
      vastuFacing?: string;
      draftMediaId?: string;
    },
    options: MediaUploadOptions = {}
  ): Promise<PropertyMediaAsset> {
    options.onProgress?.({ stage: 'preparing', percent: 0, attempt: 1, maxAttempts: MEDIA_UPLOAD_ATTEMPTS });
    const preparedFile = await prepareMediaForUpload(file);
    const draftMediaId = metadata.draftMediaId || (file as any)?.draftMediaId || (preparedFile as any)?.draftMediaId;
    const uploadRequestId = createStableUploadRequestId(propertyId, preparedFile, draftMediaId);

    const createFormData = (): FormData => {
      const formData = new FormData();
      formData.append('file', preparedFile);
      formData.append('roomTag', metadata.roomTag);
      formData.append('mediaType', metadata.mediaType || (preparedFile.type.startsWith('video/') ? 'VIDEO_WALKTHROUGH' : 'IMAGE'));
      formData.append('uploadRequestId', uploadRequestId);
      if (metadata.caption) formData.append('caption', metadata.caption);
      if (metadata.isPrimaryCover) formData.append('isPrimaryCover', String(metadata.isPrimaryCover));
      if (metadata.sector) formData.append('sector', metadata.sector);
      if (metadata.priceTag) formData.append('priceTag', metadata.priceTag);
      if (metadata.vastuFacing) formData.append('vastuFacing', metadata.vastuFacing);
      return formData;
    };

    return uploadWithRetry<PropertyMediaAsset>(
      `${API_BASE_URL}/${propertyId}/tagged-media`,
      createFormData,
      options,
      'Unable to upload this media file. Please try again.'
    );
  },

  /**
   * Fetch Rich Tagged Media Assets for a Property
   */
  async fetchTaggedMedia(propertyId: number): Promise<PropertyMediaAsset[]> {
    try {
      const response = await fetch(`${API_BASE_URL}/${propertyId}/tagged-media`, {
        headers: { 'Accept': 'application/json', ...getAdminAuthorizationHeader() }
      });
      if (!response.ok) throw await createApiRequestError(response, 'Unable to load saved property media.');
      return await response.json();
    } catch (err) {
      console.warn('Failed to fetch tagged media:', err);
      throw err;
    }
  },

  /**
   * Admin Uploads Photos to Cloudinary via Spring Boot REST API
   */
  async uploadPhotosToCloudinary(
    propertyId: number,
    files: File[],
    options: MediaUploadOptions = {}
  ): Promise<string[]> {
    const urls: string[] = [];
    for (let index = 0; index < files.length; index += 1) {
      const asset = await this.uploadTaggedMedia(propertyId, files[index], {
        roomTag: 'GENERAL',
        mediaType: 'IMAGE',
        isPrimaryCover: index === 0
      }, {
        onProgress: (progress) => options.onProgress?.({
          ...progress,
          percent: Math.round(((index + (progress.percent / 100)) / files.length) * 100)
        })
      });
      urls.push(asset.mediaUrl);
    }
    return urls;
  },

  /**
   * Admin Uploads Walkthrough Video to Cloudinary via Spring Boot REST API
   */
  async uploadVideoToCloudinary(
    propertyId: number,
    videoFile: File,
    options: MediaUploadOptions = {}
  ): Promise<string> {
    const asset = await this.uploadTaggedMedia(propertyId, videoFile, {
      roomTag: 'GENERAL',
      mediaType: 'VIDEO_WALKTHROUGH'
    }, options);
    return asset.mediaUrl;
  },

  /**
   * Calls Spring Boot backend REST API to produce a read-only, reviewable parsing result.
   */
  async parsePropertyPrompt(prompt: string, source: 'TYPED' | 'DICTATED' | 'MIXED' = 'TYPED'): Promise<any> {
    const response = await fetch(`${API_BASE_URL}/parse-prompt`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
        ...getAdminAuthorizationHeader()
      },
      body: JSON.stringify({ prompt, source })
    });

    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to read the property details. Please try again.');
    }

    return await response.json();
  },

  /**
   * Persists verified ParsedPropertyDTO to PostgreSQL database listings & rental_details tables
   */
  async createPropertyFromParsed(dto: any): Promise<any> {
    const response = await fetch(`${API_BASE_URL}/create-from-parsed`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
        ...getAdminAuthorizationHeader()
      },
      body: JSON.stringify(dto)
    });

    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to publish this property. Please try again.');
    }

    return await response.json();
  },

  /**
   * High-Performance Multi-Prompt & Voice Batch Parser API
   */
  async parseBatchPrompts(prompts: string, source: 'TYPED' | 'DICTATED' | 'MIXED' = 'TYPED'): Promise<any[]> {
    const response = await fetch(`${API_BASE_URL}/parse-batch`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
        ...getAdminAuthorizationHeader()
      },
      body: JSON.stringify({ prompts, source })
    });

    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to read the property details. Please try again.');
    }

    return await response.json();
  },

  /**
   * Batch Persist Verified Property Listings to PostgreSQL
   */
  async createBatchProperties(listings: any[], draftId?: string): Promise<any> {
    const response = await fetch(`${API_BASE_URL}/create-batch`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
        ...getAdminAuthorizationHeader()
      },
      body: JSON.stringify(draftId ? { draftId, listings } : { listings })
    });

    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to publish the selected properties. Please try again.');
    }

    return await response.json();
  },

  async getParserLearningStats(): Promise<any> {
    const response = await fetch(`${API_ROOT_URL}/parser-learning/stats`, {
      headers: {
        'Accept': 'application/json',
        ...getAdminAuthorizationHeader()
      }
    });
    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to load the learning review summary.');
    }
    return await response.json();
  },

  async getPendingParserLearningExamples(page = 0, size = 50): Promise<any> {
    const response = await fetch(
      `${API_ROOT_URL}/parser-learning/examples/pending?page=${page}&size=${size}`,
      {
        headers: {
          'Accept': 'application/json',
          ...getAdminAuthorizationHeader()
        }
      }
    );
    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to load examples awaiting learning review.');
    }
    return await response.json();
  },

  async approveParserLearningExample(id: string): Promise<any> {
    const response = await fetch(
      `${API_ROOT_URL}/parser-learning/examples/${encodeURIComponent(id)}/approve`,
      {
        method: 'POST',
        headers: {
          'Accept': 'application/json',
          ...getAdminAuthorizationHeader()
        }
      }
    );
    if (!response.ok) {
      throw await createApiRequestError(response, 'This example could not be approved for learning.');
    }
    return await response.json();
  },

  async rejectParserLearningExample(id: string, reason: string): Promise<any> {
    const response = await fetch(
      `${API_ROOT_URL}/parser-learning/examples/${encodeURIComponent(id)}/reject`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
          ...getAdminAuthorizationHeader()
        },
        body: JSON.stringify({ reason })
      }
    );
    if (!response.ok) {
      throw await createApiRequestError(response, 'This example could not be excluded from learning.');
    }
    return await response.json();
  }
};
