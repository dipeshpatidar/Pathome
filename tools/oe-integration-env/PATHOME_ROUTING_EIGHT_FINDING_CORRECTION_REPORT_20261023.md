# PATHOME — ROUTING & OWNER PERMISSIONS EIGHT-FINDING CORRECTION

Date: 23 October 2026 (IST)

Branch: `feature/phase2a-oe-ui-integration`

HEAD: `a943775e07a153087d0b38c315db20e18b30aec5`

## A. Source baseline

- Preserved branch, HEAD and all inherited dirty work.
- Empty Git index; no stage, commit, push, merge, PR, reset, stash, clean or branch switch.
- Actual pre-report status: 79 modified status entries and 175 untracked status entries.
- Read the completed routing implementation ledger, current tracked/untracked source, independent
  correction package, frozen Staff/City/Team architecture, Fast & Safe protocol and solution-first
  review rule.
- Assessed both canonical trackers read-only at the approved hashes.

## B. Corrected findings

### 1. Routing diagnostic authorization — corrected

Previous failure: routing-only task list/count/status/recheck/candidate/manual-route paths authorized
from Request City without consistently requiring readiness, enrollment and canonical locality-city
agreement. Enrolled but unready work could be promoted; wrong-City metadata/counts could leak.

Correction:

- Added one source-sensitive scope predicate for routing tasks and routing-only Requests.
- Scoped task SQL filters city, readiness and canonical locality-city equality before pagination/count.
- Direct task reads and rechecks repeat the visibility check from the locked evidence.
- Routing-only candidate reads and manual routing require both durable enrollment and readiness.
- Recheck locks Request, task, Listing and locality before requeue; manual route re-reads source
  evidence after Listing/locality locks.
- Global Staff Admin diagnostic and separately authorized City Team Admin reconciliation paths remain.

Changed: `VisitRequestRoutingTaskRepository`, `IntakeRoutingAuthority`,
`VisitRequestRoutingTaskService`, `OperationalIntakeRoutingRepository`,
`OperationalIntakeRoutingReadService`, `OperationalOwnershipService`.

Discriminators: valid ready C1 succeeds; wrong locality City, null locality, unready and unenrolled
work are concealed independently. SQL visibility constants are asserted separately.

Residual: genuine PostgreSQL execution is blocked.

### 2. Unclaimed-work SQL clock reference — corrected

Previous failure: `authorized_teams` referenced `request_clock.db_now` without a FROM/JOIN edge, so
the SQL was invalid.

Correction: `authorized_teams` now starts from the single `request_clock` CTE row and all page,
window-count and out-of-range fallback paths share that predicate block.

Test: static SQL discriminator asserts the clock join, both effective-time predicates and absence of
the invalid qualified reference.

Residual: query execution and page/count consistency remain BLOCKED until a genuine authorized
PostgreSQL target exists.

### 3. Session and authority fencing — corrected

Previous failure: routing and staff clients checked session identity before JSON parsing, then could
return parsed protected data after a token/account change. Owner UI could also publish stale
readbacks/messages.

Correction:

- Capture one exact session for a logical operation and reuse it for every request/readback.
- Check before dispatch, after headers, after success decode, after error-body decode and before
  publication/finalization.
- Owner and routing components subscribe to session invalidation, abort pending work and clear
  protected state.
- Routing generation/authorization generation fences delayed page, candidate, readback and mutation
  responses.
- Recovery/readback never begins under replacement credentials.

Tests: acquired errors and success bodies after account/token replacement; A→B→A invalidation;
held routing and Owner browser responses; delayed mutation/readback paths.

### 4. City/Team permission picker — corrected

Previous failure: changing City could leave an old Team selected until asynchronous options loaded,
allowing confirmation for City B to submit a City A Team.

Correction: City changes immediately clear Team, options, review and confirmation; Team loading is
bound to exact City/generation/session; late responses are ignored; server-returned Team City must
match the requested City; review is blocked while loading, failed or empty; review captures an
immutable staff/capability/scope/City/Team/dates/reason snapshot.

Tests: delayed old-City response, B response, empty Teams, failed Teams, payload-vs-confirmation
equality, rapid double confirmation.

### 5. Truthful Owner permission feedback — corrected

Previous failure: success text was emitted after HTTP acknowledgement even when readback was empty
or mismatched; browser dates alone labelled facts active; reasons were fixed.

Correction:

- Create/revoke responses now return and identify the exact authoritative grant.
- Readback must contain the exact grant identity/scope/time shape before success.
- Empty, mismatched, failed and unavailable readbacks produce uncertain/error results without replay.
- Lost create/revoke acknowledgements are reconciled from exact readback.
- Server time is used for future/expired wording. Without it, date status is labelled unverified.
- Facts are labelled recorded/historical; this screen does not claim effective access.
- Bounded operator reason codes are entered and submitted for create and revoke.

Tests: empty/mismatched readback, lost create/revoke acknowledgement, inactive/future/expired/revoked
presentation, exact snapshot matching and server/browser-time separation.

### 6. Routing/coverage/oversight pagination — corrected

Previous failure: all three loaders requested only page zero; records beyond 20 were unreachable;
coverage overlap was inferred from one page.

Correction:

- Added accessible Previous/Next controls and current/total page feedback.
- Page loaders use generation fencing and clamp an emptied final page.
- Mutations reload the current page and clamp when the population shrinks.
- City/filter changes reset coverage page state.
- Added a scoped locality-specific active-coverage lookup so overlap disclosure is independent of
  the current coverage page.

Tests: record 21 reached and acted on for routing, coverage and unclaimed work; final-page clamping;
overlap evidence deliberately present only in the authoritative locality lookup.

### 7. Coverage create must not restore — corrected

Previous failure: create silently reactivated an existing retired pair.

Correction: create is create-only. Any existing active or retired pair is a conflict; restoration is
available only through the existing versioned state command.

Tests: active and retired pair create conflicts; no save occurs; entity/test evidence preserves
explicit restore semantics.

### 8. Bounded retry diagnostics — corrected

Previous failure: `recordTechnicalFailure` started a new transaction and took a task lock without
the command-local bounds.

Correction: bounds are applied before the retry task lock. Control, routing recheck and coverage
create/state commands now also apply bounds before blocking guards. Bounded-wait failures are
translated to the existing sanitized recoverable 503 where appropriate.

Tests: InOrder proves bounds precede the retry lock; completed tasks stay completed; manually or
otherwise terminal tasks are not reopened.

Residual: held-lock PostgreSQL barriers are BLOCKED.

## C. Lock-order disposition

Risk is structurally confirmed from source, not claimed as a reproduced database deadlock.

Observed relevant graph before correction:

```text
worker: Request/task -> shared control -> City -> Team -> Listing -> Locality
coverage: City -> Team -> profile/grant guards
global disable: profile/grant evidence -> exclusive control
```

This formed the source-identified control→City / City→grants / grants→control cycle.

Smallest correction:

```text
global disable: nonlocking authority check -> exclusive control -> actor evidence -> fresh authority -> update/audit
worker: Request/task -> shared control -> City -> Team -> Listing -> Locality
coverage/manual route: City -> Team -> profile/grant
```

No inverse path now acquires control after profile/grant evidence in the affected writers. The
control writer does not acquire Request/Session/City workload locks. Genuine three-party barrier,
audit rollback, finite lock wait and phantom-route discrimination remain mandatory PostgreSQL gates.

## D. Verification executed

- Focused backend Maven tests: **39/39 PASS**, 0 failures, 0 errors, 0 skips.
- Affected frontend Node tests: **21/21 PASS**.
- Production TypeScript/Vite build: **PASS**.
- `git diff --check`: **PASS**.
- `git diff --cached --check`: **PASS**; empty index.
- Controlled Google Chrome + production bundle: **58/58 PASS** at 320/375/390/430/768/1024/1280/1440
  px, with no horizontal overflow and 44px touch targets/16px editable controls.
- Browser coverage included held account-switch responses, record 21 routing/coverage/oversight,
  final-page clamp, delayed old-City Team response, empty/failed Team options, exact payload review,
  double confirmation, empty readback, and lost create/revoke acknowledgements.
- Broader all-frontend run: 1,081 tests, 1,070 pass, 7 failures, 4 existing TODO. All seven failures
  reproduce in the unchanged `operationsAvailabilityDraftLifecycle.test.mjs` alone and are outside
  this correction's files. They are not claimed as passing or fixed.

Prior developer evidence remains retained but is not relabeled independent acceptance.

## E. Blocked genuine verification

- Flyway V54 clean/upgrade and actual PostgreSQL query execution.
- Exact/count/page consistency against PostgreSQL.
- Real row-lock and three-party deadlock barriers.
- Held Request/City/task/target locks and retry diagnostic timeout behavior.
- ORM/Spring application startup against a disposable schema.
- Real React→Spring Boot→PostgreSQL journeys.
- Physical iPhone/Safari and VoiceOver/full WCAG acceptance.
- Independent GPT-6.1 Sol High acceptance.

No disposable PostgreSQL target was designated, so no write-bearing database test, migration or
protected-database access was attempted.

## F. UI/UX acceptance

- Responsive controlled Chromium: PASS at all eight required widths.
- Horizontal overflow: none measured.
- Touch target and editable-font checks: PASS.
- Accessible page-navigation names and visible page status: controlled PASS.
- Loading, empty, failed, stale, review, conflict, uncertain and retry feedback: controlled PASS.
- Reduced-motion CSS and minimum target rules are present; full reduced-motion visual acceptance is
  NOT TESTED.
- Full keyboard-only traversal and screen-reader acceptance are NOT TESTED.

## G. Known remaining defects

No new in-scope product defect was established during this pass.

Unrelated existing failure class:

- `operationsAvailabilityDraftLifecycle.test.mjs`: 45 pass / 7 fail in isolated execution. These tests
  and their implementation were not modified by this package. A future correction would require a
  separate bounded investigation; it is not part of this approval.

The genuine PostgreSQL requirements in section E remain acceptance blockers, not PASS results.

## H. Tracker assessment

Cross-Role tracker SHA-256 verified:
`2eaebf3a8cefd5a16e6e2495e01961f7270e13174647d1efd25b17f912a5e77b`.

Proposed successor evidence only:

- Retain DEP-050/051 completed foundation.
- Retain DEP-054 as a future Astra governance gate.
- Record this correction as implemented/agent-tested/controlled-browser-verified, not independently
  accepted.
- Keep OE #11/#19 open until connected acceptance.
- Keep the latest independent FAIL and this correction status distinct and dated.

Visit Session tracker SHA-256 verified:
`f600520dfd9114988087f9deb4c9d4151dd7deee1d5a9ba0523d76b26b3de160`.

Proposed successor evidence only:

- Preserve D1–D6 and Phase 2A history.
- Add the correction/re-review milestone.
- Preserve D-C, G10, Web V1, native and all unrelated gates.

Neither workbook was edited.

## I. Deadline impact

- Full Web V1, 31 October 2026: **HIGH RISK / NO GO** until genuine PostgreSQL/connected acceptance,
  remaining Web V1 workstreams, device/release gates and independent review close.
- Native Tenant/GE, 15 November 2026: **AT RISK**.

This package removes the evidenced routing/permission source blockers and advances the critical path,
but it does not establish genuine integration or release readiness.

## J. Final STOP status

Corrected source is ready for focused independent GPT-6.1 Sol High re-review.

A separately designated disposable PostgreSQL acceptance target is the main remaining verification
blocker. STOP after this report.
