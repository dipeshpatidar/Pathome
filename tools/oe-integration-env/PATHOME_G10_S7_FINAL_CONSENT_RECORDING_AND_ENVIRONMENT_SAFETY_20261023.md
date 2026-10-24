# PATHOME — G10 S7 FINAL CONSENT RECORDING AND ENVIRONMENT SAFETY

**Completed:** 23 October 2026, IST  
**Repository:** `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`  
**Branch / HEAD:** `feature/phase2a-oe-ui-integration` / `a943775e07a153087d0b38c315db20e18b30aec5`

## 1. Status

| Gate | Status |
|---|---|
| IMPLEMENTED | PASS |
| AGENT-TESTED | PASS for the bounded package |
| GENUINE-INTEGRATION-VERIFIED | PASS |
| CORRECTED LIVE APPLICATION VERIFIED | PASS |
| INDEPENDENTLY ACCEPTED | NOT CLAIMED |
| OWNER VISUALLY ACCEPTED | NOT CLAIMED |
| RELEASE-READY | NOT CLAIMED |

Web V1 remains available for owner inspection. The next gate is one focused GPT-6.1 Sol High
reacceptance. No Sol task was dispatched.

## 2. Repository State

- Branch and HEAD remained exactly `feature/phase2a-oe-ui-integration` /
  `a943775e07a153087d0b38c315db20e18b30aec5`.
- The inherited G10 dirty worktree was preserved.
- At completion, `git status --short` contains 155 porcelain entries: the inherited G10 change set
  plus the bounded files listed below.
- The Git index stayed empty. `git diff --check` and `git diff --cached --check` passed.
- No stage, commit, push, merge, PR, rebase, reset, stash, clean, branch switch, deployment, tracker
  edit or WhatsApp/Meta provider action occurred.

## 3. Confirmed S7 Failure

The original live environment had no non-empty CRM contact-encryption configuration. The backend
therefore correctly failed closed for SAME/DIFFERENT encrypted destinations and returned 503.
The frontend then classified dispatched 5xx responses as if they were a lost reply, obscuring the
known service unavailability. The consent POST also had no operation receipt, so a lost
acknowledgement could not prove exactly which historical revision committed.

Before initialization, the retained disposable database contained:

- contact destination ciphertext rows: `0`
- encrypted source payload rows: `0`

No existing ciphertext could be stranded by initializing the new run identity.

## 4. Encryption Identity

- The existing external run secret facility now owns
  `OE_CRM_CONTACT_ENCRYPTION_KEY` and `OE_CRM_CONTACT_ENCRYPTION_KEY_ID`.
- The key was generated once from the OS CSPRNG and stored only in the mode-`600` external
  `/private/tmp/pathome-oe-g10-20261022/secrets.env`.
- Key ID: `primary`.
- The backend receives the exact key through `PATHOME_CRM_CONTACT_ENCRYPTION_KEY`; no key value was
  printed, logged, committed or returned.
- Missing, malformed, non-32-byte and invalid-key-ID controls are refused by the harness.
- Existing valid material is never replaced. A partial identity refuses rather than auto-repairing.
- SHA-256 of the key value as a continuity identifier:
  `08177d8cce588b4bd1881a035ddcc4472ccad44eec85fe574e376dc12b41b081`.
- This identity was unchanged across two verified backend restarts.
- A real run-owned-key reconstruction test encrypted a destination, reconstructed the protection
  service and decrypted it successfully.

## 5. Test-Harness Isolation Correction

The prior defect forwarded the bare:

`SPRING_DATASOURCE_HIKARI_JDBC_URL`

into Maven test execution. That property outranked the dynamically qualified test-schema URL.

The correction has two boundaries:

1. `env-isolation.sh` unsets the Hikari property for test execution, and `runtime.sh` no longer
   forwards it into the child Maven process.
2. `RunOwnedSchemaDataSource` validates every borrowed connection against the exact designated
   database, positive disposable sentinel and exact random test schema. It re-pins application
   borrows to that schema, rejects schema changes and connection unwrapping, and closes rejected
   connections.

Every `Statement`/`PreparedStatement` execution re-verifies the connection facts before running.
After a test attempts `SET search_path TO public`, the next statement is refused before it executes.
Flyway uses a separate bootstrap-only wrapper where `public` is visible after the exact schema for
installed extension operator classes; `current_schema()` must still be the exact owned schema.
Application connections never receive that bootstrap path.

Positive pre-write controls:

- normal borrow resolves to the exact owned schema;
- startup-style `SET search_path TO public` is refused before the next statement;
- a missing schema cannot become a public fallback;
- rejected connections are closed;
- Flyway migrated the isolated schema through V53.

## 6. Consent Receipt Contract

Existing infrastructure is reused. No command-ledger table, schema, migration or index was added.

- Endpoint: `POST /api/v1/operations/leads/{leadId}/contact-preferences`
- New optional header: `Idempotency-Key: <UUID>`
- Existing action/readback:
  `GET /api/v1/operations/lead-commands/{operationId}?action=LEAD_CONTACT_PREFERENCE_RECORD`
- Receipt table: existing `lead_command_receipts`
- Action: `LEAD_CONTACT_PREFERENCE_RECORD`

Legacy callers that omit the header retain the prior command behavior.

The request is bound to actor, Lead, action and canonical payload. The destination identity is an
HMAC fingerprint, not the raw number; the final receipt hash is SHA-256 over the canonical
non-plaintext command identity. `lead_command_receipts` uses the existing
`(actor_user_id, operation_id, action)` uniqueness.

An advisory transaction lock serializes the same actor/key command before checks. A committed
preference, Lead version, activity, audit fact and receipt are written in one transaction. Exact
replay returns the historical view, including the resulting Lead version carried in the receipt
reference, without creating another revision. Changed payload reuse returns 409.

Receipt readback still applies current actor context and Lead visibility. A foreign actor receives
`NOT_RECORDED`; a now-invisible Lead is concealed. No raw destination appears in receipt readback.

## 7. Frontend Recovery

`ShareRecipientStep` now:

- generates a UUID before dispatch;
- blocks duplicate submission while in flight or while an earlier command is unresolved;
- stores only operation UUID, baseline Lead version and baseline preference revision in
  session-scoped storage;
- never stores destination, number, evidence, JWT, password or encryption material;
- clears pending identity on auth change, expiry, completion, safe abandonment and account
  A→B→A;
- distinguishes 400/401/403/404/409/503 from genuinely uncertain transport;
- treats a success-malformed response, 500, 502/504 and network ambiguity as unconfirmed;
- checks the exact receipt and latest consent before enabling continuation;
- does not replay automatically and does not open WhatsApp from recovery;
- blocks if the current revision is revoked, disputed, absent or different from the receipt;
- keeps sharing blocked if receipt or current-state readback fails.

Immediate success also re-reads current consent and Lead truth. A response is not treated as
current authority by itself.

## 8. Failure-Class Results

| Failure class | Result | Evidence |
|---|---|---|
| Missing/malformed/conflicting key | PASS; fail closed | Harness controls + unit tests |
| Stable key across reconstruction/restart | PASS | Run-key test + unchanged identity hash |
| SAME encrypted consent | PASS; ciphertext, mask and key ID | Genuine PostgreSQL |
| DIFFERENT encrypted consent | PASS | Genuine PostgreSQL |
| Destination NONE | PASS; no WhatsApp launch | Genuine + controlled |
| Invalid input/stale version/foreign scope | PASS; zero partial write | Genuine PostgreSQL |
| Known 400/403/404/409/503 | PASS; truthful class, no lost-reply claim | Controlled Chromium |
| 401 | Shared session invalidation; no consent advancement | Code/session regression |
| Network loss / 500-class outcome | PASS; exact receipt/current reconciliation | Controlled + genuine |
| Lost ACK after committed GRANT | PASS; one revision, exact recovery | Genuine + controlled |
| Lost ACK before receipt exists | PASS; remains blocked, no replay | Controlled |
| Concurrent same key | PASS; one committed result | Genuine PostgreSQL + negative control |
| Same key changed payload | PASS; 409, no second revision | Genuine PostgreSQL |
| Different commands at one revision | PASS; one 201, one 409 | Genuine PostgreSQL |
| Activity/audit/receipt failure | PASS; zero partial facts | Genuine fault injection |
| Grant superseded by revocation | PASS; latest REVOKED blocks continuation | Controlled |
| Refresh/account switch | PASS; safe metadata clears/recovers | Controlled |
| Failed readback/recovery GET | PASS; remains blocked | Controlled |
| End-to-end consent → shortlist → share | PASS | Real Chromium + Spring + PostgreSQL |
| S8 withdrawal and final authorization | PASS; zero unauthorized navigation | Controlled + genuine |
| Datasource/search-path escape | PASS; pre-write refusal | Unit + genuine PostgreSQL |
| Owned-schema cleanup/parallel isolation | PASS; zero retained test schemas | PostgreSQL inspection |

## 9. Discriminating Controls

- Unsafe Hikari forwarding previously produced the documented incident. Current harness controls
  prove the key is neutralised and no longer forwarded.
- The full consent receipt branch was temporarily bypassed. The concurrent same-key test failed as
  required with one request receiving 409 instead of the expected replay 201. The safeguard was
  restored; the same test then passed.
- Controlled browser tests separately prove the removed blanket “lost reply” behavior fails to
  satisfy the 503 and known-rejection assertions.
- The latest-revoked recovery case proves a successful historical receipt is insufficient when
  current consent has moved on.

A direct destructive guard-removal test that commits a write into retained `public` was not run
because that mutation is prohibited. The historical incident plus current pre-write refusal tests
provide the bounded discriminator without contaminating retained data.

## 10. Genuine PostgreSQL Evidence

Target:

- database `pathome_oe_test_g10_20261022`
- host `127.0.0.1:5432`
- run identity `17cdb6d3-ef16-4b67-9f53-73725c859a41`
- exactly one `DISPOSABLE` sentinel
- Flyway 53 successful / 0 failed
- Hibernate DDL `none`
- temporary CRM test schemas remaining: `0`
- disabled triggers remaining: `0`

Focused final witnesses:

- encryption/version/owner scope: PASS
- destination revisions and NONE: PASS
- concurrent same-key exact replay and changed-payload 409: PASS
- different commands at same revision, one winner: PASS
- activity-boundary rollback including receipt absence: PASS
- existing share lost-ACK recovery: PASS
- S8 final handoff revalidation: PASS

The latest three-test consent batch was 3/3 PASS. The wider focused five-test PostgreSQL batch was
5/5 PASS. The genuine browser Spring/PostgreSQL journey was 1/1 PASS in 79.2 seconds after explicit
enablement.

## 11. Browser Evidence

Controlled production-bundle Chromium final suite passed with zero browser/page exceptions. It
includes prior G10 regressions and:

- exact consent POST count and no duplicate on lost ACK;
- 503 unavailable message rather than lost reply;
- known 400, 403, 404 and 409 classes;
- refresh recovery with receipt absence and zero replay;
- account A→B→A pending-state clearing;
- successful current GRANT continuation without WhatsApp navigation;
- successful historical receipt superseded by current REVOKED and blocked continuation;
- responsive checks at 320, 375, 390, 430, 768, 1024 and 1440 px with no horizontal overflow.

The genuine browser journey passed React → Spring Boot → PostgreSQL and manual property sharing at
the same responsive set with no unexpected API 5xx.

Evidence:

- `/private/tmp/pathome-g10-s7-controlled-20261023-final2/`
- `tools/oe-integration-env/evidence/p1-whatsapp-final-activation-20261022/genuine-browser/browser-run.log`
- `/private/tmp/pathome-oe-g10-20261022/evidence/s7-negative-receipt-dedupe.log`

## 12. Live Application

- Backend parent/listener: `21461` / `21637`
- Backend: `127.0.0.1:8080`, protected capability read returns 401 without a token
- Frontend: PID `73523`, `127.0.0.1:5173`, root returns 200
- Both listeners are loopback-only.
- Database/run identity: exact values above.
- Encryption key and ID are present in the live JVM.
- Live classpath starts with the current worktree `backend/target/classes`.
- Flyway current version: 53.
- Frontend dev server serves the current source; production build also passed.

Live artifact hashes:

- `LeadContactPreferenceService.java`:
  `5b1685d83cc842c6b5a81c96627643e1c85c94d9e6f5460414fcb0cc476a9be8`
- `LeadContactPreferenceService.class`:
  `b29ed7cdbdba9d84b2d3090310c7a37f35eda7759d88b5cf6324619a962c8254`
- Production JS bundle:
  `f89f3f96a4b06e885ca2542bdcaca7253a7637c9b1d2ae95a4573b2cc10036ef`
- OperationsLeadsWorkspace source:
  `e43fb33948066bce68972db59fc0f4a827b5326a7600e37a285ac0ebcf3e04b2`

Final non-mutating owner smoke passed 11/11 with zero browser exceptions, console errors or API 5xx.
Evidence:
`/private/tmp/pathome-oe-g10-20261022/evidence/g10-s7-owner-smoke-final2-20261023/`.

## 13. Retained Data and Triggers

Lead #6 remains unchanged:

- revision 1: `GRANTED`, `NONE`, `OTHER`, null ciphertext
- revision 2: `REVOKED`, `NONE`, `OTHER`, null ciphertext

No retained review revision was created, edited or removed during this package. Historical
pre-incident row identity remains limited by the known absence of a complete pre-incident
fingerprint; no stronger byte-for-byte assurance is claimed.

Retained `public` still has zero encrypted contact destinations. No migration or Flyway repair was
performed.

## 14. Files Changed

Production/configuration:

- `backend/src/main/java/com/indore/pathome/spaces/controller/LeadController.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/LeadContactPreferenceService.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/LeadService.java`
- `frontend-web/src/components/operations/OperationsLeadsWorkspace.tsx`
- `frontend-web/src/services/operationsLeadService.ts`
- `frontend-web/src/utils/operationsConsentRecovery.ts` (new)
- `tools/oe-integration-env/lib/env-isolation.sh`
- `tools/oe-integration-env/lib/runtime.sh`
- `tools/oe-integration-env/run.sh`

Tests/evidence tooling:

- `backend/src/test/java/com/indore/pathome/spaces/service/LeadContactPreferenceServiceTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadDataProtectionTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadHttpPostgresTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/support/RunOwnedSchemaDataSource.java` (new)
- `backend/src/test/java/com/indore/pathome/spaces/support/RunOwnedSchemaDataSourceTest.java` (new)
- `backend/src/test/java/com/indore/pathome/spaces/support/RunOwnedSchemaDataSourcePostgresTest.java` (new)
- `frontend-web/src/tests/operationsLeadsIntegration.test.mjs`
- `frontend-web/browser-verification/operations-leads/controlled-corrections.mjs`
- `tools/oe-integration-env/g10-review-smoke.mjs`

Accountability/report:

- `chat_gpt_changes`
- this report

No migration, entity, DTO schema, permission model, provider integration or tracker was changed.

## 15. Verification Commands

Representative commands executed:

```bash
bash tools/oe-integration-env/lib/env-isolation.sh selftest
mvn -q -f backend/pom.xml -Dtest=LeadContactPreferenceServiceTest,LeadDataProtectionTest,LeadControllerTest,RunOwnedSchemaDataSourceTest test
mvn -q -f backend/pom.xml -DskipTests test-compile
mvn -q -f backend/pom.xml -DskipTests package
npm run build
node --test frontend-web/src/tests/operationsLeadsIntegration.test.mjs
node frontend-web/browser-verification/operations-leads/controlled-corrections.mjs
```

The genuine PostgreSQL commands ran through `env-isolation.sh`, exact target attestation and the
run-owned datasource wrapper. The genuine browser test additionally required explicit
`PATHOME_LEAD_BROWSER_TEST=true`.

`git diff --check` and `git diff --cached --check` passed.

## 16. Not Tested / Residual Limits

- Physical iPhone/Safari: `NOT TESTED`.
- Complete VoiceOver/WCAG 2.2 AA audit: `NOT TESTED`; focused semantic, keyboard, touch-target and
  responsive checks passed.
- Real WhatsApp/Meta messaging, delivery or read receipt: `NOT TESTED`; not in scope.
- Staging/production deployment: `NOT TESTED`.
- Production-scale performance: `NOT TESTED`.
- Direct destructive guard-removal write into retained `public`: deliberately `NOT TESTED`; the
  active refusal and receipt-bypass discriminator were tested without contaminating retained data.

The unavoidable interval between committed final server authorization and browser navigation or
clipboard write remains; Pathome does not claim post-launch recall or provider delivery.

## 17. Tracker and Deadline Assessment

Read-only search for the exact owner-supplied Smart Intake Approval workbook names found neither
canonical file locally. No older/differently hashed workbook was substituted and no tracker was
modified. Tracker synchronization remains pending in the canonical Pathome environment.

- 31 October 2026 complete Web V1: **HIGH RISK**
- 15 November 2026 native Tenant/GE apps: **AT RISK**

This closure removes the S7 consent-recording dependency but does not expand or reduce approved Web
V1 scope.

## 18. Recommendation

Proceed to one focused independent GPT-6.1 Sol High reacceptance covering:

1. run-owned datasource isolation and startup write boundaries;
2. receipt payload binding, concurrency and rollback;
3. latest-consent-versus-historical-receipt behavior;
4. frontend uncertain-response classification and session isolation.

Owner visual acceptance and release approval remain separate gates.
