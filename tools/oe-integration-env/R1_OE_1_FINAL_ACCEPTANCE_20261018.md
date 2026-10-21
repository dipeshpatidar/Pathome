# PATHOME — R1-OE-1 CONSOLIDATED CORRECTION & FINAL ACCEPTANCE EVIDENCE

Date: 18 October 2026 (IST)  
Repository: `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`  
Branch: `feature/phase2a-oe-ui-integration`  
HEAD: `285c5844df4192bdec2c0ecc771bc9676e12dcb9`

## A. Outcome and provenance

Classification: **IMPLEMENTED — AGENT-TESTED — READY FOR FOCUSED INDEPENDENT ACCEPTANCE REVIEW**.

- Initial dirty worktree: 68 entries, empty Git index.
- Final dirty worktree: 71 entries; the three additional entries are the new tenant mounted harness directory, the new persisted-receipt PostgreSQL test and this report.
- Existing P2/B09/R1/Navbar work was preserved. No unrelated file was reset, stashed, cleaned or overwritten.
- No stage, commit, push, merge, PR, deployment or tracker edit.
- No new API, DTO, migration, permission, product rule or production backend behavior.

Changed within approved scope:

```text
frontend-web/src/utils/tenantAvailability.ts
frontend-web/src/components/TenantDashboard.tsx
frontend-web/src/components/TenantAvailabilityEditor.tsx
frontend-web/src/tests/tenantAvailability.test.mjs
frontend-web/browser-verification/operations-completion/run.mjs
frontend-web/browser-verification/tenant-availability/run.mjs (new)
frontend-web/browser-verification/tenant-availability/genuine-runner.mjs (new)
backend/src/test/java/com/indore/pathome/spaces/service/OperationsTenantContactHttpPostgresTest.java
backend/src/test/java/com/indore/pathome/spaces/service/VisitSchedulingReceiptPostgresTest.java (new)
chat_gpt_changes
tools/oe-integration-env/R1_OE_1_FINAL_ACCEPTANCE_20261018.md (this report)
```

## B. E1–E4 production correction

### E1 — stale-write rebasing

Root cause: the history effect copied the newest version into the same field Save used as its expected version, so a dirty form silently changed write baselines.

Correction: the draft now separates:

1. newest observed authoritative facts;
2. the reviewed write baseline;
3. unsaved form/provenance;
4. the immutable uncertain attempt.

A newer snapshot while dirty/uncertain updates display facts only, latches explicit review, and never advances the submitted version. Further edits and zone changes keep the latch. Explicit rebase establishes the reviewed baseline. Pristine drafts adopt safe newer facts. Older history pages are ignored/merged without regression.

Evidence:

- Mounted v3→v4 sequence: form remains `11:00`, Save and Clear disabled, zero PUT.
- Rebase then Save: exactly one PUT with `expectedRequestVersion: 4`.
- Negative control on a private production copy restoring silent rebasing failed at the missing review boundary.

### E2 — cleared availability resurrection

Root cause: displaced-draft projection used `currentFacts?.field ?? opening.field`, restoring old values when the authoritative field was explicitly null.

Correction: an existing authoritative snapshot supplies all four fields exactly, including nulls. Opening facts are used only when no snapshot exists. Late older history cannot resurrect a cleared window.

Evidence: committed clear produced all-null projection on page zero and after displacement; no old `10:00–13:00` text returned.

### E3 — uncertain recovery ordering

Root cause: the catch path wrote `reconciliation: reading` first and then called a function that refused to start when it observed `reading`. Immediate updater execution produced a marker with zero GETs.

Correction: one synchronous reconciliation owner acquires the operation flight, publishes `reading`, starts the bounded guarded read once, and settles only the same identity/Request/generation/sequence. Save/Clear double actions become at most one operation.

Evidence:

- Committed response dropped: PUT 1, recovery GET 1, matched current state.
- Rolled-back response dropped: draft retained, mismatch/review required, no retry PUT.
- Recovery 503: readable unresolved state, explicit Retry Read, one new GET.
- Double Save / double Check in one turn: at most one PUT / one GET.

### E4 — one active draft

Correction: the per-Request map was replaced by one retained identity-owned draft. Dirty/pending/unresolved A cannot be silently replaced by B; the UI presents an explicit keep/discard choice. Pagination displacement retains A. Account change/unmount aborts and clears it.

Evidence: one form at a time, dirty A retained, explicit discard opens B, displacement keeps A, account switch clears A.

## C. H1–H9 Operations browser harness

The approved harness now maintains a controlled receipt registry keyed by actor, exact Session and command UUID. It:

- captures the exact six-field payload including a valid UUID;
- returns the original completed receipt on exact replay;
- rejects changed payload, missing/malformed UUID, wrong actor/root;
- models accepted, not-recorded, unknown/in-flight and denied receipt outcomes;
- never infers “no booking” from `409` alone;
- exposes an explicit original-command Retry only after receipt reconciliation and an unchanged owned DRAFT;
- holds the exact actor-bound receipt GET for H8;
- gives account B a coherent owned fixture instead of A’s coordinator identity.

Results:

- Required six groups together: **91/91 PASS**.
- One full harness confirmation: **400/400 PASS**, zero failures, zero untested, 101.5 seconds.
- Final-build re-run of the six required groups: 35+10+8+3+30+5 = **91/91 PASS**.

No test was deleted, skipped or weakened. Legacy malformed-success and nanosecond assertions remain strict.

## D. Database safety

Target: `pathome_r1_test_20261018` on `127.0.0.1:5432`, database user `dipeshpatidar`.

- Sentinel count: exactly 1.
- Run identity: `run-20261018-r1-5d15916404774680b6aae1ec41732b01`.
- Static/live guards verified exact database, loopback server, user, marker and run before JDBC/Flyway/DDL.
- Every test schema was UUID-named and test-owned; owner checks preceded cleanup.
- After runs, no `tenant_contact_http_test_*`, `tenant_availability_http_test_*` or `receipt_history_test_*` schema remained.
- Sentinel remained unchanged.
- No protected, shared or unrelated database/schema/service was mutated.

## E. Genuine integration evidence

### E5 — tenant-contact races

Executed PostgreSQL/HTTP suites cover:

- owner-scoped contact, privacy-safe denials, `no-store`, synchronous audit;
- transfer, revocation and staff deactivation in both orders with real blocker checks;
- newly added expired-grant-before-admission denial with no PII/audit and valid unexpired control;
- newly added governed Team/City lifecycle refusal under active operational drain, including real blocker evidence, while the authorized reveal remains successful.

Still missing for full E5 closure: reveal already waiting on the canonical lock at the exact moment an otherwise-valid grant expires. Expired-before-admission is proven; expiry-during-lock-wait is **NOT TESTED**.

### E6 — topology and transaction boundary

Real PostgreSQL/HTTP evidence covers:

- Session→Request lock order and concurrent Session schedule serialization;
- stale version conflict without mutation;
- linked/standalone lifecycle eligibility and clear persistence;
- unit evidence for bounded three-attempt retry, rollback signalling and `Propagation.NEVER`.

Still missing: a real three-attempt lock-time topology rediscovery/relink sequence with recorded attempt PIDs. No safe production fault hook exists; **NOT TESTED**.

### E7 — genuine lost tenant PUT

Real Spring Boot + PostgreSQL + Chromium runner passed:

- real Request creation through the API;
- real committed PUT whose response was dropped → one guarded recovery GET, correct saved values;
- real refused/rolled-back PUT whose response was dropped → retained draft and explicit review;
- recovery read failure → readable unresolved state and explicit retry;
- clear commit/drop → all four nulls persisted;
- late older history → no resurrection;
- page-zero displacement → exact draft retained;
- account switch → no old tenant state published.

Java gate: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`.

### E8 — persisted historical receipt

New PostgreSQL test inserted a historical receipt without `linkedRequestTimingFacts` and verified:

- exact read/decode;
- identical raw JSON and SHA-256 before/after;
- exact payload replay returns the same historical decision;
- changed payload conflicts;
- wrong actor cannot read it;
- historical SCHEDULED acceptance remains distinct from current CANCELLED;
- no decision/outbox/credit side effects.

### E9 — mounted UX/async

Passed: keyboard reach for zone/date/time/Save/Clear/Check/Rebase/Discard, focus after reconciliation failure and review, 320/390/1280 overflow, 44px targets, 16px mobile editors, reduced motion, long copy/error wrapping, displaced-request recovery.

Physical iPhone Safari remains a separate release gate and is **NOT TESTED**.

## F. Updated original 30-scenario matrix

The original IDs 01–30 are retained. This consolidated evidence does not rename them.

| IDs | Area | Status |
|---|---|---|
| 01–04 | E1 reviewed baseline / stale history / account generation | PASS (mounted + unit + negative control) |
| 05–08 | E2 clear/null/mixed-field/Request isolation | PASS (mounted + utility) |
| 09–12 | E3 scheduling order, deferred/immediate, retry/double action | PASS (mounted) |
| 13–15 | E4 one-draft, displacement, account cleanup | PASS (mounted) |
| 16–18 | E5 contact expiry/link/City-Team lifecycle | PARTIAL — expired-before-admission and lifecycle PASS; expiry-during-lock-wait NOT TESTED |
| 19–22 | E6 topology retry/rollback/ambient transaction | PARTIAL — lock/serialization/transaction boundaries PASS; real three-attempt topology rediscovery NOT TESTED |
| 23–25 | E7 genuine uncertain PUT | PASS |
| 26–27 | E8 historical persisted receipt | PASS |
| 28–30 | E9 mounted opening/identity/usability | PASS (physical device deferred) |

Inherited independently accepted R1/P2/B09 behavior remains inherited evidence; this report does not relabel it as newly executed.

## G. Regression and business functionality

- Full frontend: 956 tests, 952 PASS, 0 FAIL, 4 existing TODO.
- TypeScript and production build: exit 0, 2,043 modules; existing chunk-size advisory only.
- Backend focused suite: 60 executed, 0 failures/errors; default availability class has one browser-gated skip that was separately executed and passed.
- Tenant/Lessor capability, server-side authorization, V37 timezone/precision, B09/V50 receipts, OTP/entitlement/outbox seams and existing P2/legacy free-text paths were not redesigned.
- Tenant availability nulls remain authoritative and separate from original lessor/tenant free text and final Session schedule.

## H. Trackers and deadlines

Read-only workbook hashes remained:

- Cross-Role: `031590c74a80fdf9d324d53917fafde9a07145a90feca5407e9fb40b8757b3de`
- Visit Session: `345aa3d37e33f3a1db894818308125a2c9b908045e943309b56b6c5cb6a333ef`

DEP-034 remains In Progress with acceptance pending. B09 gate 55 remains implemented/verified with later correction history. No tracker cell was edited.

Proposed append-only update after independent acceptance: record E1–E4 corrected, H1–H9 durable and green, E5/E8 focused PostgreSQL results, E7 genuine browser result, and retain the E5/E6 gaps plus physical-device/browser/WCAG/deployment gates.

Deadline impact:

- Web V1 (31 Oct): risk narrowed; independent acceptance, the E5/E6 gaps and release gates remain.
- Native Tenant/GE (15 Nov): no new blocker introduced.

No MVP scope reduction is claimed.

## I. Remaining defects and exact next decision

1. **E5 expiry-during-lock-wait** — NOT TESTED. Add one real HTTP/PostgreSQL test that acquires the canonical lock before expiry, observes the blocked PID/blocker, expires the grant in a second transaction, releases the lock, and requires a privacy-safe denial with no new audit.
2. **E6 real topology rediscovery** — NOT TESTED. Add a test-owned fault seam that changes Request→Session linkage after unlocked discovery but before lock acquisition, records rollback/attempt PIDs, proves two retries resolve and three changes end in 409 with no partial effects. This must remain test-only unless a production hook is separately approved.
3. Physical iPhone/Safari, Firefox/WebKit, full screen reader/WCAG, external notification delivery and deployment remain separate release gates.

Severity: both remaining gaps are verification gaps, not newly proven production defects. No production mutation is recommended without independent review and, for a fault hook, explicit approval.

## J. Focused Sol acceptance handoff

Recommend one narrow independent pass covering:

1. E1–E4 dashboard state machine and mounted matrices;
2. H1–H9 receipt registry, H8 barrier and green six groups/full harness;
3. E5/E7/E8 primary PostgreSQL/browser evidence;
4. E9 targeted mounted UX;
5. the two explicit remaining verification gaps;
6. preservation of unchanged R1/P2/B09 behavior at affected seams.

Do not redo the entire historical architecture review or all earlier integration campaigns.
