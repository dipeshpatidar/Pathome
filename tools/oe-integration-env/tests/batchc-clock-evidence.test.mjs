/** Focused, non-connecting BC05 evidence tests. No PostgreSQL, Spring, browser or provider call. */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { assertBc05WindowEvidence, bc05ExpectedWindow } from '../phase2a/batchc-closure.mjs';

const ZONE = 'Europe/Berlin';
const START = '2026-10-21T12:30:00Z';
const END = '2026-10-21T14:30:00Z';
const CONFIRMED = '2026-10-20T08:10:00Z';
const SESSION = 501;
const VERSION = 7;
const REQUEST = 15;

const stop = (over = {}) => ({
  position: 1, itemId: 901, listingId: 3, confirmationStatus: 'CONFIRMED',
  availabilityStartAt: START, availabilityEndAt: END, zoneId: ZONE,
  availabilitySource: 'PHONE', availabilityConfirmedAt: CONFIRMED, offsetMinutes: 0, ...over
});

const sessionItem = (over = {}) => ({
  itemId: 901, listingId: 3, position: 1, sourceRequestId: REQUEST, removedAt: null,
  propertyAvailabilityStartAt: START, propertyAvailabilityEndAt: END,
  propertyAvailabilityZoneId: ZONE, availabilityConfirmedAt: CONFIRMED, ...over
});

const sessionRead = (over = {}) => ({ sessionId: SESSION, version: VERSION, items: [sessionItem()], ...over });

const renderedRow = (stopOver = {}, rowOver = {}) => {
  const value = stop(stopOver);
  const window = bc05ExpectedWindow(value);
  return {
    index: 0, positionText: String(value.position), titleText: 'Property #' + value.listingId,
    windowText: window.text, metaText: 'Source: PHONE', visible: true, hidden: false, ...rowOver
  };
};

const capture = (over = {}) => ({
  identity: { sessionId: SESSION, sessionVersion: VERSION, requestId: REQUEST,
    itemId: 901, listingId: 3, position: 1 },
  timing: { sessionId: SESSION, sessionVersion: VERSION, timing: { stops: [stop()] } },
  sessionReads: { before: sessionRead(), atCapture: sessionRead(), after: sessionRead() },
  browserZone: 'America/New_York',
  rows: [renderedRow()],
  ...over
});

const twoStopCapture = () => {
  const second = stop({ position: 2, itemId: 902, listingId: 4 });
  return capture({
    identity: { sessionId: SESSION, sessionVersion: VERSION, requestId: REQUEST,
      itemId: 902, listingId: 4, position: 2 },
    timing: { sessionId: SESSION, sessionVersion: VERSION, timing: { stops: [stop(), second] } },
    sessionReads: {
      before: sessionRead({ items: [sessionItem(), sessionItem({ itemId: 902, listingId: 4, position: 2 })] }),
      atCapture: sessionRead({ items: [sessionItem(), sessionItem({ itemId: 902, listingId: 4, position: 2 })] }),
      after: sessionRead({ items: [sessionItem(), sessionItem({ itemId: 902, listingId: 4, position: 2 })] })
    },
    rows: [renderedRow({}, { index: 0 }), renderedRow(second, { index: 1 })]
  });
};

const expectFailure = (value, pattern) => assert.throws(() => assertBc05WindowEvidence(value), pattern);

test('correct target row, start, end and owning zone pass with the browser in another zone', () => {
  const result = assertBc05WindowEvidence(capture());
  assert.equal(result.domStartText, 'Wed, 21 Oct, 2026 · 2:30 pm Europe/Berlin');
  assert.equal(result.domEndText, 'Wed, 21 Oct, 2026 · 4:30 pm Europe/Berlin');
  assert.equal(result.browserZone, 'America/New_York');
  assert.equal(result.browserZoneDiffersFromOwningZone, true);
  assert.equal(result.uniquePositionRowCount, 1);
  assert.equal(result.domPropertyTitle, 'Property #3');
});

test('the former dialog-wide assertion false-positives; the corrected assertion rejects it', () => {
  const wrongTarget = renderedRow({}, {
    windowText: 'Wed, 21 Oct, 2026 · 12:30 pm Europe/Berlin → Wed, 21 Oct, 2026 · 2:30 pm Europe/Berlin'
  });
  const fromOtherProperty = renderedRow({ position: 2, itemId: 902, listingId: 4 }, { index: 1 });
  const dialogText = [wrongTarget.windowText, fromOtherProperty.windowText].join(' ');
  assert.equal(dialogText.includes('Europe/Berlin') && dialogText.includes('2:30 pm'), true,
    'the original dialog-wide predicate must reproduce the known false positive');
  const value = twoStopCapture();
  value.identity.itemId = 901;
  value.identity.listingId = 3;
  value.identity.position = 1;
  value.rows[0] = wrongTarget;
  expectFailure(value, /Property-window start text mismatch/);
});

test('correct expected text on another property cannot satisfy the target row', () => {
  const value = twoStopCapture();
  value.identity.itemId = 901;
  value.identity.listingId = 3;
  value.identity.position = 1;
  value.rows[0] = renderedRow({}, {
    windowText: 'Wed, 21 Oct, 2026 · 12:30 pm Europe/Berlin → Wed, 21 Oct, 2026 · 2:30 pm Europe/Berlin'
  });
  expectFailure(value, /Property-window start text mismatch/);
});

test('correct clock in the wrong IANA zone fails', () => {
  const value = capture();
  value.rows[0].windowText = value.rows[0].windowText.replaceAll(ZONE, 'America/New_York');
  expectFailure(value, /Property-window start text mismatch/);
});

test('correct start with an incorrect end fails on the complete endpoint', () => {
  const value = capture();
  value.rows[0].windowText = bc05ExpectedWindow(stop({ availabilityEndAt: '2026-10-21T14:45:00Z' })).text;
  expectFailure(value, /Property-window end text mismatch/);
});

test('correct end with an incorrect start fails on the complete endpoint', () => {
  const value = capture();
  value.rows[0].windowText = bc05ExpectedWindow(stop({ availabilityStartAt: '2026-10-21T12:00:00Z' })).text;
  expectFailure(value, /Property-window start text mismatch/);
});

test('stale Session version and a replaced Session fail safely', () => {
  const stale = capture();
  stale.sessionReads.atCapture = sessionRead({ version: VERSION - 1 });
  expectFailure(stale, /Session version is stale/);

  const replaced = capture();
  replaced.sessionReads.after = sessionRead({ sessionId: 999 });
  expectFailure(replaced, /belongs to another Session/);

  const foreign = capture();
  foreign.timing.sessionVersion = VERSION - 1;
  expectFailure(foreign, /another Session or version/);
});

test('same clock on two stops targets only the uniquely identified stop', () => {
  const result = assertBc05WindowEvidence(twoStopCapture());
  assert.equal(result.position, 2);
  assert.equal(result.domRowIndex, 1);
  assert.equal(result.domPropertyTitle, 'Property #4');
  assert.equal(result.domWindowText, 'Wed, 21 Oct, 2026 · 2:30 pm Europe/Berlin → Wed, 21 Oct, 2026 · 4:30 pm Europe/Berlin');
});

test('same clock on another stop with the wrong property identity fails', () => {
  const value = twoStopCapture();
  value.rows[1].titleText = 'Property #5';
  expectFailure(value, /row is missing/);
});

test('missing or duplicated target rows fail rather than skipping', () => {
  const missing = capture();
  missing.rows = [];
  expectFailure(missing, /row count differs/);

  const ambiguous = capture();
  const second = stop({ position: 2, itemId: 902, listingId: 4 });
  ambiguous.identity = { ...ambiguous.identity, itemId: 902, listingId: 4, position: 2 };
  ambiguous.timing.timing.stops = [stop(), second];
  ambiguous.sessionReads = {
    before: sessionRead({ items: [sessionItem(), sessionItem({ itemId: 902, listingId: 4, position: 2 })] }),
    atCapture: sessionRead({ items: [sessionItem(), sessionItem({ itemId: 902, listingId: 4, position: 2 })] }),
    after: sessionRead({ items: [sessionItem(), sessionItem({ itemId: 902, listingId: 4, position: 2 })] })
  };
  ambiguous.rows = [renderedRow({}, { index: 0 }), renderedRow({}, { index: 1 })];
  expectFailure(ambiguous, /do not map one-to-one/);
});

test('a hidden target row never counts as rendered evidence', () => {
  const value = capture();
  value.rows[0].hidden = true;
  expectFailure(value, /hidden or not rendered/);
});

test('a changed property-window revision rejects stale evidence', () => {
  const value = capture();
  value.timing.timing.stops[0].availabilityConfirmedAt = '2026-10-20T09:10:00Z';
  expectFailure(value, /revision changed/);
});

test('an initial window edited before capture validates the precise capture phase', () => {
  const oldStop = stop({ availabilityStartAt: '2026-10-21T10:30:00Z',
    availabilityEndAt: '2026-10-21T12:30:00Z', availabilityConfirmedAt: '2026-10-20T07:00:00Z' });
  const oldItem = sessionItem({ propertyAvailabilityStartAt: oldStop.availabilityStartAt,
    propertyAvailabilityEndAt: oldStop.availabilityEndAt,
    availabilityConfirmedAt: oldStop.availabilityConfirmedAt });
  const value = capture({
    sessionReads: {
      before: sessionRead({ items: [oldItem], version: VERSION - 1 }),
      atCapture: sessionRead(),
      after: sessionRead()
    }
  });
  const result = assertBc05WindowEvidence(value);
  assert.equal(result.domStartText, 'Wed, 21 Oct, 2026 · 2:30 pm Europe/Berlin');

  const staleApi = capture();
  staleApi.timing.timing.stops = [oldStop];
  staleApi.sessionReads.before = sessionRead({ items: [oldItem], version: VERSION - 1 });
  expectFailure(staleApi, /differs from the availability response/);
});

test('DST fold, non-hour offset and midnight rollover keep owning-zone precision', () => {
  const kolkata = bc05ExpectedWindow({ zoneId: 'Asia/Kolkata',
    availabilityStartAt: '2026-10-21T12:30:00Z', availabilityEndAt: '2026-10-21T18:30:00Z' });
  assert.match(kolkata.startText, /6:00 pm Asia\/Kolkata$/);
  assert.match(kolkata.endText, /12:00 am Asia\/Kolkata$/);
  assert.doesNotMatch(kolkata.text, /[+-]\d{2}:\d{2}\)/, 'a non-repeated non-hour offset is not disambiguated');

  const rollover = bc05ExpectedWindow({ zoneId: ZONE,
    availabilityStartAt: '2026-10-21T20:30:00Z', availabilityEndAt: '2026-10-21T22:30:00Z' });
  assert.match(rollover.startText, /Wed, 21 Oct, 2026 · 10:30 pm Europe\/Berlin$/);
  assert.match(rollover.endText, /Thu, 22 Oct, 2026 · 12:30 am Europe\/Berlin$/);

  const fold = bc05ExpectedWindow({ zoneId: 'America/New_York',
    availabilityStartAt: '2026-11-01T05:30:00Z', availabilityEndAt: '2026-11-01T06:30:00Z' });
  assert.match(fold.startText, /1:30 am America\/New_York \(-04:00\)$/);
  assert.match(fold.endText, /1:30 am America\/New_York \(-05:00\)$/);

  const foldStop = stop({ zoneId: 'America/New_York', availabilityStartAt: '2026-11-01T05:30:00Z',
    availabilityEndAt: '2026-11-01T06:30:00Z', availabilityConfirmedAt: null });
  const foldItem = sessionItem({ propertyAvailabilityZoneId: 'America/New_York',
    propertyAvailabilityStartAt: foldStop.availabilityStartAt,
    propertyAvailabilityEndAt: foldStop.availabilityEndAt, availabilityConfirmedAt: null });
  const result = assertBc05WindowEvidence(capture({
    timing: { sessionId: SESSION, sessionVersion: VERSION, timing: { stops: [foldStop] } },
    sessionReads: { before: sessionRead({ items: [foldItem] }),
      atCapture: sessionRead({ items: [foldItem] }), after: sessionRead({ items: [foldItem] }) },
    browserZone: 'Europe/Berlin',
    rows: [renderedRow(foldStop)]
  }));
  assert.match(result.domWindowText, /\(-04:00\).*\(-05:00\)$/);
});

test('incomplete or malformed evidence is refused before any comparison', () => {
  expectFailure(null, /Incomplete BC05 capture/);
  expectFailure(capture({ rows: null }), /Incomplete BC05 capture/);
  const noStop = capture();
  noStop.timing.timing.stops = [];
  expectFailure(noStop, /no property windows/);
  const reversed = capture();
  reversed.timing.timing.stops[0].availabilityEndAt = reversed.timing.timing.stops[0].availabilityStartAt;
  expectFailure(reversed, /incomplete or reversed/);
});
