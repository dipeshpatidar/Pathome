# OE workspace browser verification (Package 1A)

Repeatable behavioural verification for the Operations (OE) workspace hardening. It runs the **real
built app** in Chromium with every HTTP request answered in-process by the controllable stub in
`run.mjs` — no server port, no network, no module mocking. `localStorage`, window events, the fetch
layer, the React tree and the production components are the real ones; only the backend responses are
synthetic, so results verify **UI behaviour and network traffic, not a live Spring Boot/PostgreSQL
deployment**.

## Running it

```bash
npm --prefix frontend-web run build
node frontend-web/browser-verification/operations-workspace/run.mjs
```

Requires Playwright with the full Chromium build (present in this environment). The script prints one
line per check and exits non-zero on any failure. A full pass takes well under a minute, including a
deliberate ~35 s wait that proves the approved ~30 s revalidation cadence discovers revocation with no
user interaction.

## Coverage map

| Approved requirement | Scenario |
| --- | --- |
| Employee entry point requires an effective `OPS_COORDINATE@TEAM` grant | `employeeWithGrantSeesWorkspace`, `employeeWithoutGrantSeesNoEntry` |
| Inactive staff profile gets no access | `inactiveStaffSeesNoEntry` |
| Admin/Sub-Admin entry point from fresh backend capabilities, existing tabs preserved, never persisted as the active tab | `adminGating` |
| P1: no cross-account mutation after an `await` (paused read + account switch) | `accountSwitchDuringClaim` |
| P1: stale responses never populate the next account | `accountSwitchDuringClaim`, `unmountDuringPendingRead` |
| P1: revalidation on focus / visibility / ~30 s / on entry; revocation clears protected data | `revocationWhileDetailOpen`, `pollingDiscoversRevocation` |
| P1: visibility restoration revalidates, and a backgrounded document does not | `visibilityRevalidationDiscoversRevocation` |
| P1: transient verification failure hides protected data, blocks mutations, offers Retry, recovers by re-reading | `transientFailureAndRetry` |
| P1: unmount during a pending operation | `unmountDuringPendingRead` |
| Package 1A correction: a claim waiting on its fresh root read when the workspace unmounts transmits nothing | `claimUnmountDuringPendingRead` |
| Package 1A correction: a release waiting on its authority re-verification when the workspace closes transmits nothing | `releaseUnmountDuringPendingVerification` |
| Package 1A correction: authority withdrawn while a claim waits on a read transmits nothing | `claimAuthorityWithdrawnWhileReadPending` |
| Package 1A correction: a transient verification failure before a release transmits nothing and fails closed | `releaseTransientFailureWhileVerifying` |
| Package 1A correction: a delayed older `granted` response cannot restore a newer denial | `delayedOlderGrantCannotOverrideNewerDenial` |
| Package 1A correction: a transmitted command whose response is lost is reported as unconfirmed, never resent, and reconciled from the authoritative queues | `lostCommandResponseIsUncertainAndNeverRetried` |
| Package 1A correction: 204 claim and release still succeed and announce normally | `lostCommandResponseIsUncertainAndNeverRetried` |
| P2: claim conflict stays visible, is announced and is never auto-resent | `claimConflictFeedback` |
| P2: release conflict stays visible after the detail closes | `releaseConflictAfterDetailCloses` |
| P2: 44x44 minimum hit targets and 320/375/768/1440 layout without horizontal overflow | `hitTargetsAndOverflow` |
| Frozen contract: a confirmed 204 whose account changes mid-reconciliation writes nothing into the new account, and its delayed refresh never aborts/replaces the new account's detail | `postClaimAccountSwitchRace` |
| Frozen contract: an uncertain command whose account changes mid-reconciliation leaves exactly one POST and no notice under the new account | `lostCommandAccountSwitchRace` |
| Package 1A correction 2: a claim continuation re-validates its operation after the awaited `openDetail` read — a confirmed 204 whose queue reconciliation failed, held on its detail read while the account changes, writes nothing under Account B and leaves B's notice, selection and loading state untouched | `claimStaleNoticeAfterHeldDetailReadAcrossAccountSwitch` |
| Package 1A correction 2 (discriminating form): the same guard is proven by a *same-account* transient authority loss while the claim awaits its detail read — the invalidated continuation's refresh-failure notice is never written, never resurfaces after recovery, and transmits nothing | `claimStaleNoticeSuppressedAcrossAuthorityLoss` |
| Package 1A correction 1: a pending capability check is scoped to the current session — Account A's background verification held indefinitely cannot block Account B's own claim, its late settle cannot clear B's gate or alter B's authorization, while a check that is genuinely B's own still fails the gate closed | `obsoleteCapabilityCheckCannotBlockNextAccount` |
| Package 2 correction: a pending check is also scoped to the *workspace entry* — with entry A's verification held indefinitely, the same account leaving and re-entering as entry B is admitted only by B's own verdict, and a Claim transmits exactly one authorized POST while A's obsolete check is still unresolved | `obsoleteSameAccountEntryCheckCannotBlockClaim` |
| Package 2 correction: the same entry lifecycle for a Release of owned work — one authorized POST, ownership/reconciliation unchanged, no command rejected solely because an older entry's check is pending | `obsoleteSameAccountEntryCheckCannotBlockRelease` |
| Package 2 correction: retiring the obsolete entry check does not weaken the current-check contract — a genuinely current, held revalidation keeps the mutation fail-closed until it verifies, then exactly one POST is sent | `currentEntryCheckStillBlocksSameAccountMutation` |
| Package 2 correction: a late grant/finalizer from entry A cannot admit entry B, start a protected read, clear B's pending gate, or transmit a stale mutation | `obsoleteEntryFinalizerCannotAlterCurrentEntry` |
| Package 2 correction (regression guard): rapid same-account exit/re-entry still admits the workspace, transmits exactly one claim, and keeps every capability read on the signed-in credential | `rapidSameAccountExitReentry` |
| Frozen contract: duplicate taps during a live operation transmit exactly one ownership mutation | `duplicateClicksTransmitOnce` |
| Frozen contract: an uncertain command with a failed reconciliation stays unconfirmed and never claims a completed reload | `uncertainOutcomeWithFailedReconciliation` |
| Frozen contract: a 409 whose reconciliation failed keeps its conflict feedback, states "current ownership could not be verified" and is never resent | `conflictWithFailedReconciliation` |
| Frozen contract: a missing command version with a failed refresh transmits nothing and makes no false reload claim | `missingVersionWithFailedRefresh` |
| Frozen contract: a confirmed 204 with a failed read stays a confirmed success with a separate refresh-failure notice | `confirmedSuccessWithFailedRefresh` |
| Frozen contract: an account change returns the workspace to its neutral entry view | `accountSwitchResetsToNeutralView` |
| Existing employee destination still works | `employeeWithGrantSeesWorkspace`, `employeeWithoutGrantSeesNoEntry` |
| Package 2 correction B: an aborted, never-settling Claim mutation check is retired synchronously — entry B's new Claim runs its own fresh verification and transmits exactly one POST, with nothing stale sent from the abandoned entry | `abortedMutationCheckNeverBlocksNextEntryClaim` |
| Package 2 correction B: the same lifecycle for a Release of owned work — exactly one authorized POST, no ownership or lock corruption, no stale transmission from the abandoned entry | `abortedMutationCheckNeverBlocksNextEntryRelease` |

Deterministic Node-level behaviour for the same races (real service, real credential capture, exact
command payloads, HTTP 204 handling) lives in `../../src/tests/operationsWorkspaceSession.test.mjs`;
the operation lifecycle, the mutation-lock ownership races, the synchronous dispatch gate, exact
credential continuity (including a colliding-digest pair) and the transport-outcome wording live in
`../../src/tests/operationsOwnershipRaceCorrections.test.mjs`; pure mapping and contract tests live in
`../../src/tests/operationsWorkspaceIntegration.test.mjs`.

Commit-phase entry ownership under a genuinely *discarded* React render (a suspended `startTransition`
that never commits) cannot be reached from an ordinary navigation, so it has its own harness:
`../operations-entry-commit/run.mjs`, which drives the real React 18 client renderer against the real
hook.

`stub.freezeHeldResponse` makes a held request answer with the response produced *before* it was held,
which is how `delayedOlderGrantCannotOverrideNewerDenial` reproduces "an older granted response arrives
after a newer denial"; `stub.loseCommandResponse` drops one ownership command in transit so the client
really has transmitted it without receiving an answer; `stub.failMatcher` makes the authoritative queue
reads fail on demand, and `stub.dropClaimableVersion` omits the version needed to build a claim command.

## Negative control (Package 2 correction)

The five same-account scenarios detect the defect they were written for. With the entry-scoped
retirement call disabled in a local build (`retireObsoleteEntryChecks(...)` commented out) and every
other source restored, this suite reports 6 failures — `the claim transmits exactly one authorized POST
— expected 1, saw 0`, `the release transmits exactly one authorized POST — expected 1, saw 0`, and
`the claim is dispatched once the current check is verified — expected 1, saw 0` — because the
superseded entry's pending check still gates the mutation. The correction is restored afterwards and
all 244 checks pass.

## Negative control (Package 2 correction B)

The two mutation-cancellation scenarios detect the defect they were written for. They open their context
with `ignoreCapabilityAbort`, which strips the caller's abort signal from the capability transport so a
held request keeps its promise pending *even after the operation aborts* — the exact condition the
correction addresses, and one the real browser `fetch` cannot express because it rejects on abort. With
`retireOwnPendingCheck()` disabled in a temporary build and every other source restored, this suite
reports 7 failures — `exactly one authorized Claim POST is transmitted for entry B — expected 1, saw 0`,
`exactly one authorized Release POST is transmitted for entry B — expected 1, saw 0`, and the related
success and duplicate assertions — because the abandoned entry's pending mutation check still fails the
dispatch gate closed. The correction was restored, `tsc --noEmit` and the build re-run, and the full
suite then passes 244/244.

## Not covered here

* A live Spring Boot + PostgreSQL backend (authorization, ownership locks and version conflicts are
  enforced there and remain authoritative).
* Physical mobile devices and real touch input.
* A genuine browser tab visibility transition. Headless Chromium keeps every page "visible", so
  `visibilityRevalidationDiscoversRevocation` forces the documented `visibilityState` on the live
  document and dispatches the real `visibilitychange` event; the app's own listener and revalidation
  path run unmodified, but the browser-side transition itself is not exercised.
