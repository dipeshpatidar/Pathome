# PATHOME — P1 GRANT-EXPIRY RACE CORRECTION

**Date:** 22 October 2026, IST

**Status:** Implemented, agent-verified, genuine PostgreSQL evidence captured.

**Independent acceptance:** Not claimed; the next gate is one focused GPT-6.1 Sol High reacceptance.

## 1. Repository and Starting State

- Repository: `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`
- Branch: `feature/phase2a-oe-ui-integration`
- Starting and final HEAD: `a943775e07a153087d0b38c315db20e18b30aec5`
- Remote branch SHA: the same commit
- Starting worktree: 42 modified tracked paths and 102 untracked paths, 144 total entries
- Git index: empty
- No stage, commit, push, merge, PR, rebase, reset, stash, clean, branch switch or deployment occurred

The inherited dirty worktree is the existing G10 implementation and evidence baseline. Existing
uncommitted work was preserved. This correction changed only the focused share-event authorization
path, its tests, the controlled browser assertion and this report/log.

## 2. Confirmed Defect

`LeadShareService.recordEvent()` previously performed the initial staff/relationship authorization,
then waited for shortlist/listing readiness locks, then persisted the event. A sole qualifying grant
could expire during that wait and the event would still be admitted by the old decision.

The baseline unit discriminator failed for both guarded event types with the expected
`EntityNotFoundException`: two parameterized cases wrote the event because the old service made no
post-readiness authority decision.

The genuine PostgreSQL negative control was stronger: with the final recheck temporarily removed,
the listing-lock/expiry witness returned an admitted `HANDOFF_ATTEMPTED` event response where denial
was required.

## 3. Production Implementation

Only `backend/src/main/java/com/indore/pathome/spaces/service/LeadShareService.java` changed in
production source.

After `requireFinalActionReadiness()` has completed every readiness/domain lock and before a new
event is written, `recordEvent()` now calls:

```java
authorizeCurrentRelationship(actorId, lead);
```

The helper reloads `leadAccess.context(actorId)` and reapplies
`leadAccess.requireEditable(...)`. It does not acquire additional locks. The initial
`authorize(...)` remains responsible for the existing City → Team → EmployeeProfile → Grant guard
order; the definitive checkpoint only re-decides authority from the locked current relationship.

The effective-grant native query already evaluates `effective_at`, `expires_at`, active profile and
active scope against a fresh PostgreSQL `clock_timestamp()`. No authorization helper, repository,
DTO, endpoint, migration, permission or schema change was required. `transaction_timestamp()` and JVM
time are not used for the expiry decision.

Historical event-ID replay returns before readiness and the new checkpoint, so an unchanged replay
remains read-only. A new command is admitted only when the complete current authorization remains
valid after the readiness lock footprint.

## 4. Lock and Transaction Semantics

The preserved new-command order is:

1. Authenticated actor, current Lead row and initial security guards.
2. Fresh initial effective-grant read and editable relationship decision.
3. Shortlist lock/version, current consent/destination and ordered listing locks/public facts.
4. Fresh effective-grant read and relationship decision at the definitive admission checkpoint.
5. Event row flush, activity write, transaction commit.

The checkpoint adds no profile-first locking, lock promotion, security-to-domain inversion, retry
loop or Visit Session lock. Revocation and deactivation writers remain serialized by the existing
guard rows.

An operation validly admitted before expiry retains the transaction's bounded commit semantics.
An operation whose authority is gone before the definitive checkpoint is denied by the existing
hidden 403/404 convention and the transaction rolls back.

## 5. Focused Test Additions

- `LeadShareServiceTest`
  - both guarded event types reject authority lost after initial authorization;
  - a reused event ID with changed payload remains a conflict.
- `LeadHttpPostgresTest`
  - real listing-lock wait, database-time expiry and zero event/activity/audit/receipt writes;
  - real shortlist-lock wait and expiry denial;
  - expiry after a valid admission leaves the committed boundary intact;
  - one expired grant plus one independently valid grant permits the same relationship;
  - revocation winning before admission denies with zero facts;
  - revocation after valid admission waits for the admitted transaction;
  - inactive profile or Team denies the new event;
  - synthetic activity failure rolls back the already-flushed event.
- `controlled-corrections.mjs`
  - explicit Enter-key activation now proves one event and one noopener navigation, alongside the
    existing double-click witness.

## 6. Scenario Matrix

| Scenario | Result | Evidence |
|---|---|---|
| Grant expired before initial authorization | PASS | Unit + HTTP |
| Sole grant expires during listing-lock wait | PASS | Genuine HTTP/PostgreSQL, both event types |
| Sole grant expires during shortlist-lock wait | PASS | Genuine HTTP/PostgreSQL |
| Expiry boundary reached before definitive admission | PASS | Database-time wait; expired grant rejected |
| Validly admitted operation outlives its grant | PASS | Committed event remains after expiry |
| Multiple grants, only one expires | PASS | Second effective grant admits |
| Grant revoked before admission | PASS | Real grant-lock writer wins; no event/activity |
| Grant revoked after valid admission | PASS | Revocation waits; admitted event commits |
| Profile or relevant Team inactive | PASS | New event denied; zero event |
| Lead ownership/lifecycle changed | PASS | Existing genuine readiness test |
| Consent withdrawn/disputed | PASS | Existing genuine + controlled browser |
| Destination changed | PASS | Existing genuine + controlled browser |
| Shortlist version/order changed | PASS | Existing genuine test |
| Listing unpublished/public facts changed | PASS | Existing lock witness |
| Both `HANDOFF_ATTEMPTED` and `CLIPBOARD_COPIED` | PASS | Unit + HTTP/PostgreSQL |
| Duplicate event ID, unchanged payload | PASS | HTTP + controlled browser |
| Duplicate event ID, changed payload | PASS | Focused unit conflict |
| Lost acknowledgement | PASS | Read-only history recovery; no second launch |
| Activity SQL failure | PASS | Event and activity rollback |
| Dialog cancel/reopen and account A→B→A | PASS | Controlled Chromium |
| Double click and keyboard activation | PASS | Each records once/opens once |
| Popup blocked and server/network denial | PASS | Controlled Chromium fail-closed checks |

## 7. Broken Baseline and Negative Control

Broken unit baseline:

```text
LeadShareServiceTest#finalAdmissionRejectsAuthorityLostAfterInitialAuthorization
Tests run: 2, Failures: 2, Errors: 0, Skipped: 0
Expected EntityNotFoundException to be thrown, but nothing was thrown.
```

Isolated negative control:

- Temporarily omitted only the final relationship recheck.
- Ran `grantExpiryDuringFinalListingLockWaitDeniesBothGuardedEvents` against real Spring/PostgreSQL.
- The first expired `HANDOFF_ATTEMPTED` path was admitted.
- Restored the one-line guard, recompiled, and reran the same test.
- Restored positive: 1 test, 0 failures, 0 errors, 0 skipped.
- Final source and compiled bytecode contain no bypass flag. `javap` shows
  `requireFinalActionReadiness` followed by `authorizeCurrentRelationship` before event
  persistence.

## 8. Verification Executed

| Check | Command / scope | Result |
|---|---|---|
| Unit | `mvn -q -f backend/pom.xml -Dtest=LeadShareServiceTest,OperationalSecurityGuardsTest test` | LeadShare 15/15 PASS; guards 1/1 PASS |
| Genuine HTTP/PostgreSQL | 15 focused `LeadHttpPostgresTest` methods | 15/15 PASS, 0 skipped |
| Final compile | `mvn -q -f backend/pom.xml -DskipTests test-compile` | PASS |
| Frontend build | `npm --prefix frontend-web run build` | PASS; existing >500 kB chunk warning |
| Frontend integration | `node --test operationsLeadsIntegration.test.mjs operationsWorkspaceNavigation.test.mjs` | 16/16 PASS |
| Controlled Chromium | `controlled-corrections.mjs` against production build | 49/49 PASS, 0 browser errors |
| Responsive | 320, 375, 390, 430, 768, 1024, 1440 px | No unintended overflow |
| Whitespace | `git diff --check`, `git diff --cached --check` | PASS |
| Index | `git diff --cached --name-status` | Empty |

The PostgreSQL batch includes the core expiry witness, both guarded types, shortlist and listing
waits, multi-grant union, revocation ordering, profile/Team deactivation, consent/destination,
shortlist/public-fact and lifecycle checks, idempotency/history, and transaction rollback.

## 9. Environment and Database Evidence

- Database: `pathome_oe_test_g10_20261022`
- Endpoint: `127.0.0.1:5432`
- User: `pathome`
- Run ID: `17cdb6d3-ef16-4b67-9f53-73725c859a41`
- DISPOSABLE marker rows: 1
- Flyway successful migrations: 53
- Hibernate DDL policy in tests: `none`
- Final temporary `p4_crm_http_test_*` schemas: 0
- Final lock waits: 0
- Every mutating cohort was preceded by positive target attestation

Migration checks:

| Version | Flyway checksum | Source SHA-256 |
|---|---:|---|
| V51 | 1632712085 | `0d38b0db50874236b2bc254cd15cebe7d12a0de0df41419cf7455e2ab93c2c85` |
| V52 | -2000833596 | `722a4886f2fd125f243920da5e633bdec0726e27936187fe2112e38c3955d2ee` |
| V53 | 850334017 | `15b856ad6fe8d5f2c999617f255b546b30a457d7ac2a9270bcca7db739d640d3` |

V51–V53 were unchanged. `pathome_db`, historical `pathome_oe_test_integration` and canonical
trackers were not accessed or modified.

## 10. Evidence Paths and Hashes

External evidence:
`/private/tmp/pathome-oe-g10-20261022/evidence/grant-expiry-race-20261022/`

- Target attestation: `369f12a9957731d7fe118cca2aa51c5884c816db46a000c65cb2fd039d72989f`
- Restored core positive XML: `470eed855729ff903c6f35c3e6f20dab2a2a3d3260e823776a4550973c4cb53c`
- Negative-control XML: `d821cc3b142119de4dc0ddd7e5395ee3d4a096a32f0fc9fa2547cc3adfd9c046`
- Final focused regression XML: `6817e593d408fefbc402ebb17b12b1bcef4bd77532afd7ecd471e8fce5351dba`
- LeadShare unit XML: `555a2d9630b7e92a1d7de233cd5b893a056b635ae48fe90ae57ee92ea44e6543`
- Guard unit XML: `f837b89e58f3005e72f85699a380758f230fcd47ea741653fba38b1a806299ab`
- Controlled browser JSON: `a455210fc4a9c48210c3e5bc362410939f384c2f87d354506c7e7c804a73651b`
- Controlled screenshots cover Lead capture/detail/history/handoff and responsive queue states

Source/test hashes after correction:

- `LeadShareService.java`: `c5dbec14a0a3e44a83501bcbe28dd8079f0761882b0b76ca5cdbbebc03584890`
- `LeadShareServiceTest.java`: `2d1552f31435af53b5d1c5fe7185745b6fac74c83cabbcaaad2973eb877775c8`
- `LeadHttpPostgresTest.java`: `b9424acee3181e4006b3fa4117e229c2f2528f48726c7d1db52fbc8af4f8b715`
- `controlled-corrections.mjs`: `1f3c562fc115119a9e5292b04b026536bde17f6ce54633797cc584fb62dad96d`

## 11. Files Changed

Modified:

- `backend/src/main/java/com/indore/pathome/spaces/service/LeadShareService.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadShareServiceTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadHttpPostgresTest.java`
- `frontend-web/browser-verification/operations-leads/controlled-corrections.mjs`
- `chat_gpt_changes`

Created:

- `tools/oe-integration-env/PATHOME_P1_GRANT_EXPIRY_RACE_CORRECTION_20261022.md`

## 12. Checks Not Run

- Independent GPT-6.1 Sol High reacceptance: `NOT TESTED`; not automatically dispatched.
- Physical iPhone/Safari popup behavior: `NOT TESTED`.
- Full VoiceOver/WCAG audit: `NOT TESTED`.
- Real WhatsApp/Meta delivery or external provider activation: `NOT TESTED`.
- Staging/deployment: `NOT TESTED`.
- Owner v0 visual acceptance and overall Web V1 release approval: `NOT TESTED`.
- Timezone/DST permutations: `NOT APPLICABLE`; grant expiry is an Instant/database-time boundary.
- Full historical G10 suite rerun: intentionally not repeated; the focused affected regression batch
  was used instead.

## 13. Trackers and Deadline Impact

The exact canonical workbooks were not present locally:

- `Pathome_Cross_Role_Development_Tracker_Canonical_2026-10-22_Smart_Intake_Approval.xlsx`
- `Pathome_Visit_Session_Implementation_Packages_Canonical_2026-10-22_Smart_Intake_Approval.xlsx`

No substitute or older edition was opened or edited. Tracker synchronization remains pending.

- Web V1 by 31 October 2026: **HIGH RISK**
- Native Tenant/GE by 15 November 2026: **AT RISK**

This correction does not confer G10 acceptance or Web V1 release approval.

## 14. Security, Performance and Scope Assessment

- Cross-user/City/Team isolation remains server-side and authoritative.
- No client-provided ownership or role claim is trusted.
- Sensitive responses retain `Cache-Control: no-store`.
- The extra authorization decision is two indexed reads/decisions on the already guarded actor; no
  global lock, unbounded retry or new lock wait was added.
- Admission and write are within one transaction; denial and SQL failure leave no event/activity.
- No API/DTO, permission, role, schema, migration, tracker, provider or deployment mutation occurred.
- No residual defect was found within the approved correction boundary.

## 15. Recommendation

Proceed to one focused GPT-6.1 Sol High independent reacceptance of:

- grant expiry during genuine domain-lock waits;
- fresh database-time admission;
- both guarded event types;
- revocation/deactivation ordering;
- preserved consent withdrawal, replay/idempotency and authorized handoff.

Owner visual acceptance and overall G10/Web V1 readiness remain separate gates.
