# B09 accepted-result recovery-state correction

2026-10-16, 02:02 IST. **READY FOR INDEPENDENT CHECKPOINT REVIEW**.
This is the owner-authorized narrow correction following the independent HOLD, not a Git checkpoint
or release acceptance. The original closure report and private receipts remain preserved.

## Root cause and negative control

Before correction, `OperationsWorkspace.tsx:2153` removed resolved scheduling intent. A later
Session-detail GET failure at the former line 2164 replaced the accepted result with an `uncertain`
outcome lacking approval and last verified Session. The next invocation, at former lines 2121–2122,
found neither intent nor known approval and threw before any recovery GET. This was a frontend
recovery-state defect; no backend scheduling defect was established.

Before modifying production, added executable regressions using the existing TypeScript AST loader
for the actual production reconciliation callback and the actual React scheduling component.
`node --test frontend-web/src/tests/operationsSchedulingClosure.test.mjs` produced 14 tests,
11 PASS and 3 FAIL. The accepted → CANCELLED → repeated read failures → restored network → two
explicit checks control recorded `recoveryReads: 0`, `failedKind: uncertain`, `pending: 0`.
Other failures exposed lost acceptance on a first current read failure and absent last-verified UI.
Negative evidence: `/private/tmp/pathome-b09-recovery-negative-20261016.log`.
These are callback/component controls, not live PostgreSQL or physical-device tests.

## Minimal architecture and UX correction

The existing single authenticated drawer outcome now retains historical `approval`, nullable
`currentSession` (the last successful authoritative read), and independent `verificationFailed`.
The Session ID in retained approval provides the in-memory lookup identity. Historical approval is
never used as current Session truth. A completed receipt establishes acceptance before the current
Session GET; failure of that GET cannot revert an accepted command to unresolved retry state.

Current source: `OperationsWorkspace.tsx:2119–2135` retains scoped acceptance, stops depending on
pending-intent storage once accepted, and clears genuinely resolved intent. Lines 2156–2177 retain
acceptance when the post-retry read fails, publish fresh truth when available, and mark observations
stale on temporary failure. Existing actor/session, authorization generation, drawer identity,
abort/deadline and synchronous mutation-lock guards remain. A denial still follows the established
protected-state withdrawal path.

`OperationsScheduleFlow.tsx:403–444` preserves the result and labels retained CANCELLED as
**Last verified Session status**. It explicitly says current details are temporarily unavailable,
keeps historical acceptance visible, and offers **Check the session**. It does not assert that a
previously observed status remains current. A first read failure with no verified Session shows
unavailability rather than borrowing the historical SCHEDULED approval. Work-list refresh failure
remains a separate status-aware warning after a successful Session read. Existing styling and drawer
composition are reused; no redesign or new workflow was introduced.

Accepted recovery performs scoped Session GETs only. It never restores unresolved intent, generates
a UUID, or repeats a scheduling POST. Genuinely unresolved NOT_RECORDED commands retain the existing
explicit same-UUID/immutable-payload retry with current owner/DRAFT/version/access rechecks.

Memory stays bounded to one drawer. Closing it, changing account/authority, or unmounting the workspace
uses existing clearing and cancellation. No completed history, Session data or credentials are added
to browser storage. An unresolved intent still survives the existing supported reload contract.
After full reload, completed drawer history is intentionally absent: ordinary detail reads recover
current Session truth, without resurrecting a pending command. Re-rendering/remounting the child
with its retained parent outcome preserves the stale result; this is covered by component rendering.

## Exact files changed by this correction

1. `frontend-web/src/components/operations/OperationsWorkspace.tsx`
2. `frontend-web/src/components/operations/OperationsScheduleFlow.tsx`
3. `frontend-web/src/tests/operationsSchedulingClosure.test.mjs`
4. `tools/oe-integration-env/phase2a/b09-closure.mjs`
5. `tools/oe-integration-env/phase2a.sh` — forward focused runner arguments.
6. `tools/oe-integration-env/B09_FRONTEND_CLOSURE.md` — historical-report pointer only.
7. `tools/oe-integration-env/B09_RECOVERY_STATE_CORRECTION.md` — this report.
8. `chat_gpt_changes` — append-only entry.

All pre-existing changes are preserved. No backend source, V50, API/DTO/entity, permission, dependency,
configuration, cache or business rule was changed by this correction.

## Focused verification executed

- `node --test frontend-web/src/tests/operationsSchedulingClosure.test.mjs frontend-web/src/tests/operationsSchedulingReliability.test.mjs frontend-web/src/tests/operationsScheduling.test.mjs frontend-web/src/tests/operationsWorkspaceIntegration.test.mjs`
  — **75 PASS**, zero failures/skips. Final log:
  `/private/tmp/pathome-b09-recovery-focused-final-20261016.log`.
  Five added tests cover repeated failures/recovery, acceptance before the first verified read,
  unavailable storage after acceptance, late success/failure after account/drawer changes, and
  real React initial/remounted last-verified rendering. Existing P1/P2 tests remain green.
- `node --test tools/oe-integration-env/tests/oe-harness-static.test.mjs tools/oe-integration-env/tests/oe-blocker-closure.test.mjs`
  — **56 PASS**, zero failures/skips. Final log:
  `/private/tmp/pathome-b09-recovery-harness-final-20261016.log`.
- Controlled `run.sh preflight` with the existing external designation/media files and
  `PATHOME_OE_TEST_ALLOW_LOCAL_BUILD=true` — PASS, including `tsc && vite build` and offline backend
  compile/tooling checks. Existing Vite large-bundle advisory remains. Final runtime build log:
  `/private/tmp/pathome-b09-recovery-final-preflight-20261016.log`.
  A later source-comment clarification does not change runtime behavior. A preflight attempted while
  the owned backend was running correctly refused runtime artifacts; guarded recover then preflight
  and backend restart succeeded. No guard was bypassed and no live-classpath rebuild was performed.
- `node --check tools/oe-integration-env/phase2a/b09-closure.mjs`,
  `bash -n tools/oe-integration-env/phase2a.sh`, `git diff --check`,
  `git diff --cached --check` — PASS.

## Genuine Spring Boot / PostgreSQL / Chromium evidence

Only designated database `pathome_oe_test_phase2a_20261015` was used, run
`59614cec-fd1e-404a-a221-b3bd94c4f71e`. Guarded recreate/backend/provision/verify/attest passed;
attestation used the existing schema/migrations, not a new migration. The existing approved
disposable-media configuration and audited Admin/GE fixture exception were reused. Two separate
19-entry fixture preparations created fresh actors/roots; the 63 API coverage entries did not run.

First cohesive browser attempt:
`/private/tmp/pathome-oe-phase2a-20261015/evidence/attempts/b09_closure-1792096201920-6c6f80f9-6dc0-4751-a7d8-026a4e9f3a60/b09-closure.json`.

- ROLLBACK_RETRY **PASS**: guarded Session-specific real PostgreSQL transaction interruption,
  HTTP 500 and clean rollback, actual page reload and NOT_RECORDED, actual double-click retry and
  HTTP 200. Both POSTs used UUID `3c4d09b5-cbdb-4148-a263-381fe5c92728` and the same payload digest.
  Session 1 DRAFT v1 → SCHEDULED v2; one completed command, one decision, two outbox entries,
  unchanged audit count. Thus P1 remains fixed under real rollback/remount/retry.
- LOST_ACK_CANCELLED **PASS**: real accepted command, withheld acknowledgement, authorized Admin
  cancellation, historical acceptance plus current CANCELLED, no resend.
- CANCELLED_QUEUE_FAILURE **PASS**: six genuine queue response transports dropped; CANCELLED and
  its accurate warning remained; backend counters unchanged. Thus the original P2 remains fixed.
- CANCELLED_READ_RECOVERY **FAIL in this attempt** at its final post-reload locator: the generic
  exact-text selector matched both the Cancelled badge and hint. This receipt is not counted as a
  fully successful browser batch. Its failure and screenshot remain intact. The selector was narrowed
  to the actual status badge; production code was not changed to address this harness error.

Only the affected accepted/cancelled setup and recovery were rerun with fresh fixture roots:
`bash tools/oe-integration-env/phase2a.sh b09-browser --recovery-only`.
Final receipt (**2 PASS, 0 FAIL, 0 NOT TESTED**):
`/private/tmp/pathome-oe-phase2a-20261015/evidence/attempts/b09_recovery-1792096284648-aaccd693-ccfe-4791-9ee4-4648d739d3fc/b09-closure.json`.

Session 3 genuinely committed SCHEDULED v2 and was cancelled through the authorized backend operation
to CANCELLED v3. The real React result reconciled acceptance and cancellation. Two explicit failing
refresh gestures caused five actual upstream-200 Session response transports to be dropped; no
Session body/status was fabricated. The drawer retained last-verified CANCELLED, original acceptance
and a safe Check action. After restoring transport, two explicit checks made successful real GETs;
the subsequent full page reload/detail read added a third successful GET. Current CANCELLED was
rendered again. **Zero additional scheduling POSTs** occurred during recovery/reload.

Before/after PostgreSQL snapshots were identical: CANCELLED v3; one command, one completed command,
one decision, four total outbox entries (scheduling plus cancellation), two stored notifications and
audit count 63. Notification dispatch timing/delivery is not inferred beyond the stored snapshot.
Full reload showed authoritative Cancelled detail, no unresolved scheduling drawer, and zero
pending-intent storage keys. Completed history was not persisted into browser storage.

The last-verified drawer passed overflow, visible-control 44×44/accessibility-name, modal-label and
forward/reverse Tab checks at 320, 375, 390, 414, 430, 768, 1024, 1280 and 1440 × 900.
375px and 1280px screenshots beside the final receipt were visually inspected. Earlier P1 and queue
failure drawer checks at those widths passed in the preserved first attempt. These measurements
cover these drawer states, not all application flows or a complete WCAG audit.

## Cleanup, governance and limitations

Guarded recover, teardown and status **PASS**: the owned backend is stopped and the designated
database is absent. The Session-specific trigger was cleared by the first browser scenario before
teardown. Private evidence and failed attempts remain. Approved disposable external media assets are
not deleted by database teardown. Protected/quarantined databases were not accessed; no comparison
or claim about their contents is made.

**NOT TESTED:** full 120 PostgreSQL tests, 82 API scenarios or every Phase 2A browser scenario
(unchanged backend contract; focused tests/live regressions were sufficient); physical devices,
WebKit/Firefox, full screen-reader/contrast/WCAG audit; a live account/authorization switch during
the new accepted-result recovery GET (late account/drawer success/failure is callback-tested and
existing clearing/authority guards were inspected); JVM/power loss; real later execution/rescheduling
transitions (existing component coverage remains green). Completed history durability beyond the
current drawer/workspace lifecycle is intentionally not provided by the existing persistence contract.

The new path adds no automatic resend, polling, unbounded state or query. Each explicit check uses
the existing bounded authenticated reads; server ownership/authorization remain authoritative.

For the next canonical tracker reconciliation, attach the negative control and final recovery
receipt, record the confirmed recovery blocker corrected pending independent checkpoint review,
and retain release/owner Git gates. The owner reports the two canonical Library trackers already
reconciled for the earlier closure; no tracker was modified or independently reverified here.

Branch remains `feature/phase2a-oe-ui-integration`; HEAD remains
`23cbdfe8722516daaf474d58a5113e4da71d333a`. Existing dirty worktree preserved; index empty.
No stage, commit, push, merge, deployment, reset, stash, branch switch or remote-freshness claim.
