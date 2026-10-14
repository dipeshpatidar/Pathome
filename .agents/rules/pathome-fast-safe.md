---
trigger: always_on
description: Pathome Fast & Safe Development Protocol v1.0 - Standing rules and speed/safety protocols for all Pathome engineering tasks.
---

# Pathome Fast & Safe Development Protocol v1.0

Applies unconditionally to all contributors, maintainers, and coding agents across Pathome repositories. This protocol harmonizes maximum engineering throughput with strict production safety, zero data-leakage, and architectural integrity.

---

## 1. Core Operating Philosophy: "Fast Through Precision"

Speed in Pathome is achieved through **first-pass correctness**, **narrow targeted execution**, and **eliminating rework cycles**, NEVER through sloppy shortcuts or skipping verification.

- **Fast** means:
  - Narrow searches using targeted tools (`grep`, specific file reads); never broad whole-tree scans.
  - Model routing efficiency: low-cost models for mechanical, git, and documentation tasks; reasoning models reserved for architecture, concurrency, and security.
  - Reusing verified repository context and state; stop investigating hypotheses the instant the root cause is established.
  - Bounded test runs (<= 10 minutes) targeting affected modules; no redundant, non-differentiating test reruns.
- **Safe** means:
  - First-pass completeness for edge cases, failure modes, boundaries, and race conditions.
  - Strict approval-first change control: zero unapproved changes to UI/UX, layouts, contracts, schemas, migrations, or business rules.
  - Absolute preservation of frozen baselines and historical phase contracts.
  - Real database verification using disposable schemas; never touch production or shared data.
  - Strict tenant, session, and coordinator isolation with fail-closed security.
  - Clean Git hygiene: explicit file staging, zero unapproved commits/pushes, matching remote SHAs.

---

## 2. The Twelve Mandatory Pillars

### Pillar 1 — Preflight & Worktree Invariant Verification
Before executing any task:
1. Verify repository root, active branch, current HEAD SHA, and worktree status.
2. Confirm no remote divergence exists (`git fetch` followed by commit comparison).
3. Verify that the task scope matches the authorized mandate. If any unexpected file or divergence exists, **STOP immediately**.

### Pillar 2 — Frozen Baselines & Historical Architecture Preservation
- Historical phases (Phase 1A–1E, Phase 2A-1, 2A-3A, 2A-3B) and frozen architecture contracts are immutable.
- Never alter frozen business rules, API schemas, status enums, or contracts without explicit written approval.
- Preserved legacy fields and methods must not be speculatively removed.

### Pillar 3 — Scope Containment & Approval-First Authority
- **Authorized without prior approval**: Internal implementation details required for correctness, security, performance, accessibility, and focused unit/integration tests within the assigned package.
- **Strict approval required before modification**:
  - Any change to UI/UX layouts, styling, visual hierarchy, user flows, or navigation.
  - New business rules, role definitions, permissions, or security policies.
  - Database migrations, entity schemas, indexes, or API DTO contracts.
  - External dependencies, library updates, or global configuration.
  - Any cleanup or refactoring of files outside the authorized package scope.
- **Escalation**: When approval is required, stop and report using the canonical `APPROVAL REQUIRED` format.

### Pillar 4 — Edge-Case Discipline & First-Pass Completeness
Every approved feature must be built end-to-end to production quality in one pass:
- Explicitly handle: nulls, empty states, boundary inputs, timeouts, partial failures, duplicate requests, and idempotent retries.
- Address concurrency, race conditions, stale asynchronous reads, and transaction isolation (`REPEATABLE_READ` / `REQUIRES_NEW` where cross-transaction single-snapshot guarantees are required).
- Guarantee fail-closed security: missing vs foreign identifiers must never leak existence or tenant identity (generic, indistinguishable 404s).

### Pillar 5 — Session, Account & Cross-User Isolation
- **Stable Identity**: The authenticated `User` is authoritative; tenant and lessor capabilities are additive.
- **Strict Isolation**: No cross-tenant, cross-coordinator, or cross-account data leakage is tolerated.
- **Immediate Revocation**: Logout and account switching must immediately invalidate tokens, reset in-flight async state, and clear user-scoped storage.
- **Server-Side Authorization**: Every protected operation must verify entity ownership and active staff scope server-side on the database statement.

### Pillar 6 — Database & Concurrency Safety
- **Authoritative Source of Truth**: PostgreSQL is the sole authoritative store. No client-driven entity promotion or transient caching overrides database state.
- **Lock Ordering**: Enforce consistent, deterministic lock hierarchies (e.g., Session root lock before Request item locks) to prevent deadlocks.
- **Disposable Schemas**: All automated database tests must run against dedicated, disposable PostgreSQL schemas (e.g., UUID-based schemas migrated V1→V49). Production and persistent development data are never used for testing.
- **Zero-GC & Index Hygiene**: Reusable regex patterns must be static final constants. Unbounded `findAll()` queries in request paths are strictly prohibited. Filter and paginate in PostgreSQL.

### Pillar 7 — UX & Accessibility First (User-Facing Scope)
When building frontend or user-facing components:
- Define the user journey, empty states, loading skeletons, and error feedback before coding.
- Enforce responsive layout integrity: 320px to 1440px+ viewports with zero unintended horizontal overflow (`scrollWidth <= innerWidth`).
- Minimum touch targets of 44px; iOS input controls font-size >= 16px to prevent zoom.
- Target WCAG 2.2 AA accessibility, visible focus outlines, and keyboard navigation.

### Pillar 8 — Resource & Token Discipline
- Conserve AI context and model credits: do not run unbounded scans or repeatedly read unchanged large files.
- Route mechanical git, staging, compiling, and minor edits to low-cost models. Reserve reasoning models for complex architecture, state isolation, and security reviews.
- Once a root cause is proven, stop investigating alternate hypotheses.

### Pillar 9 — Verification Hierarchy & QA Honesty
- **Tiered Verification**:
  1. Focused unit tests (JUnit 5 / Node test runners) for modified behavior.
  2. Module integration tests with real PostgreSQL disposable schemas.
  3. Compilation & whitespace hygiene checks (`mvn test-compile`, `npm run build`, `git diff --check`).
- **QA Honesty Mandate**:
  - Never claim a test, flow, or build passed unless actually executed with exit code 0.
  - Clearly distinguish between unit test PASS, integration test PASS, and browser test PASS.
  - Mark any unexecuted test category as `NOT TESTED` with truthful rationale.

### Pillar 10 — Truthful AI Changelog Accountability
- Maintain an append-only audit trail in `chat_gpt_changes` (or designated agent log).
- Record exact IST date/time, rationale for request, exact files modified/added, concrete test evidence with command lines and log paths, outstanding/NOT TESTED items, and commit/push status.
- Never record production secrets, credentials, or customer PII.

### Pillar 11 — Git Hygiene & Staging Precision
- **Explicit Staging**: Always stage files individually using `git add -- <path1> <path2>`. Never use `git add .` or `git add -A`.
- **Pre-Commit Diffs**: Verify `git diff --cached --check`, `git diff --cached --stat`, and ensure zero untracked or unwanted files.
- **Commit Messages**: Use semantic, truthful commit messages describing the approved package.
- **Safe Push**: Push only to the designated feature branch. Never force-push (`--force`). Never merge to `main` without explicit approval.
- Verify that the remote branch SHA matches the new local commit SHA, and that the working tree is clean.

### Pillar 12 — Independent Security & Review Gate
- High-risk packages (authorization, scheduling, ownership transfers, contact access) must undergo independent review (e.g., Sol / GPT-6 review) before commit.
- Address all findings, re-verify with negative controls and deterministic PostgreSQL lock evidence, and establish clean green runs before final checkpointing.
