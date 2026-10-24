# PATHOME — BATCH D-B G10 PRODUCTION CRM INTEGRATION

**Executed:** 22 October 2026, India Standard Time
**Production repository:** `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`
**Branch:** `feature/phase2a-oe-ui-integration`
**HEAD / origin HEAD:** `a943775e07a153087d0b38c315db20e18b30aec5`
**Implementation classification:** IMPLEMENTED / AGENT-TESTED / PARTIAL
**Independent acceptance:** NOT CLAIMED
**Git publication:** NONE

## 1. Executive Result

The production CRM now contains connected, authenticated Lead routes under `/operations/leads`:

- Server-paged Lead queue with City, Team, status, owner, and due-follow-up filters.
- Enquiry capture with server duplicate lookup and idempotent creation.
- Lead detail, requirement editing, call/note activity, and audited owner-only reveal.
- Existing public Smart Search results, ordered shortlist persistence, and stale-version handling.
- `PROPERTY_SHARING` consent recording and immutable server-prepared sharing snapshots.
- Persisted, paginated share history through the minimum necessary read-only API.
- Secure manual WhatsApp URL construction, clipboard success/failure handling, and truthful events.
- Global Staff Admin source-exception access with ordinary OE/supervisor concealment.

One mandatory product path remains blocked by the accepted backend contract: a manual enquiry cannot
truthfully be attached to an existing Lead after duplicate review. Full G10 acceptance is therefore
not claimed.

The genuine Chromium + Spring Boot + PostgreSQL pass completed 35/35 checks. It used real
authenticated bearer tokens and persisted API/database behavior. No WhatsApp or Meta provider was
contacted.

## 2. Production and v0 Preflight

### Production

| Check | Result |
| --- | --- |
| Repository root | `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0` |
| Branch | `feature/phase2a-oe-ui-integration` |
| Historical/current HEAD | `a943775e07a153087d0b38c315db20e18b30aec5` |
| `origin/feature/phase2a-oe-ui-integration` | Same SHA |
| Index | Empty |
| Worktree | Preserved inherited G01–G09/G10 backend work and untracked evidence |
| Migration V51 | `0d38b0db…c2c85`, unchanged |
| Migration V52 | `722a4886…5d2ee`, unchanged |
| Migration V53 | `15b856ad…640d3`, unchanged |

The inherited dirty worktree matched the approved handoff and was not reset, stashed, cleaned, or
reconciled.

### v0 design provenance

No local `v0/oe-panel-ux-finalization` checkout exists on this host. The remote branch
`refs/heads/v0/oe-panel-ux-finalization` was inspected in an isolated temporary clone.

| Provenance item | Actual result |
| --- | --- |
| Current remote branch tip | `2783641f8ef9a192852f004014344cb8759b4f69` |
| Previously reported tip | `561a94c6b89fafe6773e5aed69c7466669973c8e` |
| Relationship | `2783641` is a later commit on the same branch |
| Authors | `v0 <it+v0agent@vercel.com>` for the Batch D screen commits |
| S3–S10 source | `components/ops/leads/*`, `components/ops/ops-workspace.tsx`, `app/ops/page.tsx` |
| Batch-specific QA report | NOT FOUND on the branch |
| Retained screenshots | Older OE review screenshots only; no new S3–S10 visual acceptance set |
| Local uncommitted v0 delta | NOT INSPECTABLE because no local v0 workspace exists |

The reviewed components were semantic/layout references only. No source branch merge, file copy of
fixtures, or Git-history operation occurred.

## 3. v0-to-Production Mapping

| v0 source | Production destination | Integration treatment |
| --- | --- | --- |
| `lead-queue.tsx` | `OperationsLeadsWorkspace.tsx` queue | Replaced fixture filtering with server paging/filter APIs |
| `lead-capture.tsx` | Capture screen | Real duplicate lookup/capture; removed synthetic attach and outcomes |
| `lead-workspace.tsx` | Lead detail | Real detail/activity/PATCH/contact APIs and version retention |
| `property-discovery.tsx` | Share properties step | Existing public property discovery; real listing IDs and shortlist bounds |
| `share-flow.tsx` | Recipient/prepare flow | Real consent revisions, shortlist versions, and server snapshot |
| `share-handoff.tsx` | Handoff/history | Exact secure link, clipboard evidence, event APIs, paginated history |
| `exceptions.tsx` | Source exceptions | Staff Admin-only list and retry; demo discard removed |
| `lead-ui.tsx` / `leads.css` | Accessible primitives / CSS | Production modal focus trap, 44px targets, responsive layout |

Raw fixture data, persona switching controls, synthetic PH-1228, placeholder links, fake timers,
simulated discard, and fabricated provider states were not transferred.

## 4. Implemented Scope and File Inventory

### Backend

Modified:

- `backend/src/main/java/com/indore/pathome/spaces/controller/LeadController.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/LeadService.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/LeadShareService.java`
- `backend/src/main/java/com/indore/pathome/spaces/repository/LeadShareRepository.java`
- `backend/src/main/java/com/indore/pathome/spaces/repository/LeadShareEventRepository.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadShareServiceTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadHttpPostgresTest.java`

Created:

- `backend/src/main/java/com/indore/pathome/spaces/dto/crm/LeadShareHistoryEventView.java`
- `backend/src/main/java/com/indore/pathome/spaces/dto/crm/LeadShareHistoryPage.java`
- `backend/src/main/java/com/indore/pathome/spaces/dto/crm/LeadShareHistoryView.java`

The share-history read is:

`GET /api/v1/operations/leads/{leadId}/shares?page=0&size=10`

It is owner/current-scope authorized, newest-first by `recordedAt DESC, id DESC`, returns masked
destinations, actual stored event types/timestamps, immutable preview/link snapshots, and current
consent/fact match flags. It performs no writes and always returns `Cache-Control: no-store`.

No migration or database schema change was added.

### Frontend

Modified:

- `frontend-web/src/components/Home.tsx`
- `frontend-web/src/components/operations/OperationsDestination.tsx`
- `frontend-web/src/components/operations/OperationsWorkspace.tsx`
- `frontend-web/src/components/operations/operationsWorkspace.css`
- `frontend-web/src/hooks/useOperationsCapability.ts`
- `frontend-web/src/utils/operationsWorkspacePresentation.ts`
- `frontend-web/src/tests/operationsDestinationNavigation.test.mjs`
- `frontend-web/src/tests/operationsWorkspaceIntegration.test.mjs`

Created:

- `frontend-web/src/components/operations/OperationsLeadsWorkspace.tsx`
- `frontend-web/src/components/operations/operationsLeads.css`
- `frontend-web/src/services/operationsLeadService.ts`
- `frontend-web/src/utils/operationsLeadPresentation.ts`
- `frontend-web/src/tests/operationsLeadsIntegration.test.mjs`
- `frontend-web/browser-verification/operations-leads/run.mjs`
- `frontend-web/browser-verification/operations-leads/README.md`

### Evidence and logging

- `tools/oe-integration-env/evidence/g10-connected-20261022/`
- `tools/oe-integration-env/PATHOME_G10_PRODUCTION_CRM_INTEGRATION_REPORT_20261022.md`
- `chat_gpt_changes` appended after this report.

## 5. G10 Journey Status

| Surface | Status | Evidence / limitation |
| --- | --- | --- |
| S3 queue | PASS, agent-tested | Genuine browser + server list; paging/filter request contract covered |
| S4 capture | PARTIAL | Capture, duplicate lookup, and keep-distinct pass; manual attach-to-existing BLOCKED |
| S5 workspace | PASS, agent-tested | Real detail, edit, activity, consent/history reads, owner controls, audited reveal contract |
| S6 search/shortlist | PASS, agent-tested | Empty/results states and ordered persisted shortlist; prior accepted 20/21 and conflict tests retained |
| S7 destination/consent | PARTIAL | SAME + consent + immutable prepare pass in Chromium; DIFFERENT/NONE API accepted, UI variants not browser-run |
| S8 manual WhatsApp/history | PASS, agent-tested | Secure URL, copy success/failure, launch attempt, operator report, persisted history reload |
| S10 source exceptions | PARTIAL | OE concealment and Staff Admin surface pass; live retry NOT TESTED because provider activation is disabled |
| Independent G10 acceptance | NOT CLAIMED | Requires the dedicated Sol review |

### S4 contract blocker

Current behavior:

1. Duplicate lookup returns candidate Lead IDs.
2. `POST /leads/{leadId}/source-events` accepts an `eventId` for an existing quarantined source event.
3. Manual capture requires an `Idempotency-Key` but creates its own new Lead and processed source event
   atomically.

Problem:

- A manual enquiry has no pre-capture quarantined source-event UUID.
- There is no accepted command that atomically attaches a manual enquiry payload to a selected
  existing Lead while preserving the typed requirements and source attribution.

Production UI behavior:

- Shows real duplicate candidates.
- Allows opening a candidate.
- Allows saving as a separate Lead.
- Does not show a fake or lossy attach action.

Smallest robust correction options, requiring owner approval:

1. Extend accepted capture with an explicit existing-Lead/duplicate resolution contract and one
   transaction boundary. Recommended.
2. Add a dedicated manual-enquiry attach command with idempotency, version checks, source attribution,
   access control, and rollback tests.
3. Accept keep-distinct/open-candidate only and defer attach.

## 6. Security and Privacy Evidence

| Scenario | Expected | Executed result |
| --- | --- | --- |
| Current owner | Authorized Lead/detail/share actions | PASS |
| Same-Team foreign owner | Generic concealment, no prior-account data | 404 UI concealment PASS |
| Same-Team supervisor | Read-only detail; no reveal/share | PASS |
| Ordinary OE → source exceptions | denied with no exception data | PASS |
| Global Staff Admin → source exceptions | restricted diagnostic surface | PASS |
| Account A→B with held detail response | late A response discarded | PASS |
| General Lead DTO destination | masked only | PASS |
| Share history destination | masked only | PASS |
| Full destination reveal | owner-only, dialog-scoped, no-store | API accepted; modal code inspected |
| WhatsApp tab | `_blank`, `noopener noreferrer` | PASS |
| Browser launch truth | attempt only; no open/send/delivery claim | PASS |
| Share status/history GET | no mutation, no replay launch | PASS |

The browser's expected 404 concealment responses are retained in redacted network evidence; they were
not treated as JavaScript failures.

## 7. Failure, Recovery, and Negative Controls

| Scenario | Status | Evidence |
| --- | --- | --- |
| Empty public search results | PASS | Real backend + Chromium |
| Real public result | PASS | Real backend + Chromium |
| One ordered shortlist | PASS | Persisted, re-read in share preparation |
| Revision/ownership conflict | PARTIAL | Backend contracts retained; browser conflict race not rerun |
| Duplicate phone candidates | PASS | Real duplicate lookup; zero candidate path exercised |
| Attach versus distinct | BLOCKED | No truthful accepted manual attach command |
| Lost capture acknowledgement | NOT TESTED | Receipt recovery code inspected |
| Lost shortlist acknowledgement | NOT TESTED | Idempotent/reconcile code inspected |
| Lost share acknowledgement | NOT TESTED | Exact idempotent prepare code and prior backend tests retained |
| Same/current consent | PASS | `PROPERTY_SHARING` revision persisted |
| Clipboard success | PASS | Event persisted after successful copy |
| Clipboard failure | PASS | Manual-copy textarea shown; no false copy event |
| Manual launch unknown | NOT TESTED | Browser result is represented as unknown by design; not fault-injected here |
| Operator reported send | PASS | Distinct from delivered/read |
| History after reload | PASS | Snapshot/event remained |
| Withdrawn history access | PASS | Foreign owner/current access loss concealment |
| Source retry conflict refresh | PARTIAL | UI fence implemented; provider retry not live-tested |

Negative controls observed:

- Foreign-owner access returned generic 404 while owner access succeeded.
- A held prior-account detail response remained absent after switching identity.
- Clipboard denial did not create a `CLIPBOARD_COPIED` event.
- Owner source-exception access was denied while the global admin surface succeeded.

## 8. Verification Executed

### Backend

```bash
mvn -q -f backend/pom.xml -DskipTests test-compile
mvn -q -f backend/pom.xml -Dtest=LeadShareServiceTest test
PATHOME_VISIT_OPERATIONS_POSTGRES_TEST=true \
PATHOME_OE_TEST_ALLOW_DISPOSABLE=true \
PATHOME_OE_TEST_DB=pathome_oe_test_integration \
PATHOME_OE_TEST_DB_HOST=127.0.0.1 \
PATHOME_OE_TEST_DB_PORT=5432 \
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/pathome_oe_test_integration \
SPRING_DATASOURCE_USERNAME=pathome \
SPRING_DATASOURCE_PASSWORD= \
mvn -q -f backend/pom.xml \
  -Dtest='LeadHttpPostgresTest#shareHistoryIsScopedPaginatedImmutableAndReadOnly' test
```

Results:

- Test compile: PASS.
- `LeadShareServiceTest`: 7/7 PASS.
- Share-history HTTP/PostgreSQL method: 1/1 PASS.

### Genuine browser

```bash
PATHOME_VISIT_OPERATIONS_POSTGRES_TEST=true \
PATHOME_OE_TEST_ALLOW_DISPOSABLE=true \
PATHOME_LEAD_BROWSER_TEST=true \
PATHOME_OE_TEST_DB=pathome_oe_test_integration \
PATHOME_OE_TEST_DB_HOST=127.0.0.1 \
PATHOME_OE_TEST_DB_PORT=5432 \
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/pathome_oe_test_integration \
SPRING_DATASOURCE_USERNAME=pathome \
SPRING_DATASOURCE_PASSWORD= \
mvn -q -f backend/pom.xml \
  -Dtest='LeadHttpPostgresTest#genuineBrowserLeadJourneyUsesSpringPostgresAndProductionBuild' test
```

Result: PASS, 35/35 browser assertions, zero browser/page exceptions.

The pass used real JWT-authenticated API calls and PostgreSQL writes. The app's sign-in form itself was
not part of this run; this is explicit token-based authentication against the real Spring security
chain.

### Frontend

```bash
npm --prefix frontend-web run build
node --test \
  src/tests/operationsLeadsIntegration.test.mjs \
  src/tests/operationsWorkspaceIntegration.test.mjs \
  src/tests/operationsDestinationNavigation.test.mjs \
  src/tests/operationsBatchBCorrections.test.mjs
```

Results:

- TypeScript + Vite production build: PASS.
- Existing only warning: main JS chunk exceeds 500 kB.
- Affected frontend cohort: 70/70 PASS.

The full frontend discovery run executed 1,036 tests: 1,025 passed, 7 failed, 0 skipped, 4 TODO.
The seven failures are confined to
`operationsAvailabilityRecoveryFailureClasses.test.mjs`. The unchanged HEAD snapshot reproduced the
same 7/53 failures in an isolated temporary checkout, so they are pre-existing and not attributed to
this G10 change. The suite is not reported green.

### Responsive and accessibility

| Width | Page overflow | Visible controls below 44px |
| --- | --- | --- |
| 320px | None | None |
| 375px | None | None |
| 390px | None | None |
| 768px | None | None |
| 1024px | None | None |
| 1440px | None | None |

Screenshots retained:

- `leads-320.png`
- `leads-390.png`
- `leads-768.png`
- `leads-1440.png`
- `lead-workspace-1440.png`
- `manual-whatsapp-history-1440.png`

Code includes semantic landmarks, labels, visible focus, dialog focus trapping, live status/error
messages, 16px mobile editable text, safe-area tab spacing, and reduced-motion CSS.

## 9. PostgreSQL Target and Cleanup Receipt

| Fact | Verified value |
| --- | --- |
| Host / port | `127.0.0.1:5432` |
| Database | `pathome_oe_test_integration` |
| Database owner | `pathome` |
| Test role | `pathome` |
| PostgreSQL | 14.14 |
| Sentinel | Exactly one `DISPOSABLE` marker |
| Run ID | `20261013T124820-98553` |
| Flyway in run-owned schema | V1 baseline → V53 successful |
| Protected `pathome_db` access | NONE |
| Final `p4_crm_http_test_*` schemas | 0 |
| Final application sessions for `pathome` | 0 |
| Final cleanup check | 2026-10-22 17:18 IST |

The harness runtime identity files were absent, so `run.sh attest` correctly refused with code 17.
Direct read-only target checks confirmed the current database identity, owner, role, sentinel, and
unchanged run ID before test mutations. The test class then enforced its own static and live
disposable-target guard and dropped its UUID-owned schema in `@AfterAll`.

## 10. Source and Evidence Hashes

### v0 reviewed source

```text
32bc9fca6c4f8d83eb9511d41aae63670ec433a0468eb7f62adb0698c6373f58  app/ops/page.tsx
f69c50833ab4e1ab4b86ae725961ccef4a68305cfc78fa16f93c5959dc034173  leads-module.tsx
07556cc8e343cdcf2b70d46207e2de1a212fa76267b57f03654706206483e579  lead-queue.tsx
0b41be22959a94a31023551db59708f0d5ac5eae08e82af6a69e09de0eb75559  lead-capture.tsx
5856825392b009468acabe7f243e344f5f9bfbb4c97be06b5f6ff43948118f4f  lead-workspace.tsx
c6f08e3413fc5465705bc47a589acd5394ee6d4fc04a2f4eb343f83fad5938c2  property-discovery.tsx
2cfdc693b12899d908c960ae0ad78f5a6cf293e6f1e47d2372fba5ca79bd7b9e  share-flow.tsx
79a967518ed6abe551df60f9157e8b696acf066c3c590ed9fa6574271530f9d4  share-handoff.tsx
041bb93dc64f106523e501e9cdc7eba5e4c3a9efee1c54d6ba27c207d5f11280  exceptions.tsx
```

### Production implementation hashes

```text
a60e40297e59c15315fe7fe789e63da1a0cdda295967ca3e84055d988f683cb1  LeadController.java
29fe83e804779038514b25eeaee171baee71c50579518a77c0386bb0960bab69  LeadService.java
b3872aca11d80fc9a5f7657668730e3d28fb1156dc71912c67b426f23b53e3ce  LeadShareService.java
4bfa40ebf4cd772fd82fede9f1eadfc6beaf56450a94b9825af0bad93030e3d3  OperationsLeadsWorkspace.tsx
ee2234908dfd430414dbe8c2b3be545dd6d8d3f05e84bc462cafce5131ffe17f  operationsLeadService.ts
b1a17af72d4bbc8229b931ef562be354a668b2c219309b449a2a9fd3a0024a28  operationsLeadPresentation.ts
```

### Browser evidence hashes

```text
193972d3e320283797c00310bc83f0fb337b89b900051f563f48780449365011  browser-results.json
0f2f60f802a9536fa45c933b8e6b1dd310f916d9f73fee58245008ab4e28623a  network-redacted.json
db9c92299bb5124c36138f3c53f149daf72cf8d3adee58b111287d7d8f673623  browser-run.log
```

These hashes are from the final masked-phone green run.

The complete evidence inventory and harness hashes are recorded in:
`tools/oe-integration-env/evidence/g10-connected-20261022/evidence-manifest.sha256`.

## 11. Tracker Assessment

The exact filenames named in the task:

- `Pathome_Cross_Role_Development_Tracker_Canonical_2026-10-22_Smart_Intake_Approval.xlsx`
- `Pathome_Visit_Session_Implementation_Packages_Canonical_2026-10-22_Smart_Intake_Approval.xlsx`

were not present locally. Older and differently hashed files were not substituted.

The current canonical handoff identifies the locally present `_WhatsApp_Approval` editions as the
latest canonical pair. Those exact files and hashes were read-only assessed:

- `Pathome_Cross_Role_Development_Tracker_Canonical_2026-10-22_WhatsApp_Approval.xlsx`
  SHA-256 `0b9ed98530ad65fb6ce3ffd36e53d89db7335678f3c1475777ce39c94c4c76da`
- `Pathome_Visit_Session_Implementation_Packages_Canonical_2026-10-22_WhatsApp_Approval.xlsx`
  SHA-256 `384d41c33de864b894b950c4f89a0e6617c072677becce717c166a472bd5a114`

Relevant recorded state before this integration report:

- DEP-043, DEP-044, DEP-047, DEP-053: In Progress.
- OE #12: D-B WIP / D-C not built / OPEN.
- Batch D approval: D1–D6, including manual prefilled WhatsApp, approved.
- Manual WhatsApp row: Owner approved UX, implementation pending.
- Web V1: 31 October, HIGH RISK, no release GO.
- Native Tenant/GE: 15 November, AT RISK.
- D-P provider activation remains separately gated.

No workbook was edited. Future tracker reconciliation must append this G10 implementation and evidence
without promoting it to independent acceptance or release-ready.

## 12. Remaining Owner Decisions and Blockers

1. Approve a smallest manual attach-to-existing contract so S4 can be complete without a fake action.
2. Confirm the business WhatsApp sender device/account operationally; software cannot verify sender
   identity or delivery.
3. Independent Sol G10 acceptance review.
4. Physical iPhone Safari, VoiceOver, keyboard-only, AA contrast, and mobile keyboard/safe-area UAT.
5. Staging/deployment, provider activation, performance/load, recovery, alerts, and release GO.
6. Tracker synchronization under separate authorization.

## 13. Checks Not Run

- Real WhatsApp network/message send: NOT TESTED by design.
- Live Meta exception retry and provider recovery: NOT TESTED; provider disabled.
- Physical iPhone/Safari, VoiceOver, and hardware keyboard: NOT TESTED.
- Comprehensive manual WCAG AA measurement: NOT TESTED.
- Browser fault injection for lost capture/shortlist/share replies and every ownership-transfer race:
  NOT TESTED in this batch.
- DIFFERENT and NONE UI journeys in Chromium: NOT TESTED in this batch; accepted backend contracts were
  retained.
- Staging, deployment, real production database, notifications delivery, rollback, and release:
  NOT TESTED.
- Full backend regression suite: NOT TESTED; focused G10 cohort ran.

## 14. Scope, Git, and Completion Confirmation

- No new migration or schema change.
- No provider, customer, or automatic outbound message action.
- No unrelated v0 history modification.
- No stage, commit, push, merge, PR, rebase, reset, stash, branch switch, or deployment.
- Branch and HEAD remain unchanged.
- Index remains empty.
- G10 is implemented and agent-tested for the accepted connected path, PARTIAL overall, and not
  independently accepted.
- Web V1 31 October remains HIGH RISK; native Tenant/GE 15 November remains AT RISK.
