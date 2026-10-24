# PATHOME — G10 CORRECTION PACKAGE FINAL IMPLEMENTATION REPORT

**Executed:** 22 October 2026, India Standard Time  
**Repository:** `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`  
**Branch / HEAD:** `feature/phase2a-oe-ui-integration` / `a943775e07a153087d0b38c315db20e18b30aec5`  
**Classification:** IMPLEMENTED / CONTROLLED-BROWSER AGENT-TESTED / GENUINE DATABASE GATE BLOCKED  
**Independent acceptance:** NOT CLAIMED  
**Git publication:** NONE

## 1. Executive Result

The approved G10 correction package is implemented across the ordinary Lead authorization surface,
manual-enquiry attachment, consent/action readiness, asynchronous draft and search fencing, queue
continuity, responsive filters, and deterministic availability tests.

The controlled Chromium pass completed 23/23 checks with zero browser/page exceptions. Backend
compilation and 47 focused backend tests pass. The affected frontend cohort passes 107/107, the
availability failure suite passes 53/53, and full frontend discovery exits successfully with 1,037
tests, 1,033 passes, four existing TODOs and zero failures.

The mandatory genuine Spring Boot/PostgreSQL witnesses and connected-browser journey were not run.
The existing attestation procedure refused with `REFUSED [17] Run identity missing`. No database
mutation or synthetic run identity was attempted. Therefore this package is not ready to claim
complete G10 agent verification or to request final Sol acceptance without that bounded DB evidence.

## 2. Baseline and Preserved State

- Repository root, branch and HEAD matched the owner-supplied baseline.
- The inherited dirty worktree was preserved; the index was and remains empty.
- No fetch, sync, branch switch, reset, stash, clean, stage, commit, push, merge or PR occurred.
- V51/V52/V53 hashes are unchanged:
  - V51 `0d38b0db50874236b2bc254cd15cebe7d12a0de0df41419cf7455e2ab93c2c85`
  - V52 `722a4886f2fd125f243920da5e633bdec0726e27936187fe2112e38c3955d2ee`
  - V53 `15b856ad6fe8d5f2c999617f255b546b30a457d7ac2a9270bcca7db739d640d3`
- No migration, provider activation, customer action or automatic outbound message occurred.

## 3. Package Results

| Package | Result | Evidence |
| --- | --- | --- |
| A — Lead access vs diagnostics | IMPLEMENTED / UNIT TESTED | Global diagnostic override removed from ordinary predicates; supervisors retain read-only Team history; exact owned-Team access retained |
| B — Consent / sharing readiness | IMPLEMENTED / CONTROLLED TESTED | SAME, DIFFERENT, NONE, withdrawal, stale ownership and lost event acknowledgement |
| C — Stale capture | IMPLEMENTED / CONTROLLED TESTED | Clear during held duplicate lookup produced zero capture writes and cleared input |
| D — S4 manual attachment | IMPLEMENTED / UNIT TESTED; GATE BLOCKED FOR REAL DB | Additive `POST /operations/leads` contract, protected snapshot, version/lock/idempotency and rollback design |
| E — Smart Search fencing | IMPLEMENTED / CONTROLLED TESTED | Held old page could not append after a new query |
| F — Continuity / incomplete enquiry | IMPLEMENTED / CONTROLLED TESTED | Queue URL continuity, unknown phone, explicit choices, discard warning and truthful copy |
| G — Mobile filters | IMPLEMENTED / CONTROLLED TESTED | 320/375/390/430px single column, complete long selected values; larger widths also checked |
| H — Availability fixtures | FIXED / PASS | Deterministic test clock; 53/53 PASS |
| I — Connected evidence | PARTIAL / NOT TESTED | Controlled checks pass; genuine PostgreSQL/Spring journey blocked by missing run identity |

## 4. Security Separation

Ordinary Lead list/count/detail/activity/history no longer treat `STAFF_ADMIN@GLOBAL` as a record
scope. The repository predicates require one of:

- current owned-Team relationship for the exact actor/Lead owner;
- current supervised-Team read relationship;
- current City intake/routing relationship for an unassigned city Lead.

Global source diagnostics remain on the separate source-exception service. Diagnostic-only Admin is
denied ordinary list/count and ordinary record actions with generic 403 responses. A combined
Admin + legitimate Team/City grant retains only the scope supplied by that legitimate grant.
Frontend `leadCanEdit` no longer grants Admin global edit authority.

## 5. S4 Manual Enquiry Contract

The existing route remains:

`POST /api/v1/operations/leads`

`LeadCaptureCommand` adds optional:

- `duplicateDecision`: `ATTACH_ENQUIRY` or `KEEP_DISTINCT`;
- `existingLeadId`;
- `expectedExistingLeadVersion`;
- `reasonCode`.

Omitted fields remain a legacy create-new command. Extended commands use canonical fingerprint
version `LEAD_CAPTURE_CANONICAL_V2`; legacy requests retain the original command shape and fingerprint.

`ATTACH_ENQUIRY` locks the target Lead, acquires its security guards, performs a fresh editable-scope
check, validates lifecycle/version/City/Team, encrypts the immutable manual-enquiry snapshot, and
atomically writes the source event, version advance, activity, audit and receipt. Existing Lead
requirements, contact, ownership, consent and shortlists are unchanged. New requirements remain
protected enquiry evidence.

`KEEP_DISTINCT` creates one independent Lead, records a protected snapshot and explicit operator
reason, and never merges Users or verified identities. No migration was added; the existing V53
source-event ciphertext/IV/key-version columns are used.

Safe projections:

- `LeadSourceAttributionView.manualEnquiry`;
- narrow `LeadManualEnquiryView`;
- top-level `LeadCaptureOutcomeView` on the command response.

No raw ciphertext, decrypted payload or full contact is returned by these views.

## 6. Consent, Recovery and UX

- Consent remains bound to the exact current preference revision and normalized destination.
- Copy and launch perform fresh server reads before the consequential client action.
- WhatsApp opening uses a second explicit confirmation stage after fresh readiness verification.
- The final launch link retains `target="_blank"` and `rel="noopener noreferrer"`.
- Withdrawal and dispute create new non-granted revisions through the existing contact-preference API.
- Lost event acknowledgement is reconciled through read-only share history; no duplicate clipboard
  or launch is required.
- `CLIPBOARD_COPIED`, `HANDOFF_ATTEMPTED`, operator-reported send and provider delivery remain distinct.
- Capture uses monotonic draft generations, aborts and fences lookup success/error/finalizers, and keeps
  the exact dispatched operation ID and payload through uncertainty.
- Smart Search uses one active query generation for initial and paginated requests, deduplicates IDs
  and ignores late responses/cursors from a retired query.
- Queue page and filters are carried in non-sensitive URL query context and survive navigation/refresh.
- Capture back/navigation guards require explicit keep-editing or discard; `beforeunload` protects
  refresh/close.
- Customer/operator copy no longer exposes source-event UUIDs, quarantine terminology or backend
  command limitations.

## 7. PostgreSQL Gate and Evidence

Before any mutating PostgreSQL test, the repository's attestation procedure was run:

```bash
bash tools/oe-integration-env/run.sh attest
```

Result:

```text
REFUSED [17] Run identity missing
```

`/private/tmp/pathome-oe-test/run-id` is absent. The existing procedure requires exact run identity,
live database identity and a single disposable marker. This package did not bypass that gate,
invent credentials or identity, use `pathome_db`, create a schema, or mutate the disposable database.
There is no “after” DB fact record because no mutating run began.

Consequently these remain `NOT TESTED`:

- real `LeadHttpPostgresTest` privacy witnesses;
- real manual attach/keep-distinct/concurrency/rollback witnesses;
- genuine authenticated Chromium → Spring Boot → PostgreSQL S3–S8/S10 journey;
- real 20×20 share-history query count against PostgreSQL.

The batching implementation has a unit witness proving one listing batch and one locality batch for a
20-share × 20-property membership shape.

## 8. Verification Executed

Backend:

```bash
mvn -q -f backend/pom.xml -DskipTests test-compile
mvn -q -f backend/pom.xml \
  -Dtest='LeadAccessServiceTest,LeadServiceTest,LeadContractTest,LeadPublicPropertyServiceTest,LeadShareServiceTest,LeadControllerTest,LeadSourceProcessingServiceTest' test
```

- Test compile: PASS.
- Focused backend: 47 tests, 0 failures, 0 errors, 0 skipped.
- LeadAccessServiceTest 9/9; LeadServiceTest 7/7; LeadContractTest 7/7;
  LeadPublicPropertyServiceTest 1/1; LeadShareServiceTest 8/8; LeadControllerTest 5/5.
  LeadSourceProcessingServiceTest 10/10.

Frontend:

```bash
npm --prefix frontend-web run build
node --test --test-reporter=dot src/tests/*.test.mjs
node --test \
  src/tests/operationsLeadsIntegration.test.mjs \
  src/tests/operationsWorkspaceIntegration.test.mjs \
  src/tests/operationsDestinationNavigation.test.mjs \
  src/tests/operationsAvailabilityRecoveryFailureClasses.test.mjs
```

- Production TypeScript/Vite build: PASS; only the existing chunk-size warning.
- Full frontend discovery: exit 0; detailed run 1,037 tests, 1,033 PASS, 0 FAIL, 4 TODO.
- Affected frontend cohort: 107/107 PASS.
- Availability failure classes: 53/53 PASS with a fixed `2026-10-22T00:00:00Z` test clock.

Controlled Chromium:

```bash
node frontend-web/browser-verification/operations-leads/controlled-corrections.mjs
```

Result: 23/23 PASS, zero console/page exceptions. It holds duplicate lookup, old Smart Search page,
share event acknowledgement and Lead detail account-switch responses; verifies explicit attach,
SAME/DIFFERENT/NONE, withdrawal, lost-event reconciliation, queue continuity and responsive filters.

Evidence:
`tools/oe-integration-env/evidence/g10-controlled-20261022/`

## 9. Failure-Class Matrix

| Failure class | Discriminating result | Status |
| --- | --- | --- |
| Diagnostic-only Admin | Ordinary named Lead data denied; diagnostics remain separate | Unit/source PASS; real HTTP NOT TESTED |
| Combined legitimate grant | Actor receives only additional grant scope | Unit PASS |
| Ownership/consent transition | Stale action produces zero clipboard/event mutation | Controlled PASS |
| Clear during duplicate lookup | Zero stale capture writes | Controlled PASS |
| Lost event acknowledgement | One clipboard only; read-only history recovery | Controlled PASS |
| Manual attach vs separate | Explicit attach or one independent Lead | Unit/API code PASS; real DB NOT TESTED |
| Attach concurrency/version | One serialized winner expected | Real PostgreSQL NOT TESTED |
| Encryption failure rollback | No source/Lead/activity/receipt write | Unit PASS |
| Legacy capture idempotency | Old command fingerprint unchanged | Unit PASS |
| Old search page | No mixed IDs/cursor after query change | Controlled PASS |
| Queue continuity | Filters/page survive navigation and refresh | Controlled PASS |
| A→B held response | Prior-account detail discarded | Controlled PASS |
| Unknown phone | Enquiry can remain incomplete; sharing gated | Unit/API design PASS; real journey NOT TESTED |
| Revoke/dispute | New non-granted revision blocks readiness | Controlled PASS |
| Share history retry | Read-only; no extra clipboard/launch | Controlled PASS |
| S10 stale retry | Existing retry conflict/fencing contracts retained | Real provider/DB NOT TESTED |
| Narrow filters | Long values complete in one column | Controlled PASS |
| Availability clock | 53/53 deterministic PASS | PASS |
| Share-history performance | One listing + one locality batch in 20×20 unit witness | Unit PASS; real query log NOT TESTED |

## 10. Source Hashes

### Files changed or created in this correction run

Backend:

- `backend/src/main/java/com/indore/pathome/spaces/dto/crm/LeadCaptureCommand.java`
- `backend/src/main/java/com/indore/pathome/spaces/dto/crm/LeadCaptureOutcomeView.java`
- `backend/src/main/java/com/indore/pathome/spaces/dto/crm/LeadDetailView.java`
- `backend/src/main/java/com/indore/pathome/spaces/dto/crm/LeadDuplicateCandidateView.java`
- `backend/src/main/java/com/indore/pathome/spaces/dto/crm/LeadManualEnquiryView.java`
- `backend/src/main/java/com/indore/pathome/spaces/dto/crm/LeadShareHistoryView.java`
- `backend/src/main/java/com/indore/pathome/spaces/dto/crm/LeadSourceAttributionView.java`
- `backend/src/main/java/com/indore/pathome/spaces/entity/LeadShortlistItem.java`
- `backend/src/main/java/com/indore/pathome/spaces/repository/LeadRoutingQueueRepository.java`
- `backend/src/main/java/com/indore/pathome/spaces/repository/LeadShortlistItemRepository.java`
- `backend/src/main/java/com/indore/pathome/spaces/repository/LeadSourceEventRepository.java`
- `backend/src/main/java/com/indore/pathome/spaces/repository/ListingRepository.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/LeadAccessContext.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/LeadAccessService.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/LeadPublicPropertyService.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/LeadService.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/LeadShareService.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/LeadSourceProcessingService.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadAccessServiceTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadHttpPostgresTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadPublicPropertyServiceTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadServiceTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadShareServiceTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadSourceProcessingServiceTest.java`

Frontend:

- `frontend-web/src/components/operations/OperationsLeadsWorkspace.tsx`
- `frontend-web/src/components/operations/OperationsWorkspace.tsx`
- `frontend-web/src/components/operations/operationsLeads.css`
- `frontend-web/src/tests/operationsAvailabilityRecoveryFailureClasses.test.mjs`
- `frontend-web/src/tests/operationsLeadsIntegration.test.mjs`
- `frontend-web/src/utils/operationsLeadPresentation.ts`
- `frontend-web/browser-verification/operations-leads/README.md`
- `frontend-web/browser-verification/operations-leads/controlled-corrections.mjs`
- `frontend-web/browser-verification/operations-leads/run.mjs`

Evidence and accountability:

- `tools/oe-integration-env/evidence/g10-controlled-20261022/controlled-results.json`
- `tools/oe-integration-env/evidence/g10-controlled-20261022/controlled-filters-375.png`
- `tools/oe-integration-env/evidence/g10-controlled-20261022/evidence-manifest.sha256`
- `tools/oe-integration-env/PATHOME_G10_CORRECTION_FINAL_REPORT_20261022.md`
- `chat_gpt_changes`

### Hashes

```text
19e4d2bc1a13fe865d252ba6848d8455455c4215ae1a9bba76c8a7bcb9043f32  LeadService.java
8b4a695e2fdadf41fcedfdc52d4ee904a027469b4995052c30f3ef0148d3d1ee  LeadShareService.java
6e7275d86f8b831da10230e01c1edcc3e322f4a880988be742686b8bde93f609  LeadPublicPropertyService.java
120ba63cc8820be081db42455127ca3bd949c454ff29356adbefffd975dcfe05  LeadAccessContext.java
901b65fd4387e2a43c8cedcfd76e04c6ca97332eeb9653b6d214f39a08063025  LeadAccessService.java
ac403f1644e3e51e816eeb1ffec28b5ffe36220a7fcd815548366b442af82499  LeadSourceProcessingService.java
8aeb55696535165b1b7390210ca1057bbd9422125ef278507fd4fc7303d9b11e  LeadCaptureCommand.java
875a4c1c08ff5f7345a6e1942cf72129492bc066b93c809f338d3ba1166aa275  LeadRoutingQueueRepository.java
fe8b283d823b41ae312c6ee842a3a7464b531fe59216a88611555202358fa7f3  ListingRepository.java
fad007d4c54e0b79434c575bf8e53284e09199e335cc0d33d5d0eee3038a11fc  OperationsLeadsWorkspace.tsx
ee2234908dfd430414dbe8c2b3be545dd6d8d3f05e84bc462cafce5131ffe17f  operationsLeadService.ts
d23122b4be6aa00f0008d20c188c10f3fe018d9b02716f10d33db99216a43d9f  operationsLeadPresentation.ts
4dd87a12d7251fb5a7c1a4f028bbbafeb110d652b2016904acc8568bd725fca6  controlled-corrections.mjs
2c1a3e24a7417634b8c7f0d171eb4759c310acb52e940144d50df98b3959ab52  run.mjs
```

Test hashes:

```text
ecd250bc659f578a913dda127e82afcf97060081e6a3067622942236e0563b7b  LeadServiceTest.java
f518677642817ae671917d084eb576aa84ab4ec967cdeed7d35408ceb8934459  LeadAccessServiceTest.java
e4431a85259cd86ac4030f03cddc59d3925977c92dc1c52892567900a6f5998f  LeadShareServiceTest.java
cf6a2764fa5e7e83521d0ddfdf41ba403e07d57e268e693c1b1495a0aa4315b3  LeadPublicPropertyServiceTest.java
373f5dc7e174853c65dcad022604e3a21704d4c85e260fefd9f6ac67b1276094  LeadHttpPostgresTest.java
f547b1fc0f4b0a90628e1c3fe00eb422e996683efd5b455874565fd58e96e0f3  LeadSourceProcessingServiceTest.java
0b581e828720649c4e3a1574948b648c2ded49c133634b26c3efe657485799b2  operationsLeadsIntegration.test.mjs
228e53366fc31e3a41283c04d3395a28cabcceb0b3133fb3a0072aeff8a03af3  operationsAvailabilityRecoveryFailureClasses.test.mjs
```

## 11. Scope, Performance and UI/UX

- No API outside the approved Lead capture/share-readiness extensions was changed.
- No database migration or entity table was added.
- Share-history current-fact projection now batches public listing and locality reads.
- No unbounded handler query was introduced.
- Unknown/masked contact remains masked in ordinary DTOs.
- Controlled responsive checks cover 320, 375, 390, 430, 768, 1024 and 1440px.
- At ≤479px, City/Team/Status use one full-width column plus a wrapping readback of each selected value.
- Modal focus trapping, Escape, 44px controls, mobile 16px inputs, visible status/error regions and
  reduced-motion CSS remain in place.
- Physical iPhone/Safari, VoiceOver, hardware keyboard, full WCAG AA audit and live WhatsApp remain
  `NOT TESTED`.

## 12. Trackers, Remaining Gate and STOP

The exact filenames required by the request:

- `Pathome_Cross_Role_Development_Tracker_Canonical_2026-10-22_Smart_Intake_Approval.xlsx`
- `Pathome_Visit_Session_Implementation_Packages_Canonical_2026-10-22_Smart_Intake_Approval.xlsx`

were not present locally. No differently hashed tracker was edited or substituted as current.
Proposed synchronization remains implementation/controlled-evidence WIP, not independent acceptance
or release-ready.

Before a Sol reacceptance request, the owner must restore a positively authorized disposable runtime
identity and permit the focused genuine PostgreSQL/Spring witnesses and consolidated connected
Chromium journey. Until then, G10 is not fully agent-verified, not independently accepted, not
integrated for release, and not release-ready. Web V1 31 October remains **HIGH RISK**; native
Tenant/GE 15 November remains **AT RISK**.

No stage, commit, push, merge, PR, tracker save, deployment, provider activation or customer message
occurred. **STOP.**
