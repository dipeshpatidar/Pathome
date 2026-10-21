# PATHOME — OE BATCH C F1–F5 COMPLETE CORRECTION & C26 DIAGNOSTIC HANDOFF

**Date:** 20 October 2026 (IST)
**Branch:** `feature/phase2a-oe-ui-integration`
**HEAD:** `285c5844df4192bdec2c0ecc771bc9676e12dcb9`
**Worktree:** 121 dirty entries before and after this package; Git index empty; no stage, commit, push, merge, PR, reset, stash, clean or deployment.

## 1. Source, version and approval preflight

- Verified the expected branch and historical HEAD.
- Preserved every inherited dirty file. This package changed only the files listed in §2.
- No canonical tracker workbook is present in this repository. The 19 October ChatGPT Library exports and Sol's complete canvas report were not available on disk, so tracker rows were not modified and are assessed only from the current request and retained handoffs.
- No conflicting active writer was observed in the touched files.
- The approved scope was applied: F1–F4 contract corrections, F5's narrow 320px correction, focused verification, bounded C26 diagnosis, and read-only genuine-integration preparation.
- No production/staging database, scheduler, outbox, worker, migration, dependency, media provider or canonical tracker was changed.

## 2. Implementation summary and changed files

### F1 — definitive denial withdrawal

`loadGeAvailability` now captures the exact mounted Session instance, credential, session epoch and authorization generation before its first await. A current-root `403`/`404` synchronously invokes the existing `withdrawDeniedRoot` path, which removes the protected detail, aborts sibling protected reads and closes the drawer. A current `403` additionally asks the authoritative capability endpoint for a fresh verdict; `404` never infers Team revocation or logs out. A `409` clears the stale evaluation and requires a fresh authoritative Session read. `401` clears the protected view and leaves sign-in handling to the existing session layer.

The informational dialog also clears roster, timing, cursor and view on `403`/`404`/`409`/`401`, keeps already-authorized rows only for transient continuation failures, and binds `loadMore` finalizers to their own generation.

### F2 — whole-envelope public plan status

`OperationsGeAvailabilityService.publicPlanStatus` projects `LOCATION_UNAVAILABLE_FALLBACK_USED` and `TRAVEL_ESTIMATE_UNCERTAIN` to `RECOMMENDATION_AVAILABLE`. A ready complete plan's page-level `NO_ELIGIBLE_GE` keeps the same neutral projection; null remains null. Legitimate readiness, bounded-evaluation and no-feasible-time distinctions are preserved. The five per-GE codes and DTO shape are unchanged.

### F3 — backend `displayName`

`toGeAvailabilityView` now reads `entry.displayName` into the internal `groundExecutiveName` property. The selectable-recommendation contract still reads `groundExecutiveName`. Availability fixtures were corrected to the real Jackson key; recommendation fixtures were not changed.

### F4 — owning-zone clocks

Availability timing now uses the existing `formatOperationsZonedDateTime`/`formatOperationsZonedWindow` utilities for tenant windows, tenant preferences, property windows and recorded provenance. `formatOperationsAvailabilityDateTime`/`formatOperationsAvailabilityWindow` add the actual offset only when a local time is repeated by a DST fold. `Evaluated at` uses the existing explicitly labelled IST detail formatter.

### F5 — compact phone layout

At `max-width: 359px`, the itinerary tool, reorder and availability-editor rows span the full item body below the property summary; their automatic minimum widths are released; the availability status pill may wrap; action text may wrap. DOM and focus order are unchanged, and controls retain 44px targets.

### Files changed in this package

- `backend/src/main/java/com/indore/pathome/spaces/service/OperationsGeAvailabilityService.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/OperationsGeAvailabilityServiceTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/OwnedOperationsHttpPostgresTest.java`
- `frontend-web/src/components/operations/OperationsGeAvailability.tsx`
- `frontend-web/src/components/operations/OperationsWorkspace.tsx`
- `frontend-web/src/components/operations/OperationsScheduleFlow.tsx`
- `frontend-web/src/components/operations/OperationsGePicker.tsx`
- `frontend-web/src/utils/operationsWorkspacePresentation.ts`
- `frontend-web/src/components/operations/operationsWorkspace.css`
- `frontend-web/src/tests/operationsBatchC.test.mjs`
- `frontend-web/browser-verification/operations-completion/run.mjs`
- This report and the `chat_gpt_changes` append.

## 3. Root causes and solution evidence

| Finding | Confirmed cause | Solution |
|---|---|---|
| F1 | Availability read failures propagated without invoking root withdrawal; roster/timing/cursor survived `403`/`404`. | Exact mounted-root denial withdrawal plus captured identity/authorization/root/version guards; `409` clears and requires a fresh Session read. |
| F2 | The response preserved the evaluator's GE-derived plan statuses. | Explicit whole-envelope public projection to the approved neutral feasible status. |
| F3 | Backend wire field is `displayName`; the availability mapper read `groundExecutiveName`. | Corrected only the availability mapper and its fixtures. |
| F4 | `windowLabel` formatted every instant in IST and appended an unrelated IANA label. | Reused owning-zone formatters; added offset only for DST folds. |
| F5 | The itinerary tools/editor and a 175px status pill forced the narrow grid column wider than the card. | Full-body spans at compact phones plus released minimum widths and wrap-capable status/action text. |

Pre-fix F5 measurement was reproduced exactly: 320px viewport, 21px document overflow, `.oe-detail-primary` and `.oe-item-tools` outside their available grid column. After the correction, the same journey and a long-content variant report zero overflow at 320, 375, 390, 430, 768, 1024, 1280 and 1440.

## 4. F1–F5 REQUIRED NOW matrix

### F1

| Row | Status | Evidence |
|---|---|---|
| F1.1 continuation 404 withdraws protected view | PASS | Controlled Chromium F1 scenario; negative control fails without withdrawal. |
| F1.2 Team A+B → B | PARTIALLY VERIFIED | Exact-root and authority-generation guards implemented and unit-tested; the live Team-change browser interleaving was not executed. |
| F1.3 delayed old 200 after denial | PARTIALLY VERIFIED | Post-await exact-root/identity guard and unit identity test; no direct delayed-200 browser run. |
| F1.4 old A denial after navigating to B | PARTIALLY VERIFIED | `sameOperationsRootInstance` unit test proves different roots/instances cannot match; live navigation interleaving not executed. |
| F1.5 account switch during load | PASS | Existing Batch C held-response account-switch check passed; the new loader drops stale completions. |
| F1.6 logout/login with new credentials | PARTIALLY VERIFIED | Credential/epoch binding and existing session tests; no new same-user re-login browser run. |
| F1.7 delayed v6 after v7/different root | PASS | Controlled 409 browser scenario clears all stale facts and performs a fresh Session read. |
| F1.8 same Session re-opened under new mount | PARTIALLY VERIFIED | Mount-generation identity unit test; live re-open interleaving not executed. |
| F1.9 unchanged grant refresh/reordered memberships | PARTIALLY VERIFIED | Shared capability/ownership tests; not separately re-executed for this dialog. |
| F1.10 failure-status handling | PARTIALLY VERIFIED | 403, 404, 409, 400 continuation and 500 tested; 401 and network interruption were not directly executed. |
| F1.11 role boundaries | PARTIALLY VERIFIED | No route or authorization-policy change; inherited cross-role suites were not rerun in this pass. |
| F1.12 remaining work and retries | PARTIALLY VERIFIED | Drawer closes and queues remain; a full post-denial retry journey was not executed. |

### F2

| Row | Status | Evidence |
|---|---|---|
| F2.1 neutral envelope across location quality | PASS | Projection unit matrix plus real HTTP/PostgreSQL assertion that the envelope never carries the GE-derived statuses. |
| F2.2 fallback versus normal route | PASS | Both evaluator statuses are projected; unit serialization control proves the raw status is absent. |
| F2.3 exact GE ID, size 1, mixed/exhausted pages | PARTIALLY VERIFIED | Existing HTTP pagination assertions; this pass re-ran the availability HTTP method. |
| F2.4 readiness/no-feasible/coverage/bounded distinctions | PARTIALLY VERIFIED | Existing HTTP assertions retain the permitted distinctions; not every combination was rerun. |
| F2.5 real Jackson serialization | PASS | Whole-envelope `ObjectMapper` test plus real HTTP response assertions. |
| F2.6 unknown status neutral | PASS | Null maps to null; no raw-code echo. |
| F2.7 audit failure/foreign root/revoked/malformed cursor | PASS | Within the re-run HTTP availability method, including the audit-blocked control. |
| F2.8 recommendation endpoint unchanged | PASS | No recommendation code or contract was modified; existing tests remain applicable. |

### F3

| Row | Status | Evidence |
|---|---|---|
| F3.1 real `displayName` renders | PASS | Real HTTP serialization plus controlled Chromium visibility of `Rahul Verma`. |
| F3.2 null/blank/missing/Unicode/long | PARTIALLY VERIFIED | Mapper preserves text; UI trims and falls back. Unicode tested; long/blank rendering not separately browser-tested. |
| F3.3 duplicate IDs deduplicated | PASS | Mapper unit test. |
| F3.4 hostile text escaped | PASS | Mapper preserves text; the component renders it as React text, never HTML. |
| F3.5 unknown reason neutral | PASS | `geAvailabilityResultCopy` unit test. |
| F3.6 fixtures use `displayName` | PASS | Availability fixtures corrected; recommendation fixtures unchanged. |
| F3.7 serialized response → mapper | PARTIALLY VERIFIED | Real HTTP response and mapper unit tests; one end-to-end JSON-to-mapper assertion was not added. |

### F4

| Row | Status | Evidence |
|---|---|---|
| F4.1 Kolkata/Berlin/New York/Kathmandu | PASS | Unit tests for the exact `2026-10-20T08:00:00Z` instant. |
| F4.2 browser zone differs | PASS | Explicit owning zone is used; controlled Chromium asserts Berlin output. |
| F4.3 DST fold/gap/offset/midnight/year | PARTIALLY VERIFIED | Fold and changing offsets tested; gap, midnight and year rollover were not separately executed. |
| F4.4 tenant preference/property provenance | PASS | Controlled Chromium asserts tenant, preference and property windows in Berlin. |
| F4.5 invalid zone/instant | PASS | Unit tests return null/neutral fallback. |
| F4.6 seconds/fractions preserved | PARTIALLY VERIFIED | Formatting does not mutate input; no UI seconds/fraction test. |
| F4.7 no booking/Session mutation | PASS | No mutation path touched; format helpers are pure. |
| F4.8 long dates readable at mobile width | PARTIALLY VERIFIED | 320px layout pass; a deliberately maximal formatted date was not separately exercised. |

### F5

| Row | Status | Evidence |
|---|---|---|
| F5.1 eight widths | PASS | 320/375/390/430/768/1024/1280/1440 zero overflow. |
| F5.2 standalone and embedded CRM/Admin | PARTIALLY VERIFIED | Standalone measured; embedded hierarchy was not executed in this pass. |
| F5.3 zero/one/many items | PARTIALLY VERIFIED | Two-item and long-content variants; zero/many variants not executed. |
| F5.4 long names/status/actions/contact | PASS | 96/64/120-character property fields, long action labels and revealed contact at 320px. |
| F5.5 contact/record/update/error/unavailable | PARTIALLY VERIFIED | Editor and contact reveal tested; validation-error and unavailable variants not separately measured. |
| F5.6 reorder/remove/edit accessible | PASS | Reorder controls visible with 44px targets; edit/update flow opened. |
| F5.7 locked/disabled truthful | PARTIALLY VERIFIED | No disabled-state change; not separately browser-tested. |
| F5.8 Tab/Shift+Tab/Escape/focus | PARTIALLY VERIFIED | Existing focus-trap check passed; full keyboard sequence not rerun. |
| F5.9 reduced motion/200% zoom/long errors | PARTIALLY VERIFIED | Reduced-motion CSS retained; 200% zoom not executed. |
| F5.10 desktop hierarchy unchanged | PASS | Narrow-only media query; desktop measurements pass. |
| F5.11 no hidden critical actions | PASS | All measured actions visible and reachable. |

## 5. Negative controls

- **F1:** temporarily disabling the current-root `403`/`404` withdrawal made both the continuation-404 and first-page-403 checks fail. Production logic was restored exactly.
- **F2:** the serialization test first serializes a deliberately raw evaluator status and proves the forbidden-status scan catches it; the projected envelope then passes the same scan.
- **F3:** a fixture containing only the former `groundExecutiveName` key maps to `null`, so the test fails if the mapper regresses to the old key.
- **F4:** a Berlin assertion fails if the IST clock is used; DST fold output carries `-04:00` versus `-05:00`.
- **F5:** the pre-fix 21px overflow was reproduced at 320px; the corrected CSS reports zero.

## 6. X1–X10 cross-family journeys

| Journey | Status | Evidence |
|---|---|---|
| X1 ownership transfer → continuation 404 → withdrawal | PASS | Controlled Chromium F1.1. |
| X2 Team authority change → no roster reappears | PARTIALLY VERIFIED | Authority-generation guard and unit identity; live Team mutation not executed. |
| X3 denial cannot close a newer unrelated root | PASS | Exact mounted-root identity unit test; browser navigation interleaving not executed. |
| X4 real `displayName` → visible name | PASS | HTTP serialization + controlled Chromium. |
| X5 non-IST backend window → owning-zone clock | PASS | Controlled Chromium Berlin assertion + unit matrix. |
| X6 fallback plan hides GE-derived metadata | PASS | Projection unit + real HTTP envelope. |
| X7 forged selection → 409/final authority | NOT TESTED | Inherited accepted B09 evidence retained; not rerun in this pass. |
| X8 narrow itinerary with long content | PASS | 320px long-content Chromium scenario. |
| X9 account switch during loading | PASS | Existing Batch C held-response check. |
| X10 Batch B/B09/V37 preservation | PARTIALLY VERIFIED | Source and contracts unchanged; the legacy full browser suite is not green because of inherited selector drift described in §12. |

## 7. Corrected C01–C26 ledger

| ID | Status | Evidence |
|---|---|---|
| C01 | PARTIALLY VERIFIED | Inherited candidate/timing fixtures; not rerun as a full matrix. |
| C02 | PARTIALLY VERIFIED | Mixed roster copy and nonselectable rows pass in focused Chromium. |
| C03 | PASS | Bounded continuation copy and existing cursor tests pass. |
| C04 | PARTIALLY VERIFIED | Search/cursor unit and HTTP validation retained; not every invalid combination rerun. |
| C05 | PARTIALLY VERIFIED | Multi-City/Team population tests retained. |
| C06 | PARTIALLY VERIFIED | Foreign/inactive/non-GE denial assertions retained in HTTP method. |
| C07 | PARTIALLY VERIFIED | Grant lifecycle tests retained; live Team-change dialog interleaving not executed. |
| C08 | PARTIALLY VERIFIED | Account-switch stale response PASS; live Team/ownership-denial interleaving not separately executed. |
| C09 | PASS | F1.1 ownership-transfer 404 and F1.7 409 scenarios pass. |
| C10 | PARTIALLY VERIFIED | Shift/unavailability/interval evidence retained. |
| C11 | PARTIALLY VERIFIED | Pending/incomplete property-window HTTP cases retained. |
| C12 | PARTIALLY VERIFIED | Disjoint/expired tenant-window evidence retained. |
| C13 | PARTIALLY VERIFIED | Dwell/max-duration boundary evidence retained. |
| C14 | PARTIALLY VERIFIED | Multi-stop order/travel evidence retained. |
| C15 | PASS | F2 projection proves GE-derived location/travel statuses cannot leak. |
| C16 | PARTIALLY VERIFIED | Fresh-selection authority retained from accepted B09 evidence. |
| C17 | PARTIALLY VERIFIED | Genuine contention evidence retained; not rerun. |
| C18 | PARTIALLY VERIFIED | Receipt/recovery evidence retained; not rerun. |
| C19 | PASS | Redaction, precedence and bounded-evaluation tests pass. |
| C20 | PASS | Real HTTP availability method re-run, including audit-blocked/no-store controls. |
| C21 | PASS | Operations work-list truth tests pass in the 370-test frontend suite. |
| C22 | PARTIALLY VERIFIED | Other-Team/Request privacy evidence retained. |
| C23 | PARTIALLY VERIFIED | Slow/failing/retry paths partly exercised; 401/network not directly run. |
| C24 | PASS | Owning-zone + DST-fold unit matrix and controlled Chromium. |
| C25 | PASS | Eight widths, long content, 44px controls and editor/contact actions. |
| C26 | BLOCKED / PARTIALLY VERIFIED | Batch A/B/P2/B09 cross-family preservation: core code unchanged, but the inherited full browser suite is not green. Scheduler order diagnosis separately remains OPEN. |

## 8. Genuine backend versus controlled browser evidence

- **Genuine HTTP/PostgreSQL:** `OwnedOperationsHttpPostgresTest#restrictedGeAvailabilityIsScopedPagedAuditedAndInformationalOnly` passed against the verified disposable database, including Jackson `displayName`, forbidden-status absence, pagination, audit/no-store and denial controls.
- **Controlled Chromium:** the real built React app ran with an in-process HTTP stub. This proves mounted UX and network traffic, not a live Spring/PostgreSQL journey.
- **Genuine backend scheduler cohort:** `realScheduleTransactionRollbackSuppressesNotificationsAndCommitPersistsThem` and `concurrentLateStartAndDownstreamStartRetryDeadlockInFreshTransaction` both passed together.

## 9. C26 causal classification

**Classification: C — insufficient evidence.**

Evidence recovered:

- No original failing XML/stdout, invocation order or environment record was present on disk; only the retained summaries naming one notification/outbox failure and one late-start retry failure.
- Both suspected methods subsequently pass in isolation and together.

Executed bounded cohort:

- Exact target: `pathome_oe_test_phase2a_20261015` on `localhost:5432`, `DISPOSABLE` sentinel present, `pathome` role connected.
- Command scope: only `realScheduleTransactionRollbackSuppressesNotificationsAndCommitPersistsThem` and `concurrentLateStartAndDownstreamStartRetryDeadlockInFreshTransaction`.
- Result: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`, 7.617s.
- Observed: `Rolling back transient visit lock failure on attempt 1 of 3`.
- Cleanup: no `visit_ops_test_*` or `owned_ops_http_test_*` schema remained.

Source-level harness risks (not proven causes):

- `deliverOutboxUntilSettled` observes only `QUEUED` and returns when a row is `FAILED` or `PROCESSING`; the worker's minimum retry backoff is 15 seconds, so a transient delivery failure can leave the helper's later `SENT` assertion false.
- The worker claims up to 50 rows per batch; a shared schema with accumulated fixtures can put older rows ahead of the test's new keys.
- No per-test truncation/reset exists; the class shares one schema for all methods.
- The late-start test coordinates a precise deadlock interleaving with 8-second latch waits and a retry-count assertion, which is sensitive to real PostgreSQL scheduling.

No production or test-harness correction was applied because B was not demonstrated.

## 10. Test-only harness correction

None. The F1/F3/F5 fixture corrections are limited to the availability wire key, owning-zone fixture and new Batch C scenarios. No worker batch size, authorization, retry semantics or production clock was changed.

## 11. Genuine React → Spring → PostgreSQL integration preparation

Read-only inspection covered `tools/oe-integration-env/phase2a.sh`, `phase2a/browser.mjs`, the shared guard/isolation libraries and the current environment contract.

Smallest safe proposal:

1. **Journey:** B01 login → B02 My Work → owned DRAFT Session → B07 property window → GE availability page 1 → ownership transfer → continuation 404 → protected withdrawal → unrelated Session remains usable → real `displayName` → Berlin/Kathmandu window → fallback-status privacy → stale 409 → denial 403 → 320px itinerary.
2. **Database/run:** one positively guarded `pathome_oe_test_integration` run with a fresh run ID, `DISPOSABLE` sentinel, current attestation, and one run-owned schema. Do not use the scheduling database designation for the genuine harness without explicit approval.
3. **Authentication:** per-run secrets, audited fixture roles, real JWT/login, two OE identities, Session ownership transfer through the API, and server-side grant changes.
4. **Transport:** real Jackson HTTP through the existing loopback proxy; no substituted status or body.
5. **Data cases:** named GE, non-IST tenant/property zones, ownership denial, stale version and foreign root.
6. **Audit:** assert `GE_AVAILABILITY_VIEWED` exists before disclosure and that an audit failure returns no roster.
7. **Booking truth:** fresh feasible selection, immutable receipt, one legal winner and no booking from the information view.
8. **Media:** either the approved disposable media account or a separately approved inert media composition. No provider or inert bean may be created under this package.
9. **Safety:** fixture provenance bound to the run ID, random schema ownership, bounded teardown only after attestation, raw evidence retained outside Git.
10. **New approvals:** live browser opt-in, disposable media destination, guarded database recreate/provision/attest, fixture-role authorization, and local build approval.

The current harness requires 82 API PASS records and same-run fixture provenance before it will start a browser scenario. That prerequisite is not met in this package.

## 12. Backend/API/privacy and responsive regression results

- Backend compile: PASS.
- `OperationsGeAvailabilityServiceTest`: 11/11 PASS.
- Real HTTP/PostgreSQL availability method: PASS.
- Frontend Operations Node tests: 370/370 PASS.
- Frontend production build: PASS (existing large-chunk advisory only).
- Controlled Chromium Batch C + F1 + F5 cohort: 50 checks, 0 failures.
- Full legacy completion harness: not green. 83 checks produced 29 failures after the focused scenarios. The first isolated failure is an inherited selector drift: older scenarios wait for a `Session #3108` heading while the current UI renders `Visit plan #3108`. This is outside the F1–F5 files changed here and was not repaired in this package.
- `git diff --check` and `git diff --cached --check`: PASS.

## 13. Disposable database identity and cleanup

- Database: `pathome_oe_test_phase2a_20261015` on `localhost:5432`.
- Sentinel: `pathome_oe_test_env` with `DISPOSABLE`, matching database name and guard version 1.0.0.
- App role: `pathome`; connection verified.
- Test schemas were UUID-named and dropped by their `@AfterAll` hooks.
- No production/staging database, migration, fixture outside the test schema, grant or service was mutated.

## 14. Source fingerprints

```
b5a05a79b4dff2095843b15c496114f2b905e706c450d3035bb4667affe6bcfa  backend/src/main/java/com/indore/pathome/spaces/service/OperationsGeAvailabilityService.java
88058f379513e33772f986dd01eb07d789fc38dfb31f5d91fe65057fcb989c1d  backend/src/test/java/com/indore/pathome/spaces/service/OperationsGeAvailabilityServiceTest.java
dd0d3234234a80f819db0ce8a89bd0e2d160843e079ffa76c66ae921605940dc  backend/src/test/java/com/indore/pathome/spaces/service/OwnedOperationsHttpPostgresTest.java
f6d016d552cffe03548610bb9462c8b1aefd257d65fc7aa378985590ceda9dbb  frontend-web/src/components/operations/OperationsGeAvailability.tsx
eb218f505dc43f8cf333f1d0722ad8934b28e48d81145bb8ec3ecfb4541f8e7e  frontend-web/src/components/operations/OperationsWorkspace.tsx
c25493f26b82692bda2b9d46872b9a5b95fb136d03e402358cc83f4f407453a3  frontend-web/src/components/operations/OperationsScheduleFlow.tsx
80757ed9890c3441bcec352355bcac221b85d1551d34e32838809ad352b039d8  frontend-web/src/components/operations/OperationsGePicker.tsx
9c30385a5f53401532f4cc7d68592ed3ac52cc15a09e6e6924d57ad376c4cdd6  frontend-web/src/utils/operationsWorkspacePresentation.ts
d3bb0f63ab9a11f4a96793294b6bfa5cdabbfd2302d000c763368f8079b0644c  frontend-web/src/components/operations/operationsWorkspace.css
5fb26b4ced63c7be97d2bf6f2cb0cee6438128adc139abf4f18e711947dd6dc8  frontend-web/src/tests/operationsBatchC.test.mjs
64baf794ab00b39a6e9ddceae050d6f49a5578a14c8dd7f3d1298df1b5f78f3e  frontend-web/browser-verification/operations-completion/run.mjs
```

## 15. Outstanding blockers, evidence gaps and release gates

- C26 scheduler order diagnosis remains **OPEN (C)**.
- The full legacy Chromium suite has inherited selector drift; it is not claimed green.
- Live Team authority change during a displayed roster, 401, network interruption, embedded CRM/Admin, zero/many itinerary items, 200% zoom, and physical Safari/VoiceOver remain evidence gaps.
- Genuine React → Spring → PostgreSQL integration remains blocked on the approved environment/media/run prerequisites.
- X7 forged-selection 409 and X10 Batch B/B09 preservation rely on inherited accepted evidence and were not rerun.
- No independent acceptance is claimed.

## 16. Recommended next step

One focused GPT-6.1 Sol High independent review of:

1. F1–F5 corrected source and tests.
2. The negative controls and the 50-check controlled Chromium cohort.
3. The real HTTP/PostgreSQL availability evidence.
4. The C26 OPEN classification and bounded cohort raw result.
5. The genuine-integration proposal and exact approval boundary.

Sol should decide whether the remaining PARTIALLY VERIFIED rows are acceptable evidence gaps or require additional execution before Batch C independent acceptance.
