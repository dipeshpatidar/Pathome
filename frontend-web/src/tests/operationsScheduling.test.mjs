/**
 * F5 — Ground Executive discovery and authoritative booking.
 *
 * The exact ranked and cursor-paged requests, the candidate mapping (including "rank 0 is unranked"),
 * the reason contract and the "a 200 with an unusable body is unconfirmed" rule.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { installOperationsActionHarness } from './support/operationsActionHarness.mjs';

const h = await installOperationsActionHarness();
const { service, session, presentation } = h;

const ACCOUNT = { id: 41, role: 'EMPLOYEE', token: 'token-account-scheduling' };

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
  diagnostics: ['RANKED_WITHIN_FIRST_200_CITY_GE_ID_CANDIDATES'],
  rejectedGroundExecutives: [{ groundExecutiveUserId: 999 }],
  candidates: [candidate()],
  mode: 'RECOMMENDATIONS',
  nextCursor: null,
  pageSize: null,
  scannedCandidateCount: null,
  ...overrides
});

/** The evaluation a candidate is chosen from: this session at this version. */
const evaluated = (overrides = {}) => ({ sessionId: 3108, sessionVersion: 6, ...overrides });

const sessionView = (overrides = {}) => ({
  sessionId: 3108,
  status: 'DRAFT',
  version: 6,
  city: 'Indore',
  items: [
    {
      itemId: 91,
      listingId: 8001,
      title: '2 BHK apartment',
      sector: 'Vijay Nagar',
      position: 1,
      sourceRequestId: 501,
      derivedFromRequestId: null,
      origin: 'TENANT_REQUESTED',
      confirmationStatus: 'CONFIRMED',
      removedAt: null,
      removalReason: null,
      propertyAvailabilityStartAt: '2027-02-14T04:00:00Z',
      propertyAvailabilityEndAt: '2027-02-16T12:00:00Z',
      propertyAvailabilityZoneId: 'Asia/Kolkata',
      propertyAvailabilitySource: 'PHONE'
    }
  ],
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

test('the ranked read is a bounded POST with only the expected session version', async () => {
  h.reset(() => h.json(200, recommendationView()));
  h.signIn(ACCOUNT);

  const view = await service.rankedRecommendations(3108, 6, { session: session.captureOperationsSession() });

  assert.equal(h.calls.length, 1);
  assert.equal(h.calls[0].method, 'POST');
  assert.match(h.calls[0].url, /\/operations\/visit-sessions\/3108\/oe-recommendations$/);
  assert.equal(h.calls[0].url.includes('?'), false);
  assert.deepEqual(h.calls[0].body, { expectedSessionVersion: 6 });
  assert.equal(view.mode, 'RECOMMENDATIONS');
  assert.equal(view.candidates.length, 1);
  assert.equal(view.candidates[0].rank, 1);
  assert.equal(view.candidates[0].durationMinutes, 120);
  // Internal rejection detail is never part of the client model.
  assert.equal('rejectedGroundExecutives' in view, false);
});

test('browse pages with all=true and size, and search adds the trimmed query, never a fabricated total', async () => {
  h.reset(() => h.json(200, recommendationView({
    mode: 'DISCOVERY',
    candidates: [candidate({ rank: 0 })],
    nextCursor: 'cursor-1',
    pageSize: 20,
    scannedCandidateCount: 20
  })));
  h.signIn(ACCOUNT);

  const browsed = await service.discoverGroundExecutives(3108, 6, {}, { session: session.captureOperationsSession() });
  assert.equal(h.calls[0].method, 'POST');
  assert.match(h.calls[0].url, /\?all=true&size=20$/);
  assert.deepEqual(h.calls[0].body, { expectedSessionVersion: 6 });
  assert.equal(browsed.nextCursor, 'cursor-1');
  assert.equal(browsed.pageSize, 20);
  assert.equal(browsed.scannedCandidateCount, 20);
  // An unranked discovery candidate is exactly rank 0: it is not a quality statement.
  assert.equal(browsed.candidates[0].rank, 0);
  assert.equal('totalCount' in browsed, false);

  h.reset(() => h.json(200, recommendationView({ mode: 'DISCOVERY', candidates: [] })));
  await service.discoverGroundExecutives(
    3108,
    6,
    { q: ' Rahul Verma ', cursor: 'opaque cursor' },
    { session: session.captureOperationsSession() }
  );
  const url = new URL(h.calls[0].url, 'http://oe.local');
  assert.equal(url.searchParams.get('all'), 'true');
  assert.equal(url.searchParams.get('size'), '20');
  assert.equal(url.searchParams.get('q'), 'Rahul Verma');
  assert.equal(url.searchParams.get('cursor'), 'opaque cursor');

  // A blank query is a browse, not an empty search term sent to the server.
  h.reset(() => h.json(200, recommendationView({ mode: 'DISCOVERY' })));
  await service.discoverGroundExecutives(3108, 6, { q: '   ' }, { session: session.captureOperationsSession() });
  assert.equal(h.calls[0].url.includes('q='), false);
});

test('a rejected continuation keeps its real 400 so the picker can restart instead of retrying', async () => {
  // The backend rejects an expired/foreign cursor page with 400. The service must surface that exact
  // status: the picker distinguishes it from a transient failure and restarts from the first page
  // rather than resending the dead cursor.
  h.reset(() => h.json(400, { message: 'Cursor page context is no longer available' }));
  h.signIn(ACCOUNT);

  await assert.rejects(
    service.discoverGroundExecutives(
      3108,
      6,
      { q: 'Ravi', cursor: 'expired-cursor' },
      { session: session.captureOperationsSession() }
    ),
    (error) => {
      assert.equal(error.status, 400);
      return true;
    }
  );
  assert.equal(h.calls.length, 1);
  assert.match(h.calls[0].url, /cursor=expired-cursor/);
});

test('the top evaluated suggestion is booked with no reason, and every other choice needs one', () => {
  const detail = presentation.toSessionDetail(sessionView());
  const view = presentation.toRecommendationView(recommendationView());
  const top = { candidate: view.candidates[0], source: 'recommended', evaluated: evaluated() };

  assert.equal(presentation.selectionIsTopRecommendation(view, top), true);
  const topCommand = presentation.scheduleSelectionCommand(detail, top, view, '');
  assert.deepEqual(topCommand.validation.errors, {});
  assert.equal(topCommand.command.overrideReason, null);
  assert.deepEqual(topCommand.command, {
    targetType: 'SESSION',
    expectedSessionVersion: 6,
    groundExecutiveUserId: 4207,
    scheduledAt: '2027-02-14T05:00:00Z',
    zoneId: 'Asia/Kolkata',
    overrideReason: null
  });

  // A different Ground Executive, even at the top candidate's own time, owes a reason.
  const other = { candidate: candidate({ groundExecutiveUserId: 5001, rank: 0 }), source: 'browse', evaluated: evaluated() };
  const missing = presentation.scheduleSelectionCommand(detail, other, view, '   ');
  assert.equal(missing.command, null);
  assert.match(missing.validation.errors.reason, /reason/);

  const supplied = presentation.scheduleSelectionCommand(detail, other, view, '  Nearest to the tenant  ');
  assert.equal(supplied.command.overrideReason, 'Nearest to the tenant');

  const tooLong = presentation.scheduleSelectionCommand(detail, other, view, 'x'.repeat(501));
  assert.equal(tooLong.command, null);
  assert.match(tooLong.validation.errors.reason, /500/);
});

test('a selection without a usable evaluated time or zone is refused before anything is sent', () => {
  const detail = presentation.toSessionDetail(sessionView());
  const view = presentation.toRecommendationView(recommendationView());

  const noTime = presentation.scheduleSelectionCommand(
    detail,
    { candidate: candidate({ scheduledAt: null }), source: 'browse', evaluated: evaluated() },
    view,
    'reason'
  );
  assert.equal(noTime.command, null);
  assert.ok(noTime.validation.errors.selection);

  const noZone = presentation.scheduleSelectionCommand(
    detail,
    { candidate: candidate({ zoneId: null }), source: 'browse', evaluated: evaluated() },
    view,
    'reason'
  );
  assert.equal(noZone.command, null);
  assert.ok(noZone.validation.errors.selection);

  const noSelection = presentation.scheduleSelectionCommand(detail, null, view, 'reason');
  assert.equal(noSelection.command, null);
  assert.ok(noSelection.validation.errors.selection);
});

test('the booking command is a single POST that applies the authoritative returned session', async () => {
  h.reset(() => h.json(200, {
    session: sessionView({
      status: 'SCHEDULED',
      version: 7,
      scheduledAt: '2027-02-14T05:00:00Z',
      reservedEndAt: '2027-02-14T07:00:00Z',
      durationMinutes: 120,
      zoneId: 'Asia/Kolkata',
      representativeUserId: 4207,
      assignedAt: '2026-10-12T16:30:00Z',
      tenantConfirmationState: 'PENDING'
    }),
    decisionId: 88,
    override: false,
    topRecommendedGroundExecutiveUserId: 4207,
    topRecommendedAt: '2027-02-14T05:00:00Z'
  }));
  h.signIn(ACCOUNT);

  const approval = await service.selectGroundExecutive(
    3108,
    {
      expectedSessionVersion: 6,
      groundExecutiveUserId: 4207,
      scheduledAt: '2027-02-14T05:00:00Z',
      zoneId: 'Asia/Kolkata',
      overrideReason: null
    },
    { session: session.captureOperationsSession() }
  );

  assert.equal(h.calls.length, 1);
  assert.equal(h.calls[0].method, 'POST');
  assert.match(h.calls[0].url, /\/operations\/visit-sessions\/3108\/oe-recommendations\/selection$/);
  assert.deepEqual(h.calls[0].body, {
    expectedSessionVersion: 6,
    groundExecutiveUserId: 4207,
    scheduledAt: '2027-02-14T05:00:00Z',
    zoneId: 'Asia/Kolkata',
    overrideReason: null
  });
  assert.equal(approval.override, false);
  assert.equal(approval.decisionId, 88);
  assert.equal(approval.session.status, 'SCHEDULED');
  assert.equal(approval.session.version, 7);
  assert.equal(approval.session.representativeUserId, 4207);
  // The tenant-confirmation state is taken from the server's session and nothing is assumed.
  assert.equal(approval.session.tenantConfirmationState, 'PENDING');
  assert.equal(
    presentation.tenantConfirmationLabel(approval.session.tenantConfirmationState),
    'Awaiting tenant'
  );
});

test('an override selection is sent with its reason', async () => {
  // The response must be a complete authoritative scheduled Session: a partial one is refused (P0-2).
  h.reset(() => h.json(200, {
    session: scheduledSession({ representativeUserId: 5001 }),
    decisionId: 89,
    override: true,
    topRecommendedGroundExecutiveUserId: 4207
  }));
  h.signIn(ACCOUNT);

  await service.selectGroundExecutive(
    3108,
    {
      expectedSessionVersion: 6,
      groundExecutiveUserId: 5001,
      scheduledAt: '2027-02-14T05:00:00Z',
      zoneId: 'Asia/Kolkata',
      overrideReason: 'Closest to the tenant’s preferred time'
    },
    { session: session.captureOperationsSession() }
  );

  assert.equal(h.calls[0].body.overrideReason, 'Closest to the tenant’s preferred time');
});

/* ------------------------------------------------------------------ *
 * R3 — a transmitted selection whose 200 cannot prove *this* booking is uncertain, never a success
 *
 * Sol reproduced a "successful" approval for `scheduledAt: 'not-a-time'` and `representativeUserId:
 * -1`. The mapper now requires a real instant and a positive safe GE id, and binds the answer to the
 * exact submitted selection (root, advanced version, GE and instant), so every variant below is
 * classified as an unconfirmed transmission: one POST, no replay, and a safe read reconciliation.
 * ------------------------------------------------------------------ */

const approvalVariant = (session) => ({
  session: sessionView({
    status: 'SCHEDULED',
    version: 7,
    scheduledAt: '2027-02-14T05:00:00Z',
    reservedEndAt: '2027-02-14T07:00:00Z',
    durationMinutes: 120,
    zoneId: 'Asia/Kolkata',
    representativeUserId: 4207,
    assignedAt: '2026-10-12T16:30:00Z',
    tenantConfirmationState: 'PENDING',
    ...session
  }),
  decisionId: 88,
  override: false
});

test('an unusable HTTP 200 is uncertain: no success, exactly one POST and a safe read reconciliation', async () => {
  const variants = [
    ['invalid nonblank time text', { scheduledAt: 'not-a-time' }],
    ['a negative assigned GE id', { representativeUserId: -1 }],
    ['a zero assigned GE id', { representativeUserId: 0 }],
    ['a mismatched assigned GE', { representativeUserId: 5001 }],
    ['a mismatched recorded instant', { scheduledAt: '2027-02-14T06:00:00Z' }],
    ['a stale version that did not advance', { version: 6 }],
    ['a missing assigned GE', { representativeUserId: null }],
    ['a non-scheduled state', { status: 'DRAFT' }]
  ];
  for (const [label, overrides] of variants) {
    // The authoritative reads that follow the transmission answer normally.
    h.reset((call) => {
      if (call.url.includes('/oe-recommendations/selection')) return h.json(200, approvalVariant(overrides));
      return h.json(200, { items: [], totalCount: 0, page: 0, size: 20, totalPages: 0 });
    });
    h.signIn(ACCOUNT);

    await assert.rejects(
      service.selectGroundExecutive(3108, bookingCommand, { session: session.captureOperationsSession() }),
      (error) => {
        assert.equal(error.status, 200, label);
        assert.equal(
          presentation.ownershipFailureKind({ dispatched: true, status: error.status }),
          'uncertain',
          label
        );
        return true;
      },
      label
    );

    // Exactly one selection POST: the unusable answer is never replayed automatically.
    const selectionPosts = h.calls.filter((call) => call.url.includes('/oe-recommendations/selection'));
    assert.equal(selectionPosts.length, 1, label);
    assert.equal(selectionPosts[0].method, 'POST', label);
    // A confirmed booking is never announced for an answer that cannot be displayed.
    assert.equal(presentation.toSchedulingApproval(approvalVariant(overrides), {
      sessionId: 3108,
      expectedSessionVersion: 6,
      groundExecutiveUserId: 4207,
      scheduledAt: '2027-02-14T05:00:00Z'
    }), null, label);
  }
});

/* ------------------------------------------------------------------ *
 * R4 — a candidate is bound to the Session version it was evaluated against
 * ------------------------------------------------------------------ */

test('a candidate evaluated at v6 is refused once the authoritative session is at v8', () => {
  const view = presentation.toRecommendationView(recommendationView({ sessionVersion: 6 }));
  const selection = {
    candidate: view.candidates[0],
    source: 'recommended',
    evaluated: { sessionId: 3108, sessionVersion: 6 }
  };

  // Reviewed version: the command is built exactly as before.
  const reviewed = presentation.scheduleSelectionCommand(
    presentation.toSessionDetail(sessionView({ version: 6 })),
    selection,
    view,
    ''
  );
  assert.deepEqual(reviewed.validation.errors, {});
  assert.equal(reviewed.command.expectedSessionVersion, 6);

  // The same selection against a fresh, changed itinerary at v8: no command at all.
  const changed = presentation.scheduleSelectionCommand(
    presentation.toSessionDetail(sessionView({
      version: 8,
      items: [
        {
          ...sessionView().items[0],
          itemId: 95,
          listingId: 8009,
          title: 'Replacement stop'
        }
      ]
    })),
    selection,
    view,
    ''
  );
  assert.equal(changed.command, null);
  assert.match(changed.validation.errors.selection, /changed since these candidates were evaluated/);

  // A selection from another Session can never be booked here either.
  assert.equal(
    presentation.scheduleSelectionCommand(
      presentation.toSessionDetail(sessionView({ sessionId: 4242 })),
      selection,
      view,
      ''
    ).command,
    null
  );
  assert.equal(presentation.selectionMatchesSession({ sessionId: 3108, version: 8 }, selection), false);
  assert.equal(presentation.selectionMatchesSession({ sessionId: 3108, version: 6 }, selection), true);
});

test('a recommendation envelope from another session or version is never usable', () => {
  assert.equal(
    presentation.recommendationViewMatchesContext(
      presentation.toRecommendationView(recommendationView()),
      3108,
      6
    ),
    true
  );
  // Wrong Session.
  assert.equal(
    presentation.recommendationViewMatchesContext(
      presentation.toRecommendationView(recommendationView({ sessionId: 4242 })),
      3108,
      6
    ),
    false
  );
  // Wrong version: the server evaluated a different itinerary.
  assert.equal(
    presentation.recommendationViewMatchesContext(
      presentation.toRecommendationView(recommendationView({ sessionVersion: 8 })),
      3108,
      6
    ),
    false
  );
  // A view that carries no identity at all is not usable either.
  assert.equal(
    presentation.recommendationViewMatchesContext(
      presentation.toRecommendationView(recommendationView({ sessionId: null, sessionVersion: null })),
      3108,
      6
    ),
    false
  );
  // A stale view can never exempt a reason, even for its own top candidate.
  const stale = presentation.toRecommendationView(recommendationView({ sessionVersion: 8 }));
  const selection = {
    candidate: stale.candidates[0],
    source: 'recommended',
    evaluated: { sessionId: 3108, sessionVersion: 6 }
  };
  assert.equal(presentation.selectionIsTopRecommendation(stale, selection), false);
  assert.equal(presentation.selectionNeedsReason(stale, selection), true);
});

test('a 409 and a 400 keep their real status so the drawer can refresh and re-review', async () => {
  for (const status of [400, 409]) {
    h.reset(() => h.json(status, { message: status === 409 ? 'Selection is stale' : 'Override reason is required' }));
    h.signIn(ACCOUNT);
    await assert.rejects(
      service.selectGroundExecutive(
        3108,
        {
          expectedSessionVersion: 6,
          groundExecutiveUserId: 4207,
          scheduledAt: '2027-02-14T05:00:00Z',
          zoneId: 'Asia/Kolkata',
          overrideReason: null
        },
        { session: session.captureOperationsSession() }
      ),
      (error) => error.status === status
    );
  }
});

test('a booking response that cannot be decoded is unconfirmed, never a silent success or failure', async () => {
  h.reset(() => h.undecodable(200));
  h.signIn(ACCOUNT);

  await assert.rejects(
    service.selectGroundExecutive(
      3108,
      {
        expectedSessionVersion: 6,
        groundExecutiveUserId: 4207,
        scheduledAt: '2027-02-14T05:00:00Z',
        zoneId: 'Asia/Kolkata',
        overrideReason: null
      },
      { session: session.captureOperationsSession() }
    ),
    (error) => {
      assert.equal(error.status, 200);
      assert.equal(presentation.ownershipFailureKind({ dispatched: true, status: error.status }), 'uncertain');
      return true;
    }
  );
});

/* ------------------------------------------------------------------ *
 * P0-2 — an incomplete authoritative booking answer is never a success
 * ------------------------------------------------------------------ */

const bookingCommand = {
  expectedSessionVersion: 6,
  groundExecutiveUserId: 4207,
  scheduledAt: '2027-02-14T05:00:00Z',
  zoneId: 'Asia/Kolkata',
  overrideReason: null
};

const bookAttempt = () =>
  service.selectGroundExecutive(3108, bookingCommand, { session: session.captureOperationsSession() });

/** A complete, authoritative scheduled Session as the backend returns it. */
const scheduledSession = (overrides = {}) => sessionView({
  status: 'SCHEDULED',
  version: 7,
  scheduledAt: '2027-02-14T05:00:00Z',
  reservedEndAt: '2027-02-14T07:00:00Z',
  durationMinutes: 120,
  zoneId: 'Asia/Kolkata',
  representativeUserId: 4207,
  assignedAt: '2026-10-12T16:30:00Z',
  ...overrides
});

/* ------------------------------------------------------------------ *
 * R3 — strict instant validation (Sol's two independently reproduced defects)
 *
 * The previous rule was `INSTANT_PATTERN` plus `Date.parse` equality. `Date.parse` normalises an
 * impossible calendar date into a real one and keeps only milliseconds, so `2027-02-30T05:00:00Z` was
 * accepted and two instants one nanosecond apart compared equal. Both are refused by the strict
 * parser/comparator below, which is the value the booking contract is now validated with.
 * ------------------------------------------------------------------ */

test('the instant validator refuses impossible dates, clock readings and offsets, and requires an offset', () => {
  const { isOperationsInstant, parseOperationsInstant } = presentation;

  for (const valid of [
    '2027-02-14T05:00:00Z',
    '2027-02-14T05:00Z',
    '2027-02-14T05:00:00+05:30',
    '2027-02-14T05:00:00-18:00',
    '2027-02-14T05:00:00+18:00',
    '2027-02-14T05:00:00.5Z',
    '2027-02-14T05:00:00.123456789Z',
    '2028-02-29T00:00:00Z',
    '2000-02-29T23:59:59.999999999Z',
    '2027-04-30T23:59Z'
  ]) {
    assert.equal(isOperationsInstant(valid), true, valid);
  }

  for (const invalid of [
    // Impossible calendar dates: the exact class of defect Sol reproduced.
    '2027-02-30T05:00:00Z',
    '2027-02-29T05:00:00Z',
    '1900-02-29T00:00:00Z',
    '2027-04-31T05:00:00Z',
    '2027-13-01T05:00:00Z',
    '2027-00-10T05:00:00Z',
    '2027-01-00T05:00:00Z',
    // Out-of-range clock readings.
    '2027-02-14T24:00:00Z',
    '2027-02-14T05:60:00Z',
    '2027-02-14T05:00:60Z',
    // Impossible or malformed offsets, and a missing designator.
    '2027-02-14T05:00:00+19:00',
    '2027-02-14T05:00:00+18:30',
    '2027-02-14T05:00:00+05:60',
    '2027-02-14T05:00:00+0530',
    '2027-02-14T05:00:00',
    // Shape violations.
    '2027-02-14 05:00:00Z',
    '2027-02-14T05:00:00.Z',
    '2027-02-14T05:00:00.1234567890Z',
    '2027-02-14T05:00:00z',
    '',
    null,
    undefined
  ]) {
    assert.equal(isOperationsInstant(invalid), false, String(invalid));
  }

  // The parsed value keeps the exact whole second and all nine fractional digits.
  assert.deepEqual(parseOperationsInstant('2027-02-14T10:30:00.123456789+05:30'), {
    epochSeconds: Date.UTC(2027, 1, 14, 5, 0, 0) / 1000,
    fraction: '123456789'
  });
  assert.equal(parseOperationsInstant('2027-02-30T05:00:00Z'), null);
});

test('equivalent instants in different offsets are equal, and a nanosecond difference is not', () => {
  const { operationsInstantsEqual, compareOperationsInstants } = presentation;

  // The same real instant, written as UTC and as the zone's own offset.
  assert.equal(operationsInstantsEqual('2027-02-14T10:30:00+05:30', '2027-02-14T05:00:00Z'), true);
  assert.equal(operationsInstantsEqual('2027-02-14T05:00:00Z', '2027-02-14T05:00:00.5Z'), false);
  assert.equal(operationsInstantsEqual('2027-02-14T05:00:00.5Z', '2027-02-14T05:00:00.500Z'), true);
  assert.equal(operationsInstantsEqual('2027-02-14T05:00:00.12Z', '2027-02-14T05:00:00.120000000Z'), true);
  // Nine digits are compared exactly: one nanosecond apart is a different instant.
  assert.equal(
    operationsInstantsEqual('2027-02-14T05:00:00.123456789Z', '2027-02-14T05:00:00.123456788Z'),
    false
  );
  assert.equal(operationsInstantsEqual('2027-02-14T05:00:00Z', '2027-02-14T05:00:00.000000001Z'), false);
  // An unusable side is never equal to, and never ordered against, a real instant.
  assert.equal(operationsInstantsEqual('2027-02-30T05:00:00Z', '2027-03-02T05:00:00Z'), false);
  assert.equal(compareOperationsInstants('not-a-time', '2027-02-14T05:00:00Z'), null);
  assert.equal(compareOperationsInstants('2027-02-14T05:00:00Z', '2027-02-14T05:00:01Z'), -1);
  assert.equal(compareOperationsInstants('2027-02-14T05:00:01Z', '2027-02-14T05:00:00Z'), 1);
  assert.equal(
    compareOperationsInstants('2027-02-14T05:00:00.123456789Z', '2027-02-14T05:00:00.123456790Z'),
    -1
  );

  /**
   * Negative control: the rule this replaces accepted both of Sol's defects. If this ever stops being
   * true, the assertions above are no longer the thing that catches them.
   */
  assert.equal(Number.isFinite(Date.parse('2027-02-30T05:00:00Z')), true);
  assert.equal(
    Date.parse('2027-02-14T05:00:00.123456789Z') === Date.parse('2027-02-14T05:00:00.123456788Z'),
    true
  );
});

test('Sol’s Case A and Case B are refused by the authoritative-approval mapper', () => {
  const expectation = {
    sessionId: 3108,
    expectedSessionVersion: 6,
    groundExecutiveUserId: 4207
  };

  // Case A — the submitted instant is a real calendar date; the recorded one cannot exist.
  assert.equal(
    presentation.toSchedulingApproval(
      approvalVariant({ scheduledAt: '2027-02-30T05:00:00Z' }),
      { ...expectation, scheduledAt: '2027-03-02T05:00:00Z' }
    ),
    null
  );
  // Case B — the recorded instant is one nanosecond earlier than the one submitted.
  assert.equal(
    presentation.toSchedulingApproval(
      approvalVariant({ scheduledAt: '2027-02-14T05:00:00.123456788Z' }),
      { ...expectation, scheduledAt: '2027-02-14T05:00:00.123456789Z' }
    ),
    null
  );

  // The same instant written as the zone's own offset is the booking that was submitted.
  assert.ok(presentation.toSchedulingApproval(
    approvalVariant({ scheduledAt: '2027-02-14T10:30:00+05:30' }),
    { ...expectation, scheduledAt: '2027-02-14T05:00:00Z' }
  ));
  // An identical nanosecond instant is accepted, and a real leap day survives the strict calendar.
  assert.ok(presentation.toSchedulingApproval(
    approvalVariant({ scheduledAt: '2027-02-14T05:00:00.123456789Z' }),
    { ...expectation, scheduledAt: '2027-02-14T05:00:00.123456789Z' }
  ));
  assert.ok(presentation.toSchedulingApproval(
    approvalVariant({ scheduledAt: '2028-02-29T05:00:00Z' }),
    { ...expectation, scheduledAt: '2028-02-29T05:00:00Z' }
  ));
  // A non-leap February 29 can never be a usable recorded or submitted instant.
  assert.equal(presentation.toSchedulingApproval(
    approvalVariant({ scheduledAt: '2027-02-29T05:00:00Z' }),
    { ...expectation, scheduledAt: '2027-02-29T05:00:00Z' }
  ), null);

  // The submitted Session identity, version and Ground Executive stay bound exactly as before.
  assert.equal(presentation.toSchedulingApproval(
    approvalVariant({ sessionId: 4242 }),
    { ...expectation, scheduledAt: '2027-02-14T05:00:00Z' }
  ), null);
  assert.equal(presentation.toSchedulingApproval(
    approvalVariant({ version: 6 }),
    { ...expectation, scheduledAt: '2027-02-14T05:00:00Z' }
  ), null);
  for (const groundExecutiveUserId of [0, -1, null, 1.5]) {
    assert.equal(presentation.toSchedulingApproval(
      approvalVariant(),
      { ...expectation, scheduledAt: '2027-02-14T05:00:00Z', groundExecutiveUserId }
    ), null, `GE ${String(groundExecutiveUserId)}`);
  }
  assert.equal(presentation.toSchedulingApproval(
    approvalVariant({ representativeUserId: 5001 }),
    { ...expectation, scheduledAt: '2027-02-14T05:00:00Z' }
  ), null);
  // A recorded instant that is simply not an instant at all is still refused.
  assert.equal(presentation.toSchedulingApproval(
    approvalVariant({ scheduledAt: 'not-a-time' }),
    { ...expectation, scheduledAt: 'not-a-time' }
  ), null);
});

test('a 200 recorded with an impossible date or a nanosecond mismatch is uncertain, sent once', async () => {
  for (const [label, recordedAt, submittedAt] of [
    ['Case A — impossible calendar date', '2027-02-30T05:00:00Z', '2027-03-02T05:00:00Z'],
    ['Case B — one-nanosecond mismatch', '2027-02-14T05:00:00.123456788Z', '2027-02-14T05:00:00.123456789Z']
  ]) {
    // The authoritative reads that follow the transmission answer normally.
    h.reset((call) => {
      if (call.url.includes('/oe-recommendations/selection')) {
        return h.json(200, approvalVariant({ scheduledAt: recordedAt }));
      }
      return h.json(200, { items: [], totalCount: 0, page: 0, size: 20, totalPages: 0 });
    });
    h.signIn(ACCOUNT);

    await assert.rejects(
      service.selectGroundExecutive(
        3108,
        { ...bookingCommand, scheduledAt: submittedAt },
        { session: session.captureOperationsSession() }
      ),
      (error) => {
        // The transmission happened and its answer cannot be trusted: unconfirmed, never a booking.
        assert.equal(error.status, 200, label);
        assert.equal(presentation.ownershipFailureKind({ dispatched: true, status: error.status }), 'uncertain', label);
        return true;
      },
      label
    );

    // Exactly one selection POST: an unusable answer is never replayed.
    const selectionPosts = h.calls.filter((call) => call.url.includes('/oe-recommendations/selection'));
    assert.equal(selectionPosts.length, 1, label);
    assert.equal(selectionPosts[0].method, 'POST', label);
    // And nothing the drawer could render as a confirmed booking comes back from the service.
    assert.equal(
      presentation.toSchedulingApproval(approvalVariant({ scheduledAt: recordedAt }), {
        sessionId: 3108,
        expectedSessionVersion: 6,
        groundExecutiveUserId: 4207,
        scheduledAt: submittedAt
      }),
      null,
      label
    );
    // The classified drawer outcome is uncertainty, never a refusal and never a success.
    const outcome = presentation.schedulingFailureOutcome({
      kind: presentation.ownershipFailureKind({ dispatched: true, status: 200 }),
      status: 200,
      message: 'This visit could not be scheduled.',
      detail: null
    });
    assert.equal(outcome.kind, 'uncertain', label);
  }
});

test('a normal successful booking still succeeds with the strict instant rule', async () => {
  h.reset(() => h.json(200, {
    session: scheduledSession({ scheduledAt: '2027-02-14T10:30:00.250000000+05:30' }),
    decisionId: 88,
    override: false
  }));
  h.signIn(ACCOUNT);

  const approval = await service.selectGroundExecutive(
    3108,
    { ...bookingCommand, scheduledAt: '2027-02-14T05:00:00.25Z' },
    { session: session.captureOperationsSession() }
  );

  assert.equal(approval.session.status, 'SCHEDULED');
  assert.equal(approval.session.version, 7);
  assert.equal(approval.session.representativeUserId, 4207);
  assert.equal(h.calls.length, 1);
  assert.equal(h.calls[0].method, 'POST');
});

test('only a complete authoritative scheduled Session confirms a booking', () => {
  const approval = presentation.toSchedulingApproval(
    { session: scheduledSession(), decisionId: 88, override: false, topRecommendedGroundExecutiveUserId: 4207 },
    3108
  );
  assert.ok(approval);
  assert.equal(approval.session.sessionId, 3108);
  assert.equal(approval.session.status, 'SCHEDULED');
  assert.equal(approval.session.version, 7);
  assert.equal(approval.session.representativeUserId, 4207);
});

test('an incomplete or malformed approval body is refused, never read as a booking', () => {
  const refused = [
    ['no session at all', { decisionId: 90, override: false }],
    ['session is null', { session: null, decisionId: 90 }],
    ['session is not an object', { session: 'scheduled', decisionId: 90 }],
    ['a different session id', { session: scheduledSession({ sessionId: 9999 }), decisionId: 90 }],
    ['a session with no id', { session: scheduledSession({ sessionId: null }), decisionId: 90 }],
    ['a session with no version', { session: scheduledSession({ version: null }), decisionId: 90 }],
    ['a non-positive version', { session: scheduledSession({ version: 0 }), decisionId: 90 }],
    ['a non-scheduled state', { session: scheduledSession({ status: 'DRAFT' }), decisionId: 90 }],
    ['a scheduled state without a booked time', { session: scheduledSession({ scheduledAt: null }), decisionId: 90 }],
    ['a scheduled state without an assigned GE', { session: scheduledSession({ representativeUserId: null }), decisionId: 90 }],
    ['the whole body is not an object', 'ok']
  ];
  for (const [label, payload] of refused) {
    assert.equal(presentation.toSchedulingApproval(payload, 3108), null, label);
  }
  // Without an expected id the identity check is skipped, but the scheduled state is still required.
  assert.ok(presentation.toSchedulingApproval({ session: scheduledSession(), decisionId: 1 }));
  assert.equal(presentation.toSchedulingApproval({ session: scheduledSession({ status: 'CANCELLED' }) }), null);
});

test('a 200 with no usable Session is an unconfirmed outcome, not a booking or a refusal', async () => {
  for (const [label, payload] of [
    ['missing session', { decisionId: 90, override: false }],
    ['mismatched session', { session: scheduledSession({ sessionId: 4242 }), decisionId: 90 }],
    ['not scheduled', { session: scheduledSession({ status: 'DRAFT' }), decisionId: 90 }],
    ['scheduled without a representative', { session: scheduledSession({ representativeUserId: null }), decisionId: 90 }]
  ]) {
    h.reset(() => h.json(200, payload));
    h.signIn(ACCOUNT);
    await assert.rejects(bookAttempt(), (error) => {
      assert.equal(error.status, 200, label);
      // The transmission happened and the answer cannot be trusted: unconfirmed, never "nothing happened".
      assert.equal(presentation.ownershipFailureKind({ dispatched: true, status: error.status }), 'uncertain', label);
      return true;
    });
  }
  // A malformed body after transmission is the same unconfirmed outcome.
  h.reset(() => h.undecodable(200));
  h.signIn(ACCOUNT);
  await assert.rejects(bookAttempt(), (error) => {
    assert.equal(error.status, 200);
    assert.equal(presentation.ownershipFailureKind({ dispatched: true, status: error.status }), 'uncertain');
    return true;
  });
});

test('a response that schedules a different session is refused, never re-labelled as this one', async () => {
  h.reset(() => h.json(200, {
    session: scheduledSession({ sessionId: 7777, representativeUserId: 4242 }),
    decisionId: 91,
    override: false
  }));
  h.signIn(ACCOUNT);
  await assert.rejects(bookAttempt(), (error) => error.status === 200);
  // No local helper can produce a scheduled state: the only mapping is the validated one above.
  assert.equal(presentation.toSchedulingApproval({ decisionId: 91, override: false }), null);
});

test('recommendation statuses are explained truthfully, and a truncated search says so', () => {
  assert.match(presentation.recommendationStatusHint('SESSION_NOT_SCHEDULABLE'), /active stop/);
  assert.match(presentation.recommendationStatusHint('INSUFFICIENT_TENANT_AVAILABILITY'), /tenant/);
  assert.match(presentation.recommendationStatusHint('PROPERTY_AVAILABILITY_INCOMPLETE'), /property-input gap/);
  assert.match(presentation.recommendationStatusHint('NO_ELIGIBLE_GE'), /No active Ground Executive/);
  assert.match(presentation.recommendationStatusHint('NO_FEASIBLE_TIME'), /bounded evaluation/);
  assert.equal(presentation.recommendationStatusHint('EVALUATED'), null);
  const unknown = presentation.recommendationStatusHint('SOMETHING_ELSE');
  assert.match(unknown, /does not recognise/);
  assert.doesNotMatch(unknown, /SOMETHING_ELSE/);
  assert.equal(presentation.RANKED_RECOMMENDATION_BOUND, 3);
  assert.equal(presentation.operationsZoneLabel('Asia/Kolkata'), 'IST (Asia/Kolkata)');
  assert.equal(presentation.operationsZoneLabel('Europe/London'), 'Europe/London');
  assert.equal(presentation.groundExecutiveLabel(candidate({ groundExecutiveName: null })), 'GE user ID 4207');
});

/* ------------------------------------------------------------------ *
 * P1-4 — the review requirement is answered from the current click
 * ------------------------------------------------------------------ */

test('the first submission is judged from the current selection and text', () => {
  const view = presentation.toRecommendationView(recommendationView());
  const top = { candidate: view.candidates[0], source: 'recommended', evaluated: evaluated() };
  const other = {
    candidate: candidate({ groundExecutiveUserId: 5001, scheduledAt: '2027-02-14T09:00:00Z' }),
    source: 'search',
    evaluated: evaluated()
  };

  // First call with a blank reason (no prior render, so no stale "invalid" flag exists).
  const blank = presentation.validateSchedulingReview(view, other, '');
  assert.match(blank.errors.reason, /reason/);
  assert.equal(blank.reason, null);

  // Whitespace-only is not a reason.
  assert.match(presentation.validateSchedulingReview(view, other, '   ').errors.reason, /reason/);
  assert.match(presentation.validateSchedulingReview(view, other, '\n\t ').errors.reason, /reason/);

  // A valid reason is accepted and trimmed.
  assert.equal(
    presentation.validateSchedulingReview(view, other, '  Nearest to the tenant  ').reason,
    'Nearest to the tenant'
  );

  // The exact top suggestion needs no reason, and none is sent.
  assert.deepEqual(presentation.validateSchedulingReview(view, top, '').errors, {});
  assert.equal(presentation.validateSchedulingReview(view, top, '').reason, null);

  // Too long is refused with the length rule rather than a missing-value message.
  assert.match(presentation.validateSchedulingReview(view, other, 'x'.repeat(501)).errors.reason, /500/);

  // The command builder is the same decision, so a blank required reason can never produce a payload.
  assert.equal(presentation.scheduleSelectionCommand(presentation.toSessionDetail(sessionView()), other, view, '').command, null);
  assert.equal(presentation.scheduleSelectionCommand(presentation.toSessionDetail(sessionView()), top, view, '').command.overrideReason, null);
});
