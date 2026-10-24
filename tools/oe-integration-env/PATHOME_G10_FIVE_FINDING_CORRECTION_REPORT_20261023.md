# PATHOME — G10 FINAL FIVE-FINDING CORRECTION REPORT

**Date:** 23 October 2026, IST  
**Branch:** `feature/phase2a-oe-ui-integration`  
**HEAD:** `a943775e07a153087d0b38c315db20e18b30aec5`  
**Index:** empty  
**Status:** IMPLEMENTED / AGENT-TESTED / GENUINE-INTEGRATION-VERIFIED  
**Not claimed:** INDEPENDENTLY ACCEPTED, OWNER VISUALLY ACCEPTED, RELEASE-READY

The inherited dirty worktree was preserved. No stage, commit, push, merge, PR, reset, stash, clean,
branch switch, migration, API change, tracker edit, deployment or provider action occurred.

## 1. Five-finding ledger

| Finding | Correction | Result |
|---|---|---|
| P1 test provisioning | Generated suite-owned identities are transactionally created; exact database, sentinel, guard version, run ID, role identity and schema ownership are attested before cleanup | IMPLEMENTED / PASS |
| P1 rejected preparation loop | Proven configuration rejection ends the pending operation and exposes a deliberate **Prepare message** path; all other 503, timeout and malformed-2xx outcomes remain uncertain under the original UUID | IMPLEMENTED / PASS |
| P2 older exact share | One bounded exact-UUID history resolver now serves recovery, preflight, readiness and copy verification; those checks make no preparation POST | IMPLEMENTED / PASS |
| P2 metadata JDBC escape | Metadata ResultSets and their Statements/Connections, plus PreparedStatement and CallableStatement result paths, remain guarded and cannot unwrap to raw JDBC | IMPLEMENTED / PASS |
| P2 OE language | Attempts, saved results, opening confirmation and delivery evidence remain distinct; technical scheduling/contact/import wording was replaced | IMPLEMENTED / PASS |

## 2. Architecture and edge cases

### Schema lifecycle

`OwnedTestSchemaRole` now:

- requires an approved disposable database and exactly one matching `DISPOSABLE` sentinel for the
  expected run ID;
- generates a unique schema and role identity;
- performs role, grants and schema creation in one PostgreSQL transaction;
- records only resources whose creation and commit were confirmed;
- verifies exact schema owner and role identity immediately before cleanup;
- drops only the owned schema and exact role after revoking only that role's known grants;
- leaves ambiguous creation, rollback or cleanup outcomes intact and refuses speculative DROP.

The genuine first run caught a real dependency that unit mocks did not: PostgreSQL refused to drop
the generated role while its exact grants remained. Targeted revokes were added inside the same
transactional cleanup. The corrected genuine suite then passed and left zero suite roles, test
schemas or foreign probes.

### JDBC metadata boundary

DatabaseMetaData result sets are now wrapped. `getStatement()` returns the guarded statement
representation, its `getConnection()` remains guarded, and `unwrap()`/`isWrapperFor()` cannot expose
raw PostgreSQL objects. Legitimate in-schema `getTables()` and `getColumns()` queries remain usable.

### Preparation state machine

- **Confirmed prepared:** a recorded command receipt and exact authorized history item are required.
- **Definitively rejected:** only the two existing trusted public-link configuration messages qualify;
  the old operation is ended and a deliberate new operation ID is generated only on a later click.
- **Outcome unknown:** a generic 503, timeout, lost response or malformed successful response keeps
  the original operation and permits GET-only receipt/history recovery.
- Synchronous in-flight refs, generation checks, account-change cleanup and bounded storage prevent
  double clicks or stale identities from issuing another preparation.
- Status checks remain GET-only.

### Exact-share recovery

The shared resolver paginates by 20, scans at most 100 authorized pages, detects result-set changes
while scanning and restarts once, propagates cancellation/network failures, and reports a truthful
bound-reached condition instead of substituting a newer share. Recovery, final preflight and copy
verification use that resolver. Current authority, consent, destination, shortlist and property flags
are rechecked before handoff.

### Language and responsive behavior

`HANDOFF_ATTEMPTED` is labeled **Attempted to open WhatsApp**. A recovered lost acknowledgement now
says the attempt was recorded but opening could not be confirmed. Scheduling, contact and import copy
no longer exposes receipt/backend/canonical/persisted terminology. The affected ledger records the
previous text, corrected text, business truth, responsive behavior and evidence.

## 3. Files changed in this correction

Backend:

- `backend/src/test/java/com/indore/pathome/spaces/support/OwnedTestSchemaRole.java`
- `backend/src/test/java/com/indore/pathome/spaces/support/OwnedTestSchemaRoleTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/support/OwnedTestSchemaRolePostgresTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/support/RunOwnedSchemaDataSource.java`
- `backend/src/test/java/com/indore/pathome/spaces/support/RunOwnedSchemaDataSourceTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/support/RunOwnedSchemaDataSourcePostgresTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadHttpPostgresTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadShareServiceTest.java`

Frontend:

- `frontend-web/src/components/operations/OperationsLeadsWorkspace.tsx`
- `frontend-web/src/components/operations/OperationsWorkspace.tsx`
- `frontend-web/src/components/operations/OperationsWorkDetail.tsx`
- `frontend-web/src/components/operations/OperationsLessorContactPanel.tsx`
- `frontend-web/src/components/operations/operationsLeads.css`
- `frontend-web/src/services/apiError.ts`
- `frontend-web/src/services/operationsLeadService.ts`
- `frontend-web/src/utils/operationsLeadPresentation.ts`
- `frontend-web/src/utils/operationsSharePreparation.ts`
- `frontend-web/src/tests/operationsPreparationRecovery.test.mjs`
- `frontend-web/src/tests/operationsPlainLanguage.test.mjs`
- `frontend-web/src/tests/operationsLeadsIntegration.test.mjs`

Verification and evidence:

- `frontend-web/browser-verification/operations-leads/controlled-corrections.mjs`
- `frontend-web/browser-verification/operations-leads/run.mjs`
- `tools/oe-integration-env/PATHOME_G10_OE_PLAIN_LANGUAGE_AND_RESPONSIVE_LEDGER_20261023.md`
- `tools/oe-integration-env/evidence/g10-five-finding-correction-20261023/`
- `tools/oe-integration-env/evidence/p1-whatsapp-final-activation-20261022/genuine-browser/`
- `tools/oe-integration-env/PATHOME_G10_FIVE_FINDING_CORRECTION_REPORT_20261023.md`
- `chat_gpt_changes`

## 4. Verification executed

| Evidence level | Command / artifact | Result |
|---|---|---|
| Frontend TypeScript and production bundle | `npm run build` | PASS |
| Focused affected frontend suites | `node --test` over preparation, language, integration, scheduling, workspace, destination, Batch C, navigation and contact suites | 114/114 PASS |
| Backend focused unit | `mvn ... -Dtest=LeadShareServiceTest,OwnedTestSchemaRoleTest,RunOwnedSchemaDataSourceTest test` | 32/32 PASS |
| Isolated PostgreSQL lifecycle/JDBC | `OwnedTestSchemaRolePostgresTest,RunOwnedSchemaDataSourcePostgresTest` | 9/9 PASS |
| Genuine Spring/PostgreSQL sharing witnesses | four preparation/final-action/replay/commit-boundary methods | 4/4 PASS |
| Genuine browser journey | `LeadHttpPostgresTest#genuineBrowserLeadJourneyUsesSpringPostgresAndProductionBuild` | 56/56 checks PASS; no unexpected 5xx |
| Controlled production Chromium | `controlled-corrections.mjs` | 133/133 checks PASS; zero browser/server errors |
| Repository hygiene | `mvn ... test-compile`, `git diff --check` | PASS |

The genuine browser journey covered PROPERTY_SHARING consent persistence, one prepared share and
receipt, clipboard evidence, manual handoff, operator-reported send separated from delivery,
withdrawal, final-action denial after revoked consent, server concealment, account switching and
history persistence.

## 5. Negative controls

- The metadata escape tests were run against the pre-fix wrapper and failed exactly as expected;
  the same tests pass after the metadata ResultSet wrapping.
- The schema collision test asserts no `DROP SCHEMA` or `DROP ROLE` after a collision; the former
  unconditional cleanup path would fail that expectation.
- A destructive replay of the former schema implementation itself was not executed because no
  isolated retained baseline artifact exists. This is recorded honestly rather than claiming a
  historical executable was run.
- Generic preparation 503/network and malformed acknowledgement controls remain uncertain and retain
  the original operation. The controlled browser proves a trusted configuration rejection requires
  a deliberate later click and a new UUID.
- Controlled Chromium proves copy/readiness checks make no preparation POST.

## 6. Database and security facts

- Database: `pathome_oe_test_g10_20261022`
- Run ID: `17cdb6d3-ef16-4b67-9f53-73725c859a41`
- DISPOSABLE sentinel rows: `1`
- Residual `pathome_suite_%` roles: `0`
- Residual CRM test schemas: `0`
- Residual foreign probe schemas: `0`
- Disabled non-internal triggers: `0`
- No access to `pathome_db` or historical `pathome_oe_test_integration`
- Restricted suite login retains no database CREATE, no public CREATE, no public writes and no role
  memberships
- S7 consent revisions and S8 final-action authorization remain server-authoritative
- Sensitive responses retain `Cache-Control: no-store`

The retained services were not restarted: backend PID 38447 remains loopback-only on
`127.0.0.1:8080`; frontend PID 73523 remains loopback-only on `127.0.0.1:5173`.

## 7. UI/UX assessment

Preparation and handoff were measured at 320, 375, 390, 430, 768, 1024, 1280 and 1440px:

- no page horizontal overflow;
- no clipped control or warning copy;
- all tested interactive controls, including property-link anchors, at least 44px high;
- visible 2px keyboard focus;
- mobile handoff actions stack naturally.

The 320px pass found a real overflow in **Wrong number or permission — change it**; mobile action
buttons now wrap within their container without shrinking text or controls. Property-link anchors
were also raised to a 44px target without changing their destination or meaning.

## 8. Status, trackers and residual gates

| Status | Result |
|---|---|
| IMPLEMENTED | YES |
| AGENT-TESTED | YES |
| GENUINE-INTEGRATION-VERIFIED | YES, for affected disposable PostgreSQL and browser journey |
| INDEPENDENTLY ACCEPTED | NO |
| OWNER VISUALLY ACCEPTED | NO |
| RELEASE-READY | NO |

The exact canonical tracker workbooks were not present under the local Documents tree. They were
not substituted and not edited. Proposed synchronization remains the five correction rows, affected
Batch B/D and DEP status, approval evidence and change-log rows after independent review.

`PATHOME_CRM_PUBLIC_ORIGIN` remains absent. Live customer preparation remains fail-closed until the
owner approves and verifies an actual public HTTPS origin. No synthetic origin was applied to
customer runtime.

Physical iPhone/Safari, full VoiceOver/WCAG specialist acceptance, production-scale performance,
real WhatsApp/Meta delivery, staging/production deployment, independent acceptance and owner visual
acceptance remain **NOT TESTED / DEFERRED**.

Web V1 on 31 October remains **HIGH RISK**. Native Tenant/GE on 15 November remains **AT RISK**.
This correction directly reduces G10 implementation risk but does not remove those gates.

## 9. Independent reacceptance scope

The next reviewer should accept or reject only:

1. ownership-aware transactional schema provisioning and cleanup;
2. metadata/ResultSet JDBC escape closure;
3. definitive preparation rejection versus uncertain recovery;
4. exact-share recovery through preflight/copy without preparation POSTs;
5. truthful OE terminology and the 320px handoff layout correction;
6. directly affected consent, final-action, account-isolation, history-bound and responsive regressions.

No automatic reviewer dispatch or Git publication was performed.
