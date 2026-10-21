# PATHOME — P1 UPLOADER CONSOLIDATED FINAL CLOSURE: IMPLEMENTATION AND EVIDENCE HANDOFF

21 October 2026 · IST

**Status:** approved correction and evidence package complete.

**Independent acceptance:** NOT CLAIMED. The next gate is one focused GPT-6.1 Sol High review.

**Release/deployment:** NOT APPROVED.

---

## 1. Approval mapping

The implementation stayed inside the owner-approved P1, amended P2, P3 and P4 boundaries:

- Current canonical User, persisted role, active EmployeeProfile, effective Staff grant and existing record relationship remain authoritative.
- Narrow Sub-admin uploader/media behavior is unchanged.
- Staff Admin listing-review authority is unchanged.
- No permission, role, API/DTO, schema, migration, parser, provider or visual-design change was introduced.
- The only production backend behavioral change moves the post-transfer media authority check into the existing terminal claim-failure handler.
- Frontend changes implement terminal withdrawal, generation fencing, local cleanup and malformed restored-card normalization without redesigning the uploader.

No frozen policy or tracker workbook was modified.

---

## 2. Root-cause closure

The repeated acceptance failures shared one product-level defect class: protected state was tied to a component callback chain rather than a terminal, generation-owned withdrawal path.

Specific causes closed:

1. Single and batch media restore callbacks swallowed a current-session 401/403, so successfully loaded draft text/cards remained visible.
2. `restoreMediaFiles` had no restore-local terminal state. Workers kept claiming media and emitting progress after one sibling received terminal denial.
3. A delayed local draft write could outlive denial and put the protected record back.
4. Current-session 401 had already invalidated the shared generation before the uploader catch ran, making an active-only check unable to distinguish it from a stale denial.
5. Nested 409 reconciliation swallowed its recovery GET denial.
6. Batch restoration assumed every persisted optional array existed, so a partial payload could crash React before the permission state rendered.
7. Post-transfer media authority denial occurred before the existing claim-failure handler, leaving the owned claim `IN_PROGRESS`.

The closure centralizes current-credential denial withdrawal in `usePropertyDraft`, adds a restore-local abort fence, and makes local persistence commit behind the captured credential.

---

## 3. Provenance and source manifest

- Repository: `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`
- Branch: `feature/phase2a-oe-ui-integration`
- HEAD: `285c5844df4192bdec2c0ecc771bc9676e12dcb9`
- Initial/final worktree: 201 → 204 status entries; inherited dirty work and this package's evidence/report remain preserved. Index: empty.
- No branch switch, stage, commit, push, merge, rebase, reset, stash, clean, PR, deployment or protected-database access occurred.
- Pre-edit manifest: `tools/oe-integration-env/evidence/p1-uploader-final-closure-20261021/source-manifest-before.sha256`
- Final manifest: `tools/oe-integration-env/evidence/p1-uploader-final-closure-20261021/source-manifest-after.sha256`

Production files changed:

| File | Final SHA-256 |
|---|---|
| `frontend-web/src/hooks/usePropertyDraft.ts` | `d45c03aa74065b740f2f8bf11e22a65dcd51d4d8ab275dffffc9c876961cdf75` |
| `frontend-web/src/components/MasterAdminDashboard.tsx` | `18eb443b201f18ea93652911b1e70227215d3a64341d14f8a7e46594a22eb98c` |
| `frontend-web/src/components/BatchPropertyIngestionStudio.tsx` | `28fbdc0eba34d6864a86122685e2064a79324c4cedcd2112eeb01982bc76cdb9` |
| `frontend-web/src/services/draftService.ts` | `0dd7ea9adf29a54738b2407ad9027aa1b7d6195c76cc826fc4720f567ca01367` |
| `frontend-web/src/services/localDraftStorage.ts` | `548162dbb288678a61956b89559707a6105301fe29614f3807545f0039f4b430` |
| `frontend-web/src/utils/uploaderSession.ts` | `4cb3a6b2f184afb2e3a62c3986345927c0709238da84768fcf458dc0a7f4513f` |
| `backend/src/main/java/com/indore/pathome/spaces/controller/PropertyController.java` | `518482347b469e62078554cd3edd1c33f01d7ab6af466aca13ec6d05d7b20922` |

Test/harness files changed or added:

| File | Final SHA-256 |
|---|---|
| `frontend-web/src/tests/propertyUploaderSessionIsolation.test.mjs` | `a2a5663b03f7f502a69bbc651dbcf298d9a2b9f0885ab2dd89b43a834bc90e23` |
| `frontend-web/src/tests/uploaderRestoreTerminalDenial.test.mjs` | `28200532da3b675af36b13c3461db76af455a23827e74203ed828b4a21806748` |
| `frontend-web/browser-verification/property-uploader-session/run.mjs` | `1306331fd56a586e088f840e3c49d0471221d044bd92d7747b7da66aac2d19e4` |
| `backend/src/test/java/com/indore/pathome/spaces/controller/UploaderMediaClosureHttpPostgresTest.java` | `936e5fe48e04fe8f9ed5513fdda3335e9c49109f68e436bd2268bc36c359eac5` |
| `backend/src/test/java/com/indore/pathome/spaces/controller/BackOfficePropertyAuthorizationHttpPostgresTest.java` | `b8b4dab68376e73d654236b694f5160fcef3f1c6676d43f57742fef89a420b1a` |
| `tools/oe-integration-env/phase2a/uploader-closure.mjs` | `40822100ba2ab1888e6f84467a82cb9f9c4c5a3b5845e0d632a2ddc27e2e7bb6` |

`FailedUploadService.java` and `BatchPropertyPublishingService.java` were tested and preserved without source mutation:

- `FailedUploadService.java`: `e70fee62d58c8366501a5ef5d3ee18dfae66be4186a968d020babcb92f87c267`
- `BatchPropertyPublishingService.java`: `e85a4d216781d8c5cc712f26119c8a5ca8e8f61002a57431050c4b78e145de5a`

---

## 4. A1–A7: terminal denial and session ownership

| Row | Event order / invariant | Evidence | Result |
|---|---|---|---|
| A1 | Saved SINGLE/BATCH detail renders, then private media returns current 401/403; text/cards/media/pointer/local record must withdraw and permission state appear | Actual built React, IndexedDB/localStorage, held media denial; `.mjs` runner `C403`, `C401`, `C-BATCH` | PASS |
| A2 | Parallel restore: first terminal denial stops new claims, later callbacks and successful sibling commits | Production `restoreMediaFiles`, controlled transport, 0/1/3/6 files; terminal-flag mutant fails | PASS |
| A3 | List/save/restore denial clears editor, previews, local record and matching pointer; stale replacement generation is preserved | Actual browser pointer/record assertions; commit guard in `localDraftStorage.save`; cross-tab generation checks | PASS |
| A4 | Nested 409 recovery GET denial must not become conflict/offline success | Mounted autosave 409 → recovery GET 403 scenario | PASS |
| A5 | Real second-tab account replacement withdraws old work; old payload never sends with new credential; new generation saves | Two real tabs in one browser context, storage events, held/mounted state | PASS |
| A6 | Terminal denial and stale-session families never publish misleading recovery state; ordinary transport/media partial recovery remains available | Mounted stale 200/401/403/network/finalizer cases; service transport failure preserves 5 of 6 files | PASS |
| A7 | Admin/Sub-admin own draft and create behavior, narrow denial and shared Operations semantics remain intact | 169-test focused Java cohort, mounted Chromium, retained Operations suite | PASS |

Further checks covered old object URLs, media-progress callbacks, busy/finalizer ownership, active pointer/version/conflict refs, remount storage truth, cross-account data and cleanup generation fencing.

Visible controlled-browser evidence:

- `tools/oe-integration-env/evidence/p1-uploader-final-closure-20261021/controlled-browser.log`
- 80 checks, 0 failures, within the 10-minute budget.

The browser pass validates responsive composition at 320, 375, 390, 414, 430, 768, 1024, 1280 and 1440 px with no horizontal overflow. It does not claim full WCAG or physical-device acceptance.

---

## 5. B1–B5: recovery authority through commit

| Row | Evidence | Result |
|---|---|---|
| B1 mutation-first | Actual proxied `markRetrying`, `dismiss`, `recordResolution`; test-only DB pause after profile → grant → User evidence; actual `StaffAdministrationService.revokeGrant` waits; specific PIDs and `pg_blocking_pids` asserted; mutation and revocation then commit | PASS |
| B2 revoke-first | Actual governed grant revocation, profile deactivation and labelled synthetic persisted-role demotion commit before recovery; all three protected commands deny without row mutation | PASS |
| B3 integrity | Retry/dismiss/resolution terminal and null/relationship behavior; atomic retry condition; associated Sub-admin vs global recovery constraints; after-commit cleanup unit evidence | PASS, representative matrix |
| B4 provider separation | `markRetrying` commits, controlled provider transfer is held, grant revocation commits, completion returns 403; failure remains truthfully `RETRYING`, no asset/gallery/false resolution | PASS |
| B5 writer order | Real supported global-admin grant revoke/deactivate with two Admins; no final-Admin masking; no reproducible frozen governance deadlock | PASS |

Evidence:

- `uploader-media-closure-postgres.log`
- `final-java-cohort.log`
- Recovery test `actualRecoveryTransactionsSerializeWithSupportedGrantRevocationUntilCommit`
- `recoveryClaimCommitIsTruthfulButPostProviderRevocationBlocksCompletion`

No recovery production rewrite was made.

---

## 6. C1–C3: claim, gallery and cleanup truth

The test-first post-transfer check reproduced `claim=IN_PROGRESS` after a 403. The smallest correction moved the authority recheck inside the existing terminal handler.

Verified after denial:

- HTTP 403 with no private media URL.
- Claim status `FAILED` with `Authorization denied`.
- No new property media asset.
- No gallery mutation.
- No fabricated `DB_PERSIST` failure.
- No provider replay or false 201.

Mutation-first ordering additionally proved:

- Authority evidence held before attachment.
- Governance revocation waited on the attachment transaction.
- One authorized asset and one gallery URL committed.
- Claim then became `COMPLETED`.
- A later retained-token request denied and created no duplicate.

Winner/no-winner, claim-wait, guarded attachment, replay and replacement/recovery positives remain covered by the focused controller/claim suites. Live Cloudinary/B2 traffic was not used and remains a separate release gate.

---

## 7. Isolated post-fix negative controls

Each control ran in an owned copy outside the shared worktree, one mutation at a time. The original worktree was never mutated for a control.

| Control | Mutant | Intended failing evidence | Result |
|---|---|---|---|
| N1 | Removed fresh-draft creation result | Fresh SINGLE/BATCH lifecycle assertion failed (`version=0`) | PASS as control |
| N2 | Removed all three recovery evidence holds | Actual revoker no longer waited; exact PID/order assertion failed | PASS as control |
| N3 | Restored denial-to-winning-asset replay | Terminal-denial test failed because no exception was thrown | PASS as control |
| N4a | Swallowed single-draft media restoration denial | Mounted pointer, record, Saved-state and permission assertions failed | PASS as control |
| N4b | Removed restore-local terminal flag | Controlled restore no longer rejected after denial | PASS as control |
| N5 | Removed per-card service authority guard | Parked in-card request committed the forbidden first property | PASS as control |

An initial N1 edit was rejected because it produced an unreachable statement and therefore compilation failure. It was not counted. A replacement N1 mutant compiled and failed only the intended guarded assertion.

Raw evidence:

- `N1-intended-failure.log` through `N5-intended-failure.log`
- Per-control before/mutant SHA-256 files
- `negative-control-restoration-check.txt`
- `mutant-cleanup.txt`

All original-source restoration hashes match. The mutant workspace was moved to Trash after evidence capture.

---

## 8. M21 and M23

M21:

- Revocation after the first committed card preserves prior success, blocks later cards and keeps partial-result semantics.
- Revocation before the actual per-card service guard produces `successCount=0`, `failedCount=2` and zero listings.
- Revocation after the last committed card preserves two truthful successes and prevents post-denial draft reconciliation.
- `REQUIRES_NEW` and partial-success behavior remain intact.

M23:

- Controlled transfer + revocation yields terminal 403, no asset, no private URL.
- Mutation-first yields one authorized asset, one gallery URL, claim `COMPLETED`, then denial on the next request.
- Post-transfer denial closes claim ownership as `FAILED` without creating a false persistence failure.

---

## 9. M31 genuine React → Spring Boot → PostgreSQL

The runner now calls the project database guard before schema creation, records the live disposable identity and sentinel, binds source/build hashes, uses the built React app, real authentication/HTTP/PostgreSQL, and drops its owned schema.

Journey:

1. Register and authenticate a synthetic Admin.
2. Provision synthetic role/profile/global grant in the disposable schema.
3. Create/resume a fresh SINGLE text draft.
4. Parse, review and create a real single property.
5. Enter bulk text through the actual React uploader.
6. Parse two cards in the actual bulk editor.
7. Manually edit a monthly rent.
8. Confirm both cards.
9. Submit a mixed-validity batch through React; one card succeeds and one server-validated card fails truthfully.
10. Revoke authority.
11. Observe browser permission denial and direct retained-token HTTP denial.
12. Read back persisted listings/draft state and clean the owned schema.

Result: 20/20 genuine assertions passed.

Raw evidence:

- `genuine-journey.log`
- `genuine-journey.json`
- `genuine-target-attestation-before.json`
- `genuine-target-attestation-after.json`

Final run schema: `p1_uploader_e2e_202610211325333d65981d`. Post-run owned uploader-schema count: 0.

No real provider, protected database, owner account or real listing was touched.

---

## 10. Regressions and verification

Final affected Java cohort:

- 169 tests, 0 failures, 0 errors, 0 skipped.
- Includes 8 real PostgreSQL/media/recovery HTTP tests and 10 real back-office HTTP/PostgreSQL tests.

Focused frontend session/service tests:

- 13 tests, 13 pass.
- Covers 0/1/3/6 restore items, 401 and 403, callbacks, order/tags/cover, partial transport recovery and replacement credential.

Mounted Chromium:

- 80 checks, 80 pass.

Genuine integration:

- 20 assertions, 20 pass.

Build/hygiene:

- `mvn -q -f backend/pom.xml -DskipTests test-compile`: PASS.
- `npm --prefix frontend-web run build`: PASS, 2,047 modules; existing large-chunk advisory only.
- `git diff --check`: PASS.
- Git index remains empty.

Raw logs are under `tools/oe-integration-env/evidence/p1-uploader-final-closure-20261021/`.

Checks not run or not claimed:

- Live Cloudinary/B2 transfer: NOT TESTED / BLOCKED release gate.
- Physical iPhone Safari, VoiceOver, full keyboard/touch/WCAG matrix: NOT TESTED.
- Real business-owner production provisioning/eligibility: NOT TESTED.
- Staging, deployment, rollback and operational observability: NOT TESTED.
- A complete every-state cross-product of all possible transport/provider failures: NOT TESTED; focused discriminating classes were executed.

---

## 11. Original M01–M34 reconciliation

| Original meaning | Status | Evidence boundary |
|---|---|---|
| M01 Active Admin positive | PASS | Real HTTP/PostgreSQL and React single/bulk |
| M02 Profile deactivation | PASS | Actual governed deactivation then denial/no mutation |
| M03 Persisted role demotion | PASS | Labelled synthetic persisted demotion then denial/no mutation |
| M04 Amended Sub-admin allowlist | PASS | Own draft/uploader positives and global/manual denial |
| M05 Admin global-grant changes | PASS | Governed revoke + writer/claim/media ordering |
| M06 Review-only Staff Admin isolation | PASS | Retained unchanged review evidence |
| M07 Parse → revocation → create interleave | NOT TESTED | Separate pre-reviewed DTO interleave not rerun |
| M08 Sign-out with pending save | PASS | Controlled mounted pending cancellation |
| M09 Old responses against another User | PASS | Mounted held success/denial and real cross-tab replacement |
| M10 Same-User new credential | PASS | Old 200/401/403/network/finalizer fencing |
| M11 Own draft create/version/resume | PASS | Fresh single/batch, same/different actor races |
| M12 Foreign draft privacy | PASS | Real HTTP 404 and ownership checks |
| M13 Foreign batch preflight | PASS | Real guarded mixed/foreign batch tests |
| M14 Malformed/null/deleted-user matrix | NOT TESTED | Full cross-product not rerun |
| M15 Revocation before media attachment | PASS | Controlled transfer/revocation |
| M16 Attachment mutation wins | PASS | Evidence-held attachment; revoker wait; one truthful commit |
| M17 All governance writer interleavings | PASS, bounded | Supported grant/profile paths proven; unrelated scope writers not exercised |
| M18 Direct service authority boundaries | PASS | Recovery and per-card service witnesses |
| M19 Full parser quarantine matrix | NOT TESTED | Parser behavior unchanged |
| M20 Partial bulk results | PASS | Real mixed batch and M21 neighbors |
| M21 Between-card authority | PASS | Before-first, between, after-last and final reconciliation |
| M22 Media claim/idempotency | PASS | Claim wait/replay/winner/no-winner focused suites |
| M23 Controlled transfer truth | PASS | No live provider |
| M24 Lessor self-service | PASS, retained | Unchanged backend evidence |
| M25 Review/publication lifecycle | PASS, retained | Unchanged evidence |
| M26 Legacy review mutations | PASS, retained | Unchanged evidence |
| M27 Complete audit/rollback matrix | NOT TESTED | Focused recovery cleanup only |
| M28 Privacy-safe draft/media denial | PASS | 404/403/no-store/private URL checks |
| M29 Denial is not offline | PASS | Mounted current and stale denial families |
| M30 No-store/back-cache | PASS, bounded | Source/test evidence; exhaustive browser cache matrix not rerun |
| M31 Genuine synthetic journey | PASS | 20 current-source assertions |
| M32 Responsive uploader | PASS, bounded | Nine widths, no overflow; not full accessibility |
| M33 Device/staging release checks | NOT TESTED | Separate gates |
| M34 Unrelated scheduling/OTP/booking | NOT APPLICABLE | Not changed |

---

## 12. Trackers

Read-only canonical fingerprints still match:

- Cross-Role: `44c9dc7af26818e1bd35249eaf8f3842f28a2d68cf5975c9db1c9e3c6b7bab17`
- Visit Session: `0e324e11bf49738a4d011d354281b42e1b018febe2ae27def95e1f0ffacb7d96`

Assessment:

- DEP-004/005/006/007 remain review/publication dependencies.
- DEP-020 remains partial pending independent uploader acceptance.
- DEP-031 configurable Sub-admin permissions remains planned and was not implemented.
- DEP-050/051 retain frozen historical meaning.
- DEP-054 remains approved future governance architecture, not an access workaround.
- Batch D CRM/Admin intake/customer conversion work remains separate.
- Append-only tracker synchronization is required after acceptance/checkpoint; no workbook was edited.

Evidence: `tracker-assessment.txt`.

---

## 13. Deadlines and remaining gates

- **31 October 2026 — Complete Web V1: HIGH RISK.**
- **15 November 2026 — Native Tenant and GE apps: AT RISK.**

Remaining gates:

1. One focused independent Sol High acceptance.
2. Real business-owner eligibility/provisioning before production activation.
3. Live media provider integration.
4. Physical Safari/VoiceOver/full accessibility and performance validation.
5. Staging, observability, rollback and deployment approval.
6. Owner-authorized tracker synchronization.

---

## 14. Final recommendation

The approved uploader security/functional correction and required evidence class are complete on current source.

No known REQUIRED NOW uploader correction case remains failing in the executed matrix. Raw negative controls, restored-source checks, actual recovery transaction evidence, claim truth, genuine React single/bulk integration and focused regression evidence are present.

**Recommendation:** READY for one focused GPT-6.1 Sol High independent acceptance of this remaining failure class and its necessary neighbors.

This is not release approval.
