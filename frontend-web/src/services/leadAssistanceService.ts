import { API_ROOT_URL } from '../config/endpoints';
import { ApiRequestError, createApiRequestError } from './apiError';
import {
  captureOperationsSession,
  isOperationsSessionActive,
  OperationsSessionChangedError,
  type OperationsSession
} from '../utils/operationsSession';
import type { Property } from '../types';

export interface LeadRequestContext {
  signal?: AbortSignal;
  session?: OperationsSession;
  onDispatch?: () => void;
}

export type PhoneState = 'PENDING' | 'VERIFIED' | 'EXPIRED' | 'EXHAUSTED' | 'CANCELLED';
export type DeliveryState = 'QUEUED' | 'ACCEPTED' | 'DELIVERED' | 'FAILED' | 'UNKNOWN' | 'SUPPRESSED';

export interface PhoneVerification {
  challengeId: string;
  leadId: number;
  state: PhoneState;
  deliveryState: DeliveryState;
  maskedDestination: string | null;
  failedAttempts: number;
  maxFailedAttempts: number;
  generation: number;
  challengeVersion: number;
  contactPhoneRevision: number;
  boundContactPhoneRevision: number;
  currentProofUsable: boolean;
  proofUsableUntil: string | null;
  createdAt: string;
  expiresAt: string;
  verifiedAt: string | null;
  entryMethod: 'CUSTOMER_FORM' | 'OE_ENTERED' | null;
  message: string;
}

export interface PhoneConfirmation {
  state: PhoneState;
  deliveryState: DeliveryState;
  maskedDestination: string | null;
  attemptsRemaining: number;
  expiresAt: string;
  message: string;
}

export type AvailabilityOutcome =
  | 'PENDING'
  | 'NO_ANSWER'
  | 'LESSOR_REPORTED_AVAILABLE'
  | 'LESSOR_REPORTED_UNAVAILABLE'
  | 'CALLBACK_REQUESTED'
  | 'CONTACT_UNUSABLE'
  | 'CUSTOMER_WITHDREW'
  | 'CUSTOMER_DISPUTED'
  | 'PROPERTY_DECLINED';

export interface AvailabilityEnquiry {
  enquiryId: string;
  leadId: number;
  shortlistId: number;
  listingId: number;
  sourceEventId: string;
  outcome: AvailabilityOutcome;
  createdAt: string;
  expiresAt: string;
  reportedAt: string | null;
  version: number;
  leadVersion: number;
  shortlistVersion: number;
  listingVersion: number;
  contactPhoneRevision: number;
  contactRevealEligible: boolean;
  phoneVerificationState: string;
  message: string;
}

export interface LessorContact {
  listingId: number;
  name: string | null;
  mobile: string | null;
  status: 'CONTACT_AVAILABLE' | 'CONTACT_UNAVAILABLE';
}

export interface VisitProposal {
  proposalId: string;
  leadId: number;
  listingId: number;
  state: 'OPEN' | 'EXPIRED' | 'REVOKED' | 'SUPERSEDED';
  associationStatus?: 'PENDING' | 'CONFIRMED' | 'DISPUTED' | 'DECLINED' | 'WITHDRAWN' | 'UNAVAILABLE';
  associationClaims: Array<{
    claimId: string;
    requestId: number;
    state: AssociationClaim['state'];
    version: number;
    createdAt: string;
  }>;
  expiresAt: string;
  version: number;
  handoffUrl?: string | null;
}

export interface VisitHandoff {
  contextId: string;
  proposalId: string;
  expiresAt: string;
  property: {
    id: number;
    title: string;
    location: string | null;
    bhk: string | null;
    monthlyRent: number | null;
    version: number;
  };
}

export interface OwnVisitRequestResult {
  requestId: number;
  propertyId: number;
  requestVersion: number | null;
  status: string;
  created: boolean;
  requiresAcknowledgement: boolean;
  associationStatus: 'PENDING' | 'CONFIRMED' | 'DISPUTED' | 'DECLINED' | 'WITHDRAWN' | 'UNAVAILABLE';
  message: string;
  receivedAt: string;
}

export interface VisitCustomerResponseResult {
  responseId: string;
  proposalId: string;
  response: 'DECLINED' | 'REQUEST_RECORDED';
  requestId: number | null;
  associationStatus: OwnVisitRequestResult['associationStatus'];
  recordedAt: string;
  version: number;
  message: string;
}

export interface AssociationClaim {
  claimId: string;
  proposalId: string;
  requestId: number;
  state: 'PENDING' | 'CONFIRMED' | 'DISPUTED' | 'DECLINED' | 'WITHDRAWN' | 'UNAVAILABLE';
  createdAt: string;
  decidedAt: string | null;
  version: number;
  message: string;
}

export interface AssistanceCommandStatus {
  state: 'NOT_RECORDED' | 'RECORDED';
  action: string | null;
  resultReference: string | null;
  completedAt: string | null;
}

const requireSession = (operation: string): OperationsSession => {
  const session = captureOperationsSession();
  if (!session) throw new ApiRequestError(`Sign in to ${operation}.`, 401);
  return session;
};

const sendAuthenticated = async <T>(
  path: string,
  options: {
    method?: 'GET' | 'POST';
    body?: unknown;
    headers?: Record<string, string>;
    fallback: string;
    context?: LeadRequestContext;
    mapper: (value: unknown) => T;
  }
): Promise<T> => {
  const session = options.context?.session ?? requireSession('continue');
  if (!isOperationsSessionActive(session)) throw new OperationsSessionChangedError();
  options.context?.onDispatch?.();
  const response = await fetch(`${API_ROOT_URL}${path}`, {
    method: options.method ?? 'GET',
    credentials: 'include',
    headers: {
      Authorization: `Bearer ${session.token}`,
      Accept: 'application/json',
      ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...options.headers
    },
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    cache: 'no-store',
    signal: options.context?.signal
  });
  if (!response.ok) {
    throw await createApiRequestError(response, options.fallback, false, session.token);
  }
  const parsed = options.mapper(await response.json());
  if (!isOperationsSessionActive(session)) throw new OperationsSessionChangedError();
  return parsed;
};

const sendPublic = async <T>(
  path: string,
  body: unknown,
  fallback: string,
  mapper: (value: unknown) => T,
  signal?: AbortSignal
): Promise<T> => {
  const response = await fetch(`${API_ROOT_URL}${path}`, {
    method: 'POST',
    credentials: 'include',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    cache: 'no-store',
    signal
  });
  if (!response.ok) throw await createApiRequestError(response, fallback, false);
  return mapper(await response.json());
};

const object = (value: unknown): Record<string, unknown> => {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error('The server response was malformed.');
  }
  return value as Record<string, unknown>;
};
const text = (value: unknown): string => {
  if (typeof value !== 'string') throw new Error('The server response was malformed.');
  return value;
};
const optionalText = (value: unknown): string | null =>
  value === null || value === undefined ? null : text(value);
const integer = (value: unknown): number => {
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) {
    throw new Error('The server response was malformed.');
  }
  return value;
};
const optionalNumber = (value: unknown): number | null => {
  if (value === null || value === undefined) return null;
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new Error('The server response was malformed.');
  }
  return value;
};
const boolean = (value: unknown): boolean => {
  if (typeof value !== 'boolean') throw new Error('The server response was malformed.');
  return value;
};

const phoneVerification = (value: unknown): PhoneVerification => {
  const item = object(value);
  return {
    challengeId: text(item.challengeId),
    leadId: integer(item.leadId),
    state: text(item.state) as PhoneState,
    deliveryState: text(item.deliveryState) as DeliveryState,
    maskedDestination: optionalText(item.maskedDestination),
    failedAttempts: integer(item.failedAttempts),
    maxFailedAttempts: integer(item.maxFailedAttempts),
    generation: integer(item.generation),
    challengeVersion: integer(item.challengeVersion),
    contactPhoneRevision: integer(item.contactPhoneRevision),
    boundContactPhoneRevision: integer(item.boundContactPhoneRevision),
    currentProofUsable: boolean(item.currentProofUsable),
    proofUsableUntil: optionalText(item.proofUsableUntil),
    createdAt: text(item.createdAt),
    expiresAt: text(item.expiresAt),
    verifiedAt: optionalText(item.verifiedAt),
    entryMethod: optionalText(item.entryMethod) as PhoneVerification['entryMethod'],
    message: text(item.message)
  };
};

const phoneConfirmation = (value: unknown): PhoneConfirmation => {
  const item = object(value);
  return {
    state: text(item.state) as PhoneState,
    deliveryState: text(item.deliveryState) as DeliveryState,
    maskedDestination: optionalText(item.maskedDestination),
    attemptsRemaining: integer(item.attemptsRemaining),
    expiresAt: text(item.expiresAt),
    message: text(item.message)
  };
};

const availability = (value: unknown): AvailabilityEnquiry => {
  const item = object(value);
  return {
    enquiryId: text(item.enquiryId),
    leadId: integer(item.leadId),
    shortlistId: integer(item.shortlistId),
    listingId: integer(item.listingId),
    sourceEventId: text(item.sourceEventId),
    outcome: text(item.outcome) as AvailabilityOutcome,
    createdAt: text(item.createdAt),
    expiresAt: text(item.expiresAt),
    reportedAt: optionalText(item.reportedAt),
    version: integer(item.version),
    leadVersion: integer(item.leadVersion),
    shortlistVersion: integer(item.shortlistVersion),
    listingVersion: integer(item.listingVersion),
    contactPhoneRevision: integer(item.contactPhoneRevision),
    contactRevealEligible: boolean(item.contactRevealEligible),
    phoneVerificationState: text(item.phoneVerificationState),
    message: text(item.message)
  };
};

const lessorContact = (value: unknown): LessorContact => {
  const item = object(value);
  return {
    listingId: integer(item.listingId),
    name: optionalText(item.name),
    mobile: optionalText(item.mobile),
    status: text(item.status) as LessorContact['status']
  };
};

const proposal = (value: unknown): VisitProposal => {
  const item = object(value);
  const claims = Array.isArray(item.associationClaims)
    ? item.associationClaims.map(entry => {
        const claim = object(entry);
        return {
          claimId: text(claim.claimId),
          requestId: integer(claim.requestId),
          state: text(claim.state) as AssociationClaim['state'],
          version: integer(claim.version),
          createdAt: text(claim.createdAt)
        };
      })
    : [];
  return {
    proposalId: text(item.proposalId),
    leadId: integer(item.leadId),
    listingId: integer(item.listingId),
    state: text(item.state) as VisitProposal['state'],
    associationStatus: optionalText(item.associationStatus) as VisitProposal['associationStatus'],
    associationClaims: claims,
    expiresAt: text(item.expiresAt),
    version: integer(item.version),
    handoffUrl: optionalText(item.handoffUrl)
  };
};

const handoff = (value: unknown): VisitHandoff => {
  const item = object(value);
  const property = object(item.property);
  return {
    contextId: text(item.contextId),
    proposalId: text(item.proposalId),
    expiresAt: text(item.expiresAt),
    property: {
      id: integer(property.id),
      title: text(property.title),
      location: optionalText(property.location),
      bhk: optionalText(property.bhk),
      monthlyRent: optionalNumber(property.monthlyRent),
      version: integer(property.version)
    }
  };
};

const ownRequest = (value: unknown): OwnVisitRequestResult => {
  const item = object(value);
  const requiresAcknowledgement = boolean(item.requiresAcknowledgement);
  const requestVersion = optionalNumber(item.requestVersion);
  if (requiresAcknowledgement && requestVersion == null) {
    throw new Error('The current Visit Request version was not returned. Refresh before continuing.');
  }
  return {
    requestId: integer(item.requestId),
    propertyId: integer(item.propertyId),
    requestVersion,
    status: text(item.status),
    created: boolean(item.created),
    requiresAcknowledgement,
    associationStatus: text(item.associationStatus) as OwnVisitRequestResult['associationStatus'],
    message: text(item.message),
    receivedAt: text(item.receivedAt)
  };
};

const visitCustomerResponse = (value: unknown): VisitCustomerResponseResult => {
  const item = object(value);
  const response = text(item.response);
  if (response !== 'DECLINED' && response !== 'REQUEST_RECORDED') {
    throw new Error('The Visit response was malformed.');
  }
  return {
    responseId: text(item.responseId),
    proposalId: text(item.proposalId),
    response,
    requestId: item.requestId === null || item.requestId === undefined
      ? null : integer(item.requestId),
    associationStatus: text(item.associationStatus) as VisitCustomerResponseResult['associationStatus'],
    recordedAt: text(item.recordedAt),
    version: integer(item.version),
    message: text(item.message)
  };
};

const associationClaim = (value: unknown): AssociationClaim => {
  const item = object(value);
  return {
    claimId: text(item.claimId),
    proposalId: text(item.proposalId),
    requestId: integer(item.requestId),
    state: text(item.state) as AssociationClaim['state'],
    createdAt: text(item.createdAt),
    decidedAt: optionalText(item.decidedAt),
    version: integer(item.version),
    message: text(item.message)
  };
};

const commandStatus = (value: unknown): AssistanceCommandStatus => {
  const item = object(value);
  const state = text(item.state);
  if (state !== 'NOT_RECORDED' && state !== 'RECORDED') {
    throw new Error('The command status response was malformed.');
  }
  return {
    state,
    action: optionalText(item.action),
    resultReference: optionalText(item.resultReference),
    completedAt: optionalText(item.completedAt)
  };
};

export const newClientOperationId = (): string => {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
  if (typeof crypto === 'undefined' || !crypto.getRandomValues) {
    throw new Error('This browser cannot create a secure command identifier.');
  }
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
};

export const leadAssistanceService = {
  latestPhone(leadId: number, context: LeadRequestContext = {}): Promise<PhoneVerification> {
    return sendAuthenticated(`/operations/leads/${leadId}/phone-verifications/latest`, {
      fallback: 'Phone verification status could not be loaded.',
      context,
      mapper: phoneVerification
    });
  },

  phoneCommandStatus(
    leadId: number,
    operationId: string,
    action: 'LEAD_PHONE_ISSUE' | 'LEAD_PHONE_RESEND' | 'LEAD_PHONE_CONFIRM',
    context: LeadRequestContext = {}
  ): Promise<AssistanceCommandStatus> {
    return sendAuthenticated(
      `/operations/leads/${leadId}/phone-verifications/commands/${operationId}?action=${encodeURIComponent(action)}`,
      {
        fallback: 'The earlier phone-verification command could not be checked.',
        context,
        mapper: commandStatus
      }
    );
  },

  issuePhone(
    leadId: number,
    command: {
      expectedLeadVersion: number;
      sourceEvent: { id: string };
      expectedContactPhoneRevision: number;
      noticeAgreed: boolean;
    },
    operationId: string,
    context: LeadRequestContext = {}
  ): Promise<PhoneVerification> {
    return sendAuthenticated(`/operations/leads/${leadId}/phone-verifications`, {
      method: 'POST',
      body: command,
      headers: { 'Idempotency-Key': operationId },
      fallback: 'The enquiry code could not be requested. The Lead remains saved.',
      context,
      mapper: phoneVerification
    });
  },

  resendPhone(
    leadId: number,
    expectedLeadVersion: number,
    verification: PhoneVerification,
    operationId: string,
    context: LeadRequestContext = {}
  ): Promise<PhoneVerification> {
    return sendAuthenticated(
      `/operations/leads/${leadId}/phone-verifications/${verification.challengeId}/resend`,
      {
        method: 'POST',
        body: {
          expectedLeadVersion,
          expectedChallengeVersion: verification.challengeVersion,
          expectedGeneration: verification.generation,
          noticeAgreed: true
        },
        headers: { 'Idempotency-Key': operationId },
        fallback: 'The enquiry code could not be resent.',
        context,
        mapper: phoneVerification
      }
    );
  },

  confirmPhoneAsOperator(
    leadId: number,
    expectedLeadVersion: number,
    verification: PhoneVerification,
    code: string,
    operationId: string,
    context: LeadRequestContext = {}
  ): Promise<PhoneVerification> {
    return sendAuthenticated(
      `/operations/leads/${leadId}/phone-verifications/${verification.challengeId}/confirm`,
      {
        method: 'POST',
        body: {
          expectedLeadVersion,
          expectedChallengeVersion: verification.challengeVersion,
          code
        },
        headers: { 'Idempotency-Key': operationId },
        fallback: 'The enquiry code could not be confirmed.',
        context,
        mapper: phoneVerification
      }
    );
  },

  exchangePhoneToken(token: string, signal?: AbortSignal): Promise<PhoneConfirmation> {
    return sendPublic('/lead-phone-verifications/exchange', { token },
      'This enquiry confirmation link is unavailable.', phoneConfirmation, signal);
  },

  confirmPhoneToken(token: string, code: string): Promise<PhoneConfirmation> {
    return sendPublic('/lead-phone-verifications/confirm', { token, code },
      'The enquiry code could not be confirmed.', phoneConfirmation);
  },

  createAvailability(
    leadId: number,
    command: {
      shortlistId: number;
      listingId: number;
      sourceEventId: string;
      expectedLeadVersion: number;
      expectedShortlistVersion: number;
      expectedListingVersion: number;
      genuineEnquiryAttested: boolean;
    },
    operationId: string,
    context: LeadRequestContext = {}
  ): Promise<AvailabilityEnquiry> {
    return sendAuthenticated(`/operations/leads/${leadId}/availability-enquiries`, {
      method: 'POST',
      body: command,
      headers: { 'Idempotency-Key': operationId },
      fallback: 'The availability enquiry could not be saved.',
      context,
      mapper: availability
    });
  },

  getAvailability(
    leadId: number,
    enquiryId: string,
    context: LeadRequestContext = {}
  ): Promise<AvailabilityEnquiry> {
    return sendAuthenticated(
      `/operations/leads/${leadId}/availability-enquiries/${enquiryId}`,
      {
        fallback: 'The availability enquiry could not be loaded.',
        context,
        mapper: availability
      }
    );
  },

  availabilityCommandStatus(
    operationId: string,
    action: 'LEAD_AVAILABILITY_CREATE' | 'LEAD_AVAILABILITY_RESPONSE',
    context: LeadRequestContext = {}
  ): Promise<AssistanceCommandStatus> {
    return sendAuthenticated(
      `/operations/lead-assistance-commands/${operationId}?action=${encodeURIComponent(action)}`,
      {
        fallback: 'The earlier availability command could not be checked.',
        context,
        mapper: commandStatus
      }
    );
  },

  revealLessorContact(
    leadId: number,
    enquiry: AvailabilityEnquiry,
    context: LeadRequestContext = {}
  ): Promise<LessorContact> {
    return sendAuthenticated(
      `/operations/leads/${leadId}/availability-enquiries/${enquiry.enquiryId}/lessor-contact`,
      {
        method: 'POST',
        body: {
          expectedLeadVersion: enquiry.leadVersion,
          expectedShortlistVersion: enquiry.shortlistVersion,
          expectedListingVersion: enquiry.listingVersion,
          expectedEnquiryVersion: enquiry.version,
          expectedContactPhoneRevision: enquiry.contactPhoneRevision,
          genuineEnquiryAttested: true
        },
        fallback: 'Lessor contact could not be disclosed.',
        context,
        mapper: lessorContact
      }
    );
  },

  recordAvailabilityOutcome(
    leadId: number,
    enquiry: AvailabilityEnquiry,
    expectedLeadVersion: number,
    outcome: AvailabilityOutcome,
    note: string | null,
    operationId: string,
    context: LeadRequestContext = {}
  ): Promise<AvailabilityEnquiry> {
    return sendAuthenticated(
      `/operations/leads/${leadId}/availability-enquiries/${enquiry.enquiryId}/responses`,
      {
        method: 'POST',
        body: { expectedLeadVersion, expectedEnquiryVersion: enquiry.version, outcome, note },
        headers: { 'Idempotency-Key': operationId },
        fallback: 'The reported availability outcome could not be saved.',
        context,
        mapper: availability
      }
    );
  },

  createProposal(
    leadId: number,
    command: {
      expectedLeadVersion: number;
      shortlistId: number;
      listingId: number;
      expectedShortlistVersion: number;
      expectedListingVersion: number;
      contactPreferenceRevision: number;
    },
    operationId: string,
    context: LeadRequestContext = {}
  ): Promise<VisitProposal> {
    return sendAuthenticated(`/operations/leads/${leadId}/visit-proposals`, {
      method: 'POST',
      body: command,
      headers: { 'Idempotency-Key': operationId },
      fallback: 'The customer Visit invitation could not be prepared.',
      context,
      mapper: proposal
    });
  },

  getProposal(
    leadId: number,
    proposalId: string,
    context: LeadRequestContext = {}
  ): Promise<VisitProposal> {
    return sendAuthenticated(`/operations/leads/${leadId}/visit-proposals/${proposalId}`, {
      fallback: 'The customer Visit invitation could not be loaded.',
      context,
      mapper: proposal
    });
  },

  proposalCommandStatus(
    leadId: number,
    operationId: string,
    action: 'LEAD_VISIT_PROPOSAL_CREATE' | 'LEAD_VISIT_PROPOSAL_REVOKE',
    context: LeadRequestContext = {}
  ): Promise<AssistanceCommandStatus> {
    return sendAuthenticated(
      `/operations/leads/${leadId}/visit-proposals/commands/${operationId}?action=${encodeURIComponent(action)}`,
      {
        fallback: 'The earlier Visit-invitation command could not be checked.',
        context,
        mapper: commandStatus
      }
    );
  },

  revokeProposal(
    leadId: number,
    proposalId: string,
    operationId: string,
    context: LeadRequestContext = {}
  ): Promise<VisitProposal> {
    return sendAuthenticated(
      `/operations/leads/${leadId}/visit-proposals/${proposalId}/revoke`,
      {
        method: 'POST',
        headers: { 'Idempotency-Key': operationId },
        fallback: 'The customer Visit invitation could not be revoked.',
        context,
        mapper: proposal
      }
    );
  },

  decideAssociation(
    leadId: number,
    proposal: VisitProposal,
    claimId: string,
    expectedClaimVersion: number,
    decision: 'CONFIRM' | 'DISPUTE',
    reasonCode: string,
    operationId: string,
    context: LeadRequestContext = {}
  ): Promise<AssociationClaim> {
    return sendAuthenticated(
      `/operations/leads/${leadId}/visit-proposals/${proposal.proposalId}/association-claims/${claimId}/decision`,
      {
        method: 'POST',
        body: {
          decision,
          expectedClaimVersion,
          reasonCode
        },
        headers: { 'Idempotency-Key': operationId },
        fallback: 'The enquiry association decision could not be saved.',
        context,
        mapper: associationClaim
      }
    );
  },

  exchangeVisitToken(token: string, signal?: AbortSignal): Promise<VisitHandoff> {
    return sendPublic('/lead-visit-handoffs/exchange', { token },
      'This Visit invitation is no longer available.', handoff, signal);
  },

  async currentVisitContext(signal?: AbortSignal): Promise<VisitHandoff | null> {
    const response = await fetch(`${API_ROOT_URL}/lead-visit-handoffs/current`, {
      credentials: 'include',
      headers: { Accept: 'application/json' },
      cache: 'no-store',
      signal
    });
    if (response.status === 404) return null;
    if (!response.ok) {
      throw await createApiRequestError(response, 'The Visit invitation context could not be loaded.');
    }
    return handoff(await response.json());
  },

  createOwnVisitRequest(
    visitor: Property,
    request: {
      budgetMin?: number;
      budgetMax?: number;
      preferredAreas?: string;
      moveInTiming?: string;
      preferredVisitTiming: string;
      availabilityStartAt?: string | null;
      availabilityEndAt?: string | null;
      availabilityZoneId?: string | null;
      preferredAt?: string | null;
      note?: string;
    },
    options: {
      signal?: AbortSignal;
      session: OperationsSession;
      handoffContext?: VisitHandoff | null;
      associateEnquiry?: boolean;
      consentWordingVersion?: string | null;
      acknowledgeExistingRequest?: boolean;
      expectedRequestVersion?: number | null;
      operationId?: string;
      onDispatch?: () => void;
    }
  ): Promise<OwnVisitRequestResult> {
    const operationId = options.operationId ?? newClientOperationId();
    return sendAuthenticated('/tenant/property-visit-commands', {
      method: 'POST',
      body: {
        operationId,
        listingId: visitor.id,
        expectedListingVersion: visitor.listingVersion ?? null,
        expectedRequestVersion: options.expectedRequestVersion ?? null,
        explicitVisitIntent: true,
        acknowledgeExistingRequest: Boolean(options.acknowledgeExistingRequest),
        handoffContextId: options.handoffContext?.contextId ?? null,
        associateEnquiry: Boolean(options.associateEnquiry),
        consentWordingVersion: options.associateEnquiry
          ? options.consentWordingVersion ?? null
          : null,
        request
      },
      fallback: 'Visit Request could not be recorded.',
      context: {
        session: options.session,
        signal: options.signal,
        onDispatch: options.onDispatch
      },
      mapper: ownRequest
    });
  },

  respondToVisitProposal(
    contextId: string,
    command: {
      response: 'DECLINED' | 'REQUEST_RECORDED';
      requestId: number | null;
      expectedResponseVersion: number | null;
      associateEnquiry: boolean;
      explicitEnquiryOwnershipConsent: boolean;
      consentWordingVersion: string | null;
    },
    session: OperationsSession,
    signal?: AbortSignal
  ): Promise<VisitCustomerResponseResult> {
    return sendAuthenticated(`/tenant/lead-visit-proposals/${contextId}/responses`, {
      method: 'POST',
      body: command,
      fallback: 'Your response to this Visit invitation could not be saved.',
      context: { session, signal },
      mapper: visitCustomerResponse
    });
  },

  ownVisitCommandStatus(
    operationId: string,
    session: OperationsSession,
    signal?: AbortSignal
  ): Promise<AssistanceCommandStatus> {
    return sendAuthenticated(`/tenant/property-visit-commands/${operationId}`, {
      fallback: 'The earlier Visit Request command could not be checked.',
      context: { session, signal },
      mapper: commandStatus
    });
  }
};
