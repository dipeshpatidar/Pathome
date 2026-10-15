import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  attentionCategory,
  claimCommandFor,
  formatOperationsDate,
  formatOperationsDateTime,
  formatOperationsDetailDateTime,
  formatOperationsDuration,
  formatStoredLocalDateTime,
  hasOperationsWorkspaceAccess,
  isOwnedRequestReleasable,
  isOwnedSessionReleasable,
  linkedRequestVersionMap,
  needsCoordinatorAttention,
  operationsAvailabilityMeta,
  operationsCapabilityScopes,
  operationsDetailFailureState,
  operationsLoadFailureState,
  operationsRecordTitle,
  operationsStatusMeta,
  parseWorkPage,
  releaseCommandForRequest,
  releaseCommandForSession,
  tenantConfirmationLabel,
  toClaimableWorkItem,
  toMyWorkItem,
  toRequestDetail,
  toSessionDetail,
  toStaffCapabilities
} from '../utils/operationsWorkspacePresentation.ts';
/** The same module as a namespace, for behaviour assertions that need helpers by name. */
import * as presentationModule from '../utils/operationsWorkspacePresentation.ts';

/**
 * Scope of this file: pure contract/mapping tests plus a small number of structural regression
 * guards on the OE modules.
 *
 * Runtime behaviour is deliberately NOT claimed from source text here. The security-critical
 * behaviour is proven by driving the real service and the real UI:
 *   - `operationsWorkspaceSession.test.mjs` — the cross-account claim race, stale-response rejection,
 *     logout/unmount during a mutation, exact command payloads, HTTP 204 handling.
 *   - `browser-verification/operations-workspace/` — capability gating in both consoles, runtime
 *     revocation and recovery, persistent conflict feedback, hit targets and responsive layout, all
 *     against the built app with deliberately delayed API responses.
 */

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const presentation = read('../utils/operationsWorkspacePresentation.ts');
const service = read('../services/operationsWorkspaceService.ts');
const workspace = read('../components/operations/OperationsWorkspace.tsx');
const detail = read('../components/operations/OperationsWorkDetail.tsx');
const lists = read('../components/operations/OperationsWorkLists.tsx');
const primitives = read('../components/operations/OperationsWorkspacePrimitives.tsx');
const itinerary = read('../components/operations/OperationsDraftItinerary.tsx');
const dashboard = read('../components/EmployeeCrmDashboard.tsx');
const admin = read('../components/MasterAdminDashboard.tsx');
const app = read('../App.tsx');
const home = read('../components/Home.tsx');
const navbar = read('../components/Navbar.tsx');

const WORK_ITEM_KEYS = [
  'targetType',
  'targetId',
  'expectedVersion',
  'cityId',
  'cityDisplayName',
  'teamId',
  'teamDisplayName',
  'status',
  'linkedRequestVersions'
];

const claimablePayload = () => ({
  targetType: 'SESSION',
  targetId: 3108,
  expectedVersion: 6,
  cityId: 2,
  cityDisplayName: 'Indore',
  teamId: 11,
  teamDisplayName: 'Vijay Nagar Coordination',
  status: 'DRAFT',
  linkedRequestVersions: [
    { requestId: 12, version: 3 },
    { requestId: 15, version: 1 }
  ],
  // Fields the claimable contract never returns. They must not survive mapping.
  customerName: 'Should never surface',
  customerMobile: '9000000000',
  lessorName: 'Should never surface',
  listingTitle: 'Should never surface',
  privateNote: 'Should never surface'
});

/* 1. Real API page-envelope parsing ------------------------------------------------ */

test('real page envelope is parsed and non-integer fields are reported', () => {
  const page = parseWorkPage(
    { items: [{ targetType: 'REQUEST', targetId: 5 }], totalCount: 41, page: 1, size: 20, totalPages: 3 },
    { page: 0, size: 20 }
  );
  assert.equal(page.totalCount, 41);
  assert.equal(page.page, 1);
  assert.equal(page.size, 20);
  assert.equal(page.totalPages, 3);
  assert.equal(page.items.length, 1);

  const stringy = parseWorkPage(
    { items: [], totalCount: '0', page: '0', size: '20', totalPages: '0' },
    { page: 0, size: 20 }
  );
  assert.equal(stringy.totalCount, 0);
  assert.equal(stringy.totalPages, 0);
});

test('a malformed payload is an error, never an empty queue', () => {
  assert.throws(() => parseWorkPage(null, { page: 0, size: 20 }), /work page/);
  assert.throws(() => parseWorkPage('nope', { page: 0, size: 20 }), /work page/);
  assert.throws(() => parseWorkPage({ totalCount: 0 }, { page: 0, size: 20 }), /item list/);
  const derived = parseWorkPage({ items: new Array(21).fill({}) , totalCount: 41, page: 0, size: 20 }, { page: 0, size: 20 });
  assert.equal(derived.totalPages, 3);
});

/* 2. My Work paging and attention filtering --------------------------------------- */

test('My Work sends the real server-side page, size and attentionOnly contract', () => {
  assert.match(
    service,
    /const query = `page=\$\{page\}&size=\$\{OPERATIONS_WORK_PAGE_SIZE\}&attentionOnly=\$\{attentionOnly \? 'true' : 'false'\}`;/
  );
  assert.match(service, /\/operations\/my-work\?\$\{query\}/);
  assert.match(service, /export const OPERATIONS_WORK_PAGE_SIZE = 20;/);
  assert.match(service, /attentionOnly=\$\{attentionOnly \? 'true' : 'false'\}/);
  // The backend rejects anything other than the literals true/false.
  assert.doesNotMatch(service, /attentionOnly=\$\{attentionOnly\}/);
});

test('attention truth mirrors the server rule and never marks terminal work', () => {
  assert.equal(needsCoordinatorAttention({ targetType: 'REQUEST', status: 'RECEIVED' }), true);
  assert.equal(needsCoordinatorAttention({ targetType: 'REQUEST', status: 'COORDINATING' }), false);
  assert.equal(needsCoordinatorAttention({ targetType: 'REQUEST', status: 'UNAVAILABLE' }), false);
  assert.equal(needsCoordinatorAttention({ targetType: 'SESSION', status: 'DRAFT', scheduledAt: null }), true);
  assert.equal(
    needsCoordinatorAttention({ targetType: 'SESSION', status: 'DRAFT', scheduledAt: '2026-10-11T11:00:00Z' }),
    false
  );
  assert.equal(
    needsCoordinatorAttention({ targetType: 'SESSION', status: 'SCHEDULED', tenantConfirmationState: 'PENDING' }),
    true
  );
  assert.equal(
    needsCoordinatorAttention({ targetType: 'SESSION', status: 'SCHEDULED', tenantConfirmationState: 'CONFIRMED' }),
    false
  );
  assert.equal(needsCoordinatorAttention({ targetType: 'SESSION', status: 'REPAIR_REQUIRED' }), true);
  assert.equal(needsCoordinatorAttention({ targetType: 'SESSION', status: 'COMPLETED' }), false);
  assert.equal(
    attentionCategory({ targetType: 'SESSION', status: 'SCHEDULED', tenantConfirmationState: 'PENDING' }),
    'Tenant confirmation pending'
  );
});

test('My Work rows keep server facts and never invent a missing update time', () => {
  const request = toMyWorkItem({
    targetType: 'REQUEST',
    targetId: 44,
    expectedVersion: 2,
    cityId: 1,
    cityDisplayName: 'Indore',
    teamId: 7,
    teamDisplayName: 'Team A',
    status: 'RECEIVED',
    updatedAt: null,
    scheduledAt: null,
    tenantConfirmationState: null,
    linkedRequestVersions: []
  });
  assert.equal(request.updatedAt, null);
  assert.equal(request.scheduledAt, null);
  assert.equal(request.tenantConfirmationState, null);
  assert.deepEqual(Object.keys(request).sort(), [
    ...WORK_ITEM_KEYS,
    'scheduledAt',
    'tenantConfirmationState',
    'updatedAt'
  ].sort());
  assert.match(lists, /Update time not recorded/);
});

/* 3. Claimable queue redaction ---------------------------------------------------- */

test('claimable mapping exposes only permitted operational metadata', () => {
  const item = toClaimableWorkItem(claimablePayload());
  assert.deepEqual(Object.keys(item).sort(), [...WORK_ITEM_KEYS].sort());
  const serialized = JSON.stringify(item).toLowerCase();
  for (const forbidden of ['customer', 'lessor', 'listingtitle', 'privatenote', 'mobile']) {
    assert.equal(serialized.includes(forbidden), false, `claimable summary leaks ${forbidden}`);
  }
  assert.deepEqual(item.linkedRequestVersions, [
    { requestId: 12, version: 3 },
    { requestId: 15, version: 1 }
  ]);
});

test('a claimable root without a usable identity is dropped instead of guessed', () => {
  assert.equal(toClaimableWorkItem({ targetType: 'PROPERTY', targetId: 4 }), null);
  assert.equal(toClaimableWorkItem({ targetType: 'SESSION', targetId: 'not-an-id' }), null);
  assert.equal(toClaimableWorkItem(null), null);
  const partial = toClaimableWorkItem({ targetType: 'REQUEST', targetId: 9, linkedRequestVersions: 'nope' });
  assert.deepEqual(partial.linkedRequestVersions, []);
  assert.equal(partial.expectedVersion, null);
});

test('the claimable detail read is redacted by the same mapper as the queue', () => {
  const refresh = service.match(/claimableItem\([\s\S]*?\n  \},/);
  assert.ok(refresh);
  assert.match(refresh[0], /toClaimableWorkItem/);
  assert.doesNotMatch(refresh[0], /requestDetail|sessionDetail/);
});

/* 4. Capability-gated visibility -------------------------------------------------- */

test('operations access needs an effective OPS_COORDINATE Team grant', () => {
  const capabilities = (staffActive, list) => toStaffCapabilities({ staffActive, capabilities: list, serverTime: null });
  const coordinate = { capability: 'OPS_COORDINATE', scopeType: 'TEAM', teamId: 7, teamDisplayName: 'Team A', cityId: 1, cityDisplayName: 'Indore' };

  assert.equal(hasOperationsWorkspaceAccess(capabilities(true, [coordinate])), true);
  assert.equal(hasOperationsWorkspaceAccess(capabilities(false, [coordinate])), false);
  assert.equal(
    hasOperationsWorkspaceAccess(capabilities(true, [{ ...coordinate, capability: 'OPS_SUPERVISE' }])),
    false
  );
  assert.equal(
    hasOperationsWorkspaceAccess(capabilities(true, [{ ...coordinate, capability: 'OPS_INTAKE', scopeType: 'CITY' }])),
    false
  );
  assert.equal(
    hasOperationsWorkspaceAccess(capabilities(true, [{ ...coordinate, scopeType: 'CITY' }])),
    false
  );
  assert.equal(hasOperationsWorkspaceAccess(null), false);
  assert.equal(hasOperationsWorkspaceAccess(toStaffCapabilities({ staffActive: true })), false);
});

test('capability scopes list each coordinating team once and never fabricate a label', () => {
  const parsed = toStaffCapabilities({
    staffActive: true,
    serverTime: '2026-10-12T15:00:00Z',
    capabilities: [
      { capability: 'OPS_COORDINATE', scopeType: 'TEAM', teamId: 7, teamDisplayName: 'Team A', cityId: 1, cityDisplayName: 'Indore' },
      { capability: 'OPS_COORDINATE', scopeType: 'TEAM', teamId: 7, teamDisplayName: 'Team A', cityId: 1, cityDisplayName: 'Indore' },
      { capability: 'OPS_SUPERVISE', scopeType: 'TEAM', teamId: 8, teamDisplayName: 'Team B', cityId: 1, cityDisplayName: 'Indore' },
      { capability: 'OPS_COORDINATE', scopeType: 'TEAM', teamId: 9 }
    ]
  });
  const scopes = operationsCapabilityScopes(parsed);
  assert.deepEqual(scopes, [
    { teamId: 7, cityName: 'Indore', teamName: 'Team A' },
    { teamId: 9, cityName: 'City not recorded', teamName: 'Team not recorded' }
  ]);
  assert.equal(parsed.serverTime, '2026-10-12T15:00:00Z');
});

test('both consoles gate the OE destination on the shared server-verified authority', () => {
  // Package 2 lifted the authority to the application shell: exactly one state machine exists for the
  // whole app, and the consoles consume it as a prop instead of starting their own polling loop.
  const shellHookCalls = [home, navbar, dashboard, admin]
    .reduce((total, source) => total + (source.match(/useOperationsCapability\(/g) ?? []).length, 0);
  assert.equal(shellHookCalls, 1, 'exactly one capability state machine may exist');
  assert.match(home, /const operationsAuthority = useOperationsCapability\(\{ workspaceEntry: operationsSurface \}\)/);
  // The entry identity, not a shared boolean: both consoles and the destination are distinct entries.
  assert.match(
    home,
    /const operationsSurface = operationsRoute\s*\n\s*\? 'destination'\s*\n\s*: embeddedOperationsOpen\s*\n\s*\? \(role === 'EMPLOYEE' \? 'embedded:employee' : 'embedded:admin'\)\s*\n\s*: null;/
  );
  assert.equal(dashboard.includes('useOperationsCapability('), false);
  assert.equal(admin.includes('useOperationsCapability('), false);
  assert.match(dashboard, /import type \{ OperationsAuthority \} from '\.\.\/hooks\/useOperationsCapability';/);
  assert.match(admin, /import type \{ OperationsAuthority \} from '\.\.\/hooks\/useOperationsCapability';/);

  // Employee console: the entry point and the destination come from the shared authority.
  assert.match(dashboard, /\{authority\.canEnter && \(/);
  assert.match(dashboard, /activeTab === 'operations' && <OperationsWorkspace authority=\{authority\} \/>/);
  assert.match(dashboard, /\['visits'\]\.includes|setActiveTab\('visits'\)/);

  // Admin console: the same component and authority, an entry built from verified authority only.
  assert.match(admin, /operationsAuthority\.canEnter \? \[\{ id: 'operations'/);
  assert.match(admin, /activeTab === 'operations' && <OperationsWorkspace authority=\{operationsAuthority\} \/>/);
  assert.match(
    admin,
    /if \(activeTab === 'operations' && operationsAuthority\.state === 'restricted'\) \{\s*\n\s*handleTabSelect\('media'\);/
  );

  // A persisted tab selection can never restore the OE destination.
  const persisted = admin.match(/const PERSISTED_ADMIN_TABS = new Set\(\[([\s\S]*?)\]\);/);
  assert.ok(persisted);
  assert.equal(persisted[1].includes('operations'), false);
  assert.match(admin, /if \(PERSISTED_ADMIN_TABS\.has\(tabId\)\) localStorage\.setItem\('pathome_active_admin_tab'/);
  const homeValidTabs = home.match(/const VALID_ADMIN_TABS = new Set\(\[([\s\S]*?)\]\);/);
  assert.ok(homeValidTabs);
  assert.equal(homeValidTabs[1].includes('operations'), false);

  // No role label participates in the operations gate, wherever the authority is constructed.
  const gate = home.match(/const operationsAuthority = useOperationsCapability[\s\S]*?\n/);
  assert.ok(gate);
  assert.doesNotMatch(gate[0], /role|WFH_ADMIN|SUB_ADMIN|SUPER_ADMIN/);
});

/* 5. Request and Session detail mapping ------------------------------------------- */

test('Request detail maps real fields only and keeps stored local created time intact', () => {
  const detailPayload = toRequestDetail({
    requestId: 501,
    status: 'RECEIVED',
    version: 4,
    createdAt: '2026-10-12T21:25:30',
    cityId: 1,
    cityDisplayName: 'Indore',
    teamId: 7,
    teamDisplayName: 'Team A',
    listingId: 8001,
    listingTitle: '2 BHK apartment',
    listingAddress: 'Scheme No. 54',
    listingStatus: 'ACTIVE',
    listingCity: 'Indore',
    sector: 'Vijay Nagar',
    availabilityStartAt: '2026-10-13T04:00:00Z',
    availabilityEndAt: '2026-10-16T12:00:00Z',
    availabilityZoneId: 'Asia/Kolkata',
    preferredAt: null,
    sessionId: null,
    customerName: 'Should never surface'
  });
  assert.equal(detailPayload.requestId, 501);
  assert.equal(detailPayload.version, 4);
  assert.equal(detailPayload.createdAt, '2026-10-12T21:25:30');
  assert.equal(detailPayload.sessionId, null);
  assert.equal(detailPayload.preferredAt, null);
  assert.equal(JSON.stringify(detailPayload).toLowerCase().includes('customer'), false);
  assert.equal(formatStoredLocalDateTime(detailPayload.createdAt), '12 Oct 2026 · 9:25 pm');
  assert.equal(formatStoredLocalDateTime(null), null);
  assert.equal(formatStoredLocalDateTime('not-a-date'), null);
});

test('Session detail maps items, linked requests and never invents a name or an end time', () => {
  const session = toSessionDetail({
    sessionId: 3108,
    status: 'DRAFT',
    version: 6,
    city: 'Indore',
    areaName: 'Vijay Nagar',
    scheduledAt: null,
    reservedEndAt: null,
    durationMinutes: 120,
    zoneId: 'Asia/Kolkata',
    representativeUserId: null,
    assignedAt: null,
    items: [
      {
        itemId: 91,
        listingId: 8001,
        title: '2 BHK apartment',
        address: 'Scheme No. 54',
        city: 'Indore',
        sector: 'Vijay Nagar',
        position: 1,
        sourceRequestId: 501,
        derivedFromRequestId: null,
        origin: 'TENANT_REQUESTED',
        confirmationStatus: 'PENDING',
        availabilityConfirmedAt: null,
        removedAt: null,
        removalReason: null,
        // The response DTO names these `propertyAvailability*`. The command-style names this fixture
        // used before are not what the backend returns, which is what hid every real window.
        propertyAvailabilityStartAt: null,
        propertyAvailabilityEndAt: null,
        propertyAvailabilityZoneId: null,
        propertyAvailabilitySource: null,
        representativeName: 'Should never surface'
      }
    ],
    linkedRequestVersions: [{ requestId: 501, version: 4 }],
    teamId: 7,
    teamDisplayName: 'Team A',
    coordinatorUserId: 42,
    updatedAt: '2026-10-12T15:55:00Z',
    tenantConfirmationState: 'NOT_REQUIRED',
    startedAt: null,
    expiresAt: null,
    executionStateChangedAt: null
  });
  assert.equal(session.items.length, 1);
  assert.equal(session.items[0].sourceRequestId, 501);
  assert.equal(session.items[0].derivedFromRequestId, null);
  assert.equal(session.items[0].availabilityStartAt, null);
  assert.equal(session.items[0].availabilitySource, null);
  assert.equal(session.representativeUserId, null);
  assert.equal(session.coordinatorUserId, 42);
  assert.deepEqual(session.linkedRequestVersions, [{ requestId: 501, version: 4 }]);
  const serialized = JSON.stringify(session);
  assert.equal(serialized.includes('representativeName'), false);
  assert.equal(serialized.toLowerCase().includes('endedat'), false);
  assert.equal(toSessionDetail({}), null);
});

test('recorded property availability maps from the response DTO names, never the command names', () => {
  const session = toSessionDetail({
    sessionId: 3109,
    status: 'DRAFT',
    version: 3,
    city: 'Indore',
    items: [
      {
        itemId: 95,
        listingId: 8100,
        title: '3 BHK apartment',
        sector: 'Vijay Nagar',
        position: 1,
        sourceRequestId: null,
        derivedFromRequestId: 501,
        origin: 'LESSOR_SUGGESTED',
        confirmationStatus: 'CONFIRMED',
        availabilityConfirmedAt: '2026-10-12T15:00:00Z',
        removedAt: null,
        removalReason: null,
        propertyAvailabilityStartAt: '2026-10-13T04:00:00Z',
        propertyAvailabilityEndAt: '2026-10-16T12:00:00Z',
        propertyAvailabilityZoneId: 'Asia/Kolkata',
        propertyAvailabilitySource: 'PHONE',
        availabilityStartAt: 'command-name-must-be-ignored',
        availabilityZoneId: 'command-name-must-be-ignored'
      }
    ]
  });
  const item = session.items[0];
  assert.equal(item.availabilityStartAt, '2026-10-13T04:00:00Z');
  assert.equal(item.availabilityEndAt, '2026-10-16T12:00:00Z');
  assert.equal(item.availabilityZoneId, 'Asia/Kolkata');
  assert.equal(item.availabilitySource, 'PHONE');
  assert.equal(item.derivedFromRequestId, 501);
  assert.deepEqual(operationsAvailabilityMeta(item), {
    view: 'confirmed-window',
    label: 'Confirmed with window',
    hint: 'A complete availability window is recorded for this stop.',
    schedulingReady: true
  });
  // A confirmation without a usable window is a real state, and it is not scheduling-ready.
  assert.equal(operationsAvailabilityMeta({ ...item, availabilitySource: null }).view, 'confirmed-incomplete');
});

test('detail rendering shows identifiers, never fabricated human references or names', () => {
  assert.match(detail, /operationsRecordTitle\(/);
  assert.doesNotMatch(detail, /'VS-'|'VR-'|`VS-|`VR-/);
  assert.doesNotMatch(detail, /geName|representativeName|coordinatorName|endedAt/);
  assert.match(detail, /User \{detail\.representativeUserId\}/);
  assert.match(detail, /User \{detail\.coordinatorUserId\}/);
});

/* 6. Versioned Request and Session claim ------------------------------------------ */

test('claim commands use the root version and the full linked Request version set', () => {
  const requestClaim = claimCommandFor(toClaimableWorkItem({
    targetType: 'REQUEST', targetId: 501, expectedVersion: 4, linkedRequestVersions: []
  }));
  assert.deepEqual(requestClaim, { targetType: 'REQUEST', expectedRequestVersion: 4 });

  const sessionClaim = claimCommandFor(toClaimableWorkItem(claimablePayload()));
  assert.deepEqual(sessionClaim, {
    targetType: 'SESSION',
    expectedSessionVersion: 6,
    expectedRequestVersions: { 12: 3, 15: 1 }
  });

  assert.equal(claimCommandFor(toClaimableWorkItem({ targetType: 'SESSION', targetId: 1 })), null);
});

test('claim re-reads the root before mutating and forwards one captured session to every request', () => {
  const claimRoot = workspace.match(/const claimRoot = useCallback\(async[\s\S]*?\n  \}, \[/);
  assert.ok(claimRoot);
  // The logical operation captures the session once and is bound to the component, the workspace
  // surface, that exact session, the current authorization generation and the exact mounted instance
  // of the claimed root (when it is on screen) before anything is awaited.
  assert.match(claimRoot[0], /const session = captureOperationsSession\(\);/);
  assert.match(
    claimRoot[0],
    /const operation = ownership\.begin\(\s*\n\s*'claim',\s*\n\s*session,\s*\n\s*authorityRef\.current\.authorizationGeneration\(\),\s*\n\s*mountedTargetFor\(\{ targetType: item\.targetType, targetId: item\.targetId \}\)\s*\n\s*\);/
  );
  assert.match(claimRoot[0], /await authorityRef\.current\.verifySession\(session, \{ signal: operation\.controller\.signal \}\)/);
  // The fresh root read happens before the command is built and dispatched, and is cancellable.
  assert.match(
    claimRoot[0],
    /claimableItem\(item\.targetType, item\.targetId, \{\s*\n\s*session,\s*\n\s*signal: operation\.controller\.signal\s*\n\s*\}/
  );
  assert.ok(claimRoot[0].indexOf('claimableItem(') < claimRoot[0].indexOf('claimRequest('));
  assert.ok(claimRoot[0].indexOf('claimableItem(') < claimRoot[0].indexOf('claimSession('));
  // Every awaited read is followed by the full invariant check before the flow may continue.
  assert.match(
    claimRoot[0],
    /claimableItem\([\s\S]*?if \(!ownership\.canCommit\(operation, authorityRef\.current\)\) return;/
  );
  assert.match(
    claimRoot[0],
    /verifySession\(session, \{ signal: operation\.controller\.signal \}\)\)[\s\S]*?if \(!ownership\.canCommit\(operation, authorityRef\.current\)\) return;/
  );
  assert.match(claimRoot[0], /claimCommandFor\(fresh\)/);
  assert.match(claimRoot[0], /claimRequest\(fresh\.targetId, command\.expectedRequestVersion, \{\s*\n\s*session,/);
  assert.match(claimRoot[0], /command\.expectedRequestVersions,\s*\n\s*\{ session, signal: operation\.controller\.signal, onDispatch \}/);
  // The synchronous dispatch gate is the last check before the transmission, and a refusal returns
  // instead of falling through to it.
  assert.ok(claimRoot[0].indexOf('blockReasonForDispatch(') < claimRoot[0].indexOf('claimRequest('));
  assert.ok(claimRoot[0].indexOf('blockReasonForDispatch(') < claimRoot[0].indexOf('claimSession('));
  const refused = claimRoot[0].match(/const blocked = ownership\.blockReasonForDispatch\([\s\S]*?\n      \}/);
  assert.ok(refused);
  assert.match(refused[0], /return;/);
  assert.doesNotMatch(refused[0], /claimRequest\(|claimSession\(/);
  assert.match(presentation, /export const OPERATIONS_CLAIM_REASON_CODE = 'CLAIM_WORK';/);
  assert.equal(linkedRequestVersionMap([{ requestId: 12, version: 3 }])['12'], 3);
  // Exactly one transmission per user action: a conflict is never resent automatically.
  assert.equal((claimRoot[0].match(/claimRequest\(/g) ?? []).length, 1);
  assert.equal((claimRoot[0].match(/claimSession\(/g) ?? []).length, 1);
});

/* 7. Versioned Request and Session release ---------------------------------------- */

test('release commands mirror the root that must be released', () => {
  const request = toRequestDetail({ requestId: 501, status: 'RECEIVED', version: 4, sessionId: null });
  assert.deepEqual(releaseCommandForRequest(request), { targetType: 'REQUEST', expectedRequestVersion: 4 });
  assert.equal(releaseCommandForRequest(toRequestDetail({ requestId: 501, status: 'RECEIVED' })), null);

  const session = toSessionDetail({
    sessionId: 3108, status: 'DRAFT', version: 6, linkedRequestVersions: [{ requestId: 501, version: 4 }]
  });
  assert.deepEqual(releaseCommandForSession(session), {
    targetType: 'SESSION',
    expectedSessionVersion: 6,
    expectedRequestVersions: { 501: 4 }
  });
  assert.equal(releaseCommandForSession(toSessionDetail({ sessionId: 3108, status: 'DRAFT' })), null);

  assert.match(service, /\{ expectedRequestVersion, reasonCode: OPERATIONS_RELEASE_REASON_CODE \}/);
  assert.match(
    service,
    /\{ expectedSessionVersion, expectedRequestVersions, reasonCode: OPERATIONS_RELEASE_REASON_CODE \}/
  );
  assert.match(presentation, /export const OPERATIONS_RELEASE_REASON_CODE = 'RELEASE_COORDINATOR';/);
});

test('release is offered only where the authoritative read state already allows it', () => {
  assert.equal(isOwnedRequestReleasable(toRequestDetail({ requestId: 1, status: 'RECEIVED', sessionId: null })), true);
  assert.equal(isOwnedRequestReleasable(toRequestDetail({ requestId: 1, status: 'RECEIVED', sessionId: 9 })), false);
  assert.equal(isOwnedRequestReleasable(toRequestDetail({ requestId: 1, status: 'COORDINATING', sessionId: null })), false);
  assert.equal(isOwnedSessionReleasable(toSessionDetail({ sessionId: 1, status: 'DRAFT' })), true);
  assert.equal(
    isOwnedSessionReleasable(toSessionDetail({ sessionId: 1, status: 'DRAFT', scheduledAt: '2026-10-14T04:00:00Z' })),
    false
  );
  assert.equal(isOwnedSessionReleasable(toSessionDetail({ sessionId: 1, status: 'DRAFT', representativeUserId: 5 })), false);
  assert.equal(isOwnedSessionReleasable(toSessionDetail({ sessionId: 1, status: 'SCHEDULED' })), false);
});

/* 8. HTTP 204 success handling ----------------------------------------------------- */

test('ownership commands never deserialise a body from a successful response', () => {
  const command = service.match(/const sendOwnershipCommand = async[\s\S]*?\n\};/);
  assert.ok(command);
  assert.match(command[0], /method: 'POST'/);
  assert.match(command[0], /return response\.status;/);
  assert.doesNotMatch(command[0], /\.json\(/);
});

/* 9. 409 stale-state recovery ------------------------------------------------------ */

test('a conflict is explained through the persistent workspace alert, not a disappearing notice', () => {
  // The outcome copy (including the preserved 409 message) is owned by the presentation module and
  // proven by behaviour in `operationsOwnershipRaceCorrections.test.mjs`; the workspace routes every
  // failed ownership command through it.
  const failure = workspace.match(
    /const reportOwnershipFailure = useCallback\(async[\s\S]*?\n  \}, \[noticeContextHolds, ownership, refreshAll, showNotice, withdrawDeniedRoot\]\);/
  );
  assert.ok(failure);
  assert.match(failure[0], /ownershipOutcomeCopy\(/);
  assert.match(failure[0], /operation\.dispatched/);
  assert.match(presentation, /changed on the server while you were working, so nothing was changed by you/);
  const claim = workspace.match(/const claimRoot = useCallback\(async[\s\S]*?\n  \}, \[/);
  assert.ok(claim);
  assert.match(claim[0], /catch \(cause\) \{[\s\S]*?reportOwnershipFailure\(cause, operation, item\.targetType, item\.targetId\)/);
  assert.match(claim[0], /await refreshAll\(session\);/);
  // One mutation per user action: the lock is taken before the first request and released only by the
  // operation that owns it.
  assert.match(claim[0], /if \(ownership\.isLocked\(\)\) return;/);
  assert.match(claim[0], /finally \{[\s\S]*?if \(ownership\.finish\(operation\)\)/);
  // Release conflicts keep their explanation even though the detail closes.
  const release = workspace.match(/const releaseRoot = useCallback\(async[\s\S]*?\n  \}, \[/);
  assert.ok(release);
  assert.match(
    release[0],
    /reportOwnershipFailure\(cause, operation, target\.targetType, target\.targetId, \{\s*\n\s*dropDetailWhenRejected: true\s*\n\s*\}\)/
  );
  const releaseFailure = release[0].match(/catch \(cause\) \{[\s\S]*?\n    \} finally/);
  assert.ok(releaseFailure);
  assert.match(
    releaseFailure[0],
    /reportOwnershipFailure\([\s\S]*?dropDetailWhenRejected: true/,
    'the release reports its outcome through the shared truthful copy'
  );
  /**
   * R1: the withdrawal is not deferred behind the reconciliation. The refused root is dropped in the
   * same tick the denial is classified — before the authoritative reads are awaited — and the notice
   * still follows the real reconciliation result, so the copy is never written before the reads that
   * decide what it may truthfully say.
   */
  const withdrawAt = failure[0].indexOf('withdrawDeniedRoot(');
  const reconcileAt = failure[0].indexOf('await refreshAll(operation.session)');
  const noticeAt = failure[0].indexOf('showNotice(copy.message, operation.session);');
  assert.ok(withdrawAt > -1 && reconcileAt > -1 && noticeAt > -1);
  assert.ok(withdrawAt < reconcileAt, 'the protected detail is dropped before the reconciliation is awaited');
  assert.ok(noticeAt > reconcileAt, 'the real reconciliation result still decides the copy');
  // The notice lives in the workspace, is announced, and is scoped to the session that raised it.
  assert.match(workspace, /<WorkspaceNotice/);
  assert.match(workspace, /notice\.sessionIdentity === \(readOperationsSession\(\)\?\.identity \?\? null\)/);
  assert.match(primitives, /role=\{tone === 'warn' \? 'alert' : 'status'\}/);
  // The record detail no longer owns any notice, so it cannot take feedback down with it.
  assert.doesNotMatch(detail, /NoticeBanner|onDismissNotice/);
});

/* 10. 401/403/404 access-loss behaviour ------------------------------------------- */

test('access loss is classified and released detail stops being shown', () => {
  assert.equal(operationsLoadFailureState(401), 'signed-out');
  assert.equal(operationsLoadFailureState(403), 'denied');
  assert.equal(operationsLoadFailureState(409), 'error');
  assert.equal(operationsLoadFailureState(undefined), 'error');
  assert.equal(operationsDetailFailureState(404), 'missing');
  assert.equal(operationsDetailFailureState(403), 'missing');
  assert.equal(operationsDetailFailureState(500), 'error');

  const release = workspace.match(/const releaseRoot = useCallback\(async[\s\S]*?\n  \}, \[/);
  assert.ok(release);
  // Every display change moves the display context with it, so a later refusal verdict can tell a
  // security withdrawal this operation performed from any navigation that replaced its record. The
  // release records the drop as its own transition, so the confirmed release keeps its own follow-up
  // notice while a newer record opened during the command is never cleared by it.
  assert.match(release[0], /setDisplayContext\(null, operation\);/);
  /**
   * A rejected release drops protected detail only for the statuses where ownership may already be
   * gone on the server. The decision itself is the real shared classifier, so it is asserted
   * behaviourally instead of by matching the workspace's source text.
   */
  assert.equal(
    presentationModule.commandFailureDisposition({ dispatched: true, status: 403 }).withdrawRoot,
    true
  );
  assert.equal(
    presentationModule.commandFailureDisposition({ dispatched: true, status: 404 }).withdrawRoot,
    true
  );
  assert.equal(
    presentationModule.commandFailureDisposition({ dispatched: true, status: 409 }).withdrawRoot,
    false
  );
  assert.equal(
    presentationModule.commandFailureDisposition({ dispatched: true, status: 409 }, { dropDetailWhenRejected: true }).withdrawRoot,
    true
  );
  assert.equal(
    presentationModule.commandFailureDisposition({ dispatched: true, status: 500 }).withdrawRoot,
    false
  );
  /**
   * The workspace owner of that cleanup exists and is the one the shared failure path calls — in the
   * same tick as the classification, before any reconciliation is awaited, and only for the exact
   * mounted instance the failing operation was started against — never whatever happens to be on
   * screen when the failure arrives. The behavioural half of this (pre-reconciliation withdrawal of
   * the refused detail and its revealed contact, and the different-root/re-mount races) is proven in
   * `browser-verification/operations-completion/` and by the real lifecycle decisions in
   * `operationsOwnershipRaceCorrections.test.mjs`.
   */
  assert.match(workspace, /const withdrawDeniedRoot = useCallback\(\(operation: OwnershipOperation\) => \{/);
  assert.match(workspace, /if \(!operationTargetIsMounted\(operation, openRootRef\.current\)\) return;/);
  assert.match(
    workspace,
    /if \(disposition\.withdrawRoot && ownership\.canCommit\(operation, authorityRef\.current\)\) \{\s*\n\s*withdrawDeniedRoot\(operation\);\s*\n\s*\}/
  );
  /**
   * Both failure paths withdraw the operation's own captured target, and both withhold a definitive
   * refusal once the display context that refusal describes has been replaced (every other outcome is
   * announced). The verdict is taken *after* the awaited reconciliation and immediately before the
   * notice — never from a `publishable` value captured earlier, which is exactly how an old #3108
   * refusal used to be written into #4109 once a held refresh resolved.
   */
  assert.equal(
    (workspace.match(/if \(disposition\.targetDenied && !noticeContextHolds\(operation\)\)/g) ?? []).length,
    2
  );
  assert.equal(
    (workspace.match(/const publishable = /g) ?? []).length,
    0,
    'no verdict may be captured before the awaited reconciliation'
  );
  assert.match(
    workspace,
    /const noticeContextHolds = useCallback\(\(operation: OwnershipOperation\): boolean =>\s*\n\s*operation\.target === null\s*\n\s*\|\| operationTargetIsMounted\(operation, openRootRef\.current\)\s*\n\s*\|\| ownership\.withdrewOwnDisplayContext\(operation\), \[ownership\]\);/
  );
  for (const [label, anchor] of [
    ['a failed ownership command', 'const reportOwnershipFailure = useCallback(async'],
    ['a failed Phase 2A command', 'const reportCommandFailure = useCallback(async']
  ]) {
    const from = workspace.indexOf(anchor);
    assert.ok(from > -1, `${label} region`);
    const slice = workspace.slice(from, workspace.indexOf('showNotice(copy.message, operation.session);', from));
    const verdictAt = slice.indexOf('if (disposition.targetDenied && !noticeContextHolds(operation))');
    const reconcileAt = slice.indexOf('await refreshAll(operation.session)');
    assert.ok(
      verdictAt > reconcileAt && reconcileAt > -1,
      `${label}: the display-context verdict is re-taken after the reconciliation`
    );
    assert.ok(
      slice.lastIndexOf('ownership.canCommit(operation, authorityRef.current)') < verdictAt,
      `${label}: the fresh verdict is the last decision before the notice`
    );
  }
  // Exactly the two failure paths call it, and neither calls it after its reconciliation any more.
  assert.equal((workspace.match(/withdrawDeniedRoot\(/g) ?? []).length, 2);
  assert.match(workspace, /signedOut \|\| accessLost/);
  assert.match(detail, /This item isn’t available to you/);
});

/* 11. Request cancellation and account switching ---------------------------------- */

test('pending work is cancelled and a stale session can never populate the workspace', () => {
  assert.match(workspace, /controllers\[key\]\?\.abort\(\);/);
  assert.match(workspace, /new AbortController\(\)/);
  assert.match(workspace, /subscribeOperationsSession\(\(\) => clearProtectedState\('session-changed', \{ neutralView: true \}\)\)/);
  assert.match(workspace, /isOperationsSessionActive\(session\)/);
  assert.match(workspace, /isOperationsSessionChangedError\(cause\)/);
  assert.match(workspace, /controller\.signal\.aborted \|\| !isMounted\(\)/);
  assert.match(workspace, /detailController\.current\?\.abort\(\)/);
  // The credential is captured once and re-checked before dispatch and after the response.
  assert.match(service, /const session = options\.session \?\? captureOperationsSession\(\);/);
  assert.match(service, /assertOperationsSessionActive\(session\);/);
  assert.equal((service.match(/assertOperationsSessionActive\(session\);/g) ?? []).length >= 2, true);
  assert.doesNotMatch(service, /readOperationsSessionIdentity/);
  assert.match(service, /cache: 'no-store'/);
  // A transient verification failure hides protected work instead of trusting a stale grant.
  assert.match(workspace, /authority\.state === 'unavailable'/);
  // Losing authority also deactivates the workspace surface and invalidates a pending command.
  assert.match(workspace, /ownership\.setWorkspaceActive\(accessGranted\);/);
  assert.match(workspace, /if \(!accessGranted\) clearProtectedState\('authorization-lost'\);/);
  assert.match(workspace, /if \(!accessGranted \|\| !queuesDenied\) return;\s*\n\s*authorityRef\.current\.revalidate\(\);/);
});

/* 12. No synthetic fixtures in production operations data ------------------------- */

test('no fixture, preview or synthetic content survives in the operations modules', () => {
  for (const [name, source] of Object.entries({ service, workspace, detail, lists })) {
    for (const forbidden of [
      'fixtures', 'fixture', 'PreviewState', 'preview state', 'Design only',
      'VS-3108', 'VR-501', 'Indore', 'Preview coordinator', 'synthetic', 'FIXTURE'
    ]) {
      assert.equal(source.includes(forbidden), false, `${name} still contains ${forbidden}`);
    }
  }
});

test('approved Phase 2A controls reach the real command contracts', () => {
  // Every approved action has an exact service method with the audited path and HTTP method.
  assert.match(service, /`\/operations\/visit-requests\/\$\{requestId\}\/lessor-contact`/);
  assert.match(service, /`\/operations\/visit-sessions\/\$\{sessionId\}\/items\/\$\{itemId\}\/lessor-contact`/);
  assert.match(service, /`\/operations\/visit-requests\/\$\{requestId\}\/coordinate`/);
  assert.match(service, /`\/operations\/visit-requests\/\$\{requestId\}\/unavailable`/);
  assert.match(service, /`\/operations\/visit-sessions\/\$\{sessionId\}\/items`,/);
  assert.match(service, /`\/operations\/visit-sessions\/\$\{sessionId\}\/items\/\$\{itemId\}`,/);
  assert.match(service, /`\/operations\/visit-sessions\/\$\{sessionId\}\/items\/order`/);
  assert.match(service, /`\/operations\/visit-sessions\/\$\{sessionId\}\/items\/\$\{itemId\}\/availability`/);
  assert.match(service, /`\/operations\/visit-sessions\/\$\{sessionId\}\/oe-recommendations`/);
  assert.match(service, /`\/operations\/visit-sessions\/\$\{sessionId\}\/oe-recommendations\/selection`/);
  // The removal command keeps its required JSON body on DELETE.
  assert.match(service, /'DELETE',\s*\n\s*body,/);
  // Discovery is cursor-paged and only sends a query when one was entered.
  assert.match(service, /params\.set\('all', 'true'\)/);
  assert.match(service, /if \(options\.cursor\) params\.set\('cursor', options\.cursor\)/);
  // A 200-returning command is never deserialised as a 204 ownership response, and vice versa.
  assert.match(service, /const sendJsonCommand = async <T,>\(/);
  assert.match(service, /const sendOwnershipCommand = async \(/);
  // Admin-only and excluded routes stay unreachable from the OE client.
  for (const forbidden of ['/recommendations/approval', '/assignment', '/cancel', '/reschedule']) {
    assert.equal(service.includes(forbidden), false, `excluded route reachable: ${forbidden}`);
  }
  // The workspace drives every approved action through the shared guarded lifecycle.
  assert.match(workspace, /operationsWorkspaceService\.coordinateRequest\(/);
  assert.match(workspace, /operationsWorkspaceService\.markRequestUnavailable\(/);
  assert.match(workspace, /operationsWorkspaceService\.addSessionItem\(/);
  assert.match(workspace, /operationsWorkspaceService\.removeSessionItem\(/);
  assert.match(workspace, /operationsWorkspaceService\.reorderSessionItems\(/);
  assert.match(workspace, /operationsWorkspaceService\.setSessionItemAvailability\(/);
  assert.match(workspace, /operationsWorkspaceService\.rankedRecommendations\(/);
  assert.match(workspace, /operationsWorkspaceService\.discoverGroundExecutives\(/);
  assert.match(workspace, /operationsWorkspaceService\.selectGroundExecutive\(/);
  assert.match(workspace, /operationsWorkspaceService\.lessorContactForRequest\(/);
  assert.match(workspace, /operationsWorkspaceService\.lessorContactForSessionItem\(/);
  // Active/History cannot be truthful without a server-wide filter, so it must not exist.
  assert.equal(workspace.includes('History'), false);
  assert.match(workspace, /Totals and pages come from the server/);
});

test('independent operational reads are cancelled independently (P1-3)', () => {
  // Each read family owns its own cancellation slot, so starting one read cannot abort another that
  // the user is still waiting for. The single shared slots this replaced must not come back.
  assert.match(
    workspace,
    /type GuardedReadFamily = 'owned-candidates' \| 'listing-search' \| 'ge-ranked' \| 'ge-discovery';/
  );
  for (const family of ['owned-candidates', 'listing-search', 'ge-ranked', 'ge-discovery']) {
    assert.equal(workspace.includes(`beginGuardedRead('${family}', signal)`), true, `missing family ${family}`);
  }
  assert.equal(workspace.includes('beginGuardedRead(signal,'), false, 'a shared read slot is still used');
  assert.equal(workspace.includes('candidateController'), false);
  assert.equal(workspace.includes('listingController'), false);
  // Invalidation still aborts every family at once, and unmounting still closes the surface.
  assert.match(workspace, /const abortGuardedReads = useCallback\(\(\) => \{/);
  assert.equal((workspace.match(/abortGuardedReads\(\);/g) ?? []).length >= 3, true);
});

test('the availability editor converts instants through the recorded zone (P0-1)', () => {
  // The editor must not slice a UTC wall clock out of the server instant, and must not assume the
  // device zone; the pure helpers in the presentation module own that conversion, and the editor
  // routes both fields through them.
  assert.equal(itinerary.includes('serverInstantToZoneInput'), true);
  assert.equal(itinerary.includes('resolveAvailabilityFieldInstant'), true);
  assert.equal(itinerary.includes('slice(0, 16)'), false);
  assert.equal(itinerary.includes('ZONE_OFFSET'), false);
  assert.equal(typeof presentationModule.serverInstantToZoneInput, 'function');
  assert.equal(typeof presentationModule.resolveAvailabilityFieldInstant, 'function');
  assert.equal(typeof presentationModule.zoneInputToServerInstant, 'function');
  assert.equal(typeof presentationModule.operationsZoneOptions, 'function');
  // The conversion the editor actually relies on: a recorded instant is displayed in the property
  // zone, and an untouched field is sent back byte-for-byte rather than re-derived.
  assert.equal(
    presentationModule.serverInstantToZoneInput('2027-02-14T04:00:00Z', 'Asia/Kolkata'),
    '2027-02-14T09:30'
  );
  assert.deepEqual(
    presentationModule.resolveAvailabilityFieldInstant({
      value: '2027-02-14T09:30',
      initialValue: '2027-02-14T09:30',
      zoneId: 'Asia/Kolkata',
      initialZoneId: 'Asia/Kolkata',
      initialInstant: '2027-02-14T04:00:37.123Z'
    }),
    { value: '2027-02-14T04:00:37.123Z', error: null }
  );
});

test('the booking result requires a complete authoritative Session (P0-2)', () => {
  // A non-null-but-unusable body is refused by the real mapper, so the caller classifies the
  // transmitted command as uncertain instead of announcing a booking.
  const scheduled = {
    sessionId: 3108,
    status: 'SCHEDULED',
    version: 7,
    scheduledAt: '2027-02-14T05:00:00Z',
    representativeUserId: 4207
  };
  const expectation = {
    sessionId: 3108,
    expectedSessionVersion: 6,
    groundExecutiveUserId: 4207,
    scheduledAt: '2027-02-14T05:00:00Z'
  };
  assert.ok(presentationModule.toSchedulingApproval({ session: scheduled }, expectation));
  for (const [label, session] of [
    ['invalid nonblank time text', { ...scheduled, scheduledAt: 'not-a-time' }],
    ['a negative assigned GE id', { ...scheduled, representativeUserId: -1 }],
    ['a zero assigned GE id', { ...scheduled, representativeUserId: 0 }],
    ['a stale version that did not advance', { ...scheduled, version: 6 }],
    ['a malformed version', { ...scheduled, version: 'v7' }]
  ]) {
    assert.equal(presentationModule.toSchedulingApproval({ session }, expectation), null, label);
  }
  // The service binds the mapper to the exact submitted selection, not only to a session id.
  assert.match(service, /toSchedulingApproval\(payload, \{/);
  assert.match(service, /expectedSessionVersion: body\.expectedSessionVersion/);
  assert.match(service, /groundExecutiveUserId: body\.groundExecutiveUserId/);
  assert.match(service, /scheduledAt: body\.scheduledAt/);
});

test('the contact reveal is generation-guarded (P1-5)', () => {
  const contactPanel = read('../components/operations/OperationsLessorContactPanel.tsx');
  const revealGate = read('../utils/operationsContactReveal.ts');
  assert.match(contactPanel, /beginContactReveal\(gateRef\.current, contextKey\)/);
  assert.match(contactPanel, /contactRevealIsCurrent\(gateRef\.current, ticket\)/);
  assert.match(contactPanel, /revealController\.current\?\.abort\(\)/);
  assert.match(revealGate, /export const contactRevealIsCurrent = \(/);
  // No storage or logging of contact values anywhere in the panel or its guard.
  for (const source of [contactPanel, revealGate]) {
    for (const forbidden of ['localStorage', 'sessionStorage', 'console.log', 'console.error']) {
      assert.equal(source.includes(forbidden), false, forbidden);
    }
  }
});

test('status, confirmation and formatting degrade to real values', () => {
  assert.deepEqual(operationsStatusMeta('SESSION', 'REPAIR_REQUIRED'), {
    label: 'Repair required',
    tone: 'attention',
    hint: 'This session needs repair outside this workspace'
  });
  assert.deepEqual(operationsStatusMeta('REQUEST', 'WIDGET_PENDING'), { label: 'widget pending', tone: 'open', hint: '' });
  assert.deepEqual(operationsStatusMeta('SESSION', null), { label: 'Status not recorded', tone: 'open', hint: '' });
  assert.equal(tenantConfirmationLabel(null), 'Confirmation not recorded');
  assert.equal(tenantConfirmationLabel('PENDING'), 'Awaiting tenant');
  assert.equal(tenantConfirmationLabel('PENDING_CONFIRMATION'), 'Awaiting tenant');
  assert.equal(tenantConfirmationLabel('REJECTED'), 'Tenant declined');
  assert.equal(tenantConfirmationLabel('NOT_REQUIRED'), 'Confirmation not required');
  assert.equal(tenantConfirmationLabel('WHATEVER'), 'whatever');
  assert.equal(operationsRecordTitle('SESSION', 3108), 'Session #3108');
  assert.equal(operationsRecordTitle('REQUEST', 501), 'Request #501');
  assert.equal(formatOperationsDate('2026-10-11T11:00:00Z'), 'Sun,\u00a011\u00a0Oct');
  assert.equal(formatOperationsDateTime('2026-10-11T11:00:00Z'), 'Sun,\u00a011\u00a0Oct\u00a0·\u00a04:30\u00a0pm');
  assert.equal(
    formatOperationsDetailDateTime('2026-10-11T11:00:00Z'),
    'Sun, 11 Oct 2026 · 4:30 pm IST'
  );
  assert.equal(formatOperationsDate(null), null);
  assert.equal(formatOperationsDate('nonsense'), null);
  assert.equal(formatOperationsDuration(150), '2\u00a0h 30\u00a0min');
  assert.equal(formatOperationsDuration(0), null);
});

/* 13. Preservation of existing employee tabs and routes --------------------------- */

test('the existing employee workspace tabs and panels are preserved', () => {
  assert.match(dashboard, /<GroundVisitOperationsPanel user=\{user\} \/>/);
  assert.match(dashboard, /activeTab === 'visit-repairs' && canReviewVisitRepairs && <OperationsVisitRepairPanel \/>/);
  assert.match(dashboard, /activeTab === 'visit-outcomes' && canReviewVisitOutcomes && <OperationsVisitOutcomePanel \/>/);
  assert.match(dashboard, /const canReviewVisitRepairs = user\?\.employeeRoleType\?\.toUpperCase\(\) === 'WFH_ADMIN';/);
  assert.match(dashboard, /Attendance, leave, and performance tools are unavailable/);
});

test('the Operations destination is a capability-gated path inside the single-route shell', () => {
  // The approved Package 2 change keeps the route table itself untouched: the shell still mounts one
  // route and dispatches on the pathname, so no new router entry or role gate was introduced.
  assert.equal((app.match(/<Route /g) ?? []).length, 1);
  assert.match(app, /path="\/\*"/);
  assert.equal(app.includes('role'), false, 'the route table must not branch on a role label');

  // The destination is derived from the pathname alone, and the role-coercion guard yields to it so
  // that a staff capability - never a role label - decides admission.
  assert.match(home, /const operationsRoute = normalizeRoutePathname\(location\.pathname\) === '\/operations';/);
  assert.match(home, /if \(operationsRoute\) return;/);
  assert.match(home, /const isProtectedRoute = path === '\/tenant' \|\| path === '\/admin' \|\| path === '\/crm'\s*\n\s*\|\| path === '\/operations';/);

  // Existing role destinations are preserved exactly as they were.
  assert.match(home, /role === 'EMPLOYEE' && path !== '\/crm'/);
  assert.match(home, /role === 'TENANT' && path !== '\/tenant' && !path\.startsWith\('\/lessor'\)/);
  assert.match(home, /\(role === 'ADMIN' \|\| role === 'SUPER_ADMIN' \|\| role === 'SUB_ADMIN'\) && path !== '\/admin' && path !== '\/crm'/);
});
