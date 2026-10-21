# PATHOME — P1 UPLOADER F1–F4 CLOSURE: IMPLEMENTATION AND VERIFICATION HANDOFF

21 October 2026 · IST

**Status:** bounded implementation complete; ready for one focused independent re-acceptance.

**Independent acceptance:** NOT CLAIMED.

**Release/deployment:** NOT APPROVED.

---

## A. Provenance

- Repository: `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`
- Branch: `feature/phase2a-oe-ui-integration`
- HEAD: `285c5844df4192bdec2c0ecc771bc9676e12dcb9`
- Remote alignment: feature-branch fetch reached `FETCH_HEAD` at the same HEAD; shared-worktree ref lock errors from another checkout/process prevented a fully clean fetch completion. `origin/feature/phase2a-oe-ui-integration` also resolved to the same HEAD. No lock file was deleted.
- Git index: empty.
- Starting worktree: inherited dirty tree with 104 tracked changes and 91 untracked entries recorded by the independent acceptance; it contained unrelated CRM/Operations work.
- Final worktree before this report/changelog: 199 status entries. The increase includes the new controlled PostgreSQL guard test and genuine-journey runner; it is not an uploader-only patch count.
- No branch switch, stage, commit, push, merge, rebase, reset, stash, clean, PR, tracker edit, deployment or protected-database mutation occurred.

### Correction-only file manifest

Production:

- `backend/src/main/java/com/indore/pathome/spaces/service/PropertyDraftService.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/FailedUploadService.java`
- `backend/src/main/java/com/indore/pathome/spaces/repository/MediaUploadFailureRepository.java`
- `backend/src/main/java/com/indore/pathome/spaces/repository/PropertyUploadDraftRepository.java`
- `backend/src/main/java/com/indore/pathome/spaces/controller/PropertyController.java`
- `backend/src/main/java/com/indore/pathome/spaces/controller/FailedUploadsController.java`
- `frontend-web/src/hooks/usePropertyDraft.ts`
- `frontend-web/src/components/MasterAdminDashboard.tsx`
- `frontend-web/src/components/BatchPropertyIngestionStudio.tsx`
- `frontend-web/src/services/draftService.ts`

Tests and evidence harness:

- `backend/src/test/java/com/indore/pathome/spaces/service/PropertyDraftServiceTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/FailedUploadServiceTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/controller/PropertyControllerTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/controller/MediaUploadClaimConcurrencyTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/controller/BackOfficePropertyAuthorizationHttpPostgresTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/controller/UploaderMediaClosureHttpPostgresTest.java` (new)
- `frontend-web/browser-verification/property-uploader-session/run.mjs`
- `tools/oe-integration-env/phase2a/uploader-closure.mjs` (new)

Documentation:

- This report.
- `chat_gpt_changes` append-only entry.

### Current SHA-256 hashes

| File | SHA-256 |
|---|---|
| `PropertyDraftService.java` | `f084758731da1c9f01065cb0c3cf73d32810ecf08604b2636f7142bcf8cb5e68` |
| `MediaUploadFailureRepository.java` | `582ddf31c456ff4e937380b46ec89daefffc8d7f4230540e49c7ac5485de6fec` |
| `PropertyUploadDraftRepository.java` | `39d92505677e033baab60d1148cb773854dba1e6a00c9d49f021c8581b019402` |
| `FailedUploadService.java` | `e70fee62d58c8366501a5ef5d3ee18dfae66be4186a968d020babcb92f87c267` |
| `PropertyController.java` | `aba9d090f60e54e9cb7ac4f654e18106f28b30fa7622c5cdc889a859b15e2641` |
| `FailedUploadsController.java` | `a33e622e4b80b1ec2397491e35182588add1e45b0e4bf6477e36b1e08bc39370` |
| `PropertyDraftServiceTest.java` | `9a442ef3fd5ec2d0f720601e5f0db8662d718117c5705e9b64e6a4d5238babec` |
| `FailedUploadServiceTest.java` | `167ddc685a695217e7cef060d7403506b1fc3492fadf7e1af85f7a21bbe2a755` |
| `PropertyControllerTest.java` | `e320cf7fecd9d6d9c49ee66345eaab4c3aed25c94890f7591b44e830ec3df5e4` |
| `BackOfficePropertyAuthorizationHttpPostgresTest.java` | `bbac4f84f417ae228e48b13d23ce78c26370a68e80c403e6a713bb009c98fec4` |
| `MediaUploadClaimConcurrencyTest.java` | `f2bd520adfaf6ecec735f3d9313bc611da803f66659107fefac82a05e21740a5` |
| `UploaderMediaClosureHttpPostgresTest.java` | `b86cf5d5deebeeec22ea08c91f8f7353ab2de925c4788cac7deac275d98b12bc` |
| `usePropertyDraft.ts` | `c7d72fbdf0cc78ea344b7e4d411342dc65f8fa79ba701aff633a388ce90ad788` |
| `MasterAdminDashboard.tsx` | `ca1bdd5cf3dd3ce6852a355e72e1bdab1dc47be3e5a32ad2a034e81c1a99c9a1` |
| `BatchPropertyIngestionStudio.tsx` | `bbdeca48a8c9500d84b51fb635d1ce9129adabe1d0c00bb2a91c877a2de08123` |
| `draftService.ts` | `429fb9fd1503e226f4ddd48cc7acaf211de71ca649ccdfdae0532e2ca1e7b285` |
| session browser runner | `b9a89bdff330d386a98c449a0cae95cc7d85d2e04a326fd7ed8951515a45053a` |
| genuine journey runner | `2fd3c2b171ff0860471f4d75b2e3a426849b2a7d7a8d48db396d023fbebd2d70` |

Starting per-file hashes were not captured before this implementation began. This is a provenance limitation; current hashes above are exact, and the inherited worktree was preserved.

---

## B. Exact implementation

### F1 — first-time SINGLE/BATCH draft creation

- Replaced existing-resource admission with separate admission for creation and ownership.
- Binds the persisted owner key to the authenticated canonical User identity.
- Validates ID and payload before storage access.
- Serializes first-time creation for the same draft ID with a transaction-scoped PostgreSQL advisory lock.
- Keeps the database uniqueness constraint as a second guard.
- If another actor wins, returns privacy-safe 404; if the same actor encounters a concurrent create, returns existing 409 conflict semantics. The winner is never overwritten.
- Existing updates use a pessimistic draft row lock, owner comparison, version/lifecycle checks and the existing 409 behavior.
- `PUBLISHING`, `PUBLISHED`, or an already-linked publication tombstone cannot be overwritten by a late autosave.
- GET/resume and DELETE/discard now admit the current actor first and return generic 404 for missing/foreign resources.
- Null/malformed existing owner data is compared through the existing safe normalization rather than dereferenced directly.

### F2 — recovery authority held through commit

- `markRetrying` holds fresh profile → global grant → User evidence before the conditional recovery-row transition.
- `dismiss` holds the same evidence, locks the failure row, then mutates state.
- `recordResolution` holds associated-media authority evidence, locks the failure row, verifies the listing relationship, then mutates state.
- Sub-admin recovery remains limited to associated media; global failure administration remains Admin-only.
- Trusted internal failure recording, retry accounting and cleanup remain independent of a human staff credential.
- Staged-object deletion is registered after transaction commit; provider I/O is not performed while the recovery security transaction remains open.
- Real PostgreSQL lock compatibility was exercised against the supported global-grant revocation update.

### F3 — terminal media denial

- `AccessDeniedException` is explicitly propagated before every generic persistence/recovery catch.
- Existing-asset replay reauthorizes immediately before returning private asset data.
- Claim wait re-fetches the current listing and reauthorizes before an already-complete asset can be replayed.
- Post-transfer attachment reauthorizes both before and inside the short listing mutation transaction.
- Winning-asset reconciliation runs only for genuine concurrency/integrity failures and only after a fresh authorization check.
- Denial marks the upload claim failed for cleanup but does not create `DB_PERSIST` failure, return 201, return asset identity, or start a retry loop.
- Failed-upload automatic/replace/reconcile paths propagate denial instead of wrapping it as a generic 500/502/recovery error.

### F4 — credential-generation ownership

- The uploader now treats same-User token rotation as a new active generation: old timers/pending payloads are retired, then autosave is reactivated for the replacement credential.
- Old finalizers only reset save-busy state when their exact captured generation remains active.
- Editor, busy, loading, notices, local buffers, progress, previews, publication, reconciliation and follow-up actions are fenced by the captured session.
- Old success, 401, 403, network failure and finalizer completions cannot mutate the replacement session.
- Terminal current-session denial withdraws editor state and asynchronously removes the protected local draft and active pointer.
- Single and batch parent components reset stale generation-owned busy state and revoke old protected previews when a valid replacement generation begins.
- Publish/reconcile calls now receive the original captured session rather than silently acquiring a newer one.
- Parallel media restore progress and upload progress callbacks are suppressed after session replacement.
- Shared `operationsSession.ts` generic semantics were not changed.

No schema migration, API/DTO change, dependency, permission model, role policy, parser behavior, provider activation or visual redesign was introduced.

---

## C. Test proof

### Discriminating baseline before correction

The pre-correction focused run was red for the intended seams:

- F1 fresh draft positive could not reach creation; the guarded service returned before a new row could be saved.
- F2 `holdEvidenceForCurrentMutation` was not invoked by retry/dismiss/resolution.
- F3 production-controller test reproduced denial → winning-asset replay (`Expected AccessDeniedException, nothing was thrown`).
- F4 mounted Chromium reproduced `new generation autosave uses the rotated credential` as FAIL while old-generation text remained held.

These are baseline red-before/green-after discriminators, not post-fix synthetic failures.

### Focused Java regression cohort

Command:

```bash
PATHOME_VISIT_OPERATIONS_POSTGRES_TEST=true \
PATHOME_OE_TEST_ALLOW_DISPOSABLE=true \
PATHOME_OE_TEST_DB=pathome_oe_test_integration \
PATHOME_OE_TEST_DB_HOST=localhost \
PATHOME_OE_TEST_DB_PORT=5432 \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/pathome_oe_test_integration \
SPRING_DATASOURCE_USERNAME=pathome \
SPRING_DATASOURCE_PASSWORD= \
mvn -q -f backend/pom.xml -Dtest=PropertyDraftServiceTest,FailedUploadServiceTest,PropertyControllerTest,MediaUploadClaimConcurrencyTest,BatchPropertyPublishingServiceTest,FailedUploadsControllerTest,BackOfficePropertyAuthorizationHttpPostgresTest,UploaderMediaClosureHttpPostgresTest test
```

Result: **161 tests, 0 failures, 0 errors, 0 skipped.**

Class results:

- `PropertyDraftServiceTest`: 52/52
- `FailedUploadServiceTest`: 17/17
- `PropertyControllerTest`: 52/52
- `MediaUploadClaimConcurrencyTest`: 12/12
- `BatchPropertyPublishingServiceTest`: 2/2
- `FailedUploadsControllerTest`: 16/16
- `BackOfficePropertyAuthorizationHttpPostgresTest`: 7/7 genuine HTTP/PostgreSQL
- `UploaderMediaClosureHttpPostgresTest`: 3/3 genuine HTTP/PostgreSQL with controlled provider seam

### F1 genuine HTTP/PostgreSQL proof

- Fresh text-only SINGLE and BATCH drafts passed for active Admin and active Sub-admin.
- Each passed create → resume → update → version 1→2 progression.
- Foreign existing draft GET and DELETE return 404; missing GET returns 404; row remains unchanged.
- Concurrent first save with the same draft ID by two actors produced exactly one 200 and one privacy-safe 404, one row, no duplicate and no owner switch.
- Stale version, published/publishing restrictions and foreign owner behavior are covered by focused unit tests.

### F2 real transaction proof

- Unit order witnesses prove human recovery transitions invoke transaction-held evidence before mutation.
- PostgreSQL lock test holds profile, grant and User evidence in the supported order; a supported grant-revocation update remains blocked until evidence releases.
- Active `pg_stat_activity ... wait_event_type='Lock'` is asserted while the revocation is held.
- Revocation committed before recovery admission denies dismissal with zero state mutation.
- Internal system recording remains outside the human recovery guard.

### F3 media proof

- Controlled synthetic transfer completed, authority was revoked before attachment, and the response remained terminal 403.
- No new media asset was persisted and no private URL appeared in the rejection body.
- Adding a synthetic winning asset and replaying did not bypass current authorization.
- Claim-wait positive reauthorizes before private replay.
- Claim-wait denial is terminal and never calls the provider.

### F4 controlled Chromium proof

Command:

```bash
node frontend-web/browser-verification/property-uploader-session/run.mjs
```

Result: **47 checks, 0 failures**, within the 8-minute budget.

Covered:

- same-User token1 → token2; token2 autosave dispatched;
- old delayed 200;
- old 401;
- old 403;
- old network failure;
- old finalizer while a new save is in flight;
- account A → B withdrawal;
- protected preview withdrawal;
- all nine required widths from 320 through 1440 without overflow.

### M21 — real revocation between bulk cards

The genuine per-card service was spied only to place a barrier in `afterCommit` after the first `REQUIRES_NEW` card committed. A supported global grant revocation committed before the controller resumed.

Result:

- first card persisted;
- later card denied;
- response `total=2`, `successCount=1`, `failedCount=1`;
- exactly one `origin_draft_id` row;
- no second-card persistence;
- partial-success contract preserved.

### M23 — controlled transfer/revocation

Passed as described under F3. No real provider request occurred. Live Cloudinary/B2 integration remains separately BLOCKED.

### M31 — genuine synthetic React → Spring Boot → PostgreSQL

Command:

```bash
node tools/oe-integration-env/phase2a/uploader-closure.mjs
```

Target attestation: loopback `pathome_oe_test_integration`, PostgreSQL 14.14, `pathome` role, `DISPOSABLE` sentinel count 1, run-owned isolated schema.

Result: **all 12 genuine journey checks passed**:

- real password registration;
- guarded synthetic Admin role/profile/global grant provisioning;
- real browser password login;
- built React mounted;
- fresh text-only SINGLE draft;
- PostgreSQL resume;
- reviewed single create;
- fresh text-only BATCH draft;
- real mixed batch: one success/one failure;
- current grant revocation;
- retained-token create denied 403 with unchanged listing count;
- persisted listing/draft facts verified.

Observed persisted result: single listing `ACTIVE`, batch listing count `1`, single draft status `PUBLISHED`.

Evidence: `/private/tmp/pathome-uploader-closure-20261021072724-f68c40f7/uploader-closure.json`. Synthetic token is redacted. The run-owned schema was dropped.

### Frontend regression cohort

Command:

```bash
cd frontend-web && node --test src/tests/*.test.mjs
```

Result: **1,010 tests, 1,006 pass, 0 fail, 4 existing TODO.**

Build:

```bash
npm --prefix frontend-web run build
```

Result: PASS, 2,047 modules; existing large-chunk advisory only.

Backend compile:

```bash
mvn -q -f backend/pom.xml -DskipTests test-compile
```

Result: PASS.

Hygiene:

- `git diff --check`: PASS
- `git diff --cached --check`: PASS
- index remains empty.

### Negative controls

The original failing baselines above reproduce the production defects and pass after correction. Separate post-fix source-mutation negative controls for all five requested bypasses were **NOT TESTED** in this pass. They remain an independent-review item; this report does not claim them.

---

## D. Existing functionality preservation

- Active Admin positive single parse/create: genuine synthetic PASS.
- Active Admin fresh SINGLE/BATCH drafts: genuine HTTP/PostgreSQL and synthetic React journey PASS.
- Active Sub-admin fresh SINGLE/BATCH drafts: genuine HTTP/PostgreSQL PASS.
- Existing own draft update/version progression: PASS.
- Foreign/missing draft privacy: PASS at HTTP boundary.
- Narrow Sub-admin global/manual exclusion: existing focused HTTP cohort PASS.
- Current grant revocation: retained-token single and media creation denied.
- Media idempotency/claim concurrency: focused class PASS.
- Single/bulk partial result: real mixed batch and M21 PASS.
- Recovery retry/dismiss/resolve authority: focused tests PASS.
- Same-account and cross-account session isolation: controlled Chromium PASS.
- Operations shared-session semantics: source dependency preserved.
- Existing responsive uploader composition: nine widths, no overflow; no visual redesign.
- Lessor/review/CRM files were not rewritten by this closure.

---

## E. M01–M34 disposition

Evidence levels are explicit; aggregate counts are not converted into broader PASS claims.

| Row | Status | Current evidence / limit |
|---|---|---|
| M01 | PASS | Genuine Admin single plus fresh drafts; genuine batch through real endpoint; bounded, not every historical UI permutation. |
| M02 | PASS | Inactive/demoted/revoked retained-token denial plus real recovery revocation/write evidence. |
| M03 | PASS | Active Sub-admin narrow allowlist; global/manual denied. |
| M04 | PASS | Fresh Sub-admin SINGLE/BATCH and own-resource actions; no configurable per-user catalog was introduced. |
| M05 | PASS | DB role/profile/global grant and DB-time authority exercised. |
| M06 | NOT TESTED | Review authority unaffected but not rerun in this closure cohort. |
| M07 | NOT TESTED | Parse → revocation → create ordering was not separately interleaved. |
| M08 | PASS | Controlled pending account switch/logout-family and browser generation checks; exact post-create media permutations not exhaustive. |
| M09 | PASS | Controlled late success/401/403/network cannot enter another account or generation. |
| M10 | PASS | Same-User rotation, old 200/401/403/network and finalizer checks. |
| M11 | PASS | Fresh SINGLE/BATCH create/resume/update/version. |
| M12 | PASS | Foreign and missing draft privacy at HTTP; owner-before-replay source/test proof. |
| M13 | PASS | Foreign batch preflight and real mixed batch; later-card revocation covered by M21. |
| M14 | NOT TESTED | Complete malformed/oversized/deleted-User real HTTP matrix. |
| M15 | PASS | Revocation wins before media attachment and recovery admission. |
| M16 | NOT TESTED | Full attachment-mutation-wins writer wait proof is not claimed; lock compatibility is separately exercised. |
| M17 | NOT TESTED | All supported governance writers/role/profile/grant cycles were not exhausted. |
| M18 | PASS | Direct draft/recovery/media service boundaries corrected and tested. |
| M19 | NOT TESTED | Full parser/quarantine/empty/oversized matrix not reopened. |
| M20 | PASS | Real mixed batch and between-card partial result; all-or-nothing behavior not introduced. |
| M21 | PASS | Two-card committed barrier plus supported revocation and DB row count. |
| M22 | PASS | Media claim/replay/idempotency and denial branches; complete lost-acknowledgement matrix not claimed. |
| M23 | PASS | Controlled transfer/revocation with synthetic provider; live provider BLOCKED. |
| M24 | NOT TESTED | Previously accepted Lessor backend tests were not rerun and no Lessor service was changed. |
| M25 | NOT TESTED | Review lifecycle/publication suite not rerun. |
| M26 | NOT TESTED | Legacy review mutation cohort not rerun. |
| M27 | NOT TESTED | Complete audit/rollback matrix not rerun. |
| M28 | PASS | Generic 404 draft privacy and terminal 403 media/recovery behavior. |
| M29 | PASS | Controlled browser: 401/403 are not offline; stale results cannot erase new work. |
| M30 | PASS | no-store retained; controlled protected preview withdrawal. Full back-cache/refresh combinations not claimed. |
| M31 | PASS | Genuine synthetic React → Spring → PostgreSQL journey, 12/12. |
| M32 | PASS | 320–1440 no-overflow; no new visual layout. Full WCAG/keyboard audit not rerun. |
| M33 | NOT TESTED / BLOCKED | Physical iPhone Safari, VoiceOver, staging, rollback and operational recovery remain separate gates. |
| M34 | NOT APPLICABLE | No visit OTP, entitlement, booking, GE collision or scheduling behavior was changed. |

---

## Trackers and deadline

Read-only canonical assessment:

- Cross-Role tracker: `44c9dc7af26818e1bd35249eaf8f3842f28a2d68cf5975c9db1c9e3c6b7bab17`
- Visit Session tracker: `0e324e11bf49738a4d011d354281b42e1b018febe2ae27def95e1f0ffacb7d96`

Both fingerprints still match the supplied 19 October authoritative exports. No newer canonical workbook was verified. Neither workbook nor its Change Log was edited.

DEP-004–007/020/031/050/051/054 retain their prior partial/planned/frozen meanings. This correction proves a bounded implementation of the existing P1/P2-amended/P3/P4 policy; it does not complete the planned configurable Sub-admin permission model (DEP-031) or future owner-governance architecture (DEP-054).

Batch D/CRM/V52 remains a separate workstream. No acceptance or release status is inferred from this uploader evidence.

Deadlines remain:

- **31 October 2026 — Complete Web V1: HIGH RISK**
- **15 November 2026 — Native Tenant and GE apps: AT RISK**

---

## Remaining gates

1. One focused independent GPT-6.1 Sol High re-acceptance of F1–F4, M21/M23/M31 and affected regressions.
2. Separate post-fix source-mutation negative controls, if required by acceptance.
3. Any real owner-account eligibility evidence before production activation. No real owner account was read or changed.
4. Live media-provider integration is separately BLOCKED; no external provider was contacted.
5. Physical-device Safari/VoiceOver, full WCAG/zoom, staging, rollback, observability and operational recovery.
6. Canonical tracker synchronization requires separate owner authorization.
7. No commit/push/deployment is authorized by this handoff.

STOP for focused independent re-acceptance.
