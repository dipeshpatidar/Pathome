import { API_ROOT_URL } from '../config/endpoints.ts';
import { ApiRequestError, createApiRequestError } from './apiError.ts';
import {
  captureOperationsSession,
  isOperationsSessionActive,
  OperationsSessionChangedError,
  type OperationsSession
} from '../utils/operationsSession.ts';

export interface StaffCapabilityFact {
  id: number;
  capability: string;
  scopeType: string;
  cityId: number | null;
  cityDisplayName: string | null;
  teamId: number | null;
  teamDisplayName: string | null;
  effectiveAt: string | null;
  expiresAt: string | null;
  revokedAt: string | null;
  createdAt: string | null;
  provisioningSource: string | null;
}

export interface StaffAccessState {
  userId: number;
  profileExists: boolean;
  staffActive: boolean;
  version: number | null;
  staffActivatedAt: string | null;
  staffDeactivatedAt: string | null;
  grants: StaffCapabilityFact[];
}

export interface StaffAccount {
  userId: number;
  email: string | null;
  fullName: string | null;
  profileExists: boolean;
  staffActive: boolean;
}

export interface StaffAccountPage {
  items: StaffAccount[];
  totalCount: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface StaffScopeOption {
  id: number;
  displayName: string;
  code: string;
  version: number;
  cityId: number;
}

export interface StaffRequestContext {
  signal?: AbortSignal;
  session?: OperationsSession;
  onDispatch?: () => void;
}

const record = (value: unknown): Record<string, unknown> | null =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;

const integer = (value: unknown): number | null =>
  typeof value === 'number' && Number.isSafeInteger(value) ? value : null;

const text = (value: unknown): string | null =>
  typeof value === 'string' && value.trim().length > 0 ? value : null;

const grant = (raw: unknown): StaffCapabilityFact | null => {
  const value = record(raw);
  const id = integer(value?.id);
  const capability = text(value?.capability);
  const scopeType = text(value?.scopeType);
  if (id === null || !capability || !scopeType) return null;
  return {
    id,
    capability,
    scopeType,
    cityId: integer(value?.cityId),
    cityDisplayName: text(value?.cityDisplayName),
    teamId: integer(value?.teamId),
    teamDisplayName: text(value?.teamDisplayName),
    effectiveAt: text(value?.effectiveAt),
    expiresAt: text(value?.expiresAt),
    revokedAt: text(value?.revokedAt),
    createdAt: text(value?.createdAt),
    provisioningSource: text(value?.provisioningSource)
  };
};

const accessState = (raw: unknown): StaffAccessState => {
  const value = record(raw);
  const userId = integer(value?.userId);
  if (!value || userId === null) throw new Error('Staff access response was incomplete');
  return {
    userId,
    profileExists: value.profileExists === true,
    staffActive: value.staffActive === true,
    version: integer(value.version),
    staffActivatedAt: text(value.staffActivatedAt),
    staffDeactivatedAt: text(value.staffDeactivatedAt),
    grants: Array.isArray(value.grants)
      ? value.grants.map(grant).filter((entry): entry is StaffCapabilityFact => entry !== null)
      : []
  };
};

const session = (): OperationsSession => {
  const current = captureOperationsSession();
  if (!current) throw new ApiRequestError('Please sign in to manage staff permissions.', 401);
  return current;
};

const requireActive = (captured: OperationsSession): void => {
  if (!isOperationsSessionActive(captured)) throw new OperationsSessionChangedError();
};

const send = async (
  path: string,
  options: { method?: 'GET' | 'POST'; body?: unknown; signal?: AbortSignal },
  captured: OperationsSession = session(),
  onDispatch?: () => void
): Promise<unknown> => {
  requireActive(captured);
  onDispatch?.();
  const response = await fetch(`${API_ROOT_URL}${path}`, {
    method: options.method ?? 'GET',
    headers: {
      Authorization: `Bearer ${captured.token}`,
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
        response,
        'Staff permissions could not be completed.',
        false,
        captured.token
      );
    } catch (cause) {
      failure = cause;
    }
    requireActive(captured);
    throw failure;
  }
  requireActive(captured);
  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    requireActive(captured);
    throw new Error('Staff permissions returned an unreadable response.');
  }
  requireActive(captured);
  return payload;
};

export const staffAdministrationService = {
  async capabilities(context: StaffRequestContext = {}): Promise<{
    staffActive: boolean;
    capabilities: Array<{ capability: string; scopeType: string }>;
    serverTime: string | null;
  }> {
    const captured = context.session ?? session();
    const value = record(await send(
      '/staff/me/capabilities', { signal: context.signal }, captured, context.onDispatch
    ));
    const capabilities = Array.isArray(value?.capabilities)
      ? value.capabilities.map(record).filter((entry): entry is Record<string, unknown> => entry !== null)
        .map((entry) => ({ capability: text(entry.capability) ?? '', scopeType: text(entry.scopeType) ?? '' }))
        .filter((entry) => entry.capability && entry.scopeType)
      : [];
    return {
      staffActive: value?.staffActive === true,
      capabilities,
      serverTime: text(value?.serverTime)
    };
  },

  async searchAccounts(query: string, context: StaffRequestContext = {}): Promise<StaffAccountPage> {
    const captured = context.session ?? session();
    const value = record(await send(
      `/staff/admin/users?query=${encodeURIComponent(query)}&page=0&size=20`,
      { signal: context.signal }, captured, context.onDispatch
    ));
    const items = Array.isArray(value?.items)
      ? value.items.map(record).filter((entry): entry is Record<string, unknown> => entry !== null)
        .map((entry) => ({
          userId: integer(entry.userId),
          email: text(entry.email),
          fullName: text(entry.fullName),
          profileExists: entry.profileExists === true,
          staffActive: entry.staffActive === true
        }))
        .filter((entry): entry is StaffAccount => entry.userId !== null)
      : null;
    if (!value || items === null) throw new Error('Staff search response was incomplete');
    return {
      items,
      totalCount: integer(value.totalCount) ?? items.length,
      page: integer(value.page) ?? 0,
      size: integer(value.size) ?? 20,
      totalPages: integer(value.totalPages) ?? 0
    };
  },

  async inspect(userId: number, context: StaffRequestContext = {}): Promise<StaffAccessState> {
    const captured = context.session ?? session();
    return accessState(await send(
      `/staff/admin/users/${userId}`, { signal: context.signal }, captured, context.onDispatch
    ));
  },

  async createGrant(userId: number, input: {
    capability: string;
    scopeType: 'GLOBAL' | 'CITY' | 'TEAM';
    cityId: number | null;
    teamId: number | null;
    effectiveAt: string | null;
    expiresAt: string | null;
    reasonCode: string;
  }, context: StaffRequestContext = {}): Promise<StaffCapabilityFact> {
    const captured = context.session ?? session();
    const result = grant(await send(`/staff/admin/users/${userId}/grants`, {
      method: 'POST',
      body: input,
      signal: context.signal
    }, captured, context.onDispatch));
    if (!result) throw new Error('Grant confirmation was incomplete');
    return result;
  },

  async revoke(grantId: number, reasonCode: string,
    context: StaffRequestContext = {}): Promise<StaffCapabilityFact> {
    const captured = context.session ?? session();
    const result = grant(await send(`/staff/admin/grants/${grantId}/revoke`, {
      method: 'POST',
      body: { reasonCode },
      signal: context.signal
    }, captured, context.onDispatch));
    if (!result) throw new Error('Revocation confirmation was incomplete');
    return result;
  },

  async cities(context: StaffRequestContext = {}): Promise<StaffScopeOption[]> {
    const captured = context.session ?? session();
    const value = record(await send(
      '/staff/admin/operating-teams/cities?page=0&size=100',
      { signal: context.signal }, captured, context.onDispatch
    ));
    if (!Array.isArray(value?.items)) throw new Error('City options were incomplete');
    return value.items.map(record).filter((entry): entry is Record<string, unknown> => entry !== null)
      .map((entry) => ({
        id: integer(entry.id),
        displayName: text(entry.displayName),
        code: text(entry.code),
        version: integer(entry.version),
        cityId: integer(entry.id)
      }))
      .filter((entry): entry is StaffScopeOption =>
        entry.id !== null && entry.displayName !== null && entry.code !== null
        && entry.version !== null && entry.cityId !== null);
  },

  async teams(cityId: number, context: StaffRequestContext = {}): Promise<StaffScopeOption[]> {
    const captured = context.session ?? session();
    const value = record(await send(
      `/staff/admin/cities/${cityId}/operating-teams?page=0&size=100`,
      { signal: context.signal }, captured, context.onDispatch
    ));
    if (!Array.isArray(value?.items)) throw new Error('Team options were incomplete');
    return value.items.map(record).filter((entry): entry is Record<string, unknown> => entry !== null)
      .map((entry) => ({
        id: integer(entry.id),
        displayName: text(entry.displayName),
        code: text(entry.code),
        version: integer(entry.version),
        cityId: integer(entry.cityId)
      }))
      .filter((entry): entry is StaffScopeOption =>
        entry.id !== null && entry.displayName !== null && entry.code !== null
        && entry.version !== null && entry.cityId !== null);
  }
};
