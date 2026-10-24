# PATHOME — D-B CRM + MANUAL WHATSAPP BACKEND IMPLEMENTATION HANDOFF

Date: 22 October 2026, IST

Branch: `feature/phase2a-oe-ui-integration`

Starting HEAD: `a943775e07a153087d0b38c315db20e18b30aec5`

Starting state: clean worktree and empty index. The local HEAD and fetched origin branch matched the
owner-approved baseline. The existing checkpoint remains preserved.

Final state: implementation and tests are uncommitted; the index remains empty. No branch switch,
stage, commit, push, merge, rebase, reset, stash or history rewrite occurred.

## 1. Implementation summary

The existing V52 Lead/source foundation was extended rather than replaced. The coherent backend package now
provides:

- exact-owner, SQL-scoped Lead detail/activity reads and consistent list/detail/receipt visibility;
- guarded Lead capture with City → Team → profile/grant evidence held through commit;
- event-specific Meta leadgen fingerprints, durable replay behavior and explicit V52 compatibility;
- exact locality identifiers and NUMERIC(14,2)-compatible budget validation;
- scoped duplicate-candidate lookup with bounded lookup audit/rate controls;
- deliberate source attach/keep-distinct decisions, call/note activity commands, source attribution and
  current qualification/communication/conversion prerequisite readback;
- append-only SAME/DIFFERENT/NONE contact preferences, purpose-bound `PROPERTY_SHARING` consent,
  masked summaries, audited reveal and AES-256-GCM destination protection;
- versioned ordered public-property shortlists capped at 20;
- manual share preparation, current public-property revalidation, masked destination output,
  idempotent share receipts, and truthful clipboard/handoff/operator-report events;
- controlled source extraction/retry with encrypted retained payload, lease recovery and a global,
  redacted exception list/retry seam.

No booking, Visit Session, scheduling, OTP, entitlement, notification delivery, User identity linking,
Meta activation or WhatsApp provider behavior was added.

## 2. F1–F4 corrections

### F1 — exact coordinator ownership

`LeadAccessContext` now compares the Lead owner with the authenticated actor for coordinator reads.
`LeadService` resolves detail and activity through one SQL-scoped visible-record query before returning
data. The same-Team foreign-owner unit discriminator is PASS. The authored HTTP/PostgreSQL case checks
detail 404, activity 404 and a zero scoped count for the foreign coordinator while the owner receives 200.
That HTTP/PostgreSQL case was not executable in this session because no disposable target was attached.

### F2 — guarded capture authority

`LeadService.capture` now performs initial discovery only to identify guard targets, acquires
City → Team → EmployeeProfile/User → grant guards, re-reads active City/Team and current persisted
authority, then holds that evidence through Lead/source/locality/activity/audit/receipt commit.
The guard-before-fresh-admission unit assertion is PASS with zero Lead writes on denial. A genuine
revocation/deactivation lock-wait race is BLOCKED until a positively attested disposable PostgreSQL target
and competing connection are available.

### F3 — per-event Meta identity

Raw-body HMAC validation remains unchanged. Each supported leadgen event now hashes a canonical identity
containing provider/Page/form/leadgen identity and event value, independent of batch membership, envelope
order and JSON formatting. A+B → A and reordered/reformatted retries produce the same event fingerprint.
A genuine V2 payload change still conflicts. Existing V52 envelope-hash rows default to
`LEGACY_ENVELOPE_V1` and are preserved for explicit reconciliation rather than treated as proved tampering.
Batch semantic validation completes before the first durable write. PostgreSQL concurrent replay/restart
and mixed-node cutover remain NOT TESTED.

### F4 — exact IDs and storage-compatible money

Capture exposes raw locality members long enough for exact validation, then accepts only supported positive
integral IDs. Fractional, floating exponent, null, negative, zero, overflow, malformed and over-count inputs
are rejected. PATCH uses the same rule. Budgets are limited by the existing NUMERIC(14,2) range and reject
excess scale/magnitude without silent rounding. The Jackson 1.9 case, 1.001 and 1,000,000,000,000 cases are
PASS. The authored actual HTTP/PostgreSQL atomic-rollback case was skipped because its guarded target flag
and credentials were not present.

## 3. Backend contracts

Existing `/api/v1/operations/leads` endpoints remain the base. Added or extended operations:

- `POST /leads/duplicate-candidates`
- `POST /leads/{id}/source-events`
- `POST /leads/{id}/activities`
- `GET /leads/source-exceptions`
- `POST /leads/source-exceptions/{eventId}/retry`
- `POST/GET /leads/{id}/contact-preferences`
- `GET /leads/{id}/contact-preferences/{revision}/destination`
- `POST /leads/{id}/shortlists`
- `PUT/GET /leads/{id}/shortlists/{shortlistId}`
- `POST /leads/{id}/shares`
- `POST /leads/{id}/shares/{shareId}/events`
- `GET /lead-commands/{operationId}?action=...`

Lead capture, source attachment, shortlist creation and share preparation require an
`Idempotency-Key` UUID and bind the receipt to both the route resource and canonical payload. Reusing a
key with changed Lead scope or payload returns 409.

All Lead responses, including authorization failures on the protected path, retain `Cache-Control: no-store`.
Missing or foreign records return the generic `Lead not found` 404. The share endpoint requires a configured
HTTPS public origin and does not use request Host headers. Contact/source protection requires a configured
32-byte Base64 key; missing key material returns 503 and never falls back to plaintext.

## 4. Files changed

Modified production and configuration files:

- `backend/src/main/java/com/indore/pathome/spaces/controller/LeadController.java`
- `backend/src/main/java/com/indore/pathome/spaces/controller/MetaLeadWebhookController.java`
- `backend/src/main/java/com/indore/pathome/spaces/dto/crm/LeadCaptureCommand.java`
- `backend/src/main/java/com/indore/pathome/spaces/dto/crm/LeadCommandReceiptView.java`
- `backend/src/main/java/com/indore/pathome/spaces/dto/crm/LeadDetailView.java`
- `backend/src/main/java/com/indore/pathome/spaces/entity/LeadRoutingQueue.java`
- `backend/src/main/java/com/indore/pathome/spaces/entity/LeadSourceEvent.java`
- `backend/src/main/java/com/indore/pathome/spaces/entity/LeadSourceEventState.java`
- `backend/src/main/java/com/indore/pathome/spaces/exception/GlobalExceptionHandler.java`
- `backend/src/main/java/com/indore/pathome/spaces/repository/LeadCommandReceiptRepository.java`
- `backend/src/main/java/com/indore/pathome/spaces/repository/LeadRoutingQueueRepository.java`
- `backend/src/main/java/com/indore/pathome/spaces/repository/LeadSourceEventRepository.java`
- `backend/src/main/java/com/indore/pathome/spaces/repository/UserRepository.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/LeadAccessContext.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/LeadAccessService.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/LeadContract.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/LeadService.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/MetaLeadInboxService.java`
- `backend/src/main/resources/application.yml`

Created production and migration files:

- `backend/src/main/resources/db/migration/V53__crm_manual_sharing_and_source_recovery.sql`
- `backend/src/main/java/com/indore/pathome/spaces/dto/crm/`:
  `LeadActivityCommand`, `LeadContactPreferenceCommand`, `LeadContactPreferenceView`,
  `LeadDestinationRevealView`, `LeadDuplicateCandidateResponse`, `LeadDuplicateCandidateView`,
  `LeadDuplicateCandidatesCommand`, `LeadShareEventCommand`, `LeadShareEventView`,
  `LeadSharePrepareCommand`, `LeadShareView`, `LeadShortlistCommand`, `LeadShortlistView`,
  `LeadSourceAttachCommand`, `LeadSourceAttributionView`, `LeadSourceExceptionPage`,
  `LeadSourceExceptionView`.
- `backend/src/main/java/com/indore/pathome/spaces/entity/`:
  `LeadConsentEvidenceMethod`, `LeadConsentStatus`, `LeadContactChannel`, `LeadContactPreference`,
  `LeadDestinationChoice`, `LeadShare`, `LeadShareEvent`, `LeadShareEventType`, `LeadShareMode`,
  `LeadShortlist`, `LeadShortlistItem`.
- `backend/src/main/java/com/indore/pathome/spaces/exception/LeadDataProtectionUnavailableException.java`
- `backend/src/main/java/com/indore/pathome/spaces/repository/`:
  `LeadContactPreferenceRepository`, `LeadShareEventRepository`, `LeadShareRepository`,
  `LeadShortlistItemRepository`, `LeadShortlistRepository`.
- `backend/src/main/java/com/indore/pathome/spaces/service/`:
  `DisabledMetaLeadSourceClient`, `LeadContactPreferenceService`, `LeadDataProtection`,
  `LeadPublicPropertyService`, `LeadShareService`, `LeadShortlistService`,
  `LeadSourceProcessingService`, `MetaLeadEventFingerprint`, `MetaLeadSourceClient`.

Modified tests:

- `backend/src/test/java/com/indore/pathome/spaces/controller/LeadControllerTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/security/MetaWebhookSignatureTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadAccessServiceTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadContractTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/MetaLeadInboxServiceTest.java`

Created tests:

- `backend/src/test/java/com/indore/pathome/spaces/service/LeadContactPreferenceServiceTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadDataProtectionTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadHttpPostgresTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadServiceTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadShareServiceTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadShortlistServiceTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadSourceProcessingServiceTest.java`

Logging/report files:

- `chat_gpt_changes` (append-only entry)
- this handoff

## 5. Verification ledger

| Case | Business invariant | Test/environment | Result |
|---|---|---|---|
| F1 | Same-Team foreign owner cannot read private detail | `LeadAccessServiceTest` unit | PASS |
| F1 | Detail/activity/count agree at the real boundary | `LeadHttpPostgresTest` guarded HTTP/PostgreSQL | BLOCKED / 4 skipped |
| F2 | Guards precede fresh capture admission | `LeadServiceTest` unit ordering | PASS |
| F2 | Revocation/deactivation race leaves zero effects | `LeadHttpPostgresTest` concurrency case still requires two-connection lock witness | BLOCKED |
| F3 | A+B then unchanged A keeps one event identity | `MetaWebhookSignatureTest` unit | PASS |
| F3 | Same V2 ID with changed content conflicts | `MetaLeadInboxServiceTest` unit | PASS |
| F3 | Legacy envelope rows remain explicit | `MetaLeadInboxServiceTest`, migration test authored | PASS unit; PostgreSQL NOT TESTED |
| F4 | Fractional/null/overflow IDs fail before mutation | `LeadContractTest` unit including Jackson 1.9 | PASS |
| F4 | Real HTTP/PATCH leaves no child/aggregate mutation | `LeadHttpPostgresTest` guarded HTTP/PostgreSQL | BLOCKED / 4 skipped |
| M01 | HMAC, configuration and form checks fail closed | `MetaWebhookSignatureTest` | PASS |
| M02 | Replay identity excludes unrelated batch/envelope content | `MetaWebhookSignatureTest`, `MetaLeadInboxServiceTest` | PASS unit; concurrent PostgreSQL NOT TESTED |
| M05/M06 | Contact choice/consent is not inferred; destination is encrypted | `LeadContactPreferenceServiceTest`, `LeadDataProtectionTest` | PASS unit |
| M07 | Lead/version/ownership rules remain enforced | `LeadAccessServiceTest`, `LeadServiceTest`, `LeadShareServiceTest` | PASS unit; live lock races NOT TESTED |
| M11 | Shortlist uses current ACTIVE rental and canonical City | `LeadPublicPropertyService` + `LeadShareServiceTest` | PASS unit; real media/property race NOT TESTED |
| M12 | Copy/handoff facts are explicit; no delivery status exists | API/entity inspection + `LeadShareServiceTest` | PASS unit; clipboard/browser NOT TESTED |
| M16 | Missing key fails closed; no raw PII in logs/URLs | `LeadDataProtectionTest`, static scan | PASS |
| M21 | No booking/entitlement/scheduling code path added | Diff inspection | PASS static; genuine cross-role NOT TESTED |
| M22 | Lists are bounded; source external call occurs outside DB transaction | Repository/service inspection | PASS static; EXPLAIN/load NOT TESTED |

Executed command:

```bash
mvn -q -f backend/pom.xml \
  -Dtest=LeadAccessServiceTest,LeadContractTest,LeadControllerTest,MetaWebhookSignatureTest,MetaLeadInboxServiceTest,LeadDataProtectionTest,LeadServiceTest,LeadContactPreferenceServiceTest,LeadSourceProcessingServiceTest,LeadShareServiceTest,LeadShortlistServiceTest,LeadHttpPostgresTest \
  test
```

Result: 41 tests, 0 failures, 0 errors, 4 skipped. The four skipped tests are the conditional
`LeadHttpPostgresTest` class. Compile and `git diff --check` passed.

Final source hashes:

- `LeadService.java`: `9e868d8cd666a2ae598f9c2d5a3237e11b011e996da7ae5caf766a0fc41174ac`
- `LeadContract.java`: `e4a65e6815e31f114961b60b0abfc259c1f82e5f70f64fc7351b49ce448d3aa6`
- `LeadShareService.java`: `6b09ee22a8a45d0f6b54120a65a74ff3ea9b7017e6f019a31fcd6adaef43d7c1`
- `LeadShortlistService.java`: `cfed9cce8422f77dd7c5aa58d1d270c9100b50d884b1e1e850927b5cf0843321`
- `V53__crm_manual_sharing_and_source_recovery.sql`:
  `15b856ad6fe8d5f2c999617f255b546b30a457d7ac2a9270bcca7db739d640d3`

Raw Surefire evidence:

- `backend/target/surefire-reports/TEST-com.indore.pathome.spaces.service.LeadAccessServiceTest.xml`
- `backend/target/surefire-reports/TEST-com.indore.pathome.spaces.service.LeadContractTest.xml`
- `backend/target/surefire-reports/TEST-com.indore.pathome.spaces.controller.LeadControllerTest.xml`
- `backend/target/surefire-reports/TEST-com.indore.pathome.spaces.security.MetaWebhookSignatureTest.xml`
- `backend/target/surefire-reports/TEST-com.indore.pathome.spaces.service.MetaLeadInboxServiceTest.xml`
- `backend/target/surefire-reports/TEST-com.indore.pathome.spaces.service.LeadDataProtectionTest.xml`
- `backend/target/surefire-reports/TEST-com.indore.pathome.spaces.service.LeadServiceTest.xml`
- `backend/target/surefire-reports/TEST-com.indore.pathome.spaces.service.LeadContactPreferenceServiceTest.xml`
- `backend/target/surefire-reports/TEST-com.indore.pathome.spaces.service.LeadSourceProcessingServiceTest.xml`
- `backend/target/surefire-reports/TEST-com.indore.pathome.spaces.service.LeadShareServiceTest.xml`
- `backend/target/surefire-reports/TEST-com.indore.pathome.spaces.service.LeadShortlistServiceTest.xml`
- `backend/target/surefire-reports/TEST-com.indore.pathome.spaces.service.LeadHttpPostgresTest.xml`

## 6. Migration and database provenance

V53 adds contact-preference revisions, shortlists/items, prepared shares/share events, lookup evidence and
source recovery fields. It preserves V51/V52 and versions legacy source fingerprints. The authored
`LeadHttpPostgresTest` uses `DisposableIntegrationTarget`, creates a unique schema from the supported
legacy baseline, migrates through V52, seeds a legacy source row, upgrades through V53 and drops its owned
schema.

No database was contacted or mutated. `PATHOME_VISIT_OPERATIONS_POSTGRES_TEST`, the designated disposable
database name, app user and credentials were unset. V53 execution, upgrade behavior, concurrent F2 lock
witness, real consent transactions, shortlist/share transactions and rollback remain BLOCKED, not PASS.
The protected `pathome_db` was not accessed.

## 7. Limitations and dependencies

Implemented and agent-tested in source: F1–F4 corrections, contact preferences, shortlists, manual-share
preparation/events, source exception/retry seam, and backend API contracts.

Not independently accepted: the entire package. Sol must review the actual uncommitted delta and evidence.

Genuinely integrated/usable operator journey: NOT ESTABLISHED. Approved v0 S3–S8/S10 screens, destination
confirmation UX, clipboard/browser behavior, account-switch handling and current business-channel operating
procedure remain UI/integration gates.

Deferred by scope: D-A screen implementation; D-C pairing/customer conversion; D-P provider API, outbound
messages, templates and callbacks. Meta source adapters remain disabled and no live provider call occurred.

Production gates: privacy/encryption-key ownership, public-origin configuration, source account/form
activation, current business-device/account custody, retention execution, staging migration/load/restore,
observability, physical devices and accessibility remain OPEN.

## 8. Canonical tracker assessment

Read-only Cross-Role workbook SHA-256:
`0561efdc0817a347f4701323161cb7885e7399d47bc0c1004d6a0b37921dfe5a`.

Read-only Visit Session workbook SHA-256:
`6de66e0d98872f1bec30f903ca70ea87088ee431f6184905a41300cdd54a01c2`.

Current relevant states remain open: DEP-029 In Progress, DEP-030 Partial, DEP-031 Planned, DEP-032 In
Progress, DEP-034 In Progress, DEP-039 Partial, DEP-043/044/053 In Progress, DEP-028 In Progress and
DEP-033 implemented on its existing Tenant baseline. OE #11/#12 remain open outcomes; original #14 date
filter remains open. The Visit tracker retains C26 open, device gates open and D-B/D-C incomplete.

Neither workbook nor its Change Log was modified.

## 9. Deadline and critical path

31 October 2026 complete Web V1 remains HIGH RISK. This package improves the D-B backend critical path but
does not create the approved operator UI or finish D-C.

15 November 2026 Native Tenant/GE remains AT RISK. No native build, physical-device, OS-notification or
installed-app evidence is affected by this backend work.

## 10. Exact next independent acceptance boundary

GPT-6.1 Sol High should review only:

1. F1–F4 source corrections and their focused tests.
2. Consent encryption, revision binding and revocation suppression.
3. Duplicate/source/activity commands and source extraction/retry boundaries.
4. Current-public shortlist and manual-share truth.
5. V53 compatibility and zero unexpected booking/entitlement/provider effects.
6. The missing genuine PostgreSQL evidence, classified as BLOCKED until a disposable target is attached.

The review must not infer operator-screen usability, D-C completion, provider delivery, release readiness
or independent acceptance from the passing unit cohort.

## 11. Prohibited-operation attestation

No Git stage/commit/push/merge/PR/rebase/reset/stash/branch-switch or lock deletion occurred. No canonical
tracker was edited. No protected database/account or live Meta/WhatsApp endpoint was contacted. No customer
message, deployment/configuration change, provider activation or real communication occurred. No agent was
launched.

STOP after this backend implementation and focused acceptance preparation.
