# PATHOME G10 FINAL IMPLEMENTATION REPORT

**Completed:** 23 October 2026, IST  
**Repository:** `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`  
**Branch / HEAD:** `feature/phase2a-oe-ui-integration` / `a943775e07a153087d0b38c315db20e18b30aec5`  
**Index:** empty. No stage, commit, push, merge, PR, reset, stash, clean, branch switch or deployment occurred.

## 1. Status

| Gate | Result |
|---|---|
| IMPLEMENTED | PASS for the approved source and disposable-infrastructure scope |
| AGENT-TESTED | PASS |
| GENUINE-INTEGRATION-VERIFIED | PASS against disposable PostgreSQL and a real Spring context |
| CORRECTED LIVE APPLICATION VERIFIED | PASS for restart, identity, loopback and authenticated smoke; live customer sharing still lacks the owner-approved public origin |
| INDEPENDENTLY ACCEPTED | NOT CLAIMED |
| OWNER VISUALLY ACCEPTED | NOT CLAIMED |
| RELEASE-READY | NOT CLAIMED |

## 2. Property-Message Preparation and Recovery

The confirmed loop was caused by two separate errors:

1. The missing public-property origin made preparation unavailable.
2. The client treated a known dispatched 5xx as a lost acknowledgement, then made the recovery button repeat the
   preparation POST.

The corrected frontend now:

- creates and stores one operation identity before dispatch;
- stores only actor/session binding, Lead ID, operation UUID, frozen versions/shortlist/preference identifiers,
  mode and save time;
- expires pending metadata after 24 hours, caps the session at eight entries and clears it on auth change;
- dispatches no preparation POST if UUID generation or browser storage fails;
- sends **one** deliberate preparation POST when the user chooses **Prepare property message**;
- performs **GET-only** receipt checks through
  `GET /api/v1/operations/lead-commands/{operationId}?action=LEAD_SHARE_PREPARE`;
- resolves the exact receipt UUID through existing authorized share history, in bounded pages of 20 and at most
  100 pages;
- never substitutes the newest share when exact history resolution is unavailable;
- verifies the resolved share ID, shortlist, shortlist version, preference revision and mode before showing it;
- never opens WhatsApp from recovery.

The prepare body retains its existing `operationId`; no header was substituted for it. Existing final-action
authorization, consent, destination and public-property checks were not weakened.

## 3. PostgreSQL Test Isolation

`OwnedTestSchemaRole` provisions one random login per integration suite. The role:

- has no role memberships and is not superuser, database creator, role creator, replication user or
  `BYPASSRLS`;
- cannot create schemas or public objects;
- owns only its run-specific schema;
- receives `CONNECT`, temporary-table use inside the run transaction, public schema `USAGE`, and only `SELECT`
  on the disposable marker;
- never receives a privileged bootstrap identity.

Public schema `CREATE` and database `TEMP` were revoked from `PUBLIC` in the disposable G10 database only.
`TEMP` is granted back only to the suite role because migration V40 requires transaction-scoped temporary
tables. This changes no protected database or production permission model.

The JDBC wrapper now preserves `Statement`, `PreparedStatement` and `CallableStatement` proxy types, wraps
result sets and metadata, blocks raw unwrap paths, and prevents `ResultSet.getStatement()` or
`DatabaseMetaData.getConnection()` from returning an unrestricted raw handle. PostgreSQL privileges remain the
durable boundary if the wrapper is bypassed.

Post-test inspection: `0` temporary test schemas, `0` `pathome_suite_*` roles, `0` non-normal triggers.

## 4. Consent Storage Resilience

Operation-ID creation and pending-metadata persistence now occur inside a handled pre-dispatch boundary for
grant, revocation and dispute commands. Quota refusal, unavailable storage, malformed IDs, session change and
UUID-generation failure produce a visible plain-language message, preserve the entered form and send zero
consent POSTs. No timestamp-derived UUID fallback remains.

## 5. Public Property Origin

`PATHOME_CRM_PUBLIC_ORIGIN` is absent from the G10 designation and run secrets. The application default is
blank, and backend validation correctly refuses to generate links.

No domain was invented and no validation was weakened. Live preparation remains blocked by this owner decision.

Acceptable configured value:

```text
PATHOME_CRM_PUBLIC_ORIGIN=https://<owner-approved-public-host>
```

It must be HTTPS, contain no user info/query/fragment, and resolve the existing public property route
`/property/{listingId}`. A synthetic HTTPS origin is used only in isolated integration tests.

## 6. Encryption Provenance

- Existing configured key ID: `primary`.
- No key was rotated, replaced or printed.
- Current raw Base64 value SHA-256:
  `03bd8587f3ec35ea155f86a1497ff708846281b4dd5d1903ab130903c344c871`.
- Current decoded 32-byte key SHA-256:
  `243229b6e204369f769fd7b89f126993539b31417699f593df6342fc6defacf4`.
- The prior report hash `08177d8c...b081` was not reproducible from the current raw value, decoded key or
  newline variants. Its original calculation/source cannot now be established from retained evidence.
- Focused genuine PostgreSQL tests encrypted, reconstructed the protection service and decrypted with the
  current key successfully.
- Live disposable `public` currently contains zero encrypted contact-destination rows and zero encrypted
  source-payload rows.

No historical encrypted row was rewritten. The prior evidence limitation remains explicit rather than
fabricating continuity proof.

## 7. OE Language, Responsive and Accessibility Pass

The current-to-new per-screen ledger is:

`tools/oe-integration-env/PATHOME_G10_OE_PLAIN_LANGUAGE_AND_RESPONSIVE_LEDGER_20261023.md`

Primary terminology now consistently uses property message, WhatsApp number, customer permission, enquiry
source, City and team, Matching Ground Executives, property visit times, visit progress and import issues.
Technical versions, raw identifiers, receipt language and payload terms were removed from ordinary flow;
authorized diagnostics remain inside disclosure.

Notification category controls and error dismissal were raised to a 44px target. Existing focus, modal focus
trapping, reduced-motion handling and destructive-action styling were preserved.

Controlled Chromium verified no horizontal overflow at 320, 375, 390, 430, 768, 1024, 1280 and 1440px.

## 8. Verification Executed

### Frontend

```bash
node --test src/tests/*.test.mjs
npm --prefix frontend-web run build
```

- 1,054 tests, 0 failures.
- TypeScript and Vite production build passed.

### Controlled Chromium

```bash
PATHOME_FIDELITY_EVIDENCE_DIR=file:///private/tmp/pathome-g10-final-20261023 \
  node browser-verification/operations-leads/controlled-corrections.mjs
```

- 67/67 checks passed.
- 0 browser/page exceptions.
- Includes known preparation 503, GET-only status checking, lost preparation acknowledgement recovery,
  denied permission storage with zero POST, refresh/account switching, diagnostics and all required widths.
- Evidence: `/private/tmp/pathome-g10-final-20261023/controlled-results.json`

### Backend / genuine PostgreSQL

```bash
mvn -q -o -f backend/pom.xml \
  -Dtest='LeadHttpPostgresTest,RunOwnedSchemaDataSourceTest,RunOwnedSchemaDataSourcePostgresTest' \
  -DfailIfNoTests=false test
```

- 93 tests: 92 executed PASS, 1 intentionally skipped browser-gated case, 0 failures, 0 errors.
- Wrapper unit: 5/5 PASS.
- Wrapper PostgreSQL: 6/6 PASS.
- Lead HTTP/PostgreSQL: 81/81 executed PASS, 1 browser-gated skip.

### Genuine React → Spring Boot → PostgreSQL browser

```bash
PATHOME_LEAD_BROWSER_TEST=true \
mvn -q -o -f backend/pom.xml \
  -Dtest='LeadHttpPostgresTest#genuineBrowserLeadJourneyUsesSpringPostgresAndProductionBuild' \
  -DfailIfNoTests=false test
```

- PASS.
- 56 checks, no unexpected API 5xx, no browser/page exception.
- Evidence:
  `tools/oe-integration-env/evidence/p1-whatsapp-final-activation-20261022/genuine-browser/browser-run.log`

### Hygiene

```bash
git diff --check
git diff --cached --check
```

Both passed. The Git index remains empty.

## 9. Live Review Application

- Backend parent/listener: `38262` / `38447`.
- Backend: `127.0.0.1:8080`; unauthenticated capability read returns `401`.
- Frontend: PID `73523`, `127.0.0.1:5173`; root returns `200`.
- Live backend classpath begins with the current worktree `backend/target/classes`.
- Database: `pathome_oe_test_g10_20261022`, disposable marker verified, Flyway 53.
- Authenticated owner smoke: 11/11 PASS, zero browser exceptions, console errors or unexpected API 5xx.
- Live class hashes:
  - `LeadService.class`: `3a1c47af6d8a2b31b831406fb8989386987582501d875dec9b7128de5507c76f`
  - `LeadAccessService.class`: `b99ba75df21dd4097f4fbf3383e017e09e9ca345551cefebd9c37452e6416cee`
- Production frontend JS hash:
  `018a925ca6f23707a2485b05f251c6b9abcd44f72a2a98e224b99803cf3595e1`

## 10. Files Changed for This Package

Backend:

- `backend/src/main/java/com/indore/pathome/spaces/service/LeadAccessContext.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/LeadAccessService.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/LeadService.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadHttpPostgresTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/support/OwnedTestSchemaRole.java`
- `backend/src/test/java/com/indore/pathome/spaces/support/RunOwnedSchemaDataSource.java`
- `backend/src/test/java/com/indore/pathome/spaces/support/RunOwnedSchemaDataSourceTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/support/RunOwnedSchemaDataSourcePostgresTest.java`

Frontend and browser verification:

- `frontend-web/src/components/NotificationCenterDrawer.tsx`
- `frontend-web/src/components/operations/OperationsGeAvailability.tsx`
- `frontend-web/src/components/operations/OperationsGePicker.tsx`
- `frontend-web/src/components/operations/OperationsLeadsWorkspace.tsx`
- `frontend-web/src/components/operations/OperationsRequestCoordination.tsx`
- `frontend-web/src/components/operations/OperationsScheduleFlow.tsx`
- `frontend-web/src/components/operations/OperationsWorkDetail.tsx`
- `frontend-web/src/components/operations/OperationsWorkspace.tsx`
- `frontend-web/src/components/operations/operationsWorkspace.css`
- `frontend-web/src/services/operationsLeadService.ts`
- `frontend-web/src/utils/operationsLeadPresentation.ts`
- `frontend-web/src/utils/operationsOperationId.ts`
- `frontend-web/src/utils/operationsShareRecovery.ts`
- `frontend-web/src/utils/operationsWorkspaceNavigation.ts`
- `frontend-web/src/tests/operationsBatchC.test.mjs`
- `frontend-web/src/tests/operationsLeadsIntegration.test.mjs`
- `frontend-web/src/tests/operationsPlainLanguage.test.mjs`
- `frontend-web/src/tests/operationsPreparationRecovery.test.mjs`
- `frontend-web/src/tests/operationsSchedulingClosure.test.mjs`
- `frontend-web/browser-verification/operations-leads/controlled-corrections.mjs`
- `frontend-web/browser-verification/operations-leads/run.mjs`

Harness and documentation:

- `tools/oe-integration-env/lib/runtime.sh`
- `tools/oe-integration-env/g10-review-smoke.mjs`
- `tools/oe-integration-env/PATHOME_G10_OE_PLAIN_LANGUAGE_AND_RESPONSIVE_LEDGER_20261023.md`
- `tools/oe-integration-env/PATHOME_G10_FINAL_IMPLEMENTATION_REPORT_20261023.md`
- `chat_gpt_changes`

## 11. Not Tested / Remaining Gates

- Live customer-facing preparation is **blocked** until an owner-approved HTTPS public origin is supplied.
- No canonical tracker workbook was present locally; tracker synchronization remains proposed only.
- Physical iPhone/Safari, full VoiceOver/WCAG audit, production-scale performance, real WhatsApp/Meta delivery,
  staging, production deployment and independent Sol reacceptance were not performed.
- Owner visual acceptance is separate and not claimed.

## 12. Scope and Invariants

- No migration, entity or API schema changed.
- No key rotation or encrypted-history rewrite occurred.
- No automatic WhatsApp/Meta send, provider delivery claim or real-customer contact occurred.
- City/Team/Lead ownership, additive capabilities, revocable consent, final handshake authorization and
  cross-account isolation remain intact.
- No protected or historical database was accessed.

Web V1 31 October remains HIGH RISK; native Tenant/GE 15 November remains AT RISK. Stop here before independent
review, tracker edits or any Git publication.
