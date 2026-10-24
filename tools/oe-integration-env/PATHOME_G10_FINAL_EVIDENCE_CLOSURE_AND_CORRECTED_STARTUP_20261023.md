# PATHOME — G10 FINAL EVIDENCE CLOSURE AND CORRECTED STARTUP

**Date:** 23 October 2026, IST

**Status:** Five evidence conditions closed, focused regressions green, corrected backend live, owner-review application left running for manual inspection.

**Independent acceptance:** The prior GPT-6.1 Sol High `PASS WITH CONDITIONS` is not upgraded by this report. A fresh independent reacceptance remains a separate gate.

## 1. Repository State

- Repository: `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`
- Branch: `feature/phase2a-oe-ui-integration`
- HEAD: `a943775e07a153087d0b38c315db20e18b30aec5`
- Remote branch SHA: `a943775e07a153087d0b38c315db20e18b30aec5`
- Git index: empty
- Worktree: inherited dirty G10 work remained uncommitted; current total is 147 status entries
- No stage, commit, push, merge, PR, rebase, reset, stash, clean, branch switch or deployment occurred

The production implementation was not changed in this pass. Changes are limited to focused test
coverage, controlled-browser evidence, the repository verification probe and this report/log.

## 2. Verdict Separation

| Gate | Status |
|---|---|
| AGENT-TESTED | PASS for the bounded evidence and regression scope |
| GENUINE POSTGRESQL-VERIFIED | PASS for all four backend conditions and listed regressions |
| CORRECTED LIVE APPLICATION VERIFIED | PASS by process, listener, datasource, classpath and class-loading evidence |
| INDEPENDENTLY ACCEPTED WITH CONDITIONS | NOT UPDATED — last independent verdict remains binding |
| OWNER VISUALLY ACCEPTED | NOT CLAIMED |
| RELEASE-READY | NOT CLAIMED |

## 3. Test Target and Attestation

- Database: `pathome_oe_test_g10_20261022`
- Host: `127.0.0.1:5432`
- Application user: `pathome`
- Run ID: `17cdb6d3-ef16-4b67-9f53-73725c859a41`
- Positive DISPOSABLE sentinel: exactly 1
- Flyway: 53 successful migrations, V53 highest, 0 failed
- Hibernate DDL: `none`
- Schema fingerprint: `7630800708c8048093d292835ea55ee9269aab220f42edf4b63f89eec4fa7c15`
- V51 checksum: `1632712085`; V52: `-2000833596`; V53: `850334017`

Every write-bearing batch was preceded by a fresh positive attestation. It verified the exact
disposable database identity, sentinel, run ID, loopback endpoint and source migrations.

`pathome_db` and historical `pathome_oe_test_integration` were not accessed. No migration, migration
checksum, sentinel, run identity or protected database was changed.

## 4. Safety Incident and Containment

The first local harness invocation forwarded
`SPRING_DATASOURCE_HIKARI_JDBC_URL` to the JVM. That Hikari-specific property overrode the
schema-qualified dynamic test URL, so the first unsuccessful fixture attempts wrote a bounded
synthetic test graph into the disposable database's retained `public` schema.

This was detected before startup and stopped immediately. The exact synthetic graph was:

- 10 synthetic users with `p4-crm-share-*@example.test`
- 10 synthetic Cities with `crm-share-*`
- 10 Teams, 8 localities, 8 synthetic listings and 8 synthetic Leads
- related grants, profiles, shares, events, activities, audit rows and receipts

The graph was removed in one transaction after exact-scope preconditions and while temporarily
disabling only the two history-protection triggers required to remove the newly created synthetic
rows. Both triggers were re-enabled in the same transaction. A later failure would have rolled back
the entire cleanup and trigger changes.

Post-cleanup proof:

- Synthetic users: 0
- Synthetic Cities: 0
- Retained review Leads: 8
- Retained published synthetic property: 1
- Disabled history triggers: 0
- Temporary test schemas: 0

The durable test class now has a `@BeforeEach` guard requiring `current_schema()` to equal the
run-owned disposable schema. Subsequent test runs explicitly unset the Hikari override and remained
inside their random test schemas.

## 5. Five Evidence Conditions

### A. Expiry After Definitive Admission

Test: `LeadHttpPostgresTest#admittedHandoffRemainsCommittedWhenItsGrantExpiresBeforeTransactionCommit`

Sequence and actual result:

1. A synthetic OE received a sole qualifying 10-second Team grant.
2. The real authenticated `HANDOFF_ATTEMPTED` request completed locks, readiness checks and
   definitive admission.
3. A test-only PostgreSQL trigger held the real transaction before the event row was inserted.
4. Database time was observed before expiry while the admitted transaction waited.
5. The test waited for database time to cross `expires_at`, then released the transaction.
6. The event was inserted after expiry and the transaction committed.

Result: PASS. HTTP 200; one event; one share activity; witness insert timestamp after expiry;
zero audit delta; zero command-receipt delta. No test hook exists in production.

### B. Revocation After Admission

Test: `LeadHttpPostgresTest#revocationAfterDefinitiveAdmissionWaitsThenFreshAuthorityIsDenied`

Sequence and actual result:

1. The final handoff reached definitive admission and was held before event persistence.
2. A competing real grant revocation started and waited on the retained grant guard.
3. Revocation could not commit before the admitted event transaction.
4. Releasing the witness allowed the admitted event to commit, then revocation completed.
5. A new event with the revoked authority was denied.

Result: PASS. One admitted event and activity; revocation waited; fresh event returned 403/404;
no deadlock, duplicate event or partial audit state.

### C. Exact Database-Time Equality Boundary

Test: `LeadHttpPostgresTest#databaseTimeEqualityBoundaryUsesStrictLessThanAndCurrentRepositoryPredicate`

One PostgreSQL-derived instant was bound identically as both sides of the deterministic equality
assertion. The real effective-grant repository was separately executed and its captured SQL was
checked for:

`g.expires_at IS NULL OR clock.db_now < g.expires_at`

Result: PASS.

- Before expiry: qualifies
- Exactly equal: does not qualify
- After expiry: does not qualify
- Non-strict `<=`: equality incorrectly qualifies, as required by the discriminator
- A second independently effective grant preserves authorization at the first grant's equality
- The real repository returns current authority before expiry and no grant after expiry

### D. Genuine Timeout, Rollback and Safe Retry

Test: `LeadHttpPostgresTest#shareAdmissionLockTimeoutRollsBackAndExactRetryCommitsOnce`

A test-only `BEFORE INSERT` trigger set `lock_timeout = 250ms` for the authenticated event
transaction and contended with a separately held test fixture row lock.

Result: PASS.

- PostgreSQL SQLSTATE: `55P03`
- HTTP response: 500, sanitized with no SQLSTATE or table disclosure
- Event, activity, audit, receipt and witness deltas after timeout: 0
- Lock released and retry with the exact same event ID: HTTP 200
- Final outcome: one event and one activity, no duplicate
- Connection pool remained usable

This is the existing application behavior. No new share retry/error classification or business
contract was introduced.

### E. Lost WhatsApp Handoff Acknowledgement

Controlled production-bundle Chromium was used with an actual persisted synthetic event and a
deliberately lost `HANDOFF_ATTEMPTED` acknowledgement.

Result: PASS.

- Event POST count: 1
- Persisted history event count: 1
- WhatsApp navigation count: 0
- Automatic clipboard writes: 0
- Reserved blank tab: closed
- UI notice: persisted history confirmed the earlier handoff; no second tab was opened

Normal acknowledgement still records once and opens one noopener tab. Unchanged event-ID replay and
changed-payload conflict behavior remain covered by the focused backend tests.

## 6. Negative Control and Production Hygiene

The isolated non-strict `<=` control failed exactly at the discriminator:

`At equality the strict production predicate must classify the grant as expired ==> expected: false but was: true`

The control exited nonzero as required. The normal strict test was then restored and passed.

Production source, compiled classes and frontend bundle contain no:

- expiry-boundary negative-control switch
- final-guard test bypass
- synthetic admission witness/gate trigger
- non-strict production grant predicate

## 7. Focused Regression Verification

| Check | Result |
|---|---|
| Four new genuine PostgreSQL witnesses | 4/4 PASS, 0 failures/errors/skipped |
| Existing focused HTTP/PostgreSQL share regression batch | 10/10 PASS |
| `LeadShareServiceTest` | 15/15 PASS |
| `OperationalSecurityGuardsTest` | 1/1 PASS |
| Controlled production-bundle Chromium suite | 50/50 PASS, 0 browser errors |
| Frontend production build | PASS; existing >500 kB bundle warning |
| Backend package build | PASS |
| `git diff --check` and empty cached diff | PASS |

The focused HTTP regression includes consent status changes, destination changes, reassignment,
closed lifecycle, shortlist version changes, listing unpublication/public-fact changes, sole and
multiple grants, revocation ordering, profile/Team deactivation, exact replay/conflict, history
read-only behavior and activity rollback. Both guarded event types remain covered.

Controlled browser coverage includes withdrawal before activation, dispute, destination revision,
popup denial, network failure, HTTP failure classes, double activation, keyboard activation,
cancellation, unmount, account A→B→A, history recovery and the valid unchanged handoff. Viewports
checked were 320, 375, 390, 430, 768, 1024 and 1440 px with no unintended horizontal overflow.

## 8. Corrected Backend Replacement

### Old Process

- Maven parent PID: 70203
- Java listener PID: 70405
- Listener: `127.0.0.1:8080`
- Started: 22 October 2026, 20:23:56 IST
- Classpath: current repository `backend/target/classes`
- Ownership, listener, signing identity and run binding: verified

The process predated the corrected `LeadShareService` source and compiled-class timestamps. It was
stopped only through the repository-owned lifecycle after ownership and target verification.

### New Process

- Maven parent PID: 1857
- Java listener PID: 2029
- Listener: `127.0.0.1:8080`
- Started: 23 October 2026, 00:44:40 IST
- Parent/listener relationship: verified
- Datasource: `jdbc:postgresql://127.0.0.1:5432/pathome_oe_test_g10_20261022`
- Run ID: `17cdb6d3-ef16-4b67-9f53-73725c859a41`
- Hibernate DDL: `none`
- Flyway: 53 successful migrations, no new migration
- Stale old listener: absent

Live JVM evidence:

- `java.class.path` begins with the current worktree's `backend/target/classes`
- `jcmd VM.class_hierarchy` confirms `LeadShareService` is loaded
- `jcmd GC.class_histogram` confirms both `LeadShareService` and `LeadAccessService` instances
- Compiled bytecode order is `requireFinalActionReadiness` → `authorizeCurrentRelationship` →
  event `saveAndFlush`
- The effective-grant query retains `clock.db_now < g.expires_at`

Artifact provenance:

- Jar SHA-256: `8e9557909c0181565d7970f988dcde94c1475a6ae36073b006bf80e3ac126d98`
- `LeadShareService.class` SHA-256:
  `06eda227c930504606928eab70d62b25903c60771dd9926c9deeee3700b72490`
- `LeadShareService.java` SHA-256:
  `c5dbec14a0a3e44a83501bcbe28dd8079f0761882b0b76ca5cdbbebc03584890`

The backend package is a thin jar; `target/classes` is the actual live classpath, and the class hash
matches the corrected package entry.

## 9. Frontend and Owner-Review Availability

- Frontend process: PID 73523
- Listener: `127.0.0.1:5173`
- Existing Vite process and current source identity were retained; no duplicate frontend was started
- Vite proxy targets `http://localhost:8080`
- `http://localhost:5173/`: HTTP 200
- `http://localhost:5173/operations/leads`: HTTP 200
- Anonymous backend capability request: expected HTTP 401

Authenticated review smoke at 1440×900 completed 11/11 checks:

1. OE login and active capability scope
2. Eight retained Leads visible
3. Operations workspace and sidebar
4. Name search
5. Lead reference, exact last-four and follow-up filters
6. Lead detail backed by persisted data
7. Next action
8. Published synthetic property in real Smart Search
9. Authenticated consent read
10. Authenticated share-history read
11. No API 5xx, page exception or console error

No handoff mutation was performed against the retained dataset.

## 10. PostgreSQL Correction Verification

The supported `run.sh verify` verification initially reported the immutable-grant probe as
allowed because its target row was already `STAFF_ADMIN`; the update was therefore a no-op. The
verifier now selects a guaranteed capability change. The final supported run passed every check:

- guard acceptance/refusal matrix
- exact live disposable identity and marker
- all 53 repository migrations
- public base/view schema parity
- authenticated staff provisioning
- immutable grant/audit delete and update rejection
- unchanged grant/audit counts after rejected probes
- final target attestation

Result: `VERIFICATION SUMMARY: failures=0`.

## 11. Evidence Locations and Hashes

Primary evidence:

`/private/tmp/pathome-oe-g10-20261022/evidence/g10-final-evidence-20261023/`

Key files:

- `g10-five-evidence-combined.log`
- `g10-five-evidence-combined.xml`
- `g10-equality-negative-control.log`
- `g10-focused-regression.log`
- `controlled-results.json`
- `owner-review-smoke.json`
- `owner-review-1440.png`
- `backend-replacement/old-process-identity.txt`
- `backend-replacement/old-backend-binding.json`
- `backend-replacement/pre-restart-hashes.txt`

The neighboring `evidence/target-attestation.txt` and `evidence/verification.txt` contain the final
target and environment verification records. A SHA-256 manifest is generated beside this evidence.

Source/test hashes:

- `LeadShareService.java`: `c5dbec14a0a3e44a83501bcbe28dd8079f0761882b0b76ca5cdbbebc03584890`
- `LeadAccessService.java`: `901b65fd4387e2a43c8cedcfd76e04c6ca97332eeb9653b6d214f39a08063025`
- `StaffAccessGrantRepository.java`: `ebef512fdb6ffca61b5e823bd9205b9fa916e971ef40f91dca1456e61facf865`
- `LeadHttpPostgresTest.java`: `e71bc469cbcc594c1d5766179ad15502ae4b97bd25b4583e29b1b1379c9d96f9`
- `controlled-corrections.mjs`: `e39408622f45fa713faeddf65293f2223053f0de4a258d98473a2cf5445d35ba`

## 12. Files Changed in This Pass

- `backend/src/test/java/com/indore/pathome/spaces/service/LeadHttpPostgresTest.java`
- `frontend-web/browser-verification/operations-leads/controlled-corrections.mjs`
- `tools/oe-integration-env/lib/verify.sh`
- `tools/oe-integration-env/g10-review-smoke.mjs`
- `tools/oe-integration-env/PATHOME_G10_FINAL_EVIDENCE_CLOSURE_AND_CORRECTED_STARTUP_20261023.md`
- `chat_gpt_changes`

The first four files are test/evidence tooling. No production Java, React, CSS, API, DTO, entity,
permission or migration file was modified by this pass.

## 13. Security, Performance and Data Preservation

- Server-side owner/Team/City/grant authorization remains authoritative.
- Admission and persistence remain in one transaction.
- Revocation serializes through the established grant guard; lock order was not changed.
- The database-clock predicate remains strict `<` and uses `clock_timestamp()`.
- Event replay remains read-only.
- Sensitive queue/history responses observed in smoke retained `Cache-Control: no-store`.
- No new cache, N+1 query, index, lock, retry loop or unbounded read was added.
- Retained review Leads: 8; retained published property: 1.
- Synthetic public fixture rows after cleanup: 0.
- Temporary test schemas after cleanup: 0.
- Orphan advisory locks after final tests: 0.
- History-protection triggers disabled after cleanup: 0.

## 14. Trackers and Deadlines

The exact canonical tracker editions were not present in the local repository:

- `Pathome_Cross_Role_Development_Tracker_Canonical_2026-10-22_Smart_Intake_Approval.xlsx`
- `Pathome_Visit_Session_Implementation_Packages_Canonical_2026-10-22_Smart_Intake_Approval.xlsx`

No substitute workbook was opened or edited. Tracker synchronization remains pending after future
owner/independent approval.

- Full Web V1 by 31 October 2026: **HIGH RISK**
- Native Tenant and GE apps by 15 November 2026: **AT RISK**

This G10 package is one release dependency and does not reduce the approved Web V1 scope.

## 15. Checks Not Run

- Fresh independent GPT-6.1 Sol High reacceptance: `NOT TESTED`
- Physical iPhone/Safari popup and touch behavior: `NOT TESTED`
- Complete VoiceOver/WCAG 2.2 AA audit: `NOT TESTED`
- Real WhatsApp/Meta request, delivery or read receipt: `NOT TESTED`
- Staging/production deployment: `NOT TESTED`
- Owner visual acceptance: `NOT TESTED`
- Exact canonical tracker review: `NOT TESTED` because the workbooks are absent locally

## 16. Scope and Recommendation

No unexpected API/business contract, migration, permission, provider, tracker, commit, push or
deployment action occurred. The five requested evidence conditions are closed by current executions,
the corrected backend is live against the attested disposable database, and the frontend is available
for owner inspection.

Recommended next gate: owner UI/UX inspection followed by one focused independent reacceptance of the
five evidence witnesses, corrected-backend identity and preserved P1 consent/authorization behavior.

