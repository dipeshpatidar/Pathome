# PATHOME — C26 & GENUINE BATCH C INTEGRATION EXECUTION HANDOFF

**Date:** 20 October 2026 (IST)
**Branch:** `feature/phase2a-oe-ui-integration`
**HEAD:** `285c5844df4192bdec2c0ecc771bc9676e12dcb9`
**Worktree:** 126 inherited dirty entries; 127 after this report; empty index; no stage/commit/push/merge/PR/reset/stash/clean/deploy.

## 1. Approval and preflight

The owner-approved scope was executed as a test-only package. No production source, migration, dependency,
permission, scheduler, workflow or canonical tracker was changed.

Preflight facts:

- Repository: `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`
- Expected branch and HEAD matched.
- All inherited dirty work was preserved.
- No backend listener existed at the start; port 8080 was vacant.
- PostgreSQL 14.14 was reachable on loopback port 5432.
- The 19 October canonical tracker exports were not present in the repository or supplied to this execution, so their SHA-256 values could not be independently re-read. No tracker was modified. This item remains **NOT TESTED / BLOCKED** on access to those files.
- `Recovery OK` archived stale runtime artifacts without changing database data.
- Preflight passed with the explicit local-build approval: offline Maven test-compile/plugin load and the frontend production build (`2046` modules, existing large-chunk advisory only).

## 2. Verified database, backend and media identity

- Database: `pathome_oe_test_phase2a_20261015`
- Host/port: `localhost:5432`; live server address `::1/128`
- Application role: `pathome`
- Sentinel: `DISPOSABLE`, database name matched, guard `1.0.0`
- Run identity: `d4107e59-e384-4308-916b-285111c7188e`
- Backend origin: `http://127.0.0.1:8080`, listener identity verified against the run binding
- Flyway: V1–V51 applied, `51/51` successful
- Media: the run’s existing explicitly approved disposable-account configuration; the isolation layer accepted it and the binding re-verified it. No production media credential or destination was loaded.
- No protected/shared database was opened or modified.
- The backend was stopped after evidence capture; the listener and PID/binding artifacts were removed.

The legacy provisioning/verification summaries were **not green** on this reused database: provisioning
reported `no Team-scoped grant` expected 0 / actual 5, and verification reported (a) the recorded
provisioning run as `missing-or-failing`, (b) grants outside the Admin API/bootstrap expected 0 / actual 2,
and (c) `this run revoked nothing` expected 0 / actual 1. Those failures reflect prior fixture history,
not a functional Batch C failure. They are recorded as **PARTIAL / FAIL for the legacy aggregate checks**,
while target attestation and the focused journey evidence passed.

## 3. Created or modified test-only resources

Repository files:

- `backend/src/test/java/com/indore/pathome/spaces/support/C26DiagnosticOrderer.java` (new)
- `backend/src/test/java/com/indore/pathome/spaces/support/C26ExecutionProbe.java` (new)
- `backend/src/test/java/com/indore/pathome/spaces/service/VisitOperationsPostgresIntegrationTest.java`
  (test-only ordering/probe annotations and schema identity print)
- `tools/oe-integration-env/phase2a/evidence.mjs` (recorded PASS entries no longer retain a placeholder reason)
- `tools/oe-integration-env/phase2a/batchc-closure.mjs` (new genuine Batch C harness slice)
- This report and the `chat_gpt_changes` append

Private runtime artifacts (outside Git):

- `/private/tmp/pathome-c26-20261020/` — two-arm logs, actual-order probes, audit-test log
- `/private/tmp/pathome-oe-phase2a-20261015/oe-test.env`, `media.env`
- `/private/tmp/pathome-oe-phase2a-20261015/evidence/` — attestation, fixture report and Batch C report

Run-owned database resources:

- B09 fixture preparation: 19/19 setup entries passed.
- Batch C sessions 8, 9 and 10, all created from the same run’s Requests 18, 16 and 17.
- The final evidence run used Request 17 / Session 10.
- No test schema remained after the C26 or HTTP/PostgreSQL runs.

## 4. C26 two-arm experiment

### Environment

- Target: `pathome_oe_test_phase2a_20261015`, `DISPOSABLE`, `pathome` role, local loopback, PostgreSQL 14.14.
- Each arm ran in a separate JVM with a fresh random schema.
- The test-only probe recorded actual `START`/`END` order with epoch milliseconds.

### Arm A — requested order

Schema: `visit_ops_test_9258847a389d45aaa99404b0ec958934`

1. `operationsEditBetweenPlanAndApplyRollsBackStaleAttemptAndRetriesOnce`
2. `postgresOverlapSqlStateAtStartRollsBackAndRetriesOnce`
3. `realScheduleTransactionRollbackSuppressesNotificationsAndCommitPersistsThem`
4. `concurrentLateStartAndDownstreamStartRetryDeadlockInFreshTransaction`

Result: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` in 7.696s.
The probe proved the requested order exactly.

### Arm B — reverse order

Schema: `visit_ops_test_de37297e018e435f9c7baf63a47e7be3`

1. `concurrentLateStartAndDownstreamStartRetryDeadlockInFreshTransaction`
2. `realScheduleTransactionRollbackSuppressesNotificationsAndCommitPersistsThem`
3. `postgresOverlapSqlStateAtStartRollsBackAndRetriesOnce`
4. `operationsEditBetweenPlanAndApplyRollsBackStaleAttemptAndRetriesOnce`

Result: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` in 6.894s.
The probe proved the reverse order exactly.

Evidence:

- `/private/tmp/pathome-c26-20261020/arm-a-instrumented/mvn.log`
- `/private/tmp/pathome-c26-20261020/arm-a-instrumented/order.log`
- `/private/tmp/pathome-c26-20261020/arm-b-instrumented/mvn.log`
- `/private/tmp/pathome-c26-20261020/arm-b-instrumented/order.log`

Observed retry evidence:

- `SQLState: 23P01`, simulated concurrent GE reservation, retry attempt 1 of 3.
- Transient visit-lock retry and stale-repair retry warnings appeared in both arms.
- Both arms completed with no failure and no leftover schema.

Outbox statuses, leases, worker claim counts and per-method pending due rows were not independently
snapshotted before the schema was dropped. The tests’ own outbox assertions passed. This capture gap is
explicitly not presented as complete.

## 5. C26 classification

**Classification: C — still insufficient evidence, with a stronger negative result.**

- **A — production defect proven:** not established.
- **B — test-harness defect proven:** not established.
- **C — insufficient evidence:** retained.

The four-method cohort passed in both requested and reverse order on fresh schemas. Order alone did not
reproduce the originally reported notification/outbox or late-start failures. The original failing XML,
stdout, invocation order and environment record remain unavailable, so the original failure still cannot
be attributed to production behavior or to a specific harness defect. The known harness risks remain:
the queued-only settle helper, 15-second failed-delivery backoff, the 50-row worker claim batch, shared
class schema, and timing-sensitive deadlock latches. None was proven causal.

No production or test-harness correction was applied for C26.

## 6. Genuine Batch C browser journey

Final authoritative evidence:

`/private/tmp/pathome-oe-phase2a-20261015/evidence/attempts/batch_c-1792483607987-41cf35f3-1b82-4b59-89c7-f6606137cc48/batchc-closure.json`

Result: **12/12 PASS, 0 FAIL, 0 NOT TESTED** in 18.7s.
Environment: real built React app (`frontend-web/dist`), loopback proxy, real Spring Boot, real PostgreSQL,
real JWT login, current Team capability, live Jackson serialization. Browser timezone was deliberately
`America/New_York` while the property window was `Europe/Berlin`.

| Scenario | Result | Evidence |
|---|---|---|
| BC01 real OE authentication/effective Team capability | PASS | Real login; `OPS_COORDINATE@TEAM` Team 4; Operations workspace rendered. |
| BC02 current ownership of DRAFT Session | PASS | Session 10 API `DRAFT` v4, coordinator 31, rendered as owned Visit plan. |
| BC03 genuine feasible GE discovery | PASS | GE 33 `FEASIBLE_OPTION_FOUND`; real Browse/Select card. |
| BC04 restricted availability with Jackson `displayName` | PASS | API entry `{33, "P2A ge", FEASIBLE_OPTION_FOUND}`; dialog rendered `P2A ge`. |
| BC05 owning-zone clocks | PASS | Property window rendered Berlin `12:30 pm` with browser zone `America/New_York`. |
| BC06 approved coarse status projection | PASS | Public `planStatus=RECOMMENDATION_AVAILABLE`; GE-derived statuses absent; 0 booking controls in informational dialog. |
| BC07 ownership invalidation and protected withdrawal | PASS | Alpha released ownership; reload showed 404 unavailable state; property/Berlin facts withdrawn; re-claimed. |
| BC08 stale Session version | PASS with qualification | UI detected the changed version, prevented the stale POST, showed the recovery message, and booked nothing. Genuine HTTP/PostgreSQL negative control returned **409** and no-store. |
| BC09 authoritative booking route | PASS | HTTP 200, command `8e8d8529-2d12-43b7-b2ad-9213fd818265`, Session 10 `DRAFT v4 → SCHEDULED v5`, GE 33, receipt `COMPLETED`, decision 8. |
| BC10 informational row cannot bypass booking authority | PASS | Browser informational dialog had 0 booking controls; foreign actor GET returned 404; HTTP/PostgreSQL control rejected the informational/mismatched selection with 409 and no decision. |
| BC11 audit-before-disclosure and fail-closed | PASS | `OwnedOperationsHttpPostgresTest#restrictedGeAvailabilityIsScopedPagedAuditedAndInformationalOnly`: real HTTP 500 on audit failure, no-store, no roster/name leakage, recovery after trigger drop. |
| BC12 current state, receipt and separate tenant confirmation | PASS | Current Session and receipt both `SCHEDULED`; both report `tenantConfirmationState=NOT_REQUIRED`; booking did not fabricate confirmation. |

Database evidence for the final journey:

- Session 10: `SCHEDULED`, version 5, coordinator 31, Team 4, representative 33
- Scheduled: `2026-10-21 18:00 IST`; reserved end `18:15 IST`
- Property window: `Europe/Berlin`, recorded as `18:00–20:00 IST` (`14:30–16:30 Berlin`)
- Scheduling commands: 1; completed commands: 1
- Scheduling decisions: 1
- Outbox rows: 2; notifications: 2
- Tenant confirmation: `NOT_REQUIRED`
- Audit: `OWNERSHIP_CLAIMED`, `OWNERSHIP_RELEASED`, `REQUEST_SESSION_LINKED`, and two `GE_AVAILABILITY_VIEWED` events for Session 10

## 7. Test-only harness correction

No product UI was changed. The new harness uses the approved current labels (`Visit plan`, `Visit request`)
and current components. The only harness changes are:

- A test-only JUnit orderer/probe used only when `pathome.c26.order` and `pathome.c26.probe` are set.
- A test-only schema identity print.
- The new bounded Batch C harness slice.
- Clearing the placeholder “not reached” reason when an evidence record is actually recorded.

The new harness preserves the existing target binding, attestation, same-run fixture provenance, real
authentication, real HTTP transport, current-root identity and cleanup guards. It does not claim completion
of the original 82 API scenarios.

## 8. Failure-class matrix

| Area | Status | Evidence |
|---|---|---|
| Authenticated OE and denied foreign actor | PASS | Real login/capability; foreign Session read 404. |
| Foreign Session ownership and protected facts | PASS | Release/reload 404; property and Berlin facts absent. |
| Team capability loss/regain | PARTIAL | Release/reclaim ownership proved; live Team-grant revocation during a displayed roster was not executed. |
| In-flight denial, stale success, account switch | PARTIAL | Stale-version and ownership controls pass; in-flight account/Session interleavings not rerun. |
| Cursor actor/root/version/scope mismatch | PARTIAL | Genuine HTTP test covers mismatch/stale/foreign; not all browser interleavings. |
| GE identity and DTO mapping | PASS | `displayName` and feasible entry through real serialization. |
| Whole-envelope privacy, size 1, exact-ID query | PASS | `OwnedOperationsHttpPostgresTest` focused method. |
| Property/tenant window readiness and timezone truth | PASS | Berlin property window rendered under New York browser zone. |
| Fresh candidate revalidation and competing reservation | PARTIAL | Fresh selection/receipt pass; live competing-reservation race not rerun. |
| Audit failure and recovery | PASS | Genuine HTTP/PostgreSQL audit-block control. |
| Browser transport failure/recovery | NOT TESTED | Not part of this cohort. |
| Media fixture ownership and provider isolation | PARTIAL | Binding/provider isolation and real fixture uploads passed; Batch C booking does not test media behavior. |
| Wrong database/run marker and cleanup failure | PASS for positive binding/attestation; PARTIAL for negative controls | Live marker, run binding and zero leftover schemas verified; destructive wrong-target attempts were not run. |

## 9. Verification executed

- `run.sh recover` — PASS; stale artifacts archived, database retained.
- `run.sh preflight` — PASS; offline backend test-compile/plugin load, frontend build, browser/image/media checks.
- `run.sh backend` — PASS; ready on 127.0.0.1:8080 after V51 migration.
- `run.sh attest` — PASS; exact database/user/server/marker/run identity.
- `b09-fixtures` — 19/19 setup entries PASS; 63 API coverage scenarios explicitly not run.
- C26 Arm A — 4/4 PASS, 7.696s.
- C26 Arm B — 4/4 PASS, 6.894s.
- `OwnedOperationsHttpPostgresTest#restrictedGeAvailabilityIsScopedPagedAuditedAndInformationalOnly` — 1/1 PASS, 8.854s.
- Batch C genuine browser cohort — 12/12 PASS, 18.7s.
- `git diff --check` and `git diff --cached --check` — PASS.
- Backend test compile — PASS.
- Frontend production build — PASS (existing chunk advisory only).
- No test schemas remained; backend stopped; port 8080 closed.

## 10. Checks not run

- Original C26 failing XML/stdout/order/environment: **NOT AVAILABLE**.
- Per-method outbox statuses, leases, worker claims and pending due rows: **NOT CAPTURED**.
- Full 82-entry Phase 2A API acceptance: **NOT RUN**; this is the bounded Batch C slice, not a claim of full Phase 2A completion.
- Full legacy Chromium campaign: **NOT RUN**; inherited selector drift remains outside this package.
- Live Team authority revocation during a displayed roster, 401, network interruption, browser transport recovery, in-flight account switch, competing reservation: **NOT TESTED**.
- Embedded CRM/Admin, zero/many itinerary items, 200% zoom, physical Safari/VoiceOver, Firefox/WebKit: **NOT TESTED**.
- Canonical tracker SHA-256/provenance: **NOT TESTED** because the exports are not on disk.

## 11. Production defects and scope

No new production defect was confirmed. No production scheduler, booking, authentication, role, migration,
API/DTO, dependency or UI behavior was changed. The five previously closed F1–F5 defects were not reopened.
B09/V50 behavior was not regressed by this package; the focused genuine HTTP/PostgreSQL and browser
journeys passed, while the full legacy suite remains outside scope.

## 12. Git and worktree preservation

- Branch and HEAD unchanged.
- Index empty.
- 127 dirty entries remain; inherited work preserved and this report added.
- No stage, commit, push, merge, PR, reset, stash, clean, checkout or deployment.
- No canonical tracker edit.

## 13. Owner decision

One evidence-based acceptance checkpoint is required:

1. Accept the bounded genuine Batch C slice as evidence for the covered journeys, while retaining the
   remaining failure-class and full-82 gates as open; or
2. Require the original C26 failing artifacts or a new discriminating scheduler experiment; or
3. Require the full legacy Chromium/Phase 2A campaign before Batch C acceptance.

The exact remaining owner decision is whether the Batch C slice is sufficient for the current Web V1
evidence bar given the explicit **PARTIAL/BLOCKED** items above.

## 14. Deadline impact

- **Web V1 — 31 October 2026: HIGH RISK.** The genuine React → Spring → PostgreSQL core flow is now
  demonstrated, but C26 remains class C, the full 82-scenario campaign is not green, and several
  concurrency/account/browser gates remain unexecuted.
- **Native Tenant/GE — 15 November 2026: AT RISK.** Native-specific device work is unaffected by this
  slice, but shared scheduling/availability and C26 evidence risks remain.

Batch D, original OE #14 date filtering and final cross-role business acceptance remain outstanding.

## 15. BC05 exact DOM timezone evidence correction (appended 20 October 2026, 14:54 IST)

**Status: CORRECTED AND INDEPENDENTLY RECAPTURED — BC05 PASS.** This section is appended evidence. It
does not rewrite or replace the historical results in sections 3–14.

### 15.1 Confirmations from the reconciliation

- Session 9 / Request 16 / item 11 (`2026-10-21T10:30:00Z` = 12:30 Berlin) and Session 10 /
  Request 17 / item 12 (`2026-10-21T12:30:00Z` = 14:30 Berlin) were different attempts. The previous
  BC05 record mixed their identities.
- The former assertion searched the complete dialog for `Europe/Berlin` plus a computed clock string.
  It could therefore accept a correct clock belonging to another property, another stop or a heading.
- The previous **12/12 PASS** aggregate remains a historical result for the other eleven scenarios, but
  its BC05 entry was not independently established. BC05 was **UNRESOLVED at the DOM-evidence level**
  until this correction.

### 15.2 Test-only correction

- `tools/oe-integration-env/phase2a/batchc-closure.mjs` now exports an identity-bound assertion and a
  capture mode (`BATCHC_FOCUS=BC05`) that performs one availability read without booking.
- The browser response is captured from the genuine
  `POST /api/v1/operations/visit-sessions/{id}/oe-ge-availability` call rendered by React. The assertion
  binds its `sessionId`, `sessionVersion`, item, listing, position, owning zone, UTC start/end and
  property-window revision.
- The exact `.oe-gea-stop` row is located by rendered stop position and `Property #N` identity, required
  to be unique and visible, and its actual `.oe-gea-stop-line` start and end text is compared for full
  equality against values derived directly with ICU from the backend instants and owning IANA zone.
- Session versions are re-read before, at capture and after DOM extraction. A changed version, removed
  item, changed revision, missing row, duplicate row, wrong property, wrong zone or wrong endpoint fails.
- New focused tests: `tools/oe-integration-env/tests/batchc-clock-evidence.test.mjs`. The established
  repository convention is `tools/oe-integration-env/tests/`, so the new test lives there rather than in
  a new `phase2a/tests/` directory. No product code, markup, API, schema or formatter was changed.

### 15.3 Negative-control matrix

Executed with `node --test --test-reporter=tap tools/oe-integration-env/tests/batchc-clock-evidence.test.mjs`
and saved at `/private/tmp/pathome-oe-phase2a-20261015/evidence/bc05-negative-controls.tap`.

| Scenario | Result |
|---|---|
| Correct target row, start, end and zone | PASS — exact row and complete endpoints |
| Former dialog-wide predicate with `12:30` on the target and `2:30` on another property | Old predicate **accepts** (defect reproduced); corrected assertion **FAILS** |
| Correct expected text on another property | FAIL — target-row mismatch |
| Correct clock, wrong IANA zone | FAIL — start text mismatch |
| Correct start, incorrect end | FAIL — end text mismatch |
| Incorrect start, correct end | FAIL — start text mismatch |
| Stale Session version / replaced Session / foreign response version | FAIL — no stale acceptance |
| Same clock on two stops | PASS for the uniquely targeted stop; wrong property identity fails |
| Missing or duplicated target row | FAIL — never silently skipped |
| Hidden target row | FAIL |
| Property-window revision changed | FAIL |
| Initial window edited before capture | PASS for the precise capture phase; stale API window fails |
| DST fold, non-hour offset, midnight rollover | PASS — owning-zone offsets and dates preserved |
| Browser zone differs from property zone | PASS — property zone remains authoritative |

Result: **15 tests, 15 PASS, 0 FAIL, 0 SKIPPED.**

### 15.4 Genuine React → Spring Boot → PostgreSQL recapture

Evidence:
`/private/tmp/pathome-oe-phase2a-20261015/evidence/attempts/batch_c_bc05-1792488272646-beb09dd7-bb82-448f-b534-44c045dfd0d7/batchc-closure.json`

- Run ID: `d4107e59-e384-4308-916b-285111c7188e`
- Result: **1/1 PASS, 0 FAIL, 0 NOT TESTED**
- Target: attested `pathome_oe_test_phase2a_20261015`, `DISPOSABLE`, `pathome` role, loopback
  PostgreSQL 14.14, Flyway 51/51, bound backend `http://127.0.0.1:8080`
- Fresh fixture: Request **15** → Session **11**, item **13**, listing **3**, stop **1**
- Session version before / at capture / after: **3 / 3 / 3**
- Property-window revision: `2026-10-20T09:24:33.354305Z`
- Backend UTC start/end: `2026-10-21T08:45:00Z` → `2026-10-21T14:45:00Z`
- Backend owning zone: `Europe/Berlin`
- Browser timezone: `America/New_York` (deliberately different)
- Actual DOM row: `Property #3`, one row, one unique position match, visible
- Actual rendered start: `Wed, 21 Oct, 2026 · 10:45 am Europe/Berlin`
- Actual rendered end: `Wed, 21 Oct, 2026 · 4:45 pm Europe/Berlin`
- Independently derived expectation: exactly the same complete strings
- Row metadata: `Source: PHONE · Recorded Tue, 20 Oct, 2026 · 11:24 am Europe/Berlin · Starts 0 min after the visit begins`
- Booking side effects: **0 commands, 0 decisions, 0 outbox rows**; Session remained `DRAFT v3`

Two setup iterations were required: the first focused attempt did not open the scheduling dialog, and a
second exposed the product’s `en-IN` date label comma (`21 Oct, 2026`). These were corrected in the test
expectation and harness path only; no wrong time, wrong zone, wrong property or production defect was
observed.

### 15.5 Updated BC05 disposition and cohort support

- **BC05: PASS** on an identity-bound, actual-DOM capture under a different browser timezone.
- The bounded 12-scenario cohort now has an adequately supported BC05 result. The other eleven scenarios
  retain their original conditionally accepted evidence; the full 12-scenario cohort was **NOT rerun**.
- The P2 verification defect is corrected. No production correction is justified.
- Retained run-owned fixture: Session 11 (`DRAFT v3`), Request 15 (`COORDINATING v3`), item 13. It is
  persistent and was not deleted; no immutable grant/audit history or booking truth was modified.

### 15.6 Verification and preservation

- Focused negative controls — 15/15 PASS.
- Focused genuine BC05 recapture — 1/1 PASS.
- `node --check` on the harness and new test — PASS.
- `git diff --check` and `git diff --cached --check` — PASS (empty index).
- `tools/oe-integration-env/tests/oe-harness-static.test.mjs` — 31/32 PASS. The single failure is the
  pre-existing V2–V50 migration expectation, which now also sees inherited untracked V51; unrelated to
  this correction and **NOT modified**.
- Backend and frontend builds — **NOT RERUN** because no product source changed; existing dist used.
- Branch `feature/phase2a-oe-ui-integration`, HEAD `285c584`; no stage, commit, push, merge, PR, deploy,
  canonical tracker edit or production mutation.
