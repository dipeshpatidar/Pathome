import { API_ROOT_URL } from '../config/endpoints';
import {
  ApiRequestError,
  createApiRequestError,
  createClassifiedApiRequestError
} from './apiError';
import {
  captureOperationsSession,
  isOperationsSessionActive,
  OperationsSessionChangedError,
  type OperationsSession
} from '../utils/operationsSession';
import {
  LEAD_PAGE_SIZE,
  SHARE_HISTORY_PAGE_SIZE,
  leadQueueQuery,
  leadShareFromHistoryItem,
  toActivity,
  toCommandReceipt,
  toContactPreference,
  toContactPreferences,
  toDestinationReveal,
  toDuplicateResponse,
  toLeadActivityPage,
  toLeadDetail,
  toLeadPage,
  toLeadShare,
  toLeadShareEvent,
  toLeadShareHistoryPage,
  toLeadShortlist,
  toSourceException,
  toSourceExceptionPage,
  type LeadActivity,
  type LeadActivityPage,
  type LeadCommandReceipt,
  type LeadContactPreference,
  type LeadDestinationReveal,
  type LeadDetail,
  type LeadDuplicateResponse,
  type LeadPage,
  type LeadQueueFilters,
  type LeadShare,
  type LeadShareEvent,
  type LeadShareEventType,
  type LeadShareHistoryItem,
  type LeadShareHistoryPage,
  type LeadShortlist,
  type LeadSourceException,
  type LeadSourceExceptionPage
} from '../utils/operationsLeadPresentation';

export interface LeadRequestContext {
  signal?: AbortSignal;
  /**
   * The exact signed-in session captured when the logical operation began. A request never re-reads
   * the current token after an await.
   */
  session?: OperationsSession;
  /** Runs in the same tick as dispatch, after all synchronous guards and immediately before fetch. */
  onDispatch?: () => void;
}

const SHARE_RECEIPT_ID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const MAX_HISTORY_PAGES_FOR_RECOVERY = 100;

export class ShareHistoryBoundReachedError extends Error {
  constructor() {
    super('The exact saved message is older than the bounded history this screen can check.');
    this.name = 'ShareHistoryBoundReachedError';
  }
}

interface LeadRequestOptions extends LeadRequestContext {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT';
  body?: unknown;
  headers?: Record<string, string>;
  retainClassifiedApplicationError?: boolean;
}

const requireSession = (context: LeadRequestContext): OperationsSession => {
  const session = context.session ?? captureOperationsSession();
  if (!session) throw new ApiRequestError('Please sign in to use the Lead workspace.', 401);
  if (!isOperationsSessionActive(session)) throw new OperationsSessionChangedError();
  return session;
};

const send = async <T>(
  path: string,
  fallbackMessage: string,
  mapper: (payload: unknown) => T,
  context: LeadRequestContext = {},
  options: Pick<LeadRequestOptions,
    'method' | 'body' | 'headers' | 'retainClassifiedApplicationError'> = {}
): Promise<T> => {
  const session = requireSession(context);
  context.onDispatch?.();
  const response = await fetch(`${API_ROOT_URL}${path}`, {
    method: options.method ?? 'GET',
    headers: {
      Authorization: `Bearer ${session.token}`,
      Accept: 'application/json',
      ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...options.headers
    },
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    cache: 'no-store',
    signal: context.signal
  });
  if (!response.ok) {
    throw options.retainClassifiedApplicationError
      ? await createClassifiedApiRequestError(response, fallbackMessage, session.token)
      : await createApiRequestError(response, fallbackMessage, false, session.token);
  }
  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    throw new ApiRequestError(fallbackMessage, response.status);
  }
  const mapped = mapper(payload);
  if (!isOperationsSessionActive(session)) throw new OperationsSessionChangedError();
  return mapped;
};

export const operationsLeadService = {
  list(
    page = 0,
    filters: LeadQueueFilters,
    context: LeadRequestContext = {}
  ): Promise<LeadPage> {
    return send(
      `/operations/leads?${leadQueueQuery(page, filters)}`,
      'Leads could not be loaded.',
      (payload) => toLeadPage(payload, page, LEAD_PAGE_SIZE),
      context
    );
  },

  capture(
    command: Record<string, unknown>,
    operationId: string,
    context: LeadRequestContext = {}
  ): Promise<LeadDetail> {
    return send(
      '/operations/leads',
      'The enquiry could not be saved.',
      toLeadDetail,
      context,
      { method: 'POST', body: command, headers: { 'Idempotency-Key': operationId } }
    );
  },

  duplicateCandidates(
    cityId: number,
    contactPhone: string,
    context: LeadRequestContext = {}
  ): Promise<LeadDuplicateResponse> {
    return send(
      '/operations/leads/duplicate-candidates',
      'Duplicate review could not be completed.',
      toDuplicateResponse,
      context,
      { method: 'POST', body: { cityId, contactPhone } }
    );
  },

  detail(leadId: number, context: LeadRequestContext = {}): Promise<LeadDetail> {
    return send(
      `/operations/leads/${leadId}`,
      'This Lead could not be loaded.',
      toLeadDetail,
      context
    );
  },

  patch(
    leadId: number,
    expectedVersion: number,
    changes: Record<string, unknown>,
    context: LeadRequestContext = {}
  ): Promise<LeadDetail> {
    return send(
      `/operations/leads/${leadId}`,
      'The requirements could not be saved.',
      toLeadDetail,
      context,
      { method: 'PATCH', body: { expectedVersion, changes } }
    );
  },

  activities(
    leadId: number,
    page = 0,
    context: LeadRequestContext = {}
  ): Promise<LeadActivityPage> {
    return send(
      `/operations/leads/${leadId}/activities?page=${page}&size=${LEAD_PAGE_SIZE}`,
      'Lead activity could not be loaded.',
      (payload) => toLeadActivityPage(payload, page, LEAD_PAGE_SIZE),
      context
    );
  },

  logActivity(
    leadId: number,
    body: {
      expectedVersion: number;
      type: 'CALL_LOGGED' | 'NOTE_ADDED';
      note: string | null;
      followUpAt: string | null;
      followUpZoneId: string | null;
    },
    context: LeadRequestContext = {}
  ): Promise<LeadActivity> {
    return send(
      `/operations/leads/${leadId}/activities`,
      'The call or note could not be saved.',
      toActivity,
      context,
      { method: 'POST', body }
    );
  },

  revealContact(leadId: number, context: LeadRequestContext = {}): Promise<{ contact: string }> {
    return send(
      `/operations/leads/${leadId}/contact`,
      'The customer contact could not be revealed.',
      (payload) => {
        const value = payload as { fullContact?: unknown } | null;
        if (typeof value?.fullContact !== 'string' || !value.fullContact.trim()) {
          throw new Error('Contact reveal response is invalid');
        }
        return { contact: value.fullContact };
      },
      context
    );
  },

  contactPreferences(
    leadId: number,
    context: LeadRequestContext = {}
  ): Promise<LeadContactPreference[]> {
    return send(
      `/operations/leads/${leadId}/contact-preferences`,
      'Current sharing consent could not be loaded.',
      toContactPreferences,
      context
    );
  },

  recordContactPreference(
    leadId: number,
    command: {
      expectedLeadVersion: number;
      expectedPreferenceRevision: number;
      channel: 'WHATSAPP' | 'OTHER';
      destinationChoice: 'SAME' | 'DIFFERENT' | 'NONE';
      countryCallingCode: string | null;
      number: string | null;
      consentStatus: 'GRANTED' | 'REVOKED' | 'DISPUTED' | 'UNKNOWN';
      consentWordingVersion: string | null;
      evidenceMethod: 'CUSTOMER_VERBAL' | 'CUSTOMER_DIGITAL' | null;
    },
    operationId: string,
    context: LeadRequestContext = {}
  ): Promise<LeadContactPreference> {
    return send(
      `/operations/leads/${leadId}/contact-preferences`,
      'Sharing consent could not be recorded.',
      toContactPreference,
      context,
      { method: 'POST', body: command, headers: { 'Idempotency-Key': operationId } }
    );
  },

  revealDestination(
    leadId: number,
    revision: number,
    context: LeadRequestContext = {}
  ): Promise<LeadDestinationReveal> {
    return send(
      `/operations/leads/${leadId}/contact-preferences/${revision}/destination`,
      'The confirmed destination could not be revealed.',
      toDestinationReveal,
      context
    );
  },

  shortlist(
    leadId: number,
    shortlistId: number,
    context: LeadRequestContext = {}
  ): Promise<LeadShortlist> {
    return send(
      `/operations/leads/${leadId}/shortlists/${shortlistId}`,
      'The shortlist could not be loaded.',
      toLeadShortlist,
      context
    );
  },

  createShortlist(
    leadId: number,
    expectedLeadVersion: number,
    listingIds: number[],
    operationId: string,
    context: LeadRequestContext = {}
  ): Promise<LeadShortlist> {
    return send(
      `/operations/leads/${leadId}/shortlists`,
      'The shortlist could not be saved.',
      toLeadShortlist,
      context,
      {
        method: 'POST',
        body: { expectedLeadVersion, listingIds },
        headers: { 'Idempotency-Key': operationId }
      }
    );
  },

  updateShortlist(
    leadId: number,
    shortlistId: number,
    expectedLeadVersion: number,
    expectedShortlistVersion: number,
    listingIds: number[],
    context: LeadRequestContext = {}
  ): Promise<LeadShortlist> {
    return send(
      `/operations/leads/${leadId}/shortlists/${shortlistId}`,
      'The shortlist could not be updated.',
      toLeadShortlist,
      context,
      {
        method: 'PUT',
        body: { expectedLeadVersion, expectedShortlistVersion, listingIds }
      }
    );
  },

  prepareShare(
    leadId: number,
    command: {
      operationId: string;
      expectedLeadVersion: number;
      shortlistId: number;
      expectedShortlistVersion: number;
      contactPreferenceRevision: number;
      mode: 'COPY_LINKS' | 'MANUAL_BUSINESS_HANDOFF';
    },
    context: LeadRequestContext = {}
  ): Promise<LeadShare> {
    return send(
      `/operations/leads/${leadId}/shares`,
      'The share could not be prepared.',
      toLeadShare,
      context,
      {
        method: 'POST',
        body: command,
        retainClassifiedApplicationError: true
      }
    );
  },

  shareHistory(
    leadId: number,
    page = 0,
    size = SHARE_HISTORY_PAGE_SIZE,
    context: LeadRequestContext = {}
  ): Promise<LeadShareHistoryPage> {
    return send(
      `/operations/leads/${leadId}/shares?page=${page}&size=${size}`,
      'Share history could not be loaded.',
      (payload) => toLeadShareHistoryPage(payload, page, size),
      context
    );
  },

  /**
   * Resolves the history projection for one exact share through the existing authorized, paginated
   * contract. A changed result set triggers one bounded restart so an insert during paging cannot
   * silently shift the requested share behind an already-read offset.
   */
  async preparedShareHistoryFromHistory(
    leadId: number,
    shareId: string,
    context: LeadRequestContext = {}
  ): Promise<LeadShareHistoryItem | null> {
    if (!SHARE_RECEIPT_ID.test(shareId)) {
      throw new Error('The property message reference is invalid.');
    }
    const scan = async (first: LeadShareHistoryPage) => {
      const exact = first.items.find((item) => item.id === shareId);
      if (exact) return exact;
      let changedWhileScanning = false;
      let reachedBound = first.totalPages > MAX_HISTORY_PAGES_FOR_RECOVERY;
      const lastPage = Math.min(first.totalPages, MAX_HISTORY_PAGES_FOR_RECOVERY);
      for (let page = 1; page < lastPage; page += 1) {
        if (context.signal?.aborted) throw new DOMException('Aborted', 'AbortError');
        const next = await this.shareHistory(
          leadId, page, SHARE_HISTORY_PAGE_SIZE, context);
        if (next.totalElements !== first.totalElements || next.totalPages !== first.totalPages) {
          changedWhileScanning = true;
        }
        reachedBound = reachedBound || next.totalPages > MAX_HISTORY_PAGES_FOR_RECOVERY;
        const found = next.items.find((item) => item.id === shareId);
        if (found) return found;
      }
      return { changedWhileScanning, reachedBound };
    };

    const first = await this.shareHistory(leadId, 0, SHARE_HISTORY_PAGE_SIZE, context);
    const firstResult = await scan(first);
    if (firstResult && 'id' in firstResult) return firstResult;

    if (firstResult.changedWhileScanning) {
      const restarted = await this.shareHistory(leadId, 0, SHARE_HISTORY_PAGE_SIZE, context);
      const restartedResult = await scan(restarted);
      if (restartedResult && 'id' in restartedResult) return restartedResult;
      if (restartedResult.reachedBound) throw new ShareHistoryBoundReachedError();
      return null;
    }
    if (firstResult.reachedBound) throw new ShareHistoryBoundReachedError();
    return null;
  },

  /**
   * Read-only exact-share mapping retained for existing callers. Status and readiness controls must
   * use the history projection above so they also retain the server's current-action flags.
   */
  async preparedShareFromHistory(
    leadId: number,
    shareId: string,
    context: LeadRequestContext = {}
  ): Promise<LeadShare | null> {
    const item = await this.preparedShareHistoryFromHistory(leadId, shareId, context);
    return item ? leadShareFromHistoryItem(item, leadId) : null;
  },

  recordShareEvent(
    leadId: number,
    shareId: string,
    eventId: string,
    type: LeadShareEventType,
    context: LeadRequestContext = {}
  ): Promise<LeadShareEvent> {
    return send(
      `/operations/leads/${leadId}/shares/${shareId}/events`,
      'The share event could not be recorded.',
      toLeadShareEvent,
      context,
      { method: 'POST', body: { eventId, type } }
    );
  },

  commandReceipt(
    operationId: string,
    action: string,
    context: LeadRequestContext = {}
  ): Promise<LeadCommandReceipt> {
    return send(
      `/operations/lead-commands/${operationId}?action=${encodeURIComponent(action)}`,
      'The command result could not be checked.',
      toCommandReceipt,
      context
    );
  },

  sourceExceptions(
    page = 0,
    size = LEAD_PAGE_SIZE,
    context: LeadRequestContext = {}
  ): Promise<LeadSourceExceptionPage> {
    return send(
      `/operations/leads/source-exceptions?page=${page}&size=${size}`,
      'Source exceptions could not be loaded.',
      (payload) => toSourceExceptionPage(payload, page, size),
      context
    );
  },

  retrySource(
    eventId: string,
    context: LeadRequestContext = {}
  ): Promise<LeadSourceException> {
    return send(
      `/operations/leads/source-exceptions/${eventId}/retry`,
      'The source event could not be retried.',
      toSourceException,
      context,
      { method: 'POST' }
    );
  }
};
