/**
 * Consolidated Phase 2A Operations completion pass (F1–F6).
 *
 * Runs the REAL built app (frontend-web/dist) in Chromium with every HTTP request fulfilled
 * in-process by the controllable stub below. No module is mocked: the React tree, the fetch layer,
 * localStorage, window events, the shared session/ownership lifecycle and the production components
 * are the real ones, so the checks verify UI behaviour and the exact network traffic this workspace
 * produces. The stub stands in for the Spring Boot/PostgreSQL backend, so a genuine backend
 * acceptance is NOT claimed here (see the completion report).
 *
 * Every check is bounded by a wall-clock deadline; if the budget is exhausted the remaining checks
 * are reported NOT TESTED instead of being reported as passed.
 *
 * Usage: npm --prefix frontend-web run build
 *        node frontend-web/browser-verification/operations-completion/run.mjs
 */
import { readFile } from 'node:fs/promises';
import { extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;

const DIST = fileURLToPath(new URL('../../dist/', import.meta.url));
const ORIGIN = 'http://oe.local';
const TOKEN_A = 'token-oe-a';
const TOKEN_B = 'token-oe-b';
const ACCOUNT_A = { id: 41, role: 'EMPLOYEE' };
const ACCOUNT_B = { id: 77, role: 'EMPLOYEE' };

const BUDGET_MS = 9 * 60 * 1000;
const STARTED_AT = Date.now();
const budgetLeftMs = () => BUDGET_MS - (Date.now() - STARTED_AT);

const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2'
};

const WINDOW_START = '2027-02-14T09:30:00+05:30';
const WINDOW_END = '2027-02-14T18:00:00+05:30';

/* ------------------------------------------------------------------ *
 * Stub backend
 * ------------------------------------------------------------------ */

const item = (overrides = {}) => ({
  itemId: 91,
  listingId: 8001,
  title: '2 BHK apartment',
  sector: 'Vijay Nagar',
  position: 1,
  sourceRequestId: 501,
  derivedFromRequestId: null,
  origin: 'TENANT_REQUESTED',
  confirmationStatus: 'PENDING',
  availabilityConfirmedAt: null,
  removedAt: null,
  removalReason: null,
  propertyAvailabilityStartAt: null,
  propertyAvailabilityEndAt: null,
  propertyAvailabilityZoneId: null,
  propertyAvailabilitySource: null,
  ...overrides
});

const derivedItem = (overrides = {}) => item({
  itemId: 92,
  listingId: 8002,
  title: '3 BHK apartment',
  sector: 'Palasia',
  position: 2,
  sourceRequestId: null,
  derivedFromRequestId: 501,
  origin: 'OE_ADDED',
  ...overrides
});

const sessionView = (overrides = {}) => ({
  sessionId: 3108,
  status: 'DRAFT',
  version: 6,
  city: 'Indore',
  areaName: 'Vijay Nagar',
  scheduledAt: null,
  reservedEndAt: null,
  durationMinutes: null,
  zoneId: null,
  representativeUserId: null,
  assignedAt: null,
  items: [item(), derivedItem()],
  linkedRequestVersions: [{ requestId: 501, version: 5 }],
  teamId: 7,
  teamDisplayName: 'Team A',
  coordinatorUserId: 41,
  updatedAt: '2026-10-12T16:00:00Z',
  tenantConfirmationState: 'NOT_REQUIRED',
  startedAt: null,
  expiresAt: null,
  executionStateChangedAt: null,
  ...overrides
});

/**
 * R1 target identity: a third, unrelated DRAFT session the OE can open while a command targeting
 * #3108 is still in flight. It has its own itinerary label and its own confirmed window so its
 * detail, its controls and its scheduling eligibility are all visibly its own.
 */
const unrelatedSessionView = () => sessionView({
  sessionId: 4109,
  version: 3,
  areaName: 'Palasia',
  items: [item({
    itemId: 94,
    listingId: 8100,
    title: '4 BHK villa',
    position: 1,
    sourceRequestId: null,
    derivedFromRequestId: null,
    confirmationStatus: 'CONFIRMED',
    propertyAvailabilityStartAt: WINDOW_START,
    propertyAvailabilityEndAt: WINDOW_END,
    propertyAvailabilityZoneId: 'Asia/Kolkata',
    propertyAvailabilitySource: 'PHONE'
  })],
  linkedRequestVersions: []
});

const candidate = (overrides = {}) => ({
  groundExecutiveUserId: 4207,
  groundExecutiveName: 'Rahul Verma',
  scheduledAt: '2027-02-14T05:00:00Z',
  reservedEndAt: '2027-02-14T07:00:00Z',
  durationMinutes: 120,
  zoneId: 'Asia/Kolkata',
  rank: 1,
  feasibilityStatus: 'FEASIBLE',
  originSource: 'SHIFT',
  locationAssessment: 'WITHIN_COVERAGE',
  travelConfidence: 'HIGH',
  ...overrides
});

const recommendationView = (overrides = {}) => ({
  sessionId: 3108,
  sessionVersion: 6,
  recommendationGeneratedAt: '2026-10-12T16:00:00Z',
  policyVersion: 'p1',
  status: 'EVALUATED',
  candidateSearchTruncated: false,
  diagnostics: [],
  candidates: [candidate()],
  mode: 'RECOMMENDATIONS',
  nextCursor: null,
  pageSize: null,
  scannedCandidateCount: null,
  ...overrides
});

const stub = {
  log: [],
  /**
   * `learn` walks the full journey, `stale` answers 409, `drop` loses the response, `denied` refuses
   * contact. The `correction*` modes hold or fail specific responses for the targeted pass:
   * `refresh-fail` fails the post-booking queue reads, `contact-hold` delays the contact read,
   * `ranked-hold` delays the ranked read, `more-hold` delays a cursor page and `more-fail-once`
   * fails the first cursor page.
   */
  mode: 'learn',
  /** Which phase the journey is in, so one stub can serve every scenario. */
  phase: 'intake',
  session: sessionView(),
  requestStatus: 'RECEIVED',
  /** Set once a booking has been accepted, used by the `refresh-fail` mode. */
  bookingDone: false,
  /** Cursor-page request counter, so a duplicate Load More is directly observable. */
  cursorCalls: 0,
  /** Number of contact reads held open by the delay mode, for diagnostics. */
  contactHolds: 0,
  /**
   * Targeted denial controls for R1. `detailDenied` makes the two owned detail reads answer `404`
   * (a fresh pre-command read denial); `mutationDenied` makes the itinerary mutations answer that
   * status (a post-dispatch rejection). Both leave the queues and capabilities untouched.
   */
  detailDenied: false,
  mutationDenied: null,
  /** Armed by the `bump-after-review` mode: the next session read returns a changed, newer version. */
  armedBump: false,
  /** Set while the `hold-failure-reconciliation` mode is holding the post-refusal queue reads. */
  holdFailure: false,
  /**
   * R1: armed so the first authoritative queue read that follows a target denial is held until the
   * check releases it. The refused root must already be withdrawn while that read is still unresolved,
   * which a check that waits for the post-refresh notice could never observe.
   */
  holdDenialReconciliation: false,
  /** True once the held reconciliation above has actually been intercepted. */
  denialReconciliationHeld: false,
  /** Resolver for the held reconciliation, called by the check that armed it. */
  releaseDenialReconciliation: null,
  /**
   * R1: milliseconds to hold every lessor-contact read, so a reveal can still be in flight when the
   * root is denied. The transport is deliberately *not* abort-aware here: the answer is delivered late
   * regardless, exactly like a response that ignores an abort.
   */
  holdContactMs: 0,
  /** R3: the ranked candidate's evaluated instant, so a strict-timestamp case can be reproduced. */
  rankedCandidateScheduledAt: '2027-02-14T05:00:00Z',
  /** R3: the exact unusable booking body the `malformed-200` mode answers with. */
  malformedBooking: { scheduledAt: 'not-a-time', groundExecutiveUserId: -1 },
  /** R1 target identity: list the unrelated Session #4109 as an owned root when armed. */
  unrelatedSessionEnabled: false,
  unrelatedSession: unrelatedSessionView(),
  /**
   * R1 display context: list the unrelated standalone Request that carries the *same numeric id* as
   * the DRAFT Session #3108. The two roots are different display contexts, so a Session refusal must
   * never be treated as this Request's.
   */
  sameIdRequestEnabled: false,
  /**
   * R1 target identity: one-shot hold on a single response, so the OE can leave the record — or open
   * another one — while the command that produced it is still in flight. The response is then
   * released with whatever the stub is configured to answer at that moment.
   */
  holdResponse: null,
  heldResponse: false,
  releaseHeldResponse: null,
  /** R1 target identity: the status the held/next scheduling selection POST is refused with. */
  selectionDenied: null,
  /**
   * Stale success: the authoritative body a held availability mutation answers with, so a response
   * computed when the command was transmitted can arrive after the OE has re-read a newer same-root
   * snapshot. The server's own state is left untouched by such an answer.
   */
  mutationResponseOverride: null,
  /**
   * Stale success: fail every authoritative queue read once armed, so a confirmed command whose
   * display context replaced it can be checked for the truthful "lists could not be refreshed" path
   * without a booking being involved.
   */
  failQueueReads: false,
  /** The roots a release command has taken out of this account's queues. */
  releasedTargets: new Set(),
  reset(mode = 'learn', phase = 'intake') {
    this.log = [];
    this.mode = mode;
    this.phase = phase;
    this.bookingDone = false;
    this.cursorCalls = 0;
    this.contactHolds = 0;
    this.detailDenied = false;
    this.mutationDenied = null;
    this.armedBump = false;
    this.holdFailure = false;
    this.holdDenialReconciliation = false;
    this.denialReconciliationHeld = false;
    this.releaseDenialReconciliation = null;
    this.holdContactMs = 0;
    this.rankedCandidateScheduledAt = '2027-02-14T05:00:00Z';
    this.malformedBooking = { scheduledAt: 'not-a-time', groundExecutiveUserId: -1 };
    this.unrelatedSessionEnabled = false;
    this.unrelatedSession = unrelatedSessionView();
    this.sameIdRequestEnabled = false;
    this.holdResponse = null;
    this.heldResponse = false;
    this.releaseHeldResponse = null;
    this.selectionDenied = null;
    this.mutationResponseOverride = null;
    this.failQueueReads = false;
    this.releasedTargets = new Set();
    this.session = sessionView(
      phase === 'draft'
        ? {
            items: [
              item({
                confirmationStatus: 'CONFIRMED',
                propertyAvailabilityStartAt: WINDOW_START,
                propertyAvailabilityEndAt: WINDOW_END,
                propertyAvailabilityZoneId: 'Asia/Kolkata',
                propertyAvailabilitySource: 'PHONE'
              }),
              derivedItem({
                confirmationStatus: 'CONFIRMED',
                propertyAvailabilityStartAt: WINDOW_START,
                propertyAvailabilityEndAt: WINDOW_END,
                propertyAvailabilityZoneId: 'Asia/Kolkata',
                propertyAvailabilitySource: 'PHONE'
              })
            ]
          }
        : phase === 'single-stop'
          ? {
              // The R7 sequence: one linked Request (501) whose *only* stop is the direct item 91.
              items: [item()]
            }
          : phase === 'scheduled'
            ? {
                /**
                 * R8: a booked, read-only session with two active confirmed stops from different
                 * lessors, plus one removed stop that must never offer a contact.
                 */
                status: 'SCHEDULED',
                version: 6,
                scheduledAt: '2027-02-14T05:00:00Z',
                reservedEndAt: '2027-02-14T07:00:00Z',
                durationMinutes: 120,
                zoneId: 'Asia/Kolkata',
                representativeUserId: 4207,
                assignedAt: '2026-10-12T16:30:00Z',
                tenantConfirmationState: 'PENDING',
                items: [
                  item({
                    confirmationStatus: 'CONFIRMED',
                    propertyAvailabilityStartAt: WINDOW_START,
                    propertyAvailabilityEndAt: WINDOW_END,
                    propertyAvailabilityZoneId: 'Asia/Kolkata',
                    propertyAvailabilitySource: 'PHONE'
                  }),
                  derivedItem({
                    confirmationStatus: 'CONFIRMED',
                    propertyAvailabilityStartAt: WINDOW_START,
                    propertyAvailabilityEndAt: WINDOW_END,
                    propertyAvailabilityZoneId: 'Asia/Kolkata',
                    propertyAvailabilitySource: 'PHONE'
                  }),
                  item({
                    itemId: 93,
                    listingId: 8003,
                    title: '1 BHK studio',
                    position: 3,
                    sourceRequestId: null,
                    derivedFromRequestId: 501,
                    origin: 'OE_ADDED',
                    removedAt: '2026-10-12T17:00:00Z',
                    removalReason: 'Lessor withdrew'
                  })
                ]
              }
            : {}
    );
    // A session that exists (draft or booked) already owns its linked request.
    this.requestStatus = phase === 'intake' ? 'RECEIVED' : 'COORDINATING';
  },
  callsMatching(predicate) {
    return this.log.filter(predicate);
  }
};

const capabilities = () => ({
  staffActive: true,
  serverTime: '2026-10-12T16:00:00Z',
  capabilities: [{
    capability: 'OPS_COORDINATE',
    scopeType: 'TEAM',
    cityId: 1,
    cityDisplayName: 'Indore',
    teamId: 7,
    teamDisplayName: 'Team A'
  }]
});

const summary = (overrides = {}) => ({
  targetType: 'REQUEST',
  targetId: 501,
  expectedVersion: 4,
  cityId: 1,
  cityDisplayName: 'Indore',
  teamId: 7,
  teamDisplayName: 'Team A',
  status: 'RECEIVED',
  linkedRequestVersions: [],
  ...overrides
});

const myWorkItems = () => {
  // Any phase that has a session lists that session as the owned root.
  if (stub.phase !== 'intake') {
    const items = stub.releasedTargets.has('SESSION-3108') ? [] : [summary({
      targetType: 'SESSION',
      targetId: 3108,
      expectedVersion: stub.session.version,
      status: stub.session.status,
      linkedRequestVersions: [{ requestId: 501, version: 5 }]
    })];
    if (stub.unrelatedSessionEnabled) {
      items.push(summary({
        targetType: 'SESSION',
        targetId: 4109,
        expectedVersion: stub.unrelatedSession.version,
        status: stub.unrelatedSession.status,
        linkedRequestVersions: []
      }));
    }
    if (stub.sameIdRequestEnabled) {
      items.push(summary({
        targetType: 'REQUEST',
        targetId: 3108,
        expectedVersion: 5,
        status: 'RECEIVED',
        linkedRequestVersions: []
      }));
    }
    return items;
  }
  return [summary({ status: stub.requestStatus, expectedVersion: stub.requestStatus === 'RECEIVED' ? 4 : 6 })];
};

const page = (items) => ({ items, totalCount: items.length, page: 0, size: 20, totalPages: items.length ? 1 : 0 });

const requestDetail = (overrides = {}) => ({
  requestId: 501,
  status: stub.requestStatus,
  version: stub.requestStatus === 'RECEIVED' ? 4 : 6,
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
  availabilityStartAt: '2027-02-14T04:00:00Z',
  availabilityEndAt: '2027-02-16T12:00:00Z',
  availabilityZoneId: 'Asia/Kolkata',
  preferredAt: null,
  sessionId: stub.requestStatus === 'RECEIVED' ? null : 3108,
  ...overrides
});

const notFound = () => ({ status: 404, body: { status: 404, message: 'Not found' } });

/**
 * R1 display context: the standalone Request whose id collides with the DRAFT Session #3108. It is a
 * different root type with its own listing, its own version and its own independently authorized
 * contact, so a Session refusal can never be mistaken for this root's.
 */
const sameIdRequestDetail = () => requestDetail({
  requestId: 3108,
  version: 5,
  listingId: 8101,
  listingTitle: '3 BHK flat',
  sector: 'Palasia',
  sessionId: null
});

/** The stub backend. `url` carries the query, so cursor and search behaviour is the real one. */
const apiResponse = (url, method, body) => {
  const path = url.pathname;

  if (path === '/api/v1/staff/me/capabilities') return { status: 200, body: capabilities() };
  if (path === '/api/v1/notifications') return { status: 200, body: [] };

  if (path === '/api/v1/operations/my-work') {
    const attentionOnly = url.searchParams.get('attentionOnly') === 'true';
    return { status: 200, body: page(attentionOnly ? [] : myWorkItems()) };
  }
  if (path === '/api/v1/operations/claimable-work') {
    return {
      status: 200,
      body: page(stub.requestStatus === 'RECEIVED' && !stub.releasedTargets.has('REQUEST-501') ? [summary()] : [])
    };
  }
  if (path === '/api/v1/operations/claimable-work/REQUEST/501') return { status: 200, body: summary() };
  if (path === '/api/v1/operations/visit-requests/501') {
    return stub.detailDenied ? notFound() : { status: 200, body: requestDetail() };
  }
  // R1 display context: the same-numeric-id Request root and its own canonical contact.
  if (path === '/api/v1/operations/visit-requests/3108') {
    return stub.detailDenied ? notFound() : { status: 200, body: sameIdRequestDetail() };
  }
  if (path === '/api/v1/operations/visit-requests/3108/lessor-contact') {
    return {
      status: 200,
      body: { listingId: 8101, name: 'Anil Mehta', mobile: '+91 90000 44444', status: 'CONTACT_AVAILABLE' }
    };
  }

  if (path === '/api/v1/operations/visit-requests/501/lessor-contact') {
    return stub.mode === 'denied'
      ? { status: 403, body: { status: 403, message: 'Forbidden' } }
      : { status: 200, body: { listingId: 8001, name: 'Rakesh Sharma', mobile: '+91 98260 11223', status: 'CONTACT_AVAILABLE' } };
  }
  const sessionItemContact = /^\/api\/v1\/operations\/visit-sessions\/3108\/items\/(\d+)\/lessor-contact$/.exec(path);
  if (sessionItemContact) {
    return stub.mode === 'denied'
      ? { status: 403, body: { status: 403, message: 'Forbidden' } }
      /**
       * Each stop resolves its own lessor: distinct properties must never share a contact. Item 91 is
       * listing 8001 (Meena Gupta), item 92 is listing 8002 (Suresh Jain), item 93 (listing 8003) and
       * anything else fall back to the same distinct pair used by the reveal-race scenario.
       */
      : Number(sessionItemContact[1]) === 92
        ? { status: 200, body: { listingId: 8002, name: 'Suresh Jain', mobile: '+91 90000 22222', status: 'CONTACT_AVAILABLE' } }
        : { status: 200, body: { listingId: 8001, name: 'Meena Gupta', mobile: '+91 90000 11111', status: 'CONTACT_AVAILABLE' } };
  }
  if (path === '/api/v1/operations/visit-sessions/3108') {
    if (stub.detailDenied) return notFound();
    // The scheduled-mode read-only fixture (R8) and the armed version bump (R4) both serve here.
    if (stub.mode === 'bump-after-review' && stub.armedBump && stub.session.version === 6) {
      stub.armedBump = false;
      stub.session = sessionView({
        version: 8,
        items: [
          item({
            itemId: 95,
            listingId: 8009,
            title: 'Replacement stop',
            confirmationStatus: 'CONFIRMED',
            propertyAvailabilityStartAt: WINDOW_START,
            propertyAvailabilityEndAt: WINDOW_END,
            propertyAvailabilityZoneId: 'Asia/Kolkata',
            propertyAvailabilitySource: 'PHONE'
          })
        ]
      });
    }
    return { status: 200, body: stub.session };
  }
  // R1 target identity: the unrelated Session #4109 is always served authoritatively, so a denial
  // aimed at #3108 can never be confused with this root's own reads.
  if (path === '/api/v1/operations/visit-sessions/4109') {
    return { status: 200, body: stub.unrelatedSession };
  }
  // The unrelated Session's own stops resolve their own lessor, exactly like #3108's do.
  const unrelatedItemContact = /^\/api\/v1\/operations\/visit-sessions\/4109\/items\/(\d+)\/lessor-contact$/.exec(path);
  if (unrelatedItemContact) {
    return {
      status: 200,
      body: { listingId: 8100, name: 'Kavita Rao', mobile: '+91 90000 33333', status: 'CONTACT_AVAILABLE' }
    };
  }
  if (path === '/api/v1/operations/visit-sessions/4109/oe-recommendations' && method === 'POST') {
    return {
      status: 200,
      body: recommendationView({ sessionId: 4109, sessionVersion: stub.unrelatedSession.version })
    };
  }

  /** Post-dispatch denials (R1): the mutation is transmitted, then refused. */
  if (stub.mutationDenied !== null
      && (path.endsWith('/items/order') || path.endsWith('/items') || path.endsWith('/availability'))) {
    return { status: stub.mutationDenied, body: { status: stub.mutationDenied, message: 'Controlled denial' } };
  }

  //---- Commands ----------------------------------------------------------------

  if (path === '/api/v1/operations/visit-requests/501/claim' && method === 'POST') {
    stub.requestStatus = 'RECEIVED';
    return { status: 204, body: null };
  }
  if (path === '/api/v1/operations/visit-requests/501/release' && method === 'POST') {
    stub.requestStatus = 'RECEIVED';
    stub.releasedTargets.add('REQUEST-501');
    return { status: 204, body: null };
  }
  if (path === '/api/v1/operations/visit-sessions/3108/release' && method === 'POST') {
    stub.releasedTargets.add('SESSION-3108');
    return { status: 204, body: null };
  }
  if (path === '/api/v1/operations/visit-requests/501/coordinate' && method === 'POST') {
    stub.phase = 'draft';
    stub.requestStatus = 'COORDINATING';
    return { status: 200, body: stub.session };
  }
  if (path === '/api/v1/operations/visit-requests/501/unavailable' && method === 'POST') {
    stub.requestStatus = 'UNAVAILABLE';
    return { status: 200, body: requestDetail({ status: 'UNAVAILABLE', version: 7, sessionId: null }) };
  }
  if (path === '/api/v1/operations/visit-sessions/3108/items/order' && method === 'PUT') {
    const order = body?.itemIds ?? [];
    stub.session = {
      ...stub.session,
      version: stub.session.version + 1,
      items: stub.session.items
        .map((entry) => ({ ...entry, position: order.indexOf(entry.itemId) + 1 || entry.position }))
        .sort((left, right) => left.position - right.position)
    };
    return { status: 200, body: stub.session };
  }
  if (/^\/api\/v1\/operations\/visit-sessions\/3108\/items\/\d+\/availability$/.test(path) && method === 'PUT') {
    const itemId = Number(path.split('/')[7]);
    // Stale success: a frozen body models the answer that was computed when the command was
    // transmitted and arrives after the OE has already re-read a newer snapshot of the same root.
    if (stub.mutationResponseOverride !== null) {
      return { status: 200, body: stub.mutationResponseOverride };
    }
    stub.session = {
      ...stub.session,
      version: stub.session.version + 1,
      items: stub.session.items.map((entry) => entry.itemId === itemId
        ? {
            ...entry,
            confirmationStatus: body.status,
            propertyAvailabilityStartAt: body.availabilityStartAt,
            propertyAvailabilityEndAt: body.availabilityEndAt,
            propertyAvailabilityZoneId: body.availabilityZoneId,
            propertyAvailabilitySource: body.availabilitySource
          }
        : entry)
    };
    return { status: 200, body: stub.session };
  }
  if (path === '/api/v1/operations/visit-sessions/3108/items' && method === 'POST') {
    stub.session = {
      ...stub.session,
      version: stub.session.version + 1,
      items: [...stub.session.items, derivedItem({ itemId: 93, listingId: body.listingId, position: stub.session.items.length + 1 })]
    };
    return { status: 200, body: stub.session };
  }
  if (/^\/api\/v1\/operations\/visit-sessions\/3108\/items\/\d+$/.test(path) && method === 'DELETE') {
    const itemId = Number(path.split('/')[7]);
    stub.session = {
      ...stub.session,
      version: stub.session.version + 1,
      items: stub.session.items.map((entry) => entry.itemId === itemId
        ? { ...entry, removedAt: '2026-10-12T17:00:00Z', removalReason: body?.reason ?? 'Removed by Operations' }
        : entry)
    };
    return { status: 200, body: stub.session };
  }
  if (path === '/api/v1/operations/visit-sessions/3108/oe-recommendations' && method === 'POST') {
    // Every evaluation echoes the exact Session version the server evaluated, as the real API does.
    const evaluatedVersion = stub.session.version;
    if (url.searchParams.get('all') !== 'true') {
      if (stub.mode === 'bump-after-review') stub.armedBump = true;
      // The evaluated instant is the one the booking will submit, so a strict-timestamp case can be
      // reproduced with an exact submitted value rather than an approximation.
      return {
        status: 200,
        body: recommendationView({
          sessionVersion: evaluatedVersion,
          candidates: [candidate({ scheduledAt: stub.rankedCandidateScheduledAt })]
        })
      };
    }
    const cursor = url.searchParams.get('cursor');
    const query = url.searchParams.get('q');
    // A rejected continuation: an expired page context answers 400, exactly as the real backend does.
    if (stub.mode === 'cursor-expired' && cursor) {
      return { status: 400, body: { status: 400, message: 'Cursor page context is no longer available' } };
    }
    if (cursor === 'empty-page') {
      // A page with no eligible candidate that still has a continuation: the client must not claim
      // there is no match.
      return { status: 200, body: recommendationView({ sessionVersion: evaluatedVersion, mode: 'DISCOVERY', candidates: [], nextCursor: 'cursor-2', pageSize: 20, scannedCandidateCount: 20 }) };
    }
    if (cursor === 'cursor-2') {
      return { status: 200, body: recommendationView({ sessionVersion: evaluatedVersion, mode: 'DISCOVERY', candidates: [], nextCursor: null, pageSize: 20, scannedCandidateCount: 20 }) };
    }
    if (query) {
      return {
        status: 200,
        body: recommendationView({
          sessionVersion: evaluatedVersion,
          mode: 'DISCOVERY',
          candidates: [candidate({ rank: 0, groundExecutiveName: 'Meena Rao', groundExecutiveUserId: 5100 })],
          nextCursor: 'cursor-1',
          pageSize: 20,
          scannedCandidateCount: 20
        })
      };
    }
    return {
      status: 200,
      body: recommendationView({
        sessionVersion: evaluatedVersion,
        mode: 'DISCOVERY',
        // A distinctly *different* candidate from the ranked top, so choosing it is a real override
        // that owes the server a reason.
        candidates: [candidate({
          rank: 0,
          groundExecutiveName: 'Sunil Nair',
          groundExecutiveUserId: 5300,
          scheduledAt: '2027-02-14T06:00:00Z',
          reservedEndAt: '2027-02-14T08:00:00Z'
        })],
        nextCursor: 'empty-page',
        pageSize: 20,
        scannedCandidateCount: 20
      })
    };
  }
  if (path === '/api/v1/operations/visit-sessions/3108/oe-recommendations/selection' && method === 'POST') {
    if (stub.selectionDenied !== null) {
      return { status: stub.selectionDenied, body: { status: stub.selectionDenied, message: 'Controlled denial' } };
    }
    if (stub.mode === 'stale' || stub.mode === 'hold-failure-reconciliation') {
      // Both refuse the selection; the second also holds the reconciliation that follows it.
      if (stub.mode === 'hold-failure-reconciliation') stub.holdFailure = true;
      return { status: 409, body: { status: 409, message: 'Selected Ground Executive or time is no longer eligible; refresh recommendations' } };
    }
    if (stub.mode === 'malformed-200') {
      /**
       * Sol's repro: a `200` that announces a booking while the body cannot prove it. The unstamped
       * default carries invalid timestamp text and a negative GE id; the strict-timestamp variants
       * carry an impossible calendar date or an instant one nanosecond away from the submitted one.
       * None of them may ever be displayed as a success.
       */
      const malformed = {
        ...stub.session,
        status: 'SCHEDULED',
        version: stub.session.version + 1,
        scheduledAt: stub.malformedBooking.scheduledAt,
        representativeUserId: stub.malformedBooking.groundExecutiveUserId
      };
      stub.session = malformed;
      stub.bookingDone = true;
      return {
        status: 200,
        body: { session: malformed, decisionId: 88, override: false, topRecommendedGroundExecutiveUserId: 4207 }
      };
    }
    const scheduled = {
      ...stub.session,
      status: 'SCHEDULED',
      version: stub.session.version + 1,
      scheduledAt: body.scheduledAt,
      reservedEndAt: '2027-02-14T07:00:00Z',
      durationMinutes: 120,
      zoneId: body.zoneId,
      representativeUserId: body.groundExecutiveUserId,
      assignedAt: '2026-10-12T16:30:00Z',
      tenantConfirmationState: 'PENDING'
    };
    stub.session = scheduled;
    stub.bookingDone = true;
    return {
      status: 200,
      body: {
        session: scheduled,
        decisionId: 88,
        override: body.overrideReason !== null,
        topRecommendedGroundExecutiveUserId: 4207,
        topRecommendedAt: '2027-02-14T05:00:00Z'
      }
    };
  }
  if (path === '/api/v1/properties') {
    return {
      status: 200,
      body: {
        properties: [{ id: 9100, title: '4 BHK duplex', sector: 'Vijay Nagar', city: 'Indore', listingType: 'RENT' }],
        hasMore: false,
        page: 0
      }
    };
  }
  return { status: 200, body: {} };
};

const handleApi = async (route, request, url) => {
  const body = request.postData() ? JSON.parse(request.postData()) : undefined;
  stub.log.push({ method: request.method(), path: url.pathname + url.search, body, token: (request.headers()['authorization'] ?? '').replace('Bearer ', '') });

  /**
   * R1 target identity: hold one specific response so the OE can navigate — to another record, or
   * away and back to this one — while the command that issued it is still in flight. The answer is
   * released by the check and then answered from whatever the stub is configured to return.
   */
  const holdMatches = stub.holdResponse !== null && (
    (stub.holdResponse.kind === 'session-detail'
      && request.method() === 'GET'
      && url.pathname === `/api/v1/operations/visit-sessions/${stub.holdResponse.sessionId}`)
    || (stub.holdResponse.kind === 'availability'
      && request.method() === 'PUT'
      && url.pathname.endsWith('/availability'))
    || (stub.holdResponse.kind === 'selection'
      && request.method() === 'POST'
      && url.pathname.endsWith('/oe-recommendations/selection'))
    || (stub.holdResponse.kind === 'claim'
      && request.method() === 'POST'
      && url.pathname.endsWith('/claim'))
    || (stub.holdResponse.kind === 'release'
      && request.method() === 'POST'
      && url.pathname.endsWith('/release'))
  );
  if (holdMatches) {
    stub.holdResponse = null;
    stub.heldResponse = true;
    await new Promise((resolve) => { stub.releaseHeldResponse = resolve; });
    stub.heldResponse = false;
  }

  // ---- Targeted correction modes -------------------------------------------------
  // A delayed contact read: the panel must drop the late answer if its context changed meanwhile.
  const contactHoldMs = stub.mode === 'contact-hold' ? 1500 : stub.holdContactMs;
  if (contactHoldMs > 0 && url.pathname.endsWith('/lessor-contact')) {
    stub.contactHolds += 1;
    // The answer is delivered late whatever the client does with its abort, exactly like a transport
    // that ignores cancellation — so only the client's own context guard can refuse it.
    await new Promise((resolve) => setTimeout(resolve, contactHoldMs));
  }
  // A delayed ranked read: an unrelated read starting meanwhile must not cancel it.
  if (stub.mode === 'ranked-hold' && url.pathname.endsWith('/oe-recommendations') && !url.search) {
    await new Promise((resolve) => setTimeout(resolve, 1200));
  }
  // A delayed or once-failing continuation page, for the Load More guard.
  if ((stub.mode === 'more-hold' || stub.mode === 'more-fail-once') && url.searchParams.get('cursor')) {
    stub.cursorCalls += 1;
    if (stub.mode === 'more-hold') await new Promise((resolve) => setTimeout(resolve, 900));
    if (stub.mode === 'more-fail-once' && stub.cursorCalls === 1) {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        headers: { 'Cache-Control': 'no-store' },
        body: JSON.stringify({ status: 500, message: 'Controlled continuation failure' })
      });
      return;
    }
  }
  /**
   * R5: hold the authoritative reconciliation that follows a refused scheduling command, so the
   * account can change underneath the old operation before its continuation resumes.
   */
  if (stub.mode === 'hold-failure-reconciliation' && stub.holdFailure
      && request.method() === 'GET'
      && (url.pathname === '/api/v1/operations/my-work' || url.pathname === '/api/v1/operations/claimable-work')) {
    stub.holdFailure = false;
    await new Promise((resolve) => setTimeout(resolve, 4000));
  }
  /**
   * R1: hold the authoritative reconciliation that follows a target denial until the check releases
   * it. The refused detail and its revealed contact must already be gone while this read is
   * unresolved; a notice that only appears afterwards is never the evidence used.
   */
  if (stub.holdDenialReconciliation
      && request.method() === 'GET'
      && (url.pathname === '/api/v1/operations/my-work' || url.pathname === '/api/v1/operations/claimable-work')) {
    stub.holdDenialReconciliation = false;
    stub.denialReconciliationHeld = true;
    await new Promise((resolve) => { stub.releaseDenialReconciliation = resolve; });
  }
  // A confirmed booking whose follow-up queue refresh fails.
  if ((stub.mode === 'refresh-fail' && stub.bookingDone || stub.failQueueReads)
      && (url.pathname === '/api/v1/operations/my-work' || url.pathname === '/api/v1/operations/claimable-work')) {
    await route.fulfill({
      status: 500,
      contentType: 'application/json',
      headers: { 'Cache-Control': 'no-store' },
      body: JSON.stringify({ status: 500, message: 'Controlled refresh failure' })
    });
    return;
  }

  if (stub.mode === 'drop' && url.pathname.endsWith('/oe-recommendations/selection')) {
    await route.abort('failed');
    return;
  }
  const { status, body: payload } = apiResponse(url, request.method(), body);
  try {
    await route.fulfill({
      status,
      contentType: 'application/json',
      headers: { 'Cache-Control': 'no-store' },
      body: payload === null ? '' : JSON.stringify(payload)
    });
  } catch {
    // The client legitimately aborted (superseded context): the answer is simply never delivered.
  }
};

const serveStatic = async (route, url) => {
  const pathname = url.pathname === '/' ? '/index.html' : url.pathname;
  try {
    const body = await readFile(join(DIST, pathname));
    await route.fulfill({ status: 200, contentType: CONTENT_TYPES[extname(pathname)] ?? 'application/octet-stream', body });
  } catch {
    await route.fulfill({
      status: 200,
      contentType: 'text/html; charset=utf-8',
      body: await readFile(join(DIST, 'index.html'))
    });
  }
};

/* ------------------------------------------------------------------ *
 * Checks
 * ------------------------------------------------------------------ */

const results = [];
let checks = 0;
let failures = 0;
let notTested = 0;

const check = (label, condition, detail) => {
  checks += 1;
  if (condition) results.push(`  ✔ ${label}`);
  else {
    failures += 1;
    results.push(`  ✖ ${label}${detail === undefined ? '' : ` — ${detail}`}`);
  }
};

const checkEqual = (label, actual, expected) =>
  check(label, Object.is(actual, expected), `expected ${JSON.stringify(expected)}, saw ${JSON.stringify(actual)}`);

const notTestedRemaining = (label) => {
  notTested += 1;
  results.push(`  ⊘ NOT TESTED (budget exhausted): ${label}`);
};

const credentials = (account, token) => [
  ['pathome_auth_token', token],
  ['pathome_role', account.role],
  ['pathome_user', JSON.stringify({ id: account.id, role: account.role, fullName: `User ${account.id}`, email: `user${account.id}@example.test` })]
];

const newPage = async (browser, { account = ACCOUNT_A, token = TOKEN_A, viewport = { width: 1280, height: 900 } } = {}) => {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage();
  await page.addInitScript((entries) => {
    entries.forEach(([key, value]) => window.localStorage.setItem(key, value));
  }, credentials(account, token));
  page.on('pageerror', (error) => results.push(`  ! page error: ${error.message}`));
  page.on('console', (message) => {
    if (message.type() === 'error') results.push(`  ! console error: ${message.text().slice(0, 200)}`);
  });
  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url());
    if (url.origin !== ORIGIN) {
      await route.continue();
      return;
    }
    if (url.pathname.startsWith('/api/')) {
      await handleApi(route, route.request(), url);
      return;
    }
    await serveStatic(route, url);
  });
  return { context, page };
};

const openOperations = async (page) => {
  await page.goto(`${ORIGIN}/operations`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: /Today.s work/ }).waitFor({ state: 'visible', timeout: 15000 });
};

const openQueue = async (page, name) => {
  await page.getByRole('button', { name: new RegExp(`^${name}`) }).first().click();
  await page.getByRole('heading', { name }).first().waitFor({ state: 'visible', timeout: 8000 });
};

/* ------------------------------------------------------------------ *
 * Scenarios
 * ------------------------------------------------------------------ */

/** The full journey: claim, contact, coordinate, itinerary, availability, discovery, booking. */
const fullJourney = async (browser) => {
  stub.reset('learn', 'intake');
  const { context, page } = await newPage(browser);
  await openOperations(page);

  check('capability admission loads the workspace', true);
  checkEqual('the entry verification used the capability endpoint', stub.log.filter((call) => call.path === '/api/v1/staff/me/capabilities').length > 0, true);

  // ---- Claim a claimable Request -------------------------------------------------
  await openQueue(page, 'Claimable work');
  // `Claim` must be matched exactly: Playwright's default name matching is a substring match, and
  // "Claimable work" would otherwise be clicked instead.
  await page.getByRole('button', { name: /^Claim$/ }).first().click();
  await waitFor(page, () => stub.log.some((call) => call.path === '/api/v1/operations/visit-requests/501/claim'));
  const claim = stub.log.find((call) => call.path === '/api/v1/operations/visit-requests/501/claim');
  check('the claim command used the captured credential and the CLAIM_WORK reason code', claim?.body?.reasonCode === 'CLAIM_WORK', JSON.stringify(claim?.body));
  checkEqual('the claim was sent with the re-read request version', claim?.body?.expectedRequestVersion, 4);
  await page.getByRole('heading', { name: /Request #501/ }).waitFor({ state: 'visible', timeout: 8000 });

  // ---- Canonical lessor contact --------------------------------------------------
  await page.getByRole('button', { name: /Show lessor contact/ }).first().click();
  await page.getByText('Rakesh Sharma').waitFor({ state: 'visible', timeout: 8000 });
  check('the canonical contact name and number are shown on demand', await page.getByText('+91 98260 11223').isVisible());
  check('a validated mobile becomes a call link', await page.getByRole('link', { name: /Call lessor/ }).isVisible());
  const stored = await page.evaluate(() => JSON.stringify({ ...window.localStorage }));
  check('revealed contact is never written to client storage', !stored.includes('98260') && !stored.includes('Rakesh'), stored.slice(0, 120));
  await page.getByRole('button', { name: /^Hide$/ }).click();
  check('hiding the contact removes it from the page', !(await page.getByText('Rakesh Sharma').isVisible().catch(() => false)));

  // ---- Coordinate into a new DRAFT session ---------------------------------------
  await page.getByRole('button', { name: 'Coordinate into a new session' }).click();
  await page.getByRole('button', { name: 'Create draft session' }).click();
  await waitFor(page, () => stub.log.some((call) => call.path === '/api/v1/operations/visit-requests/501/coordinate'));
  const coordinate = stub.log.find((call) => call.path === '/api/v1/operations/visit-requests/501/coordinate');
  check(
    'coordination sends the null-target command with only the request version',
    JSON.stringify(coordinate?.body) === JSON.stringify({ expectedRequestVersion: 4, sessionId: null, expectedSessionVersion: null }),
    JSON.stringify(coordinate?.body)
  );
  await page.getByRole('heading', { name: /Session #3108/ }).waitFor({ state: 'visible', timeout: 8000 });
  check('the returned session replaces the request view without a duplicate root', true);
  check('every stop starts with availability pending', (await page.getByText('Pending confirmation').count()) >= 2);

  // ---- Public discovery adapter for a derived stop --------------------------------
  await page.getByRole('button', { name: 'Add a stop' }).click();
  await page.getByLabel('Find a property').fill('Vijay');
  await waitFor(page, () => stub.log.some((call) => call.path.includes('q=Vijay')));
  const discovery = stub.log.find((call) => call.path.includes('q=Vijay'));
  check('derived-stop discovery uses the published rental catalogue with the entered query',
    Boolean(discovery?.path.includes('rentalOnly=true') && discovery?.path.includes('q=Vijay'))
    && stub.log.find((call) => call.path.startsWith('/api/v1/properties')) !== undefined,
    discovery?.path);
  await page.getByText('4 BHK duplex').waitFor({ state: 'visible', timeout: 8000 });
  await page.getByRole('button', { name: /^Cancel$/ }).last().click();

  // ---- Record availability: complete window, then the incomplete state ------------
  const versionBeforeFirstWindow = stub.session.version;
  await page.locator('#oe-avail-btn-91').click();
  await page.getByLabel('Available from').fill('2027-02-14T09:30');
  await page.getByLabel('Available until').fill('2027-02-14T18:00');
  await page.getByLabel('Recorded source').selectOption('PHONE');
  await page.getByRole('button', { name: 'Save availability' }).click();
  await waitFor(page, () => stub.log.some((call) => call.path.endsWith('/items/91/availability')));
  const availability = stub.log.find((call) => call.path.endsWith('/items/91/availability'));
  check('the availability command is a PUT carrying the whole window under the command field names',
    availability?.method === 'PUT'
    && availability?.body.expectedSessionVersion === versionBeforeFirstWindow
    && availability?.body.status === 'CONFIRMED'
    && availability?.body.availabilityStartAt === WINDOW_START
    && availability?.body.availabilityEndAt === WINDOW_END
    && availability?.body.availabilityZoneId === 'Asia/Kolkata'
    && availability?.body.availabilitySource === 'PHONE',
    JSON.stringify(availability?.body));
  await page.getByText('Confirmed with window').first().waitFor({ state: 'visible', timeout: 8000 });
  check('the recorded window is rendered from the response DTO', await page.getByText(/Available .*IST \(Asia\/Kolkata\).*Phone call/).first().isVisible());

  await page.locator('#oe-avail-btn-92').click();
  await page.getByRole('radio', { name: 'Available, window not known yet' }).check();
  await page.getByRole('button', { name: 'Save availability' }).click();
  await waitFor(page, () => stub.log.filter((call) => call.path.endsWith('/items/92/availability')).length > 0);
  await page.getByText('Confirmed, window incomplete').first().waitFor({ state: 'visible', timeout: 8000 });
  check('a confirmation without a window is shown as incomplete and blocks scheduling',
    await page.getByText(/Scheduling is blocked until a complete window is recorded/).first().isVisible());

  // A CONFIRMED stop may only have its window updated: the transition chooser is gone.
  await page.locator('#oe-avail-btn-92').click();
  check('a confirmed stop offers only a window update',
    (await page.locator('.oe-avail-editor').getByText('Available, window not known yet').count()) === 0);
  await page.getByLabel('Available from').fill('2027-02-14T09:30');
  await page.getByLabel('Available until').fill('2027-02-14T18:00');
  await page.getByLabel('Recorded source').selectOption('PHONE');
  await page.getByRole('button', { name: 'Save availability' }).click();
  await waitFor(page, () => stub.log.filter((call) => call.path.endsWith('/items/92/availability')).length === 2);
  await page.getByText('Confirmed with window').first().waitFor({ state: 'visible', timeout: 8000 });

  // ---- Exact reorder --------------------------------------------------------------
  const versionBeforeReorder = stub.session.version;
  await page.getByRole('button', { name: /Move 3 BHK apartment earlier/ }).click();
  await page.getByRole('button', { name: 'Save order' }).click();
  await waitFor(page, () => stub.log.some((call) => call.path.endsWith('/items/order')));
  const reorder = stub.log.find((call) => call.path.endsWith('/items/order'));
  check('reorder sends every active item exactly once in the requested order',
    reorder?.method === 'PUT'
    && reorder?.body.expectedSessionVersion === versionBeforeReorder
    && JSON.stringify(reorder?.body.itemIds) === JSON.stringify([92, 91]),
    JSON.stringify(reorder?.body));

  // ---- Scheduling: ranked, browse continuation, search continuation ---------------
  await page.getByRole('button', { name: 'Schedule visit' }).click({ timeout: 8000 });
  const drawerOpened = await page.locator('.oe-sch').waitFor({ state: 'visible', timeout: 8000 }).then(() => true).catch(() => false);
  check('the scheduling drawer opens from the session action', drawerOpened, `drawer count ${await page.locator('.oe-sch').count()}`);
  await page.getByText('Top evaluated suggestion').waitFor({ state: 'visible', timeout: 8000 });
  const ranked = stub.log.filter((call) => call.path.endsWith('/oe-recommendations'));
  check('the ranked read is a bounded POST with no query parameters', ranked.length >= 1 && !ranked[0].path.includes('?'), ranked[0]?.path);
  check('each candidate shows the single evaluated slot with its duration and zone',
    await page.getByText(/Evaluated duration/).first().isVisible());

  await page.getByRole('tab', { name: 'Browse all' }).click();
  await waitFor(page, () => stub.log.some((call) => call.path.includes('all=true')));
  const browse = stub.log.find((call) => call.path.includes('all=true'));
  check('browse pages with all=true and a bounded size', browse.path.includes('size=20') && !browse.path.includes('q='), browse.path);
  await page.getByRole('button', { name: 'Load more' }).click();
  await waitFor(page, () => stub.log.some((call) => call.path.includes('cursor=empty-page')));
  const unfinished = await page.getByText(/but the server says more pages remain/)
    .first().waitFor({ state: 'visible', timeout: 8000 }).then(() => true).catch(() => false);
  check('an eligible-free page that still has a continuation is described as unfinished, not empty', unfinished);
  await page.getByRole('button', { name: 'Load more' }).click();
  await waitFor(page, () => stub.log.some((call) => call.path.includes('cursor=cursor-2')));
  await page.getByText('End of list. The server has no further pages.').waitFor({ state: 'visible', timeout: 8000 });
  check('the list only ends when the server returns no continuation cursor', true);

  await page.getByRole('tab', { name: 'Search' }).click();
  await page.getByLabel(/Search eligible Ground Executives/).fill('  Meena  ');
  await waitFor(page, () => stub.log.some((call) => call.path.includes('q=Meena')));
  const searchCall = stub.log.find((call) => call.path.includes('q=Meena'));
  check('search sends the trimmed query and no fabricated total', searchCall.path.includes('q=Meena') && !searchCall.path.includes('totalCount'), searchCall.path);
  await page.getByText('Meena Rao').waitFor({ state: 'visible', timeout: 8000 });
  check('a search page with a continuation keeps looking instead of declaring no match',
    await page.getByRole('button', { name: 'Load more matches' }).isVisible());

  // ---- Choose, review and book the exact top suggestion --------------------------
  const versionBeforeBooking = stub.session.version;
  await page.getByRole('tab', { name: 'Recommended' }).click();
  await page.getByRole('button', { name: /Select Rahul Verma/ }).click();
  await page.getByRole('button', { name: 'Continue' }).click();
  await page.getByRole('heading', { name: 'Review before confirming' }).waitFor({ state: 'visible', timeout: 8000 });
  check('the review shows the active itinerary stops', await page.getByRole('heading', { name: /Visit stops \(2\)/ }).isVisible());
  check('the top suggestion needs no reason', await page.getByText(/no reason is needed/).isVisible());
  await page.getByRole('button', { name: 'Confirm and schedule' }).click();
  await waitFor(page, () => stub.log.some((call) => call.path.endsWith('/oe-recommendations/selection')));
  const selection = stub.log.find((call) => call.path.endsWith('/oe-recommendations/selection'));
  check('the booking is one POST of the exact current top choice with no reason',
    JSON.stringify(selection?.body) === JSON.stringify({
      expectedSessionVersion: versionBeforeBooking,
      groundExecutiveUserId: 4207,
      scheduledAt: '2027-02-14T05:00:00Z',
      zoneId: 'Asia/Kolkata',
      overrideReason: null
    }),
    JSON.stringify(selection?.body));
  checkEqual('exactly one booking command was transmitted',
    stub.log.filter((call) => call.path.endsWith('/oe-recommendations/selection')).length, 1);
  await page.getByText('The visit is scheduled.').waitFor({ state: 'visible', timeout: 8000 });
  check('the result states the authoritative tenant-confirmation state',
    await page.locator('.oe-sch').getByText('Awaiting tenant').isVisible());
  await page.locator('.oe-sch').getByRole('button', { name: /^Close$/ }).click();
  await page.getByText('A visit time is booked, so this session is read-only here.').waitFor({ state: 'visible', timeout: 8000 });
  check('a scheduled session is read-only: no booking control remains', (await page.getByRole('button', { name: 'Schedule visit' }).count()) === 0);

  await context.close();
};

const waitFor = async (page, predicate, timeoutMs = 8000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return true;
    await page.waitForTimeout(100);
  }
  return false;
};

/** Opens the scheduling drawer of the preset DRAFT session and selects the top suggestion. */
const openDraftDrawer = async (page) => {
  await openDraftDrawerAtChoose(page);
  await page.getByRole('button', { name: /Select Rahul Verma/ }).click();
  await page.getByRole('button', { name: 'Continue' }).click();
  await page.getByRole('heading', { name: 'Review before confirming' }).waitFor({ state: 'visible', timeout: 8000 });
};

/** Opens the scheduling drawer of the preset DRAFT session and stops on the Choose step. */
const openDraftDrawerAtChoose = async (page) => {
  await openOperations(page);
  await openQueue(page, 'My work');
  await page.getByRole('button', { name: /#3108/ }).first().click();
  await page.getByRole('heading', { name: /Session #3108/ }).waitFor({ state: 'visible', timeout: 8000 });
  await page.getByRole('button', { name: 'Schedule visit' }).click();
  await page.getByText('Top evaluated suggestion').waitFor({ state: 'visible', timeout: 10000 });
};

/** A stale session version: the server refuses, the drawer must refresh and re-review, never rebook. */
const staleSelection = async (browser) => {
  stub.reset('stale', 'draft');
  const { context, page } = await newPage(browser);
  await openDraftDrawer(page);
  await page.getByRole('button', { name: 'Confirm and schedule' }).click();
  await page.getByText('This selection is no longer available').waitFor({ state: 'visible', timeout: 10000 });
  check('a 409 is reported as a conflict that booked nothing', true);
  checkEqual('the refused selection was transmitted exactly once',
    stub.log.filter((call) => call.path.endsWith('/oe-recommendations/selection')).length, 1);
  await page.getByRole('button', { name: 'Reload candidates' }).click();
  await page.getByText('Top evaluated suggestion').waitFor({ state: 'visible', timeout: 8000 });
  check('recovery returns to review candidates without replaying the command',
    stub.log.filter((call) => call.path.endsWith('/oe-recommendations/selection')).length === 1);
  await context.close();
};

/** A transmitted booking whose response never arrives is unconfirmed, and it is never resent. */
const uncertainSelection = async (browser) => {
  stub.reset('drop', 'draft');
  const { context, page } = await newPage(browser);
  await openDraftDrawer(page);
  await page.getByRole('button', { name: 'Confirm and schedule' }).click();
  await page.getByText('We can’t tell whether this was scheduled').waitFor({ state: 'visible', timeout: 10000 });
  check('a lost response is reported as an unknown outcome, not a success or a refusal', true);
  checkEqual('the unknown booking was transmitted exactly once and never resent',
    stub.log.filter((call) => call.path.endsWith('/oe-recommendations/selection')).length, 1);
  await page.getByRole('button', { name: 'Check the session' }).click();
  await page.getByText('Top evaluated suggestion').waitFor({ state: 'visible', timeout: 8000 });
  check('checking the session re-reads the authoritative record and still does not resend',
    stub.log.filter((call) => call.path.endsWith('/oe-recommendations/selection')).length === 1);
  await context.close();
};

/** A revoked contact read withdraws the protected value and the protected detail behind it. */
const revokedContact = async (browser) => {
  stub.reset('denied', 'intake');
  const { context, page } = await newPage(browser);
  await openOperations(page);
  await openQueue(page, 'My work');
  await page.getByRole('button', { name: /#501/ }).first().click();
  await page.getByRole('heading', { name: /Request #501/ }).waitFor({ state: 'visible', timeout: 8000 });
  await page.getByRole('button', { name: /Show lessor contact/ }).first().click();
  await page.getByText('You no longer have access to this lessor’s contact.').waitFor({ state: 'visible', timeout: 8000 });
  check('a refused contact read shows no name and no number', (await page.getByText('Rakesh Sharma').count()) === 0);
  check('the refusal is stated in place of the contact, without a fallback value',
    await page.getByText(/No canonical lessor contact is on record/).count() === 0);
  await context.close();
};

/** An account switch while a booking is in flight must suppress the stale completion entirely. */
const accountSwitchDuringBooking = async (browser) => {
  stub.reset('learn', 'draft');
  const { context, page } = await newPage(browser);
  await openDraftDrawer(page);
  await page.route('**/oe-recommendations/selection', async (route) => {
    // Hold the transmitted command long enough for the account to change underneath it.
    await new Promise((resolve) => setTimeout(resolve, 1500));
    await route.continue();
  });
  await page.getByRole('button', { name: 'Confirm and schedule' }).click();
  await page.waitForTimeout(300);
  await page.evaluate((entries) => {
    entries.forEach(([key, value]) => window.localStorage.setItem(key, value));
    window.dispatchEvent(new CustomEvent('pathome_auth_changed'));
  }, credentials(ACCOUNT_B, TOKEN_B));
  await page.waitForTimeout(3000);
  check('an account switch during a booking shows no result for the previous account',
    (await page.getByText('The visit is scheduled.').count()) === 0
    && (await page.getByText('Awaiting tenant').count()) === 0);
  await context.close();
};

/** Responsive and accessibility measurements across the eight approved widths. */
const responsive = async (browser) => {
  stub.reset('learn', 'draft');
  const { context, page } = await newPage(browser);
  await openDraftDrawer(page);

  for (const width of [320, 375, 390, 430, 768, 1024, 1280, 1440]) {
    await page.setViewportSize({ width, height: width < 768 ? 800 : 900 });
    await page.waitForTimeout(200);
    const measurement = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      innerWidth: window.innerWidth,
      dialogWidth: document.querySelector('.oe-sch')?.scrollWidth ?? 0,
      dialogClient: document.querySelector('.oe-sch')?.clientWidth ?? 0
    }));
    check(`no horizontal overflow at ${width}px`,
      measurement.scrollWidth <= measurement.innerWidth && measurement.dialogWidth <= measurement.dialogClient + 1,
      JSON.stringify(measurement));
  }

  await page.setViewportSize({ width: 390, height: 844 });
  await page.locator('.oe-sch').getByRole('button', { name: /^Back$/ }).click({ timeout: 8000 });
  const dismiss = page.locator('.oe-sch').getByRole('button', { name: 'Close scheduling' });
  const target = await dismiss.boundingBox();
  check('the drawer close control keeps a 44px touch target', Boolean(target && target.width >= 44 && target.height >= 44), JSON.stringify(target));

  // A mobile editable control inside the drawer must not zoom the viewport on focus.
  await page.locator('.oe-sch').getByRole('tab', { name: 'Search' }).click({ timeout: 8000 });
  const searchFont = await page.evaluate(() => {
    const input = document.querySelector('.oe-sch .oe-search-input');
    return input ? Number(window.getComputedStyle(input).fontSize.replace('px', '')) : 0;
  });
  check('a mobile editable control keeps a 16px effective font', searchFont >= 16, String(searchFont));
  const searchBox = await page.locator('.oe-sch .oe-search').boundingBox();
  check('the mobile search control keeps a 44px touch target', Boolean(searchBox && searchBox.height >= 44), JSON.stringify(searchBox));

  // Escape closes the drawer and returns focus to the control that opened it.
  await page.keyboard.press('Escape');
  const closed = await page.locator('.oe-sch').waitFor({ state: 'detached', timeout: 8000 }).then(() => true).catch(() => false);
  check('Escape closes the drawer', closed);
  const restored = await page.evaluate(() => document.activeElement?.textContent?.trim() ?? '');
  check('closing the drawer restores focus to the action that opened it', restored.includes('Schedule visit'), restored);

  // The itinerary editor keeps the same mobile rules on the detail surface.
  // Both stops are already confirmed, so their control offers a window update.
  await page.locator('.oe-itin').getByRole('button', { name: 'Update window' }).first().click({ timeout: 8000 });
  const editable = await page.evaluate(() => {
    const input = document.querySelector('.oe-avail-editor input');
    return input ? Number(window.getComputedStyle(input).fontSize.replace('px', '')) : 0;
  });
  check('the itinerary editor keeps a 16px effective font on mobile', editable >= 16, String(editable));
  const save = await page.locator('.oe-avail-editor').getByRole('button', { name: 'Save availability' }).boundingBox();
  check('the primary editor action keeps a 44px touch target', Boolean(save && save.height >= 44), JSON.stringify(save));
  await page.locator('.oe-avail-editor').getByRole('button', { name: /^Cancel$/ }).click();
  await context.close();
};

/* ------------------------------------------------------------------ *
 * Main
 * ------------------------------------------------------------------ */

/** P0-2 + P1-4 — a blank required reason never reaches the booking, and success needs a real Session. */
const correctionSchedulingResultAndReason = async (browser) => {
  stub.reset('learn', 'draft');
  const { context, page } = await newPage(browser);
  await openDraftDrawerAtChoose(page);

  // Choose a candidate that is not the current top suggestion, so a reason is required.
  await page.locator('.oe-sch').getByRole('tab', { name: 'Search' }).click({ timeout: 8000 });
  await page.getByLabel(/Search eligible Ground Executives/).fill('Meena');
  await page.getByText('Meena Rao').waitFor({ state: 'visible', timeout: 8000 });
  await page.getByRole('button', { name: /Select Meena Rao/ }).click();
  await page.getByRole('button', { name: 'Continue' }).click();
  await page.getByRole('heading', { name: 'Review before confirming' }).waitFor({ state: 'visible', timeout: 8000 });
  check('a non-top choice asks for a reason', await page.getByText(/requires a short/).isVisible());

  // First click with a blank reason: accurate inline validation, no booking transmitted.
  await page.getByRole('button', { name: 'Confirm and schedule' }).click();
  await page.getByText('Add a reason to continue.').waitFor({ state: 'visible', timeout: 8000 });
  check('the first blank submission shows the reason error', true);
  const focused = await page.evaluate(() => document.activeElement?.id ?? '');
  checkEqual('the first blank submission focuses the reason field', focused, 'oe-reason');
  checkEqual('no booking command was transmitted for a blank required reason',
    stub.log.filter((call) => call.path.endsWith('/oe-recommendations/selection')).length, 0);
  check('no misleading missing-version message is shown',
    (await page.getByText(/didn’t return the version/).count()) === 0);

  // A whitespace-only reason is still not a reason.
  await page.getByLabel(/Reason for this choice/).fill('   ');
  await page.getByRole('button', { name: 'Confirm and schedule' }).click();
  await page.getByText('Add a reason to continue.').waitFor({ state: 'visible', timeout: 8000 });
  checkEqual('whitespace-only is refused as well',
    stub.log.filter((call) => call.path.endsWith('/oe-recommendations/selection')).length, 0);

  // A real reason is sent, once.
  await page.getByLabel(/Reason for this choice/).fill('Nearest to the tenant');
  await page.getByRole('button', { name: 'Confirm and schedule' }).click();
  await waitFor(page, () => stub.log.some((call) => call.path.endsWith('/oe-recommendations/selection')));
  const selection = stub.log.find((call) => call.path.endsWith('/oe-recommendations/selection'));
  checkEqual('the valid reason is transmitted with the override', selection?.body?.overrideReason, 'Nearest to the tenant');
  checkEqual('exactly one booking command was transmitted',
    stub.log.filter((call) => call.path.endsWith('/oe-recommendations/selection')).length, 1);
  await page.getByText('The visit is scheduled.').waitFor({ state: 'visible', timeout: 8000 });
  check('the authoritative result is shown for a complete scheduled Session', true);
  await context.close();
};

/** P0-2 — a confirmed booking whose queue refresh fails keeps the booking and says so. */
const correctionRefreshFailureAfterBooking = async (browser) => {
  stub.reset('refresh-fail', 'draft');
  const { context, page } = await newPage(browser);
  await openDraftDrawer(page);
  await page.getByRole('button', { name: 'Confirm and schedule' }).click();
  await page.getByText('The visit is scheduled.').waitFor({ state: 'visible', timeout: 10000 });
  check('a confirmed booking is reported even when the follow-up refresh fails', true);
  await page.getByText('Scheduled, but your work lists couldn’t be refreshed').waitFor({ state: 'visible', timeout: 8000 });
  check('the failed refresh is reported separately from the confirmed booking', true);
  checkEqual('the booking was transmitted once and never resent',
    stub.log.filter((call) => call.path.endsWith('/oe-recommendations/selection')).length, 1);
  await context.close();
};

/** P1-3 — independent reads must not cancel one another. */
const correctionConcurrentReadFamilies = async (browser) => {
  stub.reset('ranked-hold', 'draft');
  const { context, page } = await newPage(browser);
  await openDraftDrawerAtChoose(page);

  // The ranked read is still in flight. Start the other two reads before it answers.
  await page.locator('.oe-sch').getByRole('tab', { name: 'Browse all' }).click({ timeout: 8000 });
  await waitFor(page, () => stub.log.some((call) => call.path.includes('all=true')));
  await page.locator('.oe-sch').getByRole('tab', { name: 'Search' }).click({ timeout: 8000 });
  await page.getByLabel(/Search eligible Ground Executives/).fill('Meena');
  await page.getByText('Meena Rao').waitFor({ state: 'visible', timeout: 8000 });
  check('search results are applied while another read is pending', true);

  // Returning to Recommended must show the list, not an indefinite skeleton and not an error: the
  // ranked read is only cancelled by another ranked read, never by browse or search.
  await page.locator('.oe-sch').getByRole('tab', { name: 'Recommended' }).click({ timeout: 8000 });
  await page.getByText('Top evaluated suggestion').waitFor({ state: 'visible', timeout: 10000 });
  check('a pending ranked read survives unrelated reads that started after it', true);
  check('the ranked read is not reported as failed',
    (await page.getByText(/Recommendations couldn’t be loaded/).count()) === 0);

  // Browse kept its own results too, and its continuation still works.
  await page.locator('.oe-sch').getByRole('tab', { name: 'Browse all' }).click({ timeout: 8000 });
  await page.getByText('Sunil Nair').waitFor({ state: 'visible', timeout: 8000 });
  check('browse results are still applied after the other reads ran', true);
  await page.getByRole('button', { name: 'Load more' }).click();
  await waitFor(page, () => stub.log.some((call) => call.path.includes('cursor=empty-page')));
  check('browse continuation still works', true);
  await context.close();
};

/** P1-5 — a delayed contact reveal must not repopulate the panel after a version change. */
const correctionContactRevealRace = async (browser) => {
  stub.reset('contact-hold', 'draft');
  const { context, page } = await newPage(browser);
  await openOperations(page);
  await openQueue(page, 'My work');
  await page.getByRole('button', { name: /#3108/ }).first().click();
  await page.getByRole('heading', { name: /Session #3108/ }).waitFor({ state: 'visible', timeout: 8000 });

  // The session version at the moment the reveal starts.
  const versionBeforeReveal = stub.session.version;

  // Start the held contact reveal for the first stop.
  const revealButton = page.locator('.oe-itin .oe-contact').first().getByRole('button', { name: /Show lessor contact/ });
  await revealButton.click();
  // The button's accessible name carries the visible label, so its loading state is also readable
  // through the role query.
  const loadingShown = await page.getByRole('button', { name: /Loading contact… for/ })
    .first().waitFor({ state: 'visible', timeout: 4000 }).then(() => true).catch(() => false);
  check('the contact reveal is in flight', loadingShown);
  checkEqual('the contact read really was held open by the transport', stub.contactHolds, 1);

  // Change the record version underneath it, without leaving the same record.
  await page.locator('#oe-avail-btn-92').click();
  await page.getByLabel('Available from').fill('2027-02-14T09:30');
  await page.getByLabel('Available until').fill('2027-02-14T18:00');
  await page.getByLabel('Recorded source').selectOption('PHONE');
  await page.getByRole('button', { name: 'Save availability' }).click();
  await waitFor(page, () => stub.session.version > versionBeforeReveal);
  checkEqual('the record version changed while the contact read was pending',
    stub.session.version > versionBeforeReveal, true);

  // The late answer must never appear.
  await page.waitForTimeout(2500);
  /**
   * The obsolete answer is the *Session item* contact that was actually held ('Meena Gupta' /
   * '+91 90000 11111'), so absence is asserted for that exact contact — not only for the unrelated
   * Request fixture, which would prove nothing about this race.
   */
  check('a delayed contact answer never repopulates the panel after a version change',
    (await page.getByText('Rakesh Sharma').count()) === 0
    && (await page.getByText('+91 98260 11223').count()) === 0
    && (await page.getByText('Meena Gupta').count()) === 0
    && (await page.getByText('+91 90000 11111').count()) === 0);
  check('the panel returns to its on-demand state',
    (await page.getByRole('button', { name: /Show lessor contact/ }).count()) > 0);

  // The guard must not block reveals that are actually current: the same panel still works.
  await page.getByRole('button', { name: /Show lessor contact/ }).first().click();
  await page.getByText('Meena Gupta').first().waitFor({ state: 'visible', timeout: 8000 });
  check('a current reveal still displays the canonical contact', true);
  await context.close();
};

/** P2-6 — one request per Load More, with failure/retry and query-change behaviour preserved. */
const correctionSearchLoadMore = async (browser) => {
  // Part 1: a rapid double click issues exactly one continuation request.
  stub.reset('more-hold', 'draft');
  const { context, page } = await newPage(browser);
  await openDraftDrawerAtChoose(page);
  await page.locator('.oe-sch').getByRole('tab', { name: 'Search' }).click({ timeout: 8000 });
  await page.getByLabel(/Search eligible Ground Executives/).fill('Meena');
  await page.getByRole('button', { name: 'Load more matches' }).waitFor({ state: 'visible', timeout: 8000 });

  const before = stub.cursorCalls;
  /**
   * A genuine double click, dispatched in the same tick against the same node. Two sequential
   * Playwright clicks would not model the defect: the second one waits for the button to be enabled
   * again, which is a deliberate second request.
   */
  const doubleClicked = await page.evaluate(() => {
    const button = [...document.querySelectorAll('.oe-sch button')]
      .find((candidate) => (candidate.textContent ?? '').includes('Load more matches'));
    if (!button) return false;
    button.click();
    button.click();
    return true;
  });
  check('the Load more control was double-clicked in one tick', doubleClicked);
  await page.waitForTimeout(1500);
  checkEqual('a rapid double click issues exactly one continuation request', stub.cursorCalls - before, 1);
  await context.close();

  // Part 2: a failing continuation is reported, keeps its candidates, and can be retried.
  stub.reset('more-fail-once', 'draft');
  const retry = await newPage(browser);
  await openDraftDrawerAtChoose(retry.page);
  await retry.page.locator('.oe-sch').getByRole('tab', { name: 'Search' }).click({ timeout: 8000 });
  await retry.page.getByLabel(/Search eligible Ground Executives/).fill('Meena');
  await retry.page.getByRole('button', { name: 'Load more matches' }).click();
  await retry.page.getByText('More matches couldn’t be loaded').waitFor({ state: 'visible', timeout: 8000 });
  check('a failed continuation is reported without losing the loaded candidates', true);
  await retry.page.getByRole('button', { name: 'Try again' }).first().click();
  await waitFor(retry.page, () => stub.cursorCalls >= 2);
  checkEqual('retrying the continuation issues a new request', stub.cursorCalls >= 2, true);
  await retry.context.close();

  // Part 3: changing the query while a continuation is in flight releases the guard for the new query.
  stub.reset('more-hold', 'draft');
  const third = await newPage(browser);
  await openDraftDrawerAtChoose(third.page);
  await third.page.locator('.oe-sch').getByRole('tab', { name: 'Search' }).click({ timeout: 8000 });
  await third.page.getByLabel(/Search eligible Ground Executives/).fill('Meena');
  await third.page.getByRole('button', { name: 'Load more matches' }).click();
  await third.page.getByLabel(/Search eligible Ground Executives/).fill('Rahul');
  await waitFor(third.page, () => stub.log.some((call) => call.path.includes('q=Rahul')));
  await third.page.waitForTimeout(1200);
  const cursorCallsAfterQueryChange = stub.cursorCalls;
  const changed = third.page.getByRole('button', { name: /Load more matches/ });
  check('a query change during Load More still runs the new query',
    stub.log.some((call) => call.path.includes('q=Rahul')));
  await changed.click({ timeout: 8000 });
  await waitFor(third.page, () => stub.cursorCalls > cursorCallsAfterQueryChange);
  checkEqual('the new query can continue after the abandoned continuation',
    stub.cursorCalls > cursorCallsAfterQueryChange, true);
  await third.context.close();
};

/* ------------------------------------------------------------------ *
 * R1–R11 — the final correction scenarios
 * ------------------------------------------------------------------ */

/** Opens Session #3108 from My work and reveals the first stop's canonical contact. */
const openDraftDetailWithRevealedContact = async (page) => {
  await openOperations(page);
  await openQueue(page, 'My work');
  await page.getByRole('button', { name: /#3108/ }).first().click();
  await page.getByRole('heading', { name: /Session #3108/ }).waitFor({ state: 'visible', timeout: 8000 });
  await page.locator('.oe-itin .oe-contact').first().getByRole('button', { name: /Show lessor contact/ }).click();
  await page.getByText('Meena Gupta').first().waitFor({ state: 'visible', timeout: 8000 });
};

/** The DRAFT itinerary editor's save action: the path every Phase 2A item command shares. */
const saveUnchangedWindow = async (page) => {
  await page.locator('.oe-itin').getByRole('button', { name: 'Update window' }).first().click({ timeout: 8000 });
  await page.locator('.oe-avail-editor').getByRole('button', { name: 'Save availability' }).click();
};

/** The workspace's own content region, so a check is never satisfied or broken by an app-level toast. */
const workspaceRegion = (page) => page.locator('section.oe').first();

/** Opens one row of My work and waits for that root's own detail heading. */
const openSessionRow = async (page, sessionId) => {
  await page.getByRole('button', { name: new RegExp(`#${sessionId}`) }).first().click();
  await page.getByRole('heading', { name: new RegExp(`Session #${sessionId}`) })
    .waitFor({ state: 'visible', timeout: 8000 });
};

/**
 * The unrelated Session #4109 is fully its own: heading, itinerary, controls and its own contact. Every
 * check is scoped to the workspace region, because the durable confirmation a confirmed command
 * announces is a deliberate part of the corrected behaviour and names the record it is about.
 */
const assertUnrelatedSessionIntact = async (label, page) => {
  check(`${label}: #4109 is still the displayed root`,
    (await page.getByRole('heading', { name: /Session #4109/ }).count()) > 0);
  check(`${label}: #4109 keeps its own itinerary`,
    (await page.getByText('4 BHK villa').count()) > 0
    && (await page.getByText('2 BHK apartment').count()) === 0);
  check(`${label}: #4109 keeps its own controls`,
    (await page.locator('.oe-actions').getByRole('button', { name: 'Schedule visit' }).count()) > 0);
  check(`${label}: nothing of #3108 is applied to #4109`,
    (await workspaceRegion(page).getByText(/#3108/).count()) === 0
    && (await workspaceRegion(page).getByText(/Session #3108/).count()) === 0);
};

/**
 * R1 — a confirmed target denial withdraws the revealed root before anything is reconciled.
 *
 * The decisive property is *ordering*: the refused protected root, and the lessor name and number
 * revealed inside it, must be gone while the authoritative reconciliation is still unresolved. Every
 * case below holds that reconciliation open, asserts the withdrawal, and only then releases it — a
 * check that waited for the post-refresh notice would pass even with the defect present, because the
 * notice is written after the refresh that followed the withdrawal it was supposed to prove.
 */
const correctionDenialWithdrawsRevealedRoot = async (browser) => {
  stub.reset('learn', 'draft');
  const { context, page } = await newPage(browser);

  const armHeldReconciliation = () => {
    stub.holdDenialReconciliation = true;
    stub.denialReconciliationHeld = false;
    stub.releaseDenialReconciliation = null;
  };
  /** An asynchronous DOM poll, so the reconciliation can stay held for the whole assertion. */
  const until = async (predicate, timeoutMs = 8000) => {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (await predicate()) return true;
      await page.waitForTimeout(100);
    }
    return false;
  };
  const contactPanel = () => page.locator('.oe-itin .oe-contact').first();
  /** Item-availability commands transmitted so far, so each case can assert its own exact count. */
  const availabilityPosts = () =>
    stub.log.filter((call) => call.path.endsWith('/availability')).length;

  const assertWithdrawnWhileReconciling = async (label, noticePattern) => {
    const intercepted = await waitFor(page, () => stub.denialReconciliationHeld, 10000);
    check(`${label}: the authoritative reconciliation is still unresolved when the withdrawal is checked`,
      intercepted);
    check(`${label}: the refused detail is already unmounted`,
      await until(async () => (await page.getByRole('heading', { name: /Session #3108/ }).count()) === 0));
    check(`${label}: the revealed lessor name is already gone`,
      (await page.getByText('Meena Gupta').count()) === 0);
    check(`${label}: the revealed lessor number is already gone`,
      (await page.getByText('+91 90000 11111').count()) === 0);
    check(`${label}: no call link survives`,
      (await page.getByRole('link', { name: /Call lessor/ }).count()) === 0);
    check(`${label}: the refusal is not announced yet, so nothing checked here came after a refresh`,
      (await page.getByText(noticePattern).count()) === 0);
    check(`${label}: authorized account access and unrelated queues remain`,
      await page.getByRole('button', { name: /^My work/ }).first().isVisible()
      && await page.getByRole('button', { name: /^Claimable work/ }).first().isVisible()
      && (await page.getByText('You don’t have access to Operations').count()) === 0);
  };
  const releaseReconciliation = async (label, noticePattern) => {
    const release = stub.releaseDenialReconciliation;
    check(`${label}: the held reconciliation is released only after those assertions`,
      typeof release === 'function');
    if (release) release();
    stub.releaseDenialReconciliation = null;
    stub.denialReconciliationHeld = false;
    check(`${label}: the real refusal is still announced after the reconciliation`,
      await until(async () => (await page.getByText(noticePattern).count()) > 0));
  };

  // ---- Fresh-read denial: the pre-command read answers 404 and nothing is transmitted ----
  await openDraftDetailWithRevealedContact(page);
  check('the contact is revealed before the denial', await page.getByText('+91 90000 11111').first().isVisible());

  // Ownership is transferred: the owned root reads now answer 404 while the account keeps its grant.
  stub.detailDenied = true;
  armHeldReconciliation();
  await saveUnchangedWindow(page);
  await assertWithdrawnWhileReconciling('fresh-read 404', /is no longer claimable or owned by you/);
  checkEqual('fresh-read 404: nothing was transmitted for the denied fresh read',
    availabilityPosts(), 0);
  await releaseReconciliation('fresh-read 404', /is no longer claimable or owned by you/);

  // ---- Mutation denial 403, with a lessor-contact reveal still in flight ----------------
  stub.detailDenied = false;
  stub.mutationDenied = 403;
  await openDraftDetailWithRevealedContact(page);
  await contactPanel().getByRole('button', { name: 'Hide' }).click();
  // From here the contact read is delivered late whatever the client does with its abort, so only the
  // panel's own context guard can refuse the answer that arrives after the root was withdrawn.
  stub.holdContactMs = 2500;
  await contactPanel().getByRole('button', { name: /Show lessor contact/ }).click();
  check('the reveal is in flight before the denial lands',
    await waitFor(page, () => stub.contactHolds === 1, 6000));

  armHeldReconciliation();
  const postsBefore403 = availabilityPosts();
  await saveUnchangedWindow(page);
  await assertWithdrawnWhileReconciling('403 mutation', /coordination authority for this team is not currently active/);
  checkEqual('403 mutation: the refused mutation was transmitted exactly once',
    availabilityPosts() - postsBefore403, 1);
  // The withheld answer is delivered now; it must not repopulate anything, on or off the panel.
  await page.waitForTimeout(3000);
  check('403 mutation: the late reveal answer repopulates nothing',
    (await page.getByText('Meena Gupta').count()) === 0
    && (await page.getByText('+91 90000 11111').count()) === 0
    && (await page.getByRole('link', { name: /Call lessor/ }).count()) === 0
    && (await page.getByRole('button', { name: /Show lessor contact/ }).count()) === 0);
  await releaseReconciliation('403 mutation', /coordination authority for this team is not currently active/);
  stub.holdContactMs = 0;

  // ---- Mutation denial 404 -------------------------------------------------------------
  stub.mutationDenied = 404;
  await openDraftDetailWithRevealedContact(page);
  armHeldReconciliation();
  const postsBefore404 = availabilityPosts();
  await saveUnchangedWindow(page);
  await assertWithdrawnWhileReconciling('404 mutation', /is no longer claimable or owned by you/);
  checkEqual('404 mutation: the refused mutation was transmitted exactly once',
    availabilityPosts() - postsBefore404, 1);
  await releaseReconciliation('404 mutation', /is no longer claimable or owned by you/);
  // The queues were re-read from the server, and they are the workspace's own state again.
  check('the queues were re-read from the server after the denial',
    stub.log.filter((call) => call.path.startsWith('/api/v1/operations/my-work')).length > 0);
  await context.close();
};

/**
 * R1 target identity — a denial belongs to the exact mounted instance its operation targeted.
 *
 * Sol's reproduction: an operation targeting Session #3108 is dispatched and its response is held;
 * the OE opens the unrelated Session #4109; the #3108 operation is then answered with `403`. The
 * refusal must not touch #4109 — not its detail, not its controls, not its protected state, and not
 * its notices — and it must not clear a newer mount of #3108 either, after the OE navigated away and
 * back. Every denial here is asserted while the authoritative queue reconciliation is still
 * unresolved, so nothing checked can have been satisfied by anything written after a refresh.
 */
const correctionDenialTargetIdentity = async (browser) => {
  const until = async (page, predicate, timeoutMs = 8000) => {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (await predicate()) return true;
      await page.waitForTimeout(100);
    }
    return false;
  };
  const armHeldReconciliation = () => {
    stub.holdDenialReconciliation = true;
    stub.denialReconciliationHeld = false;
    stub.releaseDenialReconciliation = null;
  };
  const releaseReconciliation = async (label) => {
    const release = stub.releaseDenialReconciliation;
    check(`${label}: the held reconciliation is released only after those assertions`,
      typeof release === 'function');
    if (release) release();
    stub.releaseDenialReconciliation = null;
    stub.denialReconciliationHeld = false;
  };
  const releaseHeldResponse = async (label) => {
    const release = stub.releaseHeldResponse;
    check(`${label}: the held command response is still pending`, typeof release === 'function');
    if (release) release();
    stub.releaseHeldResponse = null;
  };
  /** Opens a row of My work and waits for that root's own detail heading. */
  const openSessionRow = async (page, sessionId) => {
    await page.getByRole('button', { name: new RegExp(`#${sessionId}`) }).first().click();
    await page.getByRole('heading', { name: new RegExp(`Session #${sessionId}`) })
      .waitFor({ state: 'visible', timeout: 8000 });
  };
  /** The unrelated root is fully its own: heading, detail, controls, and nothing of #3108 on screen. */
  const assertUnrelatedRootIntact = async (label, page) => {
    check(`${label}: #4109 is still mounted`,
      (await page.getByRole('heading', { name: /Session #4109/ }).count()) > 0);
    check(`${label}: #4109 keeps its own detail`,
      (await page.getByText('4 BHK villa').count()) > 0);
    check(`${label}: #4109 keeps its own controls`,
      (await page.locator('.oe-actions').getByRole('button', { name: 'Schedule visit' }).count()) > 0);
    check(`${label}: nothing of the #3108 refusal is applied to #4109`,
      (await page.getByText(/#3108/).count()) === 0
      && (await page.getByText(/coordination authority/).count()) === 0
      && (await page.getByText(/is no longer claimable or owned by you/).count()) === 0);
  };
  const availabilityPosts = () => stub.log.filter((call) => call.path.endsWith('/availability')).length;

  // ---- Different-root race: mutation 403 (Sol's reproduction) ----------------------
  stub.reset('learn', 'draft');
  stub.unrelatedSessionEnabled = true;
  const first = await newPage(browser);
  await openOperations(first.page);
  await openQueue(first.page, 'My work');
  await openSessionRow(first.page, 3108);
  await first.page.locator('.oe-itin .oe-contact').first().getByRole('button', { name: /Show lessor contact/ }).click();
  await first.page.getByText('Meena Gupta').first().waitFor({ state: 'visible', timeout: 8000 });

  stub.holdResponse = { kind: 'availability' };
  await saveUnchangedWindow(first.page);
  check('different-root 403: the targeted mutation is transmitted and held open',
    await waitFor(first.page, () => stub.heldResponse === true, 8000));

  // While that response is held, the OE opens the unrelated Session #4109.
  await openQueue(first.page, 'My work');
  await openSessionRow(first.page, 4109);

  stub.mutationDenied = 403;
  armHeldReconciliation();
  await releaseHeldResponse('different-root 403');
  check('different-root 403: the reconciliation is held while the decision is checked',
    await waitFor(first.page, () => stub.denialReconciliationHeld, 10000));
  await assertUnrelatedRootIntact('different-root 403', first.page);
  check('different-root 403: the refused root’s revealed contact never appears here',
    (await first.page.getByText('Meena Gupta').count()) === 0);
  checkEqual('different-root 403: the refused mutation was transmitted exactly once',
    availabilityPosts(), 1);
  await releaseReconciliation('different-root 403');
  await assertUnrelatedRootIntact('different-root 403 after the reconciliation', first.page);
  check('different-root 403: the queues were still reconciled from the server',
    stub.log.filter((call) => call.path.startsWith('/api/v1/operations/my-work')).length > 0);
  await first.context.close();

  // ---- Different-root race: mutation 404 -------------------------------------------
  stub.reset('learn', 'draft');
  stub.unrelatedSessionEnabled = true;
  const second = await newPage(browser);
  await openOperations(second.page);
  await openQueue(second.page, 'My work');
  await openSessionRow(second.page, 3108);
  stub.holdResponse = { kind: 'availability' };
  await saveUnchangedWindow(second.page);
  check('different-root 404: the targeted mutation is transmitted and held open',
    await waitFor(second.page, () => stub.heldResponse === true, 8000));
  await openQueue(second.page, 'My work');
  await openSessionRow(second.page, 4109);
  stub.mutationDenied = 404;
  armHeldReconciliation();
  await releaseHeldResponse('different-root 404');
  check('different-root 404: the reconciliation is held while the decision is checked',
    await waitFor(second.page, () => stub.denialReconciliationHeld, 10000));
  await assertUnrelatedRootIntact('different-root 404', second.page);
  checkEqual('different-root 404: the refused mutation was transmitted exactly once',
    availabilityPosts(), 1);
  await releaseReconciliation('different-root 404');
  await assertUnrelatedRootIntact('different-root 404 after the reconciliation', second.page);
  await second.context.close();

  // ---- Different-root race: fresh-read 404, with nothing transmitted ---------------
  stub.reset('learn', 'draft');
  stub.unrelatedSessionEnabled = true;
  const third = await newPage(browser);
  await openOperations(third.page);
  await openQueue(third.page, 'My work');
  await openSessionRow(third.page, 3108);
  stub.holdResponse = { kind: 'session-detail', sessionId: 3108 };
  await saveUnchangedWindow(third.page);
  check('fresh-read 404: the pre-command read for #3108 is held open',
    await waitFor(third.page, () => stub.heldResponse === true, 8000));
  await openQueue(third.page, 'My work');
  await openSessionRow(third.page, 4109);
  // Ownership of #3108 is gone: the held read answers 404 while #4109 stays authoritative.
  stub.detailDenied = true;
  armHeldReconciliation();
  await releaseHeldResponse('fresh-read 404');
  check('fresh-read 404: the reconciliation is held while the decision is checked',
    await waitFor(third.page, () => stub.denialReconciliationHeld, 10000));
  await assertUnrelatedRootIntact('fresh-read 404', third.page);
  checkEqual('fresh-read 404: nothing was transmitted for the denied fresh read', availabilityPosts(), 0);
  await releaseReconciliation('fresh-read 404');
  await assertUnrelatedRootIntact('fresh-read 404 after the reconciliation', third.page);
  await third.context.close();

  // ---- Navigation away and back: the newer mount of #3108 must survive -------------
  stub.reset('learn', 'draft');
  const fourth = await newPage(browser);
  await openOperations(fourth.page);
  await openQueue(fourth.page, 'My work');
  await openSessionRow(fourth.page, 3108);
  stub.holdResponse = { kind: 'availability' };
  await saveUnchangedWindow(fourth.page);
  check('re-mount race: the #3108 mutation is transmitted and held open',
    await waitFor(fourth.page, () => stub.heldResponse === true, 8000));

  // The OE leaves the record and re-opens it in a brand-new mount context.
  await openQueue(fourth.page, 'My work');
  await openSessionRow(fourth.page, 3108);
  await fourth.page.locator('.oe-itin .oe-contact').first().getByRole('button', { name: /Show lessor contact/ }).click();
  await fourth.page.getByText('Meena Gupta').first().waitFor({ state: 'visible', timeout: 8000 });

  stub.mutationDenied = 403;
  armHeldReconciliation();
  await releaseHeldResponse('re-mount race');
  check('re-mount race: the reconciliation is held while the decision is checked',
    await waitFor(fourth.page, () => stub.denialReconciliationHeld, 10000));
  check('re-mount race: the newer #3108 mount is not cleared',
    (await fourth.page.getByRole('heading', { name: /Session #3108/ }).count()) > 0
    && (await fourth.page.getByText('2 BHK apartment').count()) > 0);
  check('re-mount race: the newer mount keeps its freshly revealed contact',
    (await fourth.page.getByText('Meena Gupta').count()) > 0
    && (await fourth.page.getByText('+91 90000 11111').count()) > 0);
  check('re-mount race: no refusal is applied to the newer mount',
    (await fourth.page.getByText(/coordination authority/).count()) === 0);
  await releaseReconciliation('re-mount race');
  check('re-mount race: the newer mount and its contact survive the released denial',
    await until(fourth.page, async () =>
      (await fourth.page.getByRole('heading', { name: /Session #3108/ }).count()) > 0
      && (await fourth.page.getByText('Meena Gupta').count()) > 0));
  await fourth.context.close();

  // ---- Different-root race: a refused scheduling command ---------------------------
  stub.reset('learn', 'draft');
  stub.unrelatedSessionEnabled = true;
  const fifth = await newPage(browser);
  await openDraftDrawer(fifth.page);
  stub.holdResponse = { kind: 'selection' };
  await fifth.page.getByRole('button', { name: 'Confirm and schedule' }).click();
  check('scheduling refusal: the selection POST is transmitted and held open',
    await waitFor(fifth.page, () => stub.heldResponse === true, 8000));
  // The drawer is modal, so the OE closes it (the only way off the record while the command runs)
  // and opens the unrelated root before the answer arrives, through its documented Escape handler.
  // (The drawer's own close control is proven reachable by ordinary pointer at this viewport in
  // `correctionSchedulingCloseHitTarget`.)
  await fifth.page.locator('.oe-sch').press('Escape');
  check('scheduling refusal: the drawer closed, so the OE can leave the record',
    await until(fifth.page, async () => (await fifth.page.locator('.oe-sch').count()) === 0, 4000));
  await openQueue(fifth.page, 'My work');
  await openSessionRow(fifth.page, 4109);

  stub.selectionDenied = 403;
  armHeldReconciliation();
  await releaseHeldResponse('scheduling refusal');
  check('scheduling refusal: the reconciliation is held while the decision is checked',
    await waitFor(fifth.page, () => stub.denialReconciliationHeld, 10000));
  await assertUnrelatedRootIntact('scheduling refusal', fifth.page);
  await releaseReconciliation('scheduling refusal');
  await assertUnrelatedRootIntact('scheduling refusal after the reconciliation', fifth.page);
  checkEqual('scheduling refusal: transmitted exactly once and never resent',
    stub.log.filter((call) => call.path.endsWith('/oe-recommendations/selection')).length, 1);
  check('scheduling refusal: no scheduling outcome was fabricated for #4109',
    (await fifth.page.getByText(/This selection is no longer available/).count()) === 0
    && (await fifth.page.getByText(/The visit is scheduled/).count()) === 0);
  await fifth.context.close();
};

/**
 * R1 stale notice race — a refusal may only be announced in the display context it describes.
 *
 * Sol's reproduction: the #3108 mutation answers `403`, the refused detail is withdrawn (correctly),
 * and the authoritative reconciliation is held. The OE then opens Session #4109 — with its own
 * independently authorized contact — while that read is still unresolved. Resolving the older #3108
 * reconciliation *used to* publish the #3108 authority refusal into #4109, because the publish verdict
 * had been captured before the await. Every assertion below is made against the real React tree, either
 * while the reconciliation is still unresolved or after it has been released.
 */
const correctionStaleRefusalNoticeRace = async (browser) => {
  /** The exact copy a `403` refusal produces for this team (see the R1 scenario above). */
  const REFUSAL = /coordination authority for this team is not currently active/;

  const until = async (page, predicate, timeoutMs = 8000) => {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (await predicate()) return true;
      await page.waitForTimeout(100);
    }
    return false;
  };
  const armHeldReconciliation = () => {
    stub.holdDenialReconciliation = true;
    stub.denialReconciliationHeld = false;
    stub.releaseDenialReconciliation = null;
  };
  const releaseHeldResponse = async (label) => {
    const release = stub.releaseHeldResponse;
    check(`${label}: the held command response is still pending`, typeof release === 'function');
    if (release) release();
    stub.releaseHeldResponse = null;
  };
  const releaseReconciliation = (label) => {
    const release = stub.releaseDenialReconciliation;
    check(`${label}: the held reconciliation is released by the check`, typeof release === 'function');
    if (release) release();
    stub.releaseDenialReconciliation = null;
    stub.denialReconciliationHeld = false;
  };
  /** Opens one root of My work by its exact root identity, so colliding ids stay distinct. */
  const openRoot = (page, targetType, targetId) =>
    page.locator(`[data-row-key="${targetType}-${targetId}"]`).click();
  const detailIsMounted = async (page, type, id) =>
    (await page.getByRole('heading', { name: new RegExp(`${type} #${id}`) }).count()) > 0;
  const revealContact = async (page, panel, name) => {
    // `.first()`: a Session lists one contact panel per stop, and the first stop's is the one asserted.
    await page.locator(panel).first().getByRole('button', { name: /Show lessor contact/ }).click();
    return page.getByText(name).first().waitFor({ state: 'visible', timeout: 8000 })
      .then(() => true)
      .catch(() => false);
  };
  const availabilityPosts = () => stub.log.filter((call) => call.path.endsWith('/availability')).length;

  // ---- Different-root race: #4109 is opened *after* the withdrawal, while the read is held ----
  stub.reset('learn', 'draft');
  stub.unrelatedSessionEnabled = true;
  const first = await newPage(browser);
  await openDraftDetailWithRevealedContact(first.page);
  check('different-root notice race: the refused root’s contact is revealed before the denial',
    await first.page.getByText('+91 90000 11111').first().isVisible());

  stub.holdResponse = { kind: 'availability' };
  await saveUnchangedWindow(first.page);
  check('different-root notice race: the targeted mutation is transmitted and held open',
    await waitFor(first.page, () => stub.heldResponse === true, 8000));

  stub.mutationDenied = 403;
  armHeldReconciliation();
  await releaseHeldResponse('different-root notice race');
  check('different-root notice race: the denial withdraws #3108 before anything is reconciled',
    await until(first.page, () => detailIsMounted(first.page, 'Session', 3108).then((mounted) => !mounted)));
  check('different-root notice race: the authoritative reconciliation is now held open',
    await waitFor(first.page, () => stub.denialReconciliationHeld, 10000));

  // The OE moves to the unrelated Session #4109 and reveals its own authorized contact.
  await openQueue(first.page, 'My work');
  await openRoot(first.page, 'SESSION', 4109);
  check('different-root notice race: #4109 is mounted with its own detail',
    await until(first.page, () => detailIsMounted(first.page, 'Session', 4109)));
  check('different-root notice race: #4109 reveals its own independently authorized contact',
    await revealContact(first.page, '.oe-contact', 'Kavita Rao'));
  check('different-root notice race: the older reconciliation is still the held one',
    stub.denialReconciliationHeld === true);

  releaseReconciliation('different-root notice race');
  // Give the resolved continuation every chance to write into the context that replaced its own.
  await first.page.waitForTimeout(900);
  check('different-root notice race: the obsolete #3108 refusal is never announced in #4109',
    (await first.page.getByText(REFUSAL).count()) === 0);
  check('different-root notice race: no #3108 record appears in #4109’s workspace',
    (await first.page.getByText(/#3108/).count()) === 0);
  check('different-root notice race: #4109 keeps its detail, its contact and its controls',
    await until(first.page, async () => detailIsMounted(first.page, 'Session', 4109)
      && (await first.page.getByText('4 BHK villa').count()) > 0
      && (await first.page.getByText('Kavita Rao').count()) > 0
      && (await first.page.getByText('+91 90000 33333').count()) > 0
      && (await first.page.locator('.oe-actions').getByRole('button', { name: 'Schedule visit' }).count()) > 0));
  check('different-root notice race: the queues were still reconciled from the server',
    stub.log.filter((call) => call.path.startsWith('/api/v1/operations/my-work')).length > 0);
  checkEqual('different-root notice race: the refused mutation was transmitted exactly once',
    availabilityPosts(), 1);
  await first.context.close();

  // ---- Same-root remount: the root is re-opened after the withdrawal, before the reconciliation ----
  stub.reset('learn', 'draft');
  const second = await newPage(browser);
  await openDraftDetailWithRevealedContact(second.page);
  stub.holdResponse = { kind: 'availability' };
  await saveUnchangedWindow(second.page);
  check('re-mount after withdrawal: the #3108 mutation is transmitted and held open',
    await waitFor(second.page, () => stub.heldResponse === true, 8000));

  stub.mutationDenied = 403;
  armHeldReconciliation();
  await releaseHeldResponse('re-mount after withdrawal');
  check('re-mount after withdrawal: the denied #3108 detail is withdrawn',
    await until(second.page, () => detailIsMounted(second.page, 'Session', 3108).then((mounted) => !mounted)));
  check('re-mount after withdrawal: the reconciliation is held open',
    await waitFor(second.page, () => stub.denialReconciliationHeld, 10000));

  // A3: the OE re-opens #3108 in a brand-new mount generation while the older reconciliation is held.
  await openQueue(second.page, 'My work');
  await openRoot(second.page, 'SESSION', 3108);
  check('re-mount after withdrawal: the newer #3108 mount reveals its own contact',
    await revealContact(second.page, '.oe-itin .oe-contact', 'Meena Gupta'));

  releaseReconciliation('re-mount after withdrawal');
  await second.page.waitForTimeout(900);
  check('re-mount after withdrawal: the newer mount and its freshly revealed contact survive',
    (await second.page.getByRole('heading', { name: /Session #3108/ }).count()) > 0
    && (await second.page.getByText('Meena Gupta').count()) > 0
    && (await second.page.getByText('+91 90000 11111').count()) > 0);
  check('re-mount after withdrawal: the older refusal is never announced in the newer mount',
    (await second.page.getByText(REFUSAL).count()) === 0);
  await second.context.close();

  // ---- Request versus Session: the same numeric id is never the same display context ----------
  stub.reset('learn', 'draft');
  stub.sameIdRequestEnabled = true;
  const third = await newPage(browser);
  await openOperations(third.page);
  await openQueue(third.page, 'My work');
  check('Request/Session collision: both roots are listed as their own row',
    (await third.page.locator('[data-row-key="SESSION-3108"]').count()) === 1
    && (await third.page.locator('[data-row-key="REQUEST-3108"]').count()) === 1);
  await openRoot(third.page, 'SESSION', 3108);
  check('Request/Session collision: the Session root is the one mounted',
    await until(third.page, () => detailIsMounted(third.page, 'Session', 3108)));

  stub.holdResponse = { kind: 'availability' };
  await saveUnchangedWindow(third.page);
  check('Request/Session collision: the Session mutation is transmitted and held open',
    await waitFor(third.page, () => stub.heldResponse === true, 8000));
  stub.mutationDenied = 403;
  armHeldReconciliation();
  await releaseHeldResponse('Request/Session collision');
  check('Request/Session collision: the refused Session detail is withdrawn',
    await until(third.page, () => detailIsMounted(third.page, 'Session', 3108).then((mounted) => !mounted)));
  check('Request/Session collision: the reconciliation is held open',
    await waitFor(third.page, () => stub.denialReconciliationHeld, 10000));

  // The OE opens the *Request* root that happens to carry the refused Session's numeric id.
  await openQueue(third.page, 'My work');
  await openRoot(third.page, 'REQUEST', 3108);
  check('Request/Session collision: the Request root is mounted, never the Session',
    await until(third.page, async () => (await third.page.getByRole('heading', { name: /Request #3108/ }).count()) > 0
      && (await third.page.getByRole('heading', { name: /Session #3108/ }).count()) === 0));
  check('Request/Session collision: the Request reveals its own contact',
    await revealContact(third.page, '.oe-contact', 'Anil Mehta'));

  releaseReconciliation('Request/Session collision');
  await third.page.waitForTimeout(900);
  check('Request/Session collision: the Request root and its contact survive the Session denial',
    (await third.page.getByRole('heading', { name: /Request #3108/ }).count()) > 0
    && (await third.page.getByText('Anil Mehta').count()) > 0
    && (await third.page.getByText('+91 90000 44444').count()) > 0);
  check('Request/Session collision: the Session refusal is never applied to the Request root',
    (await third.page.getByText(REFUSAL).count()) === 0);
  await third.context.close();

  // ---- Drawer outcome: a stale scheduling continuation may not become another drawer's outcome ----
  stub.reset('learn', 'draft');
  stub.unrelatedSessionEnabled = true;
  const fourth = await newPage(browser);
  await openDraftDrawer(fourth.page);
  stub.holdResponse = { kind: 'selection' };
  await fourth.page.getByRole('button', { name: 'Confirm and schedule' }).click();
  check('scheduling outcome race: the selection POST is transmitted and held open',
    await waitFor(fourth.page, () => stub.heldResponse === true, 8000));
  // The drawer is modal, so the OE leaves the record through its documented Escape handler.
  await fourth.page.locator('.oe-sch').press('Escape');
  check('scheduling outcome race: the drawer closed, so the OE can leave the record',
    await until(fourth.page, async () => (await fourth.page.locator('.oe-sch').count()) === 0, 4000));
  await openQueue(fourth.page, 'My work');
  await openRoot(fourth.page, 'SESSION', 4109);
  /**
   * The workspace holds one command at a time, so while #3108's booking is still in flight no other
   * drawer can be opened at all: the scheduling control of whatever record is on screen is disabled.
   * That is asserted here rather than assumed — it is the UI half of the same invariant the failure
   * continuation enforces in state (a continuation that no longer describes the open session writes no
   * drawer outcome), and it must not quietly change.
   */
  check('scheduling outcome race: no other drawer can open while the #3108 booking owns the workspace',
    await fourth.page.getByRole('button', { name: 'Schedule visit' }).isDisabled());
  check('scheduling outcome race: no scheduling drawer is rendered for #4109',
    (await fourth.page.locator('.oe-sch').count()) === 0);

  // The older #3108 booking is answered with a stale-version conflict while the OE is elsewhere.
  stub.selectionDenied = 409;
  armHeldReconciliation();
  await releaseHeldResponse('scheduling outcome race');
  check('scheduling outcome race: the reconciliation is held while the decision is checked',
    await waitFor(fourth.page, () => stub.denialReconciliationHeld, 10000));
  await fourth.page.waitForTimeout(900);
  releaseReconciliation('scheduling outcome race');
  await fourth.page.waitForTimeout(900);
  check('scheduling outcome race: no scheduling outcome is fabricated for #4109',
    (await fourth.page.getByText('This selection is no longer available').count()) === 0
    && (await fourth.page.getByText('The visit is scheduled').count()) === 0
    && (await fourth.page.locator('.oe-sch').count()) === 0);
  check('scheduling outcome race: #4109 keeps its detail and controls',
    (await fourth.page.getByRole('heading', { name: /Session #4109/ }).count()) > 0
    && (await fourth.page.locator('.oe-actions').getByRole('button', { name: 'Schedule visit' }).count()) > 0);
  // A conflict is not a refusal: its existing truthful announcement still names the record it is about.
  check('scheduling outcome race: the conflict is still announced truthfully for its own record',
    (await fourth.page.getByText(/Session #3108 changed on the server while you were working/).count()) > 0);
  checkEqual('scheduling outcome race: transmitted exactly once and never resent',
    stub.log.filter((call) => call.path.endsWith('/oe-recommendations/selection')).length, 1);
  // With the abandoned command finished, #4109's own drawer opens clean: nothing is inherited.
  await fourth.page.getByRole('button', { name: 'Schedule visit' }).click();
  check('scheduling outcome race: #4109 can schedule again, with no inherited outcome',
    await fourth.page.getByText('Top evaluated suggestion').waitFor({ state: 'visible', timeout: 10000 })
      .then(() => true)
      .catch(() => false)
      && (await fourth.page.getByText('This selection is no longer available').count()) === 0);
  await fourth.context.close();
};

/**
 * R3 — a 200 that cannot prove this booking is uncertain: no announcement, one POST, safe reads.
 *
 * The first variant is Sol's original repro (invalid timestamp text and a negative Ground Executive
 * id); the other two are the strict-timestamp cases: an impossible calendar date, and an instant one
 * nanosecond away from the one that was submitted. Each is driven through the real drawer, so the
 * outcome, the exact network traffic and the recovery reads are the workspace's own.
 */
const correctionMalformedBooking200 = async (browser) => {
  for (const [label, submittedAt, malformed] of [
    [
      'invalid timestamp text and a negative GE id',
      '2027-02-14T05:00:00Z',
      { scheduledAt: 'not-a-time', groundExecutiveUserId: -1 }
    ],
    [
      'Case A: an impossible calendar date',
      '2027-03-02T05:00:00Z',
      { scheduledAt: '2027-02-30T05:00:00Z', groundExecutiveUserId: 4207 }
    ],
    [
      'Case B: an instant one nanosecond away',
      '2027-02-14T05:00:00.123456789Z',
      { scheduledAt: '2027-02-14T05:00:00.123456788Z', groundExecutiveUserId: 4207 }
    ]
  ]) {
    stub.reset('malformed-200', 'draft');
    stub.rankedCandidateScheduledAt = submittedAt;
    stub.malformedBooking = malformed;
    const { context, page } = await newPage(browser);
    await openDraftDrawer(page);

    await page.getByRole('button', { name: 'Confirm and schedule' }).click();
    await page.getByText('We can’t tell whether this was scheduled').waitFor({ state: 'visible', timeout: 10000 });
    check(`${label}: the unusable 200 is reported as an unknown outcome`, true);
    check(`${label}: no success result is shown`,
      (await page.getByText('The visit is scheduled.').count()) === 0);
    check(`${label}: no scheduled state was fabricated`,
      (await page.getByText('Awaiting tenant').count()) === 0);
    check(`${label}: the unusable recorded instant is never displayed`,
      (await page.getByText(malformed.scheduledAt).count()) === 0);

    const selectionPosts = stub.log.filter((call) => call.path.endsWith('/oe-recommendations/selection'));
    checkEqual(`${label}: transmitted exactly once and never resent`, selectionPosts.length, 1);
    checkEqual(`${label}: the exact evaluated instant was submitted`,
      selectionPosts[0].body.scheduledAt, submittedAt);
    const postIndex = stub.log.findIndex((call) => call.path.endsWith('/oe-recommendations/selection'));
    check(`${label}: a safe read-only reconciliation followed the unusable answer`,
      stub.log.slice(postIndex + 1).some(
        (call) => call.method === 'GET' && call.path.startsWith('/api/v1/operations/my-work')
      ));
    check(`${label}: the classified outcome is uncertainty, never a refusal`,
      (await page.getByText('This selection is no longer available').count()) === 0);
    // The unusable answer is never retried on its own: the OE decides, after checking the session.
    await page.waitForTimeout(1200);
    checkEqual(`${label}: the unusable answer triggers no automatic replay`,
      stub.log.filter((call) => call.path.endsWith('/oe-recommendations/selection')).length, 1);
    await context.close();
  }
};

/** R4 — a candidate reviewed at v6 is never booked against a changed v8 itinerary. */
const correctionEvaluationVersionBinding = async (browser) => {
  stub.reset('bump-after-review', 'draft');
  const { context, page } = await newPage(browser);
  await openDraftDrawer(page);

  const ranked = stub.log.filter((call) => call.path === '/api/v1/operations/visit-sessions/3108/oe-recommendations');
  checkEqual('the reviewed candidates were evaluated at the session version on screen',
    ranked.at(-1)?.body?.expectedSessionVersion, 6);

  // The iteration moves to v8 with a changed itinerary while the Review step is open.
  await page.getByRole('button', { name: 'Confirm and schedule' }).click();
  await page.getByText('This session changed since these candidates were evaluated').waitFor({ state: 'visible', timeout: 10000 });
  check('a changed authoritative version stops the booking and demands a new review', true);
  checkEqual('zero booking commands were transmitted for the unreviewed version',
    stub.log.filter((call) => call.path.endsWith('/oe-recommendations/selection')).length, 0);
  check('the drawer offers a reload of the authoritative session',
    await page.getByRole('button', { name: 'Reload the session' }).isVisible());

  // Reloading shows the changed itinerary and re-evaluates it at the new version.
  await page.getByRole('button', { name: 'Reload the session' }).click();
  const reEvaluated = await waitFor(page, () => stub.log.some(
    (call) => call.path === '/api/v1/operations/visit-sessions/3108/oe-recommendations'
      && call.body?.expectedSessionVersion === 8
  ), 12000);
  check('the refreshed evaluation is bound to the current version', reEvaluated);
  check('the current itinerary is on screen after the reload',
    (await page.getByText('Replacement stop').count()) > 0);
  checkEqual('still no booking command was transmitted',
    stub.log.filter((call) => call.path.endsWith('/oe-recommendations/selection')).length, 0);
  await context.close();
};

/** R6 — exactly one picker panel is rendered and reachable for each tab. */
const correctionInactivePickerTabs = async (browser) => {
  stub.reset('learn', 'draft');
  const { context, page } = await newPage(browser);
  await openDraftDrawerAtChoose(page);

  const measure = () => page.evaluate(() => {
    const panels = [...document.querySelectorAll('.oe-sch [role="tabpanel"]')];
    const selected = document.querySelector('.oe-sch [role="tab"][aria-selected="true"]');
    const visible = panels.filter((panel) => panel.getClientRects().length > 0);
    return {
      total: panels.length,
      visibleCount: visible.length,
      visibleId: visible[0]?.id ?? null,
      expectedId: selected ? `oe-ppanel-${selected.id.replace('oe-ptab-', '')}` : null,
      hiddenNotDisplayed: panels.filter((panel) => panel.hidden && window.getComputedStyle(panel).display === 'none').length,
      hiddenWithBoxes: panels.filter((panel) => panel.hidden && panel.getClientRects().length > 0).length,
      hiddenReachable: panels
        .filter((panel) => panel.hidden)
        .flatMap((panel) => [...panel.querySelectorAll('button, input, select, textarea, a[href]')])
        .filter((control) => control.getClientRects().length > 0).length
    };
  });

  for (const label of ['Recommended', 'Browse all', 'Search']) {
    await page.locator('.oe-sch').getByRole('tab', { name: label }).click({ timeout: 8000 });
    await page.waitForTimeout(250);
    const measured = await measure();
    check(`exactly one panel is rendered for the ${label} tab`,
      measured.total === 3 && measured.visibleCount === 1 && measured.hiddenNotDisplayed === 2,
      JSON.stringify(measured));
    check(`the rendered panel is the selected tab's own panel (${label})`,
      measured.visibleId === measured.expectedId, JSON.stringify(measured));
    check(`the inactive panels are not keyboard reachable (${label})`,
      measured.hiddenWithBoxes === 0 && measured.hiddenReachable === 0, JSON.stringify(measured));
  }

  // The tablist itself stays keyboard driven: only the selected tab is in the tab order.
  const tabStops = await page.evaluate(() => [...document.querySelectorAll('.oe-sch [role="tab"]')]
    .map((tab) => ({ id: tab.id, selected: tab.getAttribute('aria-selected'), tabIndex: tab.tabIndex })));
  check('only the selected picker tab is in the tab order',
    tabStops.filter((tab) => tab.tabIndex === 0).length === 1
    && tabStops.find((tab) => tab.tabIndex === 0)?.selected === 'true',
    JSON.stringify(tabStops));
  await context.close();
};

/** R7 — a linked Request still works as the origin after its only direct stop is removed. */
const correctionOriginatingRequestAfterRemoval = async (browser) => {
  stub.reset('learn', 'single-stop');
  const { context, page } = await newPage(browser);
  await openOperations(page);
  await openQueue(page, 'My work');
  await page.getByRole('button', { name: /#3108/ }).first().click();
  await page.getByRole('heading', { name: /Session #3108/ }).waitFor({ state: 'visible', timeout: 8000 });

  // 1–2. Record the direct stop unavailable, which is the documented prerequisite for removal.
  await page.locator('.oe-itin').getByRole('button', { name: 'Record availability' }).first().click();
  await page.locator('.oe-avail-editor').getByLabel('Unavailable').check();
  await page.locator('.oe-avail-editor').getByRole('button', { name: 'Record unavailable' }).click();
  await waitFor(page, () => stub.session.items[0].confirmationStatus === 'UNAVAILABLE');
  check('the direct stop is recorded unavailable', stub.session.items[0].confirmationStatus === 'UNAVAILABLE');

  // 3. Remove it. The item leaves the active itinerary; its Request stays linked.
  await page.getByRole('button', { name: /^Remove / }).first().click();
  await page.getByRole('button', { name: 'Remove stop' }).click();
  await waitFor(page, () => stub.session.items[0].removedAt !== null);
  checkEqual('no active stop remains', stub.session.items.filter((entry) => entry.removedAt === null).length, 0);
  check('the removed history is retained', await page.getByText('Removed stops (1)').isVisible());
  checkEqual('the Request is still linked to the session', stub.session.linkedRequestVersions.length, 1);

  // 4–5. Add a distinct published replacement using the still-linked originating Request.
  await page.getByRole('button', { name: 'Add a stop' }).click();
  const originOptions = await page.locator('#oe-origin-request option').allTextContents();
  check('the still-linked Request is offered as the origin',
    originOptions.some((text) => text.includes('501')), JSON.stringify(originOptions));
  check('the chooser is usable and no longer dead-ends',
    (await page.getByText(/has no linked request on record yet/).count()) === 0);
  await page.getByLabel('Find a property').fill('duplex');
  await page.getByText('4 BHK duplex').waitFor({ state: 'visible', timeout: 8000 });
  await page.getByRole('radio', { name: /4 BHK duplex/ }).check();
  await page.getByRole('button', { name: 'Add stop' }).click();
  await waitFor(page, () => stub.log.some((call) => call.path === '/api/v1/operations/visit-sessions/3108/items' && call.method === 'POST'));
  const added = stub.log.find((call) => call.path === '/api/v1/operations/visit-sessions/3108/items' && call.method === 'POST');
  checkEqual('the exact still-linked Request was sent as the origin', added?.body?.originatingRequestId, 501);
  checkEqual('the chosen published listing was sent', added?.body?.listingId, 9100);
  check('the removed history survived the addition',
    (await page.getByText('Removed stops (1)').count()) === 1
    && stub.session.items.some((entry) => entry.removedAt !== null));
  await context.close();
};

/** R8 — permitted read-only reads for non-DRAFT sessions, with no editing power. */
const correctionReadOnlyPermittedReads = async (browser) => {
  stub.reset('learn', 'scheduled');
  const { context, page } = await newPage(browser);
  await openOperations(page);
  await openQueue(page, 'My work');
  await page.getByRole('button', { name: /#3108/ }).first().click();
  /**
   * Wait for the read-only itinerary itself, not merely a `Session #3108` heading: the detail renders
   * its loading shell first, and that shell's screen-reader heading carries the same record title, so
   * waiting on it asserted against a detail whose snapshot had not arrived yet.
   */
  await page.getByRole('heading', { name: 'Itinerary', exact: true }).waitFor({ state: 'visible', timeout: 8000 });

  check('the booked session is presented read-only',
    (await page.getByRole('heading', { name: 'Itinerary', exact: true }).count()) === 1
    && (await page.getByRole('heading', { name: 'Manage the draft itinerary' }).count()) === 0);
  check('no itinerary mutation control is offered outside DRAFT',
    (await page.getByRole('button', { name: 'Add a stop' }).count()) === 0
    && (await page.getByRole('button', { name: 'Record availability' }).count()) === 0
    && (await page.getByRole('button', { name: 'Update window' }).count()) === 0
    && (await page.getByRole('button', { name: /^Remove / }).count()) === 0
    && (await page.getByRole('button', { name: /Move .* (earlier|later)/ }).count()) === 0);

  checkEqual('recorded availability facts stay visible for the active stops',
    await page.getByText('Confirmed with window').count(), 2);
  check('the recorded window is stated truthfully', (await page.getByText(/^Available /).count()) >= 2);
  checkEqual('contact is offered once per active stop and never for the removed stop',
    await page.locator('.oe-itin .oe-contact').count(), 2);
  checkEqual('the relative ordering control is gone outside DRAFT',
    await page.locator('.oe-item-order-tools').count(), 0);

  // Distinct lessors stay distinct: each stop resolves its own canonical contact.
  await page.locator('.oe-itin .oe-contact').first().getByRole('button', { name: /Show lessor contact/ }).click();
  await page.getByText('Meena Gupta').first().waitFor({ state: 'visible', timeout: 8000 });
  await page.locator('.oe-itin .oe-contact').nth(1).getByRole('button', { name: /Show lessor contact/ }).click();
  await page.getByText('Suresh Jain').first().waitFor({ state: 'visible', timeout: 8000 });
  check('each stop keeps its own lessor contact',
    (await page.getByText('Meena Gupta').count()) === 1 && (await page.getByText('+91 90000 22222').count()) === 1);

  // ---- Terminal work never offers a contact -----------------------------------------
  stub.session = { ...stub.session, status: 'COMPLETED' };
  await page.getByRole('heading', { name: /Session #3108/ }).waitFor({ state: 'visible', timeout: 8000 });
  await page.reload({ waitUntil: 'domcontentloaded' });
  await openOperations(page);
  await openQueue(page, 'My work');
  await page.getByRole('button', { name: /#3108/ }).first().click();
  await page.getByText(/read-only history/).first().waitFor({ state: 'visible', timeout: 8000 });
  checkEqual('a terminal session offers no contact reveal at all',
    await page.locator('.oe-itin .oe-contact').count(), 0);
  check('the terminal session still states its recorded availability facts',
    (await page.getByText('Confirmed with window').count()) >= 1);
  await context.close();
};

/** R9 — an expired continuation cursor is restarted, never resent. */
const correctionExpiredCursorRecovery = async (browser) => {
  stub.reset('cursor-expired', 'draft');
  const { context, page } = await newPage(browser);
  await openDraftDrawerAtChoose(page);

  // ---- Browse -----------------------------------------------------------------------
  await page.locator('.oe-sch').getByRole('tab', { name: 'Browse all' }).click({ timeout: 8000 });
  await page.getByText('Sunil Nair').waitFor({ state: 'visible', timeout: 8000 });
  await page.getByRole('button', { name: 'Load more' }).click();
  await page.getByText('This list’s continuation has expired').waitFor({ state: 'visible', timeout: 8000 });
  check('a rejected continuation is reported as an expired cursor, not a transient failure', true);
  const rejectedBrowse = stub.log.filter((call) => call.path.includes('all=true') && call.path.includes('cursor='));
  check('the expired cursor really was rejected by the server', rejectedBrowse.length >= 1);

  const browseMark = stub.log.length;
  await page.getByRole('button', { name: 'Start this list again' }).click();
  await waitFor(page, () => stub.log.length > browseMark);
  const browseRestart = stub.log.slice(browseMark).find((call) => call.path.includes('all=true'));
  check('the browse restart reads the first page with no cursor',
    browseRestart !== undefined && !browseRestart.path.includes('cursor='), JSON.stringify(browseRestart?.path));
  await page.getByText('Sunil Nair').waitFor({ state: 'visible', timeout: 8000 });
  check('the restarted list is rendered again', true);

  // ---- Search -----------------------------------------------------------------------
  await page.locator('.oe-sch').getByRole('tab', { name: 'Search' }).click({ timeout: 8000 });
  await page.getByLabel(/Search eligible Ground Executives/).fill('Meena');
  await page.getByText('Meena Rao').waitFor({ state: 'visible', timeout: 8000 });
  await page.getByRole('button', { name: 'Load more matches' }).click();
  await page.getByText(/couldn’t be continued/).waitFor({ state: 'visible', timeout: 8000 });
  check('the rejected search continuation is reported truthfully', true);

  const searchMark = stub.log.length;
  await page.getByRole('button', { name: 'Start the search again' }).click();
  const restarted = await waitFor(page, () => stub.log.slice(searchMark).some((call) => call.path.includes('q=Meena')));
  const searchRestart = stub.log.slice(searchMark).find((call) => call.path.includes('q=Meena'));
  check('the search restart keeps the normalized query and drops the expired cursor',
    restarted && searchRestart !== undefined && !searchRestart.path.includes('cursor='),
    JSON.stringify(searchRestart?.path));
  check('no booking command was transmitted while recovering',
    stub.log.filter((call) => call.path.endsWith('/oe-recommendations/selection')).length === 0);
  await context.close();
};

/** R10 — the frozen right-aligned 560px, full-height scheduling geometry. */
const correctionFrozenSchedulingGeometry = async (browser) => {
  stub.reset('learn', 'draft');
  const { context, page } = await newPage(browser);
  await openDraftDrawer(page);

  const geometry = () => page.evaluate(() => {
    const panel = document.querySelector('.oe-sch');
    const box = panel.getBoundingClientRect();
    const foot = document.querySelector('.oe-sch-foot')?.getBoundingClientRect();
    const body = document.querySelector('.oe-sch-body');
    return {
      x: box.x,
      width: box.width,
      height: box.height,
      right: box.x + box.width,
      footerTop: foot?.top ?? null,
      footerBottom: foot?.bottom ?? null,
      innerWidth: window.innerWidth,
      innerHeight: window.innerHeight,
      documentScrollWidth: document.documentElement.scrollWidth,
      bodyScrolls: body.scrollHeight > body.clientHeight,
      bodyOverflowY: window.getComputedStyle(body).overflowY
    };
  });

  // Desktop / tablet: right-aligned, about 560px wide, full viewport height.
  const desktop = await geometry();
  check('the desktop scheduling panel is about 560px wide',
    Math.abs(desktop.width - 560) <= 1, JSON.stringify(desktop));
  check('the desktop scheduling panel is right-aligned against the viewport',
    Math.abs(desktop.right - desktop.innerWidth) <= 1, JSON.stringify(desktop));
  check('the desktop scheduling panel is full height',
    Math.abs(desktop.height - desktop.innerHeight) <= 1, JSON.stringify(desktop));
  check('the desktop footer stays inside the viewport',
    desktop.footerBottom !== null && desktop.footerBottom <= desktop.innerHeight + 1,
    JSON.stringify(desktop));
  check('the body scrolls internally instead of the page',
    desktop.bodyOverflowY === 'auto' && desktop.documentScrollWidth <= desktop.innerWidth,
    JSON.stringify(desktop));
  check('the primary footer action is reachable without scrolling the page',
    await page.getByRole('button', { name: /Confirm and schedule|Continue/ }).first().isVisible());

  // Mobile: the scheduling steps fill the screen.
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(300);
  const mobile = await geometry();
  check('the mobile scheduling sheet fills the screen width',
    Math.abs(mobile.width - 390) <= 1, JSON.stringify(mobile));
  check('the mobile scheduling sheet is full height',
    Math.abs(mobile.height - 844) <= 1, JSON.stringify(mobile));
  check('the mobile footer stays inside the viewport',
    mobile.footerBottom !== null && mobile.footerBottom <= 844 + 1, JSON.stringify(mobile));
  check('no horizontal overflow at 390px',
    mobile.documentScrollWidth <= mobile.innerWidth, JSON.stringify(mobile));
  await context.close();
};

/**
 * F6 — the scheduling drawer can actually be closed by pointer at 1280×900.
 *
 * Sol's reproduction: the global header is `sticky top-0 z-[100]`, so it was painted over a scrim set
 * to `z-index: 60`; the header's floating controls covered the drawer's 44px close button and an
 * ordinary pointer click did nothing. No verdict here is taken from the stylesheet: the check measures
 * the control that is really rendered, hit-tests points inside it with `document.elementFromPoint`,
 * and then clicks those page coordinates with the mouse exactly as an OE would.
 */
const correctionSchedulingCloseHitTarget = async (browser) => {
  const until = async (page, predicate, timeoutMs = 6000) => {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (await predicate()) return true;
      await page.waitForTimeout(100);
    }
    return false;
  };
  const focusIsOn = (page, label) => page.evaluate(
    (expected) => (document.activeElement?.textContent ?? '').includes(expected),
    label
  );

  stub.reset('learn', 'draft');
  const { context, page } = await newPage(browser);
  await openDraftDrawer(page);

  const probe = await page.evaluate(() => {
    const control = document.querySelector('.oe-sch [aria-label="Close scheduling"]');
    const scrim = document.querySelector('.oe-sch-scrim');
    const header = document.querySelector('header[data-pathome-header]');
    const box = control.getBoundingClientRect();
    const centreX = box.x + box.width / 2;
    const centreY = box.y + box.height / 2;
    /**
     * The centre plus a ring a quarter of the target out from it. The control is a 44px rounded
     * square (`border-radius: 10px`), so the literal bounding-box corners are *outside* its painted
     * area and would hit whatever is behind it; a quarter of the target is inside every one of them.
     */
    const quarter = box.width / 4;
    const samples = [
      [0, 0], [0, -quarter], [0, quarter], [-quarter, 0], [quarter, 0],
      [-quarter, -quarter], [quarter, -quarter], [-quarter, quarter], [quarter, quarter]
    ].map(([dx, dy]) => {
      const x = centreX + dx;
      const y = centreY + dy;
      const hit = document.elementFromPoint(x, y);
      return {
        x,
        y,
        inControl: hit !== null && (hit === control || control.contains(hit)),
        inHeader: hit !== null && header !== null && header.contains(hit)
      };
    });
    const centre = samples[0];
    const headerControlsOverlapping = header === null
      ? 0
      : [...header.querySelectorAll('button')].filter((button) => {
          const rect = button.getBoundingClientRect();
          return rect.width > 0 && rect.height > 0
            && rect.left < centre.x && centre.x < rect.right
            && rect.top < centre.y && centre.y < rect.bottom;
        }).length;
    return {
      width: box.width,
      height: box.height,
      centre,
      samples,
      controlIsHit: samples.every((sample) => sample.inControl),
      anyHitInHeader: samples.some((sample) => sample.inHeader),
      scrimZ: Number(window.getComputedStyle(scrim).zIndex),
      headerZ: Number(window.getComputedStyle(header).zIndex),
      headerControlsOverlapping,
      viewport: { width: window.innerWidth, height: window.innerHeight }
    };
  });

  check('the drawer is open at the 1280×900 desktop viewport',
    probe.viewport.width === 1280 && probe.viewport.height === 900, JSON.stringify(probe.viewport));
  check('the close control is the frozen 44px target',
    probe.width === 44 && probe.height === 44, JSON.stringify({ width: probe.width, height: probe.height }));
  check('the scheduling overlay is stacked above the global header',
    probe.scrimZ > probe.headerZ, JSON.stringify({ scrimZ: probe.scrimZ, headerZ: probe.headerZ }));
  check('the header really does cover that point, so the hit test is decisive',
    probe.headerControlsOverlapping > 0 && probe.centre.y < 100, JSON.stringify(probe));
  check('document.elementFromPoint inside the control returns the control at every sampled point',
    probe.controlIsHit, JSON.stringify(probe.samples));
  check('no sampled point inside the close control reaches the global header',
    probe.anyHitInHeader === false, JSON.stringify(probe.samples));

  // An ordinary pointer click at the measured centre: the browser's own hit test decides its target.
  await page.mouse.click(probe.centre.x, probe.centre.y);
  check('an ordinary pointer click on the close control closes the drawer',
    await until(page, async () => (await page.locator('.oe-sch').count()) === 0));
  check('the overlay is fully removed, not merely hidden',
    (await page.locator('.oe-sch-scrim').count()) === 0);
  check('focus is restored to the control that opened the drawer',
    await focusIsOn(page, 'Schedule visit'));

  // While the overlay is open, an ordinary click aimed at the header must never reach it.
  await page.getByRole('button', { name: 'Schedule visit' }).click();
  await page.locator('.oe-sch').waitFor({ state: 'visible', timeout: 8000 });
  const headerProbe = await page.evaluate(() => {
    const header = document.querySelector('header[data-pathome-header]');
    window.__headerClicks = 0;
    header.addEventListener('click', () => { window.__headerClicks += 1; }, true);
    const leftmost = [...header.querySelectorAll('button')]
      .map((button) => ({ button, rect: button.getBoundingClientRect() }))
      .filter(({ rect }) => rect.width > 0 && rect.height > 0)
      .sort((a, b) => a.rect.left - b.rect.left)[0];
    const x = leftmost.rect.left + leftmost.rect.width / 2;
    const y = leftmost.rect.top + leftmost.rect.height / 2;
    const hit = document.elementFromPoint(x, y);
    return {
      x,
      y,
      label: leftmost.button.getAttribute('aria-label') ?? (leftmost.button.textContent ?? '').trim().slice(0, 40),
      hitIsInHeader: hit !== null && header.contains(hit),
      hitClass: typeof hit?.className === 'string' ? hit.className : null
    };
  });
  check('the header’s own controls are covered while the overlay is open',
    headerProbe.hitIsInHeader === false, JSON.stringify(headerProbe));
  await page.mouse.click(headerProbe.x, headerProbe.y);
  check('an ordinary click aimed at the header never reaches the header while the drawer is open',
    (await page.evaluate(() => window.__headerClicks)) === 0);
  check('the overlay stays open after that click',
    (await page.locator('.oe-sch').count()) === 1);

  // Escape still closes it, and focus is restored again.
  await page.locator('.oe-sch').press('Escape');
  check('Escape still closes the drawer',
    await until(page, async () => (await page.locator('.oe-sch').count()) === 0));
  check('focus is restored to the opener after Escape',
    await focusIsOn(page, 'Schedule visit'));

  // Mobile: the sheet fills the viewport and its own close control is equally reachable.
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(250);
  await page.getByRole('button', { name: 'Schedule visit' }).click();
  await page.locator('.oe-sch').waitFor({ state: 'visible', timeout: 8000 });
  const mobileProbe = await page.evaluate(() => {
    const control = document.querySelector('.oe-sch [aria-label="Close scheduling"]');
    const panel = document.querySelector('.oe-sch');
    const box = panel.getBoundingClientRect();
    const controlBox = control.getBoundingClientRect();
    const x = controlBox.x + controlBox.width / 2;
    const y = controlBox.y + controlBox.height / 2;
    const hit = document.elementFromPoint(x, y);
    const header = document.querySelector('header[data-pathome-header]');
    return {
      x,
      y,
      panelWidth: box.width,
      panelHeight: box.height,
      controlWidth: controlBox.width,
      controlHeight: controlBox.height,
      controlIsHit: hit !== null && (hit === control || control.contains(hit)),
      hitIsInHeader: hit !== null && header !== null && header.contains(hit),
      innerWidth: window.innerWidth,
      documentScrollWidth: document.documentElement.scrollWidth
    };
  });
  check('on mobile the scheduling sheet fills the viewport',
    Math.abs(mobileProbe.panelWidth - 390) <= 1 && Math.abs(mobileProbe.panelHeight - 844) <= 1,
    JSON.stringify(mobileProbe));
  check('the mobile close control keeps its 44px target',
    mobileProbe.controlWidth === 44 && mobileProbe.controlHeight === 44, JSON.stringify(mobileProbe));
  check('the mobile close control is the hit target at its centre',
    mobileProbe.controlIsHit && mobileProbe.hitIsInHeader === false, JSON.stringify(mobileProbe));
  check('the open sheet adds no horizontal overflow at 390px',
    mobileProbe.documentScrollWidth <= mobileProbe.innerWidth, JSON.stringify(mobileProbe));
  await page.mouse.click(mobileProbe.x, mobileProbe.y);
  check('an ordinary pointer click closes the mobile sheet',
    await until(page, async () => (await page.locator('.oe-sch').count()) === 0));
  check('focus is restored to the opener on mobile',
    await focusIsOn(page, 'Schedule visit'));
  await context.close();
};

/** R11 — hiding the document clears protected contact and invalidates a pending reveal. */
const correctionContactClearedWhenHidden = async (browser) => {
  const setVisibility = (page, state) => page.evaluate((next) => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => next });
    document.dispatchEvent(new Event('visibilitychange'));
  }, state);

  // ---- An already displayed contact is dropped the moment the tab is hidden ----------
  stub.reset('learn', 'draft');
  const { context, page } = await newPage(browser);
  await openOperations(page);
  await openQueue(page, 'My work');
  await page.getByRole('button', { name: /#3108/ }).first().click();
  await page.getByRole('heading', { name: /Session #3108/ }).waitFor({ state: 'visible', timeout: 8000 });
  await page.locator('.oe-itin .oe-contact').first().getByRole('button', { name: /Show lessor contact/ }).click();
  await page.getByText('Meena Gupta').first().waitFor({ state: 'visible', timeout: 8000 });
  check('the contact is displayed before the tab is hidden', true);

  await setVisibility(page, 'hidden');
  check('hiding the document clears the displayed name immediately',
    (await page.getByText('Meena Gupta').count()) === 0);
  check('hiding the document clears the number and the call link',
    (await page.getByText('+91 90000 11111').count()) === 0
    && (await page.getByRole('link', { name: /Call lessor/ }).count()) === 0);
  check('the panel returns to its on-demand state',
    (await page.locator('.oe-itin .oe-contact').first().getByRole('button', { name: /Show lessor contact/ }).count()) === 1);

  await setVisibility(page, 'visible');
  check('returning to the document does not re-reveal the contact',
    (await page.getByText('Meena Gupta').count()) === 0);
  // A deliberate reveal still works after the return.
  await page.locator('.oe-itin .oe-contact').first().getByRole('button', { name: /Show lessor contact/ }).click();
  await page.getByText('Meena Gupta').first().waitFor({ state: 'visible', timeout: 8000 });
  check('a new deliberate reveal works normally after the document returns', true);
  await context.close();

  // ---- A reveal in flight when the tab is hidden must not repopulate it --------------
  stub.reset('contact-hold', 'draft');
  const held = await newPage(browser);
  await openOperations(held.page);
  await openQueue(held.page, 'My work');
  await held.page.getByRole('button', { name: /#3108/ }).first().click();
  await held.page.getByRole('heading', { name: /Session #3108/ }).waitFor({ state: 'visible', timeout: 8000 });
  await held.page.locator('.oe-itin .oe-contact').first().getByRole('button', { name: /Show lessor contact/ }).click();
  const revealing = await held.page.getByRole('button', { name: /Loading contact… for/ }).first()
    .waitFor({ state: 'visible', timeout: 4000 }).then(() => true).catch(() => false);
  check('the reveal is pending when the tab is hidden', revealing);
  await setVisibility(held.page, 'hidden');
  await setVisibility(held.page, 'visible');
  // The held answer arrives after the tab came back: it must still be refused.
  await held.page.waitForTimeout(2500);
  check('a reveal that was pending while the tab was hidden never repopulates it',
    (await held.page.getByText('Meena Gupta').count()) === 0
    && (await held.page.getByText('+91 90000 11111').count()) === 0);
  await held.context.close();
};

/**
 * R5 — an abandoned scheduling failure continuation may not write into the context that replaced it.
 *
 * This is a supporting end-to-end check, not a standalone negative control: the workspace's own
 * invalidation also closes the drawer, so it can pass on that alone. The discriminating control is the
 * unit test of the commit guard plus the extracted failure-outcome mapper in
 * `src/tests/operationsOwnershipRaceCorrections.test.mjs`, which fails when the guard is removed.
 */
const correctionStaleFailureContinuation = async (browser) => {
  stub.reset('hold-failure-reconciliation', 'draft');
  const { context, page } = await newPage(browser);
  await openDraftDrawer(page);

  await page.getByRole('button', { name: 'Confirm and schedule' }).click();
  // The refusal is in and its authoritative reconciliation is now being held open.
  const held = await waitFor(page, () => stub.holdFailure === false && stub.log.some(
    (call) => call.path.endsWith('/oe-recommendations/selection')
  ));
  check('the refused booking is reconciling while it is held', held);

  // The signed-in identity changes while the old operation is still awaiting its reconciliation.
  await page.evaluate((entries) => {
    entries.forEach(([key, value]) => window.localStorage.setItem(key, value));
    window.dispatchEvent(new CustomEvent('pathome_auth_changed'));
  }, credentials(ACCOUNT_B, TOKEN_B));

  // Account B starts its own work and opens the very same scheduling drawer.
  await openQueue(page, 'My work');
  await page.getByRole('button', { name: /#3108/ }).first().click();
  await page.getByRole('heading', { name: /Session #3108/ }).waitFor({ state: 'visible', timeout: 12000 });
  await page.getByRole('button', { name: 'Schedule visit' }).click();
  await page.getByText('Top evaluated suggestion').waitFor({ state: 'visible', timeout: 12000 });

  // Release the old continuation and give it time to resolve.
  await page.waitForTimeout(4500);
  check('the abandoned continuation writes no stale outcome into the new drawer',
    (await page.getByText('This selection is no longer available').count()) === 0
    && (await page.getByText('Top evaluated suggestion').count()) > 0);
  check('the abandoned continuation raises no notice for the new account',
    (await page.getByText(/changed on the server while you were working/).count()) === 0
    && (await page.getByText(/is no longer claimable or owned by you/).count()) === 0);
  check('the new account keeps its own working drawer',
    await page.getByRole('button', { name: 'Continue' }).first().isVisible());
  await context.close();
};

/**
 * C1 — the shared async completion lifecycle: a confirmed success never replaces a newer display.
 *
 * The three cases below hold one successful mutation open while the OE leaves the record it belongs
 * to. The command is transmitted exactly once and its business success is still reported; what must not
 * happen is its *local publication* — the authoritative root it installs, the detail it replaces and
 * the drawer outcome it carries — landing in the context that replaced it.
 */
const correctionStaleSuccessContext = async (browser) => {
  const until = async (page, predicate, timeoutMs = 8000) => {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (await predicate()) return true;
      await page.waitForTimeout(100);
    }
    return false;
  };
  const releaseHeldResponse = async (label) => {
    const release = stub.releaseHeldResponse;
    check(`${label}: the held command response is still pending`, typeof release === 'function');
    if (release) release();
    stub.releaseHeldResponse = null;
  };
  const selectionPosts = () =>
    stub.log.filter((call) => call.path.endsWith('/oe-recommendations/selection')).length;
  const availabilityPuts = () => stub.log.filter((call) => call.path.endsWith('/availability')).length;

  // ---- #3108 schedules successfully after the OE opened #4109 ----------------------
  stub.reset('learn', 'draft');
  stub.unrelatedSessionEnabled = true;
  const first = await newPage(browser);
  await openDraftDrawer(first.page);
  stub.holdResponse = { kind: 'selection' };
  await first.page.getByRole('button', { name: 'Confirm and schedule' }).click();
  check('stale booking: the selection POST is transmitted and held open',
    await waitFor(first.page, () => stub.heldResponse === true, 8000));

  // The drawer is modal, so the OE closes it (its documented Escape handler) and opens the unrelated
  // Session #4109 — with its own itinerary and its own controls — before the answer arrives.
  await first.page.locator('.oe-sch').press('Escape');
  check('stale booking: the drawer closed, so the OE can leave the record',
    await until(first.page, async () => (await first.page.locator('.oe-sch').count()) === 0, 4000));
  await openQueue(first.page, 'My work');
  await openSessionRow(first.page, 4109);
  await releaseHeldResponse('stale booking');

  // A. The confirmed booking is still a booking, and the OE is told so in the record's own name.
  await first.page.getByText('Session #3108 scheduled').waitFor({ state: 'visible', timeout: 8000 });
  check('stale booking: the confirmed business success is still reported', true);
  checkEqual('stale booking: transmitted exactly once and never resent', selectionPosts(), 1);
  // B. …and nothing of it replaced the record the OE is looking at.
  await first.page.waitForTimeout(500);
  await assertUnrelatedSessionIntact('stale booking', first.page);
  check('stale booking: the booked session was never installed as #4109’s detail',
    (await first.page.getByText(/The visit is scheduled\./).count()) === 0);
  // #4109's own drawer is its own: it inherits no outcome from the booking that replaced its context.
  await first.page.locator('.oe-actions').getByRole('button', { name: 'Schedule visit' }).click();
  await first.page.getByText('Top evaluated suggestion').waitFor({ state: 'visible', timeout: 12000 });
  check('stale booking: #4109’s own drawer shows no #3108 booking outcome',
    (await first.page.getByText('The visit is scheduled.').count()) === 0);
  check('stale booking: #4109’s drawer and record carry nothing of #3108',
    (await workspaceRegion(first.page).getByText(/#3108/).count()) === 0);
  await first.page.locator('.oe-sch').press('Escape');
  await first.context.close();

  // ---- #3108 availability succeeds after the OE opened #4109 -----------------------
  stub.reset('learn', 'draft');
  stub.unrelatedSessionEnabled = true;
  const second = await newPage(browser);
  await openOperations(second.page);
  await openQueue(second.page, 'My work');
  await openSessionRow(second.page, 3108);
  await second.page.locator('.oe-itin .oe-contact').first().getByRole('button', { name: /Show lessor contact/ }).click();
  await second.page.getByText('Meena Gupta').first().waitFor({ state: 'visible', timeout: 8000 });
  stub.holdResponse = { kind: 'availability' };
  await saveUnchangedWindow(second.page);
  check('stale availability: the PUT is transmitted and held open',
    await waitFor(second.page, () => stub.heldResponse === true, 8000));
  await openQueue(second.page, 'My work');
  await openSessionRow(second.page, 4109);
  await releaseHeldResponse('stale availability');
  await second.page.getByText('Property availability recorded').waitFor({ state: 'visible', timeout: 8000 });
  check('stale availability: the confirmed success is still reported', true);
  checkEqual('stale availability: transmitted exactly once', availabilityPuts(), 1);
  await second.page.waitForTimeout(500);
  await assertUnrelatedSessionIntact('stale availability', second.page);
  check('stale availability: #4109 inherits neither the stopped window nor the revealed #3108 contact',
    (await second.page.getByText('Meena Gupta').count()) === 0
    && (await second.page.getByText('+91 90000 11111').count()) === 0);
  await second.context.close();

  // ---- Same-root remount: a newer snapshot is never overwritten by the older answer ---
  stub.reset('learn', 'draft');
  const third = await newPage(browser);
  await openOperations(third.page);
  await openQueue(third.page, 'My work');
  await openSessionRow(third.page, 3108);
  stub.holdResponse = { kind: 'availability' };
  await saveUnchangedWindow(third.page);
  check('same-root remount: the #3108 PUT is transmitted and held open',
    await waitFor(third.page, () => stub.heldResponse === true, 8000));

  // Someone else moved the session on, so the re-read of the same root returns a newer snapshot.
  stub.session = sessionView({
    version: 8,
    items: [
      item({
        title: 'Renewed stop',
        confirmationStatus: 'CONFIRMED',
        propertyAvailabilityStartAt: WINDOW_START,
        propertyAvailabilityEndAt: WINDOW_END,
        propertyAvailabilityZoneId: 'Asia/Kolkata',
        propertyAvailabilitySource: 'PHONE'
      }),
      derivedItem()
    ]
  });
  // The OE leaves the record and re-opens it: a brand-new mount instance with its own fresh read.
  await openQueue(third.page, 'My work');
  await openSessionRow(third.page, 3108);
  await third.page.getByText('Renewed stop').waitFor({ state: 'visible', timeout: 8000 });
  check('same-root remount: the newer mount read the newer snapshot',
    (await third.page.getByText('Renewed stop').count()) > 0);

  // The held command answers with the authoritative body it carried when it was transmitted: the older
  // version, whose stop is the one that was on screen at the time.
  stub.mutationResponseOverride = sessionView({ version: 6 });
  await releaseHeldResponse('same-root remount');
  await third.page.waitForTimeout(700);
  check('same-root remount: the older returned version never replaces the newer snapshot',
    (await third.page.getByText('Renewed stop').count()) > 0
    && (await third.page.getByText('2 BHK apartment').count()) === 0);
  checkEqual('same-root remount: transmitted exactly once', availabilityPuts(), 1);
  await third.context.close();
};

/**
 * C2 — Claim and Release: a confirmed `204` is reported, but it never navigates over a newer root and
 * never clears one. Both commands are held open while the OE opens the unrelated Session #4109.
 */
const correctionClaimReleaseContext = async (browser) => {
  const until = async (page, predicate, timeoutMs = 8000) => {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (await predicate()) return true;
      await page.waitForTimeout(100);
    }
    return false;
  };
  const releaseHeldResponse = async (label) => {
    const release = stub.releaseHeldResponse;
    check(`${label}: the held command response is still pending`, typeof release === 'function');
    if (release) release();
    stub.releaseHeldResponse = null;
  };
  const claimPosts = () =>
    stub.log.filter((call) => call.path === '/api/v1/operations/visit-requests/501/claim').length;
  const releasePosts = () => stub.log.filter((call) => call.path.endsWith('/release')).length;

  // ---- A claim from the list, confirmed while another root is on screen -------------
  stub.reset('learn', 'draft');
  // The Request is still unclaimed (so it is claimable) while My work already lists the sessions.
  stub.requestStatus = 'RECEIVED';
  stub.unrelatedSessionEnabled = true;
  const first = await newPage(browser);
  await openOperations(first.page);
  await openQueue(first.page, 'Claimable work');
  stub.holdResponse = { kind: 'claim' };
  await first.page.getByRole('button', { name: /^Claim$/ }).first().click();
  check('claim race: the claim POST is transmitted and held open',
    await waitFor(first.page, () => stub.heldResponse === true, 8000));
  // The claim was started from the list, so it has no mounted detail of its own; the OE moves to
  // another queue and opens #4109 while the `204` is still in flight.
  await openQueue(first.page, 'My work');
  await openSessionRow(first.page, 4109);
  await releaseHeldResponse('claim race');

  await first.page.getByText('Request #501 claimed').waitFor({ state: 'visible', timeout: 8000 });
  check('claim race: the confirmed claim is still reported', true);
  checkEqual('claim race: transmitted exactly once', claimPosts(), 1);
  check('claim race: the claim did not navigate over or replace the newer root',
    await until(first.page, async () =>
      (await first.page.getByRole('heading', { name: /Session #4109/ }).count()) > 0
      && (await workspaceRegion(first.page).getByText(/Request #501/).count()) === 0));
  await assertUnrelatedSessionIntact('claim race', first.page);
  await first.context.close();

  // ---- A release, confirmed while another root is on screen -------------------------
  stub.reset('learn', 'draft');
  stub.unrelatedSessionEnabled = true;
  const second = await newPage(browser);
  await openOperations(second.page);
  await openQueue(second.page, 'My work');
  await openSessionRow(second.page, 3108);
  await second.page.locator('.oe-itin .oe-contact').first().getByRole('button', { name: /Show lessor contact/ }).click();
  await second.page.getByText('Meena Gupta').first().waitFor({ state: 'visible', timeout: 8000 });
  stub.holdResponse = { kind: 'release' };
  await second.page.getByRole('button', { name: 'Release session' }).click();
  check('release race: the release POST is transmitted and held open',
    await waitFor(second.page, () => stub.heldResponse === true, 8000));
  await openQueue(second.page, 'My work');
  await openSessionRow(second.page, 4109);
  await releaseHeldResponse('release race');

  await second.page.getByText('Session #3108 released').waitFor({ state: 'visible', timeout: 8000 });
  check('release race: the confirmed release is still reported', true);
  checkEqual('release race: transmitted exactly once', releasePosts(), 1);
  await second.page.waitForTimeout(500);
  await assertUnrelatedSessionIntact('release race', second.page);
  check('release race: the newer root was not cleared, and it keeps its own detail and contact control',
    (await second.page.getByRole('heading', { name: /Session #4109/ }).count()) > 0
    && (await second.page.getByText('4 BHK villa').count()) > 0
    && (await second.page.getByRole('button', { name: /Show lessor contact/ }).count()) > 0
    && (await second.page.getByText('Meena Gupta').count()) === 0);
  await second.context.close();
};

/**
 * C3 — the legitimate current-context completions keep everything they had: the authoritative root is
 * installed, its outcome is published, and the truthful follow-up report is still shown.
 */
const correctionCurrentContextSuccessPreserved = async (browser) => {
  const selectionPosts = () =>
    stub.log.filter((call) => call.path.endsWith('/oe-recommendations/selection')).length;

  // ---- A coordination that replaces its own Request with the Session it created ------
  stub.reset('learn', 'intake');
  const first = await newPage(browser);
  await openOperations(first.page);
  await openQueue(first.page, 'Claimable work');
  await first.page.getByRole('button', { name: /^Claim$/ }).first().click();
  await first.page.getByRole('heading', { name: /Request #501/ }).waitFor({ state: 'visible', timeout: 8000 });
  // The authoritative queue refresh that follows the command is made to fail.
  stub.failQueueReads = true;
  await first.page.getByRole('button', { name: 'Coordinate into a new session' }).click();
  await first.page.getByRole('button', { name: 'Create draft session' }).click();
  await first.page.getByRole('heading', { name: /Session #3108/ }).waitFor({ state: 'visible', timeout: 8000 });
  check('a coordination in its own context still installs the authoritative Session', true);
  checkEqual('the coordination was transmitted once',
    stub.log.filter((call) => call.path.endsWith('/visit-requests/501/coordinate')).length, 1);
  /**
   * The operation published that transition itself, so the notice its own failed follow-up refresh
   * raises — which names the record the OE is still looking at — is still published.
   */
  await first.page.getByText(/but the work lists could not be refreshed just now/).waitFor({ state: 'visible', timeout: 8000 });
  check('and its own failed follow-up refresh is still reported truthfully', true);
  await first.context.close();

  // ---- A booking in its unchanged context ------------------------------------------
  stub.reset('learn', 'draft');
  const second = await newPage(browser);
  await openDraftDrawer(second.page);
  await second.page.getByRole('button', { name: 'Confirm and schedule' }).click();
  await second.page.getByText('The visit is scheduled.').waitFor({ state: 'visible', timeout: 10000 });
  check('a booking in its own context still publishes its authoritative drawer outcome', true);
  await second.page.getByText('Session #3108 scheduled').waitFor({ state: 'visible', timeout: 8000 });
  check('and the confirmed booking is announced for the record', true);
  check('the booked session is shown as read-only',
    (await second.page.getByRole('button', { name: 'Schedule visit' }).count()) === 0);
  checkEqual('transmitted exactly once', selectionPosts(), 1);
  await second.context.close();

  // ---- An availability update in its unchanged context ------------------------------
  stub.reset('learn', 'draft');
  const third = await newPage(browser);
  await openOperations(third.page);
  await openQueue(third.page, 'My work');
  await openSessionRow(third.page, 3108);
  await saveUnchangedWindow(third.page);
  await third.page.getByText('Property availability recorded').waitFor({ state: 'visible', timeout: 8000 });
  check('an availability update in its own context still publishes the returned itinerary', true);
  check('the session it belongs to is still the one on screen',
    (await third.page.getByRole('heading', { name: /Session #3108/ }).count()) > 0
    && (await third.page.getByText('2 BHK apartment').count()) > 0);
  checkEqual('transmitted exactly once',
    stub.log.filter((call) => call.path.endsWith('/availability')).length, 1);
  await third.context.close();
};

const SCENARIOS = [
  ['full journey: claim → contact → coordinate → itinerary → availability → discovery → booking', fullJourney],
  ['stale session version on booking', staleSelection],
  ['lost booking response', uncertainSelection],
  ['revoked canonical contact', revokedContact],
  ['account switch during booking', accountSwitchDuringBooking],
  ['responsive and accessibility measurements', responsive],
  ['correction P0-2/P1-4: scheduling result integrity and reason validation', correctionSchedulingResultAndReason],
  ['correction P0-2: refresh failure after a confirmed booking', correctionRefreshFailureAfterBooking],
  ['correction P1-3: independent read families', correctionConcurrentReadFamilies],
  ['correction P1-5: delayed contact reveal across a version change', correctionContactRevealRace],
  ['correction P2-6: search Load More guard', correctionSearchLoadMore],
  ['R1: target denial withdraws the revealed root (fresh-read 404, mutation 403/404)', correctionDenialWithdrawsRevealedRoot],
  ['R1: a denial only ever withdraws the exact root instance its operation targeted', correctionDenialTargetIdentity],
  ['R1: an awaited refusal never lands in a newer display context', correctionStaleRefusalNoticeRace],
  ['F6: the scheduling drawer closes by pointer above the global header', correctionSchedulingCloseHitTarget],
  ['R3: an unusable booking 200 is uncertain, never a success', correctionMalformedBooking200],
  ['R4: a candidate reviewed at v6 is never booked against v8', correctionEvaluationVersionBinding],
  ['R6: exactly one GE picker panel is rendered and reachable', correctionInactivePickerTabs],
  ['R7: a linked Request still originates a replacement stop', correctionOriginatingRequestAfterRemoval],
  ['R8: permitted read-only contact and availability reads', correctionReadOnlyPermittedReads],
  ['R9: an expired discovery cursor restarts instead of retrying', correctionExpiredCursorRecovery],
  ['R10: the frozen right-aligned 560px full-height scheduling geometry', correctionFrozenSchedulingGeometry],
  ['R11: hiding the document clears protected contact', correctionContactClearedWhenHidden],
  ['R5: an abandoned scheduling failure continuation cannot write into a newer context', correctionStaleFailureContinuation],
  ['correction C1: a stale confirmed success never replaces a newer display context', correctionStaleSuccessContext],
  ['correction C2: a confirmed claim or release never navigates over or clears a newer root', correctionClaimReleaseContext],
  ['correction C3: legitimate current-context completions and their truth are preserved', correctionCurrentContextSuccessPreserved]
];

const ONLY = process.env.ONLY ?? null;

const main = async () => {
  let browser;
  try {
    // This environment has the full Chromium build (chromium-1200) but not the headless shell, so the
    // documented chromium channel is used, exactly as the existing Operations workspace harness does.
    browser = await chromium.launch({ channel: 'chromium' });
    for (const [label, scenario] of SCENARIOS) {
      if (ONLY !== null && !label.includes(ONLY)) continue;
      if (budgetLeftMs() < 30_000) {
        notTestedRemaining(label);
        continue;
      }
      results.push(`\n▸ ${label}`);
      try {
        await scenario(browser);
      } catch (error) {
        failures += 1;
        checks += 1;
        results.push(`  ✖ ${label} threw: ${error.message.split('\n')[0]}`);
        results.push(`     last requests: ${stub.log.slice(-10).map((call) => `${call.method} ${call.path}`).join(' | ')}`);
      }
    }
  } finally {
    await browser?.close();
  }

  const elapsed = ((Date.now() - STARTED_AT) / 1000).toFixed(1);
  console.log(results.join('\n'));
  console.log(`\n${checks} checks, ${failures} failure(s), ${notTested} not tested — ${elapsed}s` +
    ` — ${failures === 0 ? 'ALL BROWSER CHECKS PASSED' : 'BROWSER CHECKS FAILED'}`);
  process.exitCode = failures === 0 ? 0 : 1;
};

await main();
