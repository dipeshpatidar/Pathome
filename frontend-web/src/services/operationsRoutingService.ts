import { API_ROOT_URL } from '../config/endpoints.ts';
import { ApiRequestError, createApiRequestError } from './apiError.ts';
import {
  captureOperationsSession,
  isOperationsSessionActive,
  OperationsSessionChangedError,
  type OperationsSession
} from '../utils/operationsSession.ts';

export interface RoutingTask {
  taskId: number;
  taskVersion: number;
  state: string;
  reasonCode: string | null;
  requestId: number;
  requestVersion: number;
  requestStatus: string;
  cityId: number | null;
  cityDisplayName: string | null;
  localityId: number | null;
  localityDisplayName: string | null;
  listingId: number | null;
  listingTitle: string | null;
  teamId: number | null;
  coordinatorUserId: number | null;
  resultTeamId: number | null;
  resultTeamDisplayName: string | null;
  resultRequestVersion: number | null;
  enrolledAt: string | null;
  updatedAt: string | null;
  initialRoutingEligible: boolean;
}

export interface RoutingTaskPage {
  items: RoutingTask[];
  totalCount: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface RoutingTeam {
  id: number;
  cityId: number;
  code: string;
  displayName: string;
  version: number;
}

export interface RoutingCandidateSet {
  cityId: number;
  cityVersion: number;
  teams: RoutingTeam[];
}

export interface RoutingUnclaimedWork {
  targetType: string;
  targetId: number;
  cityId: number;
  cityDisplayName: string;
  teamId: number;
  teamDisplayName: string;
  status: string;
  createdAt: string | null;
}

export interface RoutingCoverage {
  id: number;
  cityId: number;
  cityDisplayName: string;
  localityId: number;
  localityDisplayName: string;
  teamId: number;
  teamDisplayName: string;
  active: boolean;
  version: number;
  updatedAt: string | null;
  changeReasonCode: string;
}

export interface RoutingCoveragePage {
  items: RoutingCoverage[];
  totalCount: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface CoverageOption {
  id: number;
  displayName: string;
  cityId: number;
  version: number | null;
}

export interface RoutingCoverageOptions {
  city: CoverageOption;
  localities: CoverageOption[];
  teams: CoverageOption[];
}

export interface RoutingRequestContext {
  signal?: AbortSignal;
  /** The exact session captured when this logical operation began. */
  session?: OperationsSession;
}

const record = (value: unknown): Record<string, unknown> | null =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;

const integer = (value: unknown): number | null =>
  typeof value === 'number' && Number.isSafeInteger(value) ? value : null;

const text = (value: unknown): string | null =>
  typeof value === 'string' && value.trim().length > 0 ? value : null;

const parseTask = (raw: unknown): RoutingTask | null => {
  const value = record(raw);
  const taskId = integer(value?.taskId);
  const taskVersion = integer(value?.taskVersion);
  const requestId = integer(value?.requestId);
  const requestVersion = integer(value?.requestVersion);
  const state = text(value?.state);
  const requestStatus = text(value?.requestStatus);
  if (!value || taskId === null || taskVersion === null || requestId === null
    || requestVersion === null || state === null || requestStatus === null) return null;
  return {
    taskId,
    taskVersion,
    state,
    reasonCode: text(value.reasonCode),
    requestId,
    requestVersion,
    requestStatus,
    cityId: integer(value.cityId),
    cityDisplayName: text(value.cityDisplayName),
    localityId: integer(value.localityId),
    localityDisplayName: text(value.localityDisplayName),
    listingId: integer(value.listingId),
    listingTitle: text(value.listingTitle),
    teamId: integer(value.teamId),
    coordinatorUserId: integer(value.coordinatorUserId),
    resultTeamId: integer(value.resultTeamId),
    resultTeamDisplayName: text(value.resultTeamDisplayName),
    resultRequestVersion: integer(value.resultRequestVersion),
    enrolledAt: text(value.enrolledAt),
    updatedAt: text(value.updatedAt),
    initialRoutingEligible: value.initialRoutingEligible === true
  };
};

const parseCoverage = (raw: unknown): RoutingCoverage | null => {
  const value = record(raw);
  const id = integer(value?.id);
  const cityId = integer(value?.cityId);
  const localityId = integer(value?.localityId);
  const teamId = integer(value?.teamId);
  const version = integer(value?.version);
  const cityDisplayName = text(value?.cityDisplayName);
  const localityDisplayName = text(value?.localityDisplayName);
  const teamDisplayName = text(value?.teamDisplayName);
  if (id === null || cityId === null || localityId === null || teamId === null
    || version === null || !cityDisplayName || !localityDisplayName || !teamDisplayName) return null;
  return {
    id,
    cityId,
    cityDisplayName,
    localityId,
    localityDisplayName,
    teamId,
    teamDisplayName,
    active: value?.active === true,
    version,
    updatedAt: text(value?.updatedAt),
    changeReasonCode: text(value?.changeReasonCode) ?? ''
  };
};

const parseOption = (raw: unknown): CoverageOption | null => {
  const value = record(raw);
  const id = integer(value?.id);
  const cityId = integer(value?.cityId);
  const displayName = text(value?.displayName);
  if (id === null || cityId === null || !displayName) return null;
  return { id, cityId, displayName, version: integer(value?.version) };
};

const parseOptions = (raw: unknown): RoutingCoverageOptions => {
  const value = record(raw);
  const city = parseOption(value?.city);
  const localities = Array.isArray(value?.localities)
    ? value.localities.map(parseOption).filter((entry): entry is CoverageOption => entry !== null)
    : [];
  const teams = Array.isArray(value?.teams)
    ? value.teams.map(parseOption).filter((entry): entry is CoverageOption => entry !== null)
    : [];
  if (!city) throw new Error('Coverage options were incomplete');
  return { city, localities, teams };
};

const parseTeam = (raw: unknown): RoutingTeam | null => {
  const value = record(raw);
  const id = integer(value?.id);
  const cityId = integer(value?.cityId);
  const code = text(value?.code);
  const displayName = text(value?.displayName);
  const version = integer(value?.version);
  if (id === null || cityId === null || version === null || !code || !displayName) return null;
  return { id, cityId, code, displayName, version };
};

const request = async (
  path: string,
  options: { method?: 'GET' | 'POST'; body?: unknown; signal?: AbortSignal } = {},
  context: RoutingRequestContext = {}
): Promise<unknown> => {
  const session = context.session ?? captureOperationsSession();
  if (!session) throw new ApiRequestError('Please sign in to manage City routing.', 401);
  return send(path, { ...options, signal: options.signal ?? context.signal }, session);
};

const requireActive = (session: OperationsSession): void => {
  if (!isOperationsSessionActive(session)) throw new OperationsSessionChangedError();
};

const send = async (
  path: string,
  options: { method?: 'GET' | 'POST'; body?: unknown; signal?: AbortSignal },
  session: OperationsSession
): Promise<unknown> => {
  requireActive(session);
  const response = await fetch(`${API_ROOT_URL}${path}`, {
    method: options.method ?? 'GET',
    headers: {
      Authorization: `Bearer ${session.token}`,
      Accept: 'application/json',
      ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' })
    },
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    cache: 'no-store',
    signal: options.signal
  });
  if (!response.ok) {
    let failure: unknown;
    try {
      failure = await createApiRequestError(
        response, 'City routing could not be completed.', false, session.token
      );
    } catch (cause) {
      failure = cause;
    }
    requireActive(session);
    throw failure;
  }
  requireActive(session);
  if (response.status === 204) return null;
  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    requireActive(session);
    throw new ApiRequestError('City routing returned an unreadable response.', response.status);
  }
  requireActive(session);
  return payload;
};

export const operationsRoutingService = {
  async cities(context: RoutingRequestContext = {}): Promise<CoverageOption[]> {
    const value = await request('/operations/automatic-routing/coverage/cities', {}, context);
    if (!Array.isArray(value)) throw new Error('Routing City options were incomplete');
    return value.map(parseOption).filter((entry): entry is CoverageOption => entry !== null);
  },

  async tasks(page = 0, context: RoutingRequestContext = {}): Promise<RoutingTaskPage> {
    const value = record(await request(
      `/operations/automatic-routing/tasks?page=${page}&size=20`, {}, context
    ));
    const items = Array.isArray(value?.items)
      ? value.items.map(parseTask).filter((entry): entry is RoutingTask => entry !== null)
      : null;
    const totalCount = integer(value?.totalCount);
    if (!value || items === null || totalCount === null) throw new Error('Routing tasks were incomplete');
    return {
      items,
      totalCount,
      page: integer(value.page) ?? page,
      size: integer(value.size) ?? 20,
      totalPages: integer(value.totalPages) ?? 0
    };
  },

  async task(taskId: number, context: RoutingRequestContext = {}): Promise<RoutingTask> {
    const task = parseTask(await request(
      `/operations/automatic-routing/tasks/${taskId}`, {}, context
    ));
    if (!task) throw new Error('Routing task response was incomplete');
    return task;
  },

  async unclaimed(page = 0, context: RoutingRequestContext = {}): Promise<{
    items: RoutingUnclaimedWork[];
    totalCount: number;
    page: number;
    size: number;
    totalPages: number;
  }> {
    const value = record(await request(
      `/operations/automatic-routing/tasks/unclaimed?page=${page}&size=20`, {}, context
    ));
    if (!value || !Array.isArray(value.items)) throw new Error('Unclaimed work response was incomplete');
    const items = value.items.map(record)
      .filter((entry): entry is Record<string, unknown> => entry !== null)
      .map((entry) => ({
        targetType: text(entry.targetType),
        targetId: integer(entry.targetId),
        cityId: integer(entry.cityId),
        cityDisplayName: text(entry.cityDisplayName),
        teamId: integer(entry.teamId),
        teamDisplayName: text(entry.teamDisplayName),
        status: text(entry.status),
        createdAt: text(entry.createdAt)
      }))
      .filter((entry): entry is RoutingUnclaimedWork =>
        entry.targetType !== null && entry.targetId !== null && entry.cityId !== null
        && entry.cityDisplayName !== null && entry.teamId !== null
        && entry.teamDisplayName !== null && entry.status !== null);
    return {
      items,
      totalCount: integer(value.totalCount) ?? items.length,
      page: integer(value.page) ?? page,
      size: integer(value.size) ?? 20,
      totalPages: integer(value.totalPages) ?? 0
    };
  },

  async candidates(requestId: number, cityId: number,
    context: RoutingRequestContext = {}): Promise<RoutingCandidateSet> {
    const value = record(await request(
      `/operations/intake-routing/visit-requests/${requestId}/candidates?cityId=${cityId}&page=0&size=50`,
      {}, context
    ));
    const teams = record(value?.teams);
    const city = record(value?.city);
    const cityVersion = integer(city?.version);
    if (!teams || !Array.isArray(teams.items) || cityVersion === null) {
      throw new Error('Team candidates were incomplete');
    }
    return {
      cityId,
      cityVersion,
      teams: teams.items.map(parseTeam).filter((entry): entry is RoutingTeam => entry !== null)
    };
  },

  async route(input: {
    requestId: number;
    expectedRequestVersion: number;
    cityId: number;
    expectedCityVersion: number;
    teamId: number;
    expectedTeamVersion: number;
    expectedRoutingTaskVersion: number;
  }, context: RoutingRequestContext = {}): Promise<void> {
    await request(`/operations/intake-routing/visit-requests/${input.requestId}/route`, {
      method: 'POST',
      body: {
        expectedRequestVersion: input.expectedRequestVersion,
        cityId: input.cityId,
        expectedCityVersion: input.expectedCityVersion,
        teamId: input.teamId,
        expectedTeamVersion: input.expectedTeamVersion,
        expectedRoutingTaskVersion: input.expectedRoutingTaskVersion,
        reasonCode: 'ROUTING_EXCEPTION_TEAM_SELECTED'
      },
      signal: context.signal
    }, context);
  },

  async recheck(taskId: number, expectedTaskVersion: number,
    context: RoutingRequestContext = {}): Promise<RoutingTask> {
    const task = parseTask(await request(`/operations/automatic-routing/tasks/${taskId}/recheck`, {
      method: 'POST',
      body: { expectedTaskVersion, reasonCode: 'ROUTING_EXCEPTION_RECHECK' },
      signal: context.signal
    }, context));
    if (!task) throw new Error('Routing recheck result was incomplete');
    return task;
  },

  async coverage(cityId: number | null, page = 0,
    context: RoutingRequestContext = {}): Promise<RoutingCoveragePage> {
    const query = new URLSearchParams({ page: String(page), size: '20' });
    if (cityId !== null) query.set('cityId', String(cityId));
    const value = record(await request(
      `/operations/automatic-routing/coverage?${query}`, {}, context
    ));
    const items = Array.isArray(value?.items)
      ? value.items.map(parseCoverage).filter((entry): entry is RoutingCoverage => entry !== null)
      : null;
    if (!value || items === null) throw new Error('Coverage response was incomplete');
    return {
      items,
      totalCount: integer(value.totalCount) ?? items.length,
      page: integer(value.page) ?? page,
      size: integer(value.size) ?? 20,
      totalPages: integer(value.totalPages) ?? 0
    };
  },

  async coverageOptions(cityId: number,
    context: RoutingRequestContext = {}): Promise<RoutingCoverageOptions> {
    return parseOptions(await request(
      `/operations/automatic-routing/coverage/options?cityId=${cityId}`, {}, context
    ));
  },

  async activeCoverageForLocality(cityId: number, localityId: number,
    context: RoutingRequestContext = {}): Promise<RoutingCoverage[]> {
    const value = await request(
      `/operations/automatic-routing/coverage/locality?cityId=${cityId}&localityId=${localityId}`,
      {}, context
    );
    if (!Array.isArray(value)) throw new Error('Locality coverage response was incomplete');
    return value.map(parseCoverage).filter((entry): entry is RoutingCoverage => entry !== null);
  },

  async createCoverage(input: {
    cityId: number;
    localityId: number;
    teamId: number;
  }, context: RoutingRequestContext = {}): Promise<RoutingCoverage> {
    const coverage = parseCoverage(await request('/operations/automatic-routing/coverage', {
      method: 'POST',
      body: { ...input, reasonCode: 'TERRITORY_COVERAGE_UPDATE' },
      signal: context.signal
    }, context));
    if (!coverage) throw new Error('Coverage result was incomplete');
    return coverage;
  },

  async changeCoverageState(
    coverageId: number,
    expectedVersion: number,
    active: boolean,
    context: RoutingRequestContext = {}
  ): Promise<RoutingCoverage> {
    const coverage = parseCoverage(await request(
      `/operations/automatic-routing/coverage/${coverageId}/state`,
      {
        method: 'POST',
        body: {
          expectedVersion,
          active,
          reasonCode: active ? 'COVERAGE_REVIEWED_ACTIVE' : 'COVERAGE_RETIRED_BY_OWNER'
        },
        signal: context.signal
      },
      context
    ));
    if (!coverage) throw new Error('Coverage result was incomplete');
    return coverage;
  }
};
