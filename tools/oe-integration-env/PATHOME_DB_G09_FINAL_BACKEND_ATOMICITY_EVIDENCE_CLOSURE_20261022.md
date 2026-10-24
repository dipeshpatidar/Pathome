# PATHOME — D-B G09 FINAL BACKEND ATOMICITY EVIDENCE CLOSURE

## 1. Scope and architecture

This was a test-only evidence correction for the accepted D-B backend scope. No production source,
migration, API, authorization, UI, tracker, provider or deployment behavior was changed.

The focused witness is:

- `LeadHttpPostgresTest#shareReceiptCommitFailureLeavesNoPartialHistoryAndExactRetryRecovers`
- [test source](/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0/backend/src/test/java/com/indore/pathome/spaces/service/LeadHttpPostgresTest.java:3024)

It exercises the real controller/service/JPA/PostgreSQL path. The accepted transaction order remains:
share, activity, audit, then final command receipt (`LeadShareService` lines 169–182), with commit
occurring after the service returns.

## 2. Commit-time fault injection and cleanup

The test creates a UUID-named sequence, function and `CONSTRAINT TRIGGER` inside the test class's
positively attested disposable schema:

```sql
CREATE CONSTRAINT TRIGGER <uuid_trigger>
AFTER INSERT ON lead_command_receipts
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION <uuid_function>()
```

The deferred function matches the exact actor, operation UUID, action `LEAD_SHARE_PREPARE` and a
non-null result reference. Matching rows call `nextval(<uuid_sequence>)` and then raise
`G09 share receipt commit failure`. The non-transactional sequence increment proves the deferred hook
fired even though the transaction rollback removes receipt data.

Cleanup is in `finally`: the trigger, function and sequence are dropped; the test then asserts trigger
and relation absence. The owning test class drops its entire UUID schema in `@AfterAll`. Final live
inspection found zero `p4_crm_http_test_*` schemas, zero G09 hook functions/triggers and zero other
database sessions.

The installed definition was read back from `pg_get_triggerdef`; it contains `AFTER INSERT ON`,
`lead_command_receipts` and `DEFERRABLE INITIALLY DEFERRED`.

## 3. Phase A — commit-time rollback

A committed historical share was established first. Baseline and post-failure snapshots included Lead
and shortlist versions, share/activity/audit/receipt counts, historical share row JSON and share-event
count.

Observed:

- HTTP `500`; trigger exception originated from `PgConnection.commit` with
  `Unable to commit against JDBC Connection`.
- `Cache-Control: no-store`.
- Response did not expose the full destination, injected SQL object name or SQL state.
- Deferred hook fired once.
- Lead version `3` -> `3`; shortlist version `0` -> `0`.
- Shares `1` -> `1`; `SHARE_PREPARED` activities `1` -> `1`; share audits `1` -> `1`;
  share receipts `1` -> `1`; share events `0` -> `0`.
- `visit_notification_outbox` `0` -> `0`.
- Enacted operation receipt count: `0`.
- Historical share JSON was byte-for-byte equal before and after.

## 4. Phase B — safe exact retry and replay

After confirmed hook removal, the exact same operation UUID and payload returned HTTP `201`. It
created one new share with Lead/shortlist versions still at `3`/`0`; shares, activities, audits and
receipts each increased from `1` to `2`.

A second identical request returned HTTP `201` with the same share UUID and an unchanged full
snapshot. The new share had zero share events and the notification outbox remained unchanged. The
accepted preparation path contains no provider, WhatsApp or customer-message side effect; no such
action was invoked. Both successful responses were also checked to contain only the masked destination.

## 5. Phase C — discriminating negative control

An isolated copy at `/private/tmp/pathome-g09-negctl-20261022.x1hHGT/repo` changed only
`OperationalAuditService.recordUserEvent` to `Propagation.REQUIRES_NEW`. The exact test then failed as
required: after the receipt commit failure, `shareAuditCount` was `2` instead of baseline `1`.
Share/activity/receipt counts, versions, event count and historical snapshot remained unchanged.

That isolates the no-residual-audit assertion as a genuine detector of split transaction ownership.
The exact mutation is retained in `negative-control/audit-requires-new.patch`; the shared source copy
was never mutated.

## 6. Verification executed

Positive focused G09 command, exit `0`:

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
  -Dtest='LeadHttpPostgresTest#shareReceiptCommitFailureLeavesNoPartialHistoryAndExactRetryRecovers+sharePreparationRollsBackWhenTheActivityWriteFails+sharePreparationRecoversFromLostAcknowledgementWithoutDuplicateHistory' \
  test
```

Result: **3 tests, 0 failures, 0 errors, 0 skipped**.

- `LeadShareServiceTest`: **7 tests, 0 failures, 0 errors, 0 skipped**.
- `mvn -q -f backend/pom.xml -DskipTests test-compile`: PASS.
- `git diff --check`: PASS.

Raw evidence:

- `evidence/db-g09-commit-boundary-20261022/positive/focused-g09.log`
- `evidence/db-g09-commit-boundary-20261022/positive/TEST-LeadHttpPostgresTest.xml`
- `evidence/db-g09-commit-boundary-20261022/positive/TEST-LeadShareServiceTest.xml`
- `evidence/db-g09-commit-boundary-20261022/negative-control/test.log`
- `evidence/db-g09-commit-boundary-20261022/negative-control/TEST-LeadHttpPostgresTest.xml`
- `evidence/db-g09-commit-boundary-20261022/negative-control/audit-requires-new.patch`

## 7. Fingerprints and preservation

Source/test SHA-256:

- `LeadShareService.java`: `a2025f7c6d6da5466735ef2762f8812a3e194435005e132989e044afcfd89b4d`
- `OperationalAuditService.java`: `e5ed356deea6a25c43b8d8506b669b29f2ef7c8e07c824fa15e19dea1669678f`
- `V51__operations_work_filters_indexes.sql`: `0d38b0db50874236b2bc254cd15cebe7d12a0de0df41419cf7455e2ab93c2c85`
- `V52__crm_lead_foundation_and_source_inbox.sql`: `722a4886f2fd125f243920da5e633bdec0726e27936187fe2112e38c3955d2ee`
- `V53__crm_manual_sharing_and_source_recovery.sql`: `15b856ad6fe8d5f2c999617f255b546b30a457d7ac2a9270bcca7db739d640d3`
- `LeadHttpPostgresTest.java`: `5410c7ed5c50482d8e5b04e7a28a18e46d536cf7c19ff0fc5e4d296f7fc9cfb1`
- Positive Surefire XML: `95097e7c169d9ac755443328558cb4867a2e1729e47a01bab5a3d17ff655fdce`
- Negative-control Surefire XML: `ff242f1f1cdfb19f5f06792a7546999d8bc0639b08bfb06762f144a86cd9d165`

`LeadShareService.java`, `OperationalAuditService.java` and V51/V52/V53 retain their pre-task hashes.
Branch is `feature/phase2a-oe-ui-integration`; HEAD and remote both remain
`a943775e07a153087d0b38c315db20e18b30aec5`; the index remains empty. No stage, commit, push, merge,
PR, rebase, reset, stash or branch switch occurred.

Final database cleanup receipt at **2026-10-22 16:05:58 IST**:

- `pathome_oe_test_integration|pathome|127.0.0.1/32|5432|PostgreSQL 14.14`
- Sentinel: `DISPOSABLE|pathome_oe_test_integration|1.0.0|20261013T124820-98553`
- Owned UUID test schemas: `0`
- G09 hook functions: `0`
- G09 hook triggers: `0`
- Other application sessions: `0`

## 8. Trackers and out-of-scope checks

The exact requested Smart Intake Approval workbooks were not available locally; older differently
hashed editions were not substituted, opened for editing or saved. The canonical tracker hashes
therefore remain unverified in this focused run.

Per the approved evidence-only boundary, the complete 58/99 cohorts were not rerun. Independent
acceptance is not self-certified. G10 connected operator UI, browser/keyboard/clipboard behavior,
WhatsApp launch, devices, staging/deployment and live providers remain NOT TESTED. Web V1 remains due
31 October 2026; native Tenant/GE remains due 15 November 2026.

**G09: AGENT-TESTED PASS.**
