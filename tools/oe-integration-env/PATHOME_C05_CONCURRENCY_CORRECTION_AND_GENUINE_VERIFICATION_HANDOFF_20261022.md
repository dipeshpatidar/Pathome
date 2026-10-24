# PATHOME C05 concurrency correction and genuine PostgreSQL verification

Prepared: 2026-10-22 12:40 IST

Execution authorization: owner-approved C05 correction and Q01–Q26 verification

Execution status: implementation, self-review and agent verification complete; independent Sol acceptance still required

## 1. Business outcome

An authorized operator can no longer successfully admit more than 60 qualifying duplicate-candidate lookups in the rolling 3600-second actor-wide window when requests overlap. At 59, the first admitted request commits event 60 and the waiting same-actor request then reads the committed count and returns 429. Requests for different actors continue independently.

The correction applies only C05. It does not close D-B overall or G01–G10.

## 2. Repository and preserved baseline

- Repository: `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`
- Branch: `feature/phase2a-oe-ui-integration`
- HEAD: `a943775e07a153087d0b38c315db20e18b30aec5`
- Index before work: empty
- Worktree before work: 25 tracked modifications and 106 expanded untracked files, exactly 131 entries
- Existing unrelated and uncommitted work was preserved.
- The expected R05, queue and V53 fingerprints matched before correction.
- The locally known remote ref matched HEAD. A live fetch hung and was cancelled; no state changed.

Final package state:

- 26 tracked modified paths and 134 expanded untracked files, including the requested test/evidence/report additions
- Empty index
- Final porcelain-manifest SHA-256: `b2bcbc57b84c6d099a76c1102f31270de22ce4c88aa381a5c787001f3ce64c37`

## 3. Architecture and implementation

`LeadService.duplicateCandidates` now follows this order:

1. Validate input, resolve the stable authenticated User, and derive initial non-locking authority.
2. Apply the existing `TransactionalCommandBounds`.
3. Build a request-specific guard plan from persisted City/Team/grant facts.
4. Acquire sorted shared City → shared Team guards, then exclusive actor EmployeeProfile → grant rows → User guards.
5. Clear the JPA persistence context, rebuild persisted authority, and reject any newly required unheld scope with a truthful whole-request retry conflict.
6. Obtain one fresh `DatabaseClock.now()` instant.
7. COUNT qualifying actor events, reject at 60, INSERT when admitted, SELECT scoped candidates, READ the account indicator, and return the existing masked response in the same transaction.

`OperationalSecurityGuards.acquireDuplicateLookup` is endpoint-specific. It does not alter the existing exclusive method used by unrelated operations.

Shared City/Team `FOR SHARE` conflicts with state updates while allowing independent actors in the same scope to progress. The actor's profile, grants and User row are exclusive and remain held through commit/rollback. No Lead, Request or Session lock, table, cache, distributed mutex or new lock framework was added.

The generic exception boundary now applies the existing SQLSTATE-led transient-command classifier to this exact endpoint. PostgreSQL `55P03` and statement-timeout `57014` remain sanitized no-store 503 responses; deadlock `40P01`, non-timeout cancellation, downstream failure and commit failure retain truthful 500 behavior.

## 4. Source delta

Production files changed:

- `backend/src/main/java/com/indore/pathome/spaces/service/LeadService.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/OperationalSecurityGuards.java`
- `backend/src/main/java/com/indore/pathome/spaces/exception/GlobalExceptionHandler.java` (endpoint-specific reuse of the existing classifier; already dirty for unrelated prior work)

Tests changed or added:

- `backend/src/test/java/com/indore/pathome/spaces/service/LeadHttpPostgresTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadServiceTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/OperationalSecurityGuardsTest.java` (new)

Evidence and logging:

- `tools/oe-integration-env/evidence/c05-concurrency-correction-20261022/`
- This handoff
- `chat_gpt_changes`

No controller, DTO, entity, migration, dependency, frontend, provider or environment configuration was changed. Queue repository and V53 remain byte-identical.

## 5. Verification executed

Environment: Java 17.0.14, Spring Boot 3.3.0, PostgreSQL 14.14, loopback `127.0.0.1:5432/pathome_oe_test_integration`, authenticated user `pathome`, one DISPOSABLE sentinel, run identity `20261013T124820-98553`.

Commands and results:

```bash
mvn -q -f backend/pom.xml -DskipTests test-compile
# exit 0

mvn -q -f backend/pom.xml \
  -Dtest=LeadServiceTest,OperationalSecurityGuardsTest,LeadAccessServiceTest,LeadControllerTest,LeadContractTest test
# exit 0: 23 tests, 0 failures/errors/skips

PATHOME_VISIT_OPERATIONS_POSTGRES_TEST=true \
PATHOME_OE_TEST_ALLOW_DISPOSABLE=true \
PATHOME_OE_TEST_DB=pathome_oe_test_integration \
PATHOME_OE_TEST_DB_HOST=127.0.0.1 \
PATHOME_OE_TEST_DB_PORT=5432 \
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/pathome_oe_test_integration \
SPRING_DATASOURCE_USERNAME=pathome \
SPRING_DATASOURCE_PASSWORD='' \
mvn -q -f backend/pom.xml -Dtest=LeadHttpPostgresTest test
# exit 0: 38 tests, 0 failures/errors/skips, 31.716 seconds in final copied report

mvn -q -f backend/pom.xml \
  -Dtest=LeadContactPreferenceServiceTest,LeadDataProtectionTest,LeadShareServiceTest,LeadShortlistServiceTest,LeadSourceProcessingServiceTest,MetaLeadInboxServiceTest,MetaWebhookSignatureTest,VisitOperationsServiceTest,TenantVisitEntitlementServiceTest,VisitSessionNotificationListenerTest test
# exit 0: 62 tests, 0 failures/errors/skips
```

Green totals: 123 tests, 0 failures, 0 errors, 0 skipped.

Source/test diff checks:

```bash
git diff --check -- backend/src/main/java backend/src/test/java backend/src/main/resources
git diff --cached --check
# both exit 0; index remains empty
```

## 6. Critical red/green proof

Locked implementation:

- First same-actor request is held after acquiring the actor guard.
- PostgreSQL reports a second blocked lookup with a distinct backend while it waits before COUNT.
- First commits event 60.
- Second obtains the guard, observes the committed count, and returns 429.
- Final predicate-matched count is exactly 60.

Unlocked isolated control:

- Two requests both observe 59 and commit.
- Both return 200.
- Final predicate-matched count is 61.
- The strict test assertion fails `expected 60, actual 61`.

Raw evidence:

- `TEST-final-LeadHttpPostgresTest.xml`
- `TEST-negctl-actor-serialization-removed.xml`

## 7. Q01–Q29

Complete row-by-row evidence is in:

`tools/oe-integration-env/evidence/c05-concurrency-correction-20261022/Q01-Q29_LEDGER.md`

Result:

- Q01–Q26: PASS, COMPLETE
- Q27: DEFERRED GATE
- Q28: DEFERRED GATE / NOT TESTED
- Q29: NOT APPLICABLE

## 8. Negative controls

Isolated mutations and actual red assertions:

- Actor serialization removed: final count 61 rather than 60.
- Four concurrent requests without actor serialization: all four returned 200 rather than two.
- Different Cities/phones without actor serialization: both returned 200 at the 59-event boundary.
- Exclusive City/Team scope: independent actor returned 503 rather than progressing.
- COUNT before guards: post-wait request returned 200 rather than 429.
- Transaction bounds omitted: held request returned 200 rather than bounded 503.
- Fresh authority rebuild omitted: revoked actor received private candidate data with 200 rather than 403.

Every mutant was confined under `/private/tmp/pathome-c05-negctl-*`. The shared source was not mutated for the controls.

## 9. Failure, authority and privacy evidence

- Revocation and staff expiry winning the wait produce 403 with no event or private candidate data.
- An admitted lookup blocks revocation and finishes its own statement/transaction order.
- City and Team deactivation writers individually wait for admitted lookups; later lookups deny.
- Unknown/inactive global diagnostic behavior is preserved.
- Ownership transfer committed before candidate SELECT excludes the former owner.
- A downstream account-indicator failure rolls back the inserted admission; the waiting request then gets the released capacity.
- Lock waits time out as sanitized no-store 503 without poisoning the pool.
- Deadlock and non-timeout cancellation are not mislabeled as lock timeout.
- Deferred commit failure leaves no event and permits a later fresh retry.
- No-store, masking, account indicator, 400/401/403/404/429, cross-actor isolation and non-idempotent repeat evidence remain intact.

## 10. Preserved accepted boundaries

- R05 JDBC correction: source fingerprint and elapsed-instant tests preserved.
- Queue source remains byte-identical at `eb0ac7d7852c779e795cdc56275e49b24170affdef6f512842b817029d8df2a7`.
- V53 remains byte-identical at `15b856ad6fe8d5f2c999617f255b546b30a457d7ac2a9270bcca7db739d640d3`.
- Queue/S08, D-B capture/replay/consent/shortlist/manual share, Visit Operations, entitlements and notifications passed the selected regression batch.
- D-B overall remains NOT INDEPENDENTLY ACCEPTED; G01–G10 remain open.

## 11. Tracker assessment

Both canonical workbooks were read with the bundled artifact tool and were not saved:

- Cross-Role SHA-256: `0b9ed98530ad65fb6ce3ffd36e53d89db7335678f3c1475777ce39c94c4c76da`
- Visit Session SHA-256: `384d41c33de864b894b950c4f89a0e6617c072677becce717c166a472bd5a114`

Prospective append-only reconciliation after independent acceptance:

- Cross-Role `OE 20Q Closure` row 12: retain D-B open; add C05 strict concurrent rate admission AGENT-TESTED PASS and D-B independent review pending.
- Cross-Role `22 Oct Milestone` D-B rows: retain F1/F3/F4 and D-C work as open; add C05 correction evidence only.
- Visit `Web V1 Delivery Gates` Batch D: retain D-B WIP/D-C missing; add C05 bounded correction.
- Visit `Package Roadmap` Phase 2A row 14: retain full Web V1 HIGH RISK and D-B/D-C open.
- Visit `Approval Gates` rows 65–68: retain D1–D6 approval, implementation partial, v0/manual handoff pending.
- All other DEP rows, change logs, release and model-routing gates remain open as recorded.

No tracker was modified.

## 12. Performance, security and UX implications

- Two bounded native row-lock queries protect City and Team scope; actor serialization uses existing indexed primary/unique identities.
- No N+1 query, cache or lock table was added.
- Shared scope locks avoid City-wide or Team-wide actor exclusion.
- Database work remains bounded and statement-scoped; pooled session state is not changed.
- Authorization remains stable User → active EmployeeProfile → effective unrevoked grant → active City/Team → record scope.
- No UI or copy changed. Connected UI, responsive, accessibility and physical-device checks are outside this backend correction and remain NOT TESTED.

## 13. Database cleanup and preservation

- Every mutating cohort re-attested the loopback disposable endpoint, authenticated user, single DISPOSABLE sentinel and run identity.
- Each JUnit class created one UUID-named `p4_crm_http_test_*` schema and dropped it after the suite.
- Final direct query found zero matching test schemas and the same single sentinel row.
- Protected databases, staging, production, reset scripts, providers, customer data and customer messages were not accessed.

## 14. Git and provider state

- No stage, commit, push, merge, PR, branch switch, reset, stash, rebase or tracker save was performed.
- Git index remains empty.
- No provider credential or external message was activated or sent.
- The existing 131-entry starting worktree was preserved; this package added its authorized source/test/evidence/log entries.

## 15. Final verdicts

- A. C05 strict concurrent rate admission: **AGENT-TESTED PASS**
- B. Fresh scoped authorization and lock safety: **AGENT-TESTED PASS**
- C. Full Q01–Q26 required evidence: **COMPLETE**
- D. Existing R05 JDBC and accepted Lead Queue/S08: **PRESERVED**
- E. D-B overall: **NOT INDEPENDENTLY ACCEPTED**
- F. Worktree, database, trackers, Git and providers: **PRESERVED**
- G. Next action: one focused GPT-6.1 Sol High independent acceptance review of the C05 delta and Q01–Q26 evidence.

The 31 October complete Web V1 target remains HIGH RISK; the 15 November native Tenant/GE target remains AT RISK. This correction advances the CRM security-critical path but does not redefine Web V1 or close the remaining product/integration/device/release gates.

STOP.

