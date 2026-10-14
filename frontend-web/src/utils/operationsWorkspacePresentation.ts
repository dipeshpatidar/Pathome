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
  sourceRequestId: number | null;
  origin: string | null;
  confirmationStatus: string | null;
  availabilityConfirmedAt: string | null;
  removedAt: string | null;
  removalReason: string | null;
  availabilityStartAt: string | null;
  availabilityEndAt: string | null;
  availabilityZoneId: string | null;
}

export interface OperationsRequestDetail {
  requestId: number;
  status: string;
  version: number | null;
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
  teamId: number | null;
  teamDisplayName: string | null;
  coordinatorUserId: number | null;
  updatedAt: string | null;
  tenantConfirmationState: string | null;
  startedAt: string | null;
  expiresAt: string | null;
  executionStateChangedAt: string | null;
}

export interface OperationsScope {
  teamId: number;
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
      cityName: capability.cityDisplayName ?? 'City not recorded',
      teamName: capability.teamDisplayName ?? 'Team not recorded'
    });
  }
  return scopes;
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

export interface ParsedWorkPage {
  items: unknown[];
  totalCount: number;
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
  const totalPages = readInteger(record.totalPages)
    ?? (size > 0 && totalCount > 0 ? Math.ceil(totalCount / size) : 0);
  return { items: record.items, totalCount, page, size, totalPages };
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

export const toCapabilitySummary = (raw: unknown): StaffCapabilitySummary | null => {
  const record = asRecord(raw);
  if (!record) return null;
  const capability = readText(record.capability);
  const scopeType = readText(record.scopeType);
  if (capability === null || scopeType === null) return null;
  return {
    capability,
    scopeType,
    cityId: readInteger(record.cityId),
    cityDisplayName: readText(record.cityDisplayName),
    teamId: readInteger(record.teamId),
    teamDisplayName: readText(record.teamDisplayName)
  };
};

export const toStaffCapabilities = (raw: unknown): StaffCapabilities => {
  const record = asRecord(raw);
  if (!record) throw new Error('Staff capability response was not an object');
  const list = Array.isArray(record.capabilities) ? record.capabilities : [];
  return {
    staffActive: record.staffActive === true,
    capabilities: list
      .map(toCapabilitySummary)
      .filter((entry): entry is StaffCapabilitySummary => entry !== null),
    serverTime: readText(record.serverTime)
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
    origin: readText(record.origin),
    confirmationStatus: readText(record.confirmationStatus),
    availabilityConfirmedAt: readText(record.availabilityConfirmedAt),
    removedAt: readText(record.removedAt),
    removalReason: readText(record.removalReason),
    availabilityStartAt: readText(record.availabilityStartAt),
    availabilityEndAt: readText(record.availabilityEndAt),
    availabilityZoneId: readText(record.availabilityZoneId)
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
  RECEIVED: { label: 'Received', tone: 'open', hint: 'Waiting to be coordinated' },
  COORDINATING: { label: 'Coordinating', tone: 'active', hint: 'Coordination is in progress' },
  SCHEDULED: { label: 'Scheduled', tone: 'scheduled', hint: 'Part of a scheduled visit' },
  UNAVAILABLE: { label: 'Unavailable', tone: 'closed', hint: 'Property confirmed unavailable' },
  CANCELLED: { label: 'Cancelled', tone: 'closed', hint: 'Cancelled' }
};

const SESSION_STATUS: Record<string, OperationsStatusMeta> = {
  DRAFT: { label: 'Draft', tone: 'open', hint: 'No visit time is booked yet' },
  SCHEDULED: { label: 'Scheduled', tone: 'scheduled', hint: 'A visit time is booked' },
  STARTED: { label: 'In progress', tone: 'active', hint: 'The visit has started' },
  COMPLETED: { label: 'Completed', tone: 'closed', hint: 'The visit finished' },
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
  targetType === 'SESSION' ? 'Session' : 'Request';

/**
 * The API exposes numeric roots only, so the workspace shows the real identifier and never a
 * fabricated human reference.
 */
export const operationsRecordReference = (targetId: number): string => `#${targetId}`;

export const operationsRecordTitle = (targetType: OperationsTargetType, targetId: number): string =>
  `${operationsRecordTag(targetType)} ${operationsRecordReference(targetId)}`;

export const linkedRequestsLabel = (count: number): string =>
  `${count} linked ${count === 1 ? 'request' : 'requests'}`;

export const operationsNoun = (targetType: OperationsTargetType): string =>
  targetType === 'SESSION' ? 'session' : 'request';

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
          ? `${name} is no longer claimable or owned by you. ${recovered}`
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
