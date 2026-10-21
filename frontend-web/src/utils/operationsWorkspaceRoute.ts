/**
 * Strict, pure URL contract for the Operations destination.
 *
 * A URL may carry only the queue identity, page and exact filter values the server understands.
 * Unknown values are dropped rather than interpreted, numeric references must be positive integers,
 * and nothing here can carry personal or record detail beyond an opaque numeric ID.
 */

export const OPERATIONS_QUEUE_KEYS = ['attention', 'my-work', 'claimable'] as const;
export type OperationsQueueKey = (typeof OPERATIONS_QUEUE_KEYS)[number];
export type OperationsTargetType = 'REQUEST' | 'SESSION';

export const OPERATIONS_REQUEST_STATUS_CODES = [
  'RECEIVED',
  'COORDINATING',
  'SCHEDULED',
  'UNAVAILABLE',
  'CANCELLED'
] as const;

export const OPERATIONS_SESSION_STATUS_CODES = [
  'DRAFT',
  'SCHEDULED',
  'STARTED',
  'COMPLETED',
  'EXPIRED',
  'CANCELLED',
  'NO_SHOW',
  'PROVISIONAL_NO_SHOW',
  'REPAIR_REQUIRED',
  'INTERRUPTED'
] as const;

export interface OperationsQueueFilters {
  targetType: OperationsTargetType | null;
  status: string | null;
  cityId: number | null;
  teamId: number | null;
  targetId: number | null;
}

export interface OperationsHomeRoute {
  kind: 'home';
}

export interface OperationsQueueRoute {
  kind: 'queue';
  queue: OperationsQueueKey;
  /** One-based page number, exactly as shown in the URL and the pager. */
  page: number;
  filters: OperationsQueueFilters;
}

export interface OperationsDetailRoute {
  kind: 'detail';
  targetType: OperationsTargetType;
  targetId: number;
  from: OperationsQueueKey | 'home';
  /** One-based page number of the queue the detail was opened from. */
  page: number;
  filters: OperationsQueueFilters;
}

export type OperationsRoute = OperationsHomeRoute | OperationsQueueRoute | OperationsDetailRoute;

export const EMPTY_OPERATIONS_FILTERS: OperationsQueueFilters = {
  targetType: null,
  status: null,
  cityId: null,
  teamId: null,
  targetId: null
};

export const operationsStatusCodes = (targetType: OperationsTargetType | null): readonly string[] => {
  if (targetType === 'REQUEST') return OPERATIONS_REQUEST_STATUS_CODES;
  if (targetType === 'SESSION') return OPERATIONS_SESSION_STATUS_CODES;
  return [];
};

export const isOperationsQueueKey = (value: string | null | undefined): value is OperationsQueueKey =>
  value === 'attention' || value === 'my-work' || value === 'claimable';

export const isOperationsPath = (pathname: string | null | undefined): boolean => {
  if (!pathname) return false;
  const normalized = normalizePath(pathname);
  return normalized === '/operations' || normalized.startsWith('/operations/');
};

export const hasOperationsFilters = (filters: OperationsQueueFilters): boolean =>
  filters.targetType !== null
  || filters.status !== null
  || filters.cityId !== null
  || filters.teamId !== null
  || filters.targetId !== null;

/**
 * The one canonical filter shape.
 *
 * Status and exact-record filters depend on an explicit record type: without it the backend rejects
 * the combination, and a chip removal must never serialize a request the server cannot answer.
 * City and Team stay independent because the approved server contract accepts either on its own.
 */
export const normalizeOperationsFilters = (
  filters: OperationsQueueFilters
): OperationsQueueFilters => {
  const targetType = filters.targetType;
  const status = targetType !== null
    && filters.status !== null
    && (operationsStatusCodes(targetType) as readonly string[]).includes(filters.status)
    ? filters.status
    : null;
  return {
    targetType,
    status,
    cityId: filters.cityId,
    teamId: filters.teamId,
    targetId: targetType === null ? null : filters.targetId
  };
};

export const operationsFiltersEqual = (
  left: OperationsQueueFilters,
  right: OperationsQueueFilters
): boolean =>
  left.targetType === right.targetType
  && left.status === right.status
  && left.cityId === right.cityId
  && left.teamId === right.teamId
  && left.targetId === right.targetId;

export const operationsRoutesEqual = (left: OperationsRoute, right: OperationsRoute): boolean => {
  if (left.kind !== right.kind) return false;
  if (left.kind === 'home') return true;
  if (left.kind === 'queue') {
    const other = right as OperationsQueueRoute;
    return left.queue === other.queue
      && left.page === other.page
      && operationsFiltersEqual(left.filters, other.filters);
  }
  const other = right as OperationsDetailRoute;
  return left.targetType === other.targetType
    && left.targetId === other.targetId
    && left.from === other.from
    && left.page === other.page
    && operationsFiltersEqual(left.filters, other.filters);
};

/**
 * Parses a current location into the canonical route shape. Returns `null` for a path that is not
 * an Operations destination or does not match the approved route grammar, so callers can fall back
 * to `/operations` without ever treating an unknown path as a queue.
 */
export const parseOperationsRoute = (
  pathname: string | null | undefined,
  search: string | null | undefined = ''
): OperationsRoute | null => {
  if (!isOperationsPath(pathname)) return null;
  const segments = normalizePath(pathname as string).split('/').filter(Boolean);
  const params = new URLSearchParams(search ?? '');
  if (segments.length === 1) return { kind: 'home' };

  if (segments[1] === 'queues' && segments.length === 3 && isOperationsQueueKey(segments[2])) {
    return {
      kind: 'queue',
      queue: segments[2],
      page: readPage(params),
      filters: readFilters(params)
    };
  }

  if (segments.length === 3 && (segments[1] === 'requests' || segments[1] === 'sessions')) {
    const targetId = readPositiveInteger(segments[2]);
    if (targetId === null) return null;
    const from = readQueueName(params.get('from')) ?? 'home';
    return {
      kind: 'detail',
      targetType: segments[1] === 'requests' ? 'REQUEST' : 'SESSION',
      targetId,
      from,
      page: readPage(params),
      filters: readFilters(params)
    };
  }

  return null;
};

/**
 * Serializes the canonical URL. Queue and detail routes always carry their page and filters so a
 * refresh, Back/Forward or shared link restores the same server query.
 */
export const serializeOperationsRoute = (route: OperationsRoute): string => {
  if (route.kind === 'home') return '/operations';
  const params = new URLSearchParams();
  if (route.page > 1) params.set('page', String(route.page));
  const filters = normalizeOperationsFilters(route.filters);
  if (filters.targetType) params.set('type', filters.targetType);
  if (filters.status) params.set('status', filters.status);
  if (filters.cityId !== null) params.set('city', String(filters.cityId));
  if (filters.teamId !== null) params.set('team', String(filters.teamId));
  if (filters.targetId !== null) params.set('ref', String(filters.targetId));
  if (route.kind === 'detail' && route.from !== 'home') params.set('from', route.from);

  const path = route.kind === 'queue'
    ? `/operations/queues/${route.queue}`
    : `/operations/${route.targetType === 'REQUEST' ? 'requests' : 'sessions'}/${route.targetId}`;
  const query = params.toString();
  return query ? `${path}?${query}` : path;
};

/** The safe return path preserved through authentication. It never accepts an arbitrary URL. */
export const operationsReturnPath = (
  pathname: string | null | undefined,
  search: string | null | undefined = ''
): string => {
  const route = parseOperationsRoute(pathname, search);
  return route ? serializeOperationsRoute(route) : '/operations';
};

export const queueRoute = (
  queue: OperationsQueueKey,
  page = 1,
  filters: OperationsQueueFilters = EMPTY_OPERATIONS_FILTERS
): OperationsQueueRoute => ({ kind: 'queue', queue, page, filters });

export const detailRoute = (
  targetType: OperationsTargetType,
  targetId: number,
  from: OperationsQueueKey | 'home',
  page: number,
  filters: OperationsQueueFilters
): OperationsDetailRoute => ({ kind: 'detail', targetType, targetId, from, page, filters });

/** The queue a detail returns to, preserving its page and filters. */
export const returnRouteForDetail = (
  route: OperationsDetailRoute
): OperationsRoute => route.from === 'home'
  ? { kind: 'home' }
  : queueRoute(route.from, route.page, route.filters);

/**
 * Reads the current browser location without touching the URL. Used at mount and on popstate, so a
 * direct load and a Back/Forward navigation share exactly one parser.
 */
export const readBrowserOperationsRoute = (): OperationsRoute => {
  if (typeof window === 'undefined') return { kind: 'home' };
  return parseOperationsRoute(window.location.pathname, window.location.search) ?? { kind: 'home' };
};

const normalizePath = (pathname: string): string => {
  const trimmed = pathname.trim();
  const withoutTrailing = trimmed.length > 1 ? trimmed.replace(/\/+$/, '') : trimmed;
  return withoutTrailing.startsWith('/') ? withoutTrailing : `/${withoutTrailing}`;
};

const readQueueName = (value: string | null): OperationsQueueKey | 'home' | null => {
  if (value === null) return null;
  if (value === 'home') return 'home';
  return isOperationsQueueKey(value) ? value : null;
};

const readPage = (params: URLSearchParams): number => {
  const raw = params.get('page');
  if (raw === null) return 1;
  const page = readPositiveInteger(raw);
  return page ?? 1;
};

const readFilters = (params: URLSearchParams): OperationsQueueFilters => {
  const type = readTargetType(params.get('type'));
  const status = readStatus(params.get('status'), type);
  const targetId = type === null ? null : readPositiveInteger(params.get('ref'));
  return normalizeOperationsFilters({
    targetType: type,
    status,
    cityId: readPositiveInteger(params.get('city')),
    teamId: readPositiveInteger(params.get('team')),
    targetId
  });
};

const readTargetType = (value: string | null): OperationsTargetType | null => {
  if (value === 'REQUEST' || value === 'SESSION') return value;
  return null;
};

const readStatus = (value: string | null, type: OperationsTargetType | null): string | null => {
  if (value === null || type === null) return null;
  return (operationsStatusCodes(type) as readonly string[]).includes(value) ? value : null;
};

const readPositiveInteger = (value: string | null | undefined): number | null => {
  if (!value || !/^\d+$/.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
};

