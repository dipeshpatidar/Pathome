# PATHOME — F4-BULK STALE-401 FINAL CORRECTION HANDOFF

21 October 2026 · IST · implementation and focused evidence handoff

**Decision requested:** one focused GPT-6.1 Sol High independent acceptance of this exact stale-401
callback correction. This report does not claim independent acceptance, release approval, tracker
synchronization or completion of Web V1.

## 1. Repository, provenance and scope

- Repository: `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`
- Branch: `feature/phase2a-oe-ui-integration`
- HEAD before and after: `285c5844df4192bdec2c0ecc771bc9676e12dcb9`
- Index: empty; nothing was staged
- Worktree: inherited dirty work preserved; 207 entries at start and 208 at final audit because the
  new focused harness/report/evidence artifacts are additional untracked paths
- Protected databases, accounts, providers, permissions, backend, schema and public HTTP APIs:
  untouched
- No stage, commit, push, fetch, merge, PR, reset, stash, rebase, branch switch, deployment or
  tracker edit

The inherited dirty worktree was expected by the approval. Existing unrelated CRM, Batch D,
Operations, Lessor, uploader and test changes were not reverted or treated as this correction.

Source/build identity:

| Artifact | Before | After |
| --- | --- | --- |
| `usePropertyDraft.ts` | `f682a75540687c7ffd5da3d467e7bf8abd5dc367af28b2edb988f25b64830735` | `bc97eda04053dc0da9454c4cb1cc15e0e069dfdbe7e09d64704126a398e5d7c4` |
| `DraftManagementBar.tsx` | `212a81414c3827b68eb97a70404ee0a177723d0e729e4abdc6f850b99942c2f3` | `8bdda0b8be45931d7700373ac9c2c024d784e55960f17a43d2443c10fcaeb21c` |
| `propertyUploaderSessionIsolation.test.mjs` | inherited | `5efdb910454153ba6bee1fb4069f286640ba1fbee33ed2cd5a318e921fcdaf3c` |
| `discard-generation-isolation.mjs` | new | `af5c8ab5d62f5e73fad001a6368ed7e1ee338d86e579b4168dab63bd801b5dd6` |
| `dist/index.html` | `e33ce0aeeaaf1b741c40590b5e8d060a7c2771ccda6fce9bf6e226e4eb4f15e5` | `b36506af0c25800a2698f22d8a10d40c7f739a1aa16885b3f642fd1e6c1cc8cd` |
| `dist/assets/index-*.js` | `9503f8cab240207f2d1a89f41cabcac625add9d4c5c8da4a891241f80b2fdfff` | `04d4999fe5e421fa599af3488e75bda2d8b44d9349d0218f3435986c9215a1f6` |
| `dist/assets/index-*.css` | `fd3497db1ca584dcfc3b6c515337a772556c7fe4c268646c5dcc7b530178cf0f` | unchanged |

## 2. Root cause and exact correction

The retired operation was recognizable only when its abort reason was `auth-changed`. A real native
cross-tab replacement uses `session-changed`, so an old DELETE401 was classified as
`session-expired`. Both single and bulk result builders then granted `canFinalize: true` to every
`session-expired` result. `DraftManagementBar` trusted that result before its credential-bound
`finally`, closing a confirmation owned by the replacement session.

A second generation edge existed: A→B→A restores the same token/User text. Token-only notification
logic could not distinguish the retired A request from the new A generation.

The correction in `usePropertyDraft.ts`:

- Captures the exact User, role, token and shared generation at dispatch.
- Records a token-bound expiry receipt for that exact captured generation.
- Classifies 401 as current expiry only when the captured session is active or exactly matches that
  generation's expiry receipt; all other 401s are `cancelled`.
- Uses an internal discard request adapter with the same public endpoint, Authorization header,
  404-success semantics and `createApiRequestError` message contract. It prevents the shared error
  factory from emitting a duplicate/stale notification, then emits at most one token-bound expiry
  from the exact current-session classifier.
- Keeps current 403 as `permission-denied` without global expiry.
- Grants `canFinalize` only to a current 403 or an operation that remains active. Current and stale
  401 completion is finalized by the existing token-bound notification/Home reset, not by an async
  result.
- Uses the same rule for SINGLE and BATCH discard.

The correction in `DraftManagementBar.tsx`:

- Captures a unique action symbol and exact initiating session for SINGLE and BULK discard.
- Checks both initiating-action identity and the active shared generation before every result-driven
  dialog, selection, notice, loading, retry and expiry branch.
- Applies the same ownership predicate to `catch` feedback and `finally` busy-state cleanup.

No public API, DTO, role, permission, backend, migration, shared Operations session, Home, global auth
utility or visual design changed.

## 3. Red-before / green-after

The unchanged Sol `mounted-cross-tab-stale401-probe.mjs` was executed against the production dist.

- Before: exit 1, 3 checks / 1 intended failure, `dialog:0`, replacement token/editor intact.
- After: exit 0, 3/3 PASS.
- Final state: `dialog:1`, prompt `REPLACEMENT WORK SURVIVES`, token `token-b`, no page errors.

Raw logs:

- `tools/oe-integration-env/evidence/f4-bulk-stale401-final-20261021/C1-mounted.log`
- original failure log remains at the Sol acceptance directory referenced by the approval

## 4. C1–C12 matrix

The complete ledger is:
`tools/oe-integration-env/evidence/f4-bulk-stale401-final-20261021/ledger.md`

| Case | Result |
| --- | --- |
| C1 real cross-tab A→B with replacement confirmation open | PASS |
| C2 same-User old 204/401/403/network/500 | PASS; all inert |
| C3 cross-account and A→B→A new generation | PASS; no stale UI or expiry event |
| C4 current 401 before replacement; current 403 | PASS; one expiry vs zero |
| C5 replacement before old 401/sibling completion | PASS |
| C6 abort-ignoring sibling and simultaneous denials | PASS; current 401 notified once |
| C7 adjacent single discard after token change | PASS |
| C8 first/middle/simultaneous 401/403 at 1/3/7 drafts | PASS; dispatch fences held |
| C9 all204, mixed204/network/500, unconfirmed loss | PASS for executed representative combinations |
| C10 duplicate discard and stale catch/finally | PASS |
| C11 unchanged W1/B1 | PASS; 6/6 and 7/7 |
| C12 authorized Admin/Sub-admin and ordinary workflow | PASS for executed/retained focused evidence |

The C2–C10 production-module cohort is 37/37 PASS. C1 also preserves the replacement selection,
non-busy confirmation state and absent stale notice in the exact-source controlled run.

## 5. Negative controls

Both controls compiled and executed in isolated `/tmp` copies. Details and source patches:
`tools/oe-integration-env/evidence/f4-bulk-stale401-final-20261021/negative-controls.md`.

- N1 stale-classification bypass: mutant hook
  `df9e57decb1b6a764b92297b70bea2b58d26cd80b5044657dea4828482d34689`;
  build PASS; mounted scenario failed `stale 401 is classified as cancelled`. The replacement
  dialog remained protected by the independent caller guard, while the hook result incorrectly
  regained `session-expired` semantics.
- N2 unconditional-completion bypass: mutant hook
  `bae2c37d4f096d1c31b81b68a9bb3e3ebec6f166284b1c1b18c25ead8275ca28`
  and bar `2a853db22a213e56f31e83eff1d52309edf19910ecef2fef50b6afecc352f90a`;
  build PASS; `dialog:0` after the stale completion. The replacement confirmation was closed.

Both sources were restored to the exact shared hashes and the same-mounted discriminators reran
green. This is defense-in-depth behavior: N1 directly exercises classification/result authority,
while N2 directly exercises the UI completion guard. Because both layers are independently
protective, N1's stale result cannot by itself close the dialog; that UI-specific failure is
reserved for N2. No separate synthetic `canFinalize:false` was supplied.

## 6. Executed verification

Exact commands/results:

```text
npx tsc --noEmit
npm run build
node --test src/tests/uploaderRestoreTerminalDenial.test.mjs \
  src/tests/propertyUploaderSessionIsolation.test.mjs \
  src/tests/draftBulkDiscard.test.mjs \
  src/tests/operationsWorkspaceSession.test.mjs \
  src/tests/authSessionIsolation.test.mjs
node browser-verification/property-uploader-session/f4-callback-ownership.mjs
node browser-verification/property-uploader-session/f4-late-overlap.mjs
node browser-verification/property-uploader-session/run.mjs
node browser-verification/property-uploader-session/storage-ownership.mjs
node browser-verification/property-uploader-session/discard-generation-isolation.mjs
node <unchanged mounted-cross-tab-stale401-probe.mjs>
node <unchanged external-denial-restore-probe.mjs>
node <unchanged bulk-denial-probe.mjs>
git diff --check
git diff --cached --check
```

Results:

- TypeScript: PASS
- Production build: PASS, 2,047 modules, existing large-chunk advisory only
- Focused Node: 75/75 PASS; zero failure/error/cancel/skip
- Callback/BATCH: 37/37 PASS
- F4-LATE: 57/57 PASS
- Uploader/session: 80/80 PASS, nine widths and retained budget
- Storage ownership: 5/5 PASS
- Stale-401 production-module cohort: 37/37 PASS
- C1: 3/3 PASS; W1: 6/6 PASS; B1: 7/7 PASS
- Exact-source current401→replacement→late sibling: 5/5 PASS
- Git whitespace and cached hygiene: PASS

All browser batches completed below ten minutes.

## 7. Retained integration evidence and limitations

Backend/API/schema/transaction boundaries were unchanged. The preceding genuine 17-assertion
React→Spring Boot→disposable PostgreSQL Admin SINGLE/BATCH create/resume/partial-result/revocation
journey is retained, not newly executed or relabeled. The real committed server deletion/partial
truth limitation remains: a response lost after server commit is reported as failed/unconfirmed,
never as a client rollback.

## 8. Tracker and deadline assessment

Both owner-known canonical local exports were read only:

- Cross-Role: `44c9dc7af26818e1bd35249eaf8f3842f28a2d68cf5975c9db1c9e3c6b7bab17`
- Visit Session: `0e324e11bf49738a4d011d354281b42e1b018febe2ae27def95e1f0ffacb7d96`

No newer authenticated canonical local version was established. No workbook or Change Log was
edited. DEP-004/005/006 remain Partial/P0; DEP-007 Partial/P1; DEP-020 Partial/P1; DEP-031
Planned/P0. DEP-050/051 retain historical Completed governance status without certifying callback
lifetime. DEP-054 remains product-approved future governance with architecture pending. DEP-006's
older staff revision wording still needs future append-only reconciliation.

Future separately approved tracker synchronization should record this stale-401 callback closure;
this report does not modify the workbooks.

31 October 2026 Complete Web V1 remains **HIGH RISK**. 15 November 2026 native Tenant/GE remains
**AT RISK**. Batch D, CRM, Manual WhatsApp, customer conversion and the original 20 OE
questions/four delivery batches remain separately accountable. No scope reduction or release
approval is claimed.

## 9. Performance, security and UI assessment

- No extra database/API round trip; discard remains bounded at concurrency 3.
- Retired requests cannot reclaim credential text, UI state or expiry authority; A→B→A is
  generation-scoped.
- Current 401 still uses the existing token-bound `pathome_session_expired` event and Home reset.
- Current 403 remains local and does not fabricate offline/logout.
- No cross-user disclosure or server authorization change was introduced.
- Focused 1280×900 browser checks pass; retained session coverage includes 320–1440px containment.
- Existing responsive layout, semantics, 44px controls and readable feedback were preserved; no
  visual redesign was made.

## 10. Checks not run

`NOT TESTED` in this correction:

- Fresh genuine PostgreSQL business journey; backend boundaries are unchanged and the prior 17/17
  genuine journey is retained.
- Physical iPhone Safari, VoiceOver/full WCAG, 200% zoom, Firefox/WebKit and browser back-cache.
- Live Cloudinary/B2/Meta/WhatsApp or other provider calls.
- Staging, deployment, rollback, load, observability, production owner provisioning and tracker
  synchronization.
- Full historical Cartesian status/position/role matrices unrelated to this callback class.

## 11. Files changed by this correction

Production:

- `frontend-web/src/hooks/usePropertyDraft.ts`
- `frontend-web/src/components/DraftManagementBar.tsx`

Directly affected tests/evidence:

- `frontend-web/src/tests/propertyUploaderSessionIsolation.test.mjs`
- `frontend-web/browser-verification/property-uploader-session/discard-generation-isolation.mjs`
- `frontend-web/dist/` generated production bundle
- `tools/oe-integration-env/evidence/f4-bulk-stale401-final-20261021/`
- this report
- append-only `chat_gpt_changes` entry

All other dirty files were inherited work and were preserved.

## 12. Completion boundary

No new business rule, permission, API contract, schema, dependency, global session semantics or UI
design was introduced. No commit/push/stage occurred. The shared repository remained untouched by
the negative-control procedure.

**STOP for one GPT-6.1 Sol High independent acceptance of this exact stale-401 callback correction.**
Further implementation, Git publication, tracker modification, provisioning or deployment requires
new owner direction.
