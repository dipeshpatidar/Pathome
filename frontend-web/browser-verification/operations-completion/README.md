# OE completion browser verification (Phase 2A F1–F6)

One consolidated pass over the completed Operations Executive portal. It runs the **real built app**
(`frontend-web/dist`) in Chromium with every HTTP request answered in-process by the controllable
stub in `run.mjs`: no server port, no network, no module mocking. `localStorage`, window events, the
fetch layer, the shared session/ownership lifecycle and the production components are the real ones,
so results verify **UI behaviour and the exact network traffic**, not a live Spring Boot/PostgreSQL
deployment. All evidence from this harness is therefore labelled CONTROLLED.

## Running it

```bash
npm --prefix frontend-web run build
node frontend-web/browser-verification/operations-completion/run.mjs
```

`ONLY=<substring>` runs a single scenario while iterating. The script prints one line per check and
exits non-zero on any failure. A full pass takes well under a minute; anything still pending when the
9-minute wall-clock deadline is reached is reported as `NOT TESTED` rather than passed.

## Targeted correction scenarios

`ONLY=correction` runs only the scenarios added by the Phase 2A final engineering corrections, so the
changed workflows can be verified on their own without repeating the full journey:

| Correction | Scenario |
| --- | --- |
| P0-2 / P1-4 Blank required reason never reaches the booking; a complete scheduled Session is required for success | `correctionSchedulingResultAndReason` |
| P0-2 A confirmed booking whose follow-up queue refresh fails keeps the booking and reports the failed refresh separately | `correctionRefreshFailureAfterBooking` |
| P1-3 A pending ranked read survives Browse and Search starting after it | `correctionConcurrentReadFamilies` |
| P1-5 A delayed contact reveal cannot repopulate the panel after the record version changes | `correctionContactRevealRace` |
| P2-6 One continuation request per same-tick double click; failure/retry and query change still work | `correctionSearchLoadMore` |
| R1 A denied target (fresh-read 404, mutation 403/404) withdraws the revealed detail and contact, **before** the reconciliation it triggers is awaited | `correctionDenialWithdrawsRevealedRoot` |
| R1 A denial only ever withdraws the exact root instance its operation targeted: an unrelated record opened during a held command, and a newer mount of the same record, both survive it | `correctionDenialTargetIdentity` |
| R1 A refusal awaited across a held reconciliation is announced only in the display context it describes: #4109, a newer mount of #3108, and a same-id Request root all keep their own state and receive no obsolete notice | `correctionStaleRefusalNoticeRace` |
| F6 The scheduling drawer's own 44px close control is the real pointer hit target at 1280×900 (above the global header), and Escape and mobile close still work | `correctionSchedulingCloseHitTarget` |
| R3 An unusable booking `200` is reported as unknown, never as a booking — including an impossible calendar date and a one-nanosecond mismatch | `correctionMalformedBooking200` |
| R4 A candidate reviewed at v6 is never booked against a changed v8 itinerary | `correctionEvaluationVersionBinding` |
| R5 A held failure continuation cannot write into the context that replaced it | `correctionStaleFailureContinuation` |
| R6 Exactly one Ground Executive picker panel is rendered and reachable per tab | `correctionInactivePickerTabs` |
| R7 A still-linked Request originates a replacement stop after its direct stop is removed | `correctionOriginatingRequestAfterRemoval` |
| R8 Permitted read-only contact and availability reads, and no editing outside DRAFT | `correctionReadOnlyPermittedReads` |
| R9 An expired discovery cursor restarts from the first page instead of being resent | `correctionExpiredCursorRecovery` |
| R10 The frozen right-aligned 560px, full-height scheduling geometry at 1280px and 390px | `correctionFrozenSchedulingGeometry` |
| R11 Hiding the document clears protected contact and invalidates a pending reveal | `correctionContactClearedWhenHidden` |
| C1 A confirmed success whose display context was replaced is never published into the context that replaced it: a booking, an availability update and a same-root remount | `correctionStaleSuccessContext` |
| C2 A confirmed claim never navigates over a newer root, and a confirmed release never clears one | `correctionClaimReleaseContext` |
| C3 The legitimate current-context completions keep their authoritative outcome, their transition and their truthful follow-up report | `correctionCurrentContextSuccessPreserved` |

The Load More check dispatches two clicks in the same tick, because two sequential Playwright clicks
would model a deliberate second request rather than the double-click race.

The R1 check arms `stub.holdDenialReconciliation`, which holds the first authoritative queue read that
follows a denial until the check releases it, and asserts the withdrawal *while that read is still
unresolved* — including a lessor-contact reveal that is delivered late, after the root is gone. A
check that waited for the post-refresh notice would pass with the defect present, because that notice
is written after the refresh; the held-reconciliation form fails without the fix (verified as a
negative control: 13 checks fail when the withdrawal is moved back after the await).

The R1 target-identity check arms `stub.holdResponse`, which holds one specific response (the
pre-command read, the availability mutation, or the scheduling selection) until the check releases
it. Sol's reproduction is driven exactly: dispatch against Session #3108, open the unrelated Session
#4109 while the answer is still pending, release it as 403/404, and assert — while the queue
reconciliation is also held — that #4109 keeps its detail, its controls and its state, and that
nothing of the refusal is applied to it. The same held-command shape covers a fresh-read 404 that
transmits nothing and a navigation-away-and-back that re-opens #3108 in a new mount context, whose
freshly revealed contact must survive the older denial. The negative control (the former
wrong-target withdrawal restored) fails 35 of this scenario's 64 checks while the pre-existing R1
scenario still passes its 34, so the regression is specific to the target-identity defect.

The R3 check runs Sol's original unusable body plus the two strict-timestamp cases (an impossible
calendar date and an instant one nanosecond away from the submitted one) through the real drawer,
asserting the drawer's outcome, the exact evaluated instant submitted, exactly one POST and the safe
read-only recovery.

The R1 stale-notice check drives Sol's exact reproduction. The mutation answers `403` while the
response is held, the refused #3108 detail is withdrawn, the authoritative reconciliation is held
open, and the OE then opens Session #4109 — with its own independently authorized contact — before the
older read is released. The decisive assertion is made *after* that release: #4109 must keep its
detail, its contact and its controls and receive no #3108 notice. The same held shape covers a
re-opened #3108 (a newer mount generation) and a standalone Request that carries the refused
Session's numeric id as a different root type. The negative control (the former pre-await publish
verdict restored) fails 4 of this scenario's 45 checks — the obsolete refusal is announced in all
three replaced contexts — while the corrected source passes all 45.

The F6 check measures the close control that is actually rendered, hit-tests nine points inside it
(the centre plus a quarter-target ring, because the 44px control is a 10px-rounded square whose
bounding-box corners lie outside its painted area), requires every one of them to resolve to the
control and never into `header[data-pathome-header]`, and then clicks the measured centre with the
mouse. It also proves the header's own controls are covered while the overlay is open, that an
ordinary click aimed at the header never reaches it, that focus returns to the opener after both the
pointer and Escape closes, and that the 390px sheet fills the viewport, keeps its 44px target and
closes by pointer. The negative control (the scrim restored to `z-index: 60`) fails 7 of its 20
checks: every sampled point hits the header and the pointer click does not close the drawer.

The C1 check holds one successful mutation open (`stub.holdResponse`) while the OE leaves the record it
belongs to: the booking's drawer is closed and the unrelated Session #4109 is opened, the availability
PUT is held while #4109 is opened, and a same-root remount re-reads a newer snapshot that the held,
older answer must not replace (`stub.mutationResponseOverride`). In every case the command is
transmitted exactly once, its business success is still announced in the record's own name, and #4109
keeps its own detail, itinerary, controls and contact — with nothing of #3108 in that workspace region
(`workspaceRegion`, so an app-level confirmation toast can neither satisfy nor break a content check).
The negative control — the display verdict removed from both authoritative apply helpers, i.e. the
former unconditional application restored — fails 5 of this scenario's 26 checks and then breaks the
run's own navigation, exactly as the defect predicts.

The C2 check holds the claim's `204` and the release's `204` open while the OE opens #4109. The claim is
started from the list (no mounted detail of its own) and its confirmed navigation must not replace the
newer root; the release's confirmed drop must not clear it. The negative control — the display verdict
removed from `openDetail` and from the release's drop — fails 8 of this scenario's 18 checks: the
claimed Request replaces #4109 and the release clears it.

The C3 check preserves the other half of the contract: a coordination that legitimately replaces its
own Request with the Session it created (with a deliberately failed follow-up queue refresh), a normal
booking with its authoritative drawer outcome and read-only session, and a normal availability update.

## Coverage map

| Approved requirement | Scenario |
| --- | --- |
| Capability admission and the real queue reads | `fullJourney` |
| F2 Claim a claimable Request (204, reason code, re-read version) | `fullJourney` |
| F1 Canonical lessor contact on demand, call link, hide, no storage of PII | `fullJourney`, `revokedContact` |
| F2 Coordinate into a new DRAFT session with the exact null-target body | `fullJourney` |
| F3 Derived-stop choice from published rental discovery | `fullJourney` |
| F4 Complete window, confirmation-without-window, update-only transition | `fullJourney` |
| F3 Exact reorder of every active item | `fullJourney` |
| F5 Bounded ranked read with no query parameters | `fullJourney` |
| F5 Cursor Browse, including an eligible-free page that still has a continuation | `fullJourney` |
| F5 Search continuation with a truthful loaded count | `fullJourney` |
| F5 Choose → Review → authoritative Result, tenant confirmation from the returned session | `fullJourney` |
| F5 A booked session becomes read-only | `fullJourney` |
| F5 Stale version (409) refreshes and re-reviews without replaying the command | `staleSelection` |
| Uncertain outcome: lost response is reported unknown and never resent | `uncertainSelection` |
| F1 A refused contact read reveals nothing and states the truth in place | `revokedContact` |
| Stale-identity suppression: account switch during a booking | `accountSwitchDuringBooking` |
| F6 320/375/390/430/768/1024/1280/1440 overflow, 44px targets, 16px mobile editors, Escape focus restore | `responsive` |
| F6 The frozen drawer is closable by an ordinary pointer at 1280×900 and at 390px, without the global header intercepting it | `correctionSchedulingCloseHitTarget` |
| The shared async completion lifecycle: a confirmed success is reported but never published into a replaced display context (booking, availability, same-root remount, claim, release) | `correctionStaleSuccessContext`, `correctionClaimReleaseContext`, `correctionCurrentContextSuccessPreserved` |

## Not covered here

* Genuine backend integration (a real Spring Boot + PostgreSQL target). That requires separate owner
  authorisation of an identified safe environment; protected `pathome_db` is excluded.
* Physical-device gestures, soft-keyboard behaviour and screen-reader passes.
* Backend security/algorithm suites — historical acceptance remains closed and was not reopened.
