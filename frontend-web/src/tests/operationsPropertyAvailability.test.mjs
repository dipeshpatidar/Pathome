/**
 * F4 — property availability: the corrected response mapping, the exact command body, the supported
 * state transitions and the incomplete-window state that still blocks scheduling.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { installOperationsActionHarness } from './support/operationsActionHarness.mjs';

const h = await installOperationsActionHarness();
const { service, session, presentation } = h;

const ACCOUNT = { id: 41, role: 'EMPLOYEE', token: 'token-account-availability' };

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

const detailWith = (confirmationStatus, window = {}) =>
  presentation.toSessionDetail(sessionView([
    item({
      confirmationStatus,
      propertyAvailabilityStartAt: window.start ?? null,
      propertyAvailabilityEndAt: window.end ?? null,
      propertyAvailabilityZoneId: window.zone ?? null,
      propertyAvailabilitySource: window.source ?? null
    })
  ]));

const WINDOW = {
  availabilityStartAt: '2027-02-14T04:00:00+05:30',
  availabilityEndAt: '2027-02-16T12:00:00+05:30',
  availabilityZoneId: 'Asia/Kolkata',
  availabilitySource: 'PHONE'
};

/** A fixed "now" so the future/past window rule is tested deterministically, not against the clock. */
const NOW = new Date('2027-01-01T00:00:00Z');

test('a complete window is sent whole with the command-style field names', async () => {
  h.reset(() => h.json(200, sessionView([item({
    confirmationStatus: 'CONFIRMED',
    propertyAvailabilityStartAt: WINDOW.availabilityStartAt,
    propertyAvailabilityEndAt: WINDOW.availabilityEndAt,
    propertyAvailabilityZoneId: WINDOW.availabilityZoneId,
    propertyAvailabilitySource: WINDOW.availabilitySource
  })])));
  h.signIn(ACCOUNT);

  const result = await service.setSessionItemAvailability(
    3108,
    91,
    { expectedSessionVersion: 6, status: 'CONFIRMED', ...WINDOW },
    { session: session.captureOperationsSession() }
  );

  assert.equal(h.calls[0].method, 'PUT');
  assert.match(h.calls[0].url, /\/operations\/visit-sessions\/3108\/items\/91\/availability$/);
  assert.deepEqual(h.calls[0].body, { expectedSessionVersion: 6, status: 'CONFIRMED', ...WINDOW });
  const mapped = result.items[0];
  assert.equal(mapped.availabilityStartAt, WINDOW.availabilityStartAt);
  assert.equal(mapped.availabilityEndAt, WINDOW.availabilityEndAt);
  assert.equal(mapped.availabilityZoneId, 'Asia/Kolkata');
  assert.equal(mapped.availabilitySource, 'PHONE');
  assert.equal(presentation.operationsAvailabilityMeta(mapped).view, 'confirmed-window');
});

test('UNAVAILABLE carries no window and no source field', () => {
  const detail = detailWith('PENDING');
  const unavailable = presentation.setAvailabilityCommand(detail, 91, 'unavailable', {
    availabilityStartAt: WINDOW.availabilityStartAt,
    availabilityEndAt: WINDOW.availabilityEndAt,
    availabilityZoneId: WINDOW.availabilityZoneId,
    availabilitySource: 'PHONE'
  });
  assert.deepEqual(unavailable.command, {
    targetType: 'SESSION',
    expectedSessionVersion: 6,
    itemId: 91,
    status: 'UNAVAILABLE',
    availabilityStartAt: null,
    availabilityEndAt: null,
    availabilityZoneId: null,
    availabilitySource: null
  });
});

test('confirmation without a window is a real state, and it is not scheduling-ready', () => {
  const detail = detailWith('PENDING');
  const confirmOnly = presentation.setAvailabilityCommand(detail, 91, 'confirm-only', {
    availabilityStartAt: null,
    availabilityEndAt: null,
    availabilityZoneId: null,
    availabilitySource: null
  });
  assert.deepEqual(confirmOnly.command, {
    targetType: 'SESSION',
    expectedSessionVersion: 6,
    itemId: 91,
    status: 'CONFIRMED',
    availabilityStartAt: null,
    availabilityEndAt: null,
    availabilityZoneId: null,
    availabilitySource: null
  });

  const confirmedNoWindow = presentation.operationsAvailabilityMeta({
    confirmationStatus: 'CONFIRMED',
    availabilityStartAt: null,
    availabilityEndAt: null,
    availabilityZoneId: null,
    availabilitySource: null
  });
  assert.equal(confirmedNoWindow.view, 'confirmed-incomplete');
  assert.equal(confirmedNoWindow.schedulingReady, false);
  assert.equal(
    presentation.schedulingBlockersForSession(detailWith('CONFIRMED')).length,
    1
  );
});

test('a partial, reversed or past window is refused with a field-keyed error', () => {
  const detail = detailWith('PENDING');
  const now = new Date('2026-10-13T00:00:00Z');

  const partial = presentation.setAvailabilityCommand(detail, 91, 'window', {
    availabilityStartAt: WINDOW.availabilityStartAt,
    availabilityEndAt: null,
    availabilityZoneId: 'Asia/Kolkata',
    availabilitySource: 'PHONE'
  }, now);
  assert.equal(partial.command, null);
  assert.ok(partial.errors.end);

  const noSource = presentation.setAvailabilityCommand(detail, 91, 'window', {
    availabilityStartAt: WINDOW.availabilityStartAt,
    availabilityEndAt: WINDOW.availabilityEndAt,
    availabilityZoneId: 'Asia/Kolkata',
    availabilitySource: null
  }, now);
  assert.equal(noSource.command, null);
  assert.ok(noSource.errors.source);

  const reversed = presentation.setAvailabilityCommand(detail, 91, 'window', {
    ...WINDOW,
    availabilityStartAt: '2026-10-16T12:00:00+05:30',
    availabilityEndAt: '2026-10-16T04:00:00+05:30'
  }, now);
  assert.equal(reversed.command, null);
  assert.match(reversed.errors.end, /after the start/);

  const past = presentation.setAvailabilityCommand(detail, 91, 'window', {
    ...WINDOW,
    availabilityStartAt: '2026-10-11T04:00:00+05:30',
    availabilityEndAt: '2026-10-12T04:00:00+05:30'
  }, now);
  assert.equal(past.command, null);
  assert.match(past.errors.start, /future/);

  const noZone = presentation.setAvailabilityCommand(detail, 91, 'window', { ...WINDOW, availabilityZoneId: null }, now);
  assert.equal(noZone.command, null);
  assert.ok(noZone.errors.zone);

  const complete = presentation.setAvailabilityCommand(detail, 91, 'window', WINDOW, now);
  assert.deepEqual(complete.errors, {});
  assert.equal(complete.command.status, 'CONFIRMED');
  assert.equal(complete.command.availabilityZoneId, 'Asia/Kolkata');
});

test('the documented transitions are the only ones offered, and UNAVAILABLE is final', () => {
  assert.deepEqual(presentation.availabilityChoicesFor(item({ confirmationStatus: 'PENDING' })), [
    'window',
    'confirm-only',
    'unavailable'
  ]);
  // A CONFIRMED stop can only have its window updated; it can never be flipped.
  assert.deepEqual(presentation.availabilityChoicesFor(item({ confirmationStatus: 'CONFIRMED' })), ['window']);
  assert.deepEqual(presentation.availabilityChoicesFor(item({ confirmationStatus: 'UNAVAILABLE' })), []);
});

test('a removed item cannot be confirmed, and a non-DRAFT session builds no command', async () => {
  h.reset(() => h.json(409, { message: 'Removed itinerary items cannot be confirmed' }));
  h.signIn(ACCOUNT);
  await assert.rejects(
    service.setSessionItemAvailability(
      3108,
      93,
      { expectedSessionVersion: 6, status: 'CONFIRMED', ...WINDOW },
      { session: session.captureOperationsSession() }
    ),
    (error) => error.status === 409
  );

  assert.equal(
    presentation.setAvailabilityCommand(detailWith('PENDING', {}), 91, 'window', WINDOW, NOW).command.status,
    'CONFIRMED'
  );
  const scheduled = presentation.toSessionDetail(sessionView([item()], { status: 'SCHEDULED' }));
  assert.equal(presentation.setAvailabilityCommand(scheduled, 91, 'window', WINDOW, NOW).command, null);
});

test('a session whose only active stop is unavailable is reported, not offered for scheduling', () => {
  const detail = detailWith('UNAVAILABLE');
  const mapped = detail.items[0];
  assert.equal(presentation.operationsAvailabilityMeta(mapped).view, 'unavailable');
  assert.equal(presentation.operationsAvailabilityMeta(mapped).schedulingReady, false);
  // The server has no confirmed stop to evaluate a visit time around, so the drawer must say so
  // instead of letting the OE reach a selection that cannot be booked.
  assert.equal(presentation.schedulingBlockersForSession(detail).length, 1);
  assert.match(presentation.schedulingBlockersForSession(detail)[0], /unavailable/);
  assert.equal(presentation.unavailableActiveStopCount(detail), 1);
});

test('a full window is required before any stop counts as scheduling-ready', () => {
  const ready = detailWith('CONFIRMED', {
    start: WINDOW.availabilityStartAt,
    end: WINDOW.availabilityEndAt,
    zone: 'Asia/Kolkata',
    source: 'PHONE'
  });
  assert.deepEqual(presentation.schedulingBlockersForSession(ready), []);
  assert.equal(presentation.formatAvailabilityWindow(ready.items[0]).includes('IST (Asia/Kolkata)'), true);
  assert.equal(presentation.formatAvailabilityWindow(ready.items[0]).includes('Phone call'), true);
});

/* ------------------------------------------------------------------ *
 * Availability timestamp roundtrip (P0-1)
 *
 * The editor must show, and send back, the instant in the property's own zone. Slicing the UTC
 * wall clock off the server instant (the previous behaviour) displayed 04:00 for an instant that is
 * 09:30 in Asia/Kolkata and could shift the persisted instant by 5h30m on an unchanged save.
 * ------------------------------------------------------------------ */

const KOLKATA = 'Asia/Kolkata';

test('a server instant becomes the wall clock of the property zone, not of UTC or the device', () => {
  // UTC instant.
  assert.equal(presentation.serverInstantToZoneInput('2027-02-14T04:00:00Z', KOLKATA), '2027-02-14T09:30');
  // The same instant written with the zone's own offset is identical.
  assert.equal(presentation.serverInstantToZoneInput('2027-02-14T09:30:00+05:30', KOLKATA), '2027-02-14T09:30');
  // Another instant, to prove the conversion is not a fixed shift of the string.
  assert.equal(presentation.serverInstantToZoneInput('2027-02-14T12:00:00Z', KOLKATA), '2027-02-14T17:30');
  // The device zone is never consulted: the same input converts identically regardless of `TZ`.
  assert.equal(presentation.serverInstantToZoneInput('2027-02-14T04:00:00Z', KOLKATA).slice(11), '09:30');
});

test('an edited wall clock becomes an unambiguous instant in that zone', () => {
  assert.equal(
    presentation.zoneInputToServerInstant('2027-02-14T09:30', KOLKATA),
    '2027-02-14T09:30:00+05:30'
  );
  assert.equal(
    Date.parse(presentation.zoneInputToServerInstant('2027-02-14T09:30', KOLKATA)),
    Date.parse('2027-02-14T04:00:00Z')
  );
});

test('an unchanged window round-trips to the exact same instant', () => {
  for (const iso of [
    '2027-02-14T04:00:00Z',
    '2027-02-14T18:45:00Z',
    '2027-02-14T20:00:00Z',
    '2027-01-01T00:00:00Z'
  ]) {
    const field = presentation.serverInstantToZoneInput(iso, KOLKATA);
    const back = presentation.zoneInputToServerInstant(field, KOLKATA);
    assert.equal(Date.parse(back), Date.parse(iso), `${iso} → ${field} → ${back}`);
  }
});

test('date boundaries roll over inside the zone', () => {
  assert.equal(presentation.serverInstantToZoneInput('2027-02-14T18:29:00Z', KOLKATA), '2027-02-14T23:59');
  assert.equal(presentation.serverInstantToZoneInput('2027-02-14T18:30:00Z', KOLKATA), '2027-02-15T00:00');
  assert.equal(presentation.serverInstantToZoneInput('2027-02-14T20:00:00Z', KOLKATA), '2027-02-15T01:30');
  // And the boundary converts back to the same instant.
  assert.equal(
    Date.parse(presentation.zoneInputToServerInstant('2027-02-15T00:00', KOLKATA)),
    Date.parse('2027-02-14T18:30:00Z')
  );
});

test('an unusable zone or value is refused instead of being silently substituted', () => {
  assert.equal(presentation.serverInstantToZoneInput('2027-02-14T04:00:00Z', null), '');
  assert.equal(presentation.serverInstantToZoneInput('2027-02-14T04:00:00Z', 'Not/AZone'), '');
  assert.equal(presentation.serverInstantToZoneInput('not-an-instant', KOLKATA), '');
  assert.equal(presentation.zoneInputToServerInstant('2027-02-14T09:30', null), null);
  assert.equal(presentation.zoneInputToServerInstant('2027-02-14T09:30', 'Not/AZone'), null);
  assert.equal(presentation.zoneInputToServerInstant('nonsense', KOLKATA), null);

  // A local time that does not exist in the zone (the 2027 US spring-forward gap) is refused, not
  // shifted into a different instant.
  assert.equal(presentation.zoneInputToServerInstant('2027-03-14T02:30', 'America/New_York'), null);
  // A real local time in that zone carries that zone's offset.
  assert.equal(
    presentation.zoneInputToServerInstant('2027-07-14T09:30', 'America/New_York'),
    '2027-07-14T09:30:00-04:00'
  );
});

test('the zone selector offers the recorded zone instead of replacing it', () => {
  assert.deepEqual(presentation.operationsZoneOptions(null), ['Asia/Kolkata']);
  assert.deepEqual(presentation.operationsZoneOptions('Asia/Kolkata'), ['Asia/Kolkata']);
  assert.deepEqual(presentation.operationsZoneOptions('Europe/London'), ['Asia/Kolkata', 'Europe/London']);
  // A value that is not a real zone is ignored rather than offered as a broken option.
  assert.deepEqual(presentation.operationsZoneOptions('Not/AZone'), ['Asia/Kolkata']);
});

test('the whole editor payload path preserves the recorded instant end to end', () => {
  const detail = detailWith('CONFIRMED', {
    start: '2027-02-14T04:00:00Z',
    end: '2027-02-14T12:00:00Z',
    zone: KOLKATA,
    source: 'PHONE'
  });
  const recorded = detail.items[0];

  // What the editor displays for the recorded window.
  const startField = presentation.serverInstantToZoneInput(
    recorded.availabilityStartAt,
    recorded.availabilityZoneId
  );
  const endField = presentation.serverInstantToZoneInput(
    recorded.availabilityEndAt,
    recorded.availabilityZoneId
  );
  assert.equal(startField, '2027-02-14T09:30');
  assert.equal(endField, '2027-02-14T17:30');

  // Saving it unchanged sends the same instants back, under the command field names.
  const built = presentation.setAvailabilityCommand(detail, 91, 'window', {
    availabilityStartAt: presentation.zoneInputToServerInstant(startField, recorded.availabilityZoneId),
    availabilityEndAt: presentation.zoneInputToServerInstant(endField, recorded.availabilityZoneId),
    availabilityZoneId: recorded.availabilityZoneId,
    availabilitySource: 'PHONE'
  }, NOW);
  assert.deepEqual(built.errors, {});
  assert.equal(Date.parse(built.command.availabilityStartAt), Date.parse(recorded.availabilityStartAt));
  assert.equal(Date.parse(built.command.availabilityEndAt), Date.parse(recorded.availabilityEndAt));
  assert.equal(built.command.availabilityStartAt, '2027-02-14T09:30:00+05:30');
  assert.equal(built.command.status, 'CONFIRMED');

  // A reversed window built from converted values is still refused.
  const reversed = presentation.setAvailabilityCommand(detail, 91, 'window', {
    availabilityStartAt: presentation.zoneInputToServerInstant(endField, recorded.availabilityZoneId),
    availabilityEndAt: presentation.zoneInputToServerInstant(startField, recorded.availabilityZoneId),
    availabilityZoneId: recorded.availabilityZoneId,
    availabilitySource: 'PHONE'
  }, NOW);
  assert.equal(reversed.command, null);
  assert.match(reversed.errors.end, /after the start/);
});

/* ------------------------------------------------------------------ *
 * R2 — an unchanged window must keep its exact instant
 *
 * The editor shows the property zone's wall clock, which cannot express seconds, fractional seconds
 * or which of two repeated hours an instant belongs to. Re-deriving an untouched field from that wall
 * clock therefore moved the persisted instant (Sol's repro: 06:30Z in America/New_York returned
 * 05:30Z, and 04:00:37.123Z in Asia/Kolkata returned 04:00:00Z). The editor now returns the recorded
 * server instant verbatim while nothing was edited.
 * ------------------------------------------------------------------ */

const NEW_YORK = 'America/New_York';

test('both occurrences of a repeated DST hour survive an unchanged save', () => {
  // 2027-11-07 01:30 happens twice in New York (EDT −04:00, then EST −05:00).
  const firstOccurrence = '2027-11-07T05:30:00Z';
  const secondOccurrence = '2027-11-07T06:30:00Z';
  for (const instant of [firstOccurrence, secondOccurrence]) {
    const field = presentation.serverInstantToZoneInput(instant, NEW_YORK);
    assert.equal(field, '2027-11-07T01:30', instant);
    assert.deepEqual(
      presentation.resolveAvailabilityFieldInstant({
        value: field,
        initialValue: field,
        zoneId: NEW_YORK,
        initialZoneId: NEW_YORK,
        initialInstant: instant
      }),
      { value: instant, error: null },
      `${instant} must come back byte-for-byte`
    );
  }
  // The two instants really are an hour apart, so the round-trip is not proving a fixed value.
  assert.notEqual(Date.parse(firstOccurrence), Date.parse(secondOccurrence));
});

test('seconds and fractional seconds are preserved when no time change was requested', () => {
  const instant = '2027-02-14T04:00:37.123Z';
  const field = presentation.serverInstantToZoneInput(instant, KOLKATA);
  assert.equal(field, '2027-02-14T09:30');
  assert.deepEqual(
    presentation.resolveAvailabilityFieldInstant({
      value: field,
      initialValue: field,
      zoneId: KOLKATA,
      initialZoneId: KOLKATA,
      initialInstant: instant
    }),
    { value: instant, error: null }
  );
});

test('an unchanged window at a midnight rollover keeps its instant, and an edited one still converts', () => {
  // Kolkata midnight belongs to the previous UTC day.
  const instant = '2027-02-14T18:30:00Z';
  assert.equal(presentation.serverInstantToZoneInput(instant, KOLKATA), '2027-02-15T00:00');
  assert.deepEqual(
    presentation.resolveAvailabilityFieldInstant({
      value: '2027-02-15T00:00',
      initialValue: '2027-02-15T00:00',
      zoneId: KOLKATA,
      initialZoneId: KOLKATA,
      initialInstant: instant
    }),
    { value: instant, error: null }
  );

  // A real edit is converted through the zone, not preserved.
  assert.deepEqual(
    presentation.resolveAvailabilityFieldInstant({
      value: '2027-02-15T00:30',
      initialValue: '2027-02-15T00:00',
      zoneId: KOLKATA,
      initialZoneId: KOLKATA,
      initialInstant: instant
    }),
    { value: '2027-02-15T00:30:00+05:30', error: null }
  );
});

test('an ambiguous edited time is refused instead of being given an unintended offset', () => {
  // Reinterpreting the same wall clock in a zone where it occurs twice: no offset may be chosen.
  assert.deepEqual(
    presentation.resolveAvailabilityFieldInstant({
      value: '2027-11-07T01:30',
      initialValue: '2027-11-07T01:30',
      zoneId: NEW_YORK,
      initialZoneId: KOLKATA,
      initialInstant: '2027-11-07T01:30:00+05:30'
    }),
    { value: null, error: 'ambiguous' }
  );
  assert.equal(presentation.zoneInputToServerInstant('2027-11-07T01:30', NEW_YORK), null);
  // Clearing the field is not an ambiguity error: the field simply has no value yet.
  assert.deepEqual(
    presentation.resolveAvailabilityFieldInstant({
      value: '',
      initialValue: '2027-11-07T01:30',
      zoneId: NEW_YORK,
      initialZoneId: NEW_YORK,
      initialInstant: '2027-11-07T05:30:00Z'
    }),
    { value: null, error: null }
  );
});

test('a DST gap is still refused, and the complete-window rule is unchanged', () => {
  assert.deepEqual(
    presentation.resolveAvailabilityFieldInstant({
      value: '2027-03-14T02:30',
      initialValue: '',
      zoneId: NEW_YORK,
      initialZoneId: NEW_YORK,
      initialInstant: null
    }),
    { value: null, error: 'gap' }
  );
  assert.equal(presentation.zoneInputToServerInstant('2027-03-14T02:30', NEW_YORK), null);

  // The complete-window rule still applies to values produced by the exact round-trip path.
  const detail = detailWith('CONFIRMED', {
    start: '2027-02-14T04:00:37.123Z',
    end: '2027-02-16T12:00:00Z',
    zone: KOLKATA,
    source: 'PHONE'
  });
  const recorded = detail.items[0];
  const resolve = (value, initialValue, initialInstant) => presentation.resolveAvailabilityFieldInstant({
    value,
    initialValue,
    zoneId: KOLKATA,
    initialZoneId: KOLKATA,
    initialInstant
  });
  const startField = presentation.serverInstantToZoneInput(recorded.availabilityStartAt, KOLKATA);
  const endField = presentation.serverInstantToZoneInput(recorded.availabilityEndAt, KOLKATA);

  const whole = presentation.setAvailabilityCommand(detail, 91, 'window', {
    availabilityStartAt: resolve(startField, startField, recorded.availabilityStartAt).value,
    availabilityEndAt: resolve(endField, endField, recorded.availabilityEndAt).value,
    availabilityZoneId: KOLKATA,
    availabilitySource: 'PHONE'
  }, NOW);
  assert.deepEqual(whole.errors, {});
  assert.equal(whole.command.availabilityStartAt, '2027-02-14T04:00:37.123Z');
  assert.equal(whole.command.availabilityEndAt, '2027-02-16T12:00:00Z');

  // Only a start, still refused as a partial window.
  const partial = presentation.setAvailabilityCommand(detail, 91, 'window', {
    availabilityStartAt: resolve(startField, startField, recorded.availabilityStartAt).value,
    availabilityEndAt: '',
    availabilityZoneId: KOLKATA,
    availabilitySource: 'PHONE'
  }, NOW);
  assert.equal(partial.command, null);
  assert.ok(partial.errors.end);
});
