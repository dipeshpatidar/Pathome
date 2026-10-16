# Phase 2A B09 — consolidated implementation and execution report

**2026-10-16 frontend closure update:** Independent review subsequently found the NOT_RECORDED
recovery trap and misleading cancelled-Session rendering in the implementation described below.
Both now have targeted corrections and fresh real UI/PostgreSQL evidence. See
[B09_FRONTEND_CLOSURE.md](B09_FRONTEND_CLOSURE.md) for the current **READY FOR INDEPENDENT CHECKPOINT
REVIEW** verdict, exact file list, 70 focused frontend tests and three live closure cases. The report
below remains historical evidence. The owner confirms canonical trackers are reconciled in ChatGPT
Library; earlier statements about tracker availability do not describe the current owner-reported state.

2026-10-16, IST. Owner-approved Option A. **B09 VERIFIED; B10–B12 VERIFIED.**
Phase 2A is not marked fully accepted: canonical tracker reconciliation and wider release checks remain separate.

## Proven cause and source sequence

The original failure was reproduced before production edits on a newly guarded disposable run.
`OperationsScheduleFlow.submit` → `OperationsWorkspace.confirmSelection` → `runGuardedCommand` →
`operationsWorkspaceService.selectGroundExecutive` → `sendOperationsRequest` issued one native fetch.
There was no React effect retry, second harness click, JavaScript retry loop, or proxy retry.

The proxy completed one genuine upstream 200 SCHEDULED and destroyed the downstream socket before
headers. Chromium netlog source 627 contains one URL_REQUEST_START_JOB, SEND_REQUEST_HEADERS,
HTTP_TRANSACTION_RESTART_AFTER_ERROR net_error -100 (ERR_CONNECTION_CLOSED), and a second
SEND_REQUEST_HEADERS on that same transaction. Thus one browser request produced two wire POSTs.
The original OE endpoint rejected the second at the DRAFT guard with 409. The old drawer then
incorrectly said nothing was booked.

Baseline receipt and netlog:
`/private/tmp/pathome-oe-phase2a-20261015/evidence/attempts/react_browser-1792090854539-a72b534e-47c8-4f75-b365-85fbdbf46ed7/`.
Baseline API: 82/82 PASS; browser B01–B08 PASS, B09 FAIL, remainder NOT TESTED. Historical receipts remain intact.

## Architecture, API, DTO and persistence

The preimplementation decision is B09_SCHEDULING.md. Existing Session-root, current scope/ownership,
GE User, linked Request, version, availability, confirmed-stop and reservation rules are preserved.
V36's PostgreSQL reservation exclusion constraint remains authoritative. No new scheduling engine,
capability, entitlement rule, dependency or external sender was introduced.

- Existing OE selection POST now requires a client UUID `commandId` in ApproveVisitRecommendationCommand.
  Its successful VisitSchedulingApprovalView response shape is unchanged. Existing five-argument Java
  construction and Admin recommendation approval remain compatible; ID is mandatory on OE selection only.
- New GET `/api/v1/operations/visit-sessions/{sessionId}/oe-recommendations/selection/{commandId}`
  returns `{commandId,state,approval}`. State is COMPLETED or NOT_RECORDED. Responses are no-store.
- V50 creates visit_scheduling_commands with primary key `(actor_user_id,command_id)`, User/Session FKs,
  operation binding, canonical normalized request JSON, original approval JSON and creation time.
  An index supports Session lookup. ORM metadata matches the migration, including its timestamp default.
- PostgreSQL ON CONFLICT arbitrates identity reuse. Receipt persistence, scheduling transition, decision
  and BEFORE_COMMIT notification outbox participate in the same transaction. Replay rechecks current
  owned scope, compares Session/operation/payload, and returns the original approval without scheduling
  or emitting side effects again. Conflicting contents are rejected and the original remains intact.
- Existing TransactionalCommandBounds are reused: transaction-local 5s lock / 10s statement limits.
  Uncommitted commands are invisible. Duplicates/receipt reads wait at the Session lock for commit or
  rollback; a timeout remains uncertain. Failed transactions leave no successful receipt or reservation.
- Receipts have no TTL or purge. Late retries remain safe. A future archival/tombstone policy needs
  separate approval. The guarantee concerns OE selection; unrelated Admin command contracts were not expanded.

## OE experience and edge cases

One UUID is saved before dispatch, with its immutable small payload, in User-scoped tab sessionStorage.
It survives component/drawer remount and refresh. Duplicate taps use the synchronous ownership lock;
unresolved intent blocks replacement IDs. Storage failure prevents sending an untracked command.
The tab store is bounded to 20 unresolved intents and clears on logout/session/account invalidation.
Credentials, contacts and candidate lists are never written into it.

The request has a 10s deadline and the overall scheduling/verification path is bounded. Lost or
untrustworthy acknowledgements show uncertainty. Checking never POSTs: it reads the actor's receipt
and the current Session. NOT_RECORDED alone does not prove failure. A real validation/conflict rejection
is paired with an authoritative absent receipt before permitting a new reviewed command.

The stored approval is historical by design, so even a successful POST is followed by a current
Session read before UI publication. Existing credential, authorization-generation, operation and
mounted-drawer guards suppress stale async results. Accepted commands whose Session later changes
use current details; the final toast fallback says booking accepted instead of falsely claiming the
current Session is scheduled. That final fallback wording was build-checked after the browser batch;
the specific intervening-Admin-cancellation race was NOT TESTED in a browser.

Approved drawer composition, CSS, focus trap, navigation and existing business workflow are retained.
No styling or unrelated product changes were made.

## Executed verification

All commands used the designated disposable target, never a fallback developer database.
Harness commands used `PATHOME_OE_TEST_ENV_FILE=/private/tmp/pathome-oe-phase2a-20261015-designation.env`.
Live API/browser commands used PATHOME_OE_TEST_PHASE2A_LIVE=true; fixture provisioning additionally
used PATHOME_OE_TEST_FIXTURE_ROLES=true. Build prerequisites used the approved external media file
and PATHOME_OE_TEST_ALLOW_LOCAL_BUILD=true.

**Focused backend unit/identity/window checks — VERIFIED 40/40, zero failures/errors/skips**

`mvn -o -q -f backend/pom.xml -Dtest=VisitOperationsServiceTest,VisitOperationsAuthorizationServiceTest,VisitOperationsControllerIdentityTest,SchedulingWindowValidatorTest test`
with the existing JDK17/Maven PATH. XML reports remain under backend/target/surefire-reports.

**Real PostgreSQL HTTP + transactional module tests — VERIFIED 120/120, zero failures/errors/skips**

`bash tools/oe-integration-env/phase2a.sh scheduling-tests`
executes OwnedOperationsHttpPostgresTest (29) and VisitOperationsPostgresIntegrationTest (91).
The test launcher positively proves disposable identity/run/backend, applies environment isolation,
and builds a private source snapshot so compilation cannot mutate the live backend class directory.
The two suites also refuse an undesignated JDBC URL and pin Hikari to their own disposable schema.

Final bounded-command test evidence:
`/private/tmp/pathome-oe-phase2a-20261015/evidence/b09-postgres-bounds-tests.log`
and `/private/tmp/pathome-oe-phase2a-20261015/test-build-7b28a749-91b0-4052-846c-e13aed5cf6ff/target/surefire-reports/`.

These executed normal selection, actual simultaneous identical HTTP requests returning the same
approval, same-ID changed payload, different commands against scheduled state, cross-Session same-ID
arbitration, foreign actor denial, existing two-Team concurrent GE contention, unavailable/deactivated
and uncovered GE, stale versions, property-window/reason guards, outbox and decision counts.
The new interruption test paused the real decision write, located the live PostgreSQL backend,
terminated that connection using pg_terminate_backend, observed HTTP 500 and complete rollback,
then successfully recovered with the exact same command ID. This is an executed database-connection
interruption, not a claimed JVM/power-loss test.

**Frontend focused tests — VERIFIED 60/60, zero failures/skips**

`node --test frontend-web/src/tests/operationsSchedulingReliability.test.mjs frontend-web/src/tests/operationsScheduling.test.mjs frontend-web/src/tests/operationsWorkspaceIntegration.test.mjs`

Includes intent persistence/remount, conflicting local reuse, storage failure, identity replacement,
late response suppression and read-only NOT_RECORDED handling. These are unit checks, separately
identified from real React acceptance. Final output: `/private/tmp/pathome-b09-frontend-tests-complete.log`.

**Harness gates — VERIFIED 56/56 plus guard/isolation self-tests**

`bash tools/oe-integration-env/phase2a.sh verify-static`
Final output: `/private/tmp/pathome-b09-static-final-verified.log`.
V50 ordering and single-JavaScript-command/multiple-wire-attempt semantics are asserted explicitly;
no failing booking assertion was removed to hide the original defect.

**Compilation/build/hygiene — VERIFIED**

Guarded run.sh preflight executed offline Maven test-compile/plugin readiness and controlled
`npm --prefix frontend-web run build` (tsc + Vite), including a final build after the toast fallback.
The pre-existing large-bundle advisory remains. `git diff --check` and `git diff --cached --check` passed.

## Real API and Chromium evidence

Final run: `bad5b70d-b947-472e-8550-f98e7d36359f`.

- API: `bash tools/oe-integration-env/phase2a.sh acceptance` — **82/82 PASS**, F01–F19 and C01–C63,
  no FAIL/NOT TESTED. Actual Cloudinary uploads used only the approved disposable test account.
  Receipt: `/private/tmp/pathome-oe-phase2a-20261015/evidence/attempts/api-1792092124420-6d90491c-9032-42c7-8fd4-eec63e0aba38/phase2a-api.json`.
- Full React batch: `bash tools/oe-integration-env/phase2a.sh browser` — all B01–B12 executed.
  Receipt: `/private/tmp/pathome-oe-phase2a-20261015/evidence/attempts/react_browser-1792092278190-00df422d-edb8-4f18-ac83-11f06ee4cfcb/phase2a-browser.json`.
  B11's initial observer receipt was subsequently superseded by the corrected exact-request tail below.
- Final exact-request B10/B11 verification:
  `bash tools/oe-integration-env/phase2a.sh browser --scheduling-tail` — **2/2 PASS** on the same
  proven run/booking. The tail requires prior real B09/B12 success; it never fabricates those results.
  Receipt: `/private/tmp/pathome-oe-phase2a-20261015/evidence/attempts/react_browser_tail-1792092376419-58c071d3-a7f3-4c16-b57a-62b6f58e5b84/phase2a-browser.json`.
  B11 held the full my-work request, proved it had not settled before the actual account switch,
  then observed both proxy ABORTED_BY_BROWSER and BROWSER_ABORTED_OLD_RESPONSE. Old work remained
  absent before and after a separate new-account refresh/read.

**B09/B12 exact business evidence:** a real double-click produced one JavaScript selection request.
Chromium made two wire attempts with the same UUID `394c771a-67f7-4ce8-b4f8-3df5ff9a470a` and identical
payload digest; both upstream statuses were 200. The first acknowledgement was destroyed, the replay
acknowledgement was withheld until the real client deadline, and the drawer showed uncertainty.
An additional real receipt-read acknowledgement was withheld: verification timed out and the UI
remained uncertain. After real Reload, the saved intent was recovered and GET receipt + current
Session confirmed the booking without another selection POST.

PostgreSQL changed Session 3 DRAFT v4 → SCHEDULED v5, with exactly one completed command, one decision,
two outbox records and two delivered stored notifications. Operational audit count stayed 48.
After reconciliation every one of those counts and v5 were unchanged. B10's later separately labeled
Admin reschedule and tenant confirmation are legitimate later transitions, not command replay effects.

**B10 VERIFIED:** real Tenant UI acceptance of a genuine Admin time proposal, HTTP 200 and
CONFIRMED state. **B11 VERIFIED:** real account switch with the outstanding old-account request above.

Uncertain and confirmed scheduling drawers were checked at 320, 375, 390, 414, 430, 768, 1024, 1280
and 1440 × 900 in Chromium, reduced-motion context. Every measured page had scrollWidth <= innerWidth;
the drawer stayed inside the viewport, visible controls had 44px targets and accessible names,
modal labeling existed, and Tab/Shift+Tab stayed inside the dialog. Screenshots at 375 and 1280px
were inspected. These checks cover these drawer states, not every OE screen at every viewport.

## Failed attempts and resolved causes

All private attempts remain; no failure was overwritten or counted as acceptance.
The original product B09 failure is preserved. A test-only ORM schema initially lacked V50's
creation-time default; matching metadata fixed it without weakening booking assertions.
A first concurrent Maven rebuild temporarily removed a class from spring-boot:run's live classpath,
causing B01 500. Private test builds eliminate that interference. A restart required fresh attestation;
the guard correctly refused an old listener proof. A missing external-media input also correctly refused setup.

B10's old fragment-only browser navigation left React on Explore homes; the test now uses the actual
Visit requests control. B11 now opens Operations for the newly signed-in account before expecting
queues; it tracks request abort as well as completion and matches the proxy's non-attention queue.
Those were harness corrections, not production lifecycle or navigation changes.

## Operational state and limitations

Flyway attestation proved baseline v1 + V2–V50: 50 successful versions, 0 failed migrations,
47 public tables including 2 harness infrastructure tables, and 1 expected view.
Migration and application writes occurred only on the guarded disposable database.
Final guarded recover, teardown and status: backend not running, database absent; private evidence
and generated test builds retained. Approved Cloudinary test assets were not deleted by database
teardown; no claim of external media cleanup is made. No protected/quarantined database was accessed
or modified in this task. No new external service/provider was enabled.

NOT TESTED: Spring JVM kill or machine power loss; Safari/WebKit, Firefox and physical devices;
complete screen-reader/contrast/WCAG audit; all workflow states across all viewports; a live scheduling
write acknowledgement arriving specifically during account switching (pending-intent isolation was
unit-tested; B11 exercised the real old-account queue); intervening Admin cancellation before a
successful selection's current-state read. The final toast fallback for that last case is build-checked.
Full unrelated suites and deployment were not run. No remaining B09 application defect was found
within the executed coverage. The existing Vite bundle-size advisory remains outside this scope.

## Canonical tracker reconciliation — BLOCKED, no edits made

Neither requested canonical Updated workbook was present in the targeted local/attachment/download
searches, and Codex Document Control returned no connected workbook session. The October 14 handoff
explicitly says not to substitute older local workbooks for the canonical Library versions.
Thus exact canonical row IDs/statuses could not be inspected and are not invented.

Proposed owner-approved reconciliation:

- Pathome_Cross_Role_Development_Tracker_Notifications_Updated.xlsx: attach B09 evidence to OE/GE
  scheduling, current authorization, receipt/retry, notification/outbox and integration dependencies;
  record B10 tenant confirmation and B11/B12 isolation/reconciliation evidence, retaining release gates.
- Pathome_Visit_Session_Implementation_Packages_Updated.xlsx: associate V50 and transactionally
  idempotent OE selection with the affected scheduling package; attach 120 PostgreSQL tests and live
  B09–B12 evidence; preserve separate entitlement, lifecycle and broader release acceptance gates.

These are proposed changes only. No workbook was edited. Do not mark Phase 2A fully accepted solely
from this report. Next step: make the canonical workbooks available for read-only reconciliation,
then separately approve exact tracker updates and any remaining release/device checks. No further
implementation, commit, push, merge or deployment is initiated by this task.

## Git and exact changed files

Repository `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`;
branch `feature/phase2a-oe-ui-integration`; HEAD remains
`23cbdfe8722516daaf474d58a5113e4da71d333a`. The index is empty. Existing dirty harness/log work was
preserved. No stage, stash, reset, branch switch, commit, push, merge or deployment was performed.
The attempted fetch did not complete and was terminated; cached remote ref equals HEAD, but remote
freshness is not claimed. Permissions, identities, entitlements, reservation rules and unrelated UI
remain unchanged. Scope expansion was limited to the expressly approved B09 API/DTO/schema/UI work
and necessary tests, harness corrections and evidence documentation.

The following 31 files were created or edited by this request (other pre-existing dirty paths are not
claimed as changes made here). Paths are relative to the repository root above:

- `backend/src/main/java/com/indore/pathome/spaces/controller/VisitOperationsController.java`
- `backend/src/main/java/com/indore/pathome/spaces/dto/ApproveVisitRecommendationCommand.java`
- `backend/src/main/java/com/indore/pathome/spaces/dto/VisitSchedulingCommandView.java`
- `backend/src/main/java/com/indore/pathome/spaces/entity/VisitSchedulingCommand.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/OwnedOperationsAuthorizationService.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/VisitOperationsService.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/VisitSchedulingCommandStore.java`
- `backend/src/main/resources/db/migration/V50__visit_scheduling_commands.sql`
- `backend/src/test/java/com/indore/pathome/spaces/service/DisposableSchedulingTestDatabase.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/OwnedOperationsHttpPostgresTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/StaffAccessGovernancePostgresTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/VisitOperationsPostgresIntegrationTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/VisitOperationsServiceTest.java`
- `frontend-web/src/components/operations/OperationsScheduleFlow.tsx`
- `frontend-web/src/components/operations/OperationsWorkspace.tsx`
- `frontend-web/src/services/operationsWorkspaceService.ts`
- `frontend-web/src/utils/operationsSchedulingIntent.ts`
- `frontend-web/src/tests/operationsSchedulingReliability.test.mjs`
- `frontend-web/browser-verification/operations-workspace-real-backend/README.md`
- `tools/oe-integration-env/run.sh`
- `tools/oe-integration-env/phase2a.sh`
- `tools/oe-integration-env/expected-schema.txt`
- `tools/oe-integration-env/lib/lifecycle.mjs`
- `tools/oe-integration-env/phase2a/browser.mjs`
- `tools/oe-integration-env/phase2a/fullstack.mjs`
- `tools/oe-integration-env/tests/oe-harness-static.test.mjs`
- `tools/oe-integration-env/tests/oe-blocker-closure.test.mjs`
- `tools/oe-integration-env/PHASE2A.md`
- `tools/oe-integration-env/B09_SCHEDULING.md`
- `tools/oe-integration-env/B09_RESULTS.md`
- `chat_gpt_changes`
