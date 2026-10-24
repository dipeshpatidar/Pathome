# PATHOME — D-B COMPLETE OPERATOR CRM / MANUAL WHATSAPP HANDOFF

Date: 22 October 2026, IST

## 1. Outcome

The D-B backend is implemented and agent-tested for the applicable G01–G09 failure classes. Two additional
real defects were found and corrected during this package:

1. Source recovery had no lease-token fence. An expired worker could complete after a replacement worker
   recovered and overwrite the replacement result. The first genuine test then proved a JPA first-level-cache
   bypass; the final correction clears the persistence context before every lease decision and row lock.
2. Shortlist duplicates were silently collapsed. Duplicate property IDs are now rejected before eligibility
   validation, publication, activity or receipt writes.

The owner-approved connected S3–S8/S10 operator UI could not be implemented truthfully. The exact frozen v0
checkpoint contains only the earlier Visit workspace and the canonical trackers still list the Batch D v0
screen handoff as missing. That UI mutation is blocked pending design authority. No substitute visual system
was invented.

## 2. Repository and source truth

- Root: `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`
- Branch: `feature/phase2a-oe-ui-integration`
- HEAD and fetched origin: `a943775e07a153087d0b38c315db20e18b30aec5`
- Index: preserved empty
- Existing tracked and untracked work: preserved; no reset, stash, clean, branch switch or history rewrite
- Canonical Cross-Role SHA-256:
  `0b9ed98530ad65fb6ce3ffd36e53d89db7335678f3c1475777ce39c94c4c76da`
- Canonical Visit Session SHA-256:
  `384d41c33de864b894b950c4f89a0e6617c072677becce717c166a472bd5a114`

## 3. Production changes in this package

- `backend/src/main/java/com/indore/pathome/spaces/entity/LeadSourceEvent.java`
  - exposes the current lease token for internal fencing
- `backend/src/main/java/com/indore/pathome/spaces/service/LeadSourceProcessingService.java`
  - bounds source recovery at three attempts
  - carries the claiming lease token through external extraction
  - clears the JPA persistence context before every claim/completion/failure lock
  - rejects stale completion with 409
  - ignores stale failure completion
  - retains only the latest recovery result
- `backend/src/main/java/com/indore/pathome/spaces/service/LeadShortlistService.java`
  - rejects duplicate listing IDs instead of silently collapsing them

No controller, DTO, migration, dependency, permission model, Visit Session, Tenant/Lessor or provider code
was changed. V51/V52/V53 remain immutable.

## 4. Tests and evidence changes

- `backend/src/test/java/com/indore/pathome/spaces/service/LeadSourceProcessingServiceTest.java`
  - stale expired worker cannot overwrite a completed recovery lease
  - retry exhaustion is bounded
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadShortlistServiceTest.java`
  - duplicate listing control
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadHttpPostgresTest.java`
  - source lease/recovery fence with encrypted payload
  - capture/revocation PostgreSQL lock wait
  - contact SAME/DIFFERENT/NONE and revision binding
  - current public truth and shortlist limits
  - empty shortlist
  - share lost acknowledgement and ownership switch
  - preference/shortlist/share rollback at activity failure

Evidence: `tools/oe-integration-env/evidence/db-complete-verification-20261022/`.

### 4.1 Reused API and database contracts

- Lead queue/detail/capture/edit: `GET/POST /api/v1/operations/leads`,
  `GET/PATCH /api/v1/operations/leads/{id}`.
- Duplicate and source history: `POST /leads/duplicate-candidates`,
  `POST /leads/{id}/source-events`, `POST /leads/{id}/activities`,
  `GET /leads/{id}/activities`, `GET /leads/source-exceptions`,
  `POST /leads/source-exceptions/{eventId}/retry`.
- Destination/consent: `POST/GET /leads/{id}/contact-preferences`,
  `GET /leads/{id}/contact-preferences/{revision}/destination`.
- Property shortlist/share: `POST /leads/{id}/shortlists`,
  `GET/PUT /leads/{id}/shortlists/{shortlistId}`, `POST /leads/{id}/shares`,
  `POST /leads/{id}/shares/{shareId}/events`.
- Idempotent receipt: `GET /api/v1/operations/lead-commands/{operationId}?action=...`.

The existing V51–V53 contracts are reused unchanged. No new migration or DTO was added in this package.
Lead responses remain `Cache-Control: no-store`; protected services still rely on the established City→Team→
profile/grant lock order, AES-256-GCM destination/source protection and configured trusted HTTPS origin.

### 4.2 Performance and security implications

- Source recovery adds one bounded claim transaction, one external adapter call and one fenced completion
  transaction. It adds no cache and no unbounded retry loop.
- Clearing the JPA persistence context is scoped to the source-recovery transactions and prevents stale entity
  reuse after a lease decision.
- Shortlist validation remains bounded at 20 IDs and rejects duplicates before any property lookup or write.
- The G01 witness holds the existing profile/grant locks through commit; no new lock framework or lock order
  was introduced.
- Contact masking, generic 404s, current-owner authorization, no-store responses and protected ciphertext
  remain unchanged.

## 5. Verification executed

Genuine loopback disposable PostgreSQL 14.14 target:

```bash
PATHOME_VISIT_OPERATIONS_POSTGRES_TEST=true \
PATHOME_OE_TEST_ALLOW_DISPOSABLE=true \
PATHOME_OE_TEST_DB=pathome_oe_test_integration \
PATHOME_OE_TEST_DB_HOST=127.0.0.1 \
PATHOME_OE_TEST_DB_PORT=5432 \
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/pathome_oe_test_integration \
SPRING_DATASOURCE_USERNAME=pathome \
SPRING_DATASOURCE_PASSWORD='' \
mvn -q -f backend/pom.xml -Dtest=LeadHttpPostgresTest test
```

Result: **47 tests, 0 failures, 0 errors, 0 skipped**.

Affected unit/regression batch:

```bash
mvn -q -f backend/pom.xml \
  -Dtest=LeadSourceProcessingServiceTest,LeadShortlistServiceTest,LeadContactPreferenceServiceTest,LeadDataProtectionTest,LeadShareServiceTest,LeadServiceTest,LeadAccessServiceTest,LeadControllerTest,LeadContractTest,MetaLeadInboxServiceTest,MetaWebhookSignatureTest,OperationalSecurityGuardsTest,VisitOperationsServiceTest,TenantVisitEntitlementServiceTest,VisitSessionNotificationListenerTest test
```

Result: **88 tests, 0 failures, 0 errors, 0 skipped**.

Compile and frontend preservation:

```bash
mvn -q -f backend/pom.xml -DskipTests test-compile
npm run build
git diff --check
```

All passed. The frontend build retained the existing 1.46 MB warning but produced no error.

## 6. Negative controls

- Removing source lease fencing made genuine G02 return 200 instead of 409.
- Removing duplicate rejection made G07 throw nothing where `IllegalArgumentException` was required.
- The first G02 honest run also proved the JPA stale-entity bypass; the persistence-context clear was added
  before the final positive cohort.

Mutant reports and hashes: `evidence/db-complete-verification-20261022/negative-controls/MANIFEST.md`.

## 7. Database cleanup

The final receipt is `target-and-cleanup.txt`: one `DISPOSABLE` sentinel with unchanged run identity
`20261013T124820-98553`, zero `p4_crm_http_test_*` schemas, loopback PostgreSQL 14.14 and role `pathome`.
No protected database, reset script, external provider or customer message was touched.

## 8. Canonical tracker assessment

Both canonical 22 October workbooks were read without save or recalculation.

- Cross-Role `OE 20Q Closure` question #12 remains worksheet row 17 and open.
- Role Dependency Register still records DEP-043/044/053 and related work in progress.
- Visit `Web V1 Delivery Gates` still records D-B WIP, D-C missing and no full Web V1 release.
- Approval Gate 68 remains “owner-approved UX, implementation pending.”
- Future tracker reconciliation should append this backend evidence and retain D-B as not independently
  accepted until Sol review.

## 9. Blocked and not-tested boundaries

- Batch D v0 S3–S8/S10 screens: `BLOCKED` on owner-supplied approved design handoff.
- Connected Chromium/operator browser journey at 320/375/768/1024/1440: `NOT TESTED`.
- Clipboard success/failure, WhatsApp launch/cancel/unknown, Back/Forward and account-switch UI races:
  `NOT TESTED`.
- Physical iPhone Safari/VoiceOver and actual WhatsApp business-sender selection: `NOT TESTED`.
- Live Meta/WhatsApp Cloud API: not applicable and not activated.
- D-C authenticated customer conversion: not implemented by this scope.

## 10. Verdicts

- **A. G01–G09 backend:** AGENT-TESTED PASS for the implemented backend contract.
- **B. G10 connected operator journey:** INCOMPLETE; backend security matrix passes, UI is blocked by missing
  approved Batch D v0 screens.
- **C. Manual WhatsApp handoff:** backend preparation/history contract tested; connected UI and launch behavior
  incomplete. No delivery claim.
- **D. Accepted R05/C05/Queue/Visit behavior:** preserved in the focused regression batch.
- **E. D-B overall:** IMPLEMENTED AND AGENT-TESTED ON THE BACKEND; PARTIAL overall; not independently accepted.
- **F. Git, trackers, providers and protected databases:** preserved.
- **G. Deadline impact:** full Web V1 remains HIGH RISK; native Tenant/GE remains AT RISK.

## 11. Next independent review

Use one focused GPT-6.1 Sol High review of:

1. The two new production corrections and their genuine negative controls.
2. G01–G10 ledger and raw Surefire evidence.
3. Backend security, rollback and recovery invariants.
4. Honest separation of backend PASS from the blocked connected UI.

STOP. No Git publication, tracker mutation, deployment, provider activation or automatic reviewer dispatch.
