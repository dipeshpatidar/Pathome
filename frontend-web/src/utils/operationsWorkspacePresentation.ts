/**
 * Pure presentation, mapping and eligibility helpers for the Pathome Operations (OE) workspace.
 *
 * Everything here is free of React, fetch and DOM APIs so it can be unit tested directly.
 * Field semantics mirror the approved backend contracts exactly:
 *   GET  /api/v1/staff/me/capabilities
 *   GET  /api/v1/operations/my-work
 *   GET  /api/v1/operations/claimable-work
 *   GET  /api/v1/operations/visit-requests/{requestId}
 *   GET  /api/v1/operations/visit-sessions/{sessionId}
 *
 * No value is invented: a field the contract does not return stays absent, and an unrecognised
 * status or state is shown as its real code rather than being silently replaced.
 */

export type OperationsTargetType = 'REQUEST' | 'SESSION';

export interface LinkedRequestVersion {
  requestId: number;
  version: number;
}

/** Shared shape of both operational queues. Deliberately carries no customer, lessor or GE data. */
export interface OperationsWorkSummary {
  targetType: OperationsTargetType;
  targetId: number;
  /**
   * Null only when the server omitted the version. Ownership commands are withheld in that case
   * instead of sending a guess that the backend would have to reject.
   */
  expectedVersion: number | null;
  cityId: number | null;
  cityDisplayName: string | null;
  teamId: number | null;
  teamDisplayName: string | null;
  status: string;
  linkedRequestVersions: LinkedRequestVersion[];
}

export interface OperationsMyWorkItem extends OperationsWorkSummary {
  updatedAt: string | null;
  scheduledAt: string | null;
  tenantConfirmationState: string | null;
}

export type ClaimableWorkItem = OperationsWorkSummary;

export interface OperationsWorkPage<T> {
  items: T[];
  totalCount: number;
  /** The same authorized queue population before optional filters. */
  unfilteredTotalCount: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface StaffCapabilitySummary {
  capability: string;
  scopeType: string;
  cityId: number | null;
  cityDisplayName: string | null;
  teamId: number | null;
  teamDisplayName: string | null;
}

export interface StaffCapabilities {
  staffActive: boolean;
  capabilities: StaffCapabilitySummary[];
  serverTime: string | null;
}

export interface OperationsSessionItem {
  itemId: number;
  listingId: number | null;
  title: string | null;
  address: string | null;
  city: string | null;
  sector: string | null;
  position: number | null;
  /** The linked Request this item was created from, when the stop is a direct tenant request. */
  sourceRequestId: number | null;
  /** The linked Request a derived property was added from (`OE_ADDED` / `LESSOR_SUGGESTED`). */
  derivedFromRequestId: number | null;
  origin: string | null;
  confirmationStatus: string | null;
  availabilityConfirmedAt: string | null;
  removedAt: string | null;
  removalReason: string | null;
  /** Recorded lessor-stated window, mapped from the backend's `propertyAvailability*` response fields. */
  availabilityStartAt: string | null;
  availabilityEndAt: string | null;
  availabilityZoneId: string | null;
  /** Channel the window was recorded from. `WHATSAPP` is a manually recorded channel, not an integration. */
  availabilitySource: string | null;
  /** Public cover for an ACTIVE listing only; null when no authorized image exists. */
  coverImageUrl: string | null;
  listingStatus: string | null;
}

export interface OperationsRequestDetail {
  requestId: number;
  status: string;
  version: number | null;
  preferredVisitTiming: string | null;
  createdAt: string | null;
  cityId: number | null;
  cityDisplayName: string | null;
  teamId: number | null;
  teamDisplayName: string | null;
  listingId: number | null;
  listingTitle: string | null;
  listingAddress: string | null;
  listingStatus: string | null;
  listingCity: string | null;
  sector: string | null;
  availabilityStartAt: string | null;
  availabilityEndAt: string | null;
  availabilityZoneId: string | null;
  preferredAt: string | null;
  sessionId: number | null;
}

export interface OperationsSessionDetail {
  sessionId: number;
  status: string;
  version: number | null;
  city: string | null;
  areaName: string | null;
  scheduledAt: string | null;
  reservedEndAt: string | null;
  durationMinutes: number | null;
  zoneId: string | null;
  representativeUserId: number | null;
  assignedAt: string | null;
  items: OperationsSessionItem[];
  linkedRequestVersions: LinkedRequestVersion[];
  linkedRequestTimingFacts: OperationsLinkedRequestTiming[];
  teamId: number | null;
  teamDisplayName: string | null;
  coordinatorUserId: number | null;
  updatedAt: string | null;
  tenantConfirmationState: string | null;
  startedAt: string | null;
  expiresAt: string | null;
  executionStateChangedAt: string | null;
}

export interface OperationsLinkedRequestTiming {
  requestId: number;
  version: number;
  preferredVisitTiming: string | null;
  availabilityStartAt: string | null;
  availabilityEndAt: string | null;
  availabilityZoneId: string | null;
  preferredAt: string | null;
}

export interface OperationsScope {
  teamId: number;
  cityId: number | null;
  cityName: string;
  teamName: string;
}

/* ------------------------------------------------------------------ *
 * Ownership command vocabulary
 * ------------------------------------------------------------------ */

/**
 * The backend requires a bounded uppercase reason code (`^[A-Z][A-Z0-9_]{0,63}$`) on every
 * claim and release. These two values are the codes the backend's own ownership HTTP tests
 * send, so the workspace never has to invent a reason taxonomy.
 */
export const OPERATIONS_CLAIM_REASON_CODE = 'CLAIM_WORK';
export const OPERATIONS_RELEASE_REASON_CODE = 'RELEASE_COORDINATOR';

/* ------------------------------------------------------------------ *
 * Capability gating
 * ------------------------------------------------------------------ */

/**
 * The operational queues authorise a caller through an effective `OPS_COORDINATE` grant scoped to
 * a TEAM (`staff_access_grants` + active Team/City + active staff profile). `findEffectiveForUser`
 * already drops revoked, expired and inactive-scope grants, so this predicate matches the
 * backend's own eligibility rule instead of guessing from a role label.
 */
export const coordinatesOperationalWork = (capability: StaffCapabilitySummary): boolean =>
  capability.capability === 'OPS_COORDINATE' && capability.scopeType === 'TEAM';

export const hasOperationsWorkspaceAccess = (
  capabilities: StaffCapabilities | null | undefined
): boolean => {
  if (!capabilities || capabilities.staffActive !== true || !Array.isArray(capabilities.capabilities)) {
    return false;
  }
  return capabilities.capabilities.some(coordinatesOperationalWork);
};

export const hasRoutingWorkspaceAccess = (
  capabilities: StaffCapabilities | null | undefined
): boolean => {
  if (!capabilities || capabilities.staffActive !== true || !Array.isArray(capabilities.capabilities)) {
    return false;
  }
  return capabilities.capabilities.some((capability) =>
    (capability.capability === 'OPS_ROUTING' && capability.scopeType === 'CITY')
    || (capability.capability === 'CITY_TEAM_ADMIN' && capability.scopeType === 'CITY')
    || (capability.capability === 'STAFF_ADMIN' && capability.scopeType === 'GLOBAL')
  );
};

export interface RoutingScope {
  cityId: number | null;
  cityName: string;
}

export const routingCapabilityScopes = (
  capabilities: StaffCapabilities | null | undefined
): RoutingScope[] => {
  if (!capabilities || !Array.isArray(capabilities.capabilities)) return [];
  const scopes = new Map<string, RoutingScope>();
  for (const capability of capabilities.capabilities) {
    if ((capability.capability === 'OPS_ROUTING'
      || capability.capability === 'CITY_TEAM_ADMIN') && capability.scopeType === 'CITY') {
      const key = String(capability.cityId ?? '-');
      scopes.set(key, {
        cityId: capability.cityId,
        cityName: capability.cityDisplayName ?? 'Supported City'
      });
    }
  }
  return [...scopes.values()].sort((left, right) =>
    (left.cityId ?? Number.MAX_SAFE_INTEGER) - (right.cityId ?? Number.MAX_SAFE_INTEGER));
};

export const routingAuthorityScopeFingerprint = (
  capabilities: StaffCapabilities | null | undefined
): string => {
  if (!capabilities?.staffActive || !Array.isArray(capabilities.capabilities)) return '';
  return capabilities.capabilities
    .filter((capability) =>
      (capability.capability === 'OPS_ROUTING'
        || capability.capability === 'CITY_TEAM_ADMIN'
        || capability.capability === 'STAFF_ADMIN')
      && capability.scopeType !== 'TEAM'
    )
    .map((capability) => [
      capability.capability,
      capability.scopeType,
      capability.cityId ?? '-'
    ].join(':'))
    .sort()
    .join('|');
};

export const operationsCapabilityScopes = (
  capabilities: StaffCapabilities | null | undefined
): OperationsScope[] => {
  if (!capabilities || !Array.isArray(capabilities.capabilities)) return [];
  const scopes: OperationsScope[] = [];
  const seenTeamIds = new Set<number>();
  for (const capability of capabilities.capabilities) {
    if (!coordinatesOperationalWork(capability)) continue;
    const teamId = capability.teamId;
    if (teamId === null || seenTeamIds.has(teamId)) continue;
    seenTeamIds.add(teamId);
    scopes.push({
      teamId,
      cityId: capability.cityId,
      cityName: capability.cityDisplayName ?? 'City not recorded',
      teamName: capability.teamDisplayName ?? 'Team not recorded'
    });
  }
  return scopes;
};

/**
 * Normalized identity of the effective Operations City/Team authority.
 *
 * Only effective OPS_COORDINATE Team grants participate. Duplicates and grant order are ignored, so
 * an unchanged background refresh never looks like a scope change, while adding, removing or moving
 * a City/Team grant always produces a different value.
 */
export const operationsAuthorityScopeFingerprint = (
  capabilities: StaffCapabilities | null | undefined
): string => {
  if (!capabilities || !Array.isArray(capabilities.capabilities)) return '';
  const scopes = new Set<string>();
  for (const capability of capabilities.capabilities) {
    if (!coordinatesOperationalWork(capability) || capability.teamId === null) continue;
    scopes.add(`${capability.cityId ?? '-'}:${capability.teamId}`);
  }
  return [...scopes].sort().join('|');
};

/**
 * Effective identity of every staff grant that can admit one of the approved Lead surfaces.
 * The existing coordinate fingerprint remains unchanged for operational work queues.
 */
export const leadAuthorityScopeFingerprint = (
  capabilities: StaffCapabilities | null | undefined
): string => {
  if (!capabilities?.staffActive || !Array.isArray(capabilities.capabilities)) {
    return '';
  }
  return capabilities.capabilities
    .filter((capability) =>
      (capability.capability === 'OPS_COORDINATE'
        || capability.capability === 'OPS_SUPERVISE'
        || capability.capability === 'OPS_INTAKE'
        || capability.capability === 'CITY_TEAM_ADMIN')
      && capability.cityId !== null
      || capability.capability === 'STAFF_ADMIN'
      && capability.scopeType === 'GLOBAL'
    )
    .map((capability) => [
      capability.capability,
      capability.scopeType,
      capability.cityId ?? '-',
      capability.teamId ?? '-'
    ].join(':'))
    .sort()
    .join('|');
};

/* ------------------------------------------------------------------ *
 * Safe wire decoding
 * ------------------------------------------------------------------ */

const asRecord = (value: unknown): Record<string, unknown> | null =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;

const readInteger = (value: unknown): number | null => {
  if (typeof value === 'number') return Number.isSafeInteger(value) ? value : null;
  if (typeof value === 'string' && /^-?\d+$/.test(value.trim())) {
    const parsed = Number(value);
    return Number.isSafeInteger(parsed) ? parsed : null;
  }
  return null;
};

const readText = (value: unknown): string | null => {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed.length > 0 ? value : null;
};

export const toLinkedRequestVersions = (value: unknown): LinkedRequestVersion[] => {
  if (!Array.isArray(value)) return [];
  const versions: LinkedRequestVersion[] = [];
  for (const entry of value) {
    const record = asRecord(entry);
    const requestId = readInteger(record?.requestId);
    const version = readInteger(record?.version);
    if (requestId === null || version === null) continue;
    versions.push({ requestId, version });
  }
  return versions;
};

export const toLinkedRequestTimingFacts = (value: unknown): OperationsLinkedRequestTiming[] => {
  if (!Array.isArray(value)) return [];
  const facts: OperationsLinkedRequestTiming[] = [];
  for (const entry of value) {
    const record = asRecord(entry);
    const requestId = readInteger(record?.requestId);
    const version = readInteger(record?.version);
    if (!record || requestId === null || version === null) continue;
    facts.push({
      requestId,
      version,
      preferredVisitTiming: readText(record.preferredVisitTiming),
      availabilityStartAt: readText(record.availabilityStartAt),
      availabilityEndAt: readText(record.availabilityEndAt),
      availabilityZoneId: readText(record.availabilityZoneId),
      preferredAt: readText(record.preferredAt)
    });
  }
  return facts;
};

export interface ParsedWorkPage {
  items: unknown[];
  totalCount: number;
  unfilteredTotalCount: number;
  page: number;
  size: number;
  totalPages: number;
}

/**
 * Reads the real page envelope (`items`, `totalCount`, `page`, `size`, `totalPages`).
 * A response that is not a page is an error rather than an empty queue: reporting zero work
 * because the payload was malformed would be a false statement about a coordinator's workload.
 */
export const parseWorkPage = (
  payload: unknown,
  fallback: { page: number; size: number }
): ParsedWorkPage => {
  const record = asRecord(payload);
  if (!record) throw new Error('Operations response was not a work page');
  if (!Array.isArray(record.items)) throw new Error('Operations work page did not contain an item list');
  const size = readInteger(record.size) ?? fallback.size;
  const page = readInteger(record.page) ?? fallback.page;
  const totalCount = readInteger(record.totalCount) ?? record.items.length;
  // Older fixtures and callers predate the additive field; the filtered total is then the only
  // truthful total available.
  const unfilteredTotalCount = readInteger(record.unfilteredTotalCount) ?? totalCount;
  const totalPages = readInteger(record.totalPages)
    ?? (size > 0 && totalCount > 0 ? Math.ceil(totalCount / size) : 0);
  return { items: record.items, totalCount, unfilteredTotalCount, page, size, totalPages };
};

export const toWorkSummary = (raw: unknown): OperationsWorkSummary | null => {
  const record = asRecord(raw);
  if (!record) return null;
  const targetType: OperationsTargetType | null =
    record.targetType === 'SESSION' ? 'SESSION' : record.targetType === 'REQUEST' ? 'REQUEST' : null;
  const targetId = readInteger(record.targetId);
  if (targetType === null || targetId === null) return null;
  return {
    targetType,
    targetId,
    expectedVersion: readInteger(record.expectedVersion),
    cityId: readInteger(record.cityId),
    cityDisplayName: readText(record.cityDisplayName),
    teamId: readInteger(record.teamId),
    teamDisplayName: readText(record.teamDisplayName),
    status: readText(record.status) ?? '',
    linkedRequestVersions: toLinkedRequestVersions(record.linkedRequestVersions)
  };
};

export const toMyWorkItem = (raw: unknown): OperationsMyWorkItem | null => {
  const summary = toWorkSummary(raw);
  if (!summary) return null;
  const record = asRecord(raw) as Record<string, unknown>;
  return {
    ...summary,
    updatedAt: readText(record.updatedAt),
    scheduledAt: readText(record.scheduledAt),
    tenantConfirmationState: readText(record.tenantConfirmationState)
  };
};

export const toClaimableWorkItem = (raw: unknown): ClaimableWorkItem | null => toWorkSummary(raw);

const STAFF_CAPABILITY_VALUES = new Set([
  'STAFF_ADMIN',
  'CITY_TEAM_ADMIN',
  'OPS_INTAKE',
  'OPS_COORDINATE',
  'OPS_SUPERVISE',
  'OPS_ROUTING'
]);
const STAFF_SCOPE_TYPE_VALUES = new Set(['GLOBAL', 'CITY', 'TEAM']);

const malformedStaffCapabilities = (detail: string): Error =>
  new Error(`Staff capability response ${detail}.`);

const requiredCapabilityText = (
  record: Record<string, unknown>,
  field: string
): string => {
  if (!Object.prototype.hasOwnProperty.call(record, field)) {
    throw malformedStaffCapabilities(`is missing ${field}`);
  }
  const value = record[field];
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw malformedStaffCapabilities(`has an invalid ${field}`);
  }
  return value;
};

const requiredCapabilityNullableText = (
  record: Record<string, unknown>,
  field: string
): string | null => {
  if (!Object.prototype.hasOwnProperty.call(record, field)) {
    throw malformedStaffCapabilities(`is missing ${field}`);
  }
  const value = record[field];
  if (value === null || typeof value === 'string') return value;
  throw malformedStaffCapabilities(`has an invalid ${field}`);
};

const requiredCapabilityNullableInteger = (
  record: Record<string, unknown>,
  field: string
): number | null => {
  if (!Object.prototype.hasOwnProperty.call(record, field)) {
    throw malformedStaffCapabilities(`is missing ${field}`);
  }
  const value = record[field];
  if (value === null) return null;
  if (typeof value === 'number' && Number.isSafeInteger(value) && value > 0) return value;
  throw malformedStaffCapabilities(`has an invalid ${field}`);
};

export const toCapabilitySummary = (raw: unknown): StaffCapabilitySummary => {
  const record = asRecord(raw);
  if (!record) throw malformedStaffCapabilities('contains an invalid capability entry');
  const capability = requiredCapabilityText(record, 'capability');
  const scopeType = requiredCapabilityText(record, 'scopeType');
  if (!STAFF_CAPABILITY_VALUES.has(capability)) {
    throw malformedStaffCapabilities('contains an unknown capability');
  }
  if (!STAFF_SCOPE_TYPE_VALUES.has(scopeType)) {
    throw malformedStaffCapabilities('contains an unknown scope type');
  }
  return {
    capability,
    scopeType,
    cityId: requiredCapabilityNullableInteger(record, 'cityId'),
    cityDisplayName: requiredCapabilityNullableText(record, 'cityDisplayName'),
    teamId: requiredCapabilityNullableInteger(record, 'teamId'),
    teamDisplayName: requiredCapabilityNullableText(record, 'teamDisplayName')
  };
};

export const toStaffCapabilities = (raw: unknown): StaffCapabilities => {
  const record = asRecord(raw);
  if (!record) throw malformedStaffCapabilities('was not an object');
  const staffActive = record.staffActive;
  if (typeof staffActive !== 'boolean') {
    throw malformedStaffCapabilities('has an invalid staffActive');
  }
  const capabilities = record.capabilities;
  if (!Array.isArray(capabilities)) {
    throw malformedStaffCapabilities('has an invalid capabilities list');
  }
  const serverTime = record.serverTime;
  if (serverTime !== undefined && serverTime !== null && typeof serverTime !== 'string') {
    throw malformedStaffCapabilities('has an invalid serverTime');
  }
  return {
    staffActive,
    capabilities: capabilities.map(toCapabilitySummary),
    serverTime: readText(serverTime)
  };
};

export const toRequestDetail = (raw: unknown): OperationsRequestDetail | null => {
  const record = asRecord(raw);
  const requestId = readInteger(record?.requestId);
  if (!record || requestId === null) return null;
  return {
    requestId,
    status: readText(record.status) ?? '',
    version: readInteger(record.version),
    preferredVisitTiming: readText(record.preferredVisitTiming),
    // created_at is stored without a time zone, so it is surfaced as stored local time.
    createdAt: readText(record.createdAt),
    cityId: readInteger(record.cityId),
    cityDisplayName: readText(record.cityDisplayName),
    teamId: readInteger(record.teamId),
    teamDisplayName: readText(record.teamDisplayName),
    listingId: readInteger(record.listingId),
    listingTitle: readText(record.listingTitle),
    listingAddress: readText(record.listingAddress),
    listingStatus: readText(record.listingStatus),
    listingCity: readText(record.listingCity),
    sector: readText(record.sector),
    availabilityStartAt: readText(record.availabilityStartAt),
    availabilityEndAt: readText(record.availabilityEndAt),
    availabilityZoneId: readText(record.availabilityZoneId),
    preferredAt: readText(record.preferredAt),
    sessionId: readInteger(record.sessionId)
  };
};

const toSessionItem = (raw: unknown): OperationsSessionItem | null => {
  const record = asRecord(raw);
  const itemId = readInteger(record?.itemId);
  if (!record || itemId === null) return null;
  return {
    itemId,
    listingId: readInteger(record.listingId),
    title: readText(record.title),
    address: readText(record.address),
    city: readText(record.city),
    sector: readText(record.sector),
    position: readInteger(record.position),
    sourceRequestId: readInteger(record.sourceRequestId),
    derivedFromRequestId: readInteger(record.derivedFromRequestId),
    origin: readText(record.origin),
    confirmationStatus: readText(record.confirmationStatus),
    availabilityConfirmedAt: readText(record.availabilityConfirmedAt),
    removedAt: readText(record.removedAt),
    removalReason: readText(record.removalReason),
    // The response DTO names these `propertyAvailability*`; the client keeps its own field names.
    // Reading the command-style names here silently discarded every real recorded window.
    availabilityStartAt: readText(record.propertyAvailabilityStartAt),
    availabilityEndAt: readText(record.propertyAvailabilityEndAt),
    availabilityZoneId: readText(record.propertyAvailabilityZoneId),
    availabilitySource: readText(record.propertyAvailabilitySource),
    coverImageUrl: readText(record.coverImageUrl),
    listingStatus: readText(record.listingStatus)
  };
};

export const toSessionDetail = (raw: unknown): OperationsSessionDetail | null => {
  const record = asRecord(raw);
  const sessionId = readInteger(record?.sessionId);
  if (!record || sessionId === null) return null;
  const items = Array.isArray(record.items) ? record.items : [];
  return {
    sessionId,
    status: readText(record.status) ?? '',
    version: readInteger(record.version),
    city: readText(record.city),
    areaName: readText(record.areaName),
    scheduledAt: readText(record.scheduledAt),
    reservedEndAt: readText(record.reservedEndAt),
    durationMinutes: readInteger(record.durationMinutes),
    zoneId: readText(record.zoneId),
    representativeUserId: readInteger(record.representativeUserId),
    assignedAt: readText(record.assignedAt),
    items: items.map(toSessionItem).filter((entry): entry is OperationsSessionItem => entry !== null),
    linkedRequestVersions: toLinkedRequestVersions(record.linkedRequestVersions),
    linkedRequestTimingFacts: toLinkedRequestTimingFacts(record.linkedRequestTimingFacts),
    teamId: readInteger(record.teamId),
    teamDisplayName: readText(record.teamDisplayName),
    coordinatorUserId: readInteger(record.coordinatorUserId),
    updatedAt: readText(record.updatedAt),
    tenantConfirmationState: readText(record.tenantConfirmationState),
    startedAt: readText(record.startedAt),
    expiresAt: readText(record.expiresAt),
    executionStateChangedAt: readText(record.executionStateChangedAt)
  };
};

/* ------------------------------------------------------------------ *
 * Ownership command builders
 * ------------------------------------------------------------------ */

export type LinkedRequestVersionMap = Record<string, number>;

/**
 * Discriminated so a caller can never send a Session version for an unlinked Request root, or an
 * empty linked-version map for a linked one.
 */
export type OwnershipCommand =
  | { targetType: 'REQUEST'; expectedRequestVersion: number }
  | { targetType: 'SESSION'; expectedSessionVersion: number; expectedRequestVersions: LinkedRequestVersionMap };

export type RequestReleaseCommand = { targetType: 'REQUEST'; expectedRequestVersion: number };
export type SessionReleaseCommand = {
  targetType: 'SESSION';
  expectedSessionVersion: number;
  expectedRequestVersions: LinkedRequestVersionMap;
};

export const linkedRequestVersionMap = (versions: LinkedRequestVersion[]): LinkedRequestVersionMap =>
  Object.fromEntries(versions.map((entry) => [String(entry.requestId), entry.version]));

/**
 * A claimable REQUEST root is always a standalone request (the backend only offers unlinked
 * requests plus unclaimed Sessions), so it is claimed by its own version. A SESSION root is
 * claimed by the Session version plus every linked Request version.
 */
export const claimCommandFor = (
  item: ClaimableWorkItem
): OwnershipCommand | null => {
  if (item.expectedVersion === null) return null;
  if (item.targetType === 'REQUEST') {
    return { targetType: 'REQUEST', expectedRequestVersion: item.expectedVersion };
  }
  return {
    targetType: 'SESSION',
    expectedSessionVersion: item.expectedVersion,
    expectedRequestVersions: linkedRequestVersionMap(item.linkedRequestVersions)
  };
};

/**
 * Release is offered only where the authoritative read state already allows it. The backend still
 * validates and stays the final authority: an unexpected 409 is resolved by refreshing instead of
 * retrying the same command.
 */
export const isOwnedRequestReleasable = (detail: OperationsRequestDetail | null): boolean =>
  Boolean(detail) && detail?.sessionId === null && detail?.status === 'RECEIVED';

export const isOwnedSessionReleasable = (detail: OperationsSessionDetail | null): boolean =>
  Boolean(detail)
  && detail?.status === 'DRAFT'
  && detail?.scheduledAt === null
  && detail?.representativeUserId === null
  && detail?.startedAt === null;

export const releaseCommandForRequest = (
  detail: OperationsRequestDetail
): RequestReleaseCommand | null =>
  detail.version === null
    ? null
    : { targetType: 'REQUEST', expectedRequestVersion: detail.version };

export const releaseCommandForSession = (
  detail: OperationsSessionDetail
): SessionReleaseCommand | null =>
  detail.version === null
    ? null
    : {
        targetType: 'SESSION',
        expectedSessionVersion: detail.version,
        expectedRequestVersions: linkedRequestVersionMap(detail.linkedRequestVersions)
      };

/* ------------------------------------------------------------------ *
 * Phase 2A command vocabulary — Request coordination, itinerary and availability
 * ------------------------------------------------------------------ */

/**
 * The only two item origins Operations may add. `TENANT_REQUESTED` belongs to the tenant intake
 * path, so a derived property can never claim to be a tenant request.
 */
export const OPERATIONS_ITEM_ORIGIN_OE_ADDED = 'OE_ADDED';
export const OPERATIONS_ITEM_ORIGIN_LESSOR_SUGGESTED = 'LESSOR_SUGGESTED';
export type OperationsDerivedItemOrigin =
  | typeof OPERATIONS_ITEM_ORIGIN_OE_ADDED
  | typeof OPERATIONS_ITEM_ORIGIN_LESSOR_SUGGESTED;

export interface OperationsDerivedItemOriginOption {
  value: OperationsDerivedItemOrigin;
  label: string;
  hint: string;
}

export const OPERATIONS_DERIVED_ITEM_ORIGINS: OperationsDerivedItemOriginOption[] = [
  {
    value: OPERATIONS_ITEM_ORIGIN_OE_ADDED,
    label: 'Added by Operations',
    hint: 'You found this property while coordinating the visit.'
  },
  {
    value: OPERATIONS_ITEM_ORIGIN_LESSOR_SUGGESTED,
    label: 'Suggested by the lessor',
    hint: 'The lessor of a stop on this session suggested it.'
  }
];

/** Channels Operations may record a stated window from. `WHATSAPP` is a manual entry, not an integration. */
export const OPERATIONS_AVAILABILITY_SOURCES = ['PHONE', 'WHATSAPP', 'DIRECT', 'OTHER'] as const;
export type OperationsAvailabilitySource = (typeof OPERATIONS_AVAILABILITY_SOURCES)[number];

export interface OperationsAvailabilitySourceOption {
  value: OperationsAvailabilitySource;
  label: string;
}

export const OPERATIONS_AVAILABILITY_SOURCE_OPTIONS: OperationsAvailabilitySourceOption[] = [
  { value: 'PHONE', label: 'Phone call' },
  { value: 'WHATSAPP', label: 'WhatsApp message (recorded channel)' },
  { value: 'DIRECT', label: 'In person' },
  { value: 'OTHER', label: 'Other' }
];

export const availabilitySourceLabel = (source: string | null | undefined): string | null => {
  if (!source) return null;
  return OPERATIONS_AVAILABILITY_SOURCE_OPTIONS.find((option) => option.value === source)?.label ?? source;
};

/** The backend accepts an override/removal explanation up to 500 characters. */
export const OPERATIONS_REASON_MAX = 500;
export const OPERATIONS_REMOVAL_REASON_MAX = 500;

/* ---------------- Canonical lessor contact ---------------- */

/**
 * Canonical lessor contact for one authorized operational root. Exactly four fields.
 *
 * `CONTACT_UNAVAILABLE` is the fail-closed outcome and never carries a partial value: a response that
 * claims availability without a usable name and mobile is downgraded here, so a half-populated
 * contact can never be rendered, used for a call link, or written anywhere.
 */
export interface OperationsLessorContact {
  listingId: number | null;
  name: string | null;
  mobile: string | null;
  status: 'CONTACT_AVAILABLE' | 'CONTACT_UNAVAILABLE';
}

export interface OperationsContact {
  name: string | null;
  mobile: string | null;
  status: 'CONTACT_AVAILABLE' | 'CONTACT_UNAVAILABLE';
}

export interface OperationsTenantContact extends OperationsContact {
  requestId: number | null;
  sessionId: number | null;
}

export const toLessorContact = (raw: unknown): OperationsLessorContact | null => {
  const record = asRecord(raw);
  if (!record) return null;
  const listingId = readInteger(record.listingId);
  const status = record.status === 'CONTACT_AVAILABLE' ? 'CONTACT_AVAILABLE' : 'CONTACT_UNAVAILABLE';
  if (status !== 'CONTACT_AVAILABLE') return { listingId, name: null, mobile: null, status: 'CONTACT_UNAVAILABLE' };
  const name = readText(record.name);
  const mobile = readText(record.mobile);
  // A partial contact is not contact: report the honest unavailable outcome instead of a half record.
  if (name === null || mobile === null) {
    return { listingId, name: null, mobile: null, status: 'CONTACT_UNAVAILABLE' };
  }
  return { listingId, name, mobile, status: 'CONTACT_AVAILABLE' };
};

export const toTenantContact = (raw: unknown): OperationsTenantContact | null => {
  const record = asRecord(raw);
  if (!record) return null;
  const requestId = readInteger(record.requestId);
  const sessionId = readInteger(record.sessionId);
  const status = record.status === 'CONTACT_AVAILABLE' ? 'CONTACT_AVAILABLE' : 'CONTACT_UNAVAILABLE';
  if (status !== 'CONTACT_AVAILABLE') {
    return { requestId, sessionId, name: null, mobile: null, status: 'CONTACT_UNAVAILABLE' };
  }
  const name = readText(record.name);
  const mobile = readText(record.mobile);
  if (name === null || mobile === null) {
    return { requestId, sessionId, name: null, mobile: null, status: 'CONTACT_UNAVAILABLE' };
  }
  return { requestId, sessionId, name, mobile, status: 'CONTACT_AVAILABLE' };
};

/* ---------------- Request coordination ---------------- */

export interface CoordinateNewSessionCommand {
  targetType: 'REQUEST';
  expectedRequestVersion: number;
  sessionId: null;
  expectedSessionVersion: null;
}

export interface CoordinateAttachCommand {
  targetType: 'REQUEST';
  expectedRequestVersion: number;
  sessionId: number;
  expectedSessionVersion: number;
}

/**
 * Coordinating an owned standalone Request into a brand-new DRAFT session. The server decides every
 * eligibility question (listing state, Tenant/City/Team scope); the client supplies only the versions
 * it has just re-read.
 */
export const coordinateNewSessionCommand = (
  detail: OperationsRequestDetail
): CoordinateNewSessionCommand | null =>
  detail.version === null || detail.sessionId !== null
    ? null
    : {
        targetType: 'REQUEST',
        expectedRequestVersion: detail.version,
        sessionId: null,
        expectedSessionVersion: null
      };

/**
 * Attaching an owned Request to an owned DRAFT session. Both roots are re-read immediately before the
 * command is built, and the server still decides whether they may be combined.
 */
export const coordinateAttachCommand = (
  detail: OperationsRequestDetail,
  targetSessionId: number,
  targetSessionVersion: number
): CoordinateAttachCommand | null =>
  detail.version === null || detail.sessionId !== null
    || !Number.isSafeInteger(targetSessionId) || !Number.isSafeInteger(targetSessionVersion)
    ? null
    : {
        targetType: 'REQUEST',
        expectedRequestVersion: detail.version,
        sessionId: targetSessionId,
        expectedSessionVersion: targetSessionVersion
      };

export type RequestUnavailableCommand = { targetType: 'REQUEST'; expectedRequestVersion: number };

export const requestUnavailableCommand = (
  detail: OperationsRequestDetail
): RequestUnavailableCommand | null =>
  detail.version === null || detail.sessionId !== null
    ? null
    : { targetType: 'REQUEST', expectedRequestVersion: detail.version };

/* ------------------------------------------------------------------ *
 * DRAFT itinerary management
 * ------------------------------------------------------------------ */

export const sessionItemOriginLabel = (origin: string | null | undefined): string => {
  if (origin === OPERATIONS_ITEM_ORIGIN_OE_ADDED) return 'Derived property · added by Operations';
  if (origin === OPERATIONS_ITEM_ORIGIN_LESSOR_SUGGESTED) return 'Derived property · suggested by the lessor';
  if (origin === 'TENANT_REQUESTED') return 'Requested by the tenant';
  return origin ? humanizeOperationsCode(origin) : 'Origin not recorded';
};

/** Items still on the itinerary. Removed items are retained history and are never visited. */
export const activeSessionItems = (items: OperationsSessionItem[]): OperationsSessionItem[] =>
  items.filter((item) => item.removedAt === null);

export const removedSessionItems = (items: OperationsSessionItem[]): OperationsSessionItem[] =>
  items.filter((item) => item.removedAt !== null);

/** A stop created from a linked Request rather than from Operations/lessor discovery. */
export const isDirectRequestItem = (item: OperationsSessionItem): boolean => item.sourceRequestId !== null;

/**
 * The backend refuses to remove a directly requested stop until its availability is recorded as
 * UNAVAILABLE. Stating that guard in the UI stops the OE from making a request the server must refuse.
 */
export const sessionItemRemovalBlockedReason = (item: OperationsSessionItem): string | null => {
  if (item.removedAt !== null) return 'This stop is already removed and kept as history.';
  if (isDirectRequestItem(item) && item.confirmationStatus !== 'UNAVAILABLE') {
    return 'A property the tenant requested can only be removed after its availability is recorded as unavailable.';
  }
  return null;
};

export const isSessionItemRemovable = (item: OperationsSessionItem): boolean =>
  sessionItemRemovalBlockedReason(item) === null;

export interface ReorderSessionItemsCommand {
  targetType: 'SESSION';
  expectedSessionVersion: number;
  itemIds: number[];
}

/**
 * Exact reorder payload: every active item exactly once, in the requested order. Removed items stay
 * in the session's history and are deliberately not part of this command.
 */
export const reorderSessionItemsCommand = (
  detail: OperationsSessionDetail,
  orderedItemIds: number[]
): ReorderSessionItemsCommand | null => {
  if (detail.version === null || detail.status !== 'DRAFT') return null;
  const activeIds = activeSessionItems(detail.items).map((item) => item.itemId);
  if (orderedItemIds.length !== activeIds.length) return null;
  if (new Set(orderedItemIds).size !== orderedItemIds.length) return null;
  if (!orderedItemIds.every((itemId) => activeIds.includes(itemId))) return null;
  return { targetType: 'SESSION', expectedSessionVersion: detail.version, itemIds: orderedItemIds };
};

export interface AddSessionItemCommand {
  targetType: 'SESSION';
  expectedSessionVersion: number;
  listingId: number;
  originatingRequestId: number;
  origin: OperationsDerivedItemOrigin;
}

export const addSessionItemCommand = (
  detail: OperationsSessionDetail,
  input: { listingId: number | null; originatingRequestId: number | null; origin: OperationsDerivedItemOrigin }
): AddSessionItemCommand | null => {
  if (detail.version === null || detail.status !== 'DRAFT') return null;
  if (input.listingId === null || !Number.isSafeInteger(input.listingId) || input.listingId <= 0) return null;
  if (input.originatingRequestId === null || !Number.isSafeInteger(input.originatingRequestId)
      || input.originatingRequestId <= 0) {
    return null;
  }
  return {
    targetType: 'SESSION',
    expectedSessionVersion: detail.version,
    listingId: input.listingId,
    originatingRequestId: input.originatingRequestId,
    origin: input.origin
  };
};

export interface RemoveSessionItemCommand {
  targetType: 'SESSION';
  expectedSessionVersion: number;
  itemId: number;
  reason: string | null;
}

export const removeSessionItemCommand = (
  detail: OperationsSessionDetail,
  itemId: number,
  reason: string | null
): RemoveSessionItemCommand | null => {
  if (detail.version === null || detail.status !== 'DRAFT') return null;
  const trimmed = reason === null ? null : reason.trim();
  if (trimmed !== null && trimmed.length > OPERATIONS_REMOVAL_REASON_MAX) return null;
  return {
    targetType: 'SESSION',
    expectedSessionVersion: detail.version,
    itemId,
    reason: trimmed === null || trimmed.length === 0 ? null : trimmed
  };
};

/** Requests already on this session. The backend rejects a listing that appears anywhere in its history. */
export const sessionListingIds = (items: OperationsSessionItem[]): number[] =>
  items
    .map((item) => item.listingId)
    .filter((listingId): listingId is number => listingId !== null);

/**
 * The linked Requests eligible to be named as a derived stop's origin.
 *
 * The authoritative source is the session's own linked-Request membership, not the set of *active*
 * items: removing a stop soft-deletes the item and deliberately keeps its Request linked, and the
 * backend still accepts that Request as a derived stop's origin. Deriving the choices from active
 * items alone made a normal unavailable → remove → find-a-replacement sequence impossible to finish.
 * Which Requests the server truly accepts stays its decision, so this is a candidate list.
 */
export const derivedStopOriginCandidates = (
  session: Pick<OperationsSessionDetail, 'items' | 'linkedRequestVersions'>
): LinkedRequestVersion[] => {
  const seen = new Set<number>();
  const origins: LinkedRequestVersion[] = [];
  const add = (requestId: number | null, version: number | null): void => {
    if (requestId === null || !Number.isSafeInteger(requestId) || requestId <= 0) return;
    if (seen.has(requestId)) return;
    seen.add(requestId);
    origins.push({ requestId, version: version ?? 0 });
  };
  for (const linked of session.linkedRequestVersions) add(linked.requestId, linked.version);
  for (const item of session.items) {
    add(item.sourceRequestId, null);
    add(item.derivedFromRequestId, null);
  }
  return origins;
};

/**
 * Session statuses whose active items may disclose the canonical lessor contact. It mirrors the
 * backend's revealable set exactly: contact is never offered on terminal work or for removed items.
 */
const OPERATIONS_CONTACT_SESSION_STATUSES = ['DRAFT', 'SCHEDULED', 'STARTED', 'INTERRUPTED'] as const;

export const sessionPermitsItemContact = (status: string | null | undefined): boolean =>
  typeof status === 'string' && (OPERATIONS_CONTACT_SESSION_STATUSES as readonly string[]).includes(status);

/* ------------------------------------------------------------------ *
 * Property availability
 * ------------------------------------------------------------------ */

/**
 * What the recorded availability actually supports.
 *
 *  - `pending`              — nothing has been confirmed yet, so this stop is not schedule-ready.
 *  - `confirmed-incomplete` — confirmed without a usable window. The backend allows that state on
 *                             purpose, but a visit cannot be scheduled around it.
 *  - `confirmed-window`     — a complete window: start, end, zone and source together.
 *  - `unavailable`          — the lessor confirmed the property cannot be visited. Final.
 */
export type OperationsAvailabilityView =
  | 'pending'
  | 'confirmed-incomplete'
  | 'confirmed-window'
  | 'unavailable';

export interface OperationsAvailabilityMeta {
  view: OperationsAvailabilityView;
  label: string;
  hint: string;
  /** Whether this stop's recorded facts currently satisfy the backend's scheduling window rule. */
  schedulingReady: boolean;
}

export const operationsAvailabilityMeta = (
  item: Pick<
    OperationsSessionItem,
    'confirmationStatus' | 'availabilityStartAt' | 'availabilityEndAt' | 'availabilityZoneId' | 'availabilitySource'
  >
): OperationsAvailabilityMeta => {
  if (item.confirmationStatus === 'UNAVAILABLE') {
    return {
      view: 'unavailable',
      label: 'Confirmed unavailable',
      hint: 'The lessor confirmed this property cannot be visited. This is final.',
      schedulingReady: false
    };
  }
  if (item.confirmationStatus === 'CONFIRMED') {
    const complete = Boolean(
      item.availabilityStartAt && item.availabilityEndAt && item.availabilityZoneId && item.availabilitySource
    );
    return complete
      ? {
          view: 'confirmed-window',
          label: 'Confirmed with window',
          hint: 'A complete availability window is recorded for this stop.',
          schedulingReady: true
        }
      : {
          view: 'confirmed-incomplete',
          label: 'Confirmed, window incomplete',
          hint: 'The lessor confirmed availability, but no complete window is recorded, so scheduling stays blocked.',
          schedulingReady: false
        };
  }
  return {
    view: 'pending',
    label: 'Pending confirmation',
    hint: 'No availability has been recorded for this stop yet.',
    schedulingReady: false
  };
};

export const formatAvailabilityWindow = (
  item: Pick<OperationsSessionItem, 'availabilityStartAt' | 'availabilityEndAt' | 'availabilityZoneId' | 'availabilitySource'>
): string | null => {
  const start = formatOperationsDetailDateTime(item.availabilityStartAt);
  const end = formatOperationsDetailDateTime(item.availabilityEndAt);
  if (!start || !end) return null;
  const zone = operationsZoneLabel(item.availabilityZoneId);
  const source = availabilitySourceLabel(item.availabilitySource);
  return `${start} → ${end}${zone ? ` · ${zone}` : ''}${source ? ` · ${source}` : ''}`;
};

/**
 * Why a DRAFT session cannot be scheduled yet, from the facts this client actually holds. It mirrors
 * the server's prerequisite evaluation rather than inventing an extra rule; the server still decides.
 */
export const schedulingBlockersForSession = (detail: OperationsSessionDetail | null): string[] => {
  if (!detail) return [];
  const active = activeSessionItems(detail.items);
  const blockers: string[] = [];
  if (active.length === 0) {
    blockers.push('The session has no active stops.');
    return blockers;
  }
  const pending = active.filter((item) => operationsAvailabilityMeta(item).view === 'pending');
  if (pending.length > 0) {
    blockers.push(
      `${pending.length} ${pending.length === 1 ? 'stop has' : 'stops have'} no recorded availability.`
    );
  }
  const incomplete = active.filter((item) => operationsAvailabilityMeta(item).view === 'confirmed-incomplete');
  if (incomplete.length > 0) {
    blockers.push(
      `${incomplete.length} ${incomplete.length === 1 ? 'stop is' : 'stops are'} confirmed without a complete window.`
    );
  }
  // Nothing confirmed at all: the server has no stop it can evaluate a visit time around.
  if (pending.length === 0 && incomplete.length === 0
      && active.every((item) => operationsAvailabilityMeta(item).view === 'unavailable')) {
    blockers.push('Every active stop is recorded as unavailable, so there is nothing to schedule.');
  }
  return blockers;
};

/** Active stops the lessor has confirmed unavailable. The server excludes them from a visit time. */
export const unavailableActiveStopCount = (detail: OperationsSessionDetail): number =>
  activeSessionItems(detail.items)
    .filter((item) => operationsAvailabilityMeta(item).view === 'unavailable')
    .length;

/**
 * A fresh Session read may open the scheduling drawer only while it still describes the same DRAFT
 * visit owned by the captured coordinator. Eligibility is never inferred from the pre-open snapshot.
 */
export const freshSchedulingSessionIsEligible = (
  fresh: OperationsSessionDetail | null | undefined,
  sessionId: number,
  coordinatorUserId: number
): boolean => Boolean(
  fresh
  && fresh.sessionId === sessionId
  && fresh.status === 'DRAFT'
  && fresh.coordinatorUserId === coordinatorUserId
);

export type OperationsAvailabilityChoice = 'window' | 'confirm-only' | 'unavailable';

/**
 * The availability transitions the backend supports for the item's current status:
 *   PENDING → CONFIRMED (with a window or confirmation only) and PENDING → UNAVAILABLE;
 *   CONFIRMED → CONFIRMED for a permitted window update. UNAVAILABLE is never reversible.
 */
export const availabilityChoicesFor = (item: OperationsSessionItem): OperationsAvailabilityChoice[] => {
  if (item.confirmationStatus === 'UNAVAILABLE') return [];
  if (item.confirmationStatus === 'CONFIRMED') return ['window'];
  return ['window', 'confirm-only', 'unavailable'];
};

export const availabilityChoiceLabel = (choice: OperationsAvailabilityChoice): string => {
  if (choice === 'window') return 'Available, record the window now';
  if (choice === 'confirm-only') return 'Available, window not known yet';
  return 'Unavailable';
};

/** IANA zone identifiers offered by the editor. The server validates the zone it receives. */
export const OPERATIONS_AVAILABILITY_ZONES = ['Asia/Kolkata'] as const;

/** True for an identifier `Intl` accepts as a real IANA time zone. */
export const isOperationsZone = (zoneId: string | null | undefined): boolean => {
  if (!zoneId) return false;
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: zoneId });
    return true;
  } catch {
    return false;
  }
};

/**
 * Exact mounted-instance identity for a protected root. Type separates a Request from a Session with
 * the same numeric id; the mount generation separates a re-opened instance from the one a read or
 * command captured. A null on either side never matches.
 */
export const sameOperationsRootInstance = (
  left: { targetType: string; targetId: number; mountGeneration: number } | null | undefined,
  right: { targetType: string; targetId: number; mountGeneration: number } | null | undefined
): boolean => left !== null && left !== undefined
  && right !== null && right !== undefined
  && left.targetType === right.targetType
  && left.targetId === right.targetId
  && left.mountGeneration === right.mountGeneration;

/**
 * Zone select options: the supported list, plus the item's own recorded zone when it is a real IANA
 * zone outside that list. The recorded value is never silently replaced by another zone.
 */
export const operationsZoneOptions = (recordedZone: string | null | undefined): string[] => {
  const options: string[] = [...OPERATIONS_AVAILABILITY_ZONES];
  if (isOperationsZone(recordedZone) && !options.includes(recordedZone as string)) {
    options.push(recordedZone as string);
  }
  return options;
};

const WALL_CLOCK_PATTERN = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/;

interface WallClockParts {
  year: string;
  month: string;
  day: string;
  hour: string;
  minute: string;
}

const wallClockOf = (utcMs: number, zoneId: string, withSeconds: boolean): WallClockParts | null => {
  try {
    const parts = new Intl.DateTimeFormat('en-US', {
      timeZone: zoneId,
      hour12: false,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      ...(withSeconds ? { second: '2-digit' } : {})
    }).formatToParts(new Date(utcMs));
    const read = (type: string): string => parts.find((part) => part.type === type)?.value ?? '';
    const year = read('year');
    const month = read('month');
    const day = read('day');
    const hour = read('hour');
    const minute = read('minute');
    if (!year || !month || !day || !hour || !minute) return null;
    return { year, month, day, hour, minute };
  } catch {
    return null;
  }
};

/** Minutes the zone is offset from UTC at that instant (positive east of UTC). */
const zoneOffsetMinutesAt = (utcMs: number, zoneId: string): number | null => {
  const parts = wallClockOf(utcMs, zoneId, true);
  if (!parts) return null;
  const asUtc = Date.UTC(
    Number(parts.year),
    Number(parts.month) - 1,
    Number(parts.day),
    Number(parts.hour) % 24,
    Number(parts.minute)
  );
  // The wall clock is read to the minute, so the instant is reduced to its own minute before the two
  // are compared: an offset is always a whole number of minutes, and an instant's seconds must never
  // leak into the difference and pull the reading onto the previous minute (05:29 instead of 05:30).
  return Math.round((asUtc - Math.floor(utcMs / 60_000) * 60_000) / 60_000);
};

const offsetText = (offsetMinutes: number): string => {
  const sign = offsetMinutes < 0 ? '-' : '+';
  const absolute = Math.abs(offsetMinutes);
  return `${sign}${String(Math.floor(absolute / 60)).padStart(2, '0')}:${String(absolute % 60).padStart(2, '0')}`;
};

/** The entered wall clock expressed as if it were UTC, so it can be compared with a zone's wall clock. */
const wallClockMsOf = (utcMs: number, zoneId: string): number | null => {
  const parts = wallClockOf(utcMs, zoneId, false);
  if (!parts) return null;
  return Date.UTC(
    Number(parts.year),
    Number(parts.month) - 1,
    Number(parts.day),
    Number(parts.hour) % 24,
    Number(parts.minute)
  );
};

/**
 * Every offset the zone can be using near one local time.
 *
 * A zone's offset only changes at a transition, so sampling the same wall clock at a spread of
 * nearby instants yields the offset before and after any transition without a timezone dependency.
 */
const offsetsNearWallClock = (wallMs: number, zoneId: string): number[] => {
  const found = new Set<number>();
  for (const hours of [-24, -12, -3, -2, -1, 0, 1, 2, 3, 12, 24]) {
    const offset = zoneOffsetMinutesAt(wallMs + hours * 3_600_000, zoneId);
    if (offset !== null) found.add(offset);
  }
  return [...found];
};

/**
 * The real instants whose wall clock in the zone is exactly this local time.
 *
 *  - one result  → an ordinary local time with a single meaning;
 *  - no results  → the local time does not exist (the daylight-saving gap);
 *  - two results → the local time happens twice (the repeated hour when clocks go back).
 *
 * The ambiguity is returned rather than silently resolved: choosing one occurrence would change the
 * instant the OE is looking at without being asked to.
 */
const resolveWallClockInstants = (
  localMs: number,
  zoneId: string
): number[] => {
  const found = new Set<number>();
  for (const offset of offsetsNearWallClock(localMs, zoneId)) {
    const instantMs = localMs - offset * 60_000;
    if (wallClockMsOf(instantMs, zoneId) === localMs) found.add(instantMs);
  }
  return [...found].sort((left, right) => left - right);
};

/** The wall clock of the entered local time, or null when the shape is not a `datetime-local` value. */
const wallClockMsOfInput = (value: string): number | null => {
  const match = WALL_CLOCK_PATTERN.exec(value.trim());
  if (!match) return null;
  const [, year, month, day, hour, minute] = match;
  return Date.UTC(Number(year), Number(month) - 1, Number(day), Number(hour), Number(minute));
};

/**
 * Converts a server instant into the wall clock of the requested zone, for a `datetime-local` field.
 *
 * The device time zone is never consulted: the field always shows the property's recorded zone, so an
 * instant such as `2027-02-14T04:00:00Z` displays as `2027-02-14T09:30` in `Asia/Kolkata` instead of
 * the UTC wall clock. Returns an empty string when the input is not a real instant in that zone.
 */
export const serverInstantToZoneInput = (
  value: string | null | undefined,
  zoneId: string | null | undefined
): string => {
  if (!value || !isOperationsZone(zoneId)) return '';
  const parsed = Date.parse(value);
  if (!Number.isFinite(parsed)) return '';
  const parts = wallClockOf(parsed, zoneId as string, false);
  if (!parts) return '';
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
};

/**
 * Converts an edited wall clock back into an unambiguous timestamp for the intended instant, expressed
 * as that zone's local time with its own offset (for example `2027-02-14T09:30:00+05:30`).
 *
 * The wall clock is resolved through the zone's own offsets, so:
 *  - a time that does not exist there (a daylight-saving gap) is refused;
 *  - a time that occurs twice (the repeated hour when the clocks go back) is refused rather than
 *    silently given one of its two offsets.
 *
 * A field the OE did not edit is not re-derived from its wall clock at all — see
 * {@link resolveAvailabilityFieldInstant} and {@link instantToZoneInstant}.
 */
export const zoneInputToServerInstant = (
  value: string | null | undefined,
  zoneId: string | null | undefined
): string | null => {
  const match = typeof value === 'string' ? WALL_CLOCK_PATTERN.exec(value.trim()) : null;
  if (!match || !isOperationsZone(zoneId)) return null;
  const [, year, month, day, hour, minute] = match;
  const zone = zoneId as string;
  const localMs = Date.UTC(Number(year), Number(month) - 1, Number(day), Number(hour), Number(minute));
  const instants = resolveWallClockInstants(localMs, zone);
  // Zero instants is a gap and two is a repeated hour; neither may be guessed at.
  if (instants.length !== 1) return null;
  const offset = zoneOffsetMinutesAt(instants[0], zone);
  if (offset === null) return null;
  return `${year}-${month}-${day}T${hour}:${minute}:00${offsetText(offset)}`;
};

interface CivilTime {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  second: number;
}

const padded = (value: number, width: number): string => String(value).padStart(width, '0');

/**
 * The calendar date and clock reading of one whole-second epoch, read as UTC.
 *
 * `Date`'s UTC getters are used rather than `Intl` so midnight is always `00` and the parts can never
 * roll over into another day.
 */
const civilTimeOfEpochSeconds = (epochSeconds: number): CivilTime | null => {
  const date = new Date(epochSeconds * 1000);
  if (!Number.isFinite(date.getTime())) return null;
  return {
    year: date.getUTCFullYear(),
    month: date.getUTCMonth() + 1,
    day: date.getUTCDate(),
    hour: date.getUTCHours(),
    minute: date.getUTCMinutes(),
    second: date.getUTCSeconds()
  };
};

/**
 * Re-expresses one recorded instant with the offset its zone actually used at that instant.
 *
 * This is the representation the backend contract requires: `SchedulingWindowValidator` refuses an
 * offset the declared zone was not using at the supplied local time, so an instant a response
 * delivered as UTC `Z` cannot be sent back beside `Asia/Kolkata`. The instant itself is untouched —
 * whole seconds and every fractional digit survive — and across a repeated hour the correct
 * occurrence is kept, because the offset is read at the instant rather than guessed from a wall
 * clock that cannot tell the two apart.
 *
 * Returns null when the value is not a real instant or the zone is not a real IANA zone.
 */
export const instantToZoneInstant = (
  value: string | null | undefined,
  zoneId: string | null | undefined
): string | null => {
  const parsed = parseOperationsInstant(value);
  if (!parsed || !isOperationsZone(zoneId)) return null;
  const zone = zoneId as string;
  // The offset is read at the instant itself, milliseconds included, so an instant that sits just
  // after a transition is never labelled with the offset its neighbour used.
  const offset = zoneOffsetMinutesAt(
    parsed.epochSeconds * 1000 + Number(parsed.fraction.slice(0, 3)),
    zone
  );
  if (offset === null) return null;
  const local = civilTimeOfEpochSeconds(parsed.epochSeconds + offset * 60);
  if (!local) return null;
  // Trailing zeros carry no meaning, so `.120000000` is written `.12` and an exact second is written
  // without a fraction. The instant is identical either way.
  const fraction = parsed.fraction.replace(/0+$/, '');
  const seconds = `${padded(local.hour, 2)}:${padded(local.minute, 2)}:${padded(local.second, 2)}`;
  return `${padded(local.year, 4)}-${padded(local.month, 2)}-${padded(local.day, 2)}T${seconds}`
    + `${fraction.length > 0 ? `.${fraction}` : ''}${offsetText(offset)}`;
};

/** Why an entered availability wall clock could not be turned into exactly one instant. */
export type AvailabilityFieldInstantError = 'invalid' | 'gap' | 'ambiguous';

/**
 * Where one availability field's text came from.
 *
 * `RECORDED` — the field still holds the instant the server recorded, so a save must preserve that
 * exact instant and a zone change only re-states it. `EDITED` — the OE typed the text, so the text,
 * not the recorded instant, is what the field means.
 *
 * This is stated explicitly rather than inferred from the text. An edit can coincide with what the
 * recorded instant looks like in another zone, and inferring from text equality would then silently
 * discard that edit.
 */
export type AvailabilityFieldProvenance = 'RECORDED' | 'EDITED';

export interface AvailabilityFieldResolution {
  /** The exact instant to transmit, or null while the field is blank or cannot be resolved. */
  value: string | null;
  /** `null` while the value is usable (or still blank); the reason it is refused otherwise. */
  error: AvailabilityFieldInstantError | null;
}

/**
 * Resolves one availability field for the command body, preserving the recorded instant exactly.
 *
 * The editor shows the property zone's wall clock, which loses seconds, fractional seconds and the
 * identity of a repeated hour. Re-deriving an untouched field from that wall clock would therefore
 * change the persisted instant — silently moving a window by an hour across a DST transition, or
 * truncating seconds off every save. So a field that still displays the recorded instant is sent back
 * as that same instant written in the selected zone's own offset ({@link instantToZoneInstant}):
 * the instant and its precision survive, the offset satisfies the backend contract, and changing only
 * the zone re-labels the instant instead of moving it. A genuine edit is converted in the zone, and an
 * edit that cannot be resolved to exactly one instant is refused with its own reason instead of being
 * shifted onto an offset the OE never chose.
 *
 * Which of those two applies comes from the caller's explicit `provenance`, never from comparing the
 * text with the recorded instant's display. Every provenance other than `RECORDED` is resolved as an
 * edit, so a caller that omits or mistypes it can never silently resurrect a recorded instant over
 * what the OE typed.
 */
export const resolveAvailabilityFieldInstant = (input: {
  /** What the editor currently holds for this field. */
  value: string | null | undefined;
  /** Whether the OE edited this field, or it still holds the recorded instant. */
  provenance: AvailabilityFieldProvenance;
  /** The zone currently selected in the editor. */
  zoneId: string | null | undefined;
  /** The exact server instant behind the field's opening text. */
  initialInstant: string | null | undefined;
}): AvailabilityFieldResolution => {
  const raw = typeof input.value === 'string' ? input.value.trim() : '';
  if (raw.length === 0) return { value: null, error: null };
  if (!isOperationsZone(input.zoneId)) return { value: null, error: 'invalid' };
  const zone = input.zoneId as string;
  if (input.provenance === 'RECORDED') {
    const rezoned = instantToZoneInstant(input.initialInstant, zone);
    // The recorded instant is re-labelled, never reinterpreted from its wall clock. A field still
    // marked as holding a recorded instant but left without a readable one is reported as such rather
    // than guessed from whatever text it happens to be showing.
    return rezoned !== null ? { value: rezoned, error: null } : { value: null, error: 'invalid' };
  }

  const match = WALL_CLOCK_PATTERN.exec(raw);
  const localMs = wallClockMsOfInput(raw);
  if (!match || localMs === null) return { value: null, error: 'invalid' };
  const instants = resolveWallClockInstants(localMs, zone);
  if (instants.length === 0) return { value: null, error: 'gap' };
  if (instants.length > 1) return { value: null, error: 'ambiguous' };
  const offset = zoneOffsetMinutesAt(instants[0], zone);
  if (offset === null) return { value: null, error: 'invalid' };
  const [, year, month, day, hour, minute] = match;
  return {
    value: `${year}-${month}-${day}T${hour}:${minute}:00${offsetText(offset)}`,
    error: null
  };
};

/**
 * Re-expresses one availability field's text after the time-zone selector changes.
 *
 * A field still holding the recorded instant keeps that instant: its text is rewritten into the newly
 * selected zone, so the OE reads the same moment stated in the new zone and the save preserves the
 * exact recorded instant. Text the OE typed is returned exactly as typed, whatever it looks like in
 * either zone — choosing a zone states which zone that wall clock belongs to, and the edit is what
 * decides the instant.
 */
export const rezoneAvailabilityFieldInput = (input: {
  /** What the editor currently holds for this field. */
  value: string | null | undefined;
  /** Whether the OE edited this field, or it still holds the recorded instant. */
  provenance: AvailabilityFieldProvenance;
  /** The exact server instant behind the field's opening text. */
  initialInstant: string | null | undefined;
  /** The zone the OE just selected. */
  nextZoneId: string;
}): string | null => {
  const raw = typeof input.value === 'string' ? input.value : '';
  if (raw.trim().length === 0) return raw;
  if (input.provenance !== 'RECORDED') return raw;
  const rezoned = serverInstantToZoneInput(input.initialInstant, input.nextZoneId);
  return rezoned.length > 0 ? rezoned : raw;
};

export interface AvailabilityWindowInput {
  /** ISO-8601 instant including its offset, exactly as the OE entered it. */
  availabilityStartAt: string | null;
  availabilityEndAt: string | null;
  availabilityZoneId: string | null;
  availabilitySource: OperationsAvailabilitySource | null;
}

/** Which field still carries the recorded instant and which one the OE typed. */
export interface AvailabilityFieldProvenanceState {
  start: AvailabilityFieldProvenance;
  end: AvailabilityFieldProvenance;
}

/**
 * The one immutable command payload this editor actually transmitted.
 *
 * It is captured at the dispatch boundary, not from the mutable editor fields: while an outcome is
 * unknown the OE may keep editing, and reconciliation still has to answer the question about the
 * original command rather than about what they have typed since.
 */
export interface OperationsAvailabilityAttempt {
  choice: OperationsAvailabilityChoice;
  itemId: number;
  expectedSessionVersion: number;
  window: AvailabilityWindowInput;
}

/**
 * The one in-memory availability draft the Operations workspace can hold.
 *
 * It sits above the refresh-sensitive detail rendering boundary because a detail refresh, a queue
 * reconciliation or a failed save must never destroy what the OE typed. The record is deliberately
 * self-contained: it keeps the recorded values the editor opened with, so an authoritative refresh
 * can never silently rebase a `RECORDED` field against a newer server value, and it carries the
 * identity/context it belongs to so no completion can restore it into another editing session.
 */
export interface OperationsAvailabilityDraft {
  /** Non-secret fingerprint of the authenticated identity that opened the editor. */
  identity: string;
  /** Authorization generation in force when the editor opened. */
  authorizationGeneration: number;
  sessionId: number;
  itemId: number;
  /** Unique generation of this editor instance; asynchronous completions are matched against it. */
  editorGeneration: number;
  /** Session version the draft was opened against, for truthful stale-context reporting. */
  openingSessionVersion: number | null;
  /** The recorded availability exactly as the editor opened with it. */
  openingStartAt: string | null;
  openingEndAt: string | null;
  openingZoneId: string | null;
  openingSource: string | null;
  /** The user's own selections and raw field text. */
  choice: OperationsAvailabilityChoice;
  window: AvailabilityWindowInput;
  fieldProvenance: AvailabilityFieldProvenanceState;
  /** The original transmitted intent; only dispatch writes it, and edits never rewrite it. */
  attempt: OperationsAvailabilityAttempt | null;
  /**
   * `editing` — ordinary, save enabled.
   * `saving` — the guarded command owns the submission lock.
   * `reconciling` — a read is establishing whether an unconfirmed command was applied.
   * `refused` — a definitive refusal or pre-dispatch failure kept the draft recoverable.
   * `uncertain` — transmitted without a confirmed outcome; the draft is kept, never auto-resent.
   */
  status: 'editing' | 'saving' | 'reconciling' | 'refused' | 'uncertain';
  /** Truthful inline explanation of the last save outcome, or null. */
  failureMessage: string | null;
}

/** The user-editable portion of a draft; context fields are owned by the workspace. */
export interface OperationsAvailabilityDraftValues {
  choice: OperationsAvailabilityChoice;
  window: AvailabilityWindowInput;
  fieldProvenance: AvailabilityFieldProvenanceState;
}

/**
 * The meaningful completion of one availability save, carried from the workspace through the detail
 * and itinerary so the editor is only closed when the server's outcome is actually known.
 */
export type OperationsAvailabilitySaveOutcome =
  | { status: 'saved'; message: string | null }
  | { status: 'not-saved'; message: string }
  | { status: 'unconfirmed'; message: string }
  | { status: 'superseded'; message?: string };

/** Builds the one bounded draft for an editor that is being opened. */
export const createAvailabilityDraft = (input: {
  sessionId: number;
  sessionVersion: number | null;
  item: OperationsSessionItem;
  identity: string;
  authorizationGeneration: number;
  editorGeneration: number;
}): OperationsAvailabilityDraft => {
  const zoneId = input.item.availabilityZoneId ?? OPERATIONS_AVAILABILITY_ZONES[0];
  return {
    identity: input.identity,
    authorizationGeneration: input.authorizationGeneration,
    sessionId: input.sessionId,
    itemId: input.item.itemId,
    editorGeneration: input.editorGeneration,
    openingSessionVersion: input.sessionVersion,
    openingStartAt: input.item.availabilityStartAt,
    openingEndAt: input.item.availabilityEndAt,
    openingZoneId: input.item.availabilityZoneId,
    openingSource: input.item.availabilitySource,
    choice: availabilityChoicesFor(input.item)[0] ?? 'window',
    window: {
      availabilityStartAt: serverInstantToZoneInput(input.item.availabilityStartAt, zoneId),
      availabilityEndAt: serverInstantToZoneInput(input.item.availabilityEndAt, zoneId),
      availabilityZoneId: zoneId,
      availabilitySource: (input.item.availabilitySource as OperationsAvailabilitySource | null) ?? null
    },
    fieldProvenance: { start: 'RECORDED', end: 'RECORDED' },
    attempt: null,
    status: 'editing',
    failureMessage: null
  };
};

/**
 * True when the fields the editor opened with still describe the authoritative item. A changed
 * recorded instant, zone or source is surfaced to the OE; it is never silently folded into a
 * `RECORDED` field, which would reinterpret the old value against new server props.
 */
export const availabilityRecordedConflict = (
  draft: OperationsAvailabilityDraft,
  item: OperationsSessionItem
): boolean => {
  const instantChanged = (opening: string | null, current: string | null): boolean => {
    if (opening === null && current === null) return false;
    if (opening === null || current === null) return true;
    const compared = compareOperationsInstants(opening, current);
    return compared === null ? opening !== current : compared !== 0;
  };
  return instantChanged(draft.openingStartAt, item.availabilityStartAt)
    || instantChanged(draft.openingEndAt, item.availabilityEndAt)
    || (draft.openingZoneId ?? null) !== (item.availabilityZoneId ?? null)
    || (draft.openingSource ?? null) !== (item.availabilitySource ?? null);
};

/**
 * PostgreSQL stores `TIMESTAMP WITH TIME ZONE` at microsecond precision. A nine-digit input may be
 * returned exactly by a Java response, or rounded by the JDBC/PostgreSQL persistence path. This
 * computes only that defined rounded representation; it is not a tolerance and never conflates two
 * distinguishable microseconds.
 */
export const roundOperationsInstantToPostgresMicroseconds = (
  value: string | null | undefined
): OperationsInstant | null => {
  const parsed = parseOperationsInstant(value);
  if (!parsed) return null;
  let epochSeconds = parsed.epochSeconds;
  let microseconds = Number(parsed.fraction.slice(0, 6));
  // The seventh digit decides a half-up rounding to the persisted microsecond.
  if (parsed.fraction[6] >= '5') {
    microseconds += 1;
    if (microseconds === 1_000_000) {
      microseconds = 0;
      epochSeconds += 1;
    }
  }
  return {
    epochSeconds,
    fraction: String(microseconds).padStart(6, '0').padEnd(FRACTION_DIGITS, '0')
  };
};

/** True for the exact submitted instant or its defined PostgreSQL-microsecond representation. */
export const availabilityInstantMatchesPersisted = (
  actual: string | null | undefined,
  intended: string | null | undefined
): boolean => {
  const parsedActual = parseOperationsInstant(actual);
  const parsedIntended = parseOperationsInstant(intended);
  if (!parsedActual || !parsedIntended) return false;
  if (compareInstants(parsedActual, parsedIntended) === 0) return true;
  const persisted = roundOperationsInstantToPostgresMicroseconds(intended);
  return persisted !== null && compareInstants(parsedActual, persisted) === 0;
};

/**
 * Whether the authoritative item already shows exactly what one save intended. This is evidence that
 * the current desired state is satisfied; it is not proof of which actor created it.
 */
export const availabilityItemSatisfiesDraft = (
  item: OperationsSessionItem,
  choice: OperationsAvailabilityChoice,
  window: AvailabilityWindowInput
): boolean => {
  if (choice === 'unavailable') return item.confirmationStatus === 'UNAVAILABLE';
  if (choice === 'confirm-only') {
    return item.confirmationStatus === 'CONFIRMED'
      && item.availabilityStartAt === null
      && item.availabilityEndAt === null
      && item.availabilityZoneId === null
      && item.availabilitySource === null;
  }
  return item.confirmationStatus === 'CONFIRMED'
    && availabilityInstantMatchesPersisted(item.availabilityStartAt, window.availabilityStartAt)
    && availabilityInstantMatchesPersisted(item.availabilityEndAt, window.availabilityEndAt)
    && item.availabilityZoneId === window.availabilityZoneId
    && item.availabilitySource === window.availabilitySource;
};

/** True when the mutable editor still asks for exactly the intent captured at dispatch. */
export const availabilityIntentEqualsAttempt = (
  choice: OperationsAvailabilityChoice,
  window: AvailabilityWindowInput,
  attempt: OperationsAvailabilityAttempt
): boolean => {
  if (choice !== attempt.choice) return false;
  if (choice !== 'window') return true;
  return operationsInstantsEqual(window.availabilityStartAt, attempt.window.availabilityStartAt)
    && operationsInstantsEqual(window.availabilityEndAt, attempt.window.availabilityEndAt)
    && window.availabilityZoneId === attempt.window.availabilityZoneId
    && window.availabilitySource === attempt.window.availabilitySource;
};

/** The authoritative availability facts a preflight must present before an overwrite is allowed. */
export interface AvailabilityEvidenceSnapshot {
  confirmationStatus: string | null;
  availabilityStartAt: string | null;
  availabilityEndAt: string | null;
  availabilityZoneId: string | null;
  availabilitySource: string | null;
}

export const availabilityEvidenceSnapshot = (
  item: OperationsSessionItem
): AvailabilityEvidenceSnapshot => ({
  confirmationStatus: item.confirmationStatus,
  availabilityStartAt: item.availabilityStartAt,
  availabilityEndAt: item.availabilityEndAt,
  availabilityZoneId: item.availabilityZoneId,
  availabilitySource: item.availabilitySource
});

const availabilityEvidenceInstantsDiffer = (
  left: string | null,
  right: string | null
): boolean => {
  if (left === right) return false;
  const compared = compareOperationsInstants(left, right);
  return compared === null ? true : compared !== 0;
};

/**
 * Whether the availability facts the OE was looking at differ from the authoritative preflight read.
 * An unrelated Session version change alone is deliberately not a conflict.
 */
export const availabilityEvidenceChanged = (
  presented: AvailabilityEvidenceSnapshot,
  fresh: OperationsSessionItem
): boolean => {
  const current = availabilityEvidenceSnapshot(fresh);
  return presented.confirmationStatus !== current.confirmationStatus
    || availabilityEvidenceInstantsDiffer(presented.availabilityStartAt, current.availabilityStartAt)
    || availabilityEvidenceInstantsDiffer(presented.availabilityEndAt, current.availabilityEndAt)
    || presented.availabilityZoneId !== current.availabilityZoneId
    || presented.availabilitySource !== current.availabilitySource;
};

export interface SetAvailabilityCommand {
  targetType: 'SESSION';
  expectedSessionVersion: number;
  itemId: number;
  status: 'CONFIRMED' | 'UNAVAILABLE';
  availabilityStartAt: string | null;
  availabilityEndAt: string | null;
  availabilityZoneId: string | null;
  availabilitySource: OperationsAvailabilitySource | null;
}

export interface SetAvailabilityValidation {
  /** Field-keyed messages for the editor. */
  errors: Partial<Record<'start' | 'end' | 'zone' | 'source', string>>;
  /** The exact command body, or null while the input is incomplete or unsupported. */
  command: SetAvailabilityCommand | null;
}

/**
 * A strict ISO-8601 instant: calendar date, wall clock, optional whole seconds with up to nine
 * fractional digits, and a mandatory UTC/offset designator.
 */
const INSTANT_PATTERN =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|[+-]\d{2}:\d{2})$/;

const DAYS_PER_MONTH = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
/** Fractional seconds are compared as exactly this many digits, so a nanosecond difference survives. */
const FRACTION_DIGITS = 9;
/** Real offsets never exceed ±18:00, and only 18:00 itself may carry a zero minute. */
const MAX_OFFSET_HOURS = 18;

/** Gregorian leap-year rule, rather than `Date`'s silent roll-over of an impossible day. */
const isLeapYear = (year: number): boolean =>
  year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);

const daysInMonth = (year: number, month: number): number =>
  month === 2 && isLeapYear(year) ? 29 : DAYS_PER_MONTH[month - 1];

/**
 * Whole seconds from the Unix epoch for a validated wall clock.
 *
 * `setUTCFullYear` is used deliberately: the two-digit years `Date.UTC` remaps (0–99) would otherwise
 * silently shift an instant by 1900 years, and every part is set explicitly so no roll-over can occur.
 */
const wallClockEpochSeconds = (
  year: number,
  month: number,
  day: number,
  hour: number,
  minute: number,
  second: number
): number => {
  const date = new Date(0);
  date.setUTCFullYear(year, month - 1, day);
  date.setUTCHours(hour, minute, second, 0);
  return Math.floor(date.getTime() / 1000);
};

/** Seconds east of UTC for an instant's designator, or null when it is not a real offset. */
const offsetSecondsOf = (designator: string): number | null => {
  if (designator === 'Z') return 0;
  const hours = Number(designator.slice(1, 3));
  const minutes = Number(designator.slice(4, 6));
  if (hours > MAX_OFFSET_HOURS || minutes > 59) return null;
  if (hours === MAX_OFFSET_HOURS && minutes !== 0) return null;
  const magnitude = hours * 3600 + minutes * 60;
  return designator.startsWith('-') ? -magnitude : magnitude;
};

/**
 * One validated instant, split so equality never depends on JavaScript's own date handling.
 *
 * `Date.parse` normalises an impossible calendar date into a real one and keeps only milliseconds, so
 * it cannot be the authority for the backend's `scheduledAt` contract: `2027-02-30T05:00:00Z` would
 * be accepted, and two instants one nanosecond apart would compare equal.
 */
export interface OperationsInstant {
  /** Whole seconds from the Unix epoch, exact for the parsed calendar date and offset. */
  epochSeconds: number;
  /** Fractional second as exactly nine digits, so nanosecond differences are preserved. */
  fraction: string;
}

/**
 * Parses a strict ISO-8601 instant, or returns null.
 *
 * The grammar alone is not enough. An impossible calendar date (February 30, February 29 outside a
 * leap year), an out-of-range clock reading (hour 24, minute 60, second 60) and an impossible offset
 * (beyond ±18:00) are all refused here instead of being normalised into a different instant.
 */
export const parseOperationsInstant = (value: string | null | undefined): OperationsInstant | null => {
  if (typeof value !== 'string') return null;
  const match = INSTANT_PATTERN.exec(value.trim());
  if (!match) return null;
  const [, yearText, monthText, dayText, hourText, minuteText, secondText, fractionText, designator] = match;
  const year = Number(yearText);
  const month = Number(monthText);
  const day = Number(dayText);
  const hour = Number(hourText);
  const minute = Number(minuteText);
  // A missing seconds field means exactly zero seconds, never an unknown value.
  const second = secondText === undefined ? 0 : Number(secondText);
  if (month < 1 || month > 12) return null;
  if (day < 1 || day > daysInMonth(year, month)) return null;
  if (hour > 23 || minute > 59 || second > 59) return null;
  const offsetSeconds = offsetSecondsOf(designator);
  if (offsetSeconds === null) return null;
  return {
    epochSeconds: wallClockEpochSeconds(year, month, day, hour, minute, second) - offsetSeconds,
    fraction: (fractionText ?? '').padEnd(FRACTION_DIGITS, '0')
  };
};

/** An instant for an exact epoch-millisecond value, used to compare an instant against the clock. */
const instantFromEpochMs = (epochMs: number): OperationsInstant => {
  const epochSeconds = Math.floor(epochMs / 1000);
  return {
    epochSeconds,
    fraction: String(epochMs - epochSeconds * 1000).padStart(3, '0').padEnd(FRACTION_DIGITS, '0')
  };
};

/** Three-way comparison of two validated instants, exact to the second and to the nanosecond. */
const compareInstants = (left: OperationsInstant, right: OperationsInstant): number => {
  if (left.epochSeconds !== right.epochSeconds) return left.epochSeconds < right.epochSeconds ? -1 : 1;
  if (left.fraction === right.fraction) return 0;
  return left.fraction < right.fraction ? -1 : 1;
};

/** True only for a real instant: a valid calendar date, a valid clock reading and a real offset. */
export const isOperationsInstant = (value: string | null | undefined): boolean =>
  parseOperationsInstant(value) !== null;

/**
 * Three-way comparison of two instant values, or null when either side is not a usable instant.
 *
 * Equivalent instants written with different valid offsets compare equal, because the offset is
 * applied before comparison; an instant that is not usable is never silently ordered against one
 * that is.
 */
export const compareOperationsInstants = (
  left: string | null | undefined,
  right: string | null | undefined
): number | null => {
  const parsedLeft = parseOperationsInstant(left);
  const parsedRight = parseOperationsInstant(right);
  if (!parsedLeft || !parsedRight) return null;
  return compareInstants(parsedLeft, parsedRight);
};

/**
 * True only when both values are usable instants denoting the same point in time — the same whole
 * second and the same nine fractional digits. This is the equality the booking contract needs: a
 * one-nanosecond difference is a different instant and is refused.
 */
export const operationsInstantsEqual = (
  left: string | null | undefined,
  right: string | null | undefined
): boolean => compareOperationsInstants(left, right) === 0;

/**
 * Builds the exact availability command and the field errors to show.
 *
 * A window is sent whole or not at all: the backend rejects a partial window, and a CONFIRMED stop
 * cannot carry timestamps without a source. `UNAVAILABLE` deliberately sends no window fields.
 * A newly recorded window must be in the future and must not be reversed.
 */
export const setAvailabilityCommand = (
  detail: OperationsSessionDetail,
  itemId: number,
  choice: OperationsAvailabilityChoice,
  window: AvailabilityWindowInput,
  now: Date = new Date()
): SetAvailabilityValidation => {
  const errors: SetAvailabilityValidation['errors'] = {};
  if (detail.version === null
    || !Number.isSafeInteger(detail.version)
    || detail.version < 0
    || detail.status !== 'DRAFT') {
    return { errors, command: null };
  }
  const base = detail.version;
  const none = {
    availabilityStartAt: null,
    availabilityEndAt: null,
    availabilityZoneId: null,
    availabilitySource: null
  } as const;

  if (choice === 'unavailable') {
    return {
      errors,
      command: { targetType: 'SESSION', expectedSessionVersion: base, itemId, status: 'UNAVAILABLE', ...none }
    };
  }
  if (choice === 'confirm-only') {
    return {
      errors,
      command: { targetType: 'SESSION', expectedSessionVersion: base, itemId, status: 'CONFIRMED', ...none }
    };
  }

  const start = window.availabilityStartAt;
  const end = window.availabilityEndAt;
  if (!isOperationsInstant(start)) errors.start = 'Add the start of the window.';
  if (!isOperationsInstant(end)) errors.end = 'Add the end of the window.';
  if (!window.availabilityZoneId) errors.zone = 'Choose the time zone the lessor stated.';
  if (!window.availabilitySource) errors.source = 'Choose how the lessor told you.';
  if (!errors.start && !errors.end) {
    // Both instants were validated above, so the ordering is decided on the exact parsed values: a
    // window that ends in the same second but a fraction earlier is still a reversed window.
    const startInstant = parseOperationsInstant(start) as OperationsInstant;
    const endInstant = parseOperationsInstant(end) as OperationsInstant;
    if (compareInstants(endInstant, startInstant) <= 0) errors.end = 'The end must be after the start.';
    else if (compareInstants(startInstant, instantFromEpochMs(now.getTime())) <= 0) {
      errors.start = 'A new window must start in the future.';
    }
  }
  if (Object.keys(errors).length > 0) return { errors, command: null };
  return {
    errors,
    command: {
      targetType: 'SESSION',
      expectedSessionVersion: base,
      itemId,
      status: 'CONFIRMED',
      availabilityStartAt: (start as string).trim(),
      availabilityEndAt: (end as string).trim(),
      availabilityZoneId: window.availabilityZoneId,
      availabilitySource: window.availabilitySource
    }
  };
};

/* ------------------------------------------------------------------ *
 * Request-side availability and contact facts
 * ------------------------------------------------------------------ */

export const requestAvailabilityWindowLabel = (detail: OperationsRequestDetail): string | null => {
  const start = formatOperationsDetailDateTime(detail.availabilityStartAt);
  const end = formatOperationsDetailDateTime(detail.availabilityEndAt);
  if (start && end) return `${start} → ${end}`;
  if (start) return `From ${start}`;
  if (end) return `Until ${end}`;
  return null;
};

/** The tenant's stated window facts the backend needs before a session can be evaluated for scheduling. */
export const requestHasCompleteWindow = (detail: OperationsRequestDetail): boolean =>
  Boolean(detail.availabilityStartAt && detail.availabilityEndAt && detail.availabilityZoneId);

/* ------------------------------------------------------------------ *
 * GE recommendations and cursor-based discovery
 * ------------------------------------------------------------------ */

/** One evaluated scheduling option for one eligible Ground Executive. Exactly one time per candidate. */
export interface OperationsRecommendationCandidate {
  groundExecutiveUserId: number;
  groundExecutiveName: string | null;
  scheduledAt: string | null;
  reservedEndAt: string | null;
  durationMinutes: number | null;
  zoneId: string | null;
  /** 0 means the candidate is unranked (progressive discovery), never "lowest quality". */
  rank: number;
  feasibilityStatus: string | null;
  originSource: string | null;
  locationAssessment: string | null;
  travelConfidence: string | null;
}

export interface OperationsRecommendationView {
  sessionId: number | null;
  sessionVersion: number | null;
  status: string | null;
  generatedAt: string | null;
  policyVersion: string | null;
  candidateSearchTruncated: boolean;
  diagnostics: string[];
  candidates: OperationsRecommendationCandidate[];
  /** `RECOMMENDATIONS` for the bounded ranked read, `DISCOVERY` for cursor paging. */
  mode: string | null;
  nextCursor: string | null;
  pageSize: number | null;
  scannedCandidateCount: number | null;
}

const toRecommendationCandidate = (raw: unknown): OperationsRecommendationCandidate | null => {
  const record = asRecord(raw);
  const groundExecutiveUserId = readInteger(record?.groundExecutiveUserId);
  if (!record || groundExecutiveUserId === null) return null;
  return {
    groundExecutiveUserId,
    groundExecutiveName: readText(record.groundExecutiveName),
    scheduledAt: readText(record.scheduledAt),
    reservedEndAt: readText(record.reservedEndAt),
    durationMinutes: readInteger(record.durationMinutes),
    zoneId: readText(record.zoneId),
    rank: readInteger(record.rank) ?? 0,
    feasibilityStatus: readText(record.feasibilityStatus),
    originSource: readText(record.originSource),
    locationAssessment: readText(record.locationAssessment),
    travelConfidence: readText(record.travelConfidence)
  };
};

export const toRecommendationView = (raw: unknown): OperationsRecommendationView | null => {
  const record = asRecord(raw);
  if (!record) return null;
  const candidates = Array.isArray(record.candidates) ? record.candidates : [];
  return {
    sessionId: readInteger(record.sessionId),
    sessionVersion: readInteger(record.sessionVersion),
    status: readText(record.status),
    generatedAt: readText(record.recommendationGeneratedAt),
    policyVersion: readText(record.policyVersion),
    candidateSearchTruncated: record.candidateSearchTruncated === true,
    diagnostics: Array.isArray(record.diagnostics)
      ? record.diagnostics.filter((entry): entry is string => typeof entry === 'string')
      : [],
    candidates: candidates
      .map(toRecommendationCandidate)
      .filter((entry): entry is OperationsRecommendationCandidate => entry !== null),
    mode: readText(record.mode),
    nextCursor: readText(record.nextCursor),
    pageSize: readInteger(record.pageSize),
    scannedCandidateCount: readInteger(record.scannedCandidateCount)
  };
};

/** The bounded ranked read returns at most a small number of candidates. */
export const RANKED_RECOMMENDATION_BOUND = 3;

/**
 * True when a recommendation/discovery answer really belongs to the Session and version it was asked
 * for. The server evaluates one exact version, so an answer for another version describes a different
 * itinerary: its candidates may not be selected, and it must not replace a truthful local state.
 */
export const recommendationViewMatchesContext = (
  view: OperationsRecommendationView | null,
  sessionId: number,
  sessionVersion: number
): boolean => view !== null
  && view.sessionId === sessionId
  && view.sessionVersion === sessionVersion;

/**
 * Authoritative booking result. The returned Session is the truth about whether the visit is now
 * SCHEDULED, its version, the assigned Ground Executive and the tenant-confirmation state; the
 * decision fields are the server's record of what it compared the choice against.
 */
export interface OperationsSchedulingApproval {
  session: OperationsSessionDetail;
  decisionId: number | null;
  override: boolean;
  topRecommendedGroundExecutiveUserId: number | null;
  topRecommendedAt: string | null;
}

/**
 * The exact booking that was submitted. The returned body is only usable as proof of *this* booking.
 */
export interface SchedulingApprovalExpectation {
  sessionId: number;
  /** The Session version this command was built against. */
  expectedSessionVersion: number;
  groundExecutiveUserId: number;
  scheduledAt: string;
}

const isPositiveSafeInteger = (value: number | null | undefined): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value > 0;

/**
 * Maps a booking response, or refuses it.
 *
 * A `200` only proves a booking when it carries the complete authoritative Session for the session
 * that was booked, and — when the submitted selection is supplied — when that Session really is the
 * result of *this* selection: the same Session root, a version that advanced past the one the command
 * was built against, the selected Ground Executive actually assigned and the submitted instant
 * actually recorded. A non-null-but-unusable body (invalid timestamp text, a zero/negative GE id, a
 * stale or mismatched answer) is refused here, so the caller classifies the transmitted command as
 * unconfirmed instead of announcing a booking that cannot be displayed. Nothing here invents a
 * version increment: any strictly newer version satisfies the version rule.
 */
export const toSchedulingApproval = (
  raw: unknown,
  expected?: number | SchedulingApprovalExpectation | null
): OperationsSchedulingApproval | null => {
  const record = asRecord(raw);
  if (!record) return null;
  const session = toSessionDetail(record.session);
  if (!session) return null;
  if (!isPositiveSafeInteger(session.sessionId)) return null;
  if (session.status !== 'SCHEDULED') return null;
  const version = session.version;
  if (!isPositiveSafeInteger(version)) return null;
  // A booked Session must carry a real, unambiguous instant, not merely a non-empty string.
  if (!isOperationsInstant(session.scheduledAt)) return null;
  const representativeUserId = session.representativeUserId;
  if (!isPositiveSafeInteger(representativeUserId)) return null;
  if (typeof expected === 'number') {
    if (session.sessionId !== expected) return null;
  } else if (expected) {
    if (!isPositiveSafeInteger(expected.sessionId) || session.sessionId !== expected.sessionId) return null;
    if (version <= expected.expectedSessionVersion) return null;
    if (!isPositiveSafeInteger(expected.groundExecutiveUserId)
        || representativeUserId !== expected.groundExecutiveUserId) {
      return null;
    }
    /**
     * The recorded instant must be exactly the one that was submitted — the same second and the same
     * nanosecond. Both sides are parsed strictly and compared through their offsets, so an impossible
     * calendar date is refused rather than normalised, two equivalent offsets are accepted as the same
     * instant, and a single-digit fractional difference is a different booking.
     */
    if (!operationsInstantsEqual(expected.scheduledAt, session.scheduledAt)) return null;
  }
  return {
    session,
    decisionId: readInteger(record.decisionId),
    override: record.override === true,
    topRecommendedGroundExecutiveUserId: readInteger(record.topRecommendedGroundExecutiveUserId),
    topRecommendedAt: readText(record.topRecommendedAt)
  };
};

/** Human-readable explainer for a recommendation status the server returned. */
export const recommendationStatusHint = (status: string | null | undefined): string | null => {
  switch (status) {
    case 'SESSION_NOT_SCHEDULABLE':
      return 'This visit plan cannot be evaluated yet. Every active stop needs a complete confirmed window, and the plan must still be a draft.';
    case 'INSUFFICIENT_TENANT_AVAILABILITY':
      return 'The tenant’s available window is missing, incomplete or invalid. Scheduling cannot be evaluated until the tenant timing is recorded.';
    case 'PROPERTY_AVAILABILITY_INCOMPLETE':
      return 'A confirmed property is missing its complete lessor-recorded availability window. This is a property-input gap, not a tenant-window gap.';
    case 'NO_ELIGIBLE_GE':
      return 'No active Ground Executive in the potential City scheduling population was returned. This does not prove that no employee exists outside that population.';
    case 'NO_FEASIBLE_TIME':
      return 'No feasible time was found inside the evaluated tenant and property windows. A bounded evaluation is not proof that no Ground Executive is available at every time.';
    case 'LOCATION_UNAVAILABLE_FALLBACK_USED':
      return 'A conservative travel fallback was used because a usable live location was unavailable. Location is advisory and never makes an otherwise valid Ground Executive ineligible.';
    case 'TRAVEL_ESTIMATE_UNCERTAIN':
      return 'The travel allowance is uncertain, so the evaluated time is a conservative planning estimate rather than a live ETA.';
    case 'RECOMMENDATION_AVAILABLE':
    case 'EVALUATED':
      return null;
    case 'RECOMMENDATION_STALE':
      return 'This evaluation is stale. Refresh the visit plan before choosing a Ground Executive.';
    case 'CONFLICT':
      return 'The server reported a scheduling conflict. Refresh the visit plan before choosing again.';
    default:
      return status
        ? 'The server returned a scheduling status this screen does not recognise. Refresh the visit plan before choosing; no availability conclusion is implied.'
        : null;
  }
};

/* ------------------------------------------------------------------ *
 * Restricted GE availability information
 * ------------------------------------------------------------------ */

export type GeAvailabilityResultCode =
  | 'FEASIBLE_OPTION_FOUND'
  | 'COVERAGE_NOT_SUITABLE'
  | 'NO_FEASIBLE_OPTION_FOUND'
  | 'PLAN_NOT_READY'
  | 'EVALUATION_INCOMPLETE';

export interface OperationsGeAvailabilityEntry {
  groundExecutiveUserId: number;
  groundExecutiveName: string | null;
  /** Allow-listed backend code, or an unrecognised code rendered with a neutral fallback. */
  resultCode: string | null;
}

export interface OperationsGeAvailabilityStop {
  position: number;
  itemId: number | null;
  listingId: number | null;
  confirmationStatus: string | null;
  availabilityStartAt: string | null;
  availabilityEndAt: string | null;
  zoneId: string | null;
  availabilitySource: string | null;
  availabilityConfirmedAt: string | null;
  offsetMinutes: number | null;
}

export interface OperationsGeAvailabilityTravelAllowance {
  fromPosition: number;
  toPosition: number;
  minutes: number;
  source: string | null;
  confidence: string | null;
}

export interface OperationsGeAvailabilityTiming {
  configuredStopDwellMinutes: number | null;
  maximumSessionDurationMinutes: number | null;
  calculatedItineraryDurationMinutes: number | null;
  durationScope: string | null;
  tenantWindow: {
    startAt: string | null;
    endAt: string | null;
    zoneId: string | null;
    preferredTimes: string[];
  } | null;
  stops: OperationsGeAvailabilityStop[];
  betweenStopTravel: OperationsGeAvailabilityTravelAllowance[];
  planningWindowBounded: boolean;
}

export interface OperationsGeAvailabilityView {
  sessionId: number | null;
  sessionVersion: number | null;
  evaluatedAt: string | null;
  schedulingPolicyVersion: string | null;
  visitPolicyVersion: number | null;
  planInputFingerprint: string | null;
  planStatus: string | null;
  planReady: boolean;
  planExplanationCodes: string[];
  timing: OperationsGeAvailabilityTiming | null;
  evaluationBounded: boolean;
  entries: OperationsGeAvailabilityEntry[];
  pageSize: number | null;
  returnedEntryCount: number | null;
  nextCursor: string | null;
}

const toGeAvailabilityStop = (raw: unknown): OperationsGeAvailabilityStop | null => {
  const record = asRecord(raw);
  const position = readInteger(record?.position);
  if (!record || position === null) return null;
  return {
    position,
    itemId: readInteger(record.itemId),
    listingId: readInteger(record.listingId),
    confirmationStatus: readText(record.confirmationStatus),
    availabilityStartAt: readText(record.availabilityStartAt),
    availabilityEndAt: readText(record.availabilityEndAt),
    zoneId: readText(record.zoneId),
    availabilitySource: readText(record.availabilitySource),
    availabilityConfirmedAt: readText(record.availabilityConfirmedAt),
    offsetMinutes: readInteger(record.offsetMinutes)
  };
};

const toGeAvailabilityTravelAllowance = (
  raw: unknown
): OperationsGeAvailabilityTravelAllowance | null => {
  const record = asRecord(raw);
  const fromPosition = readInteger(record?.fromPosition);
  const toPosition = readInteger(record?.toPosition);
  const minutes = readInteger(record?.minutes);
  if (!record || fromPosition === null || toPosition === null || minutes === null) return null;
  return {
    fromPosition,
    toPosition,
    minutes,
    source: readText(record.source),
    confidence: readText(record.confidence)
  };
};

const toGeAvailabilityTiming = (raw: unknown): OperationsGeAvailabilityTiming | null => {
  const record = asRecord(raw);
  if (!record) return null;
  const tenant = asRecord(record.tenantWindow);
  return {
    configuredStopDwellMinutes: readInteger(record.configuredStopDwellMinutes),
    maximumSessionDurationMinutes: readInteger(record.maximumSessionDurationMinutes),
    calculatedItineraryDurationMinutes: readInteger(record.calculatedItineraryDurationMinutes),
    durationScope: readText(record.durationScope),
    tenantWindow: tenant
      ? {
          startAt: readText(tenant.startAt),
          endAt: readText(tenant.endAt),
          zoneId: readText(tenant.zoneId),
          preferredTimes: Array.isArray(tenant.preferredTimes)
            ? tenant.preferredTimes.filter((value): value is string => typeof value === 'string')
            : []
        }
      : null,
    stops: (Array.isArray(record.stops) ? record.stops : [])
      .map(toGeAvailabilityStop)
      .filter((value): value is OperationsGeAvailabilityStop => value !== null)
      .sort((left, right) => left.position - right.position),
    betweenStopTravel: (Array.isArray(record.betweenStopTravel) ? record.betweenStopTravel : [])
      .map(toGeAvailabilityTravelAllowance)
      .filter((value): value is OperationsGeAvailabilityTravelAllowance => value !== null),
    planningWindowBounded: record.planningWindowBounded === true
  };
};

/**
 * Maps the dedicated availability projection. Only the fields the backend contract defines are
 * retained; any unexpected field (for example contact data) is ignored rather than rendered.
 */
export const toGeAvailabilityView = (raw: unknown): OperationsGeAvailabilityView | null => {
  const record = asRecord(raw);
  if (!record) return null;
  const entries = (Array.isArray(record.entries) ? record.entries : [])
    .map((value) => {
      const entry = asRecord(value);
      const groundExecutiveUserId = readInteger(entry?.groundExecutiveUserId);
      if (!entry || groundExecutiveUserId === null || groundExecutiveUserId <= 0) return null;
      return {
        groundExecutiveUserId,
        // The restricted availability DTO carries `displayName`; the selectable recommendation
        // contract separately uses `groundExecutiveName`. Only this mapper crosses that boundary.
        groundExecutiveName: readText(entry.displayName),
        resultCode: readText(entry.resultCode)
      } satisfies OperationsGeAvailabilityEntry;
    })
    .filter((value): value is OperationsGeAvailabilityEntry => value !== null)
    .filter((entry, index, all) =>
      all.findIndex((candidate) => candidate.groundExecutiveUserId === entry.groundExecutiveUserId) === index
    );
  return {
    sessionId: readInteger(record.sessionId),
    sessionVersion: readInteger(record.sessionVersion),
    evaluatedAt: readText(record.evaluatedAt),
    schedulingPolicyVersion: readText(record.schedulingPolicyVersion),
    visitPolicyVersion: readInteger(record.visitPolicyVersion),
    planInputFingerprint: readText(record.planInputFingerprint),
    planStatus: readText(record.planStatus),
    planReady: record.planReady === true,
    planExplanationCodes: Array.isArray(record.planExplanationCodes)
      ? record.planExplanationCodes.filter((value): value is string => typeof value === 'string')
      : [],
    timing: toGeAvailabilityTiming(record.timing),
    evaluationBounded: record.evaluationBounded === true,
    entries,
    pageSize: readInteger(record.pageSize),
    returnedEntryCount: readInteger(record.returnedEntryCount),
    nextCursor: readText(record.nextCursor)
  };
};

/** The five public outcome codes; anything else is rendered as a neutral, non-committal result. */
export const geAvailabilityResultCopy = (
  code: string | null | undefined
): { label: string; detail: string; feasible: boolean } => {
  switch (code) {
    case 'FEASIBLE_OPTION_FOUND':
      return {
        label: 'Feasible at an evaluated time',
        detail: 'The server evaluated at least one feasible time for this visit plan. This is information only: it is not a booking and does not promise the same time later.',
        feasible: true
      };
    case 'COVERAGE_NOT_SUITABLE':
      return {
        label: 'Does not cover this itinerary',
        detail: 'This City candidate does not cover every required stop in this visit plan.',
        feasible: false
      };
    case 'NO_FEASIBLE_OPTION_FOUND':
      return {
        label: 'No feasible option in this evaluation',
        detail: 'No feasible time was found for this Ground Executive in this evaluation. Private scheduling reasons are not shown.',
        feasible: false
      };
    case 'PLAN_NOT_READY':
      return {
        label: 'Visit plan not ready',
        detail: 'The visit plan inputs are incomplete or unsuitable for evaluation. Correct the plan before treating this as a Ground Executive availability result.',
        feasible: false
      };
    case 'EVALUATION_INCOMPLETE':
      return {
        label: 'Evaluation incomplete',
        detail: 'The bounded search or missing evaluation data prevents a reliable conclusion for this Ground Executive.',
        feasible: false
      };
    default:
      return {
        label: 'Availability not confirmed',
        detail: 'The server returned an outcome this screen does not recognise. Treat it as informational only and refresh before deciding.',
        feasible: false
      };
  }
};

/**
 * The evaluation a chosen candidate belongs to. Booking is refused unless the Session is still at
 * exactly this version, so a candidate reviewed at v6 can never be silently booked against a v8
 * itinerary that the OE never saw.
 */
export interface SchedulingSelectionEvaluation {
  sessionId: number;
  sessionVersion: number;
}

export interface OperationsSchedulingSelection {
  candidate: OperationsRecommendationCandidate;
  source: 'recommended' | 'browse' | 'search';
  /** Identity and version of the authoritative evaluation this candidate came from. */
  evaluated: SchedulingSelectionEvaluation;
}

/** True when a candidate's evaluation is still the session version on screen. */
export const selectionMatchesSession = (
  detail: Pick<OperationsSessionDetail, 'sessionId' | 'version'> | null,
  selection: OperationsSchedulingSelection | null
): boolean => Boolean(
  detail
  && selection
  && detail.version !== null
  && selection.evaluated.sessionId === detail.sessionId
  && selection.evaluated.sessionVersion === detail.version
);

export const selectionIsTopRecommendation = (
  view: OperationsRecommendationView | null,
  selection: OperationsSchedulingSelection | null
): boolean => {
  if (!view || !selection) return false;
  // A suggestion only exempts a reason when it was evaluated for the same Session and version the
  // candidate was chosen from; anything else is treated as a non-top choice and needs one.
  if (view.sessionId !== selection.evaluated.sessionId
      || view.sessionVersion !== selection.evaluated.sessionVersion) {
    return false;
  }
  const ranked = view.candidates
    .filter((candidate) => candidate.rank > 0)
    .sort((left, right) => left.rank - right.rank);
  const top = ranked[0];
  if (!top) return false;
  return top.groundExecutiveUserId === selection.candidate.groundExecutiveUserId
    && top.scheduledAt === selection.candidate.scheduledAt;
};

/**
 * Whether this selection owes the server a reason. The server decides the authoritative answer from
 * its own fresh baseline, so this mirrors that contract conservatively: only the exact current top
 * suggestion is sent without one, and every other selection supplies a non-blank explanation.
 */
export const selectionNeedsReason = (
  view: OperationsRecommendationView | null,
  selection: OperationsSchedulingSelection | null
): boolean => !selectionIsTopRecommendation(view, selection);

export interface SchedulingValidation {
  errors: Partial<Record<'selection' | 'reason', string>>;
  reason: string | null;
}

export const validateSchedulingReview = (
  view: OperationsRecommendationView | null,
  selection: OperationsSchedulingSelection | null,
  reason: string
): SchedulingValidation => {
  const errors: SchedulingValidation['errors'] = {};
  if (!selection) {
    errors.selection = 'Choose a Ground Executive and its evaluated time.';
    return { errors, reason: null };
  }
  if (!isOperationsInstant(selection.candidate.scheduledAt)) {
    errors.selection = 'This candidate has no usable evaluated time. Refresh the candidates and choose again.';
    return { errors, reason: null };
  }
  if (!selection.candidate.zoneId) {
    errors.selection = 'This candidate has no time zone. Refresh the candidates and choose again.';
    return { errors, reason: null };
  }
  if (selectionNeedsReason(view, selection)) {
    const trimmed = reason.trim();
    if (trimmed.length === 0) {
      errors.reason = 'Add a short reason for choosing a candidate other than the top suggestion.';
      return { errors, reason: null };
    }
    if (trimmed.length > OPERATIONS_REASON_MAX) {
      errors.reason = `Keep the reason to ${OPERATIONS_REASON_MAX} characters or fewer.`;
      return { errors, reason: null };
    }
    return { errors, reason: trimmed };
  }
  return { errors, reason: null };
};

export interface ScheduleSelectionCommand {
  targetType: 'SESSION';
  expectedSessionVersion: number;
  groundExecutiveUserId: number;
  scheduledAt: string;
  zoneId: string;
  overrideReason: string | null;
}

export const scheduleSelectionCommand = (
  detail: OperationsSessionDetail,
  selection: OperationsSchedulingSelection | null,
  view: OperationsRecommendationView | null,
  reason: string
): { command: ScheduleSelectionCommand | null; validation: SchedulingValidation } => {
  const validation = validateSchedulingReview(view, selection, reason);
  if (detail.version === null || detail.status !== 'DRAFT' || !selection) {
    return { command: null, validation };
  }
  if (!selectionMatchesSession(detail, selection)) {
    // The authoritative Session moved after this candidate was evaluated. The reviewed itinerary and
    // the evaluated slot no longer describe the same state, so no command may be built at all — the
    // OE has to reload the candidates and review the current version.
    return {
      command: null,
      validation: {
        errors: {
          ...validation.errors,
          selection: 'This session changed since these candidates were evaluated. Reload the candidates and review the current itinerary before booking.'
        },
        reason: null
      }
    };
  }
  if (Object.keys(validation.errors).length > 0) return { command: null, validation };
  return {
    validation,
    command: {
      targetType: 'SESSION',
      expectedSessionVersion: detail.version,
      groundExecutiveUserId: selection.candidate.groundExecutiveUserId,
      scheduledAt: selection.candidate.scheduledAt as string,
      zoneId: selection.candidate.zoneId as string,
      overrideReason: validation.reason
    }
  };
};

export const groundExecutiveLabel = (candidate: OperationsRecommendationCandidate): string =>
  candidate.groundExecutiveName ?? `GE user ID ${candidate.groundExecutiveUserId}`;

/**
 * The drawer state one *failed* scheduling command publishes. Kept pure so the decision is testable
 * on its own, and deliberately separate from the version-binding outcome (`review-required`), which is
 * decided before anything is transmitted.
 */
export type SchedulingFailureOutcome =
  | { kind: 'not-sent'; message: string }
  | { kind: 'denied'; message: string }
  | { kind: 'conflict'; status: number; message: string; detail: string | null }
  | { kind: 'uncertain'; message: string };

export const schedulingFailureOutcome = (failure: {
  kind: OwnershipFailureKind;
  status: number | null;
  message: string;
  detail: string | null;
}): SchedulingFailureOutcome => {
  // Nothing was transmitted, so nothing was booked.
  if (failure.kind === 'pre-dispatch') return { kind: 'not-sent', message: failure.message };
  // Target denial: the root is withdrawn by the shared failure path; the drawer only states it.
  if (failure.status === 403 || failure.status === 404) return { kind: 'denied', message: failure.message };
  if (failure.kind === 'rejected') {
    return {
      kind: 'conflict',
      status: failure.status ?? 0,
      message: failure.message,
      detail: failure.detail
    };
  }
  // Transmitted with no trustworthy answer: unknown, never replayed.
  return { kind: 'uncertain', message: failure.message };
};

/** IANA zone display, e.g. `IST (Asia/Kolkata)`. Unknown zones are shown exactly as returned. */
export const operationsZoneLabel = (zoneId: string | null | undefined): string | null => {
  if (!zoneId) return null;
  return zoneId === 'Asia/Kolkata' ? 'IST (Asia/Kolkata)' : zoneId;
};

export const slotKeyFor = (candidate: OperationsRecommendationCandidate): string =>
  `${candidate.groundExecutiveUserId}:${candidate.scheduledAt ?? ''}`;

export const formatOperationsSlotRange = (
  candidate: Pick<OperationsRecommendationCandidate, 'scheduledAt' | 'reservedEndAt'>
): string | null => {
  const start = formatOperationsTime(candidate.scheduledAt);
  const end = formatOperationsTime(candidate.reservedEndAt);
  if (!start) return null;
  return end ? `${start} to ${end}` : start;
};

/* ------------------------------------------------------------------ *
 * Status presentation
 * ------------------------------------------------------------------ */

export type OperationsStatusTone = 'open' | 'scheduled' | 'active' | 'attention' | 'closed';

export interface OperationsStatusMeta {
  label: string;
  tone: OperationsStatusTone;
  hint: string;
}

export const humanizeOperationsCode = (code: string): string =>
  code.split('_').join(' ').toLowerCase();

// Request and Session statuses are separate enums and are never folded together.
const REQUEST_STATUS: Record<string, OperationsStatusMeta> = {
  RECEIVED: { label: 'Awaiting coordination', tone: 'open', hint: 'Waiting to be coordinated' },
  COORDINATING: { label: 'In coordination', tone: 'active', hint: 'Coordination is in progress' },
  SCHEDULED: { label: 'In booked visit', tone: 'scheduled', hint: 'Part of a booked visit' },
  UNAVAILABLE: { label: 'Unavailable', tone: 'closed', hint: 'Property confirmed unavailable' },
  CANCELLED: { label: 'Cancelled', tone: 'closed', hint: 'Cancelled' }
};

const SESSION_STATUS: Record<string, OperationsStatusMeta> = {
  DRAFT: { label: 'Planning', tone: 'open', hint: 'No visit time is booked yet' },
  SCHEDULED: { label: 'Visit booked', tone: 'scheduled', hint: 'A visit time is booked' },
  STARTED: { label: 'Visit in progress', tone: 'active', hint: 'The visit has started' },
  COMPLETED: { label: 'Visit completed', tone: 'closed', hint: 'The visit finished' },
  EXPIRED: { label: 'Expired', tone: 'closed', hint: 'Expired without a visit' },
  CANCELLED: { label: 'Cancelled', tone: 'closed', hint: 'Cancelled' },
  NO_SHOW: { label: 'No-show', tone: 'closed', hint: 'A no-show was recorded' },
  PROVISIONAL_NO_SHOW: { label: 'Provisional no-show', tone: 'attention', hint: 'A provisional no-show is awaiting review' },
  REPAIR_REQUIRED: { label: 'Repair required', tone: 'attention', hint: 'This session needs repair outside this workspace' },
  INTERRUPTED: { label: 'Interrupted', tone: 'attention', hint: 'The visit was interrupted' }
};

export const operationsStatusMeta = (
  targetType: OperationsTargetType,
  status: string | null | undefined
): OperationsStatusMeta => {
  if (!status) return { label: 'Status not recorded', tone: 'open', hint: '' };
  const table = targetType === 'SESSION' ? SESSION_STATUS : REQUEST_STATUS;
  return table[status] ?? { label: humanizeOperationsCode(status), tone: 'open', hint: '' };
};

const TERMINAL_STATUS: Record<OperationsTargetType, string[]> = {
  REQUEST: ['UNAVAILABLE', 'CANCELLED'],
  SESSION: ['COMPLETED', 'EXPIRED', 'CANCELLED', 'NO_SHOW']
};

export const isTerminalOperationsStatus = (
  targetType: OperationsTargetType,
  status: string | null | undefined
): boolean => Boolean(status) && TERMINAL_STATUS[targetType].includes(status as string);

/* ------------------------------------------------------------------ *
 * Tenant confirmation — its own dimension, never folded into status
 * ------------------------------------------------------------------ */

export const TENANT_CONFIRMATION_NOT_RECORDED = 'Confirmation not recorded';

export const tenantConfirmationLabel = (value: string | null | undefined): string => {
  if (!value) return TENANT_CONFIRMATION_NOT_RECORDED;
  if (value === 'PENDING' || value === 'PENDING_CONFIRMATION') return 'Awaiting tenant';
  if (value === 'CONFIRMED') return 'Tenant confirmed';
  if (value === 'REJECTED') return 'Tenant declined';
  if (value === 'NOT_REQUIRED') return 'Confirmation not required';
  return humanizeOperationsCode(value);
};

export const tenantConfirmationTone = (value: string | null | undefined): 'pending' | 'confirmed' | 'none' => {
  if (value === 'PENDING' || value === 'PENDING_CONFIRMATION') return 'pending';
  if (value === 'CONFIRMED') return 'confirmed';
  return 'none';
};

/* ------------------------------------------------------------------ *
 * Attention truth — identical rule to the server's needs-attention filter
 * ------------------------------------------------------------------ */

export interface AttentionFacts {
  targetType: OperationsTargetType;
  status: string;
  scheduledAt?: string | null;
  tenantConfirmationState?: string | null;
}

/**
 * Mirrors the repository's `needs_attention` definition, which restricts attention to states the
 * backend already treats as requiring coordinator action. UNAVAILABLE stays out on purpose: the
 * owned lifecycle leaves it terminal for the owner.
 */
export const needsCoordinatorAttention = (facts: AttentionFacts): boolean => {
  if (facts.targetType === 'REQUEST') return facts.status === 'RECEIVED';
  if (facts.status === 'DRAFT') return !facts.scheduledAt;
  if (facts.status === 'SCHEDULED') {
    return facts.tenantConfirmationState === 'PENDING' || facts.tenantConfirmationState === 'PENDING_CONFIRMATION';
  }
  return facts.status === 'REPAIR_REQUIRED'
    || facts.status === 'PROVISIONAL_NO_SHOW'
    || facts.status === 'INTERRUPTED';
};

/** Plain descriptions of persisted facts. These never name who must act or invent a root cause. */
export const attentionCategory = (facts: AttentionFacts): string => {
  if (facts.targetType === 'REQUEST') {
    return facts.status === 'RECEIVED' ? 'Coordination pending' : 'Needs a next step';
  }
  switch (facts.status) {
    case 'DRAFT':
      return facts.scheduledAt ? 'Needs a next step' : 'Scheduling pending';
    case 'SCHEDULED':
      return facts.tenantConfirmationState === 'PENDING' || facts.tenantConfirmationState === 'PENDING_CONFIRMATION'
        ? 'Tenant confirmation pending'
        : 'Needs a next step';
    case 'REPAIR_REQUIRED':
      return 'Repair required';
    case 'INTERRUPTED':
      return 'Visit interrupted';
    case 'PROVISIONAL_NO_SHOW':
      return 'Provisional no-show review';
    default:
      return 'Needs a next step';
  }
};

/* ------------------------------------------------------------------ *
 * Labels
 * ------------------------------------------------------------------ */

export const operationsRecordTag = (targetType: OperationsTargetType): string =>
  targetType === 'SESSION' ? 'Visit plan' : 'Visit request';

/**
 * The API exposes numeric roots only, so the workspace shows the real identifier and never a
 * fabricated human reference.
 */
export const operationsRecordReference = (targetId: number): string => `#${targetId}`;

export const operationsRecordTitle = (targetType: OperationsTargetType, targetId: number): string =>
  `${operationsRecordTag(targetType)} ${operationsRecordReference(targetId)}`;

export const linkedRequestsLabel = (count: number): string =>
  `${count} tenant visit ${count === 1 ? 'request' : 'requests'}`;

export const operationsNoun = (targetType: OperationsTargetType): string =>
  targetType === 'SESSION' ? 'visit plan' : 'visit request';

/* ------------------------------------------------------------------ *
 * HTTP outcome classification
 * ------------------------------------------------------------------ */

export type OperationsLoadFailure = 'signed-out' | 'denied' | 'error';

/** 401 ends the session; 403 means the operational authority is gone; everything else is retryable. */
export const operationsLoadFailureState = (status: number | null | undefined): OperationsLoadFailure => {
  if (status === 401) return 'signed-out';
  if (status === 403) return 'denied';
  return 'error';
};

/**
 * A specific root that is not currently owned by the caller answers 404, and a lost claim answers
 * 403, so both are reported as `missing` — existence is never confirmed for work the caller does
 * not own.
 */
export const operationsDetailFailureState = (status: number | null | undefined): 'missing' | 'error' =>
  status === 403 || status === 404 ? 'missing' : 'error';

/* ------------------------------------------------------------------ *
 * Ownership command outcomes
 * ------------------------------------------------------------------ */

/**
 * How an ownership command ended.
 *
 *  - `pre-dispatch` — nothing left the app, so this action provably changed no ownership.
 *  - `rejected`     — the backend answered and refused the command (HTTP 4xx), so it was not applied.
 *  - `uncertain`    — the command *was* transmitted but its outcome is not confirmed: no response
 *                     arrived, the response was lost, the request was aborted, or the server failed
 *                     while answering. The copy must never claim the mutation succeeded or failed.
 */
export type OwnershipFailureKind = 'pre-dispatch' | 'rejected' | 'uncertain';

/** The real outcome of a post-command authoritative reconciliation. */
export type OwnershipReconciliation = 'loaded' | 'failed' | 'skipped' | 'not-attempted';

export interface OwnershipOutcomeInput {
  /** True only once the POST was handed to the transport. Set by the service at dispatch time. */
  dispatched: boolean;
  /** HTTP status when the backend answered. */
  status?: number | null;
  /** The backend or transport message, when one exists. */
  message?: string | null;
  /** True when nothing was sent because authorization for the action could not be confirmed. */
  blockedByAuthorization?: boolean;
  /**
   * The *real* outcome of the authoritative reconciliation attempted for this command — never merely
   * whether one was permitted. `loaded` means every required read succeeded and was accepted under
   * the initiating session; `failed` means a required read did not succeed; `skipped` means
   * reconciliation was not attempted or was invalidated; `not-attempted` (the default) means no
   * reconciliation was needed yet. Only `loaded` may be described as a completed refresh.
   */
  reconciliation?: OwnershipReconciliation | null;
}

export interface OwnershipOutcomeCopy {
  kind: OwnershipFailureKind;
  /** The exact paragraph shown in the workspace notice. */
  message: string;
  /**
   * Whether the caller may reload the authoritative queues. Only a transmitted command needs it, and
   * the caller still has to confirm the session and authorization are valid first.
   */
  reconcile: boolean;
}

/** Classification of an ownership command outcome, driven by whether anything was transmitted. */
export const ownershipFailureKind = (input: OwnershipOutcomeInput): OwnershipFailureKind => {
  if (!input.dispatched) return 'pre-dispatch';
  const status = input.status;
  // A 4xx is the backend refusing the command: it was read and refused, so it was not applied.
  if (typeof status === 'number' && status >= 400 && status < 500) return 'rejected';
  // No response, a lost response, an abort, or a 5xx that may have failed after applying the command.
  return 'uncertain';
};

/** What one failed command must do to the workspace, decided from the real status alone. */
export interface CommandFailureDisposition {
  kind: OwnershipFailureKind;
  /** The server definitively denied access to *this* root (403/404). */
  targetDenied: boolean;
  /**
   * Withdraw the open protected root: a denial leaves the previously revealed detail and its lessor
   * contact mounted, so it has to be dropped the moment the server refuses the resource — whether the
   * refusal came from the fresh pre-command read or from the mutation itself. A target denial is never
   * a global account revocation: the separate session/authority invalidation path owns that.
   */
  withdrawRoot: boolean;
  /** Reload the authoritative queues before reporting, so the message can state the real state. */
  reconcile: boolean;
}

/**
 * The single decision every failed Phase 2A command makes, kept pure so it can be tested directly:
 * a definitive root denial withdraws protected detail even before dispatch, a transmitted command is
 * reconciled from the server, and an unconfirmed outcome is never treated as a denial.
 */
export const commandFailureDisposition = (
  input: OwnershipOutcomeInput,
  options: { dropDetailWhenRejected?: boolean } = {}
): CommandFailureDisposition => {
  const status = input.status ?? null;
  const targetDenied = status === 403 || status === 404;
  const kind = ownershipFailureKind(input);
  return {
    kind,
    targetDenied,
    withdrawRoot: targetDenied
      || (options.dropDetailWhenRejected === true && (status === 403 || status === 404 || status === 409)),
    reconcile: kind !== 'pre-dispatch' || targetDenied
  };
};

/** True only when the authoritative reads actually completed and were accepted. */
const reconciliationLoaded = (input: OwnershipOutcomeInput): boolean => input.reconciliation === 'loaded';

/**
 * Truthful OE-facing copy for a failed ownership command. A generic "nothing was changed" is never
 * produced for a command that may have reached the backend, and an uncertain outcome never suggests
 * resending it.
 *
 * The word "refreshed" is used only when the reconciliation genuinely succeeded (`reconciliation:
 * 'loaded'`); a failed or skipped reconciliation is stated as "could not be verified" and points at a
 * safe manual refresh instead of pretending the work lists are current.
 */
export const ownershipOutcomeCopy = (name: string, input: OwnershipOutcomeInput): OwnershipOutcomeCopy => {
  const kind = ownershipFailureKind(input);

  if (kind === 'pre-dispatch') {
    return {
      kind,
      reconcile: false,
      message: input.blockedByAuthorization
        ? `${name} was not sent, so nothing was changed by you. Your Operations access could not be confirmed for the action — verify your access, then try again.`
        : `${name} was not sent, so nothing was changed by you. The action stopped before any ownership command left the app — review the current state and try again.`
    };
  }

  if (kind === 'rejected') {
    const status = input.status ?? 0;
    if (status === 401) {
      return {
        kind,
        reconcile: false,
        message: 'Your session ended before the action was applied. Nothing was changed. Sign in again to continue.'
      };
    }
    const recovered = reconciliationLoaded(input)
      ? 'The queues were refreshed from the server — review the latest state before deciding again.'
      : `Current ownership could not be verified, so the work lists may be out of date — refresh them before deciding again.`;
    return {
      kind,
      reconcile: true,
      message: status === 409
        ? `${name} changed on the server while you were working, so nothing was changed by you. ${recovered}`
        : status === 404
          ? `${name} is no longer available to claim or owned by you. ${recovered}`
          : status === 403
            ? `Your coordination authority for this team is not currently active, so ${name} was left unchanged. ${recovered}`
            : input.message
              ? `${input.message} ${name} was refused by the server, so it was not applied. ${recovered}`
              : `${name} was refused by the server, so it was not applied. ${recovered}`
    };
  }

  const tail = reconciliationLoaded(input)
    ? 'The queues were reloaded from the server and show the current ownership — check them before attempting anything further.'
    : 'Current ownership could not be verified, so the work lists may be out of date — refresh them before attempting anything further, and sign in again if your session has ended.';
  const reason = typeof input.status === 'number'
    ? `the server failed while answering it (HTTP ${input.status})`
    : 'the response did not arrive';
  return {
    kind,
    reconcile: true,
    message: `${name} was sent, but ${reason}, so it is not confirmed whether it was applied. ${tail}`
  };
};

/**
 * Truthful copy for a *confirmed* ownership command whose follow-up refresh did not complete. The
 * command result and the reconciliation result are stated separately, so a confirmed success is never
 * reclassified as uncertain and the work lists are never claimed to be current.
 */
export const ownershipRefreshFailureNotice = (name: string): string =>
  `${name} was updated on the server, but the work lists could not be refreshed just now, so they may not show it yet — refresh them to update the view.`;

/* ------------------------------------------------------------------ *
 * Date and time
 * ------------------------------------------------------------------ */

const IST_OFFSET_MINUTES = 330;
const IST_WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const IST_MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const NBSP = '\u00a0';

interface ShiftedParts {
  weekday: string;
  day: number;
  month: string;
  year: number;
  hour12: number;
  minutes: string;
  meridiem: string;
}

const shiftToIst = (value: string | null | undefined): ShiftedParts | null => {
  if (!value) return null;
  const parsed = Date.parse(value);
  if (!Number.isFinite(parsed)) return null;
  const shifted = new Date(parsed + IST_OFFSET_MINUTES * 60_000);
  const hour = shifted.getUTCHours();
  return {
    weekday: IST_WEEKDAYS[shifted.getUTCDay()],
    day: shifted.getUTCDate(),
    month: IST_MONTHS[shifted.getUTCMonth()],
    year: shifted.getUTCFullYear(),
    hour12: hour % 12 || 12,
    minutes: String(shifted.getUTCMinutes()).padStart(2, '0'),
    meridiem: hour >= 12 ? 'pm' : 'am'
  };
};

/** Compact IST date, e.g. `Sun, 11 Oct`. */
export const formatOperationsDate = (value: string | null | undefined): string | null => {
  const parts = shiftToIst(value);
  return parts ? `${parts.weekday},${NBSP}${parts.day}${NBSP}${parts.month}` : null;
};

/** Compact IST clock time, e.g. `4:30 pm`. */
export const formatOperationsTime = (value: string | null | undefined): string | null => {
  const parts = shiftToIst(value);
  return parts ? `${parts.hour12}:${parts.minutes}${NBSP}${parts.meridiem}` : null;
};

export const formatOperationsDateTime = (value: string | null | undefined): string | null => {
  const date = formatOperationsDate(value);
  const time = formatOperationsTime(value);
  return date && time ? `${date}${NBSP}·${NBSP}${time}` : null;
};

/**
 * Unambiguous detail-field form, e.g. `Sun, 11 Oct 2026 · 4:30 pm IST`.
 * Detail values are ordinary prose that must wrap at natural spaces on narrow screens, so unlike the
 * compact row formats this one intentionally uses regular spaces instead of non-breaking spaces.
 */
export const formatOperationsDetailDateTime = (value: string | null | undefined): string | null => {
  const parts = shiftToIst(value);
  if (!parts) return null;
  return `${parts.weekday}, ${parts.day} ${parts.month} ${parts.year} · ${parts.hour12}:${parts.minutes} ${parts.meridiem} IST`;
};

/**
 * Availability detail values in the zone that actually owns them. The legacy IST formatter above is
 * left unchanged for accepted Session and timeline rendering; tenant availability is not an IST fact
 * once a request carries another IANA zone.
 */
export const formatOperationsZonedDateTime = (
  value: string | null | undefined,
  zoneId: string | null | undefined
): string | null => {
  if (!isOperationsZone(zoneId)) return null;
  const zoned = instantToZoneInstant(value, zoneId);
  if (!zoned) return null;
  const date = zoned.slice(0, 10);
  const time = zoned.slice(11, 16);
  const [year, month, day] = date.split('-').map(Number);
  const hour = Number(time.slice(0, 2));
  const dateLabel = new Intl.DateTimeFormat('en-IN', {
    timeZone: 'UTC',
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    year: 'numeric'
  }).format(new Date(Date.UTC(year, month - 1, day)));
  return `${dateLabel} · ${hour % 12 || 12}:${time.slice(3, 5)} ${hour >= 12 ? 'pm' : 'am'} ${zoneId}`;
};

/** Both endpoints in their owning zone, so an overnight window never hides its second date. */
export const formatOperationsZonedWindow = (
  startAt: string | null | undefined,
  endAt: string | null | undefined,
  zoneId: string | null | undefined
): string | null => {
  const start = formatOperationsZonedDateTime(startAt, zoneId);
  const end = formatOperationsZonedDateTime(endAt, zoneId);
  if (!start || !end) return null;
  return `${start} → ${end}`;
};

const zoneOffsetAt = (value: string | null, zoneId: string | null): string | null => {
  const zoned = instantToZoneInstant(value, zoneId);
  return zoned ? zoned.slice(-6) : null;
};

const localWallClockAt = (epochMs: number, zoneId: string): string | null => {
  try {
    return new Intl.DateTimeFormat('en-US', {
      timeZone: zoneId,
      hour12: false,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit'
    }).format(new Date(epochMs));
  } catch {
    return null;
  }
};

/** True when the same local wall clock occurs twice around this instant (a DST fold). */
export const isRepeatedOperationsLocalTime = (
  value: string | null | undefined,
  zoneId: string | null | undefined
): boolean => {
  const parsed = value ? Date.parse(value) : Number.NaN;
  if (!Number.isFinite(parsed) || !isOperationsZone(zoneId)) return false;
  const key = localWallClockAt(parsed, zoneId as string);
  if (!key) return false;
  // A 30-minute transition is probed as well, because not every zone changes by a whole hour.
  return [30 * 60_000, 60 * 60_000].some((delta) =>
    localWallClockAt(parsed - delta, zoneId as string) === key
    || localWallClockAt(parsed + delta, zoneId as string) === key
  );
};

/**
 * Availability time in the zone that owns it. A repeated local time (DST fold) also carries its
 * actual offset, so the two occurrences remain distinguishable; every other instant keeps the
 * accepted compact zone-labelled form.
 */
export const formatOperationsAvailabilityDateTime = (
  value: string | null | undefined,
  zoneId: string | null | undefined
): string | null => {
  const label = formatOperationsZonedDateTime(value, zoneId);
  if (!label) return null;
  const offset = isRepeatedOperationsLocalTime(value, zoneId)
    ? zoneOffsetAt(value ?? null, zoneId ?? null)
    : null;
  return offset ? `${label} (${offset})` : label;
};

/** Both endpoints in their owning zone, with repeated local times disambiguated by offset. */
export const formatOperationsAvailabilityWindow = (
  startAt: string | null | undefined,
  endAt: string | null | undefined,
  zoneId: string | null | undefined
): string | null => {
  const label = formatOperationsZonedWindow(startAt, endAt, zoneId);
  if (!label) return null;
  if (!isRepeatedOperationsLocalTime(startAt, zoneId)
    && !isRepeatedOperationsLocalTime(endAt, zoneId)) return label;
  const start = formatOperationsAvailabilityDateTime(startAt, zoneId);
  const end = formatOperationsAvailabilityDateTime(endAt, zoneId);
  return start && end ? `${start} → ${end}` : label;
};

export const formatOperationsTimeRange = (
  startAt: string | null | undefined,
  minutes: number | null | undefined
): string | null => {
  const start = formatOperationsTime(startAt);
  if (!start || !startAt || minutes === null || minutes === undefined) return null;
  const parsed = Date.parse(startAt);
  if (!Number.isFinite(parsed)) return null;
  const end = formatOperationsTime(new Date(parsed + minutes * 60_000).toISOString());
  return end ? `${start} to ${end}` : null;
};

export const formatOperationsDuration = (minutes: number | null | undefined): string | null => {
  if (minutes === null || minutes === undefined || !Number.isFinite(minutes) || minutes <= 0) return null;
  const hours = Math.floor(minutes / 60);
  const remainder = minutes % 60;
  return [hours ? `${hours}${NBSP}h` : '', remainder ? `${remainder}${NBSP}min` : '']
    .filter(Boolean)
    .join(' ');
};

/**
 * `property_visit_requests.created_at` is stored without a time zone. Converting it would invent
 * an instant, so the stored local wall-clock value is rendered as stored and labelled by the UI.
 */
export const formatStoredLocalDateTime = (value: string | null | undefined): string | null => {
  if (!value) return null;
  const match = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/.exec(value.trim());
  if (!match) return null;
  const [, year, month, day, hours, minutes] = match;
  const hour = Number(hours);
  return `${Number(day)} ${IST_MONTHS[Number(month) - 1]} ${year} · ${hour % 12 || 12}:${minutes} ${hour >= 12 ? 'pm' : 'am'}`;
};
