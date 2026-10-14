# Reusable Workflow: Pathome Package Implementation

Referencing the **Pathome Fast & Safe Development Protocol v1.0** (`.agents/rules/pathome-fast-safe.md`) and applicable frozen package architecture contracts.

---

## Workflow Overview

Every Pathome feature package or milestone (e.g., Phase 2A packages, Phase 2B, Package 3A, etc.) follows this standardized 7-stage cycle:

```text
[Stage 1: Preflight & Baseline]
             │
             ▼
[Stage 2: Architecture & Edge-Case Design]
             │
             ▼
[Stage 3: Test-First & Bounded Implementation]
             │
             ▼
[Stage 4: Self-Review & Hygiene Verification]
             │
             ▼
[Stage 5: Regression Batch & Independent Review Gate]
             │
             ▼
[Stage 6: AI Changelog Logging]
             │
             ▼
[Stage 7: Conditional Checkpoint & Push]
```

---

## Detailed Stages

### Stage 1: Preflight & Baseline Verification
- Confirm repository root: `pwd && git rev-parse --show-toplevel`
- Confirm active feature branch: `git branch --show-current`
- Verify starting commit SHA: `git rev-parse HEAD`
- Confirm worktree status: `git status --short`
- Fetch remote branch and check for divergence:
  ```bash
  git fetch origin <branch> && git rev-parse origin/<branch>
  ```
- Cross-reference with the applicable frozen package contract (e.g., `00_CANONICAL_Pathome_Phase1_Staff_Scope_Architecture_V1.1.md`, Phase 2A/3A specifications).
- **STOP** if any unexpected diff or remote divergence is discovered.

### Stage 2: Architecture & Edge-Case Design
- Identify failure modes prior to writing code:
  - Input boundaries & null checks.
  - Race conditions, concurrent ownership transfers, and unlinking.
  - Single-snapshot isolation guarantees (`REQUIRES_NEW`, `REPEATABLE_READ`).
  - Privacy safeguards: Ensure unauthorized or nonexistent entities yield indistinguishable generic 404 responses.
- Verify that no unapproved product rule, schema migration, or DTO contract change is introduced.
- If scope expansion is needed, stop and escalate via `APPROVAL REQUIRED`.

### Stage 3: Test-First & Bounded Implementation
- Write unit tests (JUnit 5) and integration tests with real PostgreSQL disposable schemas before or in tandem with implementation.
- Build production components in small, focused methods.
- Enforce static Pattern constants for regex (Zero-GC).
- Never call unbounded `repository.findAll()` in request handlers.
- Enforce server-side authorization and active staff scope on all queries.

### Stage 4: Self-Review & Hygiene Verification
- Compile check: `mvn -q -f backend/pom.xml -DskipTests test-compile` (backend) or `npm run build` (frontend).
- Whitespace check: `git diff --check`.
- Security & isolation scan: verify zero cross-tenant data leakage and no hardcoded secrets or PII.

### Stage 5: Regression Batch & Independent Review Gate
- Execute focused regression test batch against disposable PostgreSQL schemas:
  ```bash
  PATHOME_VISIT_OPERATIONS_POSTGRES_TEST=true mvn -f backend/pom.xml -Dtest=<TargetTests> test
  ```
- Ensure 0 failures, 0 errors, 0 skipped.
- Independent security review (Sol / GPT-6) where required; address all findings with negative controls and deterministic lock evidence.

### Stage 6: AI Changelog Logging
- Append entry to `chat_gpt_changes` (or designated agent log):
  - IST timestamp.
  - Reason & scope.
  - Files modified and added.
  - Concrete test evidence with log paths.
  - Outstanding/deferred checks honestly noted.
  - Zero secrets or tokens.

### Stage 7: Conditional Checkpoint & Push
- Stage exact files explicitly:
  ```bash
  git add -- <path1> <path2> ...
  ```
- Check cached diff: `git diff --cached --check && git diff --cached --stat`
- Commit with semantic message: `git commit -m "<type>(<scope>): <message>"`
- Push to origin without `--force`: `git push origin <branch>`
- Verify remote SHA matches local HEAD: `git ls-remote origin refs/heads/<branch>`
- Confirm working tree is clean.
