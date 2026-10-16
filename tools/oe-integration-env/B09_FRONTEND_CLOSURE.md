# B09 final frontend recovery closure

**Follow-up, 2026-10-16 02:02 IST:** independent review subsequently found the accepted-result
refresh recovery trap. Its correction, negative control and new live evidence are recorded in
[B09_RECOVERY_STATE_CORRECTION.md](B09_RECOVERY_STATE_CORRECTION.md). Use that report for the latest
readiness verdict; the original evidence below remains historical and is preserved.

2026-10-16, 01:40 IST. Verdict: **READY FOR INDEPENDENT CHECKPOINT REVIEW**.
This is targeted frontend closure evidence, not a release, Git checkpoint, or full Phase 2A acceptance.

## Root causes and correction

**P1:** `confirmSelection` refused every new scheduling POST while a saved intent existed, while
`reconcileScheduling` returned immediately on `NOT_RECORDED`. The operator could keep checking but
could never resubmit a rolled-back or undelivered command through the UI.

Reconciliation now reads both the scoped command receipt and current Session. Only a currently owned
`DRAFT` with the original expected version and an unchanged saved payload offers **Retry scheduling**.
The authenticated, server-authorized receipt read precedes that eligibility decision. Clicking Retry
repeats those checks and sends the original saved body and UUID through the existing endpoint. A
completed receipt instead reconciles without POST. Changed state/version/owner refuses retry and
explains the current state. `NOT_RECORDED` explicitly remains an observation, not proof of failure.
The synchronous workspace mutation lock, credential/generation checks, drawer identity checks, abort
signals and bounded deadlines also cover recovery. No background resend or replacement UUID is added.
Intent remains in the existing bounded per-user tab storage until authoritative recovery; logout and
account switching retain their existing invalidation behavior.

**P2:** Original approval and current Session were merged into one `scheduled` outcome. The result
omitted an explicit status and its queue error always asserted “Scheduled”. The drawer now retains
`approval` as historical acceptance and a separate `currentSession` as current truth. Rendering,
result details, refresh warnings and confirmation toast use current truth. `CANCELLED` is explicit;
later execution statuses and a later rescheduled time also render from current Session data.
Result refresh reads current truth again even after the saved intent has been resolved.

No backend source, migration, entity, endpoint, permission, dependency or scheduling business rule was
changed by this closure. The existing backend remains the final authority for ownership, feasibility,
version conflicts, idempotency and concurrent transactions. The approved drawer design is retained.

## Negative controls before production edits

`/private/tmp/pathome-b09-closure-negative.log` records six initial tests, five failures and one pass.
They execute the production Workspace reconciliation callback extracted with TypeScript AST and render
the actual React scheduling component. The P1 controls found no retry identity/action and no retry
POST. The P2 control found no displayed `CANCELLED` status. These are callback/component negative
controls, not live PostgreSQL or physical-device results. The independent review also retained the
original false “Scheduled” warning reproduction. Subsequent tests exercise corrected behavior rather
than changing expected outcomes to match the defects.

## New real browser and PostgreSQL evidence

Disposable run: `d51ff661-5357-4fed-9755-8704b1988170`.
Only designated database `pathome_oe_test_phase2a_20261015` was accessed. Existing positive target,
sentinel, run ID, attestation, owned-process and loopback binding guards were used. Supported APIs
prepared fresh synthetic actors/listings/requests; the existing explicit audited Admin/GE role fixture
was reused. Nineteen fixture setup entries ran separately; the 63 API coverage entries did not run.
No API response body, status or authorization was fabricated in the browser.

Authoritative report (three PASS, zero FAIL, zero NOT TESTED within this focused live suite):

`/private/tmp/pathome-oe-phase2a-20261015/evidence/attempts/b09_closure-1792094911576-946d462f-7fc9-4bd7-8e73-884d163c6cc6/b09-closure.json`

- **ROLLBACK_RETRY:** A Session-specific test trigger paused the real decision insert. The guarded
  helper terminated only the backend whose current database/user, transaction-local application name,
  `PgSleep` wait and decision-insert query matched that target. The real POST returned 500. Session 1
  stayed DRAFT v1, with zero command receipts, decisions and outbox records. The trigger was removed.
  A real page reload restored the unresolved intent. Actual Check the session returned NOT_RECORDED.
  Actual double-click on Retry scheduling issued exactly one retry; that real POST returned 200.
  Both wire requests used UUID `fd84795e-8fb3-4e69-8b57-0d0cc62c7af7` and payload SHA-256
  `e0c763d743333a7c4aa171bce60a8a908686d37b8cea8db78c4e754a28e5ffaa`.
  Final Session: SCHEDULED v2, one completed command, one decision, two scheduling outbox events,
  zero notifications at that immediate snapshot, audit count unchanged at 36. Notification dispatch
  is asynchronous; this snapshot does not claim delivery.
- **LOST_ACK_CANCELLED:** Session 2's real scheduling POST committed with 200, while the proxy withheld
  the acknowledgement until the real client deadline. There was one scheduling wire POST despite a
  double-click. A genuine Admin cancellation then returned CANCELLED. Actual OE Check the session
  recovered the original completed receipt and current CANCELLED v3. The drawer displayed cancellation
  and historical acceptance without resending. The command and decision counts stayed one; scheduling
  produced two outbox events, and the authorized cancellation added its own two, for four total.
- **CANCELLED_QUEUE_FAILURE:** After authoritative cancellation was displayed, actual Refresh session
  details re-read the current Session. The proxy dropped six genuine queue response transports; it
  did not substitute errors or Session data. The drawer kept CANCELLED, showed a CANCELLED-specific
  work-list refresh warning and did not assert that the visit was scheduled. Version, command,
  decision, outbox and audit counts were unchanged.

Retry and cancelled/failed-refresh drawers were checked at widths 320, 375, 390, 414, 430, 768, 1024,
1280 and 1440, height 900: no page overflow; visible buttons/summary controls at least 44×44 px with
accessible names; labelled modal; forward and reverse Tab retained within the dialog. The 375 px
screenshots were visually inspected. Screenshots at 375 and 1280 are beside the JSON report.

Earlier preparation attempts are preserved under the same private `evidence/attempts` directory.
The first encountered the Playwright CommonJS default-export loader difference after preparing the
two Sessions; initial retries encountered already-owned roots. Setup now resumes verified DRAFT roots,
and the loader uses the established named/default export fallback. These preparation failures are not
counted as successful scenarios. No earlier B09 evidence file was overwritten.

## Focused verification executed

- `node --test frontend-web/src/tests/operationsSchedulingClosure.test.mjs frontend-web/src/tests/operationsSchedulingReliability.test.mjs frontend-web/src/tests/operationsScheduling.test.mjs frontend-web/src/tests/operationsWorkspaceIntegration.test.mjs`
  — **70 PASS**, zero failures/skips. Log: `/private/tmp/pathome-b09-closure-focused-final.log`.
  Ten new callback/component tests cover safe retry, exact saved identity/payload, concurrent clicks,
  changed state/version/owner, denial, account switch, stale drawer response, changed saved payload,
  interrupted retry, current cancellation, result refresh, later statuses and rescheduled time.
  Existing focused intent tests also cover remount, storage failure and stale receipt reads.
- `node --test tools/oe-integration-env/tests/oe-harness-static.test.mjs tools/oe-integration-env/tests/oe-blocker-closure.test.mjs`
  — **56 PASS**, zero failures/skips. Log: `/private/tmp/pathome-b09-closure-static.log`.
- `PATHOME_OE_TEST_ENV_FILE=/private/tmp/pathome-oe-phase2a-20261015-designation.env PATHOME_OE_TEST_MEDIA_ENV_FILE=/private/tmp/pathome-oe-approved-media.env PATHOME_OE_TEST_ALLOW_LOCAL_BUILD=true bash tools/oe-integration-env/run.sh preflight`
  — PASS, including controlled `npm run build` (TypeScript + Vite), offline backend compile/tooling
  checks and browser/media prerequisites. No database contacted by preflight. Existing large-bundle
  warning remains. Log: `/private/tmp/pathome-b09-closure-preflight.log`.
- Designated `run.sh recreate`, `backend`, `provision`, `verify`, `attest` — PASS. Attestation reports
  all 50 Flyway entries and the expected disposable schema. No production migration was added here.
- With the designated environment and explicit live/fixture opt-ins:
  `bash tools/oe-integration-env/phase2a.sh b09-fixtures` — 19 setup entries PASS;
  `bash tools/oe-integration-env/phase2a.sh b09-browser` — three live regressions PASS.
  Logs: `/private/tmp/pathome-b09-closure-fixtures.log`, `/private/tmp/pathome-b09-closure-browser.log`.
- `bash -n tools/oe-integration-env/phase2a.sh`; `node --check` for `phase2a/b09-closure.mjs`,
  `phase2a/fullstack.mjs`, `lib/lifecycle.mjs`; `git diff --check`; `git diff --cached --check` — PASS.

## Files changed by this closure only

1. `frontend-web/src/components/operations/OperationsWorkspace.tsx` — guarded saved-command recovery;
   historical/current result separation; current-state toast and refresh.
2. `frontend-web/src/components/operations/OperationsScheduleFlow.tsx` — accessible explicit retry;
   current status, status-aware warning and result refresh action.
3. `frontend-web/src/tests/operationsSchedulingClosure.test.mjs` — ten new focused regressions.
4. `frontend-web/src/tests/support/operationsComponentLoader.mjs` — existing TypeScript/React tooling
   loader for executing real production callbacks/components without a new dependency.
5. `tools/oe-integration-env/phase2a.sh` — guarded fixture/browser commands and bounded Session-specific
   disposable fault helper.
6. `tools/oe-integration-env/lib/lifecycle.mjs` — supervise the two focused commands within existing bounds.
7. `tools/oe-integration-env/phase2a/fullstack.mjs` — separate fixture-only mode and second synthetic root handoff.
8. `tools/oe-integration-env/phase2a/b09-closure.mjs` — new real UI/HTTP/PostgreSQL regression runner and private evidence.
9. `tools/oe-integration-env/B09_FRONTEND_CLOSURE.md` — this report.
10. `tools/oe-integration-env/B09_RESULTS.md` — pointer to this superseding targeted closure report.
11. `chat_gpt_changes` — append-only current closure entry.

## Limits, security, performance and handoff

**NOT TESTED in this closure:** full 120 PostgreSQL/82 API suites (backend design unchanged and
independently reviewed); physical devices, other browser engines, full screen-reader/WCAG audit;
live authorization transfer/account switch during the new retry POST; JVM/power loss; real later
STARTED/COMPLETED/reschedule transitions (current rendering covered by component tests).
The cancellation/lost-ack and rollback/remount/double-click cases above were genuinely executed.
No guarantee about all workflows or all viewport combinations follows from these bounded checks.

Recovery adds bounded, explicit receipt/current-state reads. It adds no cache, unbounded query,
dependency, automatic retry or token persistence. Server authorization remains authoritative and
session/drawer guards suppress stale responses. Protected/quarantined databases were not accessed;
this is not a claim that their contents were compared or verified unchanged.

Owner reports canonical trackers are already reconciled in ChatGPT Library. No workbook was edited.
Next evidence/status update: attach this closure report and three new live receipts, record both
frontend blockers addressed pending independent checkpoint review, retain release/commit gates.
No new Git checkpoint should be recorded.

Guarded `run.sh recover`, `teardown` and `status` completed successfully: the owned backend is stopped,
the designated database is absent, and private diagnostic/evidence files remain. Logs:
`/private/tmp/pathome-b09-closure-recover.log`, `/private/tmp/pathome-b09-closure-teardown.log`,
`/private/tmp/pathome-b09-closure-status.log`. The Session-specific fault trigger was removed before
the database was dropped. Approved disposable media assets are not removed by database teardown.
All pre-existing dirty work is preserved. Branch remains `feature/phase2a-oe-ui-integration`, HEAD
`23cbdfe8722516daaf474d58a5113e4da71d333a`, index empty. No stage, commit, push, merge or deploy.
