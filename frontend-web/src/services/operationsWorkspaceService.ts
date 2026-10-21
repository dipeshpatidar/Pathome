import { API_ROOT_URL } from '../config/endpoints';
import type { SchedulingIntent } from '../utils/operationsSchedulingIntent';
import { ApiRequestError, createApiRequestError } from './apiError';
import {
  EMPTY_OPERATIONS_FILTERS,
  hasOperationsFilters,
  type OperationsQueueFilters
} from '../utils/operationsWorkspaceRoute';
import {
  captureOperationsSession,
  isOperationsSessionActive,
  OperationsSessionChangedError,
  type OperationsSession
} from '../utils/operationsSession';
import {
  OPERATIONS_CLAIM_REASON_CODE,
  OPERATIONS_RELEASE_REASON_CODE,
  parseWorkPage,
  toClaimableWorkItem,
  toLessorContact,
  toTenantContact,
  toMyWorkItem,
  toGeAvailabilityView,
  toRecommendationView,
  toRequestDetail,
  toSchedulingApproval,
  toSessionDetail,
  toStaffCapabilities,
  type ClaimableWorkItem,
  type LinkedRequestVersionMap,
  type OperationsDerivedItemOrigin,
  type OperationsLessorContact,
  type OperationsGeAvailabilityView,
  type OperationsMyWorkItem,
  type OperationsRecommendationView,
  type OperationsRequestDetail,
  type OperationsSchedulingApproval,
  type OperationsSessionDetail,
  type OperationsTargetType,
  type OperationsTenantContact,
  type OperationsWorkPage,
  type StaffCapabilities
} from '../utils/operationsWorkspacePresentation';

/** Server page size stays inside the backend's 1..100 bound. */
export const OPERATIONS_WORK_PAGE_SIZE = 20;

/**
 * Builds the additive server-filter query exactly once, so the visible route state and the
 * authoritative read can never drift apart.
 */
export const operationsFilterQuery = (filters: OperationsQueueFilters): string => {
  if (!hasOperationsFilters(filters)) return '';
  const params = new URLSearchParams();
  if (filters.targetType) params.set('targetType', filters.targetType);
  if (filters.status) params.set('status', filters.status);
  if (filters.cityId !== null) params.set('cityId', String(filters.cityId));
  if (filters.teamId !== null) params.set('teamId', String(filters.teamId));
  if (filters.targetId !== null) params.set('targetId', String(filters.targetId));
  const query = params.toString();
  return query ? `&${query}` : '';
};

/**
 * Discovery page size. The backend accepts 1..50 and returns `nextCursor` when more candidates
 * remain, so one page is a bounded batch rather than a complete answer.
 */
export const OPERATIONS_DISCOVERY_PAGE_SIZE = 20;

/** The backend accepts a trimmed search query of 1..80 printable characters. */
export const OPERATIONS_DISCOVERY_QUERY_MAX = 80;

/**
 * Every request of one logical operation carries the same captured session. Nothing here re-reads a
 * "current" token after an await, which is what previously allowed a claim started under Account A
 * to be transmitted with Account B's credential once the refresh of the item resolved.
 */
export interface OperationsRequestContext {
  signal?: AbortSignal;
  /** The session captured by the caller when the logical operation began. */
  session?: OperationsSession;
  /**
   * Invoked synchronously in the same tick as the request is handed to the transport, and only after
   * every pre-flight check has passed.
   *
   * Ownership commands use it to record that a POST really was transmitted, so the workspace can
   * distinguish "nothing was sent" from "the response never arrived" instead of claiming that
   * nothing changed when the command may have reached the backend.
   */
  onDispatch?: () => void;
}

interface OperationsRequestOptions extends OperationsRequestContext {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  body?: unknown;
}

/**
 * Refuses to run once the captured session is no longer the signed-in one. Called before the request
 * is dispatched and again after it settles, because an already transmitted command cannot be
 * recalled — but its response must never be applied to a different account.
 */
const assertOperationsSessionActive = (session: OperationsSession): void => {
  if (!isOperationsSessionActive(session)) throw new OperationsSessionChangedError();
};

const sendOperationsRequest = async (
  path: string,
  fallbackMessage: string,
  options: OperationsRequestOptions = {}
): Promise<Response> => {
  const session = options.session ?? captureOperationsSession();
  if (!session) throw new ApiRequestError('Please sign in to use the Operations workspace.', 401);
  assertOperationsSessionActive(session);
  const method = options.method ?? 'GET';
  // The last moment before anything leaves the client: the caller's gate ran in this same tick, and
  // no `await` may sit between it and this call.
  options.onDispatch?.();
  const response = await fetch(`${API_ROOT_URL}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${session.token}`,
      Accept: 'application/json',
      ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' })
    },
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    // Operational reads and ownership commands are never served from a client cache.
    cache: 'no-store',
    signal: options.signal
  });
  if (!response.ok) throw await createApiRequestError(response, fallbackMessage, false, session.token);
  assertOperationsSessionActive(session);
  return response;
};

const readOperationsPayload = async (response: Response, fallbackMessage: string): Promise<unknown> => {
  try {
    return await response.json();
  } catch {
    throw new ApiRequestError(fallbackMessage, response.status);
  }
};

const fetchWorkPage = async <T,>(
  path: string,
  fallback: { page: number; size: number },
  fallbackMessage: string,
  mapItem: (raw: unknown) => T | null,
  context: OperationsRequestContext
): Promise<OperationsWorkPage<T>> => {
  const session = requireSession(context);
  const response = await sendOperationsRequest(path, fallbackMessage, { ...context, session });
  const payload = await readOperationsPayload(response, fallbackMessage);
  const parsed = parseWorkPage(payload, fallback);
  assertOperationsSessionActive(session);
  return {
    items: parsed.items.map(mapItem).filter((entry): entry is T => entry !== null),
    totalCount: parsed.totalCount,
    unfilteredTotalCount: parsed.unfilteredTotalCount,
    page: parsed.page,
    size: parsed.size,
    totalPages: parsed.totalPages
  };
};

const fetchDetail = async <T,>(
  path: string,
  fallbackMessage: string,
  map: (payload: unknown) => T | null,
  context: OperationsRequestContext
): Promise<T> => {
  const session = requireSession(context);
  const response = await sendOperationsRequest(path, fallbackMessage, { ...context, session });
  const payload = await readOperationsPayload(response, fallbackMessage);
  const mapped = map(payload);
  if (mapped === null) throw new ApiRequestError(fallbackMessage, response.status);
  assertOperationsSessionActive(session);
  return mapped;
};

/**
 * Ownership commands answer with HTTP 204 and no body, so a successful response is never
 * deserialised. Only the status is reported back.
 */
const sendOwnershipCommand = async (
  path: string,
  body: unknown,
  fallbackMessage: string,
  context: OperationsRequestContext
): Promise<number> => {
  const session = requireSession(context);
  // `onDispatch` reaches the transport through the shared request helper, which calls it immediately
  // before `fetch` — never on a request that the pre-flight checks refused.
  const response = await sendOperationsRequest(path, fallbackMessage, {
    ...context,
    method: 'POST',
    body,
    session
  });
  assertOperationsSessionActive(session);
  return response.status;
};

/**
 * One Phase 2A operational command whose contract answers `200` with authoritative JSON.
 *
 * This is deliberately separate from {@link sendOwnershipCommand}: an ownership command is a `204`
 * with no body and reporting its status is the whole result, while these commands return the updated
 * resource and a body that cannot be decoded means the outcome is *unknown*, not "nothing happened".
 * A mapping that fails, or a body that cannot be read, rejects here after transmission so the caller
 * treats it as unconfirmed instead of as a refusal.
 */
const sendJsonCommand = async <T,>(
  path: string,
  method: 'POST' | 'PUT' | 'DELETE',
  body: unknown,
  fallbackMessage: string,
  map: (payload: unknown) => T | null,
  context: OperationsRequestContext
): Promise<T> => {
  const session = requireSession(context);
  const response = await sendOperationsRequest(path, fallbackMessage, { ...context, method, body, session });
  const payload = await readOperationsPayload(response, fallbackMessage);
  const mapped = map(payload);
  if (mapped === null) throw new ApiRequestError(fallbackMessage, response.status);
  assertOperationsSessionActive(session);
  return mapped;
};

/**
 * Resolves the session for one logical operation, inside the asynchronous path so every method
 * answers with a rejected promise rather than a synchronous throw. The caller captures the session
 * once and passes the same value to every request of that operation.
 */
const requireSession = (context: OperationsRequestContext): OperationsSession => {
  const session = context.session ?? captureOperationsSession();
  if (!session) throw new ApiRequestError('Please sign in to use the Operations workspace.', 401);
  assertOperationsSessionActive(session);
  return session;
};

export const operationsWorkspaceService = {
  /** Effective staff capability grants for the signed-in User. */
  capabilities(context: OperationsRequestContext = {}): Promise<StaffCapabilities> {
    return fetchDetail(
      '/staff/me/capabilities',
      'Operations access could not be confirmed.',
      toStaffCapabilities,
      context
    );
  },

  myWork(
    page = 0,
    attentionOnly = false,
    context: OperationsRequestContext = {},
    filters: OperationsQueueFilters = EMPTY_OPERATIONS_FILTERS
  ): Promise<OperationsWorkPage<OperationsMyWorkItem>> {
    const query = `page=${page}&size=${OPERATIONS_WORK_PAGE_SIZE}&attentionOnly=${attentionOnly ? 'true' : 'false'}`
      + operationsFilterQuery(filters);
    return fetchWorkPage(
      `/operations/my-work?${query}`,
      { page, size: OPERATIONS_WORK_PAGE_SIZE },
      'Your operations work could not be loaded.',
      toMyWorkItem,
      context
    );
  },

  claimableWork(
    page = 0,
    context: OperationsRequestContext = {},
    filters: OperationsQueueFilters = EMPTY_OPERATIONS_FILTERS
  ): Promise<OperationsWorkPage<ClaimableWorkItem>> {
    const query = `page=${page}&size=${OPERATIONS_WORK_PAGE_SIZE}${operationsFilterQuery(filters)}`;
    return fetchWorkPage(
      `/operations/claimable-work?${query}`,
      { page, size: OPERATIONS_WORK_PAGE_SIZE },
      'Claimable work could not be loaded.',
      toClaimableWorkItem,
      context
    );
  },

  /** Fresh, redacted identity of one claimable root, re-read immediately before claiming. */
  claimableItem(
    targetType: OperationsTargetType,
    targetId: number,
    context: OperationsRequestContext = {}
  ): Promise<ClaimableWorkItem> {
    return fetchDetail(
      `/operations/claimable-work/${targetType}/${targetId}`,
      'This item is no longer available to claim.',
      toClaimableWorkItem,
      context
    );
  },

  requestDetail(requestId: number, context: OperationsRequestContext = {}): Promise<OperationsRequestDetail> {
    return fetchDetail(
      `/operations/visit-requests/${requestId}`,
      'This request could not be loaded.',
      toRequestDetail,
      context
    );
  },

  sessionDetail(sessionId: number, context: OperationsRequestContext = {}): Promise<OperationsSessionDetail> {
    return fetchDetail(
      `/operations/visit-sessions/${sessionId}`,
      'This session could not be loaded.',
      toSessionDetail,
      context
    );
  },

  claimRequest(
    requestId: number,
    expectedRequestVersion: number,
    context: OperationsRequestContext = {}
  ): Promise<number> {
    return sendOwnershipCommand(
      `/operations/visit-requests/${requestId}/claim`,
      { expectedRequestVersion, reasonCode: OPERATIONS_CLAIM_REASON_CODE },
      'This request could not be claimed.',
      context
    );
  },

  claimSession(
    sessionId: number,
    expectedSessionVersion: number,
    expectedRequestVersions: LinkedRequestVersionMap,
    context: OperationsRequestContext = {}
  ): Promise<number> {
    return sendOwnershipCommand(
      `/operations/visit-sessions/${sessionId}/claim`,
      { expectedSessionVersion, expectedRequestVersions, reasonCode: OPERATIONS_CLAIM_REASON_CODE },
      'This session could not be claimed.',
      context
    );
  },

  releaseRequest(
    requestId: number,
    expectedRequestVersion: number,
    context: OperationsRequestContext = {}
  ): Promise<number> {
    return sendOwnershipCommand(
      `/operations/visit-requests/${requestId}/release`,
      { expectedRequestVersion, reasonCode: OPERATIONS_RELEASE_REASON_CODE },
      'This request could not be released.',
      context
    );
  },

  releaseSession(
    sessionId: number,
    expectedSessionVersion: number,
    expectedRequestVersions: LinkedRequestVersionMap,
    context: OperationsRequestContext = {}
  ): Promise<number> {
    return sendOwnershipCommand(
      `/operations/visit-sessions/${sessionId}/release`,
      { expectedSessionVersion, expectedRequestVersions, reasonCode: OPERATIONS_RELEASE_REASON_CODE },
      'This session could not be released.',
      context
    );
  },

  /**
   * Canonical lessor contact for one owned standalone Request.
   *
   * The response is the only permitted source of lessor contact. It is read on demand, never cached
   * and never persisted, and the backend marks these routes `no-store` because they can carry PII.
   */
  lessorContactForRequest(
    requestId: number,
    context: OperationsRequestContext = {}
  ): Promise<OperationsLessorContact> {
    return fetchDetail(
      `/operations/visit-requests/${requestId}/lessor-contact`,
      'The lessor contact could not be loaded.',
      toLessorContact,
      context
    );
  },

  /** Canonical lessor contact for one revealable item of an owned Session. */
  lessorContactForSessionItem(
    sessionId: number,
    itemId: number,
    context: OperationsRequestContext = {}
  ): Promise<OperationsLessorContact> {
    return fetchDetail(
      `/operations/visit-sessions/${sessionId}/items/${itemId}/lessor-contact`,
      'The lessor contact could not be loaded.',
      toLessorContact,
      context
    );
  },

  /**
   * Canonical tenant contact for one owned standalone Request. On demand, never cached and never
   * persisted; the backend marks the route `no-store` and audits a successful disclosure.
   */
  tenantContactForRequest(
    requestId: number,
    context: OperationsRequestContext = {}
  ): Promise<OperationsTenantContact> {
    return fetchDetail(
      `/operations/visit-requests/${requestId}/tenant-contact`,
      'The tenant contact could not be loaded.',
      toTenantContact,
      context
    );
  },

  /** Canonical tenant contact for one revealable Session root. */
  tenantContactForSession(
    sessionId: number,
    context: OperationsRequestContext = {}
  ): Promise<OperationsTenantContact> {
    return fetchDetail(
      `/operations/visit-sessions/${sessionId}/tenant-contact`,
      'The tenant contact could not be loaded.',
      toTenantContact,
      context
    );
  },

  /**
   * Coordinates an owned standalone Request into a brand-new DRAFT Session
   * (`sessionId: null`) or attaches it to an owned DRAFT Session. The server decides whether the
   * roots may be combined and returns the authoritative Session.
   */
  coordinateRequest(
    requestId: number,
    body: { expectedRequestVersion: number; sessionId: number | null; expectedSessionVersion: number | null },
    context: OperationsRequestContext = {}
  ): Promise<OperationsSessionDetail> {
    return sendJsonCommand(
      `/operations/visit-requests/${requestId}/coordinate`,
      'POST',
      body,
      'This request could not be coordinated.',
      toSessionDetail,
      context
    );
  },

  /** Records confirmed, factual unavailability for an owned standalone Request. */
  markRequestUnavailable(
    requestId: number,
    expectedRequestVersion: number,
    context: OperationsRequestContext = {}
  ): Promise<OperationsRequestDetail> {
    return sendJsonCommand(
      `/operations/visit-requests/${requestId}/unavailable`,
      'POST',
      { expectedRequestVersion },
      'This request could not be marked unavailable.',
      toRequestDetail,
      context
    );
  },

  /** Adds one derived property to an owned DRAFT itinerary. */
  addSessionItem(
    sessionId: number,
    body: {
      expectedSessionVersion: number;
      listingId: number;
      originatingRequestId: number;
      origin: OperationsDerivedItemOrigin;
    },
    context: OperationsRequestContext = {}
  ): Promise<OperationsSessionDetail> {
    return sendJsonCommand(
      `/operations/visit-sessions/${sessionId}/items`,
      'POST',
      body,
      'This property could not be added to the itinerary.',
      toSessionDetail,
      context
    );
  },

  /**
   * Removes one itinerary item. The command requires a JSON body even though the method is DELETE,
   * and the removal is a soft delete that stays in the session's history.
   */
  removeSessionItem(
    sessionId: number,
    itemId: number,
    body: { expectedSessionVersion: number; reason: string | null },
    context: OperationsRequestContext = {}
  ): Promise<OperationsSessionDetail> {
    return sendJsonCommand(
      `/operations/visit-sessions/${sessionId}/items/${itemId}`,
      'DELETE',
      body,
      'This stop could not be removed.',
      toSessionDetail,
      context
    );
  },

  /** Sets the exact order of every active item (`itemIds` must contain each active item once). */
  reorderSessionItems(
    sessionId: number,
    body: { expectedSessionVersion: number; itemIds: number[] },
    context: OperationsRequestContext = {}
  ): Promise<OperationsSessionDetail> {
    return sendJsonCommand(
      `/operations/visit-sessions/${sessionId}/items/order`,
      'PUT',
      body,
      'The itinerary order could not be saved.',
      toSessionDetail,
      context
    );
  },

  /** Records lessor-stated property availability for one DRAFT item. */
  setSessionItemAvailability(
    sessionId: number,
    itemId: number,
    body: {
      expectedSessionVersion: number;
      status: 'CONFIRMED' | 'UNAVAILABLE';
      availabilityStartAt: string | null;
      availabilityEndAt: string | null;
      availabilityZoneId: string | null;
      availabilitySource: string | null;
    },
    context: OperationsRequestContext = {}
  ): Promise<OperationsSessionDetail> {
    return sendJsonCommand(
      `/operations/visit-sessions/${sessionId}/items/${itemId}/availability`,
      'PUT',
      body,
      'The property availability could not be recorded.',
      toSessionDetail,
      context
    );
  },

  /**
   * Bounded ranked recommendations for one owned DRAFT Session. `all=false` is the default and
   * accepts no query, cursor or custom page size.
   */
  rankedRecommendations(
    sessionId: number,
    expectedSessionVersion: number,
    context: OperationsRequestContext = {}
  ): Promise<OperationsRecommendationView> {
    return sendJsonCommand(
      `/operations/visit-sessions/${sessionId}/oe-recommendations`,
      'POST',
      { expectedSessionVersion },
      'Recommendations could not be loaded.',
      toRecommendationView,
      context
    );
  },

  /**
   * Cursor-paged eligible-Ground-Executive discovery. `q` searches by partial name or exact
   * operational user ID; an opaque `cursor` continues the previous page under the same
   * actor/session/version/City/Team/query/page-size context.
   */
  discoverGroundExecutives(
    sessionId: number,
    expectedSessionVersion: number,
    options: { q?: string | null; cursor?: string | null; size?: number } = {},
    context: OperationsRequestContext = {}
  ): Promise<OperationsRecommendationView> {
    const params = new URLSearchParams();
    params.set('all', 'true');
    params.set('size', String(options.size ?? OPERATIONS_DISCOVERY_PAGE_SIZE));
    const query = options.q?.trim();
    if (query) params.set('q', query);
    if (options.cursor) params.set('cursor', options.cursor);
    return sendJsonCommand(
      `/operations/visit-sessions/${sessionId}/oe-recommendations?${params.toString()}`,
      'POST',
      { expectedSessionVersion },
      'Eligible Ground Executives could not be loaded.',
      toRecommendationView,
      context
    );
  },

  /**
   * Restricted, plan-specific Ground Executive availability information. This is not a roster and
   * never carries booking authority; every page is a fresh evaluation for the exact Session version.
   */
  geAvailability(
    sessionId: number,
    expectedSessionVersion: number,
    options: { q?: string | null; cursor?: string | null; size?: number } = {},
    context: OperationsRequestContext = {}
  ): Promise<OperationsGeAvailabilityView> {
    const params = new URLSearchParams();
    params.set('size', String(options.size ?? OPERATIONS_DISCOVERY_PAGE_SIZE));
    const query = options.q?.trim();
    if (query) params.set('q', query);
    if (options.cursor) params.set('cursor', options.cursor);
    return sendJsonCommand(
      `/operations/visit-sessions/${sessionId}/oe-ge-availability?${params.toString()}`,
      'POST',
      { expectedSessionVersion },
      'Ground Executive availability could not be loaded.',
      toGeAvailabilityView,
      context
    );
  },

  /**
   * The authoritative booking command. The returned Session is the truth about the visit's state,
   * version, assignment and tenant confirmation; nothing here imitates a booking.
   *
   * The mapper is bound to the exact selection that was submitted: the same Session root, a version
   * that advanced past the one this command was built against, the selected Ground Executive and the
   * submitted instant. A `200` that carries a different, incomplete, non-scheduled or internally
   * inconsistent Session is refused here, which is what makes the caller report an unconfirmed
   * outcome rather than announce a booking the client cannot display.
   */
  async selectGroundExecutive(
    sessionId: number,
    body: {
      expectedSessionVersion: number;
      groundExecutiveUserId: number;
      scheduledAt: string;
      zoneId: string;
      overrideReason: string | null;
      commandId: string;
    },
    context: OperationsRequestContext = {}
  ): Promise<OperationsSchedulingApproval> {
    const controller = new AbortController();
    const abort = () => controller.abort();
    if (context.signal?.aborted) abort();
    context.signal?.addEventListener('abort', abort, { once: true });
    const timer = setTimeout(abort, 10000);
    try { return await sendJsonCommand(
      `/operations/visit-sessions/${sessionId}/oe-recommendations/selection`,
      'POST',
      body,
      'This visit could not be scheduled.',
      (payload) => toSchedulingApproval(payload, {
        sessionId,
        expectedSessionVersion: body.expectedSessionVersion,
        groundExecutiveUserId: body.groundExecutiveUserId,
        scheduledAt: body.scheduledAt
      }),
      { ...context, signal: controller.signal }
    );
    } finally { clearTimeout(timer); context.signal?.removeEventListener('abort', abort); }
  },

  async schedulingCommand(sessionId: number, intent: SchedulingIntent, context: OperationsRequestContext = {}): Promise<OperationsSchedulingApproval | null> {
    const session = requireSession(context);
    const controller = new AbortController();
    const abort = () => controller.abort();
    if (context.signal?.aborted) abort();
    context.signal?.addEventListener('abort', abort, { once: true });
    const timer = setTimeout(abort, 10000);
    try {
      const response = await sendOperationsRequest(`/operations/visit-sessions/${sessionId}/oe-recommendations/selection/${intent.commandId}`,
        'The booking could not be verified. Check again when the connection is available.', { ...context, session, signal: controller.signal });
      const payload = await readOperationsPayload(response, 'The booking could not be verified.') as Record<string, unknown> | null;
      assertOperationsSessionActive(session);
      if (payload?.commandId !== intent.commandId) throw Error('The booking receipt did not match this command.');
      if (payload.state === 'NOT_RECORDED' && payload.approval === null) return null;
      const approval = payload.state === 'COMPLETED' ? toSchedulingApproval(payload.approval, { sessionId, ...intent.body }) : null;
      if (!approval) throw Error('The booking receipt could not be verified.');
      return approval;
    } finally { clearTimeout(timer); context.signal?.removeEventListener('abort', abort); }
  }
};
