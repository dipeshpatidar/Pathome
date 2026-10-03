import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError } from './apiError';

export interface GroundVisitSessionItem {
  itemId: number;
  listingId: number;
  title: string;
  address?: string | null;
  city?: string | null;
  sector?: string | null;
  position: number;
}

export interface GroundVisitSession {
  sessionId: number;
  status: string;
  version: number;
  city: string;
  scheduledAt: string | null;
  reservedEndAt: string | null;
  durationMinutes: number | null;
  zoneId: string | null;
  arrivedAt: string | null;
  startedAt: string | null;
  expectedEndAt: string | null;
  finishedAt: string | null;
  tenantEtaAt: string | null;
  tenantConfirmationState: string;
  repairState: string;
  overPlannedTime: boolean;
  items: GroundVisitSessionItem[];
}

export interface GroundVisitSessionPage {
  sessions: GroundVisitSession[];
  totalCount: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface VisitExecutionView {
  sessionId: number;
  status: string;
  version: number;
  scheduledAt: string | null;
  zoneId: string | null;
  arrivedAt: string | null;
  startedAt: string | null;
  expectedEndAt: string | null;
  finishedAt: string | null;
  tenantEtaAt: string | null;
  tenantConfirmationState: string;
  repairState: string;
  overPlannedTime: boolean;
  resultCode: string;
}

export interface GroundVisitTenantContact {
  sessionId: number;
  tenantName: string | null;
  tenantPhone: string | null;
}

export interface GroundVisitStartCodeStatus {
  sessionId: number;
  challengeAvailable: boolean;
  generation: number;
  expiresAt: string | null;
  lockedUntil: string | null;
}

export interface TenantVisitStartCode {
  sessionId: number;
  generation: number;
  code: string;
  expiresAt: string;
  nextRequestAt: string;
  deliveryChannel: string;
}

export interface GroundVisitOutcomeItem {
  itemId: number;
  listingId: number;
  position: number;
  title: string;
  address: string;
  city: string;
  sector: string;
  outcome: 'UNRECORDED' | 'VISITED' | 'SKIPPED';
  skipReason: 'PROPERTY_UNAVAILABLE' | 'ACCESS_DENIED' | 'TENANT_DECLINED' | 'TENANT_LEFT_EARLY' | 'PROPERTY_MISMATCH' | 'OTHER' | null;
  privateNote: string | null;
  recordedAt: string | null;
  itemVersion: number;
}

export interface GroundVisitOutcomeReport {
  sessionId: number;
  sessionState: string;
  sessionVersion: number;
  reportState: 'OPEN' | 'FINALIZED' | 'LEGACY_UNRECORDED';
  reportVersion: number;
  scopeCapturedAt: string | null;
  summary: 'ALL_VIEWED' | 'PARTLY_VIEWED' | 'NONE_VIEWED' | null;
  items: GroundVisitOutcomeItem[];
}

export interface GroundPendingVisitOutcome {
  sessionId: number;
  sessionState: string;
  scheduledAt: string | null;
  finishedAt: string | null;
  city: string | null;
  pendingPropertyCount: number;
  totalPropertyCount: number;
}

export interface RecordGroundVisitOutcomeCommand {
  outcome: 'VISITED' | 'SKIPPED';
  skipReason: GroundVisitOutcomeItem['skipReason'];
  privateNote: string | null;
  expectedSessionVersion: number;
  expectedReportVersion: number;
  expectedItemVersion: number;
  operationId: string;
}

const token = (): string => {
  const value = localStorage.getItem('pathome_auth_token');
  if (!value) throw new ApiRequestError('Please sign in to continue.', 401);
  return value;
};

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const authToken = token();
  const response = await fetch(`${API_ROOT_URL}${path}`, {
    ...init,
    headers: { Authorization: `Bearer ${authToken}`, ...(init.body ? { 'Content-Type': 'application/json' } : {}), ...init.headers }
  });
  if (!response.ok) throw await createApiRequestError(response, 'Visit information is unavailable. Please try again.', false, authToken);
  if (response.status === 204) return undefined as T;
  return await response.json() as T;
}

export const visitExecutionService = {
  listGround(page = 0, signal?: AbortSignal) {
    return request<GroundVisitSessionPage>(`/ground/visit-sessions?page=${page}&size=20`, { signal });
  },
  getGroundExecution(sessionId: number) {
    return request<VisitExecutionView>(`/ground/visit-sessions/${sessionId}/execution`);
  },
  getGroundContact(sessionId: number) {
    return request<GroundVisitTenantContact>(`/ground/visit-sessions/${sessionId}/tenant-contact`);
  },
  getGroundCodeStatus(sessionId: number) {
    return request<GroundVisitStartCodeStatus>(`/ground/visit-sessions/${sessionId}/start-code-status`);
  },
  markArrived(sessionId: number) {
    return request<VisitExecutionView>(`/ground/visit-sessions/${sessionId}/arrived`, { method: 'POST' });
  },
  recordContact(sessionId: number, outcome: string, tenantEtaAt: string | undefined, operationId: string) {
    return request<VisitExecutionView>(`/ground/visit-sessions/${sessionId}/contact-attempts`, {
      method: 'POST', body: JSON.stringify({ outcome, tenantEtaAt: tenantEtaAt || null,
        reasonCode: outcome === 'NO_ANSWER' ? 'TENANT_LATE' : null, operationId })
    });
  },
  markProvisionalNoShow(sessionId: number) {
    return request<VisitExecutionView>(`/ground/visit-sessions/${sessionId}/provisional-no-show`, { method: 'POST' });
  },
  start(sessionId: number, generation: number, code: string, operationId: string) {
    return request<VisitExecutionView>(`/ground/visit-sessions/${sessionId}/start`, {
      method: 'POST', body: JSON.stringify({ generation, code, operationId })
    });
  },
  needMoreTime(sessionId: number, minutes: number, operationId: string) {
    return request<VisitExecutionView>(`/ground/visit-sessions/${sessionId}/need-more-time`, {
      method: 'POST', body: JSON.stringify({ additionalMinutes: minutes, operationId })
    });
  },
  finish(sessionId: number) {
    return request<VisitExecutionView>(`/ground/visit-sessions/${sessionId}/finish`, { method: 'POST' });
  },
  getOutcomeReport(sessionId: number) {
    return request<GroundVisitOutcomeReport>(`/ground/visit-sessions/${sessionId}/outcome-report`);
  },
  recordOutcome(sessionId: number, itemId: number, command: RecordGroundVisitOutcomeCommand) {
    return request<GroundVisitOutcomeReport>(`/ground/visit-sessions/${sessionId}/items/${itemId}/outcome`, {
      method: 'PUT', body: JSON.stringify(command)
    });
  },
  completeWithOutcomes(sessionId: number, command: Omit<RecordGroundVisitOutcomeCommand, 'outcome' | 'skipReason' | 'privateNote' | 'expectedItemVersion'>) {
    return request<GroundVisitOutcomeReport>(`/ground/visit-sessions/${sessionId}/complete-with-outcomes`, {
      method: 'POST', body: JSON.stringify(command)
    });
  },
  listPendingOutcomes(page = 0, signal?: AbortSignal) {
    return request<{ content: GroundPendingVisitOutcome[]; totalPages: number; totalElements: number }>(
      `/ground/visit-sessions/pending-outcomes?page=${page}&size=20`, { signal }
    );
  },
  listTenant(page = 0, signal?: AbortSignal) {
    return request<{ sessions: VisitExecutionView[]; page: number; size: number; totalPages: number }>(
      `/tenant/visit-sessions?page=${page}`, { signal }
    );
  },
  issueStartCode(sessionId: number) {
    return request<TenantVisitStartCode>(`/tenant/visit-sessions/${sessionId}/start-code`, { method: 'POST' });
  },
  confirmTenant(sessionId: number, action: string, operationId: string, expectedSessionVersion: number) {
    return request<VisitExecutionView>(`/tenant/visit-sessions/${sessionId}/confirmation`, {
      method: 'POST', body: JSON.stringify({ action, operationId, expectedSessionVersion })
    });
  }
};

export function createVisitOperationId(): string {
  if (typeof crypto === 'undefined' || typeof crypto.randomUUID !== 'function') {
    throw new ApiRequestError('Secure request retry is unavailable in this browser. Update the browser to continue.');
  }
  return crypto.randomUUID();
}
