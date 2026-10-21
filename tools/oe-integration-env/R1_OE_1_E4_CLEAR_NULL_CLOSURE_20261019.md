# PATHOME — R1-OE-1 E4 CLEAR/NULL PROVENANCE CLOSURE

Date: 19 October 2026 (IST)  
Repository: `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`  
Branch: `feature/phase2a-oe-ui-integration`  
HEAD: `285c5844df4192bdec2c0ecc771bc9676e12dcb9`  
Status: **IMPLEMENTED — AGENT-TESTED — READY FOR ONE FOCUSED SOL ACCEPTANCE**

## A. Outcome

The confirmed P2 E4 defect is corrected: after an uncertain Clear commits all-null v4, a newer zone choice followed by a matching all-null Check now keeps the chosen zone and blanks every untouched temporal field with reset provenance. Obsolete pre-clear civil values are no longer presented as current truth, and no write is retried.

Production change is limited to `adoptAuthoritativeTenantAvailability` in `tenantAvailability.ts`. No API, DTO, migration, permission, scheduler, dashboard or editor behavior was changed.

## B. Architecture and correction

`adoptAuthoritativeTenantAvailability` now resolves one retained display zone and adopts each temporal field independently:

1. A genuine post-dispatch zone selection is retained and is never replaced by the acknowledgement zone.
2. A genuine post-dispatch start/end/preferred edit keeps its typed text and `EDITED` provenance, independently of other fields and of edit order.
3. An authoritative null with no genuine post-dispatch edit clears that field's date and time and resets provenance to `EDITED`.
4. An authoritative non-null field with no genuine post-dispatch edit is displayed from its authoritative instant in the retained zone and keeps `UNTOUCHED` provenance, so a later Save re-expresses the exact opening instant (fractions preserved) without moving it.
5. The immutable attempt, observed/reviewed versions, review latch, single identity-owned draft, generation guards and explicit Rebase/Discard semantics are unchanged.
6. Reconciliation still never issues a PUT.

Decision note: the existing editor enablement policy (Save is enabled whenever the form is editable and review is not required) was intentionally not changed. An empty post-clear Save is a local `missing` validation with no request. Disabling Save for blank required fields would be a separate UI-behavior change requiring owner approval.

## C. Failure-class results

| ID | Result | Evidence |
|---|---|---|
| C01 | PASS | Utility and recovery tests: Clear with no newer edit yields blank fields, reset null provenance, v4 reviewed baseline, no extra PUT |
| C02 | PASS | Mounted Chromium: 37/37 focused checks; broken-before 5 failures |
| C03 | PASS | Utility: start/end/preferred and mixed orders (5 cases × 2 orders); mounted genuine start+end edits survive the all-null Check; partial windows are refused by the builder |
| C04 | PASS | Utility: newer zone + matching non-null acknowledgement relabels authoritative instants and preserves exact fractional instants; existing zone controls 34/34 |
| C05 | PASS | 136/136 combined recovery/failure-class/utility tests; full tenant mounted 64/64 includes matching, mismatch, failed, unresolved and older-read behavior |
| C06 | PASS | Existing E1 held-v4/v5 mounted control: 11/11 |
| C07 | PASS | Zone controls 34/34 (Z01/Z03/Z05) plus existing utility rebase tests |
| C08 | PASS | Full tenant mounted 64/64 includes account switch, token rotation, remount, history navigation and multi-tab late reads; adoption guards unchanged |
| C09 | PASS | Two isolated mutation controls reproduced the required failures; copies removed |
| C10 | PASS | Focused mounted keyboard pass: Check, Rebase, Keep and Discard; blank fields and no-write assertions |

Existing negative coverage retained and green: no-op zone selection, four-null Clear, partial/reversed windows, Kolkata/Kathmandu/Dubai/New York, DST gap/fold, overnight and rollover windows, fractional precision, stale versions and lifecycle denial paths.

## D. Broken-before and mutation controls

Broken-before, production bundle before the fix (same new mounted scenario):

```text
16 checks, 5 failure(s)
C02 start time expected "" saw "08:30"
C02 start date expected "" saw "2026-11-21"
C02 end time expected "" saw "11:30"
C02 end date expected "" saw "2026-11-21"
empty Save never reached the truthful missing-fields guidance (invalid-date path)
```

Fixed-after: `37 checks, 0 failure(s), 0 not tested`.

Isolated mutation controls (temporary copies, restored source untouched):

```text
C09a CONFIRMED — restoring unconditional zone skip leaves start="08:30" end="11:30"
                 provenance={"start":"UNTOUCHED","end":"UNTOUCHED","preferred":"EDITED"}
C09b CONFIRMED — removing zone preservation replaces Asia/Dubai with Asia/Kolkata
                 on a matching non-null read
```

## E. Files changed

Production:

```text
frontend-web/src/utils/tenantAvailability.ts
```

Tests and verification:

```text
frontend-web/src/tests/tenantAvailability.test.mjs
frontend-web/src/tests/tenantAvailabilityRecovery.test.mjs
frontend-web/browser-verification/tenant-availability/run.mjs
tools/oe-integration-env/R1_OE_1_E4_CLEAR_NULL_CLOSURE_20261019.md (this report)
```

Source fingerprints:

```text
tenantAvailability.ts                          1dec5a85b0c9af310dee262d2e9c82e0f4b7f48886e0632e5292ba6c9b90e789
tenantAvailability.test.mjs                    82bab5fe4e439364dc6e5b9de4f59844fa59e06cfaf8e99fbca8a59b421f9e19
tenantAvailabilityRecovery.test.mjs            6deadf295ab191aadd0ac9fb2fa603384db8fdc6c52012f87323f7954fc4a86f
tenant-availability/run.mjs                    7ce10f2fe3c84dd2657503275d74c72d57db85f513a3f2b81b385a9fb56f712f
dist/assets/index-BhT-Vage.js                  b5f1a507ea05c3fbaf403572873be028f37f4c00c751d9e34ed16746103f3d95
```

## F. Verification executed

```text
node --test tenantAvailability.test.mjs tenantAvailabilityRecovery.test.mjs
  → 31/31 PASS
node --test tenantAvailability + recovery + operationsAvailabilityDraftLifecycle
           + operationsAvailabilityRecoveryFailureClasses
  → 136/136 PASS
ONLY=clear-null node .../tenant-availability/run.mjs
  → 37/37 PASS (fixed); 16 checks / 5 failures before the fix
ONLY=zone-intent node .../tenant-availability/run.mjs
  → 34/34 PASS
ONLY=e1-late-ack node .../tenant-availability/run.mjs
  → 11/11 PASS
node .../tenant-availability/run.mjs
  → 64/64 PASS
npm --prefix frontend-web run build (tsc + Vite)
  → exit 0, 2,043 modules, existing chunk-size advisory only
git diff --check
  → exit 0
```

Mounted request counts: C02 sent exactly one Clear PUT, one failed recovery GET and one explicit Check GET; no retry PUT. C03 sent exactly one Clear PUT, one explicit Check GET and one reviewed Save PUT. Keyboard Rebase/Keep/Discard sent zero writes.

NOT TESTED in this correction: the 425-check Operations harness, P2/B09 campaigns, backend/PostgreSQL suites, the nine-width responsive campaign, physical iPhone Safari, Firefox/WebKit, full screen-reader/WCAG and production external delivery. These are deferred by the bounded authority in the task; earlier accepted evidence is inherited, not re-claimed.

## G. Worktree and invariants

- Branch and HEAD unchanged; Git index empty; 73 pre-report status entries (74 with this report).
- All pre-existing R1/P2/B09/Navbar changes preserved; no stage, commit, push, merge, reset, stash, clean or PR.
- No tracker or `chat_gpt_changes` mutation, per task authority.
- No backend, database, migration, auth, dependency, deployment or external-message change.
- Server-authoritative versions, User identity, additive capabilities, City/Team scope, V37 time semantics, immutable attempt and no-auto-retry behavior preserved.
- No booking, credit, entitlement, audit or outbox side effect.

## H. Read-only tracker assessment

Canonical governance-pack copies matched the supplied 18 October fingerprints:

```text
Cross-Role:   39c55437b0ef3c0e82a91d0a79ee8fdac0ba4990fa70fe9f8ee9233ce1d34477
Visit Session: b2f52f6c6d26162b510435d568a433cd1d657f500cbd685690ee18fa29f5590a
```

Read only; no cell changed.

- Cross-Role OE 20Q: #3 `OPEN – API/UI`; #4 `PARTIALLY ACCEPTED`; #7 `APPROVED PRODUCT; CODE PENDING`; #16 `CONFIRMED GAP; CODE PENDING`. These 18 October statuses predate the R1 implementation evidence and remain unedited.
- Relevant DEP entries: DEP-034 `In Progress` (acceptance pending); DEP-012 `In Progress`; DEP-028/029/030/032 `In Progress`; DEP-039 `Partial`; DEP-040 `In Progress`; DEP-051 `Completed`.
- Web V1 gates: Batch A remains `Plan/Option A approved; implementation gated`; R1-OE-1 remains `R1 IMPLEMENTATION GATED`; Final iPhone/security #18/#20 `NOT TESTED / pending release`.
- Original 30-scenario continuity remains the inherited mapping in `R1_OE_1_RESUMED_CLOSURE_20261019.md` §J; this report does not rename or close those IDs.
- After independent acceptance, the appropriate append-only tracker update should record the E4 Clear/null correction and retain pending release gates.

Deadlines unchanged: Web V1 31 October 2026 remains HIGH RISK; native Tenant/GE 15 November 2026 remains AT RISK. This correction does not complete Web V1.

## I. Remaining boundary

No new production defect was established. Remaining blockers are independent Sol acceptance of this single correction and the deferred release gates listed above.

Next permitted workflow: one focused read-only GPT-6.1 Sol High acceptance of the E4 Clear/null correction. E1 and H6 remain closed; E2/E3/E5–E9 and P2/B09 are not reopened.
