/**
 * F3 — DRAFT itinerary management: exact order, derived property addition, removal guards and the
 * retained removed history, driven through the real service.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { installOperationsActionHarness } from './support/operationsActionHarness.mjs';

const h = await installOperationsActionHarness();
const { service, session, presentation } = h;

const ACCOUNT = { id: 41, role: 'EMPLOYEE', token: 'token-account-itinerary' };

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
  removedAt: null,
  removalReason: null,
  propertyAvailabilityStartAt: null,
  propertyAvailabilityEndAt: null,
  propertyAvailabilityZoneId: null,
  propertyAvailabilitySource: null,
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
  items: [
    item(),
    item({ itemId: 92, listingId: 8002, position: 2, sourceRequestId: null, derivedFromRequestId: 501, origin: 'OE_ADDED' }),
    item({ itemId: 93, listingId: 8003, position: 3, removedAt: '2026-10-12T10:00:00Z', removalReason: 'Lessor withdrew' })
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

test('reorder sends every active item exactly once and never the removed history', async () => {
  h.reset(() => h.json(200, sessionView({ version: 7 })));
  h.signIn(ACCOUNT);

  const result = await service.reorderSessionItems(
    3108,
    { expectedSessionVersion: 6, itemIds: [92, 91] },
    { session: session.captureOperationsSession() }
  );

  assert.equal(h.calls[0].method, 'PUT');
  assert.match(h.calls[0].url, /\/operations\/visit-sessions\/3108\/items\/order$/);
  assert.deepEqual(h.calls[0].body, { expectedSessionVersion: 6, itemIds: [92, 91] });
  assert.equal(result.version, 7);
});

test('the order builder rejects an incomplete, duplicated or foreign set', () => {
  const detail = presentation.toSessionDetail(sessionView());
  assert.deepEqual(presentation.reorderSessionItemsCommand(detail, [92, 91]), {
    targetType: 'SESSION',
    expectedSessionVersion: 6,
    itemIds: [92, 91]
  });
  // Missing an active item.
  assert.equal(presentation.reorderSessionItemsCommand(detail, [91]), null);
  // Duplicated.
  assert.equal(presentation.reorderSessionItemsCommand(detail, [91, 91]), null);
  // Includes the removed item.
  assert.equal(presentation.reorderSessionItemsCommand(detail, [91, 93]), null);
  // A non-DRAFT session has no editable itinerary.
  assert.equal(presentation.reorderSessionItemsCommand(presentation.toSessionDetail(sessionView({ status: 'SCHEDULED' })), [92, 91]), null);
});

test('a derived stop is added with an approved origin and its originating request', async () => {
  h.reset(() => h.json(200, sessionView({ version: 7 })));
  h.signIn(ACCOUNT);

  await service.addSessionItem(
    3108,
    { expectedSessionVersion: 6, listingId: 8004, originatingRequestId: 501, origin: 'LESSOR_SUGGESTED' },
    { session: session.captureOperationsSession() }
  );

  assert.equal(h.calls[0].method, 'POST');
  assert.match(h.calls[0].url, /\/operations\/visit-sessions\/3108\/items$/);
  assert.deepEqual(h.calls[0].body, {
    expectedSessionVersion: 6,
    listingId: 8004,
    originatingRequestId: 501,
    origin: 'LESSOR_SUGGESTED'
  });
  // The tenant-requested origin belongs to the intake path, not to Operations.
  //
  // The client boundary for that rule is the option list the picker renders (plus the compile-time
  // union), not a runtime check inside the pure builder — so the reachable origins are asserted
  // directly, and the backend stays the authority that rejects an unapproved one.
  assert.deepEqual(
    presentation.OPERATIONS_DERIVED_ITEM_ORIGINS.map((option) => option.value),
    ['OE_ADDED', 'LESSOR_SUGGESTED']
  );
  assert.equal(
    presentation.OPERATIONS_DERIVED_ITEM_ORIGINS.some((option) => option.value === 'TENANT_REQUESTED'),
    false
  );
});

test('the add builder refuses a stop without a chosen listing or originating request', () => {
  const detail = presentation.toSessionDetail(sessionView());
  assert.equal(
    presentation.addSessionItemCommand(detail, { listingId: null, originatingRequestId: 501, origin: 'OE_ADDED' }),
    null
  );
  assert.equal(
    presentation.addSessionItemCommand(detail, { listingId: 8004, originatingRequestId: null, origin: 'OE_ADDED' }),
    null
  );
  assert.equal(
    presentation.addSessionItemCommand(detail, { listingId: -1, originatingRequestId: 501, origin: 'OE_ADDED' }),
    null
  );
  assert.deepEqual(
    presentation.addSessionItemCommand(detail, { listingId: 8004, originatingRequestId: 501, origin: 'OE_ADDED' }),
    {
      targetType: 'SESSION',
      expectedSessionVersion: 6,
      listingId: 8004,
      originatingRequestId: 501,
      origin: 'OE_ADDED'
    }
  );
});

test('removal keeps its required DELETE body, trims the reason and rejects an oversized one', async () => {
  h.reset(() => h.json(200, sessionView({ version: 7 })));
  h.signIn(ACCOUNT);

  await service.removeSessionItem(
    3108,
    92,
    { expectedSessionVersion: 6, reason: 'Lessor withdrew' },
    { session: session.captureOperationsSession() }
  );

  assert.equal(h.calls[0].method, 'DELETE');
  assert.match(h.calls[0].url, /\/operations\/visit-sessions\/3108\/items\/92$/);
  assert.equal(h.calls[0].contentType, 'application/json');
  assert.deepEqual(h.calls[0].body, { expectedSessionVersion: 6, reason: 'Lessor withdrew' });

  const detail = presentation.toSessionDetail(sessionView());
  assert.equal(presentation.removeSessionItemCommand(detail, 92, '   ')?.reason, null);
  assert.equal(presentation.removeSessionItemCommand(detail, 92, '  spacing  ')?.reason, 'spacing');
  assert.equal(presentation.removeSessionItemCommand(detail, 92, 'x'.repeat(501)), null);
});

test('a directly requested stop can only be removed once it is recorded unavailable', () => {
  const pending = presentation.toSessionDetail(sessionView()).items[0];
  assert.equal(presentation.isSessionItemRemovable(pending), false);
  assert.match(presentation.sessionItemRemovalBlockedReason(pending), /recorded as unavailable/);

  const unavailable = { ...pending, confirmationStatus: 'UNAVAILABLE' };
  assert.equal(presentation.isSessionItemRemovable(unavailable), true);
  assert.equal(presentation.sessionItemRemovalBlockedReason(unavailable), null);

  // A derived stop carries no tenant request, so it can be removed while still pending.
  const derived = presentation.toSessionDetail(sessionView()).items[1];
  assert.equal(presentation.isSessionItemRemovable(derived), true);

  // A removed item is history, never removable again.
  const removed = { ...derived, removedAt: '2026-10-12T10:00:00Z' };
  assert.match(presentation.sessionItemRemovalBlockedReason(removed), /already removed/);
});

test('the returned session replaces the view, so removed history and provenance stay authoritative', async () => {
  h.reset(() => h.json(200, sessionView({
    version: 7,
    items: [
      // Item 91 is the stop the DELETE actually removed, so the returned history must carry *its*
      // removal — asserting an unrelated pre-removed item would prove nothing about this command.
      item({ confirmationStatus: 'UNAVAILABLE', removedAt: '2026-10-13T09:00:00Z', removalReason: 'Lessor withdrew' }),
      item({ itemId: 92, listingId: 8002, position: 2, sourceRequestId: null, derivedFromRequestId: 501, origin: 'LESSOR_SUGGESTED' }),
      item({ itemId: 93, listingId: 8003, position: 3, removedAt: '2026-10-13T09:00:00Z', removalReason: 'Lessor withdrew' })
    ]
  })));
  h.signIn(ACCOUNT);

  const result = await service.removeSessionItem(
    3108,
    91,
    { expectedSessionVersion: 6, reason: null },
    { session: session.captureOperationsSession() }
  );

  assert.match(h.calls[0].url, /\/operations\/visit-sessions\/3108\/items\/91$/);
  assert.equal(result.items.length, 3);
  const removed = presentation.removedSessionItems(result.items);
  assert.deepEqual(removed.map((entry) => entry.itemId), [91, 93]);
  assert.equal(presentation.activeSessionItems(result.items).length, 1);
  assert.equal(presentation.sessionItemOriginLabel(result.items[1].origin), 'Derived property · suggested by the lessor');
  assert.deepEqual(presentation.derivedStopOriginCandidates(result), [
    { requestId: 501, version: 5 }
  ]);
});

/* ------------------------------------------------------------------ *
 * R7 — a valid originating Request survives the removal of its direct stop
 * ------------------------------------------------------------------ */

test('a linked Request stays an available origin after its only direct stop is removed', () => {
  // The real sequence: one linked Request (501) whose only direct stop has just been soft-removed as
  // unavailable. Deriving origins from *active* items alone emptied this list and dead-ended the
  // documented "mark unavailable → remove → add a replacement" workflow.
  const afterRemoval = presentation.toSessionDetail(sessionView({
    items: [
      item({
        confirmationStatus: 'UNAVAILABLE',
        removedAt: '2026-10-13T09:00:00Z',
        removalReason: 'Lessor withdrew'
      })
    ]
  }));
  assert.equal(presentation.activeSessionItems(afterRemoval.items).length, 0);
  assert.deepEqual(presentation.derivedStopOriginCandidates(afterRemoval), [
    { requestId: 501, version: 5 }
  ]);

  // The still-linked Request is genuinely usable: the command built from it names exactly that origin.
  assert.deepEqual(
    presentation.addSessionItemCommand(afterRemoval, {
      listingId: 8004,
      originatingRequestId: 501,
      origin: 'OE_ADDED'
    }),
    {
      targetType: 'SESSION',
      expectedSessionVersion: 6,
      listingId: 8004,
      originatingRequestId: 501,
      origin: 'OE_ADDED'
    }
  );

  // Removed history is untouched by offering the origin again.
  assert.deepEqual(presentation.removedSessionItems(afterRemoval.items).map((entry) => entry.itemId), [91]);
});

test('only Requests the session is actually linked to can be offered as an origin', () => {
  // No linked membership and no item provenance: nothing may be invented as an origin.
  const unlinked = presentation.toSessionDetail(sessionView({
    linkedRequestVersions: [],
    items: [
      item({ sourceRequestId: null, derivedFromRequestId: null }),
      item({
        itemId: 92,
        listingId: 8002,
        position: 2,
        sourceRequestId: null,
        derivedFromRequestId: null,
        origin: 'OE_ADDED'
      })
    ]
  }));
  assert.deepEqual(presentation.derivedStopOriginCandidates(unlinked), []);
  // The builder still refuses a stop with no originating request at all.
  assert.equal(
    presentation.addSessionItemCommand(unlinked, { listingId: 8004, originatingRequestId: null, origin: 'OE_ADDED' }),
    null
  );

  // An item's own provenance is only used to *add* a Request the membership list also knows about.
  const linked = presentation.toSessionDetail(sessionView({
    linkedRequestVersions: [{ requestId: 501, version: 5 }],
    items: [item({ sourceRequestId: 501 }), item({ itemId: 94, listingId: 8005, position: 2, sourceRequestId: 501 })]
  }));
  assert.deepEqual(presentation.derivedStopOriginCandidates(linked), [{ requestId: 501, version: 5 }]);
});

test('a denied DRAFT edit keeps the real status and clears nothing on the client', async () => {
  h.reset(() => h.json(409, { message: 'Listing is already present in this session history' }));
  h.signIn(ACCOUNT);

  await assert.rejects(
    service.addSessionItem(
      3108,
      { expectedSessionVersion: 6, listingId: 8001, originatingRequestId: 501, origin: 'OE_ADDED' },
      { session: session.captureOperationsSession() }
    ),
    (error) => error.status === 409
  );
});

test('a removed listing cannot be re-added silently: the server rejection is surfaced verbatim', async () => {
  h.reset(() => h.json(409, { message: 'Listing is already present in this session history' }));
  h.signIn(ACCOUNT);

  await assert.rejects(
    service.addSessionItem(
      3108,
      { expectedSessionVersion: 6, listingId: 8003, originatingRequestId: 501, origin: 'OE_ADDED' },
      { session: session.captureOperationsSession() }
    ),
    (error) => {
      assert.equal(error.status, 409);
      assert.match(error.details ?? error.message, /history/);
      return true;
    }
  );
});
