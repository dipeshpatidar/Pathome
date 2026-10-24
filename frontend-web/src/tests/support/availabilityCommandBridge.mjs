/**
 * Cross-boundary bridge for the property-availability time-zone contract (Batch A1, plus the A1-F1
 * provenance correction).
 *
 * Each row is executed through the real editor. `AvailabilityEditor`, the component declared in
 * `OperationsDraftItinerary.tsx`, is extracted from its TypeScript source and driven by a synchronous
 * React hook harness, so a row applies the component's own `onChange`/`onSubmit` handlers in an
 * explicit order — typing a field, then changing the time zone, then changing it back, and so on. The
 * payload the editor hands to `onSave` is then carried along the production path: `setAvailabilityCommand`
 * builds the command and `operationsWorkspaceService.setSessionItemAvailability` transmits it, with the
 * service's raw `JSON.stringify` request text reported verbatim. A backend test can therefore push
 * exactly those bytes through the real Jackson command DTO and the real `SchedulingWindowValidator`
 * instead of a payload written to look correct by hand.
 *
 * Layers, stated as they are:
 *   - editor: the real production handlers and the real production resolvers. It is a synchronous hook
 *     harness over the extracted component, not a mounted browser — this repository's test stack has no
 *     DOM renderer, so browser/Safari event normalisation is not covered here;
 *   - serialization: the real production command builder and the real production service transport;
 *   - transport: the network is stubbed by the shared action harness, so `httpStatus: 200` is synthetic
 *     and proves nothing about a live HTTP response, persistence or a device.
 *
 * Exports `SCENARIOS`, `runBridgeScenarios(harness, names)` and the editor driver so the frontend suites
 * can assert the same ordered event flows directly, without re-implementing any conversion.
 *
 * Usage: node frontend-web/src/tests/support/availabilityCommandBridge.mjs
 * Stdout: one JSON document, `{ scenarioCount, acceptedCount, refusedCount, scenarios: [...] }`.
 */
import { createRequire } from 'node:module';
import { readFileSync, realpathSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import { installOperationsActionHarness } from './operationsActionHarness.mjs';

const require = createRequire(new URL('../../../package.json', import.meta.url));
const ts = require('typescript');
const React = require('react');

const ITINERARY_COMPONENT = fileURLToPath(
  new URL('../../components/operations/OperationsDraftItinerary.tsx', import.meta.url)
);

export const SESSION_ID = 3108;
export const ITEM_ID = 91;
export const DETAIL_VERSION = 6;
export const ACCOUNT = { id: 41, role: 'EMPLOYEE', token: 'token-availability-bridge' };
/**
 * A fixed reference clock. The future-window rule is not what this bridge measures, and the fixtures
 * must keep the same meaning on every run rather than expiring against the wall clock.
 */
export const CLOCK = new Date('2026-10-01T00:00:00Z');
export const RESPONSE_SOURCE = 'PHONE';

/** One item of the authoritative response, in the field names the operations API answers with. */
export const availabilityItemFixture = (overrides = {}) => ({
  itemId: ITEM_ID,
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
  propertyAvailabilityStartAt: null,
  propertyAvailabilityEndAt: null,
  propertyAvailabilityZoneId: null,
  propertyAvailabilitySource: null,
  ...overrides
});

export const availabilitySessionFixture = (items, overrides = {}) => ({
  sessionId: SESSION_ID,
  status: 'DRAFT',
  version: DETAIL_VERSION,
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

/**
 * The production `AvailabilityEditor` component, compiled from its own declaration.
 *
 * The body is read from the component source rather than re-implemented: if its handlers, its state or
 * its resolver wiring change, the rows below change with it. Compiling once and re-running it per
 * scenario keeps this cheap.
 */
const compileAvailabilityEditor = () => {
  const source = ts.createSourceFile(
    ITINERARY_COMPONENT,
    readFileSync(ITINERARY_COMPONENT, 'utf8'),
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TSX
  );
  let initializer = null;
  const visit = (node) => {
    if (ts.isVariableDeclaration(node) && node.name.getText(source) === 'AvailabilityEditor') {
      initializer = node.initializer.getText(source);
    }
    ts.forEachChild(node, visit);
  };
  visit(source);
  if (initializer === null) {
    throw new Error(`AvailabilityEditor is no longer declared as a component in ${ITINERARY_COMPONENT}`);
  }
  return ts.transpileModule(`globalThis.__availabilityEditor = ${initializer};`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React }
  }).outputText;
};

const COMPILED_EDITOR = compileAvailabilityEditor();

/**
 * The workspace-owned draft, constructed by the real production factory for one item. The bridge
 * drives the same controlled shape the workspace passes down, so an edit is a draft write rather than
 * component-local state the production lifecycle no longer owns.
 */
export const createAvailabilityDraftFor = (presentation, props) => presentation.createAvailabilityDraft({
  sessionId: props.session.sessionId,
  sessionVersion: props.session.version,
  item: props.item,
  identity: 'bridge|identity',
  authorizationGeneration: 1,
  editorGeneration: 1
});

/**
 * Opens the real editor for one item and returns the controls an OE would use.
 *
 * The hook harness stores `useState` cells in call order and re-runs the component body on each read,
 * which is what a re-render does for this component: every hook is called unconditionally in the same
 * order, and `useMemo` is recomputed. Each driver owns its own cells, so a driver is one mount of the
 * editor for one item.
 *
 * `overrides` exists for one purpose: a negative-control test substitutes the superseded provenance
 * inference for the production helpers to show that the regressions below fail against it. Production
 * code is never modified for testability.
 */
export const openAvailabilityEditor = (presentation, props, overrides = {}) => {
  const cells = [];
  let nextCell = 0;
  let saved = null;
  let cancelled = false;
  let draft = props.draft ?? createAvailabilityDraftFor(presentation, props);

  const context = {
    ...presentation,
    setAvailabilityCommand: (session, itemId, choice, window) =>
      presentation.setAvailabilityCommand(session, itemId, choice, window, CLOCK),
    React,
    useMemo: (factory) => factory(),
    useEffect: () => {},
    useRef: () => ({ current: null }),
    window: { setTimeout: (fn) => fn() },
    useState: (initial) => {
      const cell = nextCell++;
      if (!(cell in cells)) cells[cell] = typeof initial === 'function' ? initial() : initial;
      return [
        cells[cell],
        (next) => {
          cells[cell] = typeof next === 'function' ? next(cells[cell]) : next;
        }
      ];
    },
    ...overrides
  };
  vm.runInNewContext(COMPILED_EDITOR, context);

  const editorProps = {
    ...props,
    draft,
    onChange: (values) => {
      draft = { ...draft, ...values };
      // Re-bind so the next render reads the values the OE just produced.
      editorProps.draft = draft;
    },
    onSave: async (windowInput) => {
      saved = { choice: draft.choice, window: windowInput };
      return { status: 'saved', message: null };
    },
    onCancel: () => {
      cancelled = true;
    }
  };
  const elements = () => {
    nextCell = 0;
    const found = [];
    const walk = (node) => {
      if (!node || typeof node !== 'object') return;
      found.push(node);
      React.Children.toArray(node.props?.children).forEach(walk);
    };
    walk(context.__availabilityEditor(editorProps));
    return found;
  };
  const control = (id) => {
    const element = elements().find((node) => node.props?.id === id);
    if (!element) throw new Error(`the availability editor no longer renders #${id}`);
    return element;
  };
  const form = () => {
    const element = elements().find((node) => node.props?.className === 'oe-avail-editor');
    if (!element) throw new Error('the availability editor no longer renders its form');
    return element;
  };
  const fieldValue = (suffix) => {
    const element = elements().find((node) => node.props?.id === `oe-avail-${props.item.itemId}-${suffix}`);
    return element ? element.props.value : null;
  };

  return {
    /** Typing into the "available from" (`start`) or "available until" (`end`) field. */
    text(field, value) {
      control(`oe-avail-${props.item.itemId}-${field}`).props.onChange({ target: { value } });
    },
    /** Choosing an item on the time-zone selector, exactly as the OE's change event does. */
    zone(zoneId) {
      control(`oe-avail-${props.item.itemId}-zone`).props.onChange({ target: { value: zoneId } });
    },
    /** Pressing "Save availability". */
    submit() {
      saved = null;
      form().props.onSubmit({ preventDefault() {} });
    },
    /** Pressing "Cancel" or Escape, which discards the whole editor. */
    cancel() {
      form().props.onKeyDown({ key: 'Escape', stopPropagation() {} });
    },
    /** What the editor shows and does right now. */
    state() {
      const nodes = elements();
      const reason = nodes.find((node) =>
        node.props?.role === 'alert'
        && node.props.id === undefined
        && typeof node.props.children === 'string');
      return {
        start: fieldValue('start'),
        end: fieldValue('end'),
        zone: fieldValue('zone'),
        submitted: saved !== null,
        saved,
        cancelled,
        refusalMessage: reason ? reason.props.children : null,
        draft
      };
    },
    /** The workspace-owned draft, for lifecycle assertions (raw text and provenance). */
    get draft() {
      return draft;
    },
    /** Replaces the draft the next render receives, as a refresh or retained state would. */
    setDraft(next) {
      draft = next;
      editorProps.draft = next;
    }
  };
};

/** The exact bytes of every request the production service put on the wire, in order. */
const transmittedBodies = [];

const KOLKATA = 'Asia/Kolkata';
const NEW_YORK = 'America/New_York';

/** An ordered interaction with the editor: the OE's own sequence, never a rearranged one. */
const typed = (field, value) => ({ type: 'text', field, value });
const zone = (zoneId) => ({ type: 'zone', zoneId });

/**
 * The regression matrix. Two families:
 *  - `A1-*` — the original contract rows (unchanged zones, edits, DST folds and gaps, precision,
 *    rollovers), now driven through the editor instead of through the helpers by hand;
 *  - `F1-*` — the edited-field provenance rows: an edit followed by zone changes, an edit whose text
 *    coincides with what the recorded instant looks like in another zone, edits on either field, and
 *    the DST fold/gap cases reached after zone changes.
 */
export const SCENARIOS = [
  {
    name: 'A1-01-both-unchanged',
    note: 'Both fields untouched, the response delivered UTC Z instants for a property recorded in IST.',
    expectation: 'accepted',
    recordedZoneId: KOLKATA,
    recordedStart: '2026-10-22T12:47:00Z',
    recordedEnd: '2026-10-30T12:47:00Z',
    events: []
  },
  {
    name: 'A1-02-start-edited-end-unchanged',
    note: 'Editing only the start must not break the untouched end.',
    expectation: 'accepted',
    recordedZoneId: KOLKATA,
    recordedStart: '2026-10-22T12:47:00Z',
    recordedEnd: '2026-10-30T12:47:00Z',
    events: [typed('start', '2026-10-23T13:45')],
    expectedStartInstant: '2026-10-23T08:15:00Z'
  },
  {
    name: 'A1-03-end-edited-start-unchanged',
    note: 'Editing only the end must not break the untouched start.',
    expectation: 'accepted',
    recordedZoneId: KOLKATA,
    recordedStart: '2026-10-22T12:47:00Z',
    recordedEnd: '2026-10-30T12:47:00Z',
    events: [typed('end', '2026-10-30T19:45')],
    expectedEndInstant: '2026-10-30T14:15:00Z'
  },
  {
    name: 'A1-04-both-edited',
    note: 'Both fields edited: the ordinary wall-clock rules apply to each.',
    expectation: 'accepted',
    recordedZoneId: KOLKATA,
    recordedStart: '2026-10-22T12:47:00Z',
    recordedEnd: '2026-10-30T12:47:00Z',
    events: [typed('start', '2026-10-23T13:45'), typed('end', '2026-10-23T19:45')],
    expectedStartInstant: '2026-10-23T08:15:00Z',
    expectedEndInstant: '2026-10-23T14:15:00Z'
  },
  {
    name: 'A1-05-zone-changed-only',
    note: 'Changing only the zone must re-label the same instants, never move them.',
    expectation: 'accepted',
    recordedZoneId: NEW_YORK,
    recordedStart: '2026-10-22T12:47:00Z',
    recordedEnd: '2026-10-30T12:47:00Z',
    events: [zone(KOLKATA)]
  },
  {
    name: 'A1-06-kolkata-offset',
    note: 'The zone’s own +05:30 representation, not the server’s Z.',
    expectation: 'accepted',
    recordedZoneId: KOLKATA,
    recordedStart: '2026-10-22T12:47:00Z',
    recordedEnd: '2026-10-30T12:47:00Z',
    events: []
  },
  {
    name: 'A1-07a-dst-zone-summer',
    note: 'A DST-observing zone uses the offset the instant actually had (EDT −04:00).',
    expectation: 'accepted',
    recordedZoneId: NEW_YORK,
    recordedStart: '2027-07-14T13:30:00Z',
    recordedEnd: '2027-07-14T17:30:00Z',
    events: []
  },
  {
    name: 'A1-07b-dst-zone-winter',
    note: 'The same zone in winter (EST −05:00).',
    expectation: 'accepted',
    recordedZoneId: NEW_YORK,
    recordedStart: '2027-01-14T14:30:00Z',
    recordedEnd: '2027-01-14T18:30:00Z',
    events: []
  },
  {
    name: 'A1-08-subsecond-precision',
    note: 'Seconds and nine fractional digits must survive an unchanged save.',
    expectation: 'accepted',
    recordedZoneId: KOLKATA,
    recordedStart: '2026-10-22T12:47:37.123456789Z',
    recordedEnd: '2026-10-30T12:47:00.5Z',
    events: []
  },
  {
    name: 'A1-09-midnight-rollover',
    note: 'The zone’s date differs from the UTC date across midnight.',
    expectation: 'accepted',
    recordedZoneId: KOLKATA,
    recordedStart: '2027-02-14T18:30:00Z',
    recordedEnd: '2027-02-16T12:00:00Z',
    events: []
  },
  {
    name: 'A1-10-gap-refused',
    note: 'A local time the zone never reaches is still refused.',
    expectation: 'refused',
    recordedZoneId: NEW_YORK,
    recordedStart: '2027-03-14T07:30:00Z',
    recordedEnd: '2027-03-14T21:30:00Z',
    events: [typed('start', '2027-03-14T02:30')]
  },
  {
    name: 'A1-11a-fold-edited-refused',
    note: 'An edited repeated local time is refused rather than given one occurrence. The recorded start '
      + 'displays 00:30, so 01:15 is a genuine edit and not the recorded text again.',
    expectation: 'refused',
    recordedZoneId: NEW_YORK,
    recordedStart: '2027-11-07T04:30:00Z',
    recordedEnd: '2027-11-07T09:30:00Z',
    events: [typed('start', '2027-11-07T01:15')]
  },
  {
    name: 'A1-11b-fold-unchanged-first-occurrence',
    note: 'An unchanged instant keeps the first of the two 01:30 occurrences (EDT −04:00).',
    expectation: 'accepted',
    recordedZoneId: NEW_YORK,
    recordedStart: '2027-11-07T05:30:00Z',
    recordedEnd: '2027-11-07T05:45:00Z',
    events: []
  },
  {
    name: 'A1-11c-fold-unchanged-second-occurrence',
    note: 'An unchanged instant keeps the second occurrence (EST −05:00) an hour later.',
    expectation: 'accepted',
    recordedZoneId: NEW_YORK,
    recordedStart: '2027-11-07T06:30:00Z',
    recordedEnd: '2027-11-07T06:45:00Z',
    events: []
  },
  {
    name: 'A1-12-authoritative-session-version',
    note: 'The command carries the authoritative Session version the editor was rendered from.',
    expectation: 'accepted',
    recordedZoneId: KOLKATA,
    recordedStart: '2026-10-22T12:47:00Z',
    recordedEnd: '2026-10-30T12:47:00Z',
    events: []
  },
  {
    name: 'F1-01-start-edited-then-zone-toggles',
    note: 'The reported defect: type a start, switch to IST, switch back to New York. The typed start must '
      + 'survive both switches and decide the saved instant.',
    expectation: 'accepted',
    recordedZoneId: NEW_YORK,
    recordedStart: '2026-10-22T12:47:37.123456789Z',
    recordedEnd: '2026-10-30T12:47:00Z',
    events: [typed('start', '2026-10-22T18:17'), zone(KOLKATA), zone(NEW_YORK)],
    expectedStartInstant: '2026-10-22T22:17:00Z'
  },
  {
    name: 'F1-02-end-edited-then-zone-toggles',
    note: 'The same sequence on the end field, with the start left recorded.',
    expectation: 'accepted',
    recordedZoneId: NEW_YORK,
    recordedStart: '2026-10-22T12:47:37.123456789Z',
    recordedEnd: '2026-10-30T12:47:00Z',
    events: [typed('end', '2026-10-30T20:00'), zone(KOLKATA), zone(NEW_YORK)],
    expectedEndInstant: '2026-10-31T00:00:00Z'
  },
  {
    name: 'F1-03-both-edited-then-zone-toggles',
    note: 'Both fields edited before the zone changes: each edit survives independently.',
    expectation: 'accepted',
    recordedZoneId: NEW_YORK,
    recordedStart: '2026-10-22T12:47:37.123456789Z',
    recordedEnd: '2026-10-30T12:47:00Z',
    events: [
      typed('start', '2026-10-22T18:17'),
      typed('end', '2026-10-30T20:00'),
      zone(KOLKATA),
      zone(NEW_YORK)
    ],
    expectedStartInstant: '2026-10-22T22:17:00Z',
    expectedEndInstant: '2026-10-31T00:00:00Z'
  },
  {
    name: 'F1-04-edited-text-coincides-with-recorded-display',
    note: 'The sharpest form of the defect: the value the OE typed into the New York field is exactly what '
      + 'the recorded instant looks like once IST is selected. The edit must still win — IST 18:17 '
      + 'exactly, not the recorded instant with its hidden seconds.',
    expectation: 'accepted',
    recordedZoneId: NEW_YORK,
    recordedStart: '2026-10-22T12:47:37.123456789Z',
    recordedEnd: '2026-10-30T12:47:00Z',
    events: [typed('start', '2026-10-22T18:17'), zone(KOLKATA)],
    expectedStartInstant: '2026-10-22T12:47:00Z'
  },
  {
    name: 'F1-05-zone-change-before-edit',
    note: 'Change the zone first, then edit: the recorded instants re-express correctly and the later edit '
      + 'is still an edit.',
    expectation: 'accepted',
    recordedZoneId: NEW_YORK,
    recordedStart: '2026-10-22T12:47:00Z',
    recordedEnd: '2026-10-30T12:47:00Z',
    events: [zone(KOLKATA), typed('start', '2026-10-23T09:00')],
    expectedStartInstant: '2026-10-23T03:30:00Z'
  },
  {
    name: 'F1-06-repeated-zone-toggles-no-drift',
    note: 'Four zone changes with nothing typed: the recorded instants, seconds included, must not drift.',
    expectation: 'accepted',
    recordedZoneId: NEW_YORK,
    recordedStart: '2026-10-22T12:47:37.123456789Z',
    recordedEnd: '2026-10-30T12:47:00Z',
    events: [zone(KOLKATA), zone(NEW_YORK), zone(KOLKATA), zone(NEW_YORK)]
  },
  {
    name: 'F1-07-ambiguous-edited-fold-after-toggles',
    note: 'The recorded second 01:30 occurrence is re-expressed in IST, the OE types that ambiguous clock, '
      + 'and the selector returns to New York: the edit is refused and no request is transmitted.',
    expectation: 'refused',
    recordedZoneId: NEW_YORK,
    recordedStart: '2027-11-07T06:30:37.123456789Z',
    recordedEnd: '2027-11-07T06:45:00Z',
    events: [zone(KOLKATA), typed('start', '2027-11-07T01:30'), zone(NEW_YORK)]
  },
  {
    name: 'F1-08-gap-after-zone-toggle',
    note: 'A wall clock the zone skips between its transitions, reached after leaving and rejoining the '
      + 'zone, is refused with no request transmitted.',
    expectation: 'refused',
    recordedZoneId: NEW_YORK,
    recordedStart: '2027-03-14T07:30:00Z',
    recordedEnd: '2027-03-14T21:30:00Z',
    events: [zone(KOLKATA), typed('start', '2027-03-14T02:30'), zone(NEW_YORK)]
  },
  {
    name: 'F1-09-non-hour-offset-zone',
    note: 'A zone whose offset is not a whole hour: the recorded field and the edited field are both '
      + 'expressed with +05:45.',
    expectation: 'accepted',
    recordedZoneId: 'Asia/Kathmandu',
    recordedStart: '2026-10-22T12:47:00Z',
    recordedEnd: '2026-10-30T12:47:00Z',
    events: [typed('end', '2026-10-30T19:45')],
    expectedEndInstant: '2026-10-30T14:00:00Z'
  },
  {
    name: 'F1-10-midnight-rollover-with-edit',
    note: 'The local date rolls into the next year while the UTC date is still December: the recorded end '
      + 'survives and the edited start resolves on the correct side of the rollover.',
    expectation: 'accepted',
    recordedZoneId: KOLKATA,
    recordedStart: '2026-12-31T18:35:00Z',
    recordedEnd: '2027-01-01T18:35:00Z',
    events: [typed('start', '2027-01-01T00:30')],
    expectedStartInstant: '2026-12-31T19:00:00Z'
  }
];

/**
 * Runs one row exactly as the itinerary does: open the editor for the item, apply the OE's events in
 * order, press save, and carry whatever the editor produced through the production command builder and
 * the production service.
 */
export const runScenario = async (harness, scenario) => {
  const { presentation } = harness;
  const responseItem = availabilityItemFixture({
    propertyAvailabilityStartAt: scenario.recordedStart,
    propertyAvailabilityEndAt: scenario.recordedEnd,
    propertyAvailabilityZoneId: scenario.recordedZoneId,
    propertyAvailabilitySource: RESPONSE_SOURCE
  });
  const detail = presentation.toSessionDetail(availabilitySessionFixture([responseItem]));
  const recorded = detail.items[0];
  const editor = openAvailabilityEditor(presentation, {
    session: detail,
    item: recorded,
    blocked: false,
    busyCommand: null
  });

  const openingState = editor.state();
  for (const event of scenario.events) {
    if (event.type === 'text') editor.text(event.field, event.value);
    else if (event.type === 'zone') editor.zone(event.zoneId);
    else throw new Error(`${scenario.name}: unsupported event ${event.type}`);
  }
  editor.submit();
  const finalState = editor.state();

  const row = {
    name: scenario.name,
    note: scenario.note,
    expectation: scenario.expectation,
    openingZoneId: scenario.recordedZoneId ?? KOLKATA,
    finalZoneId: finalState.zone,
    recordedStartInstant: scenario.recordedStart ?? null,
    recordedEndInstant: scenario.recordedEnd ?? null,
    expectedStartInstant: scenario.expectedStartInstant ?? scenario.recordedStart ?? null,
    expectedEndInstant: scenario.expectedEndInstant ?? scenario.recordedEnd ?? null,
    openingStartField: openingState.start,
    openingEndField: openingState.end,
    displayedStartField: finalState.start,
    displayedEndField: finalState.end,
    submitted: finalState.submitted,
    savedWindow: finalState.saved ? finalState.saved.window : null,
    refusalMessage: finalState.refusalMessage,
    commandError: null,
    fieldErrors: {},
    command: null,
    rawBody: null,
    httpStatus: null
  };
  if (!finalState.submitted) return row;

  const built = presentation.setAvailabilityCommand(
    detail,
    ITEM_ID,
    finalState.saved.choice,
    finalState.saved.window,
    CLOCK
  );
  row.command = built.command;
  row.fieldErrors = built.errors;
  if (built.command === null) {
    row.commandError = 'refused by the command builder';
    return row;
  }

  transmittedBodies.length = 0;
  harness.reset(() => harness.json(200, availabilitySessionFixture([responseItem])));
  harness.signIn(ACCOUNT);
  await harness.service.setSessionItemAvailability(SESSION_ID, ITEM_ID, built.command, {
    session: harness.session.captureOperationsSession()
  });
  // The service's own request text, byte for byte, as the browser would have written it.
  row.rawBody = transmittedBodies[0] ?? null;
  row.httpStatus = 200;
  return row;
};

/**
 * Runs the matrix (or the named rows) against an installed action harness.
 *
 * The caller supplies the harness so a test can share the browser globals it already installed; this
 * only wraps the transport to keep the exact request text.
 */
export const runBridgeScenarios = async (harness, names) => {
  let selected = SCENARIOS;
  if (names !== undefined) {
    const missing = names.filter((name) => !SCENARIOS.some((scenario) => scenario.name === name));
    if (missing.length > 0) throw new Error(`unknown bridge scenario(s): ${missing.join(', ')}`);
    selected = SCENARIOS.filter((scenario) => names.includes(scenario.name));
  }
  const installedFetch = globalThis.fetch;
  globalThis.fetch = (url, init = {}) => {
    transmittedBodies.push(init.body === undefined ? null : init.body);
    return installedFetch(url, init);
  };
  try {
    const rows = [];
    for (const scenario of selected) rows.push(await runScenario(harness, scenario));
    return rows;
  } finally {
    globalThis.fetch = installedFetch;
  }
};

/** The full report, for the backend contract test and for anyone reading the matrix. */
export const runBridge = async (names) => {
  const harness = await installOperationsActionHarness();
  const scenarios = await runBridgeScenarios(harness, names);
  return {
    generatedAt: new Date().toISOString(),
    detailVersion: DETAIL_VERSION,
    itemId: ITEM_ID,
    sessionId: SESSION_ID,
    referenceClock: CLOCK.toISOString(),
    scenarioCount: scenarios.length,
    acceptedCount: scenarios.filter((row) => row.rawBody !== null).length,
    refusedCount: scenarios.filter((row) => row.rawBody === null).length,
    scenarios
  };
};

const invokedDirectly = (() => {
  if (process.argv[1] === undefined) return false;
  try {
    // Real paths, so the script still runs when it is reached through a symlink (an isolated build tree).
    return realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url));
  } catch {
    return false;
  }
})();
if (invokedDirectly) {
  process.stdout.write(`${JSON.stringify(await runBridge(), null, 2)}\n`);
}
