# PATHOME — F4-LATE TERMINAL OPERATION FENCING: IMPLEMENTATION AND VERIFICATION HANDOFF

21 October 2026, IST

## 1. Approval and scope

Owner approval covered one uploader-local terminal operation fence, SINGLE/BATCH integration,
focused tests, isolated negative controls, and evidence-only reconciliation of directly identified
security/test-provenance gaps.

Implemented:

- A unique uploader-local operation epoch.
- A terminal current-session denial latch.
- Synchronous epoch retirement before cleanup.
- Protected post-await commits, follow-up requests, timers, progress callbacks, recovery paths and
  finalizers.
- Replacement-session epoch reinitialization.
- Guarded IndexedDB/localStorage cleanup.
- Directly affected test and mounted-browser harness coverage.

Unchanged:

- Backend authority, roles, permissions, APIs, DTOs, entities and migrations.
- Shared Operations session behavior.
- Uploader layout, copy, navigation and business workflow.
- F1 new-draft, F2 recovery and F3 media-claim production logic beyond the directly related
  restore-terminal waiting fix.

Forbidden operations performed: none. No tracker edit, Git publication, protected database
mutation, real-provider call or deployment.

## 2. Provenance

- Repository: `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`
- Branch: `feature/phase2a-oe-ui-integration`
- HEAD before and after: `285c5844df4192bdec2c0ecc771bc9676e12dcb9`
- Fetched feature ref: `FETCH_HEAD` matched local HEAD before editing.
- The fetch reported shared-repository ref-lock errors from another worktree after writing
  `FETCH_HEAD`; it was interrupted rather than deleting or overriding a lock.
- Initial worktree count: 204 porcelain-v2 entries. Final: 205, with the additional entry being
  this required handoff report; evidence/test additions remained inside already-untracked paths.
- Index: empty.
- Inherited dirty CRM, Operations, Batch D and other work: preserved.

Initial affected hashes matched the independent acceptance manifest:

```text
d45c03aa74065b740f2f8bf11e22a65dcd51d4d8ab275dffffc9c876961cdf75  usePropertyDraft.ts
4cb3a6b2f184afb2e3a62c3986345927c0709238da84768fcf458dc0a7f4513f  uploaderSession.ts
18eb443b201f18ea93652911b1e70227215d3a64341d14f8a7e46594a22eb98c  MasterAdminDashboard.tsx
28fbdc0eba34d6864a86122685e2064a79324c4cedcd2112eeb01982bc76cdb9  BatchPropertyIngestionStudio.tsx
548162dbb288678a61956b89559707a6105301fe29614f3807545f0039f4b430  localDraftStorage.ts
```

Final hashes:

```text
e31554dc1572907232fec0325d74776fdfb3b0abd73b3ac60485432889d3d4b2  frontend-web/src/hooks/usePropertyDraft.ts
f5bbf99bd5a9c5d46a585166c53c85830e9d3698ff1180d5961bf27a587dc4f4  frontend-web/src/services/draftService.ts
727977ac04b80ef477b90ef6a8918a8fd4e4df058ffb02e308a2cfa4fa2903a0  frontend-web/src/services/localDraftStorage.ts
2a7150c9931b50ad7299de984c8970e0d140c1b5ff75b601bb031918cb577f87  frontend-web/src/components/MasterAdminDashboard.tsx
4f2eae2c57281a0000c882952dddc24908f9cd55da51e9e166c74965a2160631  frontend-web/src/components/BatchPropertyIngestionStudio.tsx
3f30c87826835ec4a9ed749bcb019018ebf2d7e808bb94c50bf95759fbf80468  frontend-web/src/tests/propertyUploaderSessionIsolation.test.mjs
3afcc03f922cfa7129303de5d5e69f00003023e9d8e252f8db74b0b92a2ce033  frontend-web/src/tests/uploaderRestoreTerminalDenial.test.mjs
fcc0f408338518a914739cfe6cfc2c915d7ca5c74322d384bf6823157ed2b581  frontend-web/browser-verification/property-uploader-session/f4-late-overlap.mjs
ace2c1d47303455d228660605a9aa457256d34cbb564ec4d8a51957362ca2ace  frontend-web/browser-verification/property-uploader-session/storage-ownership.mjs
c674015f9a2dd9f201f8883c73a5c1c3ca094bd259a4f9971cf0c613b6163d03  backend/src/test/java/com/indore/pathome/spaces/service/BackOfficePropertyAuthorityTest.java
f944415e0c200ae2ab3dec3bdee74ba4a6e9c5ad615c43b713b9134a46f0a501  backend/src/test/java/com/indore/pathome/spaces/controller/BackOfficePropertyAuthorizationHttpPostgresTest.java
```

`uploaderSession.ts` was reviewed and unchanged.

## 3. Root cause

`isSuspendedRef` blocked newly scheduled saves after withdrawal, but every already dispatched callback
still checked only the shared authentication session. A 403 does not invalidate that shared
credential, so an old callback remained “active” after the uploader had terminally withdrawn.

Uploader-local denial state and uploader-local operation lifetime were missing. A shared session
generation cannot substitute for them because the same valid credential deliberately remains active
after an object authorization denial.

## 4. Implementation

`usePropertyDraft.ts` now owns:

- `activeOperationEpochRef`: a unique `symbol` operation lifetime.
- `terminalDeniedSessionRef`: an exact credential/generation denial latch.
- `terminalDenialMessageRef`: stable denial feedback across duplicate denials.
- `UploaderOperationGuard`: captured session, email, draft identity, unique epoch and `isActive()`.
- `captureProtectedOperation()` and `isProtectedOperationActive()` for component-owned work.

On terminal 401/403 the hook synchronously:

1. Retires the operation epoch.
2. Latches the exact credential/generation.
3. Cancels the debounce timer and pending payload/session.
4. Clears editor/card/list/busy/version/Saved/conflict state through existing callbacks.
5. Removes the matching active pointer.
6. Starts ownership-guarded local-record cleanup.
7. Preserves deny-first feedback.

Old callbacks can no longer:

- set Saved/version/time;
- clear or replace denial;
- refresh drafts;
- enter 409 recovery or retry;
- dispatch offline/conflict/publication work;
- mutate local storage;
- finalize reconciliation or publication;
- create/retain previews or progress.

`resumeAutosave()` cannot clear a terminal latch. Only the existing legitimate auth-change lifecycle
creates a new epoch. Old work retains its old session object and cannot borrow a replacement token.

`localDraftStorage.removeIf()` checks ownership inside the IndexedDB read/write transaction and also
guards the localStorage fallback. Delayed old cleanup cannot delete replacement-owned data.

`draftService.restoreMediaFiles()` now races worker settlement against an explicit terminal signal.
An abort-ignoring sibling response is still dropped after await, but cannot keep the denied restore
pending forever.

SINGLE and BATCH restore, parse, create, stage/upload, progress, retry, publication, reconciliation
and finalization callbacks use the same guard infrastructure.

## 5. T1–T10 matrix

| Case | Event order and invariant | Environment / evidence | Result |
| --- | --- | --- | --- |
| T1 | SINGLE and BATCH restore → held autosave → media 403 → old save 200/409/500/network | Production build, mounted Chromium, real fetch/IndexedDB; `f4-late-overlap-green.log` | PASS |
| T2 | Save denial while parse is pending; media denial while autosave is pending; prior 401/403 and nested-recovery cases | Mounted Chromium `f4-late`, existing 80-check harness, restore production tests | PASS for required representative callback owners |
| T3 | Denial → old 409/500/network → no conflict/offline/retry/nested GET | Mounted Chromium four late-result modes; 57/57 focused checks | PASS |
| T4 | Duplicate/concurrent current-session denials; success-before-denial; cleanup idempotence | Mounted Chromium simultaneous media/save 403 case and latching assertions; existing allow/deny ordering checks | PASS |
| T5 | Same-User token replacement, cross-account, real cross-tab, old late response after replacement save | Mounted Chromium; existing 80-check cross-tab/token suite | PASS |
| T6 | Delayed IndexedDB/localStorage cleanup across replacement; delete failure; no denied-record resurrection | Real browser production storage module; `storage-ownership-green.log` | PASS |
| T7 | Denial → refresh/remount → no old private/Saved state → legitimate replacement login/save | Mounted Chromium at 1280×900 | PASS for refresh/remount; browser back-cache not separately exercised |
| T8 | 0/1/3/6 restore workers; abort-ignoring sibling success after denial | Production `restoreMediaFiles`; 11 focused restore tests | PASS |
| T9 | Authorized Admin SINGLE/BATCH create, resume, partial batch, replacement save; normal 409/partial media retained | Fresh genuine React → Spring → disposable PostgreSQL 17/17; focused recovery tests | PASS within tested scope |
| T10 | SINGLE/BATCH denial feedback, cards/previews, responsive containment | Mounted denial cases; existing nine-width 320–1440 checks | PASS for affected UI; full WCAG/physical device remains release-gated |

## 6. Red-before / green-after

Retained independent red:

- `overlap-denial-probe.log`
- In-flight save precondition PASS.
- False Saved assertion FAIL.
- Persistent denial assertion FAIL.
- Post-denial draft-list GET observed.

Local red before correction:

- Same mounted sequence failed with three assertions.

Green after correction:

- Original sequence no longer restores Saved.
- Denial remains visible.
- No post-denial draft-list refresh.
- Editor remains withdrawn.
- Replacement session can save with its own token.
- SINGLE and BATCH both pass.

## 7. Negative controls

F4-LATE epoch bypass:

- One temporary file outside the repository.
- Original hash: `e31554dc...d4b2`.
- Mutant hash: `4145211b...d42f`.
- One production line bypassed only the autosave epoch check.
- Mutant compiled and ran.
- Intended BATCH failures reproduced.
- Exact source restored, hash matched, rebuild passed, same discriminator green.

Prior N1–N5:

- Per-control before/mutant hashes and raw failure logs exist under
  `tools/oe-integration-env/evidence/p1-uploader-final-closure-20261021/`.
- N1, N3, N4a, N4b and N5 are source/hash/log bound.
- Exact historical N1–N5 patch text is not available; source differences cannot be reconstructed
  without inventing provenance.
- Combined N2 failed first at retry and did not distinguish dismiss/resolution.

New narrow N2 controls:

- Dismiss-only authority hold removal failed `dismiss: supported revocation did not wait`.
- Resolution-only authority hold removal failed `resolution: supported revocation did not wait`.
- Original hash `e70fee62...c267` restored.
- Same PostgreSQL test passed, zero skips.
- Temporary copies moved to Trash and remain recoverable.

## 8. Positive regressions

Executed:

- 25 focused frontend/session/restore/Operations tests: 25/25 pass.
- Existing uploader mounted suite: 80/80 pass.
- F4 focused mounted suite: 57/57 pass.
- Storage ownership suite: 4/4 pass.
- Identity/authority unit test: 11/11 pass.
- Denied parser side-effect integration: 1/1 pass.
- Post-denial batch side-effect integration: 1/1 pass.
- Narrow N2 restored PostgreSQL test: 1/1 pass, zero skips.
- Genuine uploader journey: 17/17 assertions pass.

The genuine journey exercised built React, real password login, fresh SINGLE and BATCH drafts,
resume, reviewed create, mixed batch partial success, grant revocation, retained-token 403, persisted
PostgreSQL truth and isolated schema cleanup.

No media provider was contacted.

## 9. Security evidence

M14 identity:

- Existing tests cover inactive profile, role demotion, grant removal, subject/email mismatch,
  missing stable identity, own/foreign media and narrow Sub-admin authority.
- New focused test covers a deleted persisted User with a retained credential; 11/11 authority tests
  pass.

M19 parse side effects:

- Deactivated and demoted actors are denied parse before learning capture.
- PostgreSQL `parser_training_examples` count is unchanged across each denied parse.

M21 post-denial side effects:

- Real two-card `REQUIRES_NEW` journey revokes after the last committed card.
- Draft remains unchanged.
- Parser-learning, notification and audit counts remain unchanged after denial.

M27 rollback/integrity:

- Retained review audit rollback proof covers forced audit failure and unchanged listing/audit state.
- Retained media recovery/after-commit and batch partial-success evidence remains source-bound.
- No uploader-specific audit event was invented.

No server authorization bypass or cross-account data disclosure was reproduced.

## 10. Integration and database

Target: `pathome_oe_test_integration`

- Loopback only.
- Positive DISPOSABLE sentinel count: 1.
- Owned uploader schemas before and after: 0.
- All test schemas were run-owned UUID schemas and dropped.
- Protected `pathome_db` was not selected.
- Real owner accounts and grants were not modified.

Controlled browser evidence remains controlled API evidence. Business success claims come from the
fresh genuine 17-assertion journey.

## 11. Exact commands and results

```text
npx tsc --noEmit
PASS

npm run build
PASS; 2,047 modules; existing large-chunk advisory only

node --test propertyUploaderSessionIsolation.test.mjs \
  uploaderRestoreTerminalDenial.test.mjs operationsWorkspaceSession.test.mjs
25 tests; 25 pass; 0 fail/errors/skips

node frontend-web/browser-verification/property-uploader-session/run.mjs
80 checks; 80 pass

node frontend-web/browser-verification/property-uploader-session/f4-late-overlap.mjs
57 checks; 57 pass

node frontend-web/browser-verification/property-uploader-session/storage-ownership.mjs
4 checks; 4 pass

mvn -q -f backend/pom.xml -Dtest=BackOfficePropertyAuthorityTest test
11 tests; 11 pass; 0 fail/errors/skips

mvn ... -Dtest=BackOfficePropertyAuthorizationHttpPostgresTest#inactiveDemotedAndGrantRevokedAdminsAreDeniedWithRetainedTokens test
1 test; pass; 0 skips

mvn ... -Dtest=BackOfficePropertyAuthorizationHttpPostgresTest#revocationAfterTheLastCommittedCardPreventsPostDenialDraftLearning test
1 test; pass; 0 skips

mvn ... -Dtest=UploaderMediaClosureHttpPostgresTest#actualRecoveryTransactionsSerializeWithSupportedGrantRevocationUntilCommit test
restored source: pass; 0 skips

node tools/oe-integration-env/phase2a/uploader-closure.mjs
17/17 assertions pass; schema dropped

git diff --check
PASS

git diff --cached --check
PASS; index empty
```

Raw logs are under
`tools/oe-integration-env/evidence/p1-uploader-f4-late-20261021/`.

## 12. Original M01–M34 reconciliation

| IDs | Reconciled status |
| --- | --- |
| M01–M06 | Bounded positive/negative authority evidence; no real-owner provisioning claim. |
| M07 | Exact parse→authority-loss→create interleave not newly executed; separate admissions remain proven. |
| M08–M10 | Mounted logout/stale/account/cross-tab/token replacement evidence supports the tested class. |
| M11 | Own draft/version/resume evidence retained; no every-version permutation claim. |
| M12–M13 | Foreign single/bulk denial-before-private-data/write evidence retained. |
| M14 | Expanded deleted-User/no-profile/subject/ownership coverage; no blanket malformed-ID matrix claim. |
| M15–M18 | Recovery/per-card ordering and service-boundary evidence retained; narrow N2 closure added. |
| M19 | Denied parser side-effect assertion added; full oversized/zero/many parser campaign not reopened. |
| M20 | Genuine mixed batch and retained rollback evidence; no complete provider-payload matrix. |
| M21 | Draft, learning, notification and final audit side effects now explicitly asserted zero after denial. |
| M22–M23 | Idempotency/claim evidence retained; live provider remains untested. |
| M24–M26 | Unchanged Lessor and review evidence retained; no new Lessor UI acceptance claim. |
| M27 | Audit rollback, recovery after-commit and batch partial-commit boundaries reconciled. |
| M28 | Privacy/no-store/private-URL subsets retained. |
| M29 | Corrected: denial now wins over late success/409/500/network. |
| M30 | Remount and delayed storage ownership strengthened; browser back-cache remains open. |
| M31 | Fresh genuine journey: corrected 17 asserted checks. |
| M32 | Nine-width no-overflow and denial feedback pass; full WCAG/physical device not tested. |
| M33 | Release/device/staging gates remain NOT TESTED. |
| M34 | NOT APPLICABLE; no unrelated OTP/booking/scheduling change. |

## 13. Tracker assessment

Read only:

- Cross-Role: `44c9dc7af26818e1bd35249eaf8f3842f28a2d68cf5975c9db1c9e3c6b7bab17`.
- Visit Session: `0e324e11bf49738a4d011d354281b42e1b018febe2ae27def95e1f0ffacb7d96`.

Both match the known canonical 19 October exports. No newer local canonical export was found.
DEP-004–007, DEP-020, DEP-031, DEP-050/051, DEP-054 and both Change Logs require a future
append-only synchronization under separate authorization.

## 14. Deadline and release gates

- 31 October 2026, complete Web V1: HIGH RISK.
- 15 November 2026, native Tenant and GE: AT RISK.
- Batch D Admin Intake, CRM/V52 business integration, manual WhatsApp sharing, customer
  conversion, staging, observability, rollback and deployment remain separate.

Still NOT TESTED:

- Real owner-account eligibility.
- Live Cloudinary/B2/Meta/WhatsApp providers.
- Physical iPhone Safari, VoiceOver, full WCAG, 200% zoom.
- Firefox and WebKit.
- Staging/deployment/load/observability/rollback.
- Browser back-cache.
- Tracker synchronization.

## 15. UI/UX and performance

- No redesign or user-facing copy change.
- Mounted denial feedback remains visible and accessible.
- Protected text/cards/previews/pointer records withdraw.
- Nine widths from 320–1440 show no horizontal overflow in the retained uploader suite.
- Touch-target/full keyboard/full WCAG and physical-device acceptance remain release gates.
- No new network fan-out, cache, polling or database query was introduced.
- Late protected follow-ups are suppressed, reducing requests after denial.

## 16. Recommendation

The corrected F4-LATE failure class is **READY FOR ONE FOCUSED INDEPENDENT SOL REVIEW**.

This is not release acceptance. The next action is one focused GPT-6.1 Sol High review of F4-LATE and
its directly affected lifecycle, with the exact evidence above.

No commit, push, merge, PR, deployment, tracker edit, protected data mutation or provider call was
performed.

STOP.
