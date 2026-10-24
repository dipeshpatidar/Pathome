# PATHOME D-B F1–F3 CORRECTION AND ACCEPTANCE-EVIDENCE CLOSURE

Date: 22 October 2026, IST

## 1. Outcome

The three owner-approved D-B corrections are implemented and agent-tested on the scoped backend:

- F1 share replay now rejects a historical preview when any current customer-visible property fact differs.
- F2 source recovery now uses exactly three total durable claims and commits exhaustion before returning the conflict.
- F3 source claim, failure cleanup and completion now hold fresh global Staff Admin authority before the source-event lock and through commit.

No migration, DTO, controller, API, permission model, dependency, tracker, provider, UI or released behavior was changed.

## 2. Repository and scope

- Repository: `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`
- Branch: `feature/phase2a-oe-ui-integration`
- HEAD and fetched origin: `a943775e07a153087d0b38c315db20e18b30aec5`
- Index: preserved empty
- Inherited dirty worktree: preserved
- No stage, commit, push, merge, PR, rebase, reset, stash, branch switch or clean

This package changed only:

- `backend/src/main/java/com/indore/pathome/spaces/service/LeadShareService.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/LeadSourceProcessingService.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadShareServiceTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadSourceProcessingServiceTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadHttpPostgresTest.java`
- `chat_gpt_changes`
- `tools/oe-integration-env/evidence/db-f123-correction-20261022/`
- this report

`V53__crm_manual_sharing_and_source_recovery.sql` was not edited.

## 3. Architecture and design

### F1 — immutable preview, current replay truth

Exact replay still obtains the committed receipt and immutable `LeadShare`; it never rewrites either. It now:

1. rechecks current Lead lifecycle and authorization;
2. reads the current shortlist and exact ordered listing IDs;
3. obtains current public properties through `LeadPublicPropertyService`, preserving ACTIVE listing, canonical City, rental type/locality and public-field rules;
4. renders current links and preview with the same production renderer used for first preparation;
5. compares that rendering to the immutable stored preview and returns the existing 409 refresh outcome on any difference.

The comparison therefore covers title, rent, locality, BHK, displayed property details, ordered links and configured public origin without adding a snapshot table or new DTO.

### F2 — three total claims and durable exhaustion

Claims 1–3 each increment the persisted attempt once after the row lock is admitted. A live unexpired claim remains PROCESSING. An expired/crashed third claim is transitioned to QUARANTINED with `SOURCE_RETRY_EXHAUSTED`, `available_at` refreshed, obsolete lease ownership cleared and the encrypted payload/provenance retained. Because the transition is returned from the claim transaction, the durability commit completes before the caller receives 409.

Extraction remains outside row-lock and business transactions. Network, blank, malformed, oversized and encryption failures re-enter a short authority-guarded cleanup transaction using the exact lease token. Completion failure rolls back its partial payload/state write and then performs token-fenced cleanup. A stale success or stale failure cannot overwrite a newer committed recovery.

### F3 — held fresh global authority

The source processor now uses the existing `StaffAccessService.holdGlobalAdminEvidence` guard before any source-event lock for claim, failure cleanup and completion. It re-reads current diagnostics authority after the potentially blocking profile/grant guard and again after the event lock. The profile and effective global grant rows remain held through that short transaction's commit.

Provider extraction occurs between guarded transactions. If authority is lost during extraction, completion is refused and the current lease remains in safe PROCESSING state until expiry/reconciliation. No system actor, role bypass, new lock framework or City/Team authorization rule was added.

The source exception surface is global Staff Admin diagnostics, so expiring global-admin grants and City/Team deactivation are not applicable: the authoritative schema requires global `STAFF_ADMIN` grants to be non-expiring. Profile deactivation and one-of-two effective grants are applicable and tested.

## 4. F1 evidence

Implemented behavior verified:

- unchanged exact replay returns the same committed share/receipt with no new share, activity, communication or event;
- title, rent, location, BHK, ACTIVE publication and canonical locality changes each produce 409;
- a foreign-City property and a closed Lead deny safely;
- historical preview and masked destination remain byte-for-byte unchanged after rejection and after a fresh command;
- a new operation produces a second share containing current truth;
- grant loss returns 403 without disclosing the historical destination;
- invalid shortlist version, changed consent revision and ownership remain guarded by the existing checks;
- advisory operation key serialization preserves one effect for concurrent/lost-ack replay.

Unit negative control removed only the preview comparison: all four changed title/rent/location/BHK assertions failed because no conflict was thrown.

## 5. F2 evidence

Implemented behavior verified:

- attempt transitions 0 → 1 → 2 → 3 occur once per admitted claim;
- a live third claim is not quarantined;
- an expired third claim becomes durably QUARANTINED before 409 and extraction calls remain zero;
- account, external event, form, fingerprint, encrypted payload, IV and key version survive exhaustion;
- blank, malformed, oversized, network and encryption failures clear the lease/plaintext and quarantine without partial payload;
- controlled completion persistence failure rolls back the attempted AWAITING_REVIEW payload and recovers through a fresh persistence context;
- stale success and stale failure after a newer success cannot overwrite or quarantine the committed newer result;
- source attachment winning during extraction causes the stale completion to return 409 with no payload overwrite.

Unit negative control removed only the expired-third exhaustion transition: the assertion observed PROCESSING instead of QUARANTINED.

## 6. F3 evidence

Genuine HTTP/Spring/PostgreSQL witnesses verified:

- revocation commits while claim waits on the held grant row, then fresh authority returns 403 with attempt count 0 and extraction count 0;
- an authorized claim waits on the event row while a concurrent revocation waits on its held grant; the claim commits first and the later completion sees revocation and returns 403 without a payload;
- inactive EmployeeProfile denies before a source claim;
- revoking one of two valid global grants does not deny the actor;
- concurrent source attachment wins without a stale completion overwriting PROCESSED state;
- external extraction stays outside guarded transactions;
- no deadlock or unauthorized mutation was observed.

Negative control bypassed the claim authority hold: the genuine no-mutation assertion failed with one claimed attempt instead of zero.

## 7. G01–G09 disposition

| Gate | Agent-tested disposition |
|---|---|
| G01 | PASS. Existing admitted-capture/revocation ordering remains green. A profile deactivation now wins while capture waits on the profile guard; fresh capture returns 403/no-store with unchanged Lead, activity, receipt and source-event counts. |
| G02 | PASS. Reconciles to F2/F3: lease fencing, three-claim exhaustion, extraction and completion failures, attachment race and encrypted retained truth pass. |
| G03 | PASS. A row created under V52 is upgraded to V53, replayed through `MetaLeadInboxService`, retained as `LEGACY_ENVELOPE_V1`, and marks `LEGACY_FINGERPRINT_RECONCILIATION_REQUIRED`; source identity and payload fingerprint remain unchanged. V51/V52/V53 checksums are retained below. |
| G04 | PRESERVED. Existing consent/version/encryption/masking/reveal tests pass in the genuine cohort; production files were not rewritten. |
| G05 | PASS in F1/F3 scoped backend. Closed Lead, stale title/facts, foreign City, removed publication, ownership/grant loss and shortlist/consent versions deny safely. Browser/account-switch fencing remains G10 work. |
| G06 | PASS. Current public facts and canonical locality are re-fetched during replay; stale preview returns 409 and a fresh command returns current truth. |
| G07 | PASS. Two valid concurrent updates at one Lead/shortlist version yield exactly one 200 and one 409; one ordered item set, one Lead-version advance and one activity result. The historical share remains unchanged. |
| G08 | PRESERVED. Existing lost-ack, receipt, same-key replay and share-event tests remain in the 58-test genuine cohort; no provider side effect exists. |
| G09 | PASS for the scoped source path. A forced completion update failure leaves no committed payload/result; token-fenced cleanup quarantines attempt 1 and a fresh retry on attempt 2 succeeds. |
| G10 | Backend security remains agent-tested. Connected S3–S8/S10 UI, responsive, keyboard/focus, clipboard and WhatsApp launch behavior remain NOT TESTED/BLOCKED on the approved v0 screen handoff. |

## 8. Verification executed

Compilation:

```bash
mvn -q -f backend/pom.xml -DskipTests test-compile
```

Result: PASS.

Genuine loopback disposable target:

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

Result: **58 tests, 0 failures, 0 errors, 0 skipped**.

Affected unit/Visit/notification regression cohort:

```bash
mvn -q -f backend/pom.xml \
  -Dtest=LeadSourceProcessingServiceTest,LeadShareServiceTest,LeadShortlistServiceTest,LeadContactPreferenceServiceTest,LeadDataProtectionTest,LeadServiceTest,LeadAccessServiceTest,LeadControllerTest,LeadContractTest,MetaLeadInboxServiceTest,MetaWebhookSignatureTest,OperationalSecurityGuardsTest,VisitOperationsServiceTest,TenantVisitEntitlementServiceTest,VisitSessionNotificationListenerTest test
```

Result after adding the final stale-failure witness: **99 tests, 0 failures, 0 errors, 0 skipped**.

The final genuine XML has 58 test cases. The regression directory retains all 15 affected class XML reports.

## 9. Raw evidence and negative controls

- `evidence/db-f123-correction-20261022/TEST-final-LeadHttpPostgresTest.xml`
- `evidence/db-f123-correction-20261022/regression/`
- `evidence/db-f123-correction-20261022/negative-controls/NEG-F1-no-current-fact-comparison.xml`
- `evidence/db-f123-correction-20261022/negative-controls/NEG-F2-no-exhaustion-transition.xml`
- `evidence/db-f123-correction-20261022/negative-controls/NEG-F3-no-held-claim-authority.xml`
- `evidence/db-f123-correction-20261022/FINAL_CLEANUP.txt`

Negative controls were isolated copies under `/private/tmp`; the shared worktree never entered a mutant state.

## 10. Hashes, database and preservation

Final source/test SHA-256:

```text
a2025f7c6d6da5466735ef2762f8812a3e194435005e132989e044afcfd89b4d  LeadShareService.java
f9d9ea541beb1275e5831c9c9e5e0573d1cce394777a2327e076d50893ed941b  LeadSourceProcessingService.java
d3d8df10750fc9498bb5320a56015933c3ff488e33eecd783126db3475dc8773  LeadShareServiceTest.java
2445e0e1610b9a553fed44637a37b39f5394af3116772094d1d3a4a8b4c5aeb1  LeadSourceProcessingServiceTest.java
af81e11d5d3fae83ae88e1ed90ea13d51a6ca71ea0c44c32aed71e4e86fed14d  LeadHttpPostgresTest.java
15b856ad6fe8d5f2c999617f255b546b30a457d7ac2a9270bcca7db739d640d3  V53__crm_manual_sharing_and_source_recovery.sql
722a4886f2fd125f243920da5e633bdec0726e27936187fe2112e38c3955d2ee  V52__crm_lead_foundation_and_source_inbox.sql
0d38b0db50874236b2bc254cd15cebe7d12a0de0df41419cf7455e2ab93c2c85  V51__operations_work_filters_indexes.sql
```

Final genuine XML SHA-256:

```text
2451bbde88022159ad6ab15c8b540b3a40fc1444558d87c96f81a6b55bb0741a
```

Database attestations before mutation and after the last mutation both showed:

```text
pathome|pathome_oe_test_integration|127.0.0.1/32|5432|14.14 (Homebrew)
DISPOSABLE|pathome_oe_test_integration|1.0.0|20261013T124820-98553
```

Final owned test schemas: 0. Remaining application sessions: 0. Protected databases were not contacted. No Meta/WhatsApp provider or customer message was invoked.

`git diff --check` and `git diff --cached --check` pass. The index is unchanged/empty. The inherited 92 porcelain entries remain uncommitted; no Git publication occurred.

## 11. Trackers and deadlines

The exact requested Smart Intake Approval workbooks and expected hashes
`2eaebf3a8cefd5a16e6e2495e01961f7270e13174647d1efd25b17f912a5e77b` and
`f600520dfd9114988087f9deb4c9d4151dd7deee1d5a9ba0523d76b26b3de160` were not available in the local
workspace, Documents, Downloads or Desktop search. The previously read local canonical editions had different
hashes (`0b9ed985...` and `384d41c3...`) and were not substituted. No tracker file was edited or saved.

The read-only assessment already on record remains: Cross-Role OE question #12 / worksheet row 17 is D-B-relevant and remains open pending independent acceptance; Visit Web V1 Batch D, Roadmap row 14 and Approval Gates 65–69 retain the connected UI handoff gap. D-B is not independently accepted or release-ready.

- Complete Web V1, 31 October 2026: HIGH RISK.
- Native Tenant/GE apps, 15 November 2026: AT RISK.

## 12. Verdicts and remaining boundary

- **A. F1 current share replay:** AGENT-TESTED PASS.
- **B. F2 bounded recovery:** AGENT-TESTED PASS.
- **C. F3 fresh held authority:** AGENT-TESTED PASS.
- **D. G01–G09 backend:** AGENT-TESTED COMPLETE for the corrected scoped backend. G01/G03/G07/G09 residual discriminator evidence is now explicit; G02/G05/G06/G08 reconcile to F1–F3; G04 is preserved.
- **E. R05/C05/Queue/Visit:** PRESERVED in the affected regression cohort.
- **F. Git, trackers, providers and test database:** Git/providers/test database preserved; exact new tracker files were unavailable and no substitute was used.
- **G. G10:** connected operator UI remains BLOCKED on owner-approved S3–S8/S10 design handoff. This is the remaining D-B journey dependency and maintains the stated deadline risk.

Status distinction:

- **Implemented:** yes.
- **Agent-tested:** yes, with the commands and raw XML above.
- **Independently accepted:** no; no reviewer was dispatched or substituted.
- **Release-ready:** no; G10 UI and environment/release gates remain open.

STOP.
