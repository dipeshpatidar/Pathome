# PATHOME — FINAL G10 FIDELITY RECONCILIATION AND ENVIRONMENT REPORT

**Executed:** 22 October 2026, India Standard Time  
**Repository:** `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`  
**Branch / HEAD:** `feature/phase2a-oe-ui-integration` / `a943775e07a153087d0b38c315db20e18b30aec5`  
**UI reconciliation:** IMPLEMENTED / AGENT-TESTED  
**Genuine PostgreSQL and connected-browser verification:** BLOCKED / NOT TESTED  
**Local owner-review application:** NOT STARTED  
**Independent or owner acceptance:** NOT CLAIMED  
**Git publication:** NONE

## 1. Executive Result

The shared Operations shell remains intact and no redesign was introduced. This pass made one bounded
Lead-detail fidelity correction: missing qualification information now uses the approved notice and
Next action treatment, while displaying only persisted gap and follow-up facts. No next-action text,
reminder date or follow-up schedule is inferred.

The v0 queue contract comparison proves that free-text Lead search and the approved Follow-up selector
cannot yet be restored truthfully. The current server supports City, Team, exact status, owner-me and a
due-before instant, but not customer/reference/locality/last-four search or
Overdue/Today/None follow-up states. Implementing those requires explicit approval for a new additive API
query contract. No client-only filtering or unsupported API was added.

The existing designated disposable database remains unproven for a new run because the required run
identity and secrets are absent. Supported attestation returned `REFUSED [17] Run identity missing`.
Creating a fresh database is a separately controlled approval not explicitly granted in this request,
so no database was created, dropped, repaired or connected to. The application remains stopped and the
owner URLs are not currently available.

## 2. Baseline and Reference Truth

- Production branch and HEAD match the owner baseline.
- Local HEAD and `origin/feature/phase2a-oe-ui-integration` both resolve to
  `a943775e07a153087d0b38c315db20e18b30aec5`.
- The inherited dirty worktree was preserved; the Git index remains empty.
- Approved v0 branch: `v0/oe-panel-ux-finalization`
- Approved v0 tip: `2783641f8ef9a192852f004014344cb8759b4f69`
- The earlier parity ledger and final P1 report remain historical input:
  - `tools/oe-integration-env/PATHOME_P1_V0_FIDELITY_PARITY_LEDGER_20261022.md`
  - `tools/oe-integration-env/PATHOME_P1_V0_FIDELITY_FINAL_REPORT_20261022.md`

No production route URL, backend DTO, migration, entity, authorization rule or provider configuration
was changed in this pass.

## 3. Current Shell and Navigation Preservation

- `OperationsDestination` continues to mount one shared `OperationsWorkspace` for `/operations` and
  `/operations/leads*`.
- Lead screens render inside the shared `oe-main` region; the sidebar/tabbar no longer unmounts.
- Desktop and mobile navigation still derive from one capability-filtered destination list.
- Leads and Source exceptions retain distinct active states.
- Restricted diagnostics remain conditional and separate from ordinary Lead access.
- The global Pathome header remains the only account, notification and top-level identity owner.
- The v0 preview strip, persona switcher and prototype controls remain intentionally absent.

## 4. Lead Queue Functionality Reconciliation

| Capability | Current production/server truth | v0 expectation | Result |
| --- | --- | --- | --- |
| Authorized City scope | `cityId` server filter with current access context | Preserve real scope | IMPLEMENTED |
| Authorized Team scope | `teamId` server filter with current access context | Team scope | IMPLEMENTED |
| Exact lifecycle status | `status` server filter | Status filter | IMPLEMENTED |
| Owner-me | `owner=me` server filter | Production capability retained | IMPLEMENTED |
| Follow-up due | `followUpBefore=<instant>` excludes null/current/future values | Overdue/Today/None selector | PARTIAL: due-only support |
| Customer-name search | No server parameter | Search by customer name | MISSING CONTRACT |
| Lead-reference search | No server parameter; displayed reference is the numeric Lead ID | `LD-####` or equivalent reference search | MISSING CONTRACT |
| Locality search | No queue query predicate | Search preferred locality text | MISSING CONTRACT |
| Masked-contact last four | No search parameter; DTO safely masks the number | Last-four search without disclosure | MISSING CONTRACT |
| Paging/count | Real server page and count | Scoped pagination | IMPLEMENTED |
| Stale response fencing | Filter-key generation guards previous UI behavior | No stale queued rows | IMPLEMENTED |

The production City and Owned-by-me controls must not be removed to imitate the synthetic v0 screen.
They represent implemented server behavior and real authority boundaries.

## 5. Proposed Smallest Search/Follow-up Contract — APPROVAL REQUIRED

No part of this proposal was implemented.

Proposed additive query on the existing route:

```text
GET /api/v1/operations/leads
  ?q=<normalized-search>
  &followUp=OVERDUE|TODAY|NONE
  &followUpZone=Asia/Kolkata
  &cityId=<authorized-city>
  &teamId=<authorized-team>
  &status=<status>
  &owner=me
  &page=<zero-based>
  &size=<1..100>
```

Smallest robust semantics:

- `q` is trimmed and collapsed, limited to 80 characters, and used only after the existing ownership,
  City intake, owned-Team and supervised-Team visibility predicate.
- Customer-name matching is case-insensitive.
- A numeric value or normalized `LD-####`/`Lead #id` value may match the numeric Lead identity.
- Locality matching uses the existing `lead_preferred_localities` relationship and locality display
  data; it does not create or promote entities.
- A four-digit value may match the last four digits of the normalized phone without returning the full
  phone, confirming existence outside scope, or allowing arbitrary infix contact search.
- `OVERDUE` compares `followUpAt < now`; `TODAY` uses a server-calculated half-open day in the validated
  `followUpZone`; `NONE` matches only `followUpAt IS NULL`.
- Page count and page content use exactly the same visibility and filter predicate.
- Invalid zone, malformed reference, oversized query and out-of-range paging return the standard
  client error without exposing protected records.
- Frontend query generations fence stale responses; changing any filter resets the page to zero.
- Composite/trigram indexes should be added only if PostgreSQL `EXPLAIN ANALYZE` proves them necessary.
  Any required index migration is a separate schema approval.

This is an API-contract change and therefore remains stopped at the approval boundary.

## 6. Lead Detail Guidance and Activity Truth

The corrected detail screen now includes:

- A missing-information notice whose count and labels come from the server’s persisted
  `qualificationGaps`.
- Human labels for `CONTACT`, `CITY`, `BUDGET` and `PROPERTY_TYPE`; an unknown future server value is
  displayed unchanged rather than guessed.
- A v0-style Next action card that states what persisted gaps remain and shows the current recorded
  follow-up.
- `No follow-up scheduled` when the server returns no follow-up.
- An action that opens the existing authoritative requirements/follow-up editor when the actor is
  editable.
- No inferred date, reminder, schedule, priority or fabricated next-action text.

The backend currently has no free-text `nextAction` field. Adding one would be a new API/schema contract
and was not attempted. The existing follow-up timestamp is the only approved persisted scheduling fact.

The empty Activity section in the earlier controlled screenshot was caused by the controlled harness
returning an empty activity page, not by a proven production history defect. The real
`ATTACH_ENQUIRY` service path writes a `MANUAL_ENQUIRY_ATTACHED` activity, and the activity endpoint
reads persisted activities in descending order. The controlled fixture now returns a matching synthetic
activity so the visual state is internally consistent. Real database persistence remains `NOT TESTED`.

## 7. S6–S10 and Mobile Reconciliation

| Surface | Result | Remaining qualification |
| --- | --- | --- |
| S4 capture | AGENT-TESTED | Approved safety fields retained; sticky action bar verified in a real viewport |
| S6 property discovery/shortlist | No new correction required | Existing real Smart Search and ordered shortlist retained |
| S7 recipient/consent | No new correction required | SAME/DIFFERENT/NONE, revision and withdrawal coverage retained |
| S8 manual handoff/history | No new correction required | Operator-manual semantics and persisted history retained |
| S10 source exceptions | No new correction required | Compact disclosure and independent diagnostics retained |
| Mobile navigation | AGENT-TESTED | One row at 320–430px with accessible active states |

At 375px, the actual sticky capture action bar measured:

```text
top=631 bottom=752 viewportHeight=760 overflow=false
```

The same measurement held at the bottom scroll position. Its mid-page appearance in a full-page
screenshot is therefore a screenshot composition artifact, not a viewport layout defect. No layout
change was made for that observation.

## 8. Exact Code Corrections

- `frontend-web/src/components/operations/OperationsLeadsWorkspace.tsx`
  - Added persisted-gap notice copy and action.
  - Added the fact-only Next action card.
  - Kept all editing, ownership, consent, share and activity behavior unchanged.
- `frontend-web/src/components/operations/operationsLeads.css`
  - Added the approved Next action card styling.
- `frontend-web/src/utils/operationsLeadPresentation.ts`
  - Added deterministic qualification-gap labels and summary formatting.
- `frontend-web/src/tests/operationsLeadsIntegration.test.mjs`
  - Added guidance and no-invention regression coverage.
- `frontend-web/browser-verification/operations-leads/controlled-corrections.mjs`
  - Added populated persisted-follow-up/gap evidence, synthetic attachment activity, and actual
    viewport sticky-action measurement.

No backend, production service, DTO, schema, migration, authorization or provider file was modified.

## 9. Verification Executed

```bash
node --test src/tests/operationsLeadsIntegration.test.mjs
```

Result: **12/12 PASS**.

```bash
node --test \
  src/tests/operationsWorkspaceNavigation.test.mjs \
  src/tests/operationsLeadsIntegration.test.mjs \
  src/tests/operationsWorkspaceIntegration.test.mjs \
  src/tests/operationsDestinationNavigation.test.mjs
```

Result: **59/59 PASS**, zero failures or skips.

```bash
npm --prefix frontend-web run build
```

Result: **PASS**; the existing Vite chunk-size warning remains.

```bash
node frontend-web/browser-verification/operations-leads/controlled-corrections.mjs
```

Result: **35/35 PASS**, zero browser/page exceptions. This is production-build Chromium with synthetic
API transport, not genuine Spring/PostgreSQL evidence.

```bash
git diff --check
git diff --cached --check
```

Both checks are clean. No full frontend or backend regression suite was repeated because this pass did
not change a new broad dependency.

## 10. Database Recovery and Attestation

Current designation:

```text
pathome_oe_test_integration
localhost:5432
```

Current run-directory facts:

```text
/private/tmp/pathome-oe-test/run-id                 MISSING
/private/tmp/pathome-oe-test/secrets.env            MISSING
/private/tmp/pathome-oe-test/backend.pid            MISSING
/private/tmp/pathome-oe-test/backend-binding.json   MISSING
```

Executed:

```bash
bash tools/oe-integration-env/run.sh attest
```

Result:

```text
REFUSED [17] Run identity missing
```

The supported recovery invariant therefore cannot be satisfied without inventing identity or bypassing
the guard. The suspect review schema was not opened, repaired, dropped or reused. `pathome_db` was not
accessed.

## 11. Fresh-Database Approval Boundary

**APPROVAL REQUIRED**

**Current behavior:** The old designated database exists, but its current run identity and secrets are
missing. Attestation correctly fails. Creating a second database is not explicitly authorized by this
request.

**Problem:** Genuine PostgreSQL/Spring witnesses and safe owner startup require a positively identified,
run-owned disposable target. Reusing the old target would treat suspect state as valid.

**Recommended option:** Authorize one fresh target named `pathome_oe_test_g10_20261022`, loopback
`localhost:5432`, with a fresh external run directory such as
`/private/tmp/pathome-oe-g10-20261022`. Update only the test-environment designation and the G10 test
guard allowlist to that exact target. Preserve `pathome_oe_test_integration` and all old evidence
unchanged.

**Alternatives:**

1. Continue using the old database after an independently validated identity-recovery procedure, if one
   can be established without manufacturing proof.
2. Keep genuine DB and connected-browser verification blocked until a later environment review.

The old database must not be dropped merely to free its name.

**Impact and scope:** Test-environment configuration and test-guard designation only. No production
database, API, schema or business rule changes are implied.

After explicit approval, the exact lifecycle boundary is:

```bash
PATHOME_OE_TEST_ENV_FILE=<approved-new-designation-file> \
PATHOME_OE_TEST_ALLOW_LOCAL_BUILD=true \
bash tools/oe-integration-env/run.sh preflight

PATHOME_OE_TEST_ENV_FILE=<approved-new-designation-file> \
bash tools/oe-integration-env/run.sh create

PATHOME_OE_TEST_ENV_FILE=<approved-new-designation-file> \
bash tools/oe-integration-env/run.sh backend

PATHOME_OE_TEST_ENV_FILE=<approved-new-designation-file> \
bash tools/oe-integration-env/run.sh provision

PATHOME_OE_TEST_ENV_FILE=<approved-new-designation-file> \
bash tools/oe-integration-env/run.sh verify

PATHOME_OE_TEST_ENV_FILE=<approved-new-designation-file> \
bash tools/oe-integration-env/run.sh attest
```

None of these creation/startup commands was executed in this pass.

## 12. Genuine PostgreSQL and Connected Browser

Not executed because positive attestation failed:

- PostgreSQL/Spring diagnostic-only Admin denial and owner/supervisor/foreign-Team witnesses.
- Real `ATTACH_ENQUIRY` and `KEEP_DISTINCT`.
- Version/concurrent ownership, replay, lost acknowledgement and rollback witnesses.
- Consent/source history correctness.
- Query-count measurement for share history.
- Authenticated Chromium → Spring Boot → PostgreSQL journeys.
- Real database confirmation that `MANUAL_ENQUIRY_ATTACHED` appears in activity history.

These are `NOT TESTED`, not failed-code results and not acceptance evidence.

## 13. Local Application and Cleanup

- Spring Boot port `8080`: closed.
- Vite port `5173`: closed.
- The application was not started against suspect state.
- Expected owner URLs, once the environment is positively attested:
  - `http://localhost:5173/`
  - `http://localhost:5173/operations/leads`
- No review schema was created in this pass.
- The controlled browser server closed successfully.
- A failed-run screenshot was moved out of retained evidence to
  `/private/tmp/pathome-oe-test/tmp/p1-controlled-failure-20261022.png`.
- Thirty retained PNG evidence images remain in the P1 evidence directory.

## 14. Trackers, Deadlines and Invariants

The two exact Smart Intake Approval workbooks were searched in the repository, Desktop, Documents,
Downloads and Spotlight and remain unavailable. No substitute or older Mac workbook was edited.

- Web V1 on 31 October 2026: **HIGH RISK**.
- Native Tenant/GE on 15 November 2026: **AT RISK**.
- Full approved Web V1 scope and all 20 original OE questions remain preserved.

No tracker save, provider activation, customer message, deployment, stage, commit, push, merge, PR,
branch switch, reset, stash or clean occurred.

## 15. Evidence and STOP

- Comparison: `tools/oe-integration-env/evidence/p1-v0-fidelity-20261022/fidelity-comparison.png`
- Interactive comparison:
  `tools/oe-integration-env/evidence/p1-v0-fidelity-20261022/fidelity-comparison.html`
- Controlled results:
  `tools/oe-integration-env/evidence/p1-v0-fidelity-20261022/controlled-results.json`
- Attestation refusal:
  `tools/oe-integration-env/evidence/p1-v0-fidelity-20261022/attestation-refusal.json`
- Environment preflight:
  `tools/oe-integration-env/evidence/p1-v0-fidelity-20261022/environment-preflight.txt`
- Source/evidence manifest:
  `tools/oe-integration-env/evidence/p1-v0-fidelity-20261022/evidence-manifest.sha256`

Migrations V51–V53 remain unchanged at their previously recorded hashes.

**STOP at the new-database and search/follow-up API approval boundaries. The next review must not begin
until a positively attested clean environment exists and the owner authorizes the controlled search
contract separately.**
