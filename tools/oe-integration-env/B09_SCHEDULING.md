# B09 scheduling reliability decision

Owner authorization: Phase 2A B09 Option A, 2026-10-16. No commit/push/deployment authorized.

## Existing architecture verified before implementation

- OE: `POST /api/v1/operations/visit-sessions/{id}/oe-recommendations/selection` accepts
  `ApproveVisitRecommendationCommand(expectedSessionVersion, groundExecutiveUserId, scheduledAt,
  zoneId, overrideReason)` and returns `VisitSchedulingApprovalView(session, decisionId, override,
  topRecommendedGroundExecutiveUserId, topRecommendedAt)`.
- `VisitOperationsService.selectForOwner` is one writable transaction: admit current staff,
  lock Session, lock/check current City/Team/grant/ownership, require DRAFT and expected version,
  lock GE User, revalidate feasibility, call existing `scheduleLocked`, save scheduling decision.
- `scheduleLocked` preserves Request versions/state, confirmed stops, location, duration, future
  time, eligible GE and overlap checks. V36 additionally enforces complete reservations and
  nonoverlapping `[scheduled_at,reserved_end_at)` GE reservations with a PostgreSQL exclusion constraint.
- `VisitSessionNotificationListener` enqueues tenant/GE notifications BEFORE_COMMIT using unique
  version-specific event keys. The scheduling decision is persisted in the same transaction.
  No new external sender or separate scheduling audit is needed.
- Existing direct Admin schedule has state-based replay, but OE selection checks DRAFT before
  recognizing any successful retry. Existing audit operation IDs are tracing data, not unique commands.
- React `confirmSelection` uses `runGuardedCommand` and its synchronous ownership lock. The service
  uses one native fetch without a retry loop. Prior B09 evidence: one page request, two proxy POSTs,
  first upstream 200 SCHEDULED, dropped acknowledgement, second 409, false nonbooking copy.
  Fresh reproduction proved Chromium source 627 emitted `HTTP_TRANSACTION_RESTART_AFTER_ERROR`
  with net_error -100 (ERR_CONNECTION_CLOSED) between two SEND_REQUEST_HEADERS events for the
  same selection. One URL_REQUEST_START_JOB and one page request; no second click/fetch/proxy retry.
  Receipt: private run evidence/attempts/react_browser-1792090854539-a72b534e-47c8-4f75-b365-85fbdbf46ed7.

## Smallest approved change

Keep the existing booking core, locks, staff authority, entitlements and UI composition.
Add a required UUID `commandId` to the OE selection payload (Admin compatibility retained).
Persist an actor/UUID unique receipt with operation, Session, canonical payload and original serialized
approval in new V50, atomically with booking/decision/outbox. Lock Session before command arbitration;
PostgreSQL `INSERT ... ON CONFLICT DO NOTHING` arbitrates same actor/ID even across different Sessions.
Replay requires current owned scope (without requiring DRAFT), then exact operation/Session/payload
equality; it returns the original approval without re-running feasibility or publishing events.
Different commands retain all current DRAFT/version/GE/ownership rules. No cross-actor receipt reads.

Add a no-store GET of this actor's command under current owned Session authority. It returns
COMPLETED with the original approval or NOT_RECORDED. NOT_RECORDED never proves failure: an HTTP
request may still be approaching the transaction. An in-progress transaction is uncommitted;
Session locking makes readers/duplicates wait for commit/rollback. Lock/database/HTTP timeouts remain
uncertain and are reconciled, never represented as definitive rejection. Rollback removes receipt,
booking, decision and outbox together. No durable successful phantom or separate pending job exists.
Retain receipts without TTL/deletion: late retries remain safe; a future purge requires a separate
approved archival/tombstone design. One indexed receipt per accepted command; no staff cache.

React generates one ID before dispatch, saves its exact small intent in tab sessionStorage scoped
to authenticated User, and preserves it through timeout, loss, drawer/component remount and refresh.
Existing credential/generation guards remain the authority for async publication. Auth invalidation
clears pending browser intents. Refuse transmission if pending intent cannot be persisted. Unresolved
intent prevents creation of a replacement ID for that Session. A bounded request deadline displays
neutral uncertainty. Checking reads the command receipt and fresh Session; it never POSTs. Success
requires the command receipt and current authoritative Session to agree. Read failure/NOT_RECORDED
keeps uncertainty and offers checking again. Preserve drawer styling, focus trap and navigation.

## Acceptance and tracking

Before final acceptance: real PostgreSQL HTTP concurrency, conflicting reuse, rollback/interruption,
ownership/version/GE regressions, exact decision/outbox/audit counts; real Chromium B09 and B10–B12.
Historical API 63/63 is preserved and must be rerun after contract/migration changes.
Canonical Updated tracker files are not locally present or connected in Document Control. Older
local workbooks are not authoritative substitutes (October 14 handoff). Proposed gates only:
Phase 2A scheduling/idempotency/reconciliation, B09, B10–B12, scoped authorization, V50 and notification
regressions. No tracker edits or full Phase 2A acceptance until actual evidence supports them.
