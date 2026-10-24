import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  EMPTY_LEAD_QUEUE_FILTERS,
  hasLeadDiagnosticsAccess,
  hasLeadWorkspaceAccess,
  leadCanEdit,
  leadCanRevealContact,
  leadQualificationGapLabel,
  leadQualificationGapSummary,
  leadQueueContextFromSearch,
  leadQueueContextQuery,
  leadQueueQuery,
  leadShareEligible,
  normalizeIndianMobile,
  sourceRetryAllowed,
  sourceRetryConflictRequiresRefresh,
  toLeadDetail,
  toLeadPage,
  toLeadShareHistoryPage,
  whatsappUrl
} from '../utils/operationsLeadPresentation.ts';

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const service = read('../services/operationsLeadService.ts');
const workspace = read('../components/operations/OperationsLeadsWorkspace.tsx');
const consentRecovery = read('../utils/operationsConsentRecovery.ts');

const coordinate = {
  capability: 'OPS_COORDINATE',
  scopeType: 'TEAM',
  cityId: 1,
  cityDisplayName: 'Indore',
  teamId: 8,
  teamDisplayName: 'Team A'
};

const capabilities = (...items) => ({
  staffActive: true,
  capabilities: items,
  serverTime: '2026-10-22T12:00:00Z'
});

const summary = {
  id: 41,
  version: 7,
  customerName: 'Synthetic Customer',
  maskedContact: '********3210',
  status: 'QUALIFYING',
  intent: 'RENT',
  cityId: 1,
  cityName: 'Indore',
  teamId: 8,
  teamName: 'Team A',
  ownerUserId: 90,
  ownerDisplayName: 'Synthetic OE',
  followUpAt: null,
  followUpZoneId: 'Asia/Kolkata',
  lastActivityAt: '2026-10-22T11:00:00Z',
  qualificationGaps: ['BUDGET']
};

test('Lead DTO mapping keeps contract facts and refuses malformed page envelopes', () => {
  const page = toLeadPage({
    items: [{ ...summary, fullContact: '+919876543210', secretNote: 'must not map' }],
    totalElements: 1,
    page: 0,
    size: 20,
    totalPages: 1
  });
  assert.equal(page.items[0].id, 41);
  assert.equal(page.items[0].maskedContact, '********3210');
  assert.equal('fullContact' in page.items[0], false);
  assert.equal('secretNote' in page.items[0], false);
  assert.throws(() => toLeadPage({ totalElements: 0 }), /Lead page/);
  assert.throws(() => toLeadPage({ items: [], totalElements: 'bad' }), /totalElements/);
});

test('Lead detail maps server requirements without inventing missing values', () => {
  const detail = toLeadDetail({
    summary,
    customerSaid: 'Two bedrooms near work',
    propertyType: null,
    bhk: '2 BHK',
    budgetMin: '15000.00',
    budgetMax: null,
    currency: 'INR',
    targetMoveInDate: null,
    preferredLocalityIds: [12, 'bad', 13],
    sourceAttributions: [{
      sourceEventId: 'source-1',
      eventKind: 'MANUAL_PHONE',
      channel: 'PHONE_CALL',
      campaignRef: null,
      referralRef: 'Resident',
      receivedAt: '2026-10-22T10:00:00Z',
      state: 'PROCESSED',
      fingerprintVersion: 'MANUAL_ENQUIRY_ATTACHED_V1',
      manualEnquiry: {
        eventKind: 'MANUAL_PHONE',
        channel: 'PHONE_CALL',
        capturedAt: '2026-10-22T10:00:00Z',
        state: 'PROCESSED',
        snapshotVersion: 'MANUAL_ENQUIRY_ATTACHED_V1',
        protectedPayloadRetained: true
      }
    }],
    communicationPrerequisites: [],
    conversionPrerequisites: ['PROPERTY_TYPE'],
    linkedCustomerAccount: false,
    provenance: 'VERIFIED_MANUAL',
    captureOutcome: {
      resolution: 'ATTACHED_TO_EXISTING',
      leadId: 41,
      leadVersion: 8,
      reasonCode: 'OPERATOR_CONFIRMED_EXISTING_ENQUIRY',
      manualEnquiry: {
        eventKind: 'MANUAL_PHONE',
        channel: 'PHONE_CALL',
        capturedAt: '2026-10-22T10:00:00Z',
        state: 'PROCESSED',
        snapshotVersion: 'MANUAL_ENQUIRY_ATTACHED_V1',
        protectedPayloadRetained: true
      }
    }
  });
  assert.equal(detail.propertyType, null);
  assert.equal(detail.budgetMin, 15000);
  assert.equal(detail.budgetMax, null);
  assert.deepEqual(detail.preferredLocalityIds, [12, 13]);
  assert.equal(detail.sourceAttributions[0].channel, 'PHONE_CALL');
  assert.equal(detail.sourceAttributions[0].manualEnquiry?.protectedPayloadRetained, true);
  assert.equal(detail.captureOutcome?.resolution, 'ATTACHED_TO_EXISTING');
  assert.equal(detail.linkedCustomerAccount, false);
});

test('Lead guidance translates persisted gaps without inventing a follow-up or next action', () => {
  assert.equal(leadQualificationGapLabel('CONTACT'), 'Contact number');
  assert.equal(leadQualificationGapLabel('CITY'), 'City');
  assert.equal(leadQualificationGapLabel('BUDGET'), 'Budget');
  assert.equal(leadQualificationGapLabel('PROPERTY_TYPE'), 'Property type');
  assert.equal(leadQualificationGapLabel('FUTURE_SERVER_GAP'), 'FUTURE_SERVER_GAP');
  assert.equal(
    leadQualificationGapSummary(['BUDGET', 'PROPERTY_TYPE']),
    'Budget and Property type'
  );
  assert.match(workspace, /id="ld-next-title"[^>]*>Next action/);
  assert.match(workspace, /No follow-up scheduled/);
  assert.match(workspace, /Ask on the next call/);
  assert.doesNotMatch(workspace, /setUTC|tomorrow|next week|auto.?follow/i);
});

test('queue page and filters round-trip through privacy-safe search context', () => {
  const query = leadQueueContextQuery(3, {
    q: 'private customer search',
    cityId: 7,
    teamId: 19,
    status: 'QUALIFYING',
    ownerMe: true,
    followUp: 'TODAY'
  });
  assert.equal(query.includes('private'), false);
  assert.deepEqual(leadQueueContextFromSearch(`?${query}`), {
    page: 3,
    filters: {
      q: '',
      cityId: 7,
      teamId: 19,
      status: 'QUALIFYING',
      ownerMe: true,
      followUp: 'TODAY'
    }
  });
  assert.deepEqual(leadQueueContextFromSearch('?page=-4&cityId=private&status=UNKNOWN'), {
    page: 0,
    filters: {
      q: '',
      cityId: null,
      teamId: null,
      status: null,
      ownerMe: false,
      followUp: 'ANY'
    }
  });
});

test('Lead capability boundaries admit CRM roles without weakening visit operations', () => {
  const admin = {
    capability: 'STAFF_ADMIN',
    scopeType: 'GLOBAL',
    cityId: null,
    cityDisplayName: null,
    teamId: null,
    teamDisplayName: null
  };
  const supervisor = { ...coordinate, capability: 'OPS_SUPERVISE' };
  const intake = { ...coordinate, capability: 'OPS_INTAKE', scopeType: 'CITY', teamId: null };
  assert.equal(hasLeadWorkspaceAccess(capabilities(coordinate)), true);
  assert.equal(hasLeadWorkspaceAccess(capabilities(supervisor)), true);
  assert.equal(hasLeadWorkspaceAccess(capabilities(intake)), true);
  assert.equal(hasLeadWorkspaceAccess(capabilities(admin)), true);
  assert.equal(hasLeadWorkspaceAccess(capabilities({
    ...admin,
    capability: 'CITY_TEAM_ADMIN',
    scopeType: 'CITY',
    cityId: 1,
    cityDisplayName: 'Indore'
  })), true);
  assert.equal(hasLeadWorkspaceAccess({
    staffActive: false,
    capabilities: [admin],
    serverTime: null
  }), false);
  assert.equal(hasLeadDiagnosticsAccess(capabilities(admin)), true);
  assert.equal(hasLeadDiagnosticsAccess(capabilities(supervisor)), false);
  assert.equal(
    leadCanEdit({ ...summary, intent: 'RENT', provenance: 'VERIFIED_MANUAL' }, 90,
      capabilities(admin)),
    false
  );
});

test('owner edit, reveal and rental-sharing decisions match server ownership rules', () => {
  const lead = { ...summary, intent: 'RENT', provenance: 'VERIFIED_MANUAL' };
  const caps = capabilities(coordinate);
  assert.equal(leadCanEdit(lead, 90, caps), true);
  assert.equal(leadCanEdit(lead, 91, caps), false);
  assert.equal(leadCanRevealContact(lead, 90, caps), true);
  assert.equal(leadCanRevealContact(lead, 91, caps), false);
  assert.equal(leadShareEligible({ ...lead }, 90, caps), true);
  assert.equal(leadShareEligible({ ...lead, intent: 'BUY' }, 90, caps), false);
  assert.equal(leadShareEligible({ ...lead, status: 'CLOSED' }, 90, caps), false);
  assert.equal(
    leadCanEdit({ ...lead, provenance: 'LEGACY_UNVERIFIED' }, 90, caps),
    false
  );
});

test('queue query carries server search, scope, status and follow-up filters exactly once', () => {
  const query = new URLSearchParams(leadQueueQuery(2, {
    ...EMPTY_LEAD_QUEUE_FILTERS,
    q: '  Green Park  ',
    cityId: 1,
    teamId: 8,
    status: 'QUALIFYING',
    ownerMe: true,
    followUp: 'OVERDUE'
  }));
  assert.equal(query.get('page'), '2');
  assert.equal(query.get('size'), '20');
  assert.equal(query.get('q'), 'Green Park');
  assert.equal(query.get('cityId'), '1');
  assert.equal(query.get('teamId'), '8');
  assert.equal(query.get('status'), 'QUALIFYING');
  assert.equal(query.get('owner'), 'me');
  assert.equal(query.get('followUp'), 'OVERDUE');
  assert.equal(query.get('followUpZone'), 'Asia/Kolkata');
  assert.equal(query.get('followUpBefore'), null);
  assert.equal([...query.keys()].filter((key) => key === 'page').length, 1);
  assert.equal([...query.keys()].filter((key) => key === 'q').length, 1);
});

test('WhatsApp URLs use the exact confirmed E.164 destination and encode the snapshot', () => {
  const url = whatsappUrl('+919876543210', 'Pathome home\nhttps://pathome.test/property/9');
  assert.match(url, /^https:\/\/wa\.me\/919876543210\?text=/);
  assert.match(url, /Pathome%20home%0Ahttps/);
  assert.equal(whatsappUrl('9876543210', 'message'), null);
  assert.equal(whatsappUrl('+919876543210', ''), null);
  assert.equal(normalizeIndianMobile('098765 43210'), null);
  assert.equal(normalizeIndianMobile('+91 98765 43210'), '+919876543210');
});

test('share history maps immutable snapshots, ordered events and current eligibility flags', () => {
  const page = toLeadShareHistoryPage({
    items: [{
      id: '11111111-1111-4111-8111-111111111111',
      shortlistId: 7,
      shortlistVersion: 2,
      contactPreferenceRevision: 4,
      mode: 'MANUAL_BUSINESS_HANDOFF',
      publicLinks: ['https://pathome.test/property/9'],
      previewText: 'Stored exact preview',
      maskedDestination: '********3210',
      recordedAt: '2026-10-22T10:00:00Z',
      events: [{
        eventId: '22222222-2222-4222-8222-222222222222',
        type: 'HANDOFF_ATTEMPTED',
        occurredAt: '2026-10-22T10:01:00Z'
      }],
      currentFactsMatch: false,
      currentConsentMatches: true,
      currentDestinationMatches: false,
      currentAuthorityMatches: true,
      actionReady: false,
      providerDeliveryStatus: 'DELIVERED'
    }],
    totalElements: 1,
    page: 0,
    size: 10,
    totalPages: 1
  });
  assert.equal(page.items[0].previewText, 'Stored exact preview');
  assert.equal(page.items[0].events[0].type, 'HANDOFF_ATTEMPTED');
  assert.equal(page.items[0].currentFactsMatch, false);
  assert.equal(page.items[0].currentAuthorityMatches, true);
  assert.equal(page.items[0].currentDestinationMatches, false);
  assert.equal(page.items[0].actionReady, false);
  assert.equal('providerDeliveryStatus' in page.items[0], false);
});

test('source retry is blocked for exhausted, resolved or refresh-required events', () => {
  const event = {
    eventId: '33333333-3333-4333-8333-333333333333',
    eventKind: 'META_LEADGEN',
    sourceChannel: 'META',
    receivedAt: '2026-10-22T10:00:00Z',
    state: 'QUARANTINED',
    attempts: 1,
    errorCode: 'SOURCE_EXTRACTION_FAILED',
    fingerprintVersion: 'v1',
    attachedLeadId: null,
    resolutionLeadId: null,
    extractedPayloadRetained: true
  };
  assert.equal(sourceRetryAllowed(event, false), true);
  assert.equal(sourceRetryAllowed(event, true), false);
  assert.equal(sourceRetryAllowed({ ...event, attempts: 3 }, false), false);
  assert.equal(sourceRetryAllowed({ ...event, state: 'AWAITING_REVIEW' }, false), false);
  assert.equal(sourceRetryAllowed({ ...event, resolutionLeadId: 5 }, false), false);
  assert.equal(sourceRetryConflictRequiresRefresh(409), true);
  assert.equal(sourceRetryConflictRequiresRefresh(503), false);
});

test('production service binds only accepted routes and idempotency headers', () => {
  assert.match(service, /\/operations\/leads\?\$\{leadQueueQuery/);
  assert.match(service, /headers: \{ 'Idempotency-Key': operationId \}/);
  assert.match(service, /\/operations\/leads\/\$\{leadId\}\/shares\?page=\$\{page\}&size=\$\{size\}/);
  assert.match(service, /\/operations\/lead-commands\/\$\{operationId\}/);
  assert.match(service, /recordContactPreference[\s\S]*headers: \{ 'Idempotency-Key': operationId \}/);
  assert.match(service, /cache: 'no-store'/);
  assert.doesNotMatch(service, /auto.?send|providerDeliveryStatus|DELIVERED|READ/);
});

test('consent recovery distinguishes exact receipt, current truth and unknown outcomes', () => {
  assert.match(consentRecovery,
    /\^preference:\\d\{1,18\}:revision:\(\\d\{1,18\}\):leadVersion:\\d\{1,18\}\$/);
  assert.match(consentRecovery, /pathome_oe_consent_v1/);
  assert.doesNotMatch(consentRecovery,
    /destinationCiphertext|evidenceMethod|countryCallingCode|destinationNumber/);
  assert.match(workspace, /LEAD_CONTACT_PREFERENCE_RECORD/);
  assert.match(workspace, /Pathome confirmed that the customer has agreed to receive property links/);
  assert.match(workspace, /permission service is unavailable/);
  assert.match(workspace, /consentOutcomeIsUnconfirmed/);
  assert.match(workspace, /status >= 200 && status < 300/);
  assert.match(workspace, /Check saved request again/);
  assert.match(workspace, /Continue with saved permission/);
  assert.doesNotMatch(workspace, /The consent reply was lost\./);
});

test('connected screens contain no synthetic fixtures or simulated mutations', () => {
  assert.doesNotMatch(workspace, /INITIAL_LEADS|PROPERTIES|PERSONAS|PH-1228|Synthetic test control/);
  assert.match(workspace, /target="_blank"/);
  assert.match(workspace, /rel="noopener noreferrer"/);
  assert.match(workspace, /openWhatsAppConfirmation/);
  assert.match(workspace, /navigator\.clipboard\.writeText/);
  assert.match(workspace, /currentFactsMatch/);
  assert.match(workspace, /currentConsentMatches/);
  assert.match(workspace, /currentAuthorityMatches/);
  assert.match(workspace, /draftGeneration/);
  assert.match(workspace, /searchGeneration/);
  assert.match(workspace, /reconcilePendingEvent/);
  assert.match(workspace, /window\.open\('', '_blank'\)/);
  assert.match(workspace, /target\.opener = null/);
  assert.match(workspace, /target\.location\.replace\(waUrl\)/);
  assert.match(workspace, /preparedShareHistoryFromHistory/);
  assert.doesNotMatch(workspace, /revalidatePreparedShare/);
  assert.doesNotMatch(workspace, /href=\{waUrl\}/);
  assert.match(workspace, /Save permission and continue/);
  assert.match(workspace, /load\(initialContext\.page, initialContext\.filters\)/);
  assert.doesNotMatch(workspace, /initialLoad/);
  assert.doesNotMatch(workspace, /useEffect\(\(\) => \(\) => \{\s*mounted\.current = false/);
  assert.match(workspace, /mounted\.current = true/);
  assert.doesNotMatch(workspace, /Attach-to-existing is not available/);
  assert.doesNotMatch(workspace, /event\.eventId\} retry completed/);
});
