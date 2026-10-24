import type {
  StaffCapabilities,
  StaffCapabilitySummary
} from './operationsWorkspacePresentation';

export type LeadStatus =
  | 'NEW'
  | 'QUALIFYING'
  | 'QUALIFIED'
  | 'CLOSED'
  | 'ASSIGNED'
  | 'UNMAPPED'
  | 'CONTACTED'
  | 'STALE'
  | string;

export type LeadIntent = 'RENT' | 'BUY' | 'UNSPECIFIED' | string;
export type LeadFollowUpFilter = 'ANY' | 'OVERDUE' | 'TODAY' | 'NONE';

export interface LeadSummary {
  id: number;
  version: number;
  customerName: string | null;
  maskedContact: string | null;
  status: LeadStatus;
  intent: LeadIntent;
  cityId: number | null;
  cityName: string | null;
  teamId: number | null;
  teamName: string | null;
  ownerUserId: number | null;
  ownerDisplayName: string | null;
  followUpAt: string | null;
  followUpZoneId: string | null;
  lastActivityAt: string | null;
  qualificationGaps: string[];
}

export interface LeadSourceAttribution {
  sourceEventId: string;
  eventKind: string;
  channel: string | null;
  campaignRef: string | null;
  referralRef: string | null;
  receivedAt: string | null;
  state: string;
  fingerprintVersion: string | null;
  manualEnquiry: LeadManualEnquiry | null;
}

export interface LeadManualEnquiry {
  eventKind: string;
  channel: string | null;
  capturedAt: string | null;
  state: string;
  snapshotVersion: string | null;
  protectedPayloadRetained: boolean;
}

export interface LeadCaptureOutcome {
  resolution: 'CREATED' | 'ATTACHED_TO_EXISTING' | 'KEPT_DISTINCT' | string;
  leadId: number;
  leadVersion: number;
  reasonCode: string | null;
  manualEnquiry: LeadManualEnquiry | null;
}

export interface LeadDetail extends LeadSummary {
  customerSaid: string | null;
  propertyType: string | null;
  bhk: string | null;
  budgetMin: number | null;
  budgetMax: number | null;
  currency: string | null;
  targetMoveInDate: string | null;
  furnishing: string | null;
  tenantProfile: string | null;
  parkingRequired: boolean | null;
  petsAllowed: boolean | null;
  liftRequired: boolean | null;
  floorRequirement: string | null;
  preferredLocalityIds: number[];
  sourceAttributions: LeadSourceAttribution[];
  communicationPrerequisites: string[];
  conversionPrerequisites: string[];
  linkedCustomerAccount: boolean;
  provenance: string | null;
  captureOutcome: LeadCaptureOutcome | null;
}

export interface LeadPage {
  items: LeadSummary[];
  totalElements: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface LeadActivity {
  id: number;
  type: string;
  occurredAt: string | null;
  note: string | null;
}

export interface LeadActivityPage {
  items: LeadActivity[];
  totalElements: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface LeadDuplicateCandidate {
  leadId: number;
  version: number;
  maskedContact: string | null;
  customerName: string | null;
  status: LeadStatus;
  cityId: number | null;
  teamId: number | null;
  currentlyOwnedByActor: boolean;
}

export interface LeadDuplicateResponse {
  candidates: LeadDuplicateCandidate[];
  existingAccountMatchAvailable: boolean;
}

export interface LeadContactPreference {
  leadId: number;
  revision: number;
  leadVersion: number;
  channel: 'WHATSAPP' | 'OTHER' | string;
  destinationChoice: 'SAME' | 'DIFFERENT' | 'NONE' | string;
  maskedDestination: string | null;
  consentStatus: 'GRANTED' | 'REVOKED' | 'DISPUTED' | 'UNKNOWN' | string;
  consentPurpose: string | null;
  consentWordingVersion: string | null;
  evidenceMethod: 'CUSTOMER_VERBAL' | 'CUSTOMER_DIGITAL' | string | null;
  recordedAt: string | null;
}

export interface LeadDestinationReveal {
  leadId: number;
  revision: number;
  fullDestination: string;
  recordedAt: string | null;
}

export interface LeadShortlist {
  id: number;
  version: number;
  leadVersion: number;
  listingIds: number[];
  createdAt: string | null;
  updatedAt: string | null;
}

export type LeadShareMode = 'COPY_LINKS' | 'MANUAL_BUSINESS_HANDOFF';
export type LeadShareEventType =
  | 'CLIPBOARD_COPIED'
  | 'HANDOFF_ATTEMPTED'
  | 'OPERATOR_REPORTED_MANUAL_SEND'
  | 'HANDOFF_CANCELLED';

export interface LeadShare {
  id: string;
  leadId: number;
  shortlistId: number;
  shortlistVersion: number;
  contactPreferenceRevision: number;
  mode: LeadShareMode | string;
  publicLinks: string[];
  previewText: string;
  maskedDestination: string | null;
  recordedAt: string | null;
}

export interface LeadShareEvent {
  id: string;
  shareId: string;
  type: LeadShareEventType | string;
  occurredAt: string | null;
}

export interface LeadShareHistoryEvent {
  eventId: string;
  type: LeadShareEventType | string;
  occurredAt: string | null;
}

export interface LeadShareHistoryItem {
  id: string;
  shortlistId: number;
  shortlistVersion: number;
  contactPreferenceRevision: number;
  mode: LeadShareMode | string;
  publicLinks: string[];
  previewText: string;
  maskedDestination: string | null;
  recordedAt: string | null;
  events: LeadShareHistoryEvent[];
  currentFactsMatch: boolean;
  currentConsentMatches: boolean;
  currentDestinationMatches: boolean;
  currentAuthorityMatches: boolean;
  actionReady: boolean;
}

export interface LeadShareHistoryPage {
  items: LeadShareHistoryItem[];
  totalElements: number;
  page: number;
  size: number;
  totalPages: number;
}

/**
 * Converts an authorized history record into the same shape used by the initial preparation
 * response. This adds only the Lead identity from the already-authorized route; every displayed
 * share fact still comes from the server history.
 */
export const leadShareFromHistoryItem = (
  item: LeadShareHistoryItem,
  leadId: number
): LeadShare => ({
  id: item.id,
  leadId,
  shortlistId: item.shortlistId,
  shortlistVersion: item.shortlistVersion,
  contactPreferenceRevision: item.contactPreferenceRevision,
  mode: item.mode,
  publicLinks: item.publicLinks,
  previewText: item.previewText,
  maskedDestination: item.maskedDestination,
  recordedAt: item.recordedAt
});

export interface LeadCommandReceipt {
  state: string;
  action: string | null;
  resultReference: string | null;
  recordedAt: string | null;
}

export interface LeadSourceException {
  eventId: string;
  eventKind: string;
  sourceChannel: string | null;
  receivedAt: string | null;
  state: string;
  attempts: number;
  errorCode: string | null;
  fingerprintVersion: string | null;
  attachedLeadId: number | null;
  resolutionLeadId: number | null;
  extractedPayloadRetained: boolean;
}

export interface LeadSourceExceptionPage {
  items: LeadSourceException[];
  totalElements: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface LeadScope {
  cityId: number;
  cityName: string;
  teamId: number | null;
  teamName: string | null;
}

export interface LeadQueueFilters {
  q: string;
  cityId: number | null;
  teamId: number | null;
  status: LeadStatus | null;
  ownerMe: boolean;
  followUp: LeadFollowUpFilter;
}

export const EMPTY_LEAD_QUEUE_FILTERS: LeadQueueFilters = {
  q: '',
  cityId: null,
  teamId: null,
  status: null,
  ownerMe: false,
  followUp: 'ANY'
};

export interface LeadQueueContext {
  page: number;
  filters: LeadQueueFilters;
}

export const LEAD_PAGE_SIZE = 20;
export const SHARE_HISTORY_PAGE_SIZE = 10;
export const SHORTLIST_MAX = 20;
export const LEAD_QUEUE_FOLLOW_UP_ZONE = 'Asia/Kolkata';

export const LEAD_STATUS_META: Record<string, {
  label: string;
  tone: 'open' | 'active' | 'attention' | 'closed';
}> = {
  NEW: { label: 'New enquiry', tone: 'open' },
  QUALIFYING: { label: 'Qualifying', tone: 'active' },
  QUALIFIED: { label: 'Qualified', tone: 'active' },
  CLOSED: { label: 'Closed', tone: 'closed' },
  ASSIGNED: { label: 'Assigned (legacy)', tone: 'closed' },
  UNMAPPED: { label: 'Unmapped (legacy)', tone: 'attention' },
  CONTACTED: { label: 'Contacted (legacy)', tone: 'closed' },
  STALE: { label: 'Stale (legacy)', tone: 'attention' }
};

const positiveSearchInteger = (value: string | null): number | null => {
  if (!value || !/^\d+$/.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
};

/** Queue navigation context contains only non-sensitive scope, status and page coordinates. */
export const leadQueueContextFromSearch = (search: string): LeadQueueContext => {
  const params = new URLSearchParams(search.startsWith('?') ? search.slice(1) : search);
  const pageValue = params.get('page');
  const parsedPage = pageValue && /^\d+$/.test(pageValue) ? Number(pageValue) : 0;
  const statusValue = params.get('status');
  const followUpValue = params.get('followUp');
  const followUp: LeadFollowUpFilter = followUpValue === 'OVERDUE'
    || followUpValue === 'TODAY'
    || followUpValue === 'NONE'
    ? followUpValue
    : params.get('followUpBefore') === 'due' ? 'OVERDUE' : 'ANY';
  return {
    page: Number.isSafeInteger(parsedPage) && parsedPage >= 0 ? parsedPage : 0,
    filters: {
      q: '',
      cityId: positiveSearchInteger(params.get('cityId')),
      teamId: positiveSearchInteger(params.get('teamId')),
      status: statusValue && LEAD_STATUS_META[statusValue] ? statusValue : null,
      ownerMe: params.get('owner') === 'me',
      followUp
    }
  };
};

export const leadQueueContextQuery = (
  page: number,
  filters: LeadQueueFilters
): string => {
  const params = new URLSearchParams();
  if (page > 0) params.set('page', String(page));
  if (filters.cityId !== null) params.set('cityId', String(filters.cityId));
  if (filters.teamId !== null) params.set('teamId', String(filters.teamId));
  if (filters.status !== null) params.set('status', filters.status);
  if (filters.ownerMe) params.set('owner', 'me');
  if (filters.followUp !== 'ANY') {
    params.set('followUp', filters.followUp);
    params.set('followUpZone', LEAD_QUEUE_FOLLOW_UP_ZONE);
  }
  return params.toString();
};

export const SHARE_EVENT_META: Record<string, {
  label: string;
  tone: 'open' | 'active' | 'attention' | 'closed';
}> = {
  CLIPBOARD_COPIED: { label: 'Links copied', tone: 'active' },
  HANDOFF_ATTEMPTED: { label: 'Attempted to open WhatsApp', tone: 'active' },
  OPERATOR_REPORTED_MANUAL_SEND: { label: 'You reported sending it', tone: 'active' },
  HANDOFF_CANCELLED: { label: 'Sending stopped', tone: 'closed' }
};

const recordOf = (value: unknown): Record<string, unknown> | null =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;

const integerOf = (value: unknown, field: string): number => {
  if (typeof value === 'number' && Number.isSafeInteger(value)) return value;
  if (typeof value === 'string' && /^-?\d+$/.test(value.trim())) {
    const parsed = Number(value);
    if (Number.isSafeInteger(parsed)) return parsed;
  }
  throw new Error(`Lead response field ${field} is invalid`);
};

const optionalInteger = (value: unknown): number | null => {
  if (value === null || value === undefined) return null;
  try {
    return integerOf(value, 'optional integer');
  } catch {
    return null;
  }
};

const textOf = (value: unknown): string | null =>
  typeof value === 'string' && value.trim() ? value : null;

const stringArray = (value: unknown): string[] =>
  Array.isArray(value)
    ? value.filter((entry): entry is string => typeof entry === 'string')
    : [];

const integerArray = (value: unknown): number[] =>
  Array.isArray(value)
    ? value.map(optionalInteger).filter((entry): entry is number => entry !== null)
    : [];

const pageEnvelope = (
  value: unknown,
  fallbackPage: number,
  fallbackSize: number,
  label: string
): Record<string, unknown> & { items: unknown[] } => {
  const record = recordOf(value);
  if (!record || !Array.isArray(record.items)) throw new Error(`${label} response is invalid`);
  return {
    ...record,
    items: record.items,
    totalElements: integerOf(record.totalElements ?? 0, `${label}.totalElements`),
    page: integerOf(record.page ?? fallbackPage, `${label}.page`),
    size: integerOf(record.size ?? fallbackSize, `${label}.size`),
    totalPages: integerOf(record.totalPages ?? 0, `${label}.totalPages`)
  };
};

const toSummaryRecord = (value: unknown): Record<string, unknown> => {
  const record = recordOf(value);
  if (!record) throw new Error('Lead summary response is invalid');
  return record;
};

export const toLeadSummary = (value: unknown): LeadSummary => {
  const item = toSummaryRecord(value);
  return {
    id: integerOf(item.id, 'id'),
    version: integerOf(item.version, 'version'),
    customerName: textOf(item.customerName),
    maskedContact: textOf(item.maskedContact),
    status: textOf(item.status) ?? 'UNKNOWN',
    intent: textOf(item.intent) ?? 'UNSPECIFIED',
    cityId: optionalInteger(item.cityId),
    cityName: textOf(item.cityName),
    teamId: optionalInteger(item.teamId),
    teamName: textOf(item.teamName),
    ownerUserId: optionalInteger(item.ownerUserId),
    ownerDisplayName: textOf(item.ownerDisplayName),
    followUpAt: textOf(item.followUpAt),
    followUpZoneId: textOf(item.followUpZoneId),
    lastActivityAt: textOf(item.lastActivityAt),
    qualificationGaps: stringArray(item.qualificationGaps)
  };
};

export const toLeadPage = (
  value: unknown,
  fallbackPage = 0,
  fallbackSize = LEAD_PAGE_SIZE
): LeadPage => {
  const envelope = pageEnvelope(value, fallbackPage, fallbackSize, 'Lead page');
  return {
    items: envelope.items.map(toLeadSummary),
    totalElements: envelope.totalElements as number,
    page: envelope.page as number,
    size: envelope.size as number,
    totalPages: envelope.totalPages as number
  };
};

export const toLeadDetail = (value: unknown): LeadDetail => {
  const item = recordOf(value);
  if (!item || !recordOf(item.summary)) throw new Error('Lead detail response is invalid');
  const summary = toLeadSummary(item.summary);
  const numberOrNull = (entry: unknown): number | null => {
    if (entry === null || entry === undefined) return null;
    const parsed = typeof entry === 'number' ? entry : Number(entry);
    return Number.isFinite(parsed) ? parsed : null;
  };
  const booleanOrNull = (entry: unknown): boolean | null =>
    typeof entry === 'boolean' ? entry : null;
  const manualEnquiry = (entry: unknown): LeadManualEnquiry | null => {
    const source = recordOf(entry);
    if (!source) return null;
    return {
      eventKind: textOf(source.eventKind) ?? 'UNKNOWN',
      channel: textOf(source.channel),
      capturedAt: textOf(source.capturedAt),
      state: textOf(source.state) ?? 'UNKNOWN',
      snapshotVersion: textOf(source.snapshotVersion),
      protectedPayloadRetained: source.protectedPayloadRetained === true
    };
  };
  const captureOutcome = (() => {
    const outcome = recordOf(item.captureOutcome);
    if (!outcome) return null;
    return {
      resolution: textOf(outcome.resolution) ?? 'UNKNOWN',
      leadId: integerOf(outcome.leadId, 'captureOutcome.leadId'),
      leadVersion: integerOf(outcome.leadVersion, 'captureOutcome.leadVersion'),
      reasonCode: textOf(outcome.reasonCode),
      manualEnquiry: manualEnquiry(outcome.manualEnquiry)
    };
  })();
  const sourceAttributions = Array.isArray(item.sourceAttributions)
    ? item.sourceAttributions.map((entry) => {
        const source = recordOf(entry);
        if (!source) throw new Error('Lead source attribution is invalid');
        return {
          sourceEventId: textOf(source.sourceEventId) ?? '',
          eventKind: textOf(source.eventKind) ?? 'UNKNOWN',
          channel: textOf(source.channel),
          campaignRef: textOf(source.campaignRef),
          referralRef: textOf(source.referralRef),
          receivedAt: textOf(source.receivedAt),
          state: textOf(source.state) ?? 'UNKNOWN',
          fingerprintVersion: textOf(source.fingerprintVersion),
          manualEnquiry: manualEnquiry(source.manualEnquiry)
        };
      })
    : [];
  return {
    ...summary,
    customerSaid: textOf(item.customerSaid),
    propertyType: textOf(item.propertyType),
    bhk: textOf(item.bhk),
    budgetMin: numberOrNull(item.budgetMin),
    budgetMax: numberOrNull(item.budgetMax),
    currency: textOf(item.currency),
    targetMoveInDate: textOf(item.targetMoveInDate),
    furnishing: textOf(item.furnishing),
    tenantProfile: textOf(item.tenantProfile),
    parkingRequired: booleanOrNull(item.parkingRequired),
    petsAllowed: booleanOrNull(item.petsAllowed),
    liftRequired: booleanOrNull(item.liftRequired),
    floorRequirement: textOf(item.floorRequirement),
    preferredLocalityIds: integerArray(item.preferredLocalityIds),
    sourceAttributions,
    communicationPrerequisites: stringArray(item.communicationPrerequisites),
    conversionPrerequisites: stringArray(item.conversionPrerequisites),
    linkedCustomerAccount: item.linkedCustomerAccount === true,
    provenance: textOf(item.provenance),
    captureOutcome
  };
};

export const toLeadActivityPage = (
  value: unknown,
  fallbackPage = 0,
  fallbackSize = LEAD_PAGE_SIZE
): LeadActivityPage => {
  const envelope = pageEnvelope(value, fallbackPage, fallbackSize, 'Lead activity page');
  return {
    items: envelope.items.map((entry) => {
      const item = recordOf(entry);
      if (!item) throw new Error('Lead activity response is invalid');
      return {
        id: integerOf(item.id, 'activity.id'),
        type: textOf(item.type) ?? 'UNKNOWN',
        occurredAt: textOf(item.occurredAt),
        note: textOf(item.note)
      };
    }),
    totalElements: envelope.totalElements as number,
    page: envelope.page as number,
    size: envelope.size as number,
    totalPages: envelope.totalPages as number
  };
};

export const toActivity = (value: unknown): LeadActivity => {
  const item = recordOf(value);
  if (!item) throw new Error('Lead activity response is invalid');
  return {
    id: integerOf(item.id, 'activity.id'),
    type: textOf(item.type) ?? 'UNKNOWN',
    occurredAt: textOf(item.occurredAt),
    note: textOf(item.note)
  };
};

export const toDuplicateResponse = (value: unknown): LeadDuplicateResponse => {
  const item = recordOf(value);
  if (!item || !Array.isArray(item.candidates)) {
    throw new Error('Duplicate candidate response is invalid');
  }
  return {
    candidates: item.candidates.map((entry) => {
      const candidate = recordOf(entry);
      if (!candidate) throw new Error('Duplicate candidate is invalid');
      return {
        leadId: integerOf(candidate.leadId, 'candidate.leadId'),
        version: integerOf(candidate.version, 'candidate.version'),
        maskedContact: textOf(candidate.maskedContact),
        customerName: textOf(candidate.customerName),
        status: textOf(candidate.status) ?? 'UNKNOWN',
        cityId: optionalInteger(candidate.cityId),
        teamId: optionalInteger(candidate.teamId),
        currentlyOwnedByActor: candidate.currentlyOwnedByActor === true
      };
    }),
    existingAccountMatchAvailable: item.existingAccountMatchAvailable === true
  };
};

export const toContactPreference = (value: unknown): LeadContactPreference => {
  const item = recordOf(value);
  if (!item) throw new Error('Contact preference response is invalid');
  return {
    leadId: integerOf(item.leadId, 'preference.leadId'),
    revision: integerOf(item.revision, 'preference.revision'),
    leadVersion: integerOf(item.leadVersion, 'preference.leadVersion'),
    channel: textOf(item.channel) ?? 'OTHER',
    destinationChoice: textOf(item.destinationChoice) ?? 'NONE',
    maskedDestination: textOf(item.maskedDestination),
    consentStatus: textOf(item.consentStatus) ?? 'UNKNOWN',
    consentPurpose: textOf(item.consentPurpose),
    consentWordingVersion: textOf(item.consentWordingVersion),
    evidenceMethod: textOf(item.evidenceMethod),
    recordedAt: textOf(item.recordedAt)
  };
};

export const toContactPreferences = (value: unknown): LeadContactPreference[] => {
  if (!Array.isArray(value)) throw new Error('Contact preference history is invalid');
  return value.map(toContactPreference);
};

export const toDestinationReveal = (value: unknown): LeadDestinationReveal => {
  const item = recordOf(value);
  if (!item) throw new Error('Destination reveal response is invalid');
  const fullDestination = textOf(item.fullDestination);
  if (!fullDestination) throw new Error('Destination reveal response is invalid');
  return {
    leadId: integerOf(item.leadId, 'reveal.leadId'),
    revision: integerOf(item.revision, 'reveal.revision'),
    fullDestination,
    recordedAt: textOf(item.recordedAt)
  };
};

export const toLeadShortlist = (value: unknown): LeadShortlist => {
  const item = recordOf(value);
  if (!item) throw new Error('Lead shortlist response is invalid');
  return {
    id: integerOf(item.id, 'shortlist.id'),
    version: integerOf(item.version, 'shortlist.version'),
    leadVersion: integerOf(item.leadVersion, 'shortlist.leadVersion'),
    listingIds: integerArray(item.listingIds),
    createdAt: textOf(item.createdAt),
    updatedAt: textOf(item.updatedAt)
  };
};

export const toLeadShare = (value: unknown): LeadShare => {
  const item = recordOf(value);
  if (!item) throw new Error('Prepared share response is invalid');
  const id = textOf(item.id);
  const previewText = textOf(item.previewText);
  if (!id || !previewText) throw new Error('Prepared share response is invalid');
  return {
    id,
    leadId: integerOf(item.leadId, 'share.leadId'),
    shortlistId: integerOf(item.shortlistId, 'share.shortlistId'),
    shortlistVersion: integerOf(item.shortlistVersion, 'share.shortlistVersion'),
    contactPreferenceRevision: integerOf(
      item.contactPreferenceRevision,
      'share.contactPreferenceRevision'
    ),
    mode: textOf(item.mode) ?? 'COPY_LINKS',
    publicLinks: stringArray(item.publicLinks),
    previewText,
    maskedDestination: textOf(item.maskedDestination),
    recordedAt: textOf(item.recordedAt)
  };
};

export const toLeadShareEvent = (value: unknown): LeadShareEvent => {
  const item = recordOf(value);
  if (!item) throw new Error('Share event response is invalid');
  const id = textOf(item.id);
  const shareId = textOf(item.shareId);
  if (!id || !shareId) throw new Error('Share event response is invalid');
  return {
    id,
    shareId,
    type: textOf(item.type) ?? 'UNKNOWN',
    occurredAt: textOf(item.occurredAt)
  };
};

export const toLeadShareHistoryPage = (
  value: unknown,
  fallbackPage = 0,
  fallbackSize = SHARE_HISTORY_PAGE_SIZE
): LeadShareHistoryPage => {
  const envelope = pageEnvelope(value, fallbackPage, fallbackSize, 'Share history');
  return {
    items: envelope.items.map((entry) => {
      const item = recordOf(entry);
      if (!item) throw new Error('Share history item is invalid');
      const id = textOf(item.id);
      const previewText = textOf(item.previewText);
      if (!id || !previewText) throw new Error('Share history item is invalid');
      const events = Array.isArray(item.events)
        ? item.events.map((eventEntry) => {
            const event = recordOf(eventEntry);
            if (!event) throw new Error('Share history event is invalid');
            const eventId = textOf(event.eventId);
            if (!eventId) throw new Error('Share history event is invalid');
            return {
              eventId,
              type: textOf(event.type) ?? 'UNKNOWN',
              occurredAt: textOf(event.occurredAt)
            };
          })
        : [];
      return {
        id,
        shortlistId: integerOf(item.shortlistId, 'history.shortlistId'),
        shortlistVersion: integerOf(item.shortlistVersion, 'history.shortlistVersion'),
        contactPreferenceRevision: integerOf(
          item.contactPreferenceRevision,
          'history.contactPreferenceRevision'
        ),
        mode: textOf(item.mode) ?? 'COPY_LINKS',
        publicLinks: stringArray(item.publicLinks),
        previewText,
        maskedDestination: textOf(item.maskedDestination),
        recordedAt: textOf(item.recordedAt),
        events,
        currentFactsMatch: item.currentFactsMatch === true,
        currentConsentMatches: item.currentConsentMatches === true,
        currentDestinationMatches: item.currentDestinationMatches === true,
        currentAuthorityMatches: item.currentAuthorityMatches === true,
        actionReady: item.actionReady === true
      };
    }),
    totalElements: envelope.totalElements as number,
    page: envelope.page as number,
    size: envelope.size as number,
    totalPages: envelope.totalPages as number
  };
};

export const toCommandReceipt = (value: unknown): LeadCommandReceipt => {
  const item = recordOf(value);
  if (!item) throw new Error('Command receipt response is invalid');
  return {
    state: textOf(item.state) ?? 'UNKNOWN',
    action: textOf(item.action),
    resultReference: textOf(item.resultReference),
    recordedAt: textOf(item.recordedAt)
  };
};

export const toSourceException = (value: unknown): LeadSourceException => {
  const item = recordOf(value);
  if (!item) throw new Error('Source exception response is invalid');
  const eventId = textOf(item.eventId);
  if (!eventId) throw new Error('Source exception response is invalid');
  return {
    eventId,
    eventKind: textOf(item.eventKind) ?? 'UNKNOWN',
    sourceChannel: textOf(item.sourceChannel),
    receivedAt: textOf(item.receivedAt),
    state: textOf(item.state) ?? 'UNKNOWN',
    attempts: integerOf(item.attempts, 'exception.attempts'),
    errorCode: textOf(item.errorCode),
    fingerprintVersion: textOf(item.fingerprintVersion),
    attachedLeadId: optionalInteger(item.attachedLeadId),
    resolutionLeadId: optionalInteger(item.resolutionLeadId),
    extractedPayloadRetained: item.extractedPayloadRetained === true
  };
};

export const toSourceExceptionPage = (
  value: unknown,
  fallbackPage = 0,
  fallbackSize = LEAD_PAGE_SIZE
): LeadSourceExceptionPage => {
  const envelope = pageEnvelope(value, fallbackPage, fallbackSize, 'Source exception page');
  return {
    items: envelope.items.map(toSourceException),
    totalElements: envelope.totalElements as number,
    page: envelope.page as number,
    size: envelope.size as number,
    totalPages: envelope.totalPages as number
  };
};

export const hasLeadWorkspaceCapability = (
  capability: StaffCapabilitySummary
): boolean =>
  (capability.capability === 'OPS_COORDINATE' && capability.scopeType === 'TEAM')
  || (capability.capability === 'OPS_SUPERVISE' && capability.scopeType === 'TEAM')
  || (capability.capability === 'OPS_INTAKE' && capability.scopeType === 'CITY')
  || (capability.capability === 'CITY_TEAM_ADMIN' && capability.scopeType === 'CITY')
  || (capability.capability === 'STAFF_ADMIN' && capability.scopeType === 'GLOBAL');

export const hasLeadWorkspaceAccess = (
  capabilities: StaffCapabilities | null | undefined
): boolean =>
  Boolean(
    capabilities
    && capabilities.staffActive === true
    && Array.isArray(capabilities.capabilities)
    && capabilities.capabilities.some(hasLeadWorkspaceCapability)
  );

export const hasLeadDiagnosticsAccess = (
  capabilities: StaffCapabilities | null | undefined
): boolean =>
  Boolean(
    capabilities?.staffActive === true
    && capabilities.capabilities.some((capability) =>
      capability.capability === 'STAFF_ADMIN' && capability.scopeType === 'GLOBAL')
  );

export const leadScopes = (
  capabilities: StaffCapabilities | null | undefined
): LeadScope[] => {
  if (!capabilities?.staffActive) return [];
  const scopes = new Map<string, LeadScope>();
  capabilities.capabilities.filter(hasLeadWorkspaceCapability).forEach((capability) => {
    if (capability.cityId === null) return;
    const key = `${capability.cityId}:${capability.teamId ?? 'city'}`;
    scopes.set(key, {
      cityId: capability.cityId,
      cityName: capability.cityDisplayName ?? 'City name unavailable',
      teamId: capability.scopeType === 'TEAM' ? capability.teamId : null,
      teamName: capability.scopeType === 'TEAM'
        ? capability.teamDisplayName ?? 'Team name unavailable'
        : null
    });
  });
  return [...scopes.values()].sort((left, right) =>
    left.cityName.localeCompare(right.cityName)
    || (left.teamName ?? '').localeCompare(right.teamName ?? '')
  );
};

export const canCoordinateTeam = (
  capabilities: StaffCapabilities | null | undefined,
  teamId: number | null
): boolean =>
  teamId !== null && Boolean(capabilities?.capabilities.some((capability) =>
    capability.capability === 'OPS_COORDINATE'
    && capability.scopeType === 'TEAM'
    && capability.teamId === teamId
  ));

export const canIntakeCity = (
  capabilities: StaffCapabilities | null | undefined,
  cityId: number | null
): boolean =>
  cityId !== null && Boolean(capabilities?.capabilities.some((capability) =>
    capability.capability === 'OPS_INTAKE'
    && capability.scopeType === 'CITY'
    && capability.cityId === cityId
  ));

export const leadCanEdit = (
  lead: Pick<LeadSummary, 'ownerUserId' | 'teamId' | 'cityId' | 'status'>
    & { provenance?: string | null },
  actorId: number,
  capabilities: StaffCapabilities | null | undefined
): boolean => {
  if (lead.provenance === 'LEGACY_UNVERIFIED') return false;
  if (lead.teamId !== null) {
    return lead.ownerUserId === actorId && canCoordinateTeam(capabilities, lead.teamId);
  }
  return lead.ownerUserId === null && canIntakeCity(capabilities, lead.cityId);
};

export const leadCanRevealContact = (
  lead: Pick<LeadSummary, 'ownerUserId' | 'teamId'>
    & { provenance?: string | null },
  actorId: number,
  capabilities: StaffCapabilities | null | undefined
): boolean =>
  lead.provenance !== 'LEGACY_UNVERIFIED'
  && lead.ownerUserId === actorId
  && lead.teamId !== null
  && canCoordinateTeam(capabilities, lead.teamId);

export const leadActiveForCrmMutation = (
  lead: Pick<LeadSummary, 'status' | 'intent'>
): boolean =>
  !['CLOSED', 'ASSIGNED', 'UNMAPPED', 'CONTACTED', 'STALE'].includes(lead.status);

export const leadShareEligible = (
  lead: LeadDetail,
  actorId: number,
  capabilities: StaffCapabilities | null | undefined
): boolean =>
  leadActiveForCrmMutation(lead)
  && lead.intent !== 'BUY'
  && lead.cityId !== null
  && leadCanRevealContact(lead, actorId, capabilities)
  && leadCanEdit(lead, actorId, capabilities);

export const leadQueueQuery = (
  page: number,
  filters: LeadQueueFilters
): string => {
  const params = new URLSearchParams();
  params.set('page', String(page));
  params.set('size', String(LEAD_PAGE_SIZE));
  if (filters.cityId !== null) params.set('cityId', String(filters.cityId));
  if (filters.teamId !== null) params.set('teamId', String(filters.teamId));
  if (filters.status) params.set('status', filters.status);
  if (filters.ownerMe) params.set('owner', 'me');
  if (filters.q.trim()) params.set('q', filters.q.trim());
  if (filters.followUp !== 'ANY') {
    params.set('followUp', filters.followUp);
    params.set('followUpZone', LEAD_QUEUE_FOLLOW_UP_ZONE);
  }
  return params.toString();
};

export const leadRequirementSummary = (
  lead: Pick<LeadDetail, 'bhk' | 'propertyType' | 'budgetMin' | 'budgetMax' | 'targetMoveInDate'>
): string => {
  const facts: string[] = [];
  if (lead.bhk) facts.push(lead.bhk);
  if (lead.propertyType) facts.push(lead.propertyType);
  if (lead.budgetMin !== null || lead.budgetMax !== null) {
    const min = lead.budgetMin !== null ? `INR ${lead.budgetMin.toLocaleString('en-IN')}` : null;
    const max = lead.budgetMax !== null ? `INR ${lead.budgetMax.toLocaleString('en-IN')}` : null;
    facts.push(min && max ? `${min}–${max}` : min ? `From ${min}` : `Up to ${max}`);
  }
  if (lead.targetMoveInDate) facts.push(`Move-in ${lead.targetMoveInDate}`);
  return facts.join(' · ');
};

const LEAD_QUALIFICATION_GAP_LABELS: Readonly<Record<string, string>> = {
  CONTACT: 'Contact number',
  CITY: 'City',
  BUDGET: 'Budget',
  PROPERTY_TYPE: 'Property type'
};

export const leadQualificationGapLabel = (gap: string): string =>
  LEAD_QUALIFICATION_GAP_LABELS[gap] ?? gap;

export const leadQualificationGapSummary = (gaps: string[]): string => {
  const labels = gaps.map(leadQualificationGapLabel).filter(Boolean);
  if (labels.length <= 1) return labels[0] ?? '';
  if (labels.length === 2) return `${labels[0]} and ${labels[1]}`;
  return `${labels.slice(0, -1).join(', ')} and ${labels[labels.length - 1]}`;
};

export const normalizeIndianMobile = (value: string): string | null => {
  const digits = value.replace(/\D/g, '').replace(/^91(?=\d{10}$)/, '');
  return /^[6-9]\d{9}$/.test(digits) ? `+91${digits}` : null;
};

export const whatsappUrl = (
  destination: string | null | undefined,
  message: string | null | undefined
): string | null => {
  const number = destination?.trim();
  if (!number || !/^\+[1-9]\d{7,14}$/.test(number) || !message?.trim()) return null;
  return `https://wa.me/${number.slice(1)}?text=${encodeURIComponent(message)}`;
};

export const shareLinksText = (links: string[]): string => links.join('\n');

export const sourceRetryAllowed = (
  event: LeadSourceException,
  requiresFreshRead: boolean
): boolean =>
  !requiresFreshRead
  && event.attempts < 3
  && ['PENDING', 'PROCESSING', 'QUARANTINED'].includes(event.state)
  && event.attachedLeadId === null
  && event.resolutionLeadId === null;

export const sourceRetryConflictRequiresRefresh = (
  status: number | null | undefined
): boolean => status === 409;
