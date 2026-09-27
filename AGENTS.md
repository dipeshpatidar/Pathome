# Pathome Engineering Constitution

Applies to all contributors and coding agents across Pathome's Spring Boot backend, PostgreSQL data, web, mobile, and CRM. Apply requirements to the affected scope; do not expand a task merely to retrofit unrelated code.

## 1. Scope, Authority, and Approval

- Follow the agent platform's instruction hierarchy. Within it, explicit user instructions define the authorized task. Read-only and file restrictions also apply to logs and generated artifacts.
- Preserve approved behavior and design. Routine implementation details within approved scope need no repeated approval: local names, helpers, focused tests, and small internal fixes/refactors that do not change contracts, architecture, data semantics, or material risk.
- Obtain approval for changes not already authorized to UI/UX behavior, workflows, business rules, architecture, API contracts, schemas, dependencies, authentication/authorization, roles, navigation, user-visible names/copy/claims, scope, destructive migrations, or material caching/performance behavior.
- If such a change becomes necessary, stop the affected work and present: problem, recommended option, alternatives, and impact. Continue independent authorized work where safe. Silence is not approval.
- Also stop the blocked action for missing required secrets/configuration, ambiguous business rules, destructive Git operations, verification beyond the allowed window, or a workaround that hides the root cause. Never invent credentials, permissions, or product rules.
- Security, data integrity, correctness, and applicable accessibility requirements are acceptance gates, not permission to expand scope. Report conflicts instead of silently weakening a requirement or making an unapproved fix.
- Local instructions may specialize these rules without silently weakening them. Report unresolved conflicts.

## 2. Git and Workspace Safety

- Before modifying files, run `git branch --show-current` and `git status --short`; inspect relevant existing changes. Preserve unrelated and uncommitted work.
- Never run `git reset --hard`, `git clean`, discard/restore unrelated work, force-push, or rewrite/amend history without explicit authorization. Do not stash user work for convenience.
- Do not commit or push unless explicitly authorized by the task or conversation.
- Before committing, review the diff and staged diff, run `git diff --check` and `git diff --cached --check`, and verify the exact staged files. Exclude secrets and unintended generated artifacts.
- Revert only your own unapproved changes, without overwriting others' edits. Stop if ownership cannot be established safely.

## 3. Architecture and Maintainability

- Keep clear module/service responsibilities and separate presentation, business/domain, and persistence concerns. Prefer understandable code and existing patterns over clever abstractions.
- Shared authoritative business rules belong in backend/domain services. Web, mobile, and CRM may assist users with validation but must not independently define business rules or permissions.
- Define transaction boundaries and handle concurrent updates deliberately. Use constraints and appropriate concurrency controls to protect invariants; make retryable side effects idempotent.
- Preserve API/client compatibility where applicable. Approved schema migrations must account for existing data, mixed application versions, deployment order, and rollback or recovery.
- Design for current scale and multi-city growth through clear boundaries and scoped data access. Do not introduce unnecessary services, queues, frameworks, or distributed-system complexity. Architecture changes require approval.

## 4. Domain Data and Persistence

- PostgreSQL is authoritative for cities, localities, sectors, properties, fees, and other production domain entities.
- Distinguish canonical entities, aliases referencing canonical entities, learned suggestions, and unknown user-entered text. Unknown or extracted text is not an authoritative entity.
- AI, search, and NLP output may create or promote records only through explicit approved domain rules covering validation, normalization, provenance, uniqueness, and authorization. Retain suggestions only under the approved learning policy; never silently promote them into canonical data.
- Protect integrity with database constraints and indexes suited to actual queries, including composite indexes where justified. ORM declarations do not replace required migration handling.
- Filter and paginate in the database. Do not use `findAll()` in request handlers for large or potentially unbounded tables, or load entire tables merely to filter them in application code.

## 5. Security and Role Consistency

- Enforce authentication and authorization server-side for every protected operation, including object ownership and organization/city/assigned scope as applicable. Hidden frontend controls are not security.
- Verify existing rules for tenant, owner, GE, OE, admin, sub-admin, and future roles. Keep UI visibility and workflows consistent with backend permissions across web/mobile/CRM; do not duplicate authoritative permission rules inconsistently.
- Validate untrusted input and allowlist writable fields. Prevent injection, mass assignment, IDOR, and privilege escalation using established framework protections and explicit checks.
- Keep secrets out of Git, frontend bundles, logs, fixtures, and diagnostics. Minimize sensitive data collection/logging; use least privilege and approved secret/configuration mechanisms.
- Treat sessions, cookies, tokens, expiry/revocation, and cross-origin protections deliberately. Validate upload authorization, size, type/content, filename/storage handling, and access; do not trust client metadata alone.
- Security-sensitive changes require focused positive and negative tests. Do not weaken security to make local development pass. Dependency and security behavior changes require approval when outside scope.

## 6. Failure Handling and Production Readiness

- Route unhandled REST controller exceptions through `@RestControllerAdvice` in `GlobalExceptionHandler.java`. Preserve `{ timestamp, status, error, message, path }`, use appropriate HTTP status codes, and keep sensitive diagnostics out of responses.
- Provide truthful, actionable user errors and useful internal diagnostics. Never present fake success, fabricated fallback data, or swallowed failures as successful completion.
- Distinguish transient from permanent failures. Retry only appropriate operations with bounded attempts, backoff, timeouts, and idempotency; do not blindly retry validation, permission, or other permanent failures.
- Consider network interruption, duplicate requests, database/provider/upload failure, and partial completion. Define safe recovery, cleanup, or compensation where relevant; preserve user context when possible.
- Bound memory, queues, concurrency, connections, and request/upload sizes. Configure connection and operation timeouts where relevant; avoid unbounded background work or accumulation.
- Use production-safe configuration and environment separation. Consider graceful degradation, deployment/migration safety, and rollback/recovery; local success alone is not production readiness.
- Production-impacting data changes must consider backup/restore and recovery strategy where data loss or irreversible transformation is possible.
- For important flows, use proportionate structured logs, useful error context, correlation IDs, operational metrics, audit history, and health/readiness checks. Reuse existing facilities; new infrastructure requires appropriate approval.

## 7. Performance and Resource Discipline

- Avoid unbounded work in hot paths, N+1 queries, unnecessary network roundtrips, and oversized payloads. Use database filtering/pagination and review query/index behavior.
- Measure or establish a bottleneck before adding caches. Inspect profiles and query plans when practical; use repository-defined SLOs and do not invent universal latency or complexity targets.
- Every cache must have an owner, justified contents, growth bounds, invalidation/expiry policy, and defined consistency behavior. Do not serve stale correctness-sensitive data; prefer database correctness over speculative caching.
- Optimize media, frontend rendering, lazy loading, and code splitting where evidence or clear workload characteristics justify them. Preserve correctness and maintainability; document material tradeoffs.
- Compile reusable Java regex as `private static final Pattern` constants. Do not compile fixed patterns inside methods/loops; use equivalent reuse in other runtimes and validate/bound genuinely dynamic patterns.

## 8. Product Truthfulness and UI/UX Quality

- All customer-facing text must be meaningful, accurate, and production-ready. No developer notes, debug/layout labels, filler, or unsupported claims.
- Do not fabricate counters, activity, verification, availability, trust signals, or operational guarantees. Clearly distinguish planned capability from available functionality when communicating it is in scope.
- Before editing a screen, compare the affected area with its last committed approved implementation and inspect existing uncommitted changes. Preserve approved hierarchy, layout, styling, navigation, and responsive behavior unless changes are authorized.
- Reuse the established design language and components. Integrate approved features with the smallest reasonable visual footprint; do not add unsolicited controls, sections, or workflows.
- Feature completion includes deliberate hierarchy, spacing, typography, interaction clarity, targets, hover/focus/keyboard behavior, transitions, navigation, context preservation, and perceived performance.
- Design applicable loading, empty, error, success, disabled, and pending states. Give clear feedback, prevent accidental duplicate actions, and preserve useful form/navigation context.
- Aim for polished, purposeful interactions and restrained micro-interactions. Generic scaffolding is not finished design; aesthetic ambition does not authorize redesigning approved areas.

## 9. Responsive Behavior

- Responsive behavior is an acceptance criterion for every frontend change: 320–375px, 390–414px, 768px, 1024px, 1280px, and 1440px+. Cover relevant routes and states, including loading/error/empty/success.
- Prevent unintended page overflow (`scrollWidth <= innerWidth`), overlap, clipping, and hidden/unreachable actions. Make long content wrap or truncate intentionally.
- Account for portrait/landscape changes, dynamic browser chrome, safe areas, virtual keyboards, and touch/mouse/keyboard differences. Do not rely on hover for essential actions.
- Use adequate touch targets, normally at least 44px; editable controls on iOS must have an effective font size of at least 16px. Never disable pinch zoom with `user-scalable=no` or restrictive `maximum-scale`.
- Keep overlays/dropdowns/dialogs within the usable viewport, with scrollable content and reachable actions. Drawers/menus must lock background scrolling without losing page position and restore it on close.
- Respect reduced motion and safe-area insets. Prefer Grid/Flexbox, flexible sizing, `min-width: 0`, `clamp()`, and aspect ratios over device-specific hacks.

## 10. Accessibility

- Target WCAG 2.2 AA where applicable and practical. Do not claim formal compliance without an audit; report known gaps rather than silently treating them as acceptable.
- Use semantic HTML, appropriate labels/accessible names, meaningful image alternatives, sufficient contrast, logical reading/tab order, keyboard access, and visible focus.
- Associate form errors with fields and make important asynchronous feedback perceivable to assistive technology. Manage dialog focus on open/close and prevent focus escaping modal dialogs.
- Respect zoom and reduced motion. Verify affected keyboard and screen-reader behavior proportionately; automated checks alone do not establish accessibility.

## 11. Tiered Verification

- Select checks by changed behavior and risk:
  1. Focused tests for the modified behavior.
  2. Affected-module tests when consequences extend beyond it.
  3. Relevant compile/build checks before finalizing substantive executable changes.
  4. Broader/full regression when risk warrants it, before release, or when explicitly requested.
- Cover new/changed Java parser, service, and exception-handler behavior with JUnit 5. Add meaningful regression tests for bugs and integration, repository/database, API, frontend, security, and accessibility checks where relevant.
- Use the relevant module's `mvn test`, `mvn clean compile`, and/or `npm run build` as applicable; do not run unrelated suites by default. Documentation-only changes need appropriate document/diff validation.
- Tests must check behavior and important failure cases. Never claim a test passed unless it ran successfully; distinguish failures, environmental blockers, and checks not run.

## 12. Verification Time Limit and Browser Automation

- Any single autonomous verification command or cohesive verification batch expected to exceed 10 minutes requires manual handoff. The limit applies per command or cohesive batch, not as one cumulative timer for the task's verification; multiple justified bounded checks may run when each is expected to complete within 10 minutes.
- If a required check is expected to exceed 10 minutes, stop before running it. Provide what needs testing, exact manual commands/steps, prerequisites, expected results, and evidence the user should return without secrets.
- Do not chain open-ended test loops, repeatedly rerun failing commands without diagnosis, split one clearly long verification into artificial smaller commands, or restart the clock to bypass the limit. Longer automation needs an explicit user exception.
- If a normally fast command unexpectedly hangs, safely stop it, diagnose the cause, and do not blindly rerun it repeatedly. Repeat successful checks only with a concrete reason.
- Use browser automation only for large or critical UI changes needing a focused behavioral/visual check. Inspect code first, state the precise question, and run useful focused non-browser checks first.
- Test relevant routes, viewports, and states only; avoid broad site tours without an explicit reason. When browser automation is unwarranted or impractical, provide concise manual visual checks.
- A passing build does not prove visual quality. Required checks deferred by the time limit remain outstanding and must be reported.

## 13. Context and Operating Cost

- Search narrowly with `rg`/`rg --files`, inspect likely files first, and reuse known context. Avoid unnecessary whole-repository scans and rereading unchanged large files.
- Keep diagnostics bounded: prefer targeted matches, summaries, or relevant log tails over giant outputs. Stop investigating when evidence is sufficient; retain concise findings needed for continuity.
- Use the cheapest capable model/workflow when instructed and available. Reduce wasted reads, tool calls, and retries without reducing required reasoning, engineering quality, or verification honesty.

## 14. Dependencies and Generated Files

- Exclude `node_modules/`, `target/`, `dist/`, `build/`, `coverage/`, `.git/` internals, generated artifacts, and large dependency caches from broad scans and routine edits. Normal Git commands remain appropriate.
- When dependency/generated-output investigation is necessary, inspect specific resolved files or bounded output. Change source files instead of generated copies; commit generated output only when intentionally tracked by repository policy.
- Do not experiment with `npm install`, `npm update`, `npm audit fix`, Maven upgrades, or framework changes to guess at a fix. Establish the dependency issue first.
- Dependency additions/upgrades/removals require approval unless already in scope. Restoring declared dependencies through the established locked installation workflow is allowed when necessary for approved work; do not silently change manifests or lockfiles.

## 15. AI Change Log

- For substantive authorized work, append to the established repository-root log: `chat_gpt_changes` for ChatGPT / OpenAI Codex, `antigravity_changes` for Antigravity; other agents use the repository's explicitly designated log. Preserve prior entries.
- Record current India date/time, why the work was requested, exact files/areas changed or investigated, outcome, verification actually performed, outstanding checks, and truthful commit/push status. Never include secrets.
- Avoid noise for trivial read-only checks. Explicit read-only or file restrictions override logging; do not create or modify a log outside authorized scope.

## 16. Completion and Reporting

- User-facing work is complete only after addressing functionality, UX, responsiveness, accessibility, failure states, performance, applicable security, and appropriate verification. A passing build alone is insufficient.
- Review the final diff for scope and unintended changes. Identify every intentional user-visible difference against the approved baseline; remove only your own unapproved changes safely.
- Report what changed, why, verification performed, checks not run, material limitations, and commit/push status when relevant. Never describe work with unresolved required checks as fully verified.
