# Pathome Engineering Constitution & Strict Development Rules

Applies to all contributors, maintainers, and coding agents across Pathome's Spring Boot backend, PostgreSQL persistence, web, mobile, and CRM applications. These are **STANDING PATHOME RULES** that govern all planning, architecture, implementation, code review, testing, UI/UX work, debugging, performance optimization, AI-agent handoffs, and future tasks. Apply requirements strictly to the affected scope; do not expand a task merely to retrofit unrelated code.

---

## Strict Development Rules — Mandatory

These twelve rules are mandatory for every future Pathome task.

### 1. First-Pass Completeness and Edge-Case Discipline
For every approved use case, deliberately identify and handle all relevant edge cases and failure modes **before and during implementation**. Do not implement only the happy path.
- **Edge cases to address where relevant:**
  - Input validation, boundary values, empty states, loading states, error states, and partial failures.
  - Network interruption, timeouts, API failures, duplicate requests, double click/tap, and idempotent retry/recovery.
  - Concurrency, race conditions, stale asynchronous responses, and optimistic locking/version conflicts.
  - Account switching, logout/login transitions, cross-user isolation, stale cache/state, and guest-to-authenticated transitions.
  - Authorization, object ownership, permissions, missing data, malformed data, and transaction boundaries with clean rollback.
  - Mobile/browser/device quirks, responsive behavior, keyboard/focus management, touch targets, and slow networks.
  - Large datasets, ordering, duplicate records, page refresh, back/forward navigation, and multi-tab synchronization.
  - Observability, structured logging, and debuggability where useful.
- **Objective:** Complete an approved use case end-to-end in one production-quality pass to eliminate subsequent bug-fix cycles.
- **Boundary:** If handling an edge case is necessary for the approved use case to be correct, secure, resilient, or production-ready, handle it within the task. If handling it requires a new business rule, workflow change, architectural modification beyond the approved design, naming change, or scope expansion, **stop and request approval**. Never silently invent product rules.

### 2. UX-First Development
For every user-facing use case, never start coding without first defining the intended user experience. Functionality alone does not constitute completion.
- **Quality target:** Premium, modern, intuitive, trustworthy, fast, responsive, accessible, polished, consumer-grade, top-tier real-estate product quality.
- **Mandatory UX considerations:**
  - User journeys, information hierarchy, navigation clarity, and refined micro-UX.
  - Loading experience, perceived performance, empty states, error states, and retry states with meaningful feedback.
  - Mobile-first constraints, responsive adaptation (320px to 1440px+), safe areas, and zero unintended horizontal overflow (`scrollWidth <= innerWidth`).
  - Minimum touch targets of at least 44px on interactive controls; editable controls on iOS must maintain an effective font size of at least 16px.
  - Keyboard navigation, visible focus indicators, screen reader accessibility (targeting WCAG 2.2 AA), and modal focus trapping.
  - Cohesive typography, deliberate spacing, alignment, iconography, and distinct destructive-action styling.
  - Purposeful transitions, smooth spring physics, reduced motion compliance (`prefers-reduced-motion`), and layout stability without jarring shifts.
  - Truthful copy without filler, developer notes, debug labels, or fabricated claims.
- **Rule against gimmicks:** Do not add visual clutter or arbitrary animations merely to appear premium. Premium means deliberate, clear, fast, and polished.

### 3. Credit and Token Efficiency (Resource Discipline)
Pathome AI and API credits are a constrained engineering resource. Every agent must actively minimize unnecessary model calls and token consumption.
- **Operational requirements:**
  - Reuse established repository context; inspect current Git diffs and branch status before repeating work.
  - Do not repeat already-proven audits, investigations, or broad whole-repository scans.
  - Search narrowly using targeted tools (`rg`, `rg --files`); avoid reading unchanged large files.
  - Avoid running repeated broad browser runs or rerunning identical passing test suites unnecessarily.
  - When resuming an interrupted task, **continue from the exact verified state**; do not restart analysis from scratch.
  - Stop investigating hypotheses the moment the actual root cause is proven.
- **Model routing principle:**
  - Use the cheapest capable model for routine, bounded, documentation, Git, or single-file changes.
  - Reserve premium reasoning models for genuinely difficult architecture, security, state isolation, concurrency, or high-risk cross-system changes.
  - Never trade engineering correctness or verification honesty for cost. The goal is **maximum first-pass correctness with minimum repeated model usage**.

### 4. Architecture Before Code
For every non-trivial approved use case, design the smallest robust production architecture **before writing code**.
- **Architectural checklist:**
  - Authoritative source of truth (PostgreSQL domain data vs. client state vs. transient cache).
  - Domain boundaries, User ownership, server-side authorization, and DTO/API contract boundaries.
  - Persistence invariants, transaction boundaries, concurrency controls, and idempotent side effects.
  - Caching policy: every cache must have an owner, justified contents, growth bounds, invalidation policy, and consistency behavior.
  - Stale-state protection, frontend state ownership, background processing, retries, and rollback safety.
  - Error classification, scalability, pagination, media handling, and backward/forward API compatibility.
- **Avoid:** Temporary hacks, duplicate sources of truth, tight role/state coupling requiring future rework, client-controlled ownership, and premature architectural overengineering.
- **Future alignment:** All architecture must stay compatible with Pathome's known trajectory: multi-city expansion, mobile-number OTP authentication, stable User identity, additive tenant + lessor dual capability, and scalable property/media pipelines.

### 5. World-Class Quality Bar and Self-Review
Execute every task to the standards of senior software engineering, software architecture, product design, UX, QA, security, and performance disciplines.
- **Pre-completion diff self-review checklist:**
  - *What can fail?* Are transient and permanent failures handled gracefully?
  - *What can race?* Are concurrent requests, rapid taps, and async responses protected?
  - *What can become stale?* Are cached data and component state properly refreshed or invalidated?
  - *Can one User see another User's state?* Is cross-user, cross-account, and tenant/lessor session isolation guaranteed?
  - *What happens on retry, refresh, or back/forward navigation?*
  - *What happens on a slow network or physical mobile device?*
  - *What happens with missing, empty, or oversized data?*
  - *Is the UI completely truthful?* Are metrics, badges, and copy 100% accurate?
  - *Does the interaction feel premium, responsive, and polished?*
  - *Did we modify anything outside the authorized scope?*
- Fix all in-scope defects prior to reporting completion.

### 6. One-Pass Development Objective
The engineering goal is to minimize iteration cycles and rework through rigorous upfront analysis:
```
UNDERSTAND → DESIGN → EDGE-CASE ANALYSIS → IMPLEMENT → SELF-REVIEW → FOCUSED TESTS → BOUNDED BROWSER/UX CHECK → COMPLETE
```
Never settle for a pattern of `IMPLEMENT → BUG → PATCH → BUG → PATCH → REDESIGN`. While no software is guaranteed defect-free, Pathome requires maximum reasonable first-pass correctness and explicit reporting of any remaining risks.

### 7. Verification Discipline and QA Honesty
Testing must be strictly proportionate to the modified behavior and risk:
- **Tiered verification hierarchy:**
  1. Focused unit tests for modified behavior (JUnit 5 for Java parser, service, and exception logic; targeted unit tests for frontend utilities).
  2. Affected-module tests when consequences extend beyond the immediate unit.
  3. Relevant build/compile checks (`mvn clean compile`, `npm run build`, `git diff --check`).
  4. Broader regression suites only when risk warrants it or upon explicit instruction.
- **Browser verification policy:**
  - Use browser automation only for large or critical UI changes requiring focused visual/behavioral validation.
  - Batch related changes first; execute one cohesive browser pass rather than testing every micro-edit.
  - Limit verification commands or cohesive batches to **10 minutes maximum**. If a check is expected to exceed 10 minutes, stop and provide exact manual steps, expected results, and verification criteria.
  - Minimize physical-device verification; reserve it for device-specific hardware or gesture validation.
- **QA Honesty Mandate:** Never claim a test or flow passed unless it was actually executed and succeeded.
  - `responsive layout PASS` ≠ `functional flow PASS`
  - `guest PASS` ≠ `authenticated PASS`
  - `unit test PASS` ≠ `browser PASS`
  - `code inspection PASS` ≠ `physical-device PASS`
  - Always mark unexecuted checks as `NOT TESTED` with truthful rationale.

### 8. Performance as a Product Requirement
Pathome must feel fast and responsive to tenants, lessors, and administrators:
- Avoid blocking users on internal processing; offload heavy work to safe background execution.
- Eliminate N+1 queries, oversized payloads, and unindexed database queries. Filter and paginate in PostgreSQL.
- Avoid downloading full-size media where thumbnails or responsive srcset images suffice; use appropriate media compression.
- Provide immediate UI feedback (optimistic updates with safe rollback, local image previews, skeleton states).
- Never sacrifice correctness, data integrity, or tenant/lessor session isolation merely for perceived speed.

### 9. Approval-First Authority and Scope Expansion Boundaries
Pathome operates under strict approval-first change control:
- **Authorized without repeated approval:** Routine technical implementation details required to make the approved task correct, secure, resilient, accessible, performant, and production-ready (local variable names, private helpers, focused regression tests, small bug fixes within affected scope).
- **Explicit approval required for:**
  - Any change to UI/UX behavior, layouts, visual hierarchy, styling, workflows, or navigation.
  - New business rules, permissions, role definitions, or product capabilities.
  - API contracts, DTO schemas, database migrations, or entity definitions.
  - New external dependencies, framework upgrades, or configuration changes.
  - User-facing copy, claims, brand taglines, or naming conventions.
  - Unrelated refactorings, premature generalizations, or cleaning up untouched files.
- **Escalation format:** When approval is needed, stop the affected portion and report:
  `APPROVAL REQUIRED`
  - Current Behavior: (What happens today)
  - Problem: (Why a change is necessary)
  - Recommended Option: (Smallest robust solution)
  - Alternatives: (Other viable options)
  - Impact & Scope: (Architectural, UI, or contract effects)

### 10. Core Pathome Invariants and Architecture Preservations
Every agent must preserve the established Pathome architecture and domain invariants:
- **Stable User Identity:** `User` is the authoritative authenticated entity. Tenant and platform capabilities must never depend on an email-specific identity; mobile-number OTP authentication must remain achievable without schema restructuring.
- **Additive Capabilities:** Tenant and lessor capabilities are additive. Acquiring lessor status does not replace `ROLE_TENANT`. Lessors remain capable tenants.
- **Authoritative Capability State:** The backend `LessorProfile` and server-side capability endpoints are authoritative for property management permissions (`My Properties`). UI controls must reflect backend truth.
- **Strict Account/Session Isolation:** Cross-account and cross-user data must never leak. Logout and account switching must immediately invalidate tokens, clear user-scoped storage, and reset in-flight async listeners. Stale async responses from a prior identity must never populate a subsequent session.
- **Server-Side Security:** Enforce authentication and authorization server-side for every protected operation, including entity ownership. Client-provided user IDs or hidden controls are never trusted for authorization.
- **Truthful Data:** All customer-facing text, metrics, counters, and badges must be 100% accurate. Never fabricate dashboard activity, visit counters, verification trust signals, or operational availability.
- **Multi-City Scalability:** PostgreSQL is authoritative for cities, localities, sectors, properties, and fees. Never hardcode Indore or any specific city into generic architecture, business rules, or database schemas.
- **PostgreSQL Entity Promotion:** Unknown or extracted text from AI, NLP, or search is not authoritative. AI suggestions may only be promoted into canonical entities through explicit, approved validation, normalization, and uniqueness rules.

### 11. Workspace, Git, and Dependency Safety
- **Workspace inspection:** Before modifying files, run `git branch --show-current` and `git status --short`; inspect existing uncommitted changes. Preserve all unrelated and uncommitted work.
- **Destructive commands prohibited:** Never run `git reset --hard`, `git clean`, force-push, or rewrite/amend history without explicit authorization. Do not stash user work for convenience.
- **Commit/Push restriction:** Do not commit or push unless explicitly instructed by the user or task prompt.
- **Diff hygiene:** Before staging or finalizing, review diffs with `git diff --check` and `git diff --cached --check`. Exclude secrets, debug artifacts, and unintended files.
- **Dependency discipline:** Exclude `node_modules/`, `target/`, `dist/`, `.git/`, and generated caches from broad scans. Do not run speculative `npm install`, `npm update`, or Maven dependency upgrades to guess at fixes; establish root causes first. Adding or removing dependencies requires approval.

### 12. Final Completion and Reporting Mandate
Every substantive task must conclude with a concise, truthful engineering report covering:
1. **Architecture & Design:** Key structural decisions and patterns applied.
2. **Edge Cases Covered:** Failure modes, boundaries, and session scenarios handled.
3. **Files Changed:** Exact list of modified and created files within authorized scope.
4. **Verification Executed:** Exact test commands, unit/integration results, build checks, and browser viewports validated.
5. **Checks Not Run:** Explicitly mark any deferred or unexecuted checks as `NOT TESTED` with justification.
6. **Performance & Security Implications:** Caching, query impact, authorization, and isolation verified.
7. **UI/UX Assessment:** Responsive behavior, accessibility, and visual polish confirmation (where applicable).
8. **Scope & Invariants Confirmation:** Confirm no unapproved scope expansion, no auth regressions, and no commit/push performed.

---

## Detailed Technical Standards

### A. Backend and Database Engineering (Spring Boot & PostgreSQL)
- **Method Scope & Null Safety:** Write small, focused methods with single responsibilities. Use `Optional<T>` appropriately; close streams, connections, and file handles using `try-with-resources`.
- **Naming Conventions:** Standard Java conventions (PascalCase classes/interfaces, camelCase methods/variables, UPPER_SNAKE_CASE constants).
- **PostgreSQL Indexing & Queries:** Filter and paginate in the database. Never call `repository.findAll()` in request handlers for potentially unbounded tables. Ensure query filters leverage composite B-Tree indexes on PostgreSQL.
- **Zero-GC String Handling:** Compile reusable regular expressions as `private static final Pattern` constants at class-loading time. Never compile fixed patterns inside methods or loops.
- **Global Exception Handling:** Route unhandled REST controller exceptions through `@RestControllerAdvice` in `GlobalExceptionHandler.java`. Preserve standardized payloads `{ timestamp, status, error, message, path }`, return appropriate HTTP status codes, and exclude internal stack traces from responses.

### B. Frontend Architecture, UI/UX, and Responsive Design (React & Tailwind CSS)
- **Component Hygiene:** Keep components pure, idempotent, and focused. Hooks must be called only at the top level. Keep side effects inside `useEffect` or event handlers, never during render.
- **Responsive Viewport Coverage:** Frontend work must be tested across mobile, tablet, laptop, and desktop viewports:
  - Mobile: `320–375px` (compact mobile), `390–414px` (standard mobile), `430px+` (large mobile).
  - Tablet & Desktop: `768px` (tablet portrait), `1024px` (tablet landscape / small laptop), `1280px` (desktop), `1440px+` (wide desktop).
- **Horizontal Overflow Prevention:** Prevent unintended page overflow (`scrollWidth <= innerWidth`). Use flexible grids, `min-width: 0`, and clamped values.
- **Animation & Physics:** Use Framer Motion with standard spring curves (e.g., stiffness 450, damping 28) or cubic-bezier `(0.16, 1, 0.3, 1)`. On mobile viewports (`<768px`), lateral slide-in animations must avoid horizontal layout displacement; prefer subtle vertical entrances (`y: 12`) to preserve boundary integrity.
- **Accessibility Standards:** Target WCAG 2.2 AA. Use semantic HTML5 landmarks, visible focus outlines, proper `aria-*` attributes, accessible names, sufficient color contrast, and focus trapping in modals/drawers.

### C. Logging, Operating History, and Agent Accountability
- **AI Change Log:** For substantive authorized work, append an entry to the established repository-root log: `chat_gpt_changes` for ChatGPT / OpenAI Codex, `antigravity_changes` for Antigravity; other agents use the repository's designated log.
- **Log Entry Content:** Current India date/time (IST), reason for request, exact files/areas changed or investigated, outcome, verification performed, outstanding checks, and truthful commit/push status. Never record secrets or tokens.
