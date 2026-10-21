/**
 * F4 — property availability: the corrected response mapping, the exact command body, the supported
 * state transitions and the incomplete-window state that still blocks scheduling.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { installOperationsActionHarness } from './support/operationsActionHarness.mjs';
import {
  DETAIL_VERSION,
  SCENARIOS,
  availabilityItemFixture,
  availabilitySessionFixture,
  openAvailabilityEditor,
  runBridgeScenarios
} from './support/availabilityCommandBridge.mjs';

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
 * R2 — an unchanged window must keep its exact instant and its zone's own offset
 *
 * The editor shows the property zone's wall clock, which cannot express seconds, fractional seconds
 * or which of two repeated hours an instant belongs to. Re-deriving an untouched field from that wall
 * clock therefore moved the persisted instant (Sol's repro: 06:30Z in America/New_York returned
 * 05:30Z, and 04:00:37.123Z in Asia/Kolkata returned 04:00:00Z). An untouched field now keeps the
 * recorded instant and writes it with the selected zone's own offset: the backend validator refuses
 * an offset the declared zone was not using at that local time, which is why an unchanged save failed
 * on the iPhone ("offset does not match the supplied timezone") — the server's UTC Z representation
 * was arriving beside Asia/Kolkata.
 * ------------------------------------------------------------------ */

const NEW_YORK = 'America/New_York';

test('both occurrences of a repeated DST hour survive an unchanged save', () => {
  // 2027-11-07 01:30 happens twice in New York (EDT −04:00, then EST −05:00), an hour apart in UTC.
  const occurrences = [
    { instant: '2027-11-07T05:30:00Z', offset: '-04:00' },
    { instant: '2027-11-07T06:30:00Z', offset: '-05:00' }
  ];
  for (const { instant, offset } of occurrences) {
    const field = presentation.serverInstantToZoneInput(instant, NEW_YORK);
    assert.equal(field, '2027-11-07T01:30', instant);
    const resolved = presentation.resolveAvailabilityFieldInstant({
      value: field,
      provenance: 'RECORDED',
      zoneId: NEW_YORK,
      initialInstant: instant
    });
    assert.equal(resolved.error, null);
    assert.equal(resolved.value, `2027-11-07T01:30:00${offset}`, `${instant} keeps its own occurrence`);
    assert.equal(Date.parse(resolved.value), Date.parse(instant), `${instant} keeps its exact instant`);
  }
  // The two instants really are an hour apart, so the round-trip is not proving a fixed value.
  assert.notEqual(Date.parse(occurrences[0].instant), Date.parse(occurrences[1].instant));
});

test('seconds and fractional seconds are preserved when no time change was requested', () => {
  const instant = '2027-02-14T04:00:37.123456789Z';
  const field = presentation.serverInstantToZoneInput(instant, KOLKATA);
  assert.equal(field, '2027-02-14T09:30');
  assert.deepEqual(
    presentation.resolveAvailabilityFieldInstant({
      value: field,
      provenance: 'RECORDED',
      zoneId: KOLKATA,
      initialInstant: instant
    }),
    { value: '2027-02-14T09:30:37.123456789+05:30', error: null }
  );
});

test('an unchanged window at a midnight rollover keeps its instant, and an edited one still converts', () => {
  // Kolkata midnight belongs to the previous UTC day.
  const instant = '2027-02-14T18:30:00Z';
  assert.equal(presentation.serverInstantToZoneInput(instant, KOLKATA), '2027-02-15T00:00');
  assert.deepEqual(
    presentation.resolveAvailabilityFieldInstant({
      value: '2027-02-15T00:00',
      provenance: 'RECORDED',
      zoneId: KOLKATA,
      initialInstant: instant
    }),
    { value: '2027-02-15T00:00:00+05:30', error: null }
  );
  assert.equal(Date.parse('2027-02-15T00:00:00+05:30'), Date.parse(instant));

  // A real edit is converted through the zone, not preserved.
  assert.deepEqual(
    presentation.resolveAvailabilityFieldInstant({
      value: '2027-02-15T00:30',
      provenance: 'EDITED',
      zoneId: KOLKATA,
      initialInstant: instant
    }),
    { value: '2027-02-15T00:30:00+05:30', error: null }
  );
});

/** The zone's own offset at an instant, read from `Intl` rather than from the code under test. */
const intlOffsetAt = (iso, zone) =>
  new Intl.DateTimeFormat('en-US', { timeZone: zone, timeZoneName: 'longOffset' })
    .formatToParts(new Date(iso))
    .find((part) => part.type === 'timeZoneName').value.replace('GMT', '') || '+00:00';

test('an untouched field is sent with the selected zone’s own offset, not the server’s Z', () => {
  // The exact shape the iPhone response delivered: UTC Z instants for a property recorded in IST.
  const start = '2026-10-22T12:47:00Z';
  const end = '2026-10-30T12:47:00Z';
  const resolvedStart = presentation.resolveAvailabilityFieldInstant({
    value: presentation.serverInstantToZoneInput(start, KOLKATA),
    provenance: 'RECORDED',
    zoneId: KOLKATA,
    initialInstant: start
  });
  const resolvedEnd = presentation.resolveAvailabilityFieldInstant({
    value: presentation.serverInstantToZoneInput(end, KOLKATA),
    provenance: 'RECORDED',
    zoneId: KOLKATA,
    initialInstant: end
  });

  assert.deepEqual(resolvedStart, { value: '2026-10-22T18:17:00+05:30', error: null });
  assert.deepEqual(resolvedEnd, { value: '2026-10-30T18:17:00+05:30', error: null });
  // The instants are exactly the recorded ones, and the offset is the one the zone really used there.
  assert.equal(Date.parse(resolvedStart.value), Date.parse(start));
  assert.equal(Date.parse(resolvedEnd.value), Date.parse(end));
  assert.equal(resolvedStart.value.slice(-6), intlOffsetAt(start, KOLKATA));
  // The representation the backend refused is no longer produced for an untouched field.
  assert.equal(resolvedStart.value.endsWith('Z'), false);
});

test('editing one field leaves the untouched field valid for the supplied zone', () => {
  const start = '2026-10-22T12:47:00Z';
  const end = '2026-10-30T12:47:00Z';
  const startField = presentation.serverInstantToZoneInput(start, KOLKATA);
  const endField = presentation.serverInstantToZoneInput(end, KOLKATA);

  // Start edited, end untouched.
  const editedStart = presentation.resolveAvailabilityFieldInstant({
    value: '2026-10-23T13:45',
    provenance: 'EDITED',
    zoneId: KOLKATA,
    initialInstant: start
  });
  const keptEnd = presentation.resolveAvailabilityFieldInstant({
    value: endField,
    provenance: 'RECORDED',
    zoneId: KOLKATA,
    initialInstant: end
  });
  assert.equal(editedStart.value, '2026-10-23T13:45:00+05:30');
  assert.equal(editedStart.value.slice(-6), intlOffsetAt(editedStart.value, KOLKATA));
  assert.equal(keptEnd.value, '2026-10-30T18:17:00+05:30');
  assert.equal(keptEnd.value.slice(-6), intlOffsetAt(end, KOLKATA));

  // End edited, start untouched — the second rejection the owner saw, now valid too.
  const keptStart = presentation.resolveAvailabilityFieldInstant({
    value: startField,
    provenance: 'RECORDED',
    zoneId: KOLKATA,
    initialInstant: start
  });
  const editedEnd = presentation.resolveAvailabilityFieldInstant({
    value: '2026-10-30T19:45',
    provenance: 'EDITED',
    zoneId: KOLKATA,
    initialInstant: end
  });
  assert.equal(keptStart.value, '2026-10-22T18:17:00+05:30');
  assert.equal(keptStart.value.slice(-6), intlOffsetAt(start, KOLKATA));
  assert.equal(editedEnd.value, '2026-10-30T19:45:00+05:30');
});

test('changing only the zone re-expresses the same instant instead of moving it', () => {
  // The item records New York while the OE switches the selector to IST and touches no time.
  const start = '2026-10-22T12:47:00Z';
  const end = '2026-10-30T12:47:00Z';
  assert.equal(presentation.serverInstantToZoneInput(start, NEW_YORK), '2026-10-22T08:47');

  const rezonedStart = presentation.rezoneAvailabilityFieldInput({
    value: '2026-10-22T08:47',
    provenance: 'RECORDED',
    initialInstant: start,
    nextZoneId: KOLKATA
  });
  const rezonedEnd = presentation.rezoneAvailabilityFieldInput({
    value: presentation.serverInstantToZoneInput(end, NEW_YORK),
    provenance: 'RECORDED',
    initialInstant: end,
    nextZoneId: KOLKATA
  });
  assert.equal(rezonedStart, '2026-10-22T18:17');
  assert.equal(rezonedEnd, '2026-10-30T18:17');

  // The re-expressed text still resolves to the recorded instant, under the new zone's offset.
  for (const [text, instant] of [[rezonedStart, start], [rezonedEnd, end]]) {
    const resolved = presentation.resolveAvailabilityFieldInstant({
      value: text,
      provenance: 'RECORDED',
      zoneId: KOLKATA,
      initialInstant: instant
    });
    assert.equal(resolved.error, null);
    assert.equal(Date.parse(resolved.value), Date.parse(instant));
    assert.equal(resolved.value.slice(-6), '+05:30');
  }

  // Text the OE typed is left exactly as typed: choosing a zone says which zone that wall clock is in.
  assert.equal(
    presentation.rezoneAvailabilityFieldInput({
      value: '2026-10-23T13:45',
      provenance: 'EDITED',
      initialInstant: start,
      nextZoneId: KOLKATA
    }),
    '2026-10-23T13:45'
  );
  // An untouched field re-expressed in a DST zone follows that zone's offset for its own instant.
  const summer = '2027-07-14T13:30:00Z';
  const rezonedSummer = presentation.rezoneAvailabilityFieldInput({
    value: presentation.serverInstantToZoneInput(summer, KOLKATA),
    provenance: 'RECORDED',
    initialInstant: summer,
    nextZoneId: NEW_YORK
  });
  assert.equal(rezonedSummer, '2027-07-14T09:30');
  assert.deepEqual(
    presentation.resolveAvailabilityFieldInstant({
      value: rezonedSummer,
      provenance: 'RECORDED',
      zoneId: NEW_YORK,
      initialInstant: summer
    }),
    { value: '2027-07-14T09:30:00-04:00', error: null }
  );
});

test('a value that is not a real instant in a real zone is never re-expressed', () => {
  assert.equal(presentation.instantToZoneInstant('2026-10-22T12:47:00Z', null), null);
  assert.equal(presentation.instantToZoneInstant('2026-10-22T12:47:00Z', 'Not/AZone'), null);
  assert.equal(presentation.instantToZoneInstant('not-an-instant', KOLKATA), null);
  assert.equal(presentation.instantToZoneInstant('2026-02-30T12:47:00Z', KOLKATA), null);
  assert.equal(presentation.instantToZoneInstant(null, KOLKATA), null);
  // The zone's own offset form is re-expressed as itself, with the fraction left alone.
  assert.equal(presentation.instantToZoneInstant('2026-10-22T18:17:00+05:30', KOLKATA), '2026-10-22T18:17:00+05:30');
  assert.equal(presentation.instantToZoneInstant('2026-10-22T12:47:37.120000000Z', KOLKATA), '2026-10-22T18:17:37.12+05:30');
  // Seconds must not pull the offset onto the previous minute: 18:17:59.999 is still +05:30.
  assert.equal(presentation.instantToZoneInstant('2026-10-22T12:47:59.999Z', KOLKATA), '2026-10-22T18:17:59.999+05:30');
  assert.equal(presentation.instantToZoneInstant('2026-10-22T12:47:00Z', NEW_YORK), '2026-10-22T08:47:00-04:00');
  // An instant `Date.parse` tolerates but that is not a real calendar instant is refused rather than
  // reinterpreted from the wall clock it was normalised into.
  assert.deepEqual(
    presentation.resolveAvailabilityFieldInstant({
      value: presentation.serverInstantToZoneInput('2027-02-30T04:00:00Z', KOLKATA),
      provenance: 'RECORDED',
      zoneId: KOLKATA,
      initialInstant: '2027-02-30T04:00:00Z'
    }),
    { value: null, error: 'invalid' }
  );
});

test('an ambiguous edited time is refused instead of being given an unintended offset', () => {
  // Reinterpreting the same wall clock in a zone where it occurs twice: no offset may be chosen.
  assert.deepEqual(
    presentation.resolveAvailabilityFieldInstant({
      value: '2027-11-07T01:30',
      provenance: 'EDITED',
      zoneId: NEW_YORK,
      initialInstant: '2027-11-07T01:30:00+05:30'
    }),
    { value: null, error: 'ambiguous' }
  );
  assert.equal(presentation.zoneInputToServerInstant('2027-11-07T01:30', NEW_YORK), null);
  // Clearing the field is not an ambiguity error: the field simply has no value yet.
  assert.deepEqual(
    presentation.resolveAvailabilityFieldInstant({
      value: '',
      provenance: 'EDITED',
      zoneId: NEW_YORK,
      initialInstant: '2027-11-07T05:30:00Z'
    }),
    { value: null, error: null }
  );
});

test('a DST gap is still refused, and the complete-window rule is unchanged', () => {
  assert.deepEqual(
    presentation.resolveAvailabilityFieldInstant({
      value: '2027-03-14T02:30',
      provenance: 'EDITED',
      zoneId: NEW_YORK,
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
  const resolve = (value, initialInstant) => presentation.resolveAvailabilityFieldInstant({
    value,
    provenance: 'RECORDED',
    zoneId: KOLKATA,
    initialInstant
  });
  const startField = presentation.serverInstantToZoneInput(recorded.availabilityStartAt, KOLKATA);
  const endField = presentation.serverInstantToZoneInput(recorded.availabilityEndAt, KOLKATA);

  const whole = presentation.setAvailabilityCommand(detail, 91, 'window', {
    availabilityStartAt: resolve(startField, recorded.availabilityStartAt).value,
    availabilityEndAt: resolve(endField, recorded.availabilityEndAt).value,
    availabilityZoneId: KOLKATA,
    availabilitySource: 'PHONE'
  }, NOW);
  assert.deepEqual(whole.errors, {});
  assert.equal(whole.command.availabilityStartAt, '2027-02-14T09:30:37.123+05:30');
  assert.equal(whole.command.availabilityEndAt, '2027-02-16T17:30:00+05:30');
  // The saved window still denotes the recorded instants, to the nanosecond.
  assert.equal(Date.parse(whole.command.availabilityStartAt), Date.parse(recorded.availabilityStartAt));
  assert.equal(Date.parse(whole.command.availabilityEndAt), Date.parse(recorded.availabilityEndAt));
  assert.equal(whole.command.availabilityStartAt.slice(-6), intlOffsetAt(recorded.availabilityStartAt, KOLKATA));

  // Only a start, still refused as a partial window.
  const partial = presentation.setAvailabilityCommand(detail, 91, 'window', {
    availabilityStartAt: resolve(startField, recorded.availabilityStartAt).value,
    availabilityEndAt: '',
    availabilityZoneId: KOLKATA,
    availabilitySource: 'PHONE'
  }, NOW);
  assert.equal(partial.command, null);
  assert.ok(partial.errors.end);
});

/* ------------------------------------------------------------------ *
 * A1-F1 — per-field provenance: RECORDED versus EDITED
 *
 * A1 inferred an untouched field by comparing its text with what the recorded instant looks like in
 * the selected zone. A value the OE typed can coincide with that display once the zone changes, so the
 * edit was silently replaced by the recorded instant — with its hidden seconds, or with the other
 * occurrence of a repeated hour. Provenance is now explicit per field.
 *
 * These tests drive the real editor component: `support/availabilityCommandBridge.mjs` extracts
 * `AvailabilityEditor` from `OperationsDraftItinerary.tsx` and applies its own change and submit
 * handlers in the OE's own order. No conversion is re-implemented here.
 * ------------------------------------------------------------------ */

/** Opens the real availability editor for one recorded item, as pressing "Update window" does. */
const openEditor = (recorded, overrides) => {
  const responseItem = availabilityItemFixture({
    propertyAvailabilityStartAt: recorded.start ?? null,
    propertyAvailabilityEndAt: recorded.end ?? null,
    propertyAvailabilityZoneId: recorded.zone ?? null,
    propertyAvailabilitySource: 'PHONE'
  });
  const detail = presentation.toSessionDetail(availabilitySessionFixture([responseItem]));
  return openAvailabilityEditor(presentation, {
    session: detail,
    item: detail.items[0],
    blocked: false,
    busyCommand: null
  }, overrides);
};

/** 12:47:37.123456789Z reads 08:47 in New York — the window the reported defect was raised on. */
const REPORTED_ITEM = {
  zone: NEW_YORK,
  start: '2026-10-22T12:47:37.123456789Z',
  end: '2026-10-30T12:47:00Z'
};

test('F1: a typed start survives the reported zone sequence and still decides the saved instant', () => {
  const editor = openEditor(REPORTED_ITEM);
  assert.equal(editor.state().start, '2026-10-22T08:47');

  editor.text('start', '2026-10-22T18:17');
  editor.zone(KOLKATA);
  assert.equal(editor.state().start, '2026-10-22T18:17', 'a zone change must not rewrite an edit');
  editor.zone(NEW_YORK);
  assert.equal(editor.state().start, '2026-10-22T18:17', 'nor must changing the zone back');

  editor.submit();
  const saved = editor.state();
  assert.equal(saved.submitted, true);
  assert.equal(saved.saved.window.availabilityStartAt, '2026-10-22T18:17:00-04:00');
  assert.equal(Date.parse(saved.saved.window.availabilityStartAt), Date.parse('2026-10-22T22:17:00Z'));
  // The untouched end keeps its recorded instant exactly, expressed in the selected zone's offset.
  assert.equal(saved.saved.window.availabilityEndAt, '2026-10-30T08:47:00-04:00');
  assert.equal(Date.parse(saved.saved.window.availabilityEndAt), Date.parse('2026-10-30T12:47:00Z'));
});

test('F1: an edit to one field leaves the other field’s recorded instant alone', () => {
  const endOnly = openEditor(REPORTED_ITEM);
  endOnly.text('end', '2026-10-30T20:00');
  endOnly.zone(KOLKATA);
  endOnly.zone(NEW_YORK);
  endOnly.submit();
  const endSaved = endOnly.state().saved.window;
  assert.equal(endSaved.availabilityEndAt, '2026-10-30T20:00:00-04:00');
  assert.equal(Date.parse(endSaved.availabilityEndAt), Date.parse('2026-10-31T00:00:00Z'));
  assert.equal(endSaved.availabilityStartAt, '2026-10-22T08:47:37.123456789-04:00');
  assert.equal(Date.parse(endSaved.availabilityStartAt), Date.parse(REPORTED_ITEM.start));

  const startOnly = openEditor(REPORTED_ITEM);
  startOnly.text('start', '2026-10-22T18:17');
  startOnly.zone(KOLKATA);
  startOnly.zone(NEW_YORK);
  startOnly.submit();
  const startSaved = startOnly.state().saved.window;
  assert.equal(startSaved.availabilityStartAt, '2026-10-22T18:17:00-04:00');
  assert.equal(startSaved.availabilityEndAt, '2026-10-30T08:47:00-04:00');
  assert.equal(Date.parse(startSaved.availabilityEndAt), Date.parse(REPORTED_ITEM.end));
});

test('F1: both fields edited stay edited through repeated zone changes', () => {
  const editor = openEditor(REPORTED_ITEM);
  editor.text('start', '2026-10-22T18:17');
  editor.text('end', '2026-10-30T20:00');
  editor.zone(KOLKATA);
  editor.zone(NEW_YORK);
  editor.zone(KOLKATA);
  assert.deepEqual([editor.state().start, editor.state().end], ['2026-10-22T18:17', '2026-10-30T20:00']);
  editor.zone(NEW_YORK);
  editor.submit();
  const saved = editor.state().saved.window;
  assert.equal(saved.availabilityStartAt, '2026-10-22T18:17:00-04:00');
  assert.equal(Date.parse(saved.availabilityStartAt), Date.parse('2026-10-22T22:17:00Z'));
  assert.equal(saved.availabilityEndAt, '2026-10-30T20:00:00-04:00');
  assert.equal(Date.parse(saved.availabilityEndAt), Date.parse('2026-10-31T00:00:00Z'));
});

test('F1: an edit that coincides with the recorded display in another zone is still an edit', () => {
  // The recorded instant reads 08:47 in New York and 18:17 in IST, so typing 18:17 and then selecting
  // IST makes the typed text identical to what the recorded instant looks like in the selected zone.
  const editor = openEditor(REPORTED_ITEM);
  editor.text('start', '2026-10-22T18:17');
  editor.zone(KOLKATA);
  assert.equal(editor.state().start, '2026-10-22T18:17');
  editor.submit();
  const saved = editor.state().saved.window;
  // The edit wins: IST 18:17 exactly, without the recorded instant's hidden 37.123456789 seconds.
  assert.equal(saved.availabilityStartAt, '2026-10-22T18:17:00+05:30');
  assert.equal(Date.parse(saved.availabilityStartAt), Date.parse('2026-10-22T12:47:00Z'));
  assert.notEqual(saved.availabilityStartAt, '2026-10-22T18:17:37.123456789+05:30');
});

test('F1: changing the zone first re-expresses recorded fields and leaves a later edit an edit', () => {
  const editor = openEditor({ zone: NEW_YORK, start: '2026-10-22T12:47:00Z', end: '2026-10-30T12:47:00Z' });
  editor.zone(KOLKATA);
  assert.deepEqual([editor.state().start, editor.state().end], ['2026-10-22T18:17', '2026-10-30T18:17']);
  editor.text('start', '2026-10-23T09:00');
  editor.zone(NEW_YORK);
  assert.equal(editor.state().start, '2026-10-23T09:00', 'the later edit is not re-expressed');
  assert.equal(editor.state().end, '2026-10-30T08:47', 'the still-recorded end follows the zone');
  editor.submit();
  const saved = editor.state().saved.window;
  assert.equal(saved.availabilityStartAt, '2026-10-23T09:00:00-04:00');
  assert.equal(Date.parse(saved.availabilityStartAt), Date.parse('2026-10-23T13:00:00Z'));
  assert.equal(Date.parse(saved.availabilityEndAt), Date.parse('2026-10-30T12:47:00Z'));
});

test('F1: four zone changes with nothing typed keep the recorded instants exactly', () => {
  const editor = openEditor(REPORTED_ITEM);
  for (const zone of [KOLKATA, NEW_YORK, KOLKATA, NEW_YORK]) editor.zone(zone);
  assert.deepEqual([editor.state().start, editor.state().end], ['2026-10-22T08:47', '2026-10-30T08:47']);
  editor.submit();
  const saved = editor.state().saved.window;
  assert.equal(saved.availabilityStartAt, '2026-10-22T08:47:37.123456789-04:00');
  assert.equal(saved.availabilityEndAt, '2026-10-30T08:47:00-04:00');
  assert.equal(Date.parse(saved.availabilityStartAt), Date.parse(REPORTED_ITEM.start));
});

test('F1: an edited ambiguous fold after zone changes is refused, while untouched folds survive', () => {
  const folded = openEditor({ zone: NEW_YORK, start: '2027-11-07T06:30:37.123456789Z', end: '2027-11-07T06:45:00Z' });
  folded.zone(KOLKATA);
  folded.text('start', '2027-11-07T01:30');
  folded.zone(NEW_YORK);
  folded.submit();
  const refused = folded.state();
  assert.equal(refused.submitted, false, 'no availability request may be transmitted');
  assert.equal(refused.saved, null);
  assert.match(refused.refusalMessage, /happens twice/);
  assert.equal(folded.state().start, '2027-11-07T01:30', 'the refused edit is left in place');

  // Untouched: each recorded occurrence keeps its own offset and its own instant.
  const occurrences = [
    { instant: '2027-11-07T05:30:37.123456789Z', offset: '-04:00' },
    { instant: '2027-11-07T06:30:00Z', offset: '-05:00' }
  ];
  for (const { instant, offset } of occurrences) {
    const editor = openEditor({ zone: NEW_YORK, start: instant, end: '2027-11-07T09:30:00Z' });
    editor.zone(KOLKATA);
    editor.zone(NEW_YORK);
    editor.submit();
    const saved = editor.state().saved.window;
    assert.equal(saved.availabilityStartAt.endsWith(offset), true, `${instant} keeps ${offset}`);
    assert.equal(Date.parse(saved.availabilityStartAt), Date.parse(instant));
  }
  assert.notEqual(Date.parse(occurrences[0].instant), Date.parse(occurrences[1].instant));
});

test('F1: a nonexistent local time reached after a zone change is refused', () => {
  const editor = openEditor({ zone: NEW_YORK, start: '2027-03-14T07:30:00Z', end: '2027-03-14T21:30:00Z' });
  editor.zone(KOLKATA);
  editor.text('start', '2027-03-14T02:30');
  editor.zone(NEW_YORK);
  editor.submit();
  const refused = editor.state();
  assert.equal(refused.submitted, false);
  assert.equal(refused.saved, null);
  assert.match(refused.refusalMessage, /doesn’t exist/);
});

test('F1: a non-hour offset zone and a midnight rollover both resolve correctly', () => {
  const kathmandu = openEditor({ zone: 'Asia/Kathmandu', start: '2026-10-22T12:47:00Z', end: '2026-10-30T12:47:00Z' });
  assert.deepEqual([kathmandu.state().start, kathmandu.state().end], ['2026-10-22T18:32', '2026-10-30T18:32']);
  kathmandu.text('end', '2026-10-30T19:45');
  kathmandu.submit();
  const kathmanduWindow = kathmandu.state().saved.window;
  assert.equal(kathmanduWindow.availabilityEndAt, '2026-10-30T19:45:00+05:45');
  assert.equal(Date.parse(kathmanduWindow.availabilityEndAt), Date.parse('2026-10-30T14:00:00Z'));
  assert.equal(kathmanduWindow.availabilityStartAt, '2026-10-22T18:32:00+05:45');
  assert.equal(Date.parse(kathmanduWindow.availabilityStartAt), Date.parse('2026-10-22T12:47:00Z'));

  // The local date rolls into the next year while the UTC date is still December.
  const rollover = openEditor({ zone: KOLKATA, start: '2026-12-31T18:35:00Z', end: '2027-01-01T18:35:00Z' });
  assert.deepEqual([rollover.state().start, rollover.state().end], ['2027-01-01T00:05', '2027-01-02T00:05']);
  rollover.text('start', '2027-01-01T00:30');
  rollover.zone(NEW_YORK);
  rollover.zone(KOLKATA);
  rollover.submit();
  const rolloverWindow = rollover.state().saved.window;
  assert.equal(rolloverWindow.availabilityStartAt, '2027-01-01T00:30:00+05:30');
  assert.equal(Date.parse(rolloverWindow.availabilityStartAt), Date.parse('2026-12-31T19:00:00Z'));
  assert.equal(Date.parse(rolloverWindow.availabilityEndAt), Date.parse('2027-01-01T18:35:00Z'));
});

test('F1: provenance is scoped to one editor instance, so an abandoned edit cannot leak', () => {
  const first = openEditor(REPORTED_ITEM);
  first.text('start', '2026-10-22T18:17');
  first.zone(KOLKATA);
  assert.equal(first.state().start, '2026-10-22T18:17');

  // Cancelling discards the editor; opening it again starts from the recorded instant again.
  first.cancel();
  assert.equal(first.state().cancelled, true);
  const reopened = openEditor(REPORTED_ITEM);
  assert.equal(reopened.state().start, '2026-10-22T08:47');

  // Another item's editor starts from that item's own recorded values and keeps its own instants.
  const other = openEditor({ zone: KOLKATA, start: '2027-02-14T04:00:00Z', end: '2027-02-16T12:00:00Z' });
  assert.deepEqual([other.state().start, other.state().end], ['2027-02-14T09:30', '2027-02-16T17:30']);
  other.submit();
  const otherWindow = other.state().saved.window;
  assert.equal(otherWindow.availabilityStartAt, '2027-02-14T09:30:00+05:30');
  assert.equal(Date.parse(otherWindow.availabilityStartAt), Date.parse('2027-02-14T04:00:00Z'));
  assert.equal(Date.parse(otherWindow.availabilityEndAt), Date.parse('2027-02-16T12:00:00Z'));
});

test('F1: the two provenances resolve the same text differently, and an unstated one is an edit', () => {
  const displayed = presentation.serverInstantToZoneInput(REPORTED_ITEM.start, NEW_YORK);
  assert.equal(displayed, '2026-10-22T08:47');
  assert.deepEqual(
    presentation.resolveAvailabilityFieldInstant({
      value: displayed,
      provenance: 'RECORDED',
      zoneId: NEW_YORK,
      initialInstant: REPORTED_ITEM.start
    }),
    { value: '2026-10-22T08:47:37.123456789-04:00', error: null }
  );
  assert.deepEqual(
    presentation.resolveAvailabilityFieldInstant({
      value: displayed,
      provenance: 'EDITED',
      zoneId: NEW_YORK,
      initialInstant: REPORTED_ITEM.start
    }),
    { value: '2026-10-22T08:47:00-04:00', error: null }
  );
  // A caller that states no provenance is resolved as an edit, which can never resurrect the recorded
  // instant in place of what the OE typed.
  assert.deepEqual(
    presentation.resolveAvailabilityFieldInstant({
      value: displayed,
      zoneId: NEW_YORK,
      initialInstant: REPORTED_ITEM.start
    }),
    { value: '2026-10-22T08:47:00-04:00', error: null }
  );
  // Only a recorded field is re-expressed by a zone change; an edited one keeps its text.
  assert.equal(
    presentation.rezoneAvailabilityFieldInput({
      value: displayed,
      provenance: 'RECORDED',
      initialInstant: REPORTED_ITEM.start,
      nextZoneId: KOLKATA
    }),
    '2026-10-22T18:17'
  );
  assert.equal(
    presentation.rezoneAvailabilityFieldInput({
      value: displayed,
      provenance: 'EDITED',
      initialInstant: REPORTED_ITEM.start,
      nextZoneId: KOLKATA
    }),
    displayed
  );
});

const F1_SCENARIO_NAMES = SCENARIOS
  .filter((scenario) => scenario.name.startsWith('F1-'))
  .map((scenario) => scenario.name);

test('F1 cross-boundary: the production service transmits the edited field and suppresses refusals', async () => {
  const rows = await runBridgeScenarios(h, F1_SCENARIO_NAMES);
  assert.equal(rows.length, F1_SCENARIO_NAMES.length);
  const byName = new Map(rows.map((row) => [row.name, row]));

  const reported = byName.get('F1-01-start-edited-then-zone-toggles');
  assert.equal(reported.submitted, true);
  assert.equal(reported.displayedStartField, '2026-10-22T18:17');
  const body = JSON.parse(reported.rawBody);
  assert.equal(body.availabilityStartAt, '2026-10-22T18:17:00-04:00');
  assert.equal(Date.parse(body.availabilityStartAt), Date.parse('2026-10-22T22:17:00Z'));
  assert.equal(body.availabilityEndAt, '2026-10-30T08:47:00-04:00');
  assert.equal(Date.parse(body.availabilityEndAt), Date.parse('2026-10-30T12:47:00Z'));
  assert.equal(body.availabilityZoneId, NEW_YORK);
  assert.equal(body.expectedSessionVersion, DETAIL_VERSION);
  assert.equal(body.status, 'CONFIRMED');
  assert.equal(body.availabilitySource, 'PHONE');

  // A refused row must not transmit at all: nothing is built and nothing is sent.
  for (const name of ['F1-07-ambiguous-edited-fold-after-toggles', 'F1-08-gap-after-zone-toggle']) {
    const row = byName.get(name);
    assert.equal(row.submitted, false, `${name} must not reach the command builder`);
    assert.equal(row.rawBody, null, `${name} must transmit nothing`);
    assert.equal(row.command, null);
    assert.equal(row.httpStatus, null);
    assert.ok(row.refusalMessage, `${name} must say why`);
  }

  // Every row that was sent really denotes the instant the OE's own interaction intends.
  for (const row of rows) {
    if (row.rawBody === null) continue;
    const sent = JSON.parse(row.rawBody);
    assert.equal(Date.parse(sent.availabilityStartAt), Date.parse(row.expectedStartInstant), row.name);
    assert.equal(Date.parse(sent.availabilityEndAt), Date.parse(row.expectedEndInstant), row.name);
  }
});

test('F1: the serialized result does not depend on the device time zone', async () => {
  const names = [
    'F1-01-start-edited-then-zone-toggles',
    'F1-04-edited-text-coincides-with-recorded-display',
    'A1-11c-fold-unchanged-second-occurrence'
  ];
  const rows = await runBridgeScenarios(h, names);
  const summarise = (report) => report.map((row) => ({
    name: row.name,
    displayedStartField: row.displayedStartField,
    displayedEndField: row.displayedEndField,
    submitted: row.submitted,
    refusalMessage: row.refusalMessage,
    rawBody: row.rawBody
  }));
  const expected = summarise(rows);
  assert.ok(expected.some((row) => row.rawBody !== null));

  // The same interactions are replayed in fresh processes whose device zone differs from every zone in
  // play, and must serialize byte-identical requests.
  const bridgeUrl = new URL('./support/availabilityCommandBridge.mjs', import.meta.url).href;
  const childSource = [
    `const { runBridge } = await import(${JSON.stringify(bridgeUrl)});`,
    `const report = await runBridge(${JSON.stringify(names)});`,
    'process.stdout.write(JSON.stringify({ tz: process.env.TZ, rows: report.scenarios }));'
  ].join('\n');
  for (const deviceZone of ['America/Los_Angeles', 'Pacific/Auckland']) {
    const child = JSON.parse(execFileSync(process.execPath, ['--input-type=module', '-e', childSource], {
      env: { ...process.env, TZ: deviceZone },
      encoding: 'utf8'
    }));
    assert.equal(child.tz, deviceZone);
    assert.deepEqual(summarise(child.rows), expected, `device zone ${deviceZone}`);
  }
});

test('F1 negative control: the superseded text-equality inference loses the edit', () => {
  /**
   * The A1 rule, transcribed from the reviewed diff: a field counts as the recorded instant when its
   * text equals what that instant looks like in the selected zone. Supplied only here, to show that the
   * regressions above fail against the mechanism they replaced.
   */
  let shownZone = REPORTED_ITEM.zone;
  const supersededInference = {
    resolveAvailabilityFieldInstant: (input) => {
      shownZone = input.zoneId;
      const raw = typeof input.value === 'string' ? input.value.trim() : '';
      const displayed = presentation.serverInstantToZoneInput(input.initialInstant, input.zoneId);
      if (raw.length > 0 && displayed.length > 0 && raw === displayed) {
        const rezoned = presentation.instantToZoneInstant(input.initialInstant, input.zoneId);
        if (rezoned !== null) return { value: rezoned, error: null };
      }
      return presentation.resolveAvailabilityFieldInstant({ ...input, provenance: 'EDITED' });
    },
    rezoneAvailabilityFieldInput: (input) => {
      const raw = typeof input.value === 'string' ? input.value : '';
      const shown = presentation.serverInstantToZoneInput(input.initialInstant, shownZone);
      if (raw.trim().length === 0 || shown.length === 0 || raw.trim() !== shown) return raw;
      shownZone = input.nextZoneId;
      const rezoned = presentation.serverInstantToZoneInput(input.initialInstant, input.nextZoneId);
      return rezoned.length > 0 ? rezoned : raw;
    }
  };

  const editor = openEditor(REPORTED_ITEM, supersededInference);
  editor.text('start', '2026-10-22T18:17');
  editor.zone(KOLKATA);
  editor.zone(NEW_YORK);
  // The superseded rule replaces the OE's 18:17 with the recorded instant's New York display.
  assert.equal(editor.state().start, '2026-10-22T08:47');
  editor.submit();
  const saved = editor.state().saved.window;
  assert.equal(saved.availabilityStartAt, '2026-10-22T08:47:37.123456789-04:00');
  assert.equal(Date.parse(saved.availabilityStartAt), Date.parse(REPORTED_ITEM.start));
  assert.notEqual(Date.parse(saved.availabilityStartAt), Date.parse('2026-10-22T22:17:00Z'));
});
