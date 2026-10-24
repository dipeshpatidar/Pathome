import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import { resolve as resolveOperationsModule } from './support/operationsServiceLoader.mjs';
import {
  formatOperationsAvailabilityDateTime,
  formatOperationsAvailabilityWindow,
  formatOperationsZonedDateTime,
  geAvailabilityResultCopy,
  instantToZoneInstant,
  isRepeatedOperationsLocalTime,
  recommendationStatusHint,
  sameOperationsRootInstance,
  toGeAvailabilityView
} from '../utils/operationsWorkspacePresentation.ts';

registerHooks({ resolve: resolveOperationsModule });

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const picker = read('../components/operations/OperationsGePicker.tsx');
const availability = read('../components/operations/OperationsGeAvailability.tsx');
const scheduleFlow = read('../components/operations/OperationsScheduleFlow.tsx');
const workspace = read('../components/operations/OperationsWorkspace.tsx');
const workLists = read('../components/operations/OperationsWorkLists.tsx');

test('C2 recommendation copy uses the statuses the backend actually emits', () => {
  assert.match(recommendationStatusHint('NO_ELIGIBLE_GE'), /potential City scheduling population/);
  assert.match(recommendationStatusHint('NO_FEASIBLE_TIME'), /bounded evaluation/);
  assert.match(recommendationStatusHint('PROPERTY_AVAILABILITY_INCOMPLETE'), /property-input gap/);
  assert.match(recommendationStatusHint('INSUFFICIENT_TENANT_AVAILABILITY'), /tenant/i);
  assert.match(recommendationStatusHint('SESSION_NOT_SCHEDULABLE'), /Every active stop/);
  assert.equal(recommendationStatusHint('RECOMMENDATION_AVAILABLE'), null);
  const unknown = recommendationStatusHint('SOME_NEW_STATUS');
  assert.match(unknown, /does not recognise/);
  assert.doesNotMatch(unknown, /SOME_NEW_STATUS/);
});

test('availability projection keeps only allow-listed safe fields and dedupes entries', () => {
  const view = toGeAvailabilityView({
    sessionId: 11,
    sessionVersion: 4,
    evaluatedAt: '2026-10-19T08:00:00Z',
    schedulingPolicyVersion: '2cb-v1',
    visitPolicyVersion: 3,
    planInputFingerprint: 'a'.repeat(64),
    planStatus: 'NO_FEASIBLE_TIME',
    planReady: true,
    planExplanationCodes: ['RESERVATION_SEARCH_BOUND_EXCEEDED'],
    evaluationBounded: true,
    pageSize: 20,
    returnedEntryCount: 2,
    nextCursor: 'cursor',
    timing: {
      configuredStopDwellMinutes: 15,
      maximumSessionDurationMinutes: 60,
      calculatedItineraryDurationMinutes: 35,
      durationScope: 'DWELL_PLUS_BETWEEN_STOP_TRAVEL',
      tenantWindow: {
        startAt: '2026-10-20T04:00:00Z',
        endAt: '2026-10-20T08:00:00Z',
        zoneId: 'Asia/Kolkata',
        preferredTimes: ['2026-10-20T04:00:00Z']
      },
      stops: [
        { position: 2, itemId: 22, listingId: 202, confirmationStatus: 'CONFIRMED', offsetMinutes: 20 },
        { position: 1, itemId: 21, listingId: 201, confirmationStatus: 'CONFIRMED', offsetMinutes: 0 }
      ],
      betweenStopTravel: [{ fromPosition: 1, toPosition: 2, minutes: 20, source: 'SAME_CITY', confidence: 'MEDIUM' }],
      planningWindowBounded: false
    },
    entries: [
      {
        groundExecutiveUserId: 7,
        displayName: 'Recorded Name',
        resultCode: 'FEASIBLE_OPTION_FOUND',
        phone: 'must-not-render',
        email: 'must-not-render@example.test'
      },
      { groundExecutiveUserId: 7, displayName: 'Duplicate', resultCode: 'COVERAGE_NOT_SUITABLE' },
      { groundExecutiveUserId: 8, displayName: null, resultCode: 'UNKNOWN_CODE' },
      { groundExecutiveUserId: 0, displayName: 'Invalid', resultCode: 'FEASIBLE_OPTION_FOUND' }
    ]
  });
  assert.ok(view);
  assert.deepEqual(view.entries.map((entry) => entry.groundExecutiveUserId), [7, 8]);
  assert.equal(view.entries[0].groundExecutiveName, 'Recorded Name');
  assert.equal('phone' in view.entries[0], false);
  assert.equal('email' in view.entries[0], false);
  assert.deepEqual(view.timing.stops.map((stop) => stop.position), [1, 2]);
  assert.equal(view.timing.betweenStopTravel[0].minutes, 20);
  assert.equal(view.planReady, true);
  assert.equal(view.entries[1].resultCode, 'UNKNOWN_CODE');
});

test('availability maps the real displayName field and never the recommendation-only key', () => {
  const view = toGeAvailabilityView({
    sessionId: 11,
    sessionVersion: 4,
    entries: [
      {
        groundExecutiveUserId: 7,
        displayName: '  राहुल वर्मा  ',
        groundExecutiveName: 'Recommendation field must not be read',
        resultCode: 'FEASIBLE_OPTION_FOUND'
      },
      {
        groundExecutiveUserId: 8,
        groundExecutiveName: 'Legacy availability fixture',
        resultCode: 'NO_FEASIBLE_OPTION_FOUND'
      },
      {
        groundExecutiveUserId: 9,
        displayName: '<img src=x onerror=alert(1)>',
        resultCode: 'UNKNOWN_CODE'
      }
    ]
  });
  // The mapper preserves the server text verbatim; the view trims it only for display.
  assert.equal(view.entries[0].groundExecutiveName, '  राहुल वर्मा  ');
  // Discriminating control: the former frontend key now maps to no name, so this assertion fails if
  // the mapper regresses to `groundExecutiveName`.
  assert.equal(view.entries[1].groundExecutiveName, null);
  // The mapper preserves text exactly; rendering escapes it as text rather than interpreting HTML.
  assert.equal(view.entries[2].groundExecutiveName, '<img src=x onerror=alert(1)>');
});

test('availability windows use the owning zone, including DST folds', () => {
  const instant = '2026-10-20T08:00:00Z';
  assert.match(formatOperationsZonedDateTime(instant, 'Asia/Kolkata'), /1:30 pm Asia\/Kolkata/);
  assert.match(formatOperationsZonedDateTime(instant, 'Europe/Berlin'), /10:00 am Europe\/Berlin/);
  assert.match(formatOperationsZonedDateTime(instant, 'America/New_York'), /4:00 am America\/New_York/);
  assert.match(formatOperationsZonedDateTime(instant, 'Asia/Kathmandu'), /1:45 pm Asia\/Kathmandu/);

  const berlinWindow = formatOperationsAvailabilityWindow(
    '2026-10-20T08:00:00Z',
    '2026-10-20T10:00:00Z',
    'Europe/Berlin'
  );
  assert.match(berlinWindow, /10:00 am Europe\/Berlin/);
  assert.match(berlinWindow, /12:00 pm Europe\/Berlin/);
  assert.doesNotMatch(berlinWindow, /1:30 pm/);

  // A fall-back fold repeats 01:30 in New York. The actual offset distinguishes the two instants.
  assert.equal(isRepeatedOperationsLocalTime('2026-11-01T05:30:00Z', 'America/New_York'), true);
  assert.equal(isRepeatedOperationsLocalTime('2026-11-01T06:30:00Z', 'America/New_York'), true);
  assert.match(
    formatOperationsAvailabilityDateTime('2026-11-01T05:30:00Z', 'America/New_York'),
    /1:30 am America\/New_York \(-04:00\)/
  );
  assert.match(
    formatOperationsAvailabilityDateTime('2026-11-01T06:30:00Z', 'America/New_York'),
    /1:30 am America\/New_York \(-05:00\)/
  );
  assert.equal(instantToZoneInstant(instant, 'Europe/Berlin'), '2026-10-20T10:00:00+02:00');

  assert.equal(formatOperationsAvailabilityDateTime('not-an-instant', 'Europe/Berlin'), null);
  assert.equal(formatOperationsAvailabilityDateTime(instant, 'Not/AZone'), null);
  assert.equal(formatOperationsAvailabilityWindow(instant, null, 'Europe/Berlin'), null);
  assert.equal(instant, '2026-10-20T08:00:00Z', 'formatting never mutates the backend instant');
});

test('a denial is bound to one exact mounted root instance', () => {
  const captured = { targetType: 'SESSION', targetId: 3108, mountGeneration: 4 };
  assert.equal(sameOperationsRootInstance(captured, { ...captured }), true);
  assert.equal(sameOperationsRootInstance(captured,
    { targetType: 'REQUEST', targetId: 3108, mountGeneration: 4 }), false,
    'a Request and a Session with the same numeric id are different roots');
  assert.equal(sameOperationsRootInstance(captured,
    { targetType: 'SESSION', targetId: 4109, mountGeneration: 4 }), false);
  assert.equal(sameOperationsRootInstance(captured,
    { targetType: 'SESSION', targetId: 3108, mountGeneration: 5 }), false,
    'a re-opened Session is a new mounted instance');
  assert.equal(sameOperationsRootInstance(captured, null), false);
  assert.equal(sameOperationsRootInstance(null, captured), false);
});

test('the five public outcome codes map to distinct truthful labels', () => {
  const feasible = geAvailabilityResultCopy('FEASIBLE_OPTION_FOUND');
  assert.equal(feasible.feasible, true);
  assert.match(feasible.detail, /not a booking/);
  assert.match(geAvailabilityResultCopy('COVERAGE_NOT_SUITABLE').detail, /does not cover/i);
  assert.match(geAvailabilityResultCopy('NO_FEASIBLE_OPTION_FOUND').detail, /Private scheduling reasons are not shown/);
  assert.match(geAvailabilityResultCopy('PLAN_NOT_READY').detail, /incomplete or unsuitable/);
  assert.match(geAvailabilityResultCopy('EVALUATION_INCOMPLETE').detail, /bounded search/);
  const unknown = geAvailabilityResultCopy('RAW_PRIVATE_REASON');
  assert.equal(unknown.feasible, false);
  assert.equal(unknown.label, 'Availability not confirmed');
  assert.doesNotMatch(unknown.detail, /RAW_PRIVATE_REASON/);
});

test('C1 copy never claims that a continuation means more feasible Ground Executives', () => {
  assert.match(picker, /label: 'Matching Ground Executives'/);
  assert.match(picker, /Shows GEs with an evaluated time for this visit plan\. It does not show every employee\./);
  assert.match(picker, /More records remain to check\./);
  assert.match(picker, /No matching visit time was found in this check/);
  assert.doesNotMatch(picker, /More are available/);
  assert.doesNotMatch(picker, /label: 'Browse all'/);
});

test('the information view has no booking action and explains its informational authority', () => {
  assert.match(availability, /This view shows who may be available for this City/);
  assert.match(availability, /To book, choose a newly checked option/);
  assert.match(availability, /First-stop arrival travel and later-commitment protection are/);
  assert.doesNotMatch(availability, /onSelect/);
  assert.doesNotMatch(availability, /groundExecutiveUserId\s*:\s*entry\.groundExecutiveUserId,[\s\S]{0,200}scheduledAt/);
});

test('runtime timing is reachable from both the picker and a blocked visit plan', () => {
  assert.match(scheduleFlow, /View plan timing and GE availability/);
  assert.match(scheduleFlow, /loadAvailability=\{loadAvailability\}/);
  assert.match(availability, /Configured dwell per stop/);
  assert.match(availability, /Calculated itinerary duration/);
  assert.match(availability, /Maximum visit plan duration/);
  assert.match(availability, /Between-property allowances/);
  assert.match(availability, /not a live ETA/);
});

test('work-list empty states distinguish owned, unclaimed and filtered truth', () => {
  assert.match(workspace, /You don’t own any work in your current teams\./);
  assert.match(workspace, /No unclaimed work was returned/);
  assert.match(workspace, /current authorized scope/);
  assert.match(workspace, /Work owned by another coordinator is not shown here as available\./);
  assert.match(workspace, /New intake appears only after an authorized Team routes it\./);
  assert.match(workspace, /Your authorized scope has \$\{queue\.unfilteredTotalCount\}/);
  assert.match(workLists, /A Team filter hides records that are still authorized in your other teams\./);
  assert.doesNotMatch(workspace, /Request #7/);
});
