# PATHOME — D-B GENUINE POSTGRESQL VERIFICATION HANDOFF

Date: 22 October 2026, IST
Branch: `feature/phase2a-oe-ui-integration`
HEAD: `a943775e07a153087d0b38c315db20e18b30aec5`
Evidence run label: `dbverify-20261022-011339`

**Decision: D-B is not ready for independent acceptance. Genuine PostgreSQL testing found one
production-blocking Lead-queue defect. The owner/peer Lead list endpoint returns 500 on every tested form
because Hibernate emits nullable parameters as untyped PostgreSQL predicates. The isolated typed-cast
control passes, proving the smallest remedy, but the production correction is outside this test-only
authorization.**

## 1. Repository baseline and preservation

- Repository root: `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`.
- Branch and HEAD match the requested baseline.
- Initial and final index: empty.
- Initial worktree/untracked manifest: 77 entries.
- Final worktree/untracked manifest: 93 entries (16 verification artifacts plus one existing tracked log
  modification; the strengthened test was already present in the initial untracked manifest).
- No branch switch, fetch, stage, commit, push, merge, rebase, reset, stash, lock deletion or tracker edit.
- No production implementation file changed in this continuation.
- Required D-B source hashes remained byte-identical:
  - `LeadService.java` — `9e868d8cd666a2ae598f9c2d5a3237e11b011e996da7ae5caf766a0fc41174ac`
  - `LeadContract.java` — `e4a65e6815e31f114961b60b0abfc259c1f82e5f70f64fc7351b49ce448d3aa6`
  - `LeadShareService.java` — `6b09ee22a8a45d0f6b54120a65a74ff3ea9b7017e6f019a31fcd6adaef43d7c1`
  - `LeadShortlistService.java` — `cfed9cce8422f77dd7c5aa58d1d270c9100b50d884b1e1e850927b5cf0843321`
  - `V53__crm_manual_sharing_and_source_recovery.sql` — `15b856ad6fe8d5f2c999617f255b546b30a457d7ac2a9270bcca7db739d640d3`
- Current `LeadRoutingQueueRepository.java` hash:
  `db3c8f409c824555293657fdb45a3cbddc2c627ae27614ab315411b6c4cfb1d9`.
- Current strengthened test hash:
  `3ee78028da34b74954758ab76493ec85ae2f419b014eb9fbf5df2187afb88f57`.
- `git diff --check` and `git diff --cached --check` passed.

Files changed in this continuation:

- Strengthened
  `backend/src/test/java/com/indore/pathome/spaces/service/LeadHttpPostgresTest.java`.
- Appended `chat_gpt_changes`.
- Created this handoff.
- Created `tools/oe-integration-env/evidence/db-genuine-verification-20261022/` containing the 11
  non-PostgreSQL class XML reports, `TEST-com.indore.pathome.spaces.service.LeadHttpPostgresTest.xml`,
  `TEST-negctl-typed-nullable-filters.xml`, `source-hashes-after.txt` and
  `target-and-cleanup.txt`.

## 2. Authority and safe database target

The request's five governance references, frozen Staff/Scope architecture, Batch D contract, primary
implementation handoff and latest Sol readiness report were accessible. The exact requested canonical
WhatsApp Approval tracker files were not locally accessible; similarly named 22 October files were not
substituted.

The database guard was positively verified before mutation:

- Host/port: loopback `127.0.0.1:5432`.
- Database: `pathome_oe_test_integration`.
- Authenticated user: `postgres`.
- Sentinel: exactly one `DISPOSABLE` row in `pathome_oe_test_env`.
- Sentinel run identity: `20261013T124820-98553`.
- Protected `pathome_db` was not accessed.
- No database was provisioned and no global configuration or safety guard was changed.
- Each HTTP/PostgreSQL class run created one UUID-owned `p4_crm_http_test_*` schema and dropped it.
- Final combined-run schema observed:
  `p4_crm_http_test_694412a068954d4e9cacc85de0e3156c`.
- Final cleanup query found zero remaining `p4_crm_http_test_*` schemas and one sentinel row.

The pre-existing temporary Phase 2A configuration names a different database and was not substituted.

## 3. Migration and application compatibility

Both the primary and negative-control runs used the supported legacy baseline fixture:

1. Loaded `db/baseline/pathome-v1-legacy-core.sql`.
2. Baselines at V1 and applies V2–V52.
3. Seeds a V52 legacy source-fingerprint row.
4. Applies V53.
5. Starts Spring and validates JPA mappings against the migrated schema.

V52 validated as 52 migrations, V53 validated as 53, and V53 applied additively. The seeded legacy row
read back as `LEGACY_ENVELOPE_V1` with `SOURCE_ACTIVATION_NOT_APPROVED`. No migration duplicate,
destructive rewrite or startup/entity mismatch was observed. All owned schemas were dropped afterward.

## 4. F1–F4 verification

### F1 — FAIL

- Exact owner detail: HTTP 200.
- Same-Team foreign owner detail: generic 404.
- Same-Team foreign owner activity: generic 404.
- Same-Team foreign owner list: HTTP 500, not a successful zero-row response.
- Exact owner list with every optional filter supplied: HTTP 500.
- The list failures are production defects, not fixture failures.
- The 500 response is generic and does not disclose private data, but queue/count visibility is unusable.

Root cause: `LeadRoutingQueueRepository.findVisibleLeadPage` uses HQL predicates such as
`(:cityId is null or ...)`, `(:status is null or ...)` and `(:followUpBefore is null or ...)`.
Hibernate 6.5.2 sends these to PostgreSQL as untyped `?` values and PostgreSQL raises SQLState `42P18`,
`could not determine data type of parameter $15`.

Smallest compliant production remedy: keep the existing scoped query and explicitly type the nullable
parameters, for example `cast(:cityId as long)`, `cast(:teamId as long)`,
`cast(:status as string)` and `cast(:followUpBefore as timestamp)` in both select and count queries.
The equivalent isolated control compiled and passed all five genuine tests. No production file was
changed because this package authorizes test-only work.

### F2 — PARTIAL

- Revoked coordinator capture returned 403 and created no Lead, activity, receipt or source-event delta.
- Valid capture persisted exact locality membership and budget `999999999999.99`.
- Same operation UUID and payload replayed to the same Lead with one activity and one receipt.
- Same UUID with changed contact data returned 409 and created no additional Lead.
- Receipt readback returned `RECORDED`.
- No competing revocation/deactivation transaction or `pg_blocking_pids` witness was executed.

### F3 — PASS for the tested transport/replay boundary

- Signed `[A,B]`, then unchanged `A`, then `[B,A]` persisted exactly two quarantined source rows.
- Eight concurrent signed deliveries of `A` all returned 200 and still produced no duplicate row.
- Genuine changed content under the same source identity returned 409.
- Missing and stale HMACs returned 403.
- Wrong form returned 403; a signed unrelated page was ignored.
- Rows carried `EVENT_CANONICAL_V2`, `QUARANTINED` and
  `SOURCE_ACTIVATION_NOT_APPROVED`.
- No Lead, activity or Visit Session was created.
- Process restart, mixed repair/upgrade replay and durable-store failure injection remain not tested.

### F4 — PASS for tested persistence boundaries

- Fractional locality `1.9` returned 400 with unchanged Lead version and zero locality rows.
- A valid integral locality persisted.
- Exact maximum `999999999999.99` persisted without rounding.
- Unit coverage rejects one-over maximum, excess scale, null/zero/negative/overflow IDs and invalid
  ordering.
- The full HTTP/PATCH matrix, DB constraint-failure injection and locality-version matrix were not run.

## 5. D-B business contracts

Real HTTP/PostgreSQL evidence established:

- Consent `SAME`, `GRANTED`, purpose `PROPERTY_SHARING`, wording version and verbal evidence persisted.
- Destination ciphertext and IV were present at rest and the synthetic phone number was absent from
  ciphertext.
- History returned only a masked destination.
- Owner reveal returned the exact synthetic destination and wrote one reveal audit event.
- A stale consent revision returned 409 and left exactly one preference row.
- Same-Team foreign owner preference history returned generic 404.
- Two active canonical-City properties remained ordered in the shortlist.
- Shortlist replay produced one shortlist row.
- Share preparation used configured `https://pathome.test`, ordered links, masked destination and no full
  destination in preview text.
- Share replay produced one share and one `SHARE_PREPARED` activity.
- Clipboard event replay produced one event row and one `SHARE_ACTIVITY` row.
- No delivered/read state was created.
- `DIFFERENT`, `NONE`, transfer/revocation withdrawal, 20-item bounds, unpublished race and changed-price
  revalidation remain not tested at the genuine boundary.

## 6. Concurrency and recovery

- Real concurrent Meta delivery passed with eight HTTP callers against one UUID schema.
- Capture idempotency replay/rejection passed sequentially.
- Revocation before admission passed without partial effects.
- A concurrent authority mutation while capture is blocked was not executed.
- Lost-share-history acknowledgement, source lease/restart recovery, and rollback fault injection remain
  not tested.

## 7. Regression and security

- Concealed detail/activity behavior returned generic 404.
- No-store was asserted on the successful list path in the test, but the current list path fails before
  returning a page.
- Synthetic contact data only; no actual customer number was used.
- Live Meta activation stayed false and no Graph/WhatsApp call was made.
- Meta replay created no Lead, activity or Visit Session.
- Tests did not observe booking, entitlement, OTP, notification, User-linking or provider effects.
- Cross-role activation/deactivation/transfer and full information-disclosure scans remain incomplete
  because the queue path is not functional.

## 8. Evidence ledger and commands

Consolidated command:

```bash
PATHOME_VISIT_OPERATIONS_POSTGRES_TEST=true \
PATHOME_OE_TEST_ALLOW_DISPOSABLE=true \
PATHOME_OE_TEST_DB=pathome_oe_test_integration \
PATHOME_OE_TEST_DB_HOST=127.0.0.1 \
PATHOME_OE_TEST_DB_PORT=5432 \
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/pathome_oe_test_integration \
SPRING_DATASOURCE_USERNAME=postgres \
SPRING_DATASOURCE_PASSWORD='' \
mvn -q -f backend/pom.xml \
  -Dtest=LeadAccessServiceTest,LeadContractTest,LeadControllerTest,MetaWebhookSignatureTest,MetaLeadInboxServiceTest,LeadDataProtectionTest,LeadServiceTest,LeadContactPreferenceServiceTest,LeadSourceProcessingServiceTest,LeadShareServiceTest,LeadShortlistServiceTest,LeadHttpPostgresTest \
  test
```

Result: **46 tests, 2 failures, 0 errors, 0 skipped**.

- Non-database/unit/security tests: 37 PASS.
- Genuine HTTP/PostgreSQL tests: 9 total, 7 PASS, 2 FAIL.
- Raw Surefire XML:
  `tools/oe-integration-env/evidence/db-genuine-verification-20261022/`.
- Negative-control XML: `TEST-negctl-typed-nullable-filters.xml`, 5 tests, 0 failures.
- The negative-control source copy is outside the repository at:
  `/private/tmp/pathome-db-negctl-HL8Eip`.

## 9. Remaining failures, blocked and not tested

**FAIL P1 — PostgreSQL Lead visibility query**

- Current behavior: `/api/v1/operations/leads` returns 500 for owner and peer.
- Impact: S3 Lead queue and consistent list/count truth cannot function.
- Evidence: two independent genuine cases; SQLState `42P18`; parameter `$15`.
- Smallest remedy: typed nullable predicates in select/count queries.
- Authorization required: production source correction and focused rerun.

**BLOCKED/NOT TESTED**

- Concurrent revocation/deactivation while capture waits for guards.
- Real source extraction/retry, lease recovery, process restart and mixed V52/V53 writer cutover.
- `DIFFERENT`/`NONE` consent, destination-change invalidation, stale action revision and transfer withdrawal.
- Property unpublish/price change/private-media race and 20-property boundary.
- Cross-role deactivation/transfer/missing-profile matrix and cross-account session isolation.
- Staging load, query plans, deployment, rollback, physical devices, browser/UI and providers.

## 10. Canonical tracker assessment

The exact required files were not found locally:

- `Pathome_Cross_Role_Development_Tracker_Canonical_2026-10-22_WhatsApp_Approval.xlsx`
- `Pathome_Visit_Session_Implementation_Packages_Canonical_2026-10-22_WhatsApp_Approval.xlsx`

Similarly named files without the `WhatsApp_Approval` suffix are present with hashes
`0561efdc0817a347f4701323161cb7885e7399d47bc0c1004d6a0b37921dfe5a` and
`6de66e0d98872f1bec30f903ca70ea87088ee431f6184905a41300cdd54a01c2`.
They were not silently treated as the requested canonical files, and no workbook was edited. Current
DEP/OE row acceptance therefore could not be independently re-assessed from the exact required sources.

## 11. Deadline and deliverable impact

- **D-B backend acceptance:** BLOCKED by the P1 queue defect; 7 of 9 genuine cases pass.
- **D-A City routing UI:** unaffected source dependency, but not implemented or accepted here.
- **Approved D-B v0 screens:** remain dependent on the v0 visual handoff and a functioning queue API.
- **D-C paired conversion:** remains unimplemented and dependent on D-B acceptance.
- **31 October complete Web V1:** remains **HIGH RISK**.
- **15 November Native Tenant/GE:** remains **AT RISK**.

No project-completion percentage or delivery guarantee is claimed.

## 12. Next independent acceptance boundary and STOP

The next owner-approved action is the narrow production correction of
`LeadRoutingQueueRepository.findVisibleLeadPage` (and any equivalent untyped nullable predicates proven
affected), followed by a focused rerun of `LeadHttpPostgresTest` and the 46-test D-B cohort.

Only after that correction is green may GPT-6.1 Sol High independently accept the D-B backend against
the actual diff and evidence. Sol must not infer D-B UI usability, D-C completion, provider delivery,
device acceptance or release readiness.

No stage, commit, push, merge, PR, deployment, tracker edit, protected-data mutation, provider call or
real communication occurred. STOP.
