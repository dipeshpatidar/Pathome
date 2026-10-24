# PATHOME — ROUTING-CLOSURE-01A169A2 EXECUTION REPORT

Date: 24 October 2026, IST  
Repository: `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`  
Branch/HEAD: `feature/phase2a-oe-ui-integration` / `a943775e07a153087d0b38c315db20e18b30aec5`  
Git index: empty; no stage, commit, push, merge, PR, reset, stash or branch switch.

## A. Input and source integrity

Verified the independent 23 October review and its checksum file, the prior acceptance report, final manifest and evidence checksum at their recorded paths. The expected report, manifest and evidence-bundle hashes all matched. Both canonical 23 October workbooks were read-only and matched their expected SHA-256 identities:

- Cross-Role: `cdc036af9efa0ab03a46b83a3ac80c4dcc87e10c6c9c7c5e9fb2ec987902297c`
- Visit Session: `011adba3ae3391ddba5d42eb7a9ae578d6a9cf6edc9b12b6f91a4bd7c9e2e3dc`

The available Fast & Safe v1.0, GPT/Sol Solution-First v1.1, frozen Staff/City/Team V1.1, listing-review extension, routing review and 22 October continuation handoff were consulted. The standalone complete frozen Visit collection and standalone Premium Responsive UX v1.1 text were not available; no claim is made that those unavailable originals were read.

Original recoverable checkpoint:

`/Users/dipeshpatidar/Documents/Pathome-Routing-Closure-01A169A2-source-checkpoint/`

- 1,579 tracked/nonignored files archived with per-file SHA-256.
- Archive SHA-256: `47627aa768f7a957f6c3ef8c17c19454948556fe698ef540e58149aac757898e`
- Per-file manifest SHA-256: `5b2f8350090a8d1b919f6ae23c050c835d0e92f70390a7de7418fb4e438a127d`
- Safe extraction rehearsal recovered all 1,579 members and every hash matched.
- `.env.local` was excluded by Git ignore. The nonignored local environment file contains designation keys only, with no password, secret or token values.

Corrections delta from the checkpoint: 13 modified and 3 added source files; no removals. V53 remains `15b856ad...d640d3`, V54 remains `50136181...de900`; no migration changed.

## B. Defect resolution

### F1 — corrected and genuinely verified

Root cause 1: both oversight `UNION ALL` branches referenced nonexistent `t.display_name` on the `authorized_teams` CTE.

Correction: both branches now read `t.team_display_name`. Mixed Request/Session results also exposed a second failure in the same private SQL path: PostgreSQL returned `TIMESTAMPTZ`, while the unchanged DTO requires `LocalDateTime`. Both branches now cast `created_at` to `timestamp`; no DTO or API change was made. Deterministic ordering is `created_at, target_id, target_type`.

Evidence:

- Empty-result corrected SQL executes; in-memory old-alias SQL fails with PostgreSQL `column t.display_name does not exist`.
- Authentic HTTP and PostgreSQL returned 200 for empty, Request-only, Session-only, mixed, 1/2/20/21/22-row, first/second/out-of-range pages.
- City scope, wrong-City exclusion, global diagnostic visibility, stable duplicate-free pages, equal timestamp/equal numeric ID Request/Session ordering, and Team-only denial were exercised.
- 19/19 genuine Chromium checks passed at 1280px and across 320/375/390/430/768/1024/1280/1440: real login, real Spring/PostgreSQL responses, strict unclaimed 200, no overflow, focus, and held focus revalidation without a skeleton or page replacement.

### F2 — corrected and genuinely verified

Root cause: the browser runner accepted every response with `status >= 200` and did not assert required body settlement.

Correction: exact per-endpoint/method statuses, required JSON contracts, required-response settlement, independent proxy-forwarding records, a settled response ledger, and failure evidence on exceptions. The old broad predicate remains only as a negative control and accepts 401/500; the replacement rejects 401, 403, 404, 409 and 500 and fails missing, malformed, late and timeout cases.

Result: 3/3 runner-contract tests passed. One connected run correctly failed against a stale HTTP 500 backend. After restarting the owned backend, the corrected genuine browser pass succeeded 19/19.

### F3 — evidence and reporting corrected

This report supersedes prior optimistic wording:

- Direct-service fixtures are not labelled genuine Tenant HTTP intake.
- C06 automatic uses real tenant login, real Tenant HTTP intake, worker routing, two real HTTP claims, winner/loser readback and My Work evidence.
- The earlier scenario did not establish My Work merely by creating service-level work.
- One grant-revocation sequence did not establish both revocation orders; the new HTTP test performs City-first and Team-first independently.
- Simultaneous thread completion is not a lock graph; C04 records two waiting backend PIDs with non-empty `pg_blocking_pids`.
- Historical Flyway attempts are not described as occurring before migrations.

Historical evidence files were not rewritten.

## C. PostgreSQL safety

Exact approved target used:

`127.0.0.1:5432/pathome_oe_test_routing_closure_20261023_01a169a2`

The pre-write check proved the database and both exact roles were absent on loopback before creation. Post-create attestation proved:

- One exact disposable marker and run ID.
- Schema owner `routing_closure_01a169a2_migrator`.
- Distinct migrator/runtime roles.
- Runtime schema/public/database CREATE all false.
- No runtime superuser, create-db, create-role, replication, bypass-RLS or inherited role powers.
- No protected database was opened; no external provider/media operation occurred.

Cleanup verified database count 0, exact role count 0 and no port-8080 listener. Evidence:

`/private/tmp/pathome-routing-closure-20261023-01a169a2/evidence/closure/`

## D. Genuine closure matrix

| Scenario | Result | Evidence |
|---|---|---|
| C01 oversight SQL, mixed types, scope, paging | PASS | Corrected repository/HTTP tests and connected browser receipt |
| C02 one-condition authorization and historical upgrade | FAIL | V53 Request retained/no enrollment PASS; live routing-only visibility and direct-read concealment FAIL |
| C03 worker/manual serialization | FAIL | Worker-first 409 and final state PASS; manual-first worker throws `ObjectOptimisticLockingFailureException` instead of a clean terminal/no-op result |
| C04 worker/coverage/disable lock graph | PASS | Forced contention; waiting City and control PIDs with actual blocking-PID arrays; one route/audit; no torn/idle-aborted transaction |
| C05 retry/backoff and restart | PARTIAL | 5/10/20/40/80/160/300 seconds, attempt 8 NEEDS_REVIEW and precision passed deterministic unit coverage; real interrupted Spring child restart NOT TESTED |
| C06 automatic Tenant → Team → HTTP claims → My Work | PASS | Real login/intake, one route, one 204 winner/one 409 loser, owner-scoped My Work, one SYSTEM and one USER audit, no Session side effect |
| C06 manual exception → HTTP claim → My Work | FAIL | Real `OPS_ROUTING@CITY` route returns 403 from `requireFreshAdminForReconciliation` before the approved manual route |
| C07 owner grants/revocation | PARTIAL | Authenticated HTTP City-first and Team-first revocation independence PASS; connected delayed A/B account-switch browser journey NOT TESTED |

### Verification commands executed

```text
mvn -o -q -f backend/pom.xml -DskipTests test-compile
node --test --test-reporter=tap tools/oe-integration-env/tests/routing-acceptance-guard.test.mjs tools/oe-integration-env/tests/routing-browser-contract.test.mjs
mvn -o -q -f backend/pom.xml '-Dtest=AutomaticRoutingAcceptanceHttpPostgresTest#c01UnclaimedWorkIsSqlCorrectScopedAndStableAtEveryPageBoundary+c04ForcedWorkerCoverageAndDisableContentionHasARealLockGraph+c06GenuineTenantToHttpClaimAndMyWork+ownerCanGrantTeamAndCityDutiesAndRevokeThemIndependentlyThroughAuthenticatedHttp,RoutingOversightRepositorySqlTest,RoutingOversightRepositoryPostgresTest,RoutingAcceptanceMigrationPostgresTest,RoutingAcceptanceContractLiveTest,AutomaticRoutingDomainTest' test
node tools/oe-integration-env/routing-acceptance-browser.mjs
git diff --check
git diff --cached --check
```

The green class selector above passed 10 tests with 0 failures/errors/skips. C02, C03 and manual C06 were then re-run individually with their exact class/method selectors; all three produced the named failures recorded above and retained separate Surefire XML/log receipts. The exact target environment was established through `tools/oe-integration-env/routing-acceptance-target.env`, guarded preflight, positive attestation, and the guarded `run.sh stop-backend` / `run.sh teardown` paths.

## E. Confirmed new findings requiring approval

### P1 — routing-only oversight exposes non-enrolled and canonical-conflict work

Observed: `GET /automatic-routing/tasks/unclaimed` returned routing-only-visible rows for (1) an unready/unenrolled direct fixture and (2) a Request whose Listing canonical locality belongs to another City. Team-only direct task read returns HTTP 403 rather than the contract's concealed 404.

Smallest proposed solution, subject to owner approval because it changes the oversight predicate/authorization behavior beyond the alias correction: add the durable enrollment, current readiness/canonical-City predicate and the known current-authority check to the oversight SQL/service; preserve global diagnostic reach and City-scoped positives. Add positive and negative HTTP/PostgreSQL tests for each omitted predicate.

### P1 — routing-only manual exception route is denied

Observed: a valid, active `OPS_ROUTING@CITY` actor receives 403 from `/operations/intake-routing/visit-requests/{id}/route` for a ready, durably enrolled Request. The source reaches the legacy `requireInitialAdminForReconciliation`, which accepts only global Staff Admin or City Team Admin.

Smallest proposed solution, subject to owner approval because it changes authorization composition: in the existing initial-routing path, authorize either the existing admin authority or current `OPS_ROUTING@CITY` over the ready, canonical, enrolled source City; keep global Admin, canonical evidence, versions, active scope, locks, fresh-authority checks and audit unchanged. Add positive routing-only and wrong-City/wrong-locality/revoked/expired controls.

### P1 — manual-first worker observes a stale exception

Observed: when manual routing commits while a worker has discovered PENDING work and is waiting for the task lock, the worker raises `ObjectOptimisticLockingFailureException` at `findLockedById` rather than re-reading terminal state and returning cleanly.

Smallest proposed solution, subject to owner approval because it changes routing concurrency behavior: after the locked reload/transaction boundary, detect the already-terminal/manual state and return the contractual no-op; do not catch arbitrary exceptions or reopen terminal work. Add manual-first and worker-first two-connection controls with exact outcomes.

## F. Cleanup, workspace and handoff

- Original source checkpoint retained outside the repository with a verified extraction path.
- Corrected source manifest retained at the checkpoint.
- Disposable database, migrator/runtime roles and owned backend listener removed.
- Git branch/HEAD unchanged; index empty; no migration edits; no secrets or provider writes.
- Backoffice remains owner-approved and pending implementation. Routing-shared files remain: oversight SQL, routing acceptance test/contract, `db-guard.sh`, `runtime.sh`, `runtime.mjs`, `lifecycle.mjs`, and capability/grant seams. Do not edit concurrently in this dirty worktree. Start Backoffice only after the confirmed routing corrections are separately approved and a fresh source checkpoint is established.

## G. Tracker and deadlines

Both canonical workbooks were read-only. No workbook was modified. Proposed append-only successor records:

- Cross-Role Change Log after the existing routing rows: F1/F2 corrected; C01/C04/automatic C06/both grant orders evidenced; C02 and manual C06 corrections required; C03 manual-first stale failure; C05 restart and delayed account-switch browser evidence remain open.
- Visit Session Change Log and Delivery Gates: preserve Backoffice T1–T7 approved/pending, global no-flicker rule and T5 legal/retention boundary; routing remains not independently accepted or release-ready.

Web V1 31 October remains HIGH RISK / NO GO. Native Tenant/GE 15 November remains AT RISK. Independent Sol verification, physical browser/device/WCAG, staging/recovery and owner release GO remain open.

## H. Final verdict

**FAIL — CONFIRMED CORRECTION REQUIRED**

Developer verification is not independent acceptance. Routing activation, Backoffice implementation, tracker edits, Git publication and deployment were not performed.
