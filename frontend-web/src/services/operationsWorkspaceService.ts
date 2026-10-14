import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError } from './apiError';
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
  toMyWorkItem,
  toRequestDetail,
  toSessionDetail,
  toStaffCapabilities,
  type ClaimableWorkItem,
  type LinkedRequestVersionMap,
  type OperationsMyWorkItem,
  type OperationsRequestDetail,
  type OperationsSessionDetail,
  type OperationsTargetType,
  type OperationsWorkPage,
  type StaffCapabilities
} from '../utils/operationsWorkspacePresentation';

/** Server page size stays inside the backend's 1..100 bound. */
export const OPERATIONS_WORK_PAGE_SIZE = 20;

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
  method?: 'GET' | 'POST';
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
    context: OperationsRequestContext = {}
  ): Promise<OperationsWorkPage<OperationsMyWorkItem>> {
    const query = `page=${page}&size=${OPERATIONS_WORK_PAGE_SIZE}&attentionOnly=${attentionOnly ? 'true' : 'false'}`;
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
    context: OperationsRequestContext = {}
  ): Promise<OperationsWorkPage<ClaimableWorkItem>> {
    const query = `page=${page}&size=${OPERATIONS_WORK_PAGE_SIZE}`;
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
  }
};
