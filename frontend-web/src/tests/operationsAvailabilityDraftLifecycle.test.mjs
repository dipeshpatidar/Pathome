/**
 * P2 — availability draft preservation across unsuccessful saves, refreshes and context changes.
 *
 * Two real production surfaces are exercised:
 *  - the actual `OperationsDraftItinerary` parent and `AvailabilityEditor` bodies, driven by a
 *    workspace-shaped controlled availability object;
 *  - the actual `saveAvailability` callback extracted from `OperationsWorkspace.tsx`, with a
 *    controlled guarded-command runner and an authoritative-read sequence.
 *
 * Browser mounting, real HTTP and physical-device verification remain deferred; these are focused
 * handler-level tests, exactly as labelled in each test name.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { installOperationsActionHarness } from './support/operationsActionHarness.mjs';
import {
  createItineraryHarness,
  ITINERARY_CLOCK
} from './support/availabilityDraftItineraryHarness.mjs';
import { createAvailabilitySaveHarness } from './support/availabilityDraftSaveHarness.mjs';

const h = await installOperationsActionHarness();
const { presentation } = h;

const KOLKATA = 'Asia/Kolkata';
const NEW_YORK = 'America/New_York';

const item = (overrides = {}) => ({
  itemId: 91,
  listingId: 8001,
  title: '2 BHK apartment',
  sector: 'Vijay Nagar',
  position: 1,
  sourceRequestId: 501,
  derivedFromRequestId: null,
  origin: 'TENANT_REQUESTED',
  confirmationStatus: 'CONFIRMED',
  availabilityConfirmedAt: null,
  removedAt: null,
  removalReason: null,
  propertyAvailabilityStartAt: '2026-10-22T03:17:00Z',
  propertyAvailabilityEndAt: '2026-10-30T07:17:00Z',
  propertyAvailabilityZoneId: KOLKATA,
  propertyAvailabilitySource: 'PHONE',
  ...overrides
});

const sessionView = (items, overrides = {}) => ({
  sessionId: 3108,
  status: 'DRAFT',
  version: 6,
  city: 'Indore',
  items,
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

const detailFor = (items = [item()], overrides = {}) =>
  presentation.toSessionDetail(sessionView(items, overrides));

/** One response item mapped through the production detail mapper (command-side field names). */
const mappedItem = (overrides = {}) => detailFor([item(overrides)]).items[0];

const draftFor = (detail, overrides = {}) => presentation.createAvailabilityDraft({
  sessionId: detail.sessionId,
  sessionVersion: detail.version,
  item: detail.items[0],
  identity: '41|EMPLOYEE|deadbeef',
  authorizationGeneration: 1,
  editorGeneration: 1,
  ...overrides
});

const WINDOW = {
  availabilityStartAt: '2026-10-23T18:17:00+05:30',
  availabilityEndAt: '2026-10-30T12:47:00+05:30',
  availabilityZoneId: KOLKATA,
  availabilitySource: 'PHONE'
};

/** A draft that has already received the OE's edited start and an explicit EDITED provenance. */
const editedDraft = (detail, overrides = {}) => ({
  ...draftFor(detail, overrides),
  window: {
    availabilityStartAt: '2026-10-23T18:17',
    availabilityEndAt: '2026-10-30T12:47',
    availabilityZoneId: KOLKATA,
    availabilitySource: 'PHONE'
  },
  fieldProvenance: { start: 'EDITED', end: 'RECORDED' }
});

const attemptFor = (window = WINDOW, expectedSessionVersion = 6) => ({
  choice: 'window',
  itemId: 91,
  expectedSessionVersion,
  window
});

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

test('T1 availability validation uses one deterministic clock for past, equal and future boundaries', () => {
  const detail = detailFor();
  const at = (start) => presentation.setAvailabilityCommand(detail, 91, 'window', {
    ...WINDOW,
    availabilityStartAt: start
  }, ITINERARY_CLOCK);

  assert.equal(at('2026-09-30T23:59:59.999Z').errors.start,
    'A new window must start in the future.');
  assert.equal(at('2026-10-01T00:00:00.000Z').errors.start,
    'A new window must start in the future.',
  'the exact current instant is not future');
  assert.equal(at('2026-10-01T00:00:00.001Z').errors.start, undefined);
  assert.equal(at('2026-10-01T00:00:00.001Z').command?.availabilityStartAt,
    '2026-10-01T00:00:00.001Z');
});

test('T1 Asia/Kolkata wall time and precision remain exact at the command boundary', () => {
  assert.equal(
    presentation.zoneInputToServerInstant('2026-10-02T05:30', KOLKATA),
    '2026-10-02T05:30:00+05:30'
  );
  assert.equal(
    Date.parse(presentation.zoneInputToServerInstant('2026-10-02T05:30', KOLKATA)),
    Date.parse('2026-10-02T00:00:00Z')
  );
  const precise = '2026-10-02T05:30:00.123456+05:30';
  const result = presentation.setAvailabilityCommand(detailFor(), 91, 'window', {
    ...WINDOW,
    availabilityStartAt: precise,
    availabilityEndAt: '2026-10-03T05:30:00+05:30'
  }, ITINERARY_CLOCK);
  assert.equal(result.command?.availabilityStartAt, precise);
  assert.equal(result.command?.availabilityZoneId, KOLKATA);
  assert.equal(result.command?.availabilityEndAt, '2026-10-03T05:30:00+05:30');
});

/* ------------------------------------------------------------------ *
 * P2-01 — recoverable unsuccessful save
 * ------------------------------------------------------------------ */

test('P2-01 handler: the editor stays open with raw input and provenance while a save is pending and after it fails', async () => {
  let settle;
  const detail = detailFor();
  const host = createItineraryHarness(presentation, {
    session: detail,
    onSave: () => new Promise((resolve) => { settle = resolve; })
  });
  host.openEditor(91);
  host.type('start', '2026-10-23T18:17');
  host.zone(NEW_YORK);
  host.zone(KOLKATA);
  host.submit();

  let state = host.state();
  assert.equal(state.open, true, 'the editor must not close before the outcome is known');
  assert.equal(state.start, '2026-10-23T18:17');
  assert.equal(state.draft.fieldProvenance.start, 'EDITED');
  assert.equal(state.draft.fieldProvenance.end, 'RECORDED');
  assert.equal(state.draft.window.availabilityZoneId, KOLKATA);
  assert.equal(state.submitDisabled, true);
  assert.equal(host.saveCalls.length, 1);

  settle({ status: 'not-saved', message: 'The server refused the availability change.' });
  await flush();
  state = host.state();
  assert.equal(state.open, true, 'a definitive failure keeps the editor open');
  assert.equal(state.start, '2026-10-23T18:17', 'the typed value is recoverable');
  assert.equal(state.draft.fieldProvenance.start, 'EDITED');
  assert.equal(state.failureMessage, 'The server refused the availability change.');
  assert.equal(state.submitDisabled, false, 'a definitive refusal is safely retryable');
});

test('P2-01 save callback: a pre-dispatch failure retains the draft and sends nothing', async () => {
  const detail = detailFor();
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: detail,
    outcomes: [{
      status: 'failed',
      failure: {
        kind: 'pre-dispatch',
        status: null,
        message: 'The action stopped before any ownership command left the app.',
        detail: null,
        published: true
      }
    }]
  });
  harness.state.draft = draftFor(detail);

  const result = await harness.save(1, WINDOW);
  assert.equal(result.status, 'not-saved');
  assert.equal(harness.state.puts.length, 0, 'nothing was transmitted');
  assert.equal(harness.draft().status, 'refused');
  assert.equal(harness.draft().window.availabilityStartAt, '2026-10-22T08:47');
  assert.match(harness.draft().failureMessage, /stopped before/);
});

test('P2-01 negative control: the superseded close-before-save parent loses the draft', async () => {
  const host = createItineraryHarness(presentation, {
    session: detailFor(),
    legacyCloseBeforeSave: true,
    onSave: () => new Promise(() => {})
  });
  host.openEditor(91);
  host.type('start', '2026-10-23T18:17');
  host.submit();
  await flush();
  assert.equal(host.hasEditor(), false);
  assert.equal(host.internal.draft, null, 'the old behavior destroyed the input before the outcome');
});

/* ------------------------------------------------------------------ *
 * P2-02 — pending and duplicate submission
 * ------------------------------------------------------------------ */

test('P2-02 handler: repeated Save while pending dispatches once and keeps the draft', async () => {
  const host = createItineraryHarness(presentation, {
    session: detailFor(),
    onSave: () => new Promise(() => {})
  });
  host.openEditor(91);
  host.type('start', '2026-10-23T18:17');
  host.submit();
  host.submit();
  host.submit();
  const state = host.state();
  assert.equal(host.saveCalls.length, 1, 'the existing submission lock admits one command');
  assert.equal(state.draft.status, 'saving');
  assert.equal(state.draft.window.availabilityStartAt, '2026-10-23T18:17');
  assert.equal(state.submitDisabled, true);
});

test('P2-02 negative control: removing the pending lock would dispatch duplicates', async () => {
  const host = createItineraryHarness(presentation, {
    session: detailFor(),
    ignoreSavingStatus: true,
    onSave: () => new Promise(() => {})
  });
  host.openEditor(91);
  host.type('start', '2026-10-23T18:17');
  host.submit();
  host.submit();
  assert.equal(host.saveCalls.length, 2, 'the negative control proves the duplicate test discriminates');
});

test('P2-02 save callback: a guard-held command leaves the draft untouched and re-enables nothing', async () => {
  const detail = detailFor();
  const harness = createAvailabilitySaveHarness(presentation, { freshSession: detail });
  harness.state.draft = draftFor(detail);
  harness.state.locked = true;
  const result = await harness.save(1, WINDOW);
  assert.equal(result.status, 'unconfirmed');
  assert.equal(harness.state.puts.length, 0);
  assert.equal(harness.draft().status, 'saving');
});

/* ------------------------------------------------------------------ *
 * P2-03 — lost acknowledgement / uncertain outcome
 * ------------------------------------------------------------------ */

const uncertainFailure = {
  status: 'failed',
  failure: {
    kind: 'uncertain',
    status: null,
    message: 'The save was sent, but the response did not arrive, so it is not confirmed whether it was applied.',
    detail: null,
    published: true
  }
};

test('P2-03 save callback: an unconfirmed save is reconciled by a read, never resent', async () => {
  const before = detailFor();
  const different = detailFor([item({ propertyAvailabilityStartAt: '2026-10-24T03:17:00Z' })], { version: 7 });
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: before,
    detailResponses: [before, different],
    outcomes: [uncertainFailure]
  });
  harness.state.draft = editedDraft(before);
  const result = await harness.save(1, WINDOW);

  assert.equal(result.status, 'not-saved');
  assert.equal(harness.state.puts.length, 1, 'only the original command was transmitted');
  assert.equal(harness.state.gets.length, 2, 'one pre-command read and one reconciliation read');
  assert.equal(harness.draft().status, 'refused', 'review is required before a fenced old attempt can be replaced');
  assert.equal(harness.draft().attempt, null, 'the later version proves the old command can no longer commit');
  assert.doesNotMatch(harness.draft().failureMessage, /not applied/);
  assert.match(harness.draft().failureMessage, /not confirmed/);
  assert.equal(harness.state.outcomes.length, 0, 'no automatic resend was queued');
  assert.equal(harness.state.details.length, 1, 'the changed authoritative window is shown for review');
});

test('P2-03 save callback: a reconciliation that shows the intent clears the draft once', async () => {
  const before = detailFor();
  const applied = detailFor([
    item({
      propertyAvailabilityStartAt: '2026-10-23T12:47:00Z',
      propertyAvailabilityEndAt: '2026-10-30T07:17:00Z'
    })
  ], { version: 7 });
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: before,
    detailResponses: [before, applied],
    outcomes: [uncertainFailure]
  });
  harness.state.draft = editedDraft(before);
  const result = await harness.save(1, WINDOW);
  assert.equal(result.status, 'saved');
  assert.equal(harness.draft(), null);
  assert.equal(harness.state.puts.length, 1, 'the original command only');
  assert.equal(harness.state.successes.length, 1);
});

test('P2-03 save callback: a failed reconciliation keeps the input and states that it is unconfirmed', async () => {
  const before = detailFor();
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: before,
    detailResponses: [before, new Error('connection interrupted')],
    outcomes: [uncertainFailure]
  });
  harness.state.draft = editedDraft(before);
  const result = await harness.save(1, WINDOW);
  assert.equal(result.status, 'unconfirmed');
  assert.equal(harness.draft().status, 'uncertain');
  assert.match(harness.draft().failureMessage, /not confirmed/);
});

test('P2-03 save callback: pressing Save after an unknown outcome reconciles first and sends no second command', async () => {
  const before = detailFor();
  const applied = detailFor([
    item({
      propertyAvailabilityStartAt: '2026-10-23T12:47:00Z',
      propertyAvailabilityEndAt: '2026-10-30T07:17:00Z'
    })
  ], { version: 7 });
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: before,
    detailResponses: [applied],
    outcomes: [{ status: 'applied' }]
  });
  harness.state.draft = {
    ...editedDraft(before),
    attempt: {
      choice: 'window',
      itemId: 91,
      expectedSessionVersion: 6,
      window: WINDOW
    },
    status: 'uncertain',
    failureMessage: 'The save was sent, but the response did not arrive.'
  };
  const result = await harness.save(1, WINDOW);
  assert.equal(result.status, 'saved');
  assert.equal(harness.draft(), null);
  assert.equal(harness.state.puts.length, 0, 'no blind retry was sent');
  assert.equal(harness.state.outcomes.length, 1, 'the queued command was never built or dispatched');
});

/* ------------------------------------------------------------------ *
 * P2 correction — precision-safe reconciliation and visible preflight
 * ------------------------------------------------------------------ */

test('R1 precision: exact intent or its defined PostgreSQL microsecond representation is accepted; adjacent values are not', () => {
  const intended = '2026-10-22T03:17:00.123456789Z';
  assert.equal(presentation.availabilityInstantMatchesPersisted(
    '2026-10-22T03:17:00.123456789Z', intended
  ), true, 'the exact Java response is accepted');
  assert.equal(presentation.availabilityInstantMatchesPersisted(
    '2026-10-22T03:17:00.123457Z', intended
  ), true, 'the JDBC-rounded persisted value is accepted');
  assert.equal(presentation.availabilityInstantMatchesPersisted(
    '2026-10-22T03:17:00.123456Z', intended
  ), false, 'the neighbouring persisted microsecond is not the intent');
  assert.equal(presentation.availabilityInstantMatchesPersisted(
    '2026-10-22T03:17:00.123999Z', '2026-10-22T03:17:00.123456Z'
  ), false, '543 microseconds inside one millisecond remain distinguishable');

  // Negative control: the superseded comparison loses this discrimination.
  assert.equal(
    Date.parse('2026-10-22T03:17:00.123456Z') === Date.parse('2026-10-22T03:17:00.123999Z'),
    true,
    'Date.parse would falsely equate the two values'
  );
  assert.equal(presentation.compareOperationsInstants(
    '2026-10-22T03:17:00.123456Z', '2026-10-22T03:17:00.123999Z'
  ), -1);
});

test('R1 precision: half-up microseconds carry across milliseconds, seconds, days and years', () => {
  const expected = [
    ['2026-10-22T03:17:00.123456499Z', '2026-10-22T03:17:00.123456000Z'],
    ['2026-10-22T03:17:00.123456500Z', '2026-10-22T03:17:00.123457000Z'],
    ['2026-10-22T03:17:00.123999600Z', '2026-10-22T03:17:00.124000000Z'],
    ['2026-12-31T23:59:59.999999600Z', '2027-01-01T00:00:00.000000000Z']
  ];
  for (const [input, canonical] of expected) {
    const rounded = presentation.roundOperationsInstantToPostgresMicroseconds(input);
    assert.notEqual(rounded, null);
    assert.equal(
      presentation.availabilityInstantMatchesPersisted(canonical, input),
      true,
      `${input} accepts its rounded representation ${canonical}`
    );
  }
  assert.equal(presentation.availabilityInstantMatchesPersisted(
    '2027-01-01T00:00:00Z', '2026-12-31T23:59:59.999999600Z'
  ), true);
  assert.equal(presentation.availabilityInstantMatchesPersisted(
    '2027-01-01T00:00:00.000001Z', '2026-12-31T23:59:59.999999600Z'
  ), false);
});

test('R1 save callback: a sub-millisecond mismatch cannot become a confirmed save or discard the draft', async () => {
  const before = detailFor([item({
    propertyAvailabilityStartAt: '2026-10-22T03:17:00.123456Z'
  })]);
  const different = detailFor([item({
    propertyAvailabilityStartAt: '2026-10-22T03:17:00.123999Z'
  })], { version: 7 });
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: before,
    detailResponses: [before, different],
    outcomes: [uncertainFailure]
  });
  const intended = {
    ...WINDOW,
    availabilityStartAt: '2026-10-23T08:47:00.123456+05:30'
  };
  harness.state.draft = editedDraft(before);
  const result = await harness.save(1, intended);

  assert.equal(result.status, 'not-saved');
  assert.notEqual(harness.draft(), null);
  assert.equal(harness.draft().status, 'refused');
  assert.equal(harness.state.successes.length, 0, 'no false success is announced');
  assert.equal(harness.state.puts.length, 1, 'the uncertain command is never resent');
  assert.equal(harness.state.details.length, 1, 'the authoritative difference is published for review');
});

test('R1 save callback: an applied nine-digit fraction rounded by persistence is recognised', async () => {
  const before = detailFor([item({
    propertyAvailabilityStartAt: '2026-10-22T03:17:00.123999600Z'
  })]);
  const stored = detailFor([item({
    propertyAvailabilityStartAt: '2026-10-23T03:17:00.124000Z',
    propertyAvailabilityEndAt: '2026-10-30T07:17:00Z'
  })], { version: 7 });
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: before,
    detailResponses: [before, stored],
    outcomes: [uncertainFailure]
  });
  const intended = {
    ...WINDOW,
    availabilityStartAt: '2026-10-23T08:47:00.123999600+05:30'
  };
  harness.state.draft = editedDraft(before);
  const result = await harness.save(1, intended);

  assert.equal(result.status, 'saved');
  assert.equal(harness.draft(), null);
  assert.equal(harness.state.puts.length, 1);
  assert.match(result.message, /there is nothing left to retry/);
  assert.doesNotMatch(result.message, /response|receipt|accepted/i, 'current state is not a historical receipt');
});

test('R1 reconciliation checks status, zone and source as well as instants', () => {
  const window = WINDOW;
  const confirmed = mappedItem({
    propertyAvailabilityStartAt: '2026-10-23T12:47:00Z',
    propertyAvailabilityEndAt: '2026-10-30T07:17:00Z'
  });
  assert.equal(presentation.availabilityItemSatisfiesDraft(confirmed, 'window', window), true);
  assert.equal(presentation.availabilityItemSatisfiesDraft(
    { ...confirmed, availabilityZoneId: NEW_YORK }, 'window', window
  ), false);
  assert.equal(presentation.availabilityItemSatisfiesDraft(
    { ...confirmed, availabilitySource: 'WHATSAPP' }, 'window', window
  ), false);
  assert.equal(presentation.availabilityItemSatisfiesDraft(
    { ...confirmed, confirmationStatus: 'PENDING' }, 'window', window
  ), false);
  assert.equal(presentation.availabilityItemSatisfiesDraft(
    { ...confirmed, availabilityStartAt: null }, 'window', window
  ), false);
  assert.equal(presentation.availabilityItemSatisfiesDraft(
    { ...confirmed, availabilityEndAt: 'not-an-instant' }, 'window', window
  ), false);
});

test('R2 same version: a mismatching read remains unconfirmed, keeps its attempt and never says “not applied”', async () => {
  const before = detailFor();
  const intended = {
    ...WINDOW,
    availabilityStartAt: '2026-10-23T18:18:00+05:30'
  };
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: before,
    detailResponses: [before, before],
    outcomes: [uncertainFailure]
  });
  harness.state.draft = editedDraft(before);
  const result = await harness.save(1, intended);

  assert.equal(result.status, 'unconfirmed');
  assert.equal(harness.draft().status, 'uncertain');
  assert.equal(harness.draft().attempt.window.availabilityStartAt, intended.availabilityStartAt);
  assert.equal(harness.draft().attempt.window.availabilityEndAt, intended.availabilityEndAt);
  assert.equal(harness.draft().attempt.window.availabilityZoneId, intended.availabilityZoneId);
  assert.equal(harness.draft().attempt.window.availabilitySource, intended.availabilitySource);
  assert.equal(harness.draft().attempt.expectedSessionVersion, 6);
  assert.match(result.message, /not confirmed/);
  assert.doesNotMatch(result.message, /not applied/);
  assert.equal(harness.state.puts.length, 1);
});

test('R2 repeated reconciliation is single-flight and sends no second command', async () => {
  const before = detailFor();
  const intended = {
    ...WINDOW,
    availabilityStartAt: '2026-10-23T18:18:00+05:30'
  };
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: before,
    detailResponses: [before, before],
    outcomes: [uncertainFailure]
  });
  harness.state.draft = editedDraft(before);
  assert.equal((await harness.save(1, intended)).status, 'unconfirmed');
  assert.equal((await harness.save(1, intended)).status, 'unconfirmed');

  assert.equal(harness.state.puts.length, 1, 'a second explicit check still never retransmits');
  assert.equal(harness.state.outcomes.length, 0);
  assert.equal(harness.draft().status, 'uncertain');
  assert.notEqual(harness.draft().attempt, null);
});

test('R2 higher version fences the old attempt; the next explicit Save is a new reviewed command', async () => {
  const before = detailFor();
  const changed = detailFor([item({
    propertyAvailabilityStartAt: '2026-10-24T03:17:00Z'
  })], { version: 7 });
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: before,
    appliedSession: changed,
    detailResponses: [before, changed],
    outcomes: [uncertainFailure, { status: 'applied' }]
  });
  harness.state.draft = editedDraft(before);
  const first = await harness.save(1, WINDOW);
  assert.equal(first.status, 'not-saved');
  assert.equal(harness.draft().attempt, null);
  assert.equal(harness.state.puts.length, 1);

  harness.state.freshSession = changed;
  const second = await harness.save(1, WINDOW);
  assert.equal(second.status, 'saved');
  assert.equal(harness.state.puts.length, 2, 'only the deliberate second Save dispatches');
  assert.equal(harness.state.puts[1].body.expectedSessionVersion, 7);
  assert.equal(harness.draft(), null);
});

test('R2 newer edits survive a matching reconciliation of the original attempt', async () => {
  const before = detailFor();
  const original = {
    ...WINDOW,
    availabilityStartAt: '2026-10-23T18:17:00+05:30'
  };
  const stored = detailFor([item({
    propertyAvailabilityStartAt: '2026-10-23T12:47:00Z',
    propertyAvailabilityEndAt: '2026-10-30T07:17:00Z'
  })], { version: 7 });
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: before,
    detailResponses: [stored],
    outcomes: []
  });
  harness.state.draft = {
    ...editedDraft(before),
    attempt: attemptFor(original),
    status: 'uncertain',
    window: {
      ...editedDraft(before).window,
      availabilityStartAt: '2026-10-25T18:17'
    }
  };
  const newer = {
    ...original,
    availabilityStartAt: '2026-10-25T18:17:00+05:30'
  };
  const result = await harness.save(1, newer);

  assert.equal(result.status, 'not-saved');
  assert.notEqual(harness.draft(), null);
  assert.equal(harness.draft().window.availabilityStartAt, '2026-10-25T18:17');
  assert.equal(harness.draft().attempt, null, 'the satisfied old attempt no longer blocks the newer edit');
  assert.match(result.message, /newer edits have not been saved/);
  assert.equal(harness.state.puts.length, 0);
  assert.equal(harness.state.successes.length, 0);
});

test('R2 reconciliation is generation-scoped and cannot publish into a newer editor', async () => {
  const before = detailFor();
  let release;
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: before,
    detailResponses: [() => new Promise((resolve) => { release = resolve; })],
    outcomes: []
  });
  harness.state.draft = {
    ...editedDraft(before),
    attempt: attemptFor(WINDOW),
    status: 'uncertain'
  };
  const pending = harness.save(1, WINDOW);
  await flush();
  const newer = {
    ...draftFor(before, { editorGeneration: 2 }),
    itemId: 92,
    window: {
      availabilityStartAt: '2026-10-26T18:17',
      availabilityEndAt: '2026-10-27T18:17',
      availabilityZoneId: KOLKATA,
      availabilitySource: 'PHONE'
    }
  };
  harness.state.draft = newer;
  release(before);
  const result = await pending;

  assert.equal(result.status, 'unconfirmed');
  assert.equal(harness.draft().editorGeneration, 2);
  assert.equal(harness.draft().itemId, 92);
  assert.equal(harness.state.details.length, 0, 'an old reconciliation never publishes into editor B');
  assert.equal(harness.state.successes.length, 0);
});

test('R2 reconciliation: a failed or null read keeps uncertainty; a lifecycle change publishes and invalidates', async () => {
  const before = detailFor();
  const nullRead = createAvailabilitySaveHarness(presentation, {
    freshSession: before,
    detailResponses: [before, null],
    outcomes: [uncertainFailure]
  });
  nullRead.state.draft = editedDraft(before);
  const nullResult = await nullRead.save(1, WINDOW);
  assert.equal(nullResult.status, 'unconfirmed');
  assert.equal(nullRead.draft().status, 'uncertain');
  assert.notEqual(nullRead.draft().attempt, null);
  assert.equal(nullRead.state.puts.length, 1);

  const scheduled = detailFor([item()], { status: 'SCHEDULED', version: 7 });
  const lifecycle = createAvailabilitySaveHarness(presentation, {
    freshSession: before,
    detailResponses: [before, scheduled],
    outcomes: [uncertainFailure]
  });
  lifecycle.state.draft = editedDraft(before);
  const lifecycleResult = await lifecycle.save(1, WINDOW);
  assert.equal(lifecycleResult.status, 'superseded');
  assert.equal(lifecycle.draft(), null);
  assert.equal(lifecycle.state.puts.length, 1);
  assert.equal(lifecycle.state.details.length, 1);
  assert.match(lifecycle.state.notices[0], /now SCHEDULED/);
});

test('R3 preflight: a newly discovered window change is published and stops before any write', async () => {
  const before = detailFor();
  const changed = detailFor([item({
    propertyAvailabilityStartAt: '2026-10-24T03:17:00Z'
  })], { version: 7 });
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: changed,
    presentedSession: before,
    appliedSession: changed,
    outcomes: [{ status: 'applied' }]
  });
  harness.state.draft = editedDraft(before);
  const result = await harness.save(1, WINDOW);

  assert.equal(result.status, 'not-saved');
  assert.equal(harness.state.puts.length, 0, 'nothing overwrites the unseen change');
  assert.equal(harness.state.details.length, 1, 'the fresh authoritative window is shown');
  assert.equal(harness.state.outcomes.length, 1, 'the stopped submission consumed no command');
  assert.equal(harness.draft().openingStartAt, before.items[0].availabilityStartAt);
  assert.equal(harness.draft().status, 'refused');
  assert.match(harness.draft().failureMessage, /before your save was sent/);

  const second = await harness.save(1, WINDOW);
  assert.equal(second.status, 'saved', 'a later explicit Save may overwrite the now-visible window');
  assert.equal(harness.state.puts.length, 1);
  assert.equal(harness.state.puts[0].body.expectedSessionVersion, 7);
  assert.equal(harness.draft(), null);
});

test('R3 preflight: every newly discovered intervening change must be reviewed again', async () => {
  const before = detailFor();
  const firstChange = detailFor([item({
    propertyAvailabilityStartAt: '2026-10-24T03:17:00Z'
  })], { version: 7 });
  const secondChange = detailFor([item({
    propertyAvailabilityStartAt: '2026-10-25T03:17:00Z'
  })], { version: 8 });
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: firstChange,
    presentedSession: before,
    appliedSession: secondChange,
    outcomes: [{ status: 'applied' }]
  });
  harness.state.draft = editedDraft(before);

  assert.equal((await harness.save(1, WINDOW)).status, 'not-saved');
  assert.equal(harness.state.puts.length, 0);
  harness.state.freshSession = secondChange;
  assert.equal((await harness.save(1, WINDOW)).status, 'not-saved');
  assert.equal(harness.state.puts.length, 0, 'the second unseen change is also reviewed first');
  assert.equal(harness.state.details.length, 2);

  const result = await harness.save(1, WINDOW);
  assert.equal(result.status, 'saved');
  assert.equal(harness.state.puts.length, 1);
  assert.equal(harness.state.puts[0].body.expectedSessionVersion, 8);
});

test('R3 preflight: an unrelated version change with the same availability still saves once', async () => {
  const before = detailFor();
  const versionOnly = detailFor([item()], { version: 7 });
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: versionOnly,
    appliedSession: versionOnly,
    outcomes: [{ status: 'applied' }]
  });
  harness.state.draft = editedDraft(before);
  const result = await harness.save(1, WINDOW);

  assert.equal(result.status, 'saved');
  assert.equal(harness.state.puts.length, 1);
  assert.equal(harness.state.puts[0].body.expectedSessionVersion, 7);
});

test('R3b preflight: a versioned non-DRAFT lifecycle is reported accurately and invalidates the editor', async () => {
  const before = detailFor();
  const scheduled = detailFor([item()], { status: 'SCHEDULED', version: 7 });
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: scheduled,
    presentedSession: before,
    outcomes: []
  });
  harness.state.draft = editedDraft(before);
  const result = await harness.save(1, WINDOW);

  assert.equal(result.status, 'not-saved');
  assert.equal(harness.draft(), null, 'an inapplicable lifecycle draft is not retained');
  assert.equal(harness.state.puts.length, 0);
  assert.equal(harness.state.details.length, 1);
  assert.match(result.message, /now SCHEDULED/);
  assert.doesNotMatch(result.message, /didn’t return the version/);
  assert.equal(harness.state.notices.length, 1);
});

test('R3 preflight: a wrong Session identity and a no-longer-offered choice close without a command', async () => {
  const before = detailFor();
  const wrong = detailFor([item()], { sessionId: 9999, version: 7 });
  const wrongHarness = createAvailabilitySaveHarness(presentation, {
    freshSession: wrong,
    presentedSession: before,
    outcomes: []
  });
  wrongHarness.state.draft = editedDraft(before);
  const wrongResult = await wrongHarness.save(1, WINDOW);
  assert.equal(wrongResult.status, 'not-saved');
  assert.equal(wrongHarness.draft(), null);
  assert.equal(wrongHarness.state.puts.length, 0);
  assert.equal(wrongHarness.state.details.length, 0);
  assert.match(wrongHarness.state.notices[0], /different session/);

  const pending = detailFor([item({
    confirmationStatus: 'PENDING',
    propertyAvailabilityStartAt: null,
    propertyAvailabilityEndAt: null,
    propertyAvailabilityZoneId: null,
    propertyAvailabilitySource: null
  })]);
  const unavailable = detailFor([item({ confirmationStatus: 'UNAVAILABLE' })], { version: 7 });
  const choiceHarness = createAvailabilitySaveHarness(presentation, {
    freshSession: unavailable,
    presentedSession: pending,
    outcomes: []
  });
  choiceHarness.state.draft = draftFor(pending);
  const choiceResult = await choiceHarness.save(1, WINDOW);
  assert.equal(choiceResult.status, 'not-saved');
  assert.equal(choiceHarness.draft(), null);
  assert.equal(choiceHarness.state.puts.length, 0);
  assert.ok(choiceHarness.state.details.length > 0);
});

test('R3b preflight: removed and no-longer-offered items invalidate the draft; a missing version does not', async () => {
  const before = detailFor();
  const removed = detailFor([item({
    removedAt: '2026-10-17T10:00:00Z',
    removalReason: 'Lessor withdrew'
  })], { version: 7 });
  const removedHarness = createAvailabilitySaveHarness(presentation, {
    freshSession: removed,
    presentedSession: before,
    outcomes: []
  });
  removedHarness.state.draft = editedDraft(before);
  const removedResult = await removedHarness.save(1, WINDOW);
  assert.equal(removedResult.status, 'not-saved');
  assert.equal(removedHarness.draft(), null);
  assert.equal(removedHarness.state.puts.length, 0);
  assert.match(removedResult.message, /no longer editable/);

  const noVersion = detailFor([item()], { version: null });
  const versionHarness = createAvailabilitySaveHarness(presentation, {
    freshSession: noVersion,
    presentedSession: before,
    outcomes: [{ status: 'applied' }]
  });
  versionHarness.state.draft = editedDraft(before);
  const versionResult = await versionHarness.save(1, WINDOW);
  assert.equal(versionResult.status, 'not-saved');
  assert.notEqual(versionHarness.draft(), null);
  assert.equal(versionHarness.draft().status, 'refused');
  assert.equal(versionHarness.state.puts.length, 0);
  assert.match(versionResult.message, /version needed/);
});

test('R3 concurrency: a held preflight cannot publish into editor B after it opens', async () => {
  const before = detailFor();
  let release;
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: () => new Promise((resolve) => { release = resolve; }),
    presentedSession: before,
    outcomes: [{ status: 'applied' }]
  });
  harness.state.draft = editedDraft(before);
  const pending = harness.save(1, WINDOW);
  await flush();
  harness.state.draft = {
    ...draftFor(before, { editorGeneration: 2 }),
    itemId: 92
  };
  release(before);
  const result = await pending;

  assert.equal(result.status, 'superseded');
  assert.equal(harness.state.details.length, 0);
  assert.equal(harness.draft().editorGeneration, 2);
  assert.equal(harness.state.puts.length, 0);
  assert.equal(harness.state.outcomes.length, 1, 'the held preflight consumed no command');
});

test('R2/R3 negative controls: immutable attempt and exact preflight comparison discriminate the old behaviour', async () => {
  const legacyDecision = (different) => different ? 'not-applied' : 'applied';
  assert.equal(legacyDecision(true), 'not-applied', 'the superseded mismatch inference is exposed');

  const before = detailFor();
  const changed = detailFor([item({
    propertyAvailabilityStartAt: '2026-10-22T03:17:00.4Z'
  })], { version: 7 });
  assert.equal(
    presentation.availabilityEvidenceChanged(
      presentation.availabilityEvidenceSnapshot(before.items[0]), changed.items[0]
    ),
    true,
    'changed state is shown before a command may transmit'
  );
  assert.equal(
    presentation.availabilityEvidenceChanged(
      presentation.availabilityEvidenceSnapshot(changed.items[0]), changed.items[0]
    ),
    false,
    'the newly reviewed snapshot is the baseline for the next explicit Save'
  );
});

/* ------------------------------------------------------------------ *
 * P2-04 — successful save followed by failed refresh
 * ------------------------------------------------------------------ */

test('P2-04 save callback: save success clears the draft even when the queue refresh fails', async () => {
  const detail = detailFor();
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: detail,
    appliedSession: detail,
    outcomes: [{ status: 'applied', reconciliation: 'failed' }]
  });
  harness.state.draft = draftFor(detail);
  const result = await harness.save(1, WINDOW);
  assert.equal(result.status, 'saved');
  assert.equal(harness.draft(), null);
  assert.equal(harness.state.puts.length, 1);
  assert.equal(harness.state.successes.length, 1, 'the save is reported as successful');
  assert.equal(harness.state.notices.length, 1, 'the refresh failure is reported separately');
  assert.match(harness.state.notices[0], /could not be refreshed/);
});

/* ------------------------------------------------------------------ *
 * P2-05 — same-context authoritative refresh
 * ------------------------------------------------------------------ */

test('P2-05 handler: a same-context refresh cannot rebase or discard the draft', () => {
  const first = detailFor();
  const host = createItineraryHarness(presentation, { session: first });
  host.openEditor(91);
  host.type('start', '2026-10-23T18:17');
  host.zone(NEW_YORK);
  host.refreshSession(detailFor([item()], { version: 7 }));

  let state = host.state();
  assert.equal(state.open, true);
  assert.equal(state.start, '2026-10-23T18:17');
  assert.equal(state.zone, NEW_YORK);
  assert.equal(state.draft.fieldProvenance.start, 'EDITED');
  assert.equal(state.draft.openingStartAt, '2026-10-22T03:17:00Z');

  host.refreshSession(detailFor([
    item({ propertyAvailabilityStartAt: '2026-10-22T04:17:00Z' })
  ], { version: 8 }));
  state = host.state();
  assert.equal(state.start, '2026-10-23T18:17', 'the refresh never rewrites unsaved text');
  assert.equal(state.draft.openingStartAt, '2026-10-22T03:17:00Z', 'RECORDED keeps its opening instant');
  assert.ok(state.statusNotes.some((note) => String(note).includes('changed on the server')));
});

test('P2-05 negative control: the old refresh reset drops the draft', () => {
  const host = createItineraryHarness(presentation, {
    session: detailFor(),
    legacyResetOnRefresh: true
  });
  host.openEditor(91);
  host.type('start', '2026-10-23T18:17');
  host.refreshSession(detailFor([item()], { version: 7 }));
  assert.equal(host.hasEditor(), false);
  assert.equal(host.internal.draft, null);
});

/* ------------------------------------------------------------------ *
 * P2-06 — explicit cancellation and reopen
 * ------------------------------------------------------------------ */

test('P2-06 handler: Cancel discards the edit and a reopen starts from the authoritative recorded value', () => {
  const host = createItineraryHarness(presentation, { session: detailFor() });
  host.openEditor(91);
  host.type('start', '2026-10-23T18:17');
  host.cancel();
  assert.equal(host.hasEditor(), false);
  assert.equal(host.internal.draft, null);
  assert.equal(host.discards.length, 1);

  host.openEditor(91);
  const state = host.state();
  assert.equal(state.start, '2026-10-22T08:47');
  assert.equal(state.draft.fieldProvenance.start, 'RECORDED');
});

test('P2-06 negative control: a cancel that leaves the draft would resurrect the edit', () => {
  const host = createItineraryHarness(presentation, {
    session: detailFor(),
    legacyCancelKeepsDraft: true
  });
  host.openEditor(91);
  host.type('start', '2026-10-23T18:17');
  host.cancel();
  assert.equal(host.state().start, '2026-10-23T18:17');
});

/* ------------------------------------------------------------------ *
 * P2-07 — item and Session isolation
 * ------------------------------------------------------------------ */

test('P2-07 handler: opening another stop or Session never shows the first stop’s draft', () => {
  const detail = detailFor([
    item({ itemId: 91 }),
    item({
      itemId: 92,
      listingId: 8002,
      title: '3 BHK apartment',
      sourceRequestId: null,
      derivedFromRequestId: 501,
      origin: 'OE_ADDED',
      propertyAvailabilityStartAt: '2026-10-23T03:17:00Z'
    })
  ]);
  const host = createItineraryHarness(presentation, { session: detail });
  host.openEditor(91);
  host.type('start', '2026-10-23T18:17');
  host.openEditor(92);
  assert.equal(host.state().draft.itemId, 92);
  assert.equal(host.state().start, '2026-10-23T08:47');

  const other = detailFor([item({ itemId: 93 })], { sessionId: 9999 });
  host.setSession(other);
  assert.equal(host.hasEditor(), false);
  assert.equal(host.internal.draft, null);
});

test('P2-07 save callback: a late completion cannot clear a newer editor generation', async () => {
  const detail = detailFor();
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: detail,
    outcomes: ['deferred']
  });
  harness.state.draft = draftFor(detail, { editorGeneration: 1 });
  const pending = harness.save(1, WINDOW);
  await flush();

  harness.state.draft = draftFor(detail, {
    editorGeneration: 2,
    identity: '41|EMPLOYEE|deadbeef'
  });
  harness.resolveCommand({
    status: 'applied',
    applied: detail,
    reconciliation: 'loaded',
    failure: null,
    operation: {},
    published: true
  });
  const result = await pending;
  assert.equal(result.status, 'superseded');
  assert.equal(harness.draft().editorGeneration, 2);
  assert.equal(harness.draft().window.availabilityStartAt, '2026-10-22T08:47');
});

test('P2-07 negative control: dropping the generation comparison lets the late completion clear the new draft', async () => {
  const detail = detailFor();
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: detail,
    outcomes: ['deferred'],
    ignoreGeneration: true
  });
  harness.state.draft = draftFor(detail, { editorGeneration: 1 });
  const pending = harness.save(1, WINDOW);
  await flush();
  harness.state.draft = draftFor(detail, { editorGeneration: 2 });
  harness.resolveCommand({
    status: 'applied',
    applied: detail,
    reconciliation: 'loaded',
    failure: null,
    operation: {},
    published: true
  });
  await pending;
  assert.equal(harness.draft(), null, 'the negative control proves the generation assertion discriminates');
});

/* ------------------------------------------------------------------ *
 * P2-08 — identity and authority invalidation
 * ------------------------------------------------------------------ */

test('P2-08 save callback: a signed-out or authority-lost context invalidates the draft and sends nothing', async () => {
  const detail = detailFor();
  const signedOut = createAvailabilitySaveHarness(presentation, { freshSession: detail });
  signedOut.state.draft = draftFor(detail);
  signedOut.state.active = false;
  const result = await signedOut.save(1, WINDOW);
  assert.equal(result.status, 'superseded');
  assert.equal(signedOut.draft(), null);
  assert.equal(signedOut.state.puts.length, 0);

  const authorityLost = createAvailabilitySaveHarness(presentation, { freshSession: detail });
  authorityLost.state.draft = draftFor(detail);
  authorityLost.state.authorizationGeneration = 2;
  const second = await authorityLost.save(1, WINDOW);
  assert.equal(second.status, 'superseded');
  assert.equal(authorityLost.draft(), null);
  assert.equal(authorityLost.state.puts.length, 0);
});

test('P2-08 save callback: a late success after invalidation cannot recreate the draft', async () => {
  const detail = detailFor();
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: detail,
    outcomes: ['deferred']
  });
  harness.state.draft = draftFor(detail);
  const pending = harness.save(1, WINDOW);
  await flush();
  harness.state.active = false;
  harness.state.draft = null;
  harness.resolveCommand({
    status: 'applied',
    applied: detail,
    reconciliation: 'loaded',
    failure: null,
    operation: {},
    published: true
  });
  const result = await pending;
  assert.equal(result.status, 'superseded');
  assert.equal(harness.draft(), null);
  assert.equal(harness.state.successes.length, 0);
});

test('P2-08 negative control: omitting the logout invalidation would leave the protected draft in memory', async () => {
  const detail = detailFor();
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: detail,
    legacyKeepDraftOnInvalidation: true
  });
  harness.state.draft = draftFor(detail);
  harness.state.active = false;
  const result = await harness.save(1, WINDOW);
  assert.equal(result.status, 'superseded');
  assert.notEqual(harness.draft(), null, 'the control shows the leak the production assertion rejects');
});

/* ------------------------------------------------------------------ *
 * P2-09 — stale version and lifecycle refusal
 * ------------------------------------------------------------------ */

test('P2-09 save callback: a definitive conflict keeps the draft and surfaces the refusal', async () => {
  const detail = detailFor();
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: detail,
    outcomes: [{
      status: 'failed',
      failure: {
        kind: 'rejected',
        status: 409,
        message: 'The session changed on the server while you were working.',
        detail: null,
        published: true
      }
    }]
  });
  harness.state.draft = draftFor(detail);
  const result = await harness.save(1, WINDOW);
  assert.equal(result.status, 'not-saved');
  assert.equal(harness.state.puts.length, 1);
  assert.equal(harness.draft().status, 'refused');
  assert.match(harness.draft().failureMessage, /changed on the server/);
});

test('P2-09 save callback: a denied root is invalidated rather than left submit-ready', async () => {
  const detail = detailFor();
  const harness = createAvailabilitySaveHarness(presentation, {
    freshSession: detail,
    outcomes: [{
      status: 'failed',
      failure: {
        kind: 'rejected',
        status: 403,
        message: 'Your coordination authority is not active.',
        detail: null,
        published: false
      }
    }]
  });
  harness.state.draft = draftFor(detail);
  const result = await harness.save(1, WINDOW);
  assert.equal(result.status, 'superseded');
  assert.equal(harness.draft(), null);
});

test('P2-09 handler: a lifecycle or item change invalidates the draft', () => {
  const host = createItineraryHarness(presentation, { session: detailFor() });
  host.openEditor(91);
  host.type('start', '2026-10-23T18:17');
  host.setSession(detailFor([item()], { status: 'SCHEDULED', version: 7 }));
  assert.equal(host.hasEditor(), false);
  assert.equal(host.internal.draft, null);

  const removed = createItineraryHarness(presentation, { session: detailFor() });
  removed.openEditor(91);
  removed.type('start', '2026-10-23T18:17');
  removed.setSession(detailFor([
    item({ removedAt: '2026-10-17T10:00:00Z', removalReason: 'Lessor withdrew' })
  ], { version: 7 }));
  assert.equal(removed.hasEditor(), false);
  assert.equal(removed.internal.draft, null);
});

/* ------------------------------------------------------------------ *
 * P2-10 — F1 provenance and time correctness after a failed save
 * ------------------------------------------------------------------ */

test('P2-10 handler: a failed save keeps the edit, its provenance and its exact recorded end', async () => {
  const recorded = item({
    propertyAvailabilityStartAt: '2026-10-22T12:47:37.123456789Z',
    propertyAvailabilityEndAt: '2026-10-30T12:47:00.5Z',
    propertyAvailabilityZoneId: NEW_YORK
  });
  const detail = detailFor([recorded]);
  let settle;
  const host = createItineraryHarness(presentation, {
    session: detail,
    onSave: () => new Promise((resolve) => { settle = resolve; })
  });
  host.openEditor(91);
  host.type('start', '2026-10-23T18:17');
  host.zone(KOLKATA);
  host.submit();
  settle({ status: 'not-saved', message: 'The server refused the update.' });
  await flush();

  const state = host.state();
  assert.equal(state.start, '2026-10-23T18:17', 'the edit is not replaced by the recorded display');
  assert.equal(state.zone, KOLKATA);
  assert.equal(state.draft.fieldProvenance.start, 'EDITED');
  assert.equal(state.draft.fieldProvenance.end, 'RECORDED');

  const start = presentation.resolveAvailabilityFieldInstant({
    value: state.draft.window.availabilityStartAt,
    provenance: state.draft.fieldProvenance.start,
    zoneId: state.draft.window.availabilityZoneId,
    initialInstant: state.draft.openingStartAt
  });
  const end = presentation.resolveAvailabilityFieldInstant({
    value: state.draft.window.availabilityEndAt,
    provenance: state.draft.fieldProvenance.end,
    zoneId: state.draft.window.availabilityZoneId,
    initialInstant: state.draft.openingEndAt
  });
  assert.equal(start.value, '2026-10-23T18:17:00+05:30');
  assert.equal(Date.parse(start.value), Date.parse('2026-10-23T12:47:00Z'));
  assert.equal(end.value, '2026-10-30T18:17:00.5+05:30');
  assert.equal(Date.parse(end.value), Date.parse('2026-10-30T12:47:00.5Z'));
});

test('P2-10 handler: after a refused save, a DST gap is still refused before any second dispatch', async () => {
  const detail = detailFor([item({ propertyAvailabilityZoneId: NEW_YORK })]);
  let settle;
  const host = createItineraryHarness(presentation, {
    session: detail,
    onSave: () => new Promise((resolve) => { settle = resolve; })
  });
  host.openEditor(91);
  host.type('start', '2026-10-23T18:17');
  host.submit();
  settle({ status: 'not-saved', message: 'Refused.' });
  await flush();

  host.zone(NEW_YORK);
  host.type('start', '2027-03-14T02:30');
  host.submit();
  assert.equal(host.saveCalls.length, 1, 'the invalid wall clock never reaches the save callback');
  assert.equal(host.state().draft.window.availabilityStartAt, '2027-03-14T02:30');
  assert.equal(host.state().draft.fieldProvenance.start, 'EDITED');
  assert.match(host.state().zoneError ?? '', /doesn’t exist/);
});

/* ------------------------------------------------------------------ *
 * Additional neighboring checks
 * ------------------------------------------------------------------ */

test('P2 additional: clearing a field after a refused save does not dispatch and keeps the draft', async () => {
  let settle;
  const host = createItineraryHarness(presentation, {
    session: detailFor(),
    onSave: () => new Promise((resolve) => { settle = resolve; })
  });
  host.openEditor(91);
  host.type('start', '2026-10-23T18:17');
  host.submit();
  settle({ status: 'not-saved', message: 'Refused.' });
  await flush();
  const calls = host.saveCalls.length;

  host.type('start', '');
  host.submit();
  assert.equal(host.saveCalls.length, calls, 'a blank required field is refused before dispatch');
  assert.equal(host.state().start, '');
  assert.equal(host.internal.draft.fieldProvenance.start, 'EDITED');
});

test('P2 additional: consecutive edits after a refusal dispatch only after the next explicit Save', async () => {
  let settle;
  const host = createItineraryHarness(presentation, {
    session: detailFor(),
    onSave: () => new Promise((resolve) => { settle = resolve; })
  });
  host.openEditor(91);
  host.type('start', '2026-10-23T18:17');
  host.submit();
  settle({ status: 'not-saved', message: 'Refused.' });
  await flush();
  assert.equal(host.saveCalls.length, 1);

  host.type('start', '2026-10-23T19:17');
  assert.equal(host.saveCalls.length, 1, 'an edit alone never retransmits');
  host.submit();
  assert.equal(host.saveCalls.length, 2);
});

test('P2 additional: the bounded draft keeps one record, never an accumulation', () => {
  const detail = detailFor([item({ itemId: 91 }), item({ itemId: 92, sourceRequestId: null })]);
  const host = createItineraryHarness(presentation, { session: detail });
  host.openEditor(91);
  host.type('start', '2026-10-23T18:17');
  host.openEditor(92);
  assert.equal(host.state().draft.itemId, 92);
  assert.equal(host.state().draft.window.availabilityStartAt, '2026-10-22T08:47');
});

/* ------------------------------------------------------------------ *
 * Draft model and workspace wiring
 * ------------------------------------------------------------------ */

test('P2 draft model: the opening recorded snapshot and explicit provenance are captured together', () => {
  const detail = detailFor();
  const draft = presentation.createAvailabilityDraft({
    sessionId: detail.sessionId,
    sessionVersion: detail.version,
    item: detail.items[0],
    identity: '41|EMPLOYEE|deadbeef',
    authorizationGeneration: 3,
    editorGeneration: 7
  });
  assert.deepEqual(draft.fieldProvenance, { start: 'RECORDED', end: 'RECORDED' });
  assert.equal(draft.openingStartAt, '2026-10-22T03:17:00Z');
  assert.equal(draft.openingEndAt, '2026-10-30T07:17:00Z');
  assert.equal(draft.window.availabilityStartAt, '2026-10-22T08:47');
  assert.equal(draft.window.availabilityZoneId, KOLKATA);
  assert.equal(draft.identity, '41|EMPLOYEE|deadbeef');
  assert.equal(draft.authorizationGeneration, 3);
  assert.equal(draft.editorGeneration, 7);
  assert.equal(draft.status, 'editing');
  assert.equal(draft.failureMessage, null);
});

test('P2 draft model: conflicts compare exact instants and never treat an offset spelling as a change', () => {
  const detail = detailFor();
  const draft = presentation.createAvailabilityDraft({
    sessionId: detail.sessionId,
    sessionVersion: detail.version,
    item: detail.items[0],
    identity: '41|EMPLOYEE|deadbeef',
    authorizationGeneration: 1,
    editorGeneration: 1
  });
  assert.equal(presentation.availabilityRecordedConflict(draft, detail.items[0]), false);
  assert.equal(presentation.availabilityRecordedConflict(draft, mappedItem({
    propertyAvailabilityStartAt: '2026-10-22T08:47:00+05:30'
  })), false, 'the same instant with another valid offset is not a conflict');
  assert.equal(presentation.availabilityRecordedConflict(draft, mappedItem({
    propertyAvailabilityStartAt: '2026-10-22T03:17:00.000000001Z'
  })), true, 'a nanosecond change is a conflict');
});

test('P2 draft model: authority reconciliation recognizes the exact intended confirmation states', () => {
  assert.equal(presentation.availabilityItemSatisfiesDraft(
    mappedItem({
      propertyAvailabilityStartAt: '2026-10-23T12:47:00Z',
      propertyAvailabilityEndAt: '2026-10-30T07:17:00Z'
    }),
    'window',
    WINDOW
  ), true);
  assert.equal(presentation.availabilityItemSatisfiesDraft(
    mappedItem({ propertyAvailabilityStartAt: '2026-10-24T12:47:00Z' }),
    'window',
    WINDOW
  ), false);
  assert.equal(presentation.availabilityItemSatisfiesDraft(
    mappedItem({
      propertyAvailabilityStartAt: null,
      propertyAvailabilityEndAt: null,
      propertyAvailabilityZoneId: null,
      propertyAvailabilitySource: null
    }),
    'confirm-only',
    { availabilityStartAt: null, availabilityEndAt: null, availabilityZoneId: null, availabilitySource: null }
  ), true);
  assert.equal(presentation.availabilityItemSatisfiesDraft(
    mappedItem({ confirmationStatus: 'UNAVAILABLE' }),
    'unavailable',
    { availabilityStartAt: null, availabilityEndAt: null, availabilityZoneId: null, availabilitySource: null }
  ), true);
});

test('P2 wiring: the workspace owns and invalidates exactly one generation-scoped draft', () => {
  const workspace = readFileSync(
    new URL('../components/operations/OperationsWorkspace.tsx', import.meta.url),
    'utf8'
  );
  const itinerary = readFileSync(
    new URL('../components/operations/OperationsDraftItinerary.tsx', import.meta.url),
    'utf8'
  );
  const detail = readFileSync(
    new URL('../components/operations/OperationsWorkDetail.tsx', import.meta.url),
    'utf8'
  );

  assert.match(workspace, /const \[availabilityDraft, setAvailabilityDraft\] = useState<OperationsAvailabilityDraft \| null>\(null\)/);
  assert.match(workspace, /identity: session\.identity/);
  assert.match(workspace, /authorizationGeneration: authorityRef\.current\.authorizationGeneration\(\)/);
  assert.match(workspace, /editorGeneration: availabilityEditorGeneration\.current/);
  assert.match(workspace, /current\.editorGeneration !== editorGeneration/);
  // Every invalidation path drops the protected draft in the same tick as the protected detail.
  assert.match(workspace, /setDetail\(\{ status: 'loading' \}\);\s*\n\s*\/\/ A protected availability draft[^\n]*\n\s*\/\/[^\n]*\n\s*writeAvailabilityDraft\(null\);/);
  assert.match(workspace, /const closeDetail = useCallback[\s\S]{0,400}?writeAvailabilityDraft\(null\);/);
  assert.match(
    workspace,
    /const withdrawDeniedRoot = useCallback\(\(\s*source: OwnershipOperation \| MountedRootIdentity\s*\): boolean => \{[\s\S]{0,1200}?writeAvailabilityDraft\(null\);/
  );
  assert.match(workspace, /const openDraft = availabilityDraftRef\.current[\s\S]{0,500}?writeAvailabilityDraft\(null\);/);
  // Only an applied command clears; failures and uncertainty keep the draft.
  assert.match(workspace, /if \(outcome\.status === 'applied'\) \{[\s\S]{0,900}?clearAvailabilityDraftIfGeneration\(editorGeneration\)/);
  assert.match(workspace, /failure\.kind === 'uncertain'/);
  assert.match(workspace, /writeAvailabilityDraft\(\{ \.\.\.current, status: 'uncertain'/);
  assert.match(workspace, /onDispatch: \(\) => \{[\s\S]{0,500}?writeAvailabilityDraft\(\{ \.\.\.current, attempt \}\)/);
  assert.match(workspace, /availabilityIntentEqualsAttempt\(choice, window, attempt\)[\s\S]{0,160}?clearAvailabilityDraftIfGeneration\(editorGeneration\)/);
  assert.match(workspace, /preflight: \(fresh\) => \{[\s\S]{0,800}?availabilityEvidenceChanged\(presentedEvidence, freshItem\)/);
  assert.equal(workspace.includes('onSaveAvailability={('), false);
  assert.match(detail, /availability=\{availability\}/);
  assert.equal(itinerary.includes('setEditingItemId'), false);
  assert.match(itinerary, /availability\.draft\?\.itemId === item\.itemId/);
});
