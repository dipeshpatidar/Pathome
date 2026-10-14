# OE entry commit-phase verification (Package 2 corrections)

Repeatable behavioural verification for the defects no other harness in this repository can reach:
**a React render that is started and then thrown away**, and the lifecycles that must not be influenced
by one. Ordinary navigation, StrictMode and route changes never produce such a render, so this harness
drives the real React 18 client renderer through a `startTransition` update that suspends and is
therefore never committed while its promise is pending. It also drives the **retired in-flight
capability check** lifecycle: the surface closes in a committed transition while a verification is still
held open, and the same signed-in user returns to the closed, non-granted surface.

It runs in Chromium with:

* the **real production hook** (`src/hooks/useOperationsCapability.ts`) bundled unmodified,
* the **real** `react-dom/client` concurrent renderer and `Suspense`/`useTransition`,
* a controlled in-page `fetch` that holds the capability transport open and records whether the
  transport was aborted — an abort is recorded but deliberately does **not** reject, so a late response
  can still be produced afterwards,
* a recorded `window.setInterval` / `window.clearInterval` history, so a scenario can state whether a
  surface owns a live ~30s cadence (and that a discarded render never activated one).

## Running it

```bash
node frontend-web/browser-verification/operations-entry-commit/run.mjs
OE_ENTRY_COMMIT_SCENARIOS=3,4 node frontend-web/browser-verification/operations-entry-commit/run.mjs
```

Requires Playwright with Chromium (installed globally in this environment). The script prints one line
per check and exits non-zero on any failure. `OE_ENTRY_COMMIT_SCENARIOS` runs a subset (1-8), which is
what the negative controls use to keep their output focused.

## Coverage

| Requirement | Scenario |
| --- | --- |
| An abandoned speculative B render cannot advance ownership, abort or retire committed entry A's live verification | `scenarioDiscardedRenderPreservesEntryA` |
| A still-committed entry A is admitted by its own verification after the discarded render, with no protected read before that verdict | `scenarioDiscardedRenderPreservesEntryA` |
| No request from the uncommitted entry establishes authority, and no protected read runs for it | `scenarioDiscardedRenderPreservesEntryA` |
| A committed transition retires entry A's obsolete entry check (its transport is aborted) | `scenarioCommittedEntryRetiresA` |
| The new committed entry B receives its own verification and is admitted only by it; zero B protected reads before that | `scenarioCommittedEntryRetiresA` |
| A late grant from entry A's aborted transport authorises nothing in B | `scenarioCommittedEntryRetiresA` |
| Entry B's own pending check survives entry A's late finalizer, keeps the dispatch gate closed, and only B's own success opens it | `scenarioCommittedEntryRetiresA` |
| Entry A stays committed, visible, admitted and cadence-owning while a concurrent exit render is discarded | `scenarioDiscardedExitRetainsFocusRevalidation`, `…VisibilityRevalidation` |
| A discarded exit render cannot suppress the committed entry's revalidation: a focus event still re-verifies entry A | `scenarioDiscardedExitRetainsFocusRevalidation` |
| The same through a `visibilitychange` event on the document | `scenarioDiscardedExitRetainsVisibilityRevalidation` |
| The re-verification carries the signed-in credential, provably belongs to the committed entry, and no protected read runs before its verdict | both exit scenarios |
| A denial delivered through that re-verification withdraws entry A's protected content, leaves authority `restricted`, keeps the dispatch gate closed and refuses a Claim/Release pre-flight | both exit scenarios |
| A committed exit retires the obsolete entry: no workspace is rendered, nothing is admitted, the dispatch gate is closed and no cadence survives | `scenarioCommittedExitStopsWorkspaceRevalidation` |
| A granted but closed workspace is no longer revalidated from focus or visibility, and re-entering requires a fresh entry-scoped verification with protected content withheld until it succeeds | `scenarioCommittedExitStopsWorkspaceRevalidation` |
| A speculative Operations entry cannot admit, read, poll or cadence anything while a closed surface is committed | `scenarioSpeculativeOpenCannotActivateClosedWorkspace` |
| Only the real commit of the Operations entry issues its own verification, owns a cadence, and then admits it | `scenarioSpeculativeOpenCannotActivateClosedWorkspace` |
| A committed close transmits a fresh capability check for the closed surface instead of adopting the retired, never-settling promise | `scenarioRetiredCheckIsNeverReusedOnFocusRecovery` |
| Focus and visibility while the closed surface is non-granted adopt the genuinely current check rather than duplicating it, and a denial delivered through that check stays fail closed | `scenarioRetiredCheckIsNeverReusedOnFocusRecovery` |
| Settling the obsolete check late (with a grant) neither clears the fresh check nor its gate, nor becomes a verdict, and no protected operation is authorised from it | `scenarioRetiredCheckIsNeverReusedOnFocusRecovery` |
| Focusing the closed non-granted surface transmits its own fresh check, and a valid response still supports a normal future entry | `scenarioRetiredCheckIsNeverReusedOnFocusRecovery` |
| Overlapping supported revalidations of one open entry share the one live check, and that live check keeps the dispatch gate closed | `scenarioCurrentCheckDeduplicationIsPreserved` |
| A Claim/Release pre-flight is still issued as its own check and settles independently of the shared background check | `scenarioCurrentCheckDeduplicationIsPreserved` |

## Negative controls (test discrimination)

The harness can rebuild the hook at bundle time with one superseded defect reinserted, in a **test-only**
module instance — the file on disk is never modified, so a control cannot drift from it and nothing has
to be restored afterwards.

```bash
OE_ENTRY_COMMIT_CONTROL=render-phase \
  node frontend-web/browser-verification/operations-entry-commit/run.mjs
OE_ENTRY_COMMIT_CONTROL=workspace-open-render-phase OE_ENTRY_COMMIT_SCENARIOS=3 \
  node frontend-web/browser-verification/operations-entry-commit/run.mjs
OE_ENTRY_COMMIT_CONTROL=stale-inflight-sharing OE_ENTRY_COMMIT_SCENARIOS=7 \
  node frontend-web/browser-verification/operations-entry-commit/run.mjs
```

`render-phase` (a render pass advances committed entry ownership and retires the still-committed entry's
checks) fails exactly on the invariants it was written for — 17 checks / 4 failures in scenario 1:

* `the discarded render did not abort entry A’s live verification — aborted=true`
* `entry A is admitted by its own verification after the discarded render`
* `entry A performs exactly one protected read, after its verdict — expected 1, saw 0`

and, in the committed-transition scenario, entry B is never verified or admitted.

`workspace-open-render-phase` (a render pass mirrors the *requested* `workspaceEntry` prop and the
render-pass state into refs, which is the superseded input of the focus/visibility decision) fails on
the revalidation invariant — 22 checks / 6 failures in scenario 3 alone:

* `the focus event re-verifies the committed entry A` — the discarded exit render suppressed the
  committed entry's revalidation, exactly as reported
* `the fresh verification carries the signed-in credential — expected "token-entry-a", saw null`
* `the denial withdraws entry A’s protected content` and `the committed authority is restricted —
  expected "restricted", saw "granted"` — the denial was never even fetched
* `the committed authority is no longer admitted` and `the dispatch gate stays closed after the denial` —
  entry A stayed admitted and dispatchable after a denial that the revalidation would have delivered

Running scenarios 3, 4 and 6 together under that control reports 62 checks / 17 failures, including
`a speculative open render does not activate workspace polling — expected 1, saw 2`.

`stale-inflight-sharing` reinserts **both** halves of the superseded sharing model: eligibility for
reusing an already-issued check is decided from the cached in-flight pointer alone with no consult of
the pending-check registry, and a retirement leaves that pointer dangling at the retired check. It fails
scenario 7 with 16 of 33 checks failing, starting with exactly the reported defect:

* `the committed close transmits a fresh check instead of adopting the retired one — capability
  requests: [{"token":"token-entry-a","aborted":true,"released":false,"settled":false}]` — the only
  transport that exists is the retired, aborted one, so no fresh capability request is transmitted
* `focus adopts the genuinely current check instead of duplicating it — expected 2, saw 1`
* the whole recovery chain then collapses: no fresh check, no admission, and `exactly one protected read
  ran, for the admitted entry — expected 1, saw 0`

Scenario 8 still passes under `stale-inflight-sharing`, because the checks it shares are genuinely live —
scenario 7 is what isolates the defect. Running the *whole* suite under `render-phase` reports 147 checks
/ 31 failures: that defect advances the entry transition during render, so the closed surface's own
verification effect never runs and scenario 7 sees only the retired, aborted transport.

The default run (control off) passes all checks, which is what makes the pair discriminating rather than
merely descriptive.

## Not covered here

* A live Spring Boot + PostgreSQL backend. This harness proves the React commit-phase lifecycle and the
  client's own traffic; the backend remains authoritative for authorization and ownership.
* The Claim / Release mutation lifecycle, which is exercised against the real application in
  `../operations-workspace/run.mjs` (scenarios `abortedMutationCheckNeverBlocksNextEntryClaim` and
  `abortedMutationCheckNeverBlocksNextEntryRelease`).
* A browser-produced focus or visibility transition. This environment cannot produce one: probing
  Chromium (headless and headed) showed `window.blur()` is a no-op, activating a sibling tab through
  `bringToFront()` leaves `document.visibilityState` at `visible` and fires no `visibilitychange`, and
  `Page.setWebLifecycleState` changes neither. The scenarios therefore dispatch a **real DOM event**
  (`focus` on `window`, `visibilitychange` on `document`) through the production listeners while the
  browser's own, un-stubbed `document.visibilityState` (`visible`) is read by the production guard. The
  handler and the state it consults are real; the trigger is harness-driven, and the `hidden` branch is
  not exercised here.
