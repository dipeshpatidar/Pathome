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

export type RemovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN';

export interface RemovalRequestView {
  requestId: number;
  listingId: number;
  requestStatus: RemovalStatus;
  requestVersion: number;
  currentListingStatus: string | null;
  currentListingVersion: number | null;
  requestedAt: string;
  terminalAt: string | null;
  ownerReason: string | null;
  decisionReason: string | null;
  permittedActions: string[];
}

export interface RemovalQueueItem {
  requestId: number;
  listingId: number;
  propertyTitle: string;
  city: string | null;
  locality: string | null;
  requestStatus: RemovalStatus;
  requestVersion: number;
  currentListingVersion: number;
  currentListingStatus: string;
  requestedAt: string;
  ownerReason: string | null;
  activeAppointmentCount: number;
}

export interface RemovalAdminDetail {
  request: RemovalRequestView;
  propertyTitle: string;
  city: string | null;
  locality: string | null;
  scheduledAppointmentCount: number;
  startedAppointmentCount: number;
  openRevisionStatus: string | null;
  openRevisionDraftId: string | null;
}

const currentSession = (): OperationsSession => {
  const captured = captureOperationsSession();
  if (!captured) {
    notifySessionExpired();
    throw new ApiRequestError('Sign in to continue.', 401);
  }
  return captured;
};

const send = async <T>(path: string, options: RequestInit = {},
                       fallback = 'This removal action could not be completed.'): Promise<T> => {
  const session = currentSession();
  const response = await fetch(`${API_ROOT_URL}${path}`, {
    ...options,
    cache: 'no-store',
    headers: {
      Authorization: `Bearer ${session.token}`,
      Accept: 'application/json',
      'Content-Type': 'application/json',
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

export const propertyRemovalService = {
  create(listingId: number, listingVersion: number, reason: string,
         operationId: string = createOperationId()): Promise<RemovalRequestView> {
    return send<{ request: RemovalRequestView; created: boolean }>(`/lessor/properties/${listingId}/removal-requests`, {
      method: 'POST',
      headers: { 'If-Match': String(listingVersion) },
      body: JSON.stringify({ operationId, reason })
    }, 'The removal request could not be created.').then(result => result.request);
  },

  history(listingId: number, page = 0): Promise<{ items: RemovalRequestView[]; page: number; hasMore: boolean }> {
    return send(`/lessor/properties/${listingId}/removal-requests?page=${page}`,
      {}, 'Removal request history could not be loaded.');
  },

  withdraw(listingId: number, request: RemovalRequestView,
           operationId: string = createOperationId()): Promise<RemovalRequestView> {
    return send(`/lessor/properties/${listingId}/removal-requests/${request.requestId}/withdraw`, {
      method: 'POST',
      headers: { 'If-Match': String(request.requestVersion) },
      body: JSON.stringify({ operationId })
    }, 'The removal request could not be withdrawn.');
  },

  adminQueue(status: RemovalStatus | '' = 'PENDING', page = 0,
             cityId: number | null = null, signal?: AbortSignal): Promise<{
    items: RemovalQueueItem[]; page: number; hasMore: boolean;
  }> {
    const params = new URLSearchParams({ page: String(page), size: '20' });
    if (status) params.set('status', status);
    if (cityId !== null) params.set('cityId', String(cityId));
    return send(`/admin/property-removal-requests?${params}`,
      { signal }, 'Removal requests could not be loaded.');
  },

  adminDetail(requestId: number, signal?: AbortSignal): Promise<RemovalAdminDetail> {
    return send(`/admin/property-removal-requests/${requestId}`,
      { signal }, 'The removal request could not be opened.');
  },

  decide(detail: RemovalAdminDetail, decision: 'APPROVED' | 'REJECTED',
         reason: string, operationId: string = createOperationId(),
         signal?: AbortSignal): Promise<RemovalRequestView> {
    return send(`/admin/property-removal-requests/${detail.request.requestId}/decision`, {
      method: 'POST',
      signal,
      headers: { 'If-Match': String(detail.request.requestVersion) },
      body: JSON.stringify({
        operationId,
        decision,
        expectedListingVersion: detail.request.currentListingVersion,
        reason
      })
    }, 'The removal decision could not be completed.');
  }
};

