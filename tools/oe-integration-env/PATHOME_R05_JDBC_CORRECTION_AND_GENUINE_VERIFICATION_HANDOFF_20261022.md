# PATHOME — R05 JDBC CORRECTION & GENUINE POSTGRESQL VERIFICATION HANDOFF

Date: 22 October 2026, IST

Branch: `feature/phase2a-oe-ui-integration`

HEAD: `a943775e07a153087d0b38c315db20e18b30aec5`

Execution role: DeepSeek Flash High requested by the owner; runtime-model identity is not independently attested.

**Decision summary**

- **A. Source implementation: IMPLEMENTED.** Only the approved `Timestamp` import and two JDBC argument conversions were added to `LeadService.duplicateCandidates`.
- **B. R05 JDBC binding correction: PASS after genuine verification.** Real HTTP -> Spring -> PostgreSQL reached COUNT, INSERT, candidate SELECT, account-indicator read and the response.
- **C. Full R05 business behavior: FAIL.** The separate unsynchronized rate-limit admission defect was reproduced: two same-actor requests that each observed 59 both succeeded and left 61 events.
- **D. Lead queue S08 condition: CLOSED by genuine evidence.** Expired-only, inactive Team, inactive City, mixed valid/expired grants, revocation between requests and independent valid actors were exercised through HTTP/PostgreSQL.
- **E. D-B overall: NOT INDEPENDENTLY ACCEPTED.** G01–G10 and the remaining release/UI/device gates remain open.
- **F. Git, trackers, staging, UI and providers: UNCHANGED.** No stage, commit, push, merge, tracker edit, deployment or provider activation occurred.

## 1. Repository baseline and preservation

- Repository root: `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`.
- Branch and local/origin HEAD all resolve to `a943775e07a153087d0b38c315db20e18b30aec5`.
- The index was empty before and after execution. No branch switch, reset, stash, rebase, history rewrite or Git publication occurred.
- The starting full manifest was the owner-authorized 25 tracked modifications plus the existing untracked D-B/V53/test/evidence work. The historical Sol baseline contained 108 dirty/untracked entries; directory-collapsed counts were not used as file counts.
- All six preflight production/evidence fingerprints matched:
  - `LeadService.java`: `9e868d8cd666a2ae598f9c2d5a3237e11b011e996da7ae5caf766a0fc41174ac`
  - `LeadRoutingQueueRepository.java`: `eb0ac7d7852c779e795cdc56275e49b24170affdef6f512842b817029d8df2a7`
  - `LeadHttpPostgresTest.java`: `aca70daa13d985d8c03e1359e8f00232dcc5fe3d8e503abbcbbc5a3027236816`
  - `LeadContract.java`: `e4a65e6815e31f114961b60b0abfc259c1f82e5f70f64fc7351b49ce448d3aa6`
  - `LeadShareService.java`: `6b09ee22a8a45d0f6b54120a65a74ff3ea9b7017e6f019a31fcd6adaef43d7c1`
  - V53 migration: `15b856ad6fe8d5f2c999617f255b546b30a457d7ac2a9270bcca7db739d640d3`
- The full post-execution Git manifest is preserved in
  [git-manifest-final.txt](evidence/r05-jdbc-correction-20261022/git-manifest-final.txt).
- Existing `.xlsx`, historical evidence, D-B source and the accepted queue repository were not rewritten. The queue repository hash remains exactly unchanged.

## 2. Approved source delta

The production delta is exactly:

```java
import java.sql.Timestamp;

Integer.class, actorId, Timestamp.from(now.minusSeconds(3600)));

actorId, Timestamp.from(now));
```

The existing SQL, actor argument, argument order, fixed lookup purpose, single `Instant now = databaseClock.now()`, strict `occurred_at > cutoff`, 3600-second window, threshold 60, candidate query, masking, limit, transaction and failure propagation are unchanged.

- Before: `9e868d8cd666a2ae598f9c2d5a3237e11b011e996da7ae5caf766a0fc41174ac`
- After: `0daf769227a628c6c52e117296bb9a6a7ff7be4b9f0e818911f4cb5d0fa22388`
- Exact patch: [approved-source-delta.patch](evidence/r05-jdbc-correction-20261022/approved-source-delta.patch)
- `LeadServiceTest.java`: `6800e05726a9f3f92d5e3d76b058cdf3b989704898a3db58c64a4ed7899014b8`
- `LeadHttpPostgresTest.java`: `85f932b9bd63ffa59e0a86080e0f5bd0b83097b7e26665d8a51a32e092ce896f`

`LeadServiceTest` initially failed against the raw-Instant baseline at the new timestamp assertion, then passed after the two approved substitutions. Test-only clock, JDBC barrier/failure, repository-failure and database isolation instrumentation remained inside the test class and did not modify production behavior.

## 3. Files changed within the authorized boundary

Production:

- `backend/src/main/java/com/indore/pathome/spaces/service/LeadService.java`

Tests:

- `backend/src/test/java/com/indore/pathome/spaces/service/LeadServiceTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadHttpPostgresTest.java`

Evidence:

- `tools/oe-integration-env/evidence/r05-jdbc-correction-20261022/LeadService.before.java`
- `tools/oe-integration-env/evidence/r05-jdbc-correction-20261022/approved-source-delta.patch`
- `tools/oe-integration-env/evidence/r05-jdbc-correction-20261022/source-hashes-after.txt`
- `tools/oe-integration-env/evidence/r05-jdbc-correction-20261022/target-and-cleanup.txt`
- `tools/oe-integration-env/evidence/r05-jdbc-correction-20261022/git-manifest-final.txt`
- `tools/oe-integration-env/evidence/r05-jdbc-correction-20261022/tracker-readonly-extract.json`
- `tools/oe-integration-env/evidence/r05-jdbc-correction-20261022/positive-surefire-summary.json`
- `tools/oe-integration-env/evidence/r05-jdbc-correction-20261022/control-manifest.json`
- Five `TEST-positive-*.xml` Surefire reports.
- Nine `TEST-control-*.xml` Surefire reports.
- Nine `control-*.log` raw control logs.

Reporting/logging:

- This handoff.
- Append-only `chat_gpt_changes` entry.

No controller, DTO, entity, migration, configuration, dependency, queue repository or provider source was changed by this package.

## 4. Verified disposable target and cleanup

- Host/port: loopback `127.0.0.1:5432`.
- Database: `pathome_oe_test_integration`.
- Authenticated role: `pathome`.
- PostgreSQL: `14.14 (Homebrew)`.
- Sentinel: exactly one `DISPOSABLE` row with run identity `20261013T124820-98553`.
- Supported legacy baseline -> V52 -> V53 validated; V53 applied; Spring/Hibernate started against the migrated schema.
- Final successful owned schema: `p4_crm_http_test_8fa9fdb92a924d0695c5ffba665a9eef`.
- Final owned-schema count after cleanup: `0`.
- Current sentinel remained exactly one row and retained its run identity.
- The positive and every mutating control repeated the target/identity/sentinel/zero-schema gate immediately before execution.
- `pathome_db`, reset scripts, protected/staging/production data, provider credentials and external messaging were not accessed.

Exact receipt: [target-and-cleanup.txt](evidence/r05-jdbc-correction-20261022/target-and-cleanup.txt).

## 5. Genuine R05 execution

The successful journey executed:

1. Authenticated staff resolution and fresh persisted authority read.
2. One `databaseClock.now()` capture.
3. JDBC COUNT with `Timestamp.from(cutoff)`.
4. Threshold decision.
5. JDBC insertion with `Timestamp.from(now)`.
6. Scoped candidate repository SELECT.
7. Existing-account indicator read.
8. HTTP 200 with the approved masked candidate fields and `Cache-Control: no-store`.

The stored fixed-clock event read back as exactly `2026-10-22T05:00:00.123456Z`. The raw PostgreSQL session accepted the value under UTC and Asia/Kolkata settings; changing the JVM default timezone did not change the absolute instant. An unmocked clock run showed the inserted event between genuine `clock_timestamp()` brackets.

The final positive cohort contained **45 tests, 0 failures, 0 errors, 0 skipped**:

| Class | Tests | Result |
|---|---:|---|
| `LeadHttpPostgresTest` | 25 | 25 PASS |
| `LeadServiceTest` | 2 | 2 PASS |
| `LeadAccessServiceTest` | 6 | 6 PASS |
| `LeadControllerTest` | 5 | 5 PASS |
| `LeadContractTest` | 7 | 7 PASS |

Raw summaries: [positive-surefire-summary.json](evidence/r05-jdbc-correction-20261022/positive-surefire-summary.json).

## 6. Complete 54-case R05 matrix

`HPG` means genuine HTTP/Spring/PostgreSQL in `LeadHttpPostgresTest`; `PGT` means the transactional service path using the same real database; `U` means JUnit 5/mock boundary; `NC` is an isolated external control.

| ID | Actual boundary and evidence | Result |
|---|---|---|
| A01 | Raw two-Instant baseline; NC `raw`; COUNT parameter binding failed; positive source returned 200 | PASS |
| A02 | Cutoff-only NC reached INSERT and failed there; zero committed lookup | PASS |
| A03 | INSERT-only NC remained red at COUNT | PASS |
| A04 | Both converted bindings; HPG completed COUNT -> INSERT -> candidate SELECT -> indicator -> HTTP 200 | PASS |
| A05 | HPG zero-event, one-event, 59-event, 60-event and 61-event actors returned the actual per-actor counts | PASS |
| A06 | HPG event exactly at `now - 3600s` was excluded by strict `>`; request became event 60 | PASS |
| A07 | HPG cutoff-minus-1µs was recent and admitted; cutoff-plus-1µs made count 60 and returned 429 | PASS |
| A08 | HPG persisted `.123456Z`; NC millisecond truncation failed with `.123Z` | PASS |
| A09 | PGT same fixed instant under UTC and Asia/Kolkata session timezones | PASS |
| A10 | PGT/JVM default UTC/Asia-Kolkata cross-check preserved the same absolute event | PASS |
| A11 | Genuine unmocked event bracketed by PostgreSQL clock reads; U proved one clock call | PASS |
| A12 | U captured actor Long then Timestamp at COUNT and INSERT with exact values/order | PASS |
| A13 | HPG COUNT and INSERT fault injections returned generic 500/no-store with zero evidence | PASS |
| A14 | No local-time/DST input or calendar arithmetic was added | NOT APPLICABLE |
| B01 | HPG coordinator saw exact-owned candidate only and `currentlyOwnedByActor=true` | PASS |
| B02 | HPG Team-A supervisor saw Team-A rows, no Team-B row and no contact-reveal capability | PASS |
| B03 | HPG intake and City-admin saw unassigned City-A rows only | PASS |
| B04 | HPG global Staff Admin retained City/phone/limit-bounded diagnostic visibility | PASS |
| B05 | HPG coordinator requesting foreign City received 403/no-store/no event | PASS |
| B06 | HPG actor-owned Lead in a foreign Team remained absent | PASS |
| B07 | HPG same-Team peer-owned Lead remained absent; owner Lead remained visible | PASS |
| B08 | HPG absent/revoked/expired authority denied with no event; valid control remained authorized | PASS |
| B09 | HPG inactive profile, inactive Team and inactive City denied fresh requests; reactivation restored current access | PASS |
| B10 | HPG actor with one expired and one valid grant saw only the valid Team’s record | PASS |
| B11 | HPG unauthenticated 401; role-label-only actor 403; no event or sensitive record | PASS |
| B12 | HPG null/zero/negative/malformed/overflow City rejected; unknown permitted-shape City denied. Fractional JSON `1.5` reached admission and returned 403 rather than 400; see the separate input-policy observation below | PASS for authority; pre-existing input-policy finding |
| B13 | HPG missing/null/blank/local-only/extension/malformed phone returned 400 with no event | PASS |
| B14 | HPG `0091 98222 22222` normalized to the same E.164 candidate; each request was audited | PASS |
| B15 | HPG valid empty search returned 200 empty candidates and exactly one event | PASS |
| B16 | HPG one/many candidates preserved repository order and masks | PASS |
| B17 | HPG 21 visible same-phone Leads returned the newest 20; earlier row was hidden | PASS |
| B18 | HPG candidate objects had seven approved fields, masked contact, account boolean and no full phone/email; no-store present | PASS |
| B19 | HPG A -> B -> A requests returned A-B-A candidates and attributed events to the actual actor | PASS |
| C01 | HPG 59 recent events -> 200 -> durable event 60 | PASS |
| C02 | HPG 60 and 61 recent events -> 429/no-store/no candidate query/no insertion; unrelated actor unaffected | PASS |
| C03 | HPG repeated same body produced separate lookup evidence | PASS |
| C04 | HPG 60-event actor remained limited after changing phone and permitted City | PASS |
| C05 | HPG two real PostgreSQL connections, two-request barrier after COUNT: both saw 59, both returned 200, final count 61 | **FAIL — separate concurrency defect** |
| C06 | HPG two actors independently at 59 both succeeded to 60; quotas were not mixed | PASS |
| C07 | HPG COUNT failure -> 500/no-store/no insert/candidate/indicator; retry succeeded | PASS |
| C08 | HPG real INSERT failure -> 500/no committed event; retry succeeded | PASS |
| C09 | HPG candidate table failure after COUNT+INSERT -> transaction rollback left zero events; retry succeeded | PASS |
| C10 | HPG account-indicator failure after candidate read -> rollback left zero events; retry succeeded | PASS |
| C11 | HPG safe retry after each injected failure left exactly one event | PASS |
| C12 | HPG committed success followed by acknowledgement loss and repeat created a second event under current non-idempotent semantics | PASS |
| C13 | HPG successful lookup -> persisted grant revoked -> fresh request returned 403 with unchanged event count | PASS |
| C14 | Midflight authority writer race remains G01; no locking behavior was added or inferred | DEFERRED |
| C15 | Real PostgreSQL `count(*)` on zero rows returns numeric 0; no fabricated null workaround exists | NOT APPLICABLE |
| D01 | HPG original red 500 becomes genuine 200 with exact masked candidate and one durable event | PASS |
| D02 | HPG existing mask/201-row queue SELECT+COUNT cases remained green; queue repository hash unchanged; S08 added genuine expiry/inactive proof | PASS |
| D03 | Existing genuine capture/replay, encrypted consent, ordered shortlist and manual-share cases remained green in the same HPG class | PASS |
| D04 | HPG compared Request, Session, entitlement-account and notification-outbox counts before/after the R05 journey; no unrelated writes; source diff is one service method | PASS |
| D05 | Connected browser, visual, keyboard, screen-reader and physical-device validation was not part of this backend correction | DEFERRED |
| D06 | No external WhatsApp/Meta/provider code, credentials, calls or delivery claim were introduced | NOT APPLICABLE |

## 7. S08 closure

The previously missing evidence condition is genuinely closed:

1. Expired-only coordinator authority returned 403 for both queue list/count and duplicate lookup; no lookup event was inserted.
2. Inactive Team removed the affected coordinator’s visibility and lookup admission; reactivation restored current access.
3. Inactive City removed intake visibility and lookup admission; reactivation restored current access.
4. Mixed expired/valid grants exposed only the valid Team and hid the expired Team’s known record.
5. A separate valid actor remained authorized throughout.
6. Revoking a grant between a successful lookup and retry made the next request return 403 with no additional event.
7. Denied bodies contained no seeded full phone or candidate name, and no affected row/count leaked.

The effective-grant SQL still uses persisted active City/Team and database time; no authority cache or queue production correction was introduced.

## 8. Negative controls

All controls used external source copies containing the current D-B implementation. Each started Spring/PostgreSQL and failed a named acceptance assertion at the intended stage.

| Control | Variant hash | Named red result |
|---|---|---|
| N-R05-01 raw | `9e868d8c…74ac` | HTTP 500 at COUNT; expected 200 |
| N-R05-02a cutoff-only | `abb72b79…17da` | COUNT passed, INSERT failed; expected 200 |
| N-R05-02b INSERT-only | `5eeb0ae2…2bab` | COUNT remained red; expected 200 |
| N-R05-03 wrong time | `5982f44a…8b75` | persisted `.123Z`, expected `.123456Z` |
| N-R05-04 rate bypass | `81a7bd5b…9ab0` | 60-event actor got 200, expected 429 |
| N-R05-05a City bypass | `35ccbbe8…2f6` | foreign-City request got 200, expected 403 |
| N-R05-05b owner bypass | `b3dc5979…92f` | same-Team peer exposed: `[2,1]`, expected `[1]` |
| N-R05-06a audit omitted | `aa8f3928…ee0` | successful lookup left 0 events, expected 1 |
| N-R05-06b INSERT error swallowed | `cc665715…3cf` | response leaked a successful candidate result, expected 500 |

Exact source paths, hashes and failure messages are in
[control-manifest.json](evidence/r05-jdbc-correction-20261022/control-manifest.json).
Raw logs and XML are in the same evidence directory. Shared production source hashes were rechecked after controls and remained the positive values in section 2.

## 9. Concurrency and recovery

### Confirmed concurrency defect

- Severity: **P1**.
- Reproduction: seed 59 recent events for one actor, start two genuine HTTP requests, and hold both after their COUNT using a test-only two-connection barrier.
- Observed: both transactions counted 59, neither blocked, both returned 200, both inserted, and the final actor count was 61.
- Invariant affected: the approved 60-lookups/hour admission ceiling is not serialized.
- Root cause: `duplicateCandidates` runs COUNT and INSERT under READ COMMITTED with no actor-scoped serialization. `@Transactional` does not make the predicate exclusive.
- This is separate from the two binding arguments and does not invalidate the genuine JDBC correction result.

**APPROVAL REQUIRED — rate-limit admission serialization**

- Current Behavior: concurrent same-actor requests at 59 can both succeed and exceed 60.
- Problem: the strict hourly audit/admission ceiling is bypassable by timing; repeated concurrent bursts may exceed it further.
- Recommended Option: inside the same transaction, use the existing `OperationalSecurityGuards.acquire(emptyCities, emptyTeams, [actorId])` before the rate clock/count, then re-read authority/City admission, obtain fresh database time, COUNT, INSERT and candidate/indicator reads. This reuses the existing actor/profile/grant guard rather than adding a throttle table, cache or distributed lock.
- Alternatives: a conditional reservation row/schema change; or accept a documented soft limit. Both have larger contract or policy impact.
- Impact & Scope: add actor-scoped synchronization and contention to this one lookup command; preserve window, threshold, transaction and privacy. Requires genuine lock compatibility, two-connection C05/C06 reruns and G01 coordination. It is not included in this correction.

Recovery results are otherwise truthful: COUNT, INSERT, candidate-query and account-indicator failures rolled back; safe retries created one event; a committed acknowledgement loss produced the current non-idempotent second event.

### Pre-existing fractional-ID input observation

An authenticated coordinator request body containing `cityId:1.5` returned HTTP 403 rather than 400 because the JSON literal was accepted by deserialization and then failed normal City authority. No event or unauthorized candidate was produced. This task explicitly forbids silently changing the shared JSON deserialization policy. If malformed fractional IDs must be rejected before service admission, that is a separate bounded input-policy correction requiring owner approval; it is not used to explain or excuse the rate-limit failure.

## 10. Test evidence and commands

Positive target cohort:

```bash
PATHOME_VISIT_OPERATIONS_POSTGRES_TEST=true \
PATHOME_OE_TEST_ALLOW_DISPOSABLE=true \
PATHOME_OE_TEST_DB=pathome_oe_test_integration \
PATHOME_OE_TEST_DB_HOST=127.0.0.1 \
PATHOME_OE_TEST_DB_PORT=5432 \
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/pathome_oe_test_integration \
SPRING_DATASOURCE_USERNAME=pathome \
SPRING_DATASOURCE_PASSWORD='' \
mvn -q -f backend/pom.xml \
  -Dtest=LeadHttpPostgresTest,LeadServiceTest,LeadAccessServiceTest,LeadControllerTest,LeadContractTest \
  test
```

Result: **45 tests, 0 failures, 0 errors, 0 skipped**.

Build and hygiene:

```bash
mvn -q -f backend/pom.xml -DskipTests test-compile
git diff --no-ext-diff --check -- . ':(exclude)chat_gpt_changes'
git diff --no-ext-diff --check -- chat_gpt_changes
git diff --cached --no-ext-diff --check
```

All exited 0. Every isolated control command exited nonzero because its one intended assertion failed. Test execution, environment attestation, raw XML and source hashes were preserved rather than replaced with summaries.

## 11. Canonical trackers and deadlines

Both exact local canonical workbooks were read through a read-only workbook loader and were not saved/exported/recalculated:

- Cross-Role `Pathome_Cross_Role_Development_Tracker_Canonical_2026-10-22_WhatsApp_Approval.xlsx`: SHA-256 `0b9ed98530ad65fb6ce3ffd36e53d89db7335678f3c1475777ce39c94c4c76da`.
- Visit Session `Pathome_Visit_Session_Implementation_Packages_Canonical_2026-10-22_WhatsApp_Approval.xlsx`: SHA-256 `384d41c33de864b894b950c4f89a0e6617c072677becce717c166a472bd5a114`.

The extraction contains all populated rows from both Change Logs plus the Role Dependency Register, OE 20Q Closure, Approval Gates and Web V1 Delivery Gates:
[tracker-readonly-extract.json](evidence/r05-jdbc-correction-20261022/tracker-readonly-extract.json).

Preserved milestone facts:

- D1–D6 remain approved; D-P remains separately gated.
- Lead queue remains independent PASS WITH CONDITIONS, now with S08 genuine evidence closed.
- D-B backend is implemented in source but remains **NOT INDEPENDENTLY ACCEPTED**.
- D-A connected City/Admin routing UI remains open.
- D-C authenticated customer pairing/Lead-to-Request conversion remains not built.
- OE #11/#12 remain open outcomes, original OE #14 date filtering remains open, BC05 bounded closure and C26 remain preserved.
- The manual one-click prefilled WhatsApp/Copy Link fallback UX remains approved but not connected/accepted.
- Full Web V1 on 31 October 2026 remains **HIGH RISK**; native Tenant/GE on 15 November 2026 remains **AT RISK**.

Future tracker synchronization should append, after independent acceptance: the exact R05 two-binding correction and positive evidence; S08 expiry/inactive proof; the confirmed concurrent admission defect and its separate approval boundary. It must not promote D-B, connected UI, provider delivery or release status. No tracker cell or Change Log was modified.

## 12. Decision and STOP

**A. Source implementation:** IMPLEMENTED exactly as approved.

**B. R05 JDBC binding correction:** PASS after genuine HTTP/PostgreSQL verification.

**C. Full R05 business behavior:** FAIL because concurrent COUNT-to-INSERT admission permits 61 events at the 59-event boundary.

**D. Lead queue S08 condition:** CLOSED by genuine evidence.

**E. D-B overall:** NOT INDEPENDENTLY ACCEPTED; G01–G10 and other applicable gates remain open.

**F. Git/trackers/staging/UI/provider:** unchanged; no publication or deployment.

**Next review:** one focused GPT-6.1 Sol High independent verification of the actual R05 correction, S08 evidence and confirmed concurrency finding. Do not repeat the accepted Lead queue architecture audit or broaden the work into D-B acceptance.

**STOP.** No stage, commit, push, merge, PR, branch switch, reset, stash, rebase, tracker edit, migration change, deployment, provider activation, real customer message or automatic reviewer dispatch occurred.
