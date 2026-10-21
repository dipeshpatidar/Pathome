# PATHOME R1-OE-1 — RESUMED CLOSURE & FINAL SOL HANDOFF

Date: 19 October 2026 (IST)  
Repository: `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`  
Branch: `feature/phase2a-oe-ui-integration`  
HEAD: `285c5844df4192bdec2c0ecc771bc9676e12dcb9`  
Status: **AGENT-IMPLEMENTED / AGENT-TESTED — NOT INDEPENDENTLY ACCEPTED OR RELEASED**

## A. Recovered baseline and interruption boundary

- Branch and HEAD were unchanged from the accepted B09 checkpoint.
- The Git index is empty. The worktree has 72 status entries; this is the pre-existing accepted P2/B09/R1/Navbar work plus the resumed R1 files.
- No reset, stash, clean, checkout, stage, commit, push, merge, PR, deployment, tracker edit or `chat_gpt_changes` edit was performed.
- The interrupted work was resumed from the existing E1 and harness edits. Completed evidence was not repeated except where a new control or a final verification materially required it.
- No Maven test, held Request, Spring test, Chromium or fault-proxy process from this run remains.
- A pre-existing long-running `spring-boot:run` process (PIDs 89092/89282, run directory `/private/tmp/pathome-oe-phase2a-20261015`) was observed. It was not adopted, restarted or killed and did not use the R1 schema.

Key files changed in the resumed batch:

```text
frontend-web/src/components/TenantDashboard.tsx
frontend-web/src/utils/tenantAvailability.ts
frontend-web/src/tests/tenantAvailability.test.mjs
frontend-web/browser-verification/tenant-availability/run.mjs
frontend-web/browser-verification/tenant-availability/genuine-runner.mjs
frontend-web/browser-verification/operations-completion/run.mjs
backend/src/test/java/com/indore/pathome/spaces/service/TenantVisitAvailabilityTopologyPostgresTest.java (new)
backend/src/test/java/com/indore/pathome/spaces/service/OperationsTenantContactHttpPostgresTest.java
backend/src/test/java/com/indore/pathome/spaces/service/TenantVisitAvailabilityHttpPostgresTest.java
backend/src/test/java/com/indore/pathome/spaces/service/TenantVisitAvailabilityServiceTest.java
tools/oe-integration-env/R1_OE_1_RESUMED_CLOSURE_20261019.md (this report)
```

The prior uncommitted production and test files were preserved. No new API, DTO, migration, permission, scheduler, entitlement, City/timezone mapping or external messaging was added.

## B. E1 — late Save success cannot defeat the review latch

Root cause: `saveAvailabilityDraft` acknowledged a successful v4 response and then unconditionally wrote `reviewRequired: false`. The reducer correctly refused to regress v5, but the callback still cleared the review gate and re-enabled Save/Clear.

Production correction:

- `TenantDashboard.tsx:1534` now calls `settleTenantAvailabilitySaveAcknowledgement`.
- `tenantAvailability.ts:791` compares the acknowledged snapshot with the latest observed authority, including exact facts for equal versions.
- An older acknowledgement releases only its own attempt metadata. It preserves the newer observed facts, reviewed baseline, dirty form/provenance and latch, and states truthfully that newer saved times arrived.
- A current acknowledgement completes the normal saved transition.
- Explicit rebase remains the only way to adopt the newest facts and invalidate the prior editor generation.

Evidence:

- Broken-before mounted control: **5 failures / 8 checks**. The late v4 cleared the v5 latch, enabled Save/Clear and removed the Rebase action.
- Fixed-after focused mounted scenario: **11/11 checks PASS**.
- `tenantAvailability.test.mjs`: **17/17 PASS**, including normal current acknowledgement, older acknowledgement regression, further edits, zone change, preferred removal and rebase.
- Combined focused frontend files: **29/29 PASS**.
- Full tenant mounted pass: **62/62 checks PASS**, including the held-v4/v5 scenario, review-notice focus, Rebase keyboard activation, Check keyboard activation, protected-switch alert/action and remount identity controls.

## C. H1–H9 browser harness — strict test-only controls

The Operations harness now:

- keeps immutable separate A-owned and B-owned Session fixtures; GET never mutates A's coordinator;
- denies receipt reads without authentication or with a foreign actor/root before `NOT_RECORDED`;
- keys receipt barriers by authenticated actor, exact Session root and exact UUID GET;
- holds A and B receipt barriers independently and proves stale A cannot release, close, reopen, publish into or clear B's pending operation;
- asserts completed receipt equality for the full six-field command and rejects changed GE, version, time, zone and reason;
- preserves malformed UUID, impossible date, one-nanosecond and negative-GE controls;
- keeps H4 outcome copy semantic and does not change production booking behavior.

Red controls were executed against isolated pre-repair copies:

- anonymous/foreign receipt authority removal: **2 failures**;
- GET ownership mutation restored: **1 failure**;
- actor-unbound latch restored: **4 failures**.

Current-source controls:

- stale-session group: **18/18 PASS**;
- required six affected groups plus E9 fresh-open retry: **125/125 checks PASS** (41 + 18 + 15 + 12 + 30 + 5 + 4);
- one bounded full confirmation: **425 checks, 1 failure** — `account switch during booking` timed out in `page.goto`. The same scenario rerun alone: **1/1 PASS**. This is recorded as a full-run navigation timeout, not a proven production regression.

No production booking code was changed to satisfy the harness.

## D. E5 — grant expiry during canonical lock wait

`OperationsTenantContactHttpPostgresTest`: **11/11 PASS**.

The decisive run observed:

```text
revealPid=43515
blockerPid=43537
dbBefore=2026-10-18 18:08:00.236869+05:30
dbAfter=2026-10-18 18:08:05.207435+05:30
expiresAt=2026-10-18T12:38:05.182544Z
```

- The reveal was demonstrably blocked on the canonical Request lock before fresh admission.
- The fixed grant expired naturally while blocked; `expires_at` was not rewritten.
- Final response was a generic denial (403/404), with no tenant name/number and zero disclosure audit.
- An unexpired equivalent owner produced 200 and one metadata-only audit.
- A reveal already admitted before expiry but held past expiry completed under frozen admitted-operation semantics.
- Ownership transfer, revocation and staff deactivation were exercised in both orders with real PostgreSQL blockers.
- Standalone-versus-linked contact topology and Session-owner positive controls are covered.

Limitation: City drain with active work cannot legally be reached in both orders because City deactivation requires every Team in the City to be inactive, while active work blocks Team deactivation. The existing sequential active-Team/City refusal remains checked. No forbidden state bypass was used. This specific City-drain both-order race is **NOT TESTED / not legally reachable**; E5 is otherwise closed for this package.

## E. E6 — real topology rollback and rediscovery

New real PostgreSQL/Spring test class: **2/2 PASS**.

The test-only repository proxy pauses immediately after the real unlocked topology discovery. During the pause, the supported production coordination workflow commits the Request→DRAFT Session link. The first attempt then locks the Request, detects the changed topology and rolls back. The service starts a fresh transaction, rediscovers the linked Session, takes the canonical Session→Request order, and returns a legal current-version conflict because linking advanced the Request version.

Evidence from the final run:

```text
firstTx=395801
secondTx=395803
final trace=topology:topologySession=null, locked:requestSession=null,
            locked:requestSession=1, topology:topologySession=1,
            locked:requestSession=1
```

- Distinct transaction identities prove a fresh retry transaction.
- The first locked attempt left no availability mutation; a `FOR UPDATE NOWAIT` probe succeeded before the retry lock, proving the first attempt's lock was released.
- The retry's own locked Request trace is present.
- The retry's outcome is a lawful 409 current-version refusal, not an automatic write.
- An ambient `TransactionTemplate` call to the actual Spring service proxy threw `IllegalTransactionStateException` before repository discovery (`discoveries=0`); a nonambient control passed.
- Stale-version and invalid-zone failures each performed exactly one discovery, not a topology retry.
- `TenantVisitAvailabilityServiceTest` verifies exactly three defensive topology exceptions → one final conflict and no fourth attempt (`verifyNoMoreInteractions`).

No production lock, retry-budget or transaction policy was changed.

## F. E7 — genuine tenant PUT and identity isolation

Genuine browser/Java gate: **Java test PASS; runner 14/14 checks PASS**.

- Request created through the real API; real history row read back.
- Commit-then-drop: one PUT, guarded recovery read, current saved values, no second PUT.
- Rollback-then-drop: the **original authorized PUT** was sent unchanged. A test-owned PostgreSQL trigger raised a real persistence failure. The response was dropped. Stored values and version remained exactly unchanged, the draft/review remained, and no second PUT occurred.
- `expectedRequestVersion` was the committed version (1), not `-1`.
- Recovery-read failure remained readable and unresolved; no resend.
- Clear persisted all four authoritative nulls; late older history did not resurrect the window; pagination displacement retained the exact draft.
- `genuine-db-summary.json`: commands=0, decisions=0, outbox=0.
- The trigger and function were dropped, and the isolated schema was removed.

Mounted identity controls:

- held A PUT across account A→B: no B publication, no second PUT, post-switch reads used no A credential;
- same-user token rotation: stale finalizer rejected, rotated read used the new token;
- remount: no stale success/draft resurrection; reopened editor started from authoritative v3.

## G. E9 — mounted opening, keyboard and recovery

- Operations fresh-open transient 503: drawer stayed closed, explicit Try again appeared, retry performed exactly one new current-root read, then the current owned DRAFT drawer opened. **4/4 PASS**.
- Tenant mounted pass **62/62**: E1 held-v4/v5, E3 recovery, E4 protected switch, E7 identity controls, Review/Rebase/Check focus and keyboard activation, 390/1280 overflow, 320 long error, 44px targets, 16px mobile editors and reduced motion.
- B09 pending/receipt recovery remains covered by the full-journey and lost-booking-response groups; no duplicate booking or automatic retry was observed.
- Physical iPhone Safari, Firefox/WebKit, full screen-reader/WCAG and production external delivery remain **NOT TESTED** release gates.

## H. Database safety and cleanup

Read-only preflight and post-run checks:

- Database: `pathome_r1_test_20261018`
- Server: `127.0.0.1:5432`
- User: `dipeshpatidar`
- PostgreSQL: `14.14 (Homebrew)`
- Sentinel: exactly 1
- Run ID: `run-20261018-r1-5d15916404774680b6aae1ec41732b01`
- Initial and final non-system schema: `public` only
- Protected `pathome_db`, shared/quarantined databases and unrelated grants/audits were not accessed.

New schemas were UUID-named, test-owned and dropped after each class. E5, E6 and E7 post-run checks found no `tenant_contact_http_test_*`, `tenant_avail_topology_test_*` or `tenant_availability_http_test_*` schema remaining. The sentinel and run marker were unchanged.

## I. Verification commands and counts

```text
node --test frontend-web/src/tests/tenantAvailability.test.mjs                    17/17 PASS
node --test tenantAvailability + tenantAvailabilityRecovery + freshDrawer          29/29 PASS
node frontend-web/browser-verification/tenant-availability/run.mjs                62/62 PASS
Operations required groups + E9 retry                                            125/125 PASS
Operations full confirmation                                                     425 checks, 1 navigation timeout; isolated rerun PASS
mvn -Dtest=TenantVisitAvailabilityServiceTest,TenantVisitAvailabilityTransactionTest,
    VisitOperationsServiceTest,AvailabilityTimezoneContractTest test                54/54 PASS
mvn -Dtest=OperationsTenantContactHttpPostgresTest test                           11/11 PASS
mvn -Dtest=TenantVisitAvailabilityTopologyPostgresTest test                        2/2 PASS
mvn -Dtest=TenantVisitAvailabilityHttpPostgresTest test                            8 tests, 7 executed PASS, 1 browser-gated skip
genuine mounted E7 Java gate                                                       PASS
genuine browser runner                                                             14/14 PASS
npm --prefix frontend-web run build                                                PASS, 2043 modules
mvn -q -f backend/pom.xml -DskipTests test-compile                                 PASS
git diff --check                                                                   PASS
```

The build retains the existing Vite chunk-size advisory only.

## J. Original 30-scenario continuity

| IDs | Area | Result |
|---|---|---|
| 01–04 | E1 late acknowledgement / reviewed baseline / stale history | PASS, mounted + unit + negative control |
| 05 | E5 contact expiry / linkage / City-Team lifecycle | PASS except City-drain both-order race NOT TESTED / not legally reachable |
| 06–15 | Prior accepted P2/B09/R1 behavior | Inherited accepted evidence; no regression run |
| 16 | E6 topology retry/rollback/rediscovery | PASS, real PostgreSQL/Spring |
| 17 | E1/E7 late acknowledgement, genuine uncertain PUT | PASS |
| 18–20 | Prior accepted coordination/cancellation/lifecycle | Inherited accepted evidence |
| 21 | E9 fresh opening / recovery / mounted UX | PASS |
| 22–25 | Prior accepted scheduling/contact/notification seams | Inherited accepted evidence |
| 26 | Identity/account/token isolation | PASS, controlled mounted transport |
| 27 | Keyboard/focus/responsive critical paths | PASS at 320/390/1280, reduced motion |
| 28 | Physical iPhone Safari | NOT TESTED — release gate |
| 29 | Production crash/recovery/external notification delivery | NOT TESTED — release gate |
| 30 | No new scheduler/API/migration/WhatsApp/lead scope | PASS by diff/API inventory |

## K. Read-only tracker and deadline assessment

Canonical 18 October fingerprints supplied by the owner:

```text
Cross-Role:   39c55437b0ef3c0e82a91d0a79ee8fdac0ba4990fa70fe9f8ee9233ce1d34477
Visit Session: b2f52f6c6d26162b510435d568a433cd1d657f500cbd685690ee18fa29f5590a
```

The Mac-local workbooks found were older:

```text
Cross-Role:   031590c74a80fdf9d324d53917fafde9a07145a90feca5407e9fb40b8757b3de
Visit Session: 345aa3d37e33f3a1db894818308125a2c9b908045e943309b56b6c5cb6a333ef
```

They were read only. The canonical ChatGPT Library copies were not available in this workspace, so this is an older-copy assessment, not authoritative 18 October reconciliation.

- Cross-Role DEP-034 remains In Progress with OE/R1 acceptance pending; DEP-012/028/029/030/032 remain related open operational gates.
- Visit Session Package 2A and B09/P2 gates remain implemented/verified with later correction history; no tracker cell was edited.
- Batch A remains OE #3/#4/#7/#16. Batches B/C/D and cross-batch questions remain separately governed.
- Web V1 (31 October 2026) and native Tenant/GE (15 November 2026) remain at risk until independent acceptance, device/browser/WCAG and release gates close. No MVP reduction is claimed.

## L. Remaining limitations and next boundary

No new confirmed production defect was found in the resumed work. Remaining limits:

1. City-drain versus contact in both orders is not legally inducible without forbidden Team/City state bypass; Team drain both orders is covered.
2. The one full-harness navigation timeout was not reproduced in isolation; it remains an observed flake, not a PASS claim.
3. Physical iPhone Safari, Firefox/WebKit, full screen-reader/WCAG and production external notification delivery remain release gates.
4. Canonical tracker copies were unavailable; only older Mac copies were assessed.

This report stops at the Sol handoff boundary. It does not claim independent Batch A acceptance, complete Web V1, release readiness, or permission to begin OE Batch B/C/D.

Recommended next action: one focused GPT-6.1 Sol High independent acceptance of the corrected E1/H1–H9 controls and the E5/E6/E7/E9 evidence above, without rerunning the prior P2/B09 campaigns.
