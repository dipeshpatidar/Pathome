import { API_ROOT_URL } from '../config/endpoints.ts';
import { ApiRequestError, createApiRequestError, notifySessionExpired } from './apiError.ts';
import {
  captureOperationsSession,
  commitParsedSessionResponse,
  isOperationsSessionActive,
  OperationsSessionChangedError,
  type OperationsSession
} from '../utils/operationsSession.ts';
import { createOperationId } from '../utils/operationsOperationId.ts';

export type Workflow = 'SUBMITTED' | 'UNDER_REVIEW' | 'CHANGES_REQUIRED' | 'PUBLISHED' | 'PAUSED' | 'ARCHIVED';
export type PropertyType = 'FLAT' | 'HOUSE' | 'STUDIO' | 'PENTHOUSE' | 'SERVICED_APARTMENT';
export type RoomTag = 'GENERAL' | 'LIVING_ROOM' | 'MASTER_BEDROOM' | 'BEDROOM' | 'KITCHEN'
  | 'BATHROOM' | 'BALCONY' | 'EXTERIOR' | 'AMENITIES' | 'FLOOR_PLAN';
export type MediaType = 'IMAGE' | 'VIDEO_WALKTHROUGH';

export interface StaffMedia {
  sourceMediaAssetId: number | null;
  mediaId: string;
  url: string | null;
  mediaType: MediaType;
  roomTag: RoomTag;
  caption: string | null;
  sortOrder: number | null;
  cover: boolean;
  uploadStatus: string;
}

export interface StaffSnapshot {
  title: string | null;
  titleProvenance: string | null;
  description: string | null;
  propertyType: PropertyType;
  bhkCount: string;
  monthlyRent: number;
  securityDeposit: number;
  availableFrom: string;
  maintenanceCharge: number | null;
  brokerageAmount: number | null;
  brokerageDays: number | null;
  securityDepositMonths: number | null;
  totalAreaSqFt: number | null;
  floorNumber: number | null;
  totalFloors: number | null;
  furnishingStatus: string | null;
  amenities: string | null;
  bathroomCount: number | null;
  vastuFacing: string | null;
  bachelorAllowed: boolean | null;
  preferredTenant: string | null;
  address: string;
  landmark: string | null;
  colony: string | null;
  pincode: string | null;
  media: StaffMedia[];
}

export interface PropertySummary {
  listingId: number;
  title: string;
  city: string;
  locality: string;
  coverUrl: string | null;
  monthlyRent: number;
  status: Workflow;
  version: number;
  updatedAt: string;
  openRevisionDraftId: string | null;
  openRevisionStatus: string | null;
}

export interface PropertyDetail {
  listingId: number;
  status: Workflow;
  version: number;
  city: string;
  locality: string;
  content: StaffSnapshot;
  openRevisionDraftId: string | null;
  openRevisionStatus: string | null;
  openRevisionVersion: number | null;
  openRevisionMode: string | null;
  canSupersedeRevision: boolean;
  publicationVersion: number;
  publishedAt: string | null;
  lastContentPublishedAt: string | null;
}

export interface FieldChange {
  field: string;
  label: string;
  before: string;
  after: string;
}

export interface Preview {
  ready: boolean;
  blockers: string[];
  warnings: string[];
  changes: FieldChange[];
  baseVersion: number;
  currentVersion: number;
}

export interface StaffRevision {
  draftId: string;
  listingId: number;
  revisionMode: string;
  status: string;
  version: number;
  revisionBaseVersion: number;
  content: StaffSnapshot;
  changes: FieldChange[];
  preview: Preview;
}

export interface Activity {
  eventId: string;
  kind: 'PUBLICATION' | 'REVISION';
  eventType: string;
  draftId: string | null;
  actorUserId: number;
  actorDisplayName: string;
  occurredAt: string;
  version: number | null;
  summary: string;
  details: Record<string, unknown>;
}

export interface PublishResult {
  listingId: number;
  listingVersion: number;
  publicationVersion: number;
  publishedAt: string;
  status: Workflow;
}

export interface CommandOutcome {
  operationId: string;
  commandType: 'PROPERTY_PUBLISH' | 'PROPERTY_LIFECYCLE';
  listingId: number;
  resultVersion: number;
  listingVersion: number;
  workflowStatus: Workflow;
  committedAt: string;
}

export interface UploadedStaffMedia {
  mediaId: string;
  url: string;
  mediaType: MediaType;
  roomTag: RoomTag;
  caption: string | null;
  sortOrder: number;
  cover: boolean;
  uploadStatus: string;
}

const sessionOrThrow = (): OperationsSession => {
  const session = captureOperationsSession();
  if (!session) {
    notifySessionExpired();
    throw new ApiRequestError('Sign in to continue.', 401);
  }
  return session;
};

const request = async <T>(
  path: string,
  options: RequestInit = {},
  session: OperationsSession = sessionOrThrow(),
  fallback = 'This property action could not be completed.'
): Promise<T> => {
  if (!isOperationsSessionActive(session)) throw new ApiRequestError('The signed-in account changed.', 401);
  const response = await fetch(`${API_ROOT_URL}${path}`, {
    ...options,
    cache: 'no-store',
    headers: {
      Authorization: `Bearer ${session.token}`,
      Accept: 'application/json',
      ...(options.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
      ...(options.headers ?? {})
    }
  });
  if (!response.ok) {
    const error = await createApiRequestError(response, fallback, false, session.token);
    if (!isOperationsSessionActive(session)) throw new OperationsSessionChangedError();
    throw error;
  }
  const parsed = await response.json() as T;
  return commitParsedSessionResponse(session, parsed);
};

const mutate = <T>(path: string, version: number, body: unknown, action: string): Promise<T> =>
  request<T>(path, {
    method: 'PUT',
    headers: { 'If-Match': String(version) },
    body: JSON.stringify(body)
  }, sessionOrThrow(), action);

export const newOperationId = (): string => createOperationId();

export const backofficePropertyService = {
  list(page = 0, q = '', session = sessionOrThrow()): Promise<{
    items: PropertySummary[]; page: number; hasMore: boolean;
  }> {
    return request(`/backoffice/properties?page=${page}&q=${encodeURIComponent(q)}`,
      {}, session, 'Properties could not be loaded.');
  },

  detail(listingId: number, session = sessionOrThrow()): Promise<PropertyDetail> {
    return request(`/backoffice/properties/${listingId}`, {}, session,
      'This property could not be opened.');
  },

  startRevision(listingId: number, session = sessionOrThrow()): Promise<StaffRevision> {
    return request(`/backoffice/properties/${listingId}/revisions`, { method: 'POST' },
      session, 'A staff revision could not be started.');
  },

  correctiveRevision(listingId: number, publicationVersion: number,
                     session = sessionOrThrow()): Promise<StaffRevision> {
    return request(`/backoffice/properties/${listingId}/revisions/corrective?publicationVersion=${publicationVersion}`,
      { method: 'POST' }, session, 'A corrective revision could not be started.');
  },

  revision(listingId: number, draftId: string, session = sessionOrThrow()): Promise<StaffRevision> {
    return request(`/backoffice/properties/${listingId}/revisions/${encodeURIComponent(draftId)}`,
      {}, session, 'This staff revision could not be opened.');
  },

  saveBasics(listingId: number, draftId: string, version: number, body: unknown): Promise<StaffRevision> {
    return mutate(`/backoffice/properties/${listingId}/revisions/${encodeURIComponent(draftId)}/basics`,
      version, body, 'Property basics could not be saved.');
  },

  saveCommercial(listingId: number, draftId: string, version: number, body: unknown): Promise<StaffRevision> {
    return mutate(`/backoffice/properties/${listingId}/revisions/${encodeURIComponent(draftId)}/commercial`,
      version, body, 'Commercial details could not be saved.');
  },

  saveDetails(listingId: number, draftId: string, version: number, body: unknown): Promise<StaffRevision> {
    return mutate(`/backoffice/properties/${listingId}/revisions/${encodeURIComponent(draftId)}/details`,
      version, body, 'Home details could not be saved.');
  },

  saveAddress(listingId: number, draftId: string, version: number, body: unknown): Promise<StaffRevision> {
    return mutate(`/backoffice/properties/${listingId}/revisions/${encodeURIComponent(draftId)}/address`,
      version, body, 'Presentation address could not be saved.');
  },

  saveMedia(listingId: number, draftId: string, version: number, body: unknown): Promise<StaffRevision> {
    return mutate(`/backoffice/properties/${listingId}/revisions/${encodeURIComponent(draftId)}/media`,
      version, body, 'Media presentation could not be saved.');
  },

  listMedia(listingId: number, draftId: string, session = sessionOrThrow()): Promise<UploadedStaffMedia[]> {
    return request(`/backoffice/properties/${listingId}/revisions/${encodeURIComponent(draftId)}/media`,
      {}, session, 'Revision media could not be loaded.');
  },

  uploadMedia(listingId: number, draftId: string, version: number, mediaId: string,
              file: File, session = sessionOrThrow()): Promise<UploadedStaffMedia> {
    const form = new FormData();
    form.set('file', file);
    form.set('mediaId', mediaId);
    return request(`/backoffice/properties/${listingId}/revisions/${encodeURIComponent(draftId)}/media?mediaId=${encodeURIComponent(mediaId)}`,
      { method: 'POST', headers: { 'If-Match': String(version) }, body: form },
      session, 'This media file could not be uploaded.');
  },

  recoverMedia(listingId: number, draftId: string, version: number,
               mediaId: string): Promise<UploadedStaffMedia> {
    return request(`/backoffice/properties/${listingId}/revisions/${encodeURIComponent(draftId)}/media/${encodeURIComponent(mediaId)}/recover`,
      { method: 'POST', headers: { 'If-Match': String(version) } },
      sessionOrThrow(), 'This upload could not be recovered.');
  },

  publish(listingId: number, draftId: string, draftVersion: number, listingVersion: number,
          reason: string, operationId = newOperationId()): Promise<PublishResult> {
    return request(`/backoffice/properties/${listingId}/revisions/${encodeURIComponent(draftId)}/publish`, {
      method: 'POST',
      headers: {
        'If-Match': String(draftVersion),
        'X-Listing-Version': String(listingVersion),
        'Idempotency-Key': operationId
      },
      body: JSON.stringify({ reason })
    }, sessionOrThrow(), 'Publication could not be completed.');
  },

  discard(listingId: number, draftId: string, reason: string): Promise<unknown> {
    return request(`/backoffice/properties/${listingId}/revisions/${encodeURIComponent(draftId)}/discard`, {
      method: 'POST', body: JSON.stringify({ reason })
    }, sessionOrThrow(), 'The staff draft could not be discarded.');
  },

  supersede(listingId: number, draftId: string, version: number, reason: string): Promise<StaffRevision> {
    return request(`/backoffice/properties/${listingId}/revisions/${encodeURIComponent(draftId)}/supersede`, {
      method: 'POST', headers: { 'If-Match': String(version) }, body: JSON.stringify({ reason })
    }, sessionOrThrow(), 'The staff draft could not be superseded.');
  },

  lifecycle(listingId: number, version: number,
            action: 'pause' | 'archive', reason: string,
            operationId = newOperationId()): Promise<unknown> {
    return request(`/backoffice/properties/${listingId}/lifecycle/${action}`, {
      method: 'POST',
      headers: { 'If-Match': String(version), 'Idempotency-Key': operationId },
      body: JSON.stringify({ reason })
    }, sessionOrThrow(), `The property could not be ${action === 'pause' ? 'paused' : 'archived'}.`);
  },

  commandOutcome(listingId: number, operationId: string): Promise<CommandOutcome> {
    return request(`/backoffice/properties/${listingId}/commands/${encodeURIComponent(operationId)}`,
      {}, sessionOrThrow(), 'The recorded command outcome could not be checked.');
  },

  activity(listingId: number, cursor: string | null = null, limit = 20): Promise<{
    items: Activity[]; page: number; hasMore: boolean; nextCursor: string | null;
  }> {
    const params = new URLSearchParams({ limit: String(limit) });
    if (cursor) params.set('cursor', cursor);
    return request(`/backoffice/properties/${listingId}/activity?${params}`,
      {}, sessionOrThrow(), 'Activity history could not be loaded.');
  }
};

