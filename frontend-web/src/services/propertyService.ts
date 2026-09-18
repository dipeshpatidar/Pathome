import { Property, PropertyMediaAsset, RoomTag } from '../types';
import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError } from './apiError';
import { prepareMediaForUpload } from '../utils/imageOptimizer';

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
}

const getAdminAuthorizationHeader = (): Record<string, string> => {
  const token = localStorage.getItem('pathome_auth_token');
  if (!token) {
    throw new ApiRequestError('Your session has ended. Please sign in again to continue.', 401);
  }
  return { Authorization: `Bearer ${token}` };
};

const wait = (durationMs: number): Promise<void> =>
  new Promise((resolve) => window.setTimeout(resolve, durationMs));

const createStableUploadRequestId = (propertyId: number, file: File): string => {
  const source = `${propertyId}:${file.name}:${file.size}:${file.lastModified}`;
  let hash = 2166136261;
  for (let index = 0; index < source.length; index += 1) {
    hash ^= source.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return `media-${propertyId}-${(hash >>> 0).toString(16)}`;
};

const parseXhrError = (xhr: XMLHttpRequest, fallback: string): ApiRequestError => {
  let serverMessage = '';
  try {
    const payload = JSON.parse(xhr.responseText || '{}') as { message?: string };
    serverMessage = payload.message || '';
  } catch {
    // Proxies can return an empty or non-JSON error body.
  }

  if (xhr.status === 401) return new ApiRequestError('Your session has ended. Please sign in again to continue.', 401);
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
  const xhr = new XMLHttpRequest();
  xhr.open('POST', url);
  xhr.timeout = 10 * 60 * 1000;
  Object.entries(getAdminAuthorizationHeader()).forEach(([name, value]) => xhr.setRequestHeader(name, value));

  xhr.upload.onprogress = (event) => {
    if (!event.lengthComputable) return;
    options.onProgress?.({
      stage: 'uploading',
      percent: Math.min(99, Math.round((event.loaded / event.total) * 100)),
      attempt,
      maxAttempts: MEDIA_UPLOAD_ATTEMPTS
    });
  };
  xhr.onload = () => {
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
  xhr.onerror = () => reject(new ApiRequestError('The media connection was interrupted.', 0));
  xhr.ontimeout = () => reject(new ApiRequestError('The media upload took too long and was stopped.', 408));
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
    try {
      const result = await uploadFormData<T>(url, createFormData(), attempt, options, fallbackMessage);
      options.onProgress?.({ stage: 'complete', percent: 100, attempt, maxAttempts: MEDIA_UPLOAD_ATTEMPTS });
      return result;
    } catch (error) {
      lastError = error instanceof ApiRequestError ? error : new ApiRequestError(fallbackMessage);
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
   * Fetches active properties from Spring Boot backend REST API
   */
  async fetchProperties(sector?: string, city?: string): Promise<Property[]> {
    const url = new URL(API_BASE_URL, window.location.origin);
    if (sector) url.searchParams.append('sector', sector);
    if (city) url.searchParams.append('city', city);

    const response = await fetch(url.toString(), {
      headers: {
        'Accept': 'application/json'
      }
    });

    if (!response.ok) {
      throw await createApiRequestError(response, 'Unable to load properties. Please try again.');
    }

    const listings = await response.json();
    
    return listings.map((item: any) => {
      const rawMedia = item.mediaGalleryUrls ? item.mediaGalleryUrls.split(',') : [];
      const images = rawMedia.filter((m: string) => !m.endsWith('.mp4'));
      const video = rawMedia.find((m: string) => m.endsWith('.mp4'));

      return {
        id: item.id,
        title: item.title || '',
        listingType: item.listingType || 'RENT',
        propertyType: item.propertyType || 'FLAT',
        city: item.city || 'Indore',
        sector: item.sector || '',
        bhk: item.bhkCount || item.bhk || '',
        monthlyRent: item.monthlyRent ? Number(item.monthlyRent) : 0,
        securityDeposit: item.securityDeposit ? Number(item.securityDeposit) : 0,
        askingPrice: item.askingPrice ? Number(item.askingPrice) : undefined,
        totalAreaSqFt: item.totalAreaSqFt ? Number(item.totalAreaSqFt) : 0,
        images: images,
        videoUrl: video || '',
        verified: item.status === 'ACTIVE',
        ownerPhone: item.ownerPhoneNumber || '',
        latitude: item.latitude,
        longitude: item.longitude
      };
    });
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
    },
    options: MediaUploadOptions = {}
  ): Promise<PropertyMediaAsset> {
    options.onProgress?.({ stage: 'preparing', percent: 0, attempt: 1, maxAttempts: MEDIA_UPLOAD_ATTEMPTS });
    const preparedFile = await prepareMediaForUpload(file);
    const uploadRequestId = createStableUploadRequestId(propertyId, preparedFile);

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
      const response = await fetch(`${API_BASE_URL}/${propertyId}/tagged-media`);
      if (!response.ok) return [];
      return await response.json();
    } catch (err) {
      console.warn('Failed to fetch tagged media:', err);
      return [];
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
  async createBatchProperties(listings: any[]): Promise<any> {
    const response = await fetch(`${API_BASE_URL}/create-batch`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
        ...getAdminAuthorizationHeader()
      },
      body: JSON.stringify({ listings })
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
