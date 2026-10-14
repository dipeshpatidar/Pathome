---
name: pathome-package-implementation
description: >-
  Use this skill when planning, implementing, verifying, or checkpointing an approved Pathome package or milestone
  (e.g., Phase 2A packages, Phase 2B, Package 3A, etc.) in accordance with the Pathome Fast & Safe Development Protocol v1.0.
---

# Pathome Package Implementation Workflow

This reusable workflow guides the end-to-end execution of approved Pathome packages. It enforces the **Pathome Fast & Safe Development Protocol v1.0** and guarantees first-pass correctness, zero scope leakage, and verifiable checkpointing.

---

## Stage 1: Preflight & Baseline Verification
1. **Workspace Inspection**:
   - Verify repository root: `git rev-parse --show-toplevel`
   - Verify current branch: `git branch --show-current`
   - Verify starting HEAD: `git rev-parse HEAD`
   - Verify worktree status: `git status --short`
2. **Remote Alignment**:
   - Fetch remote ref: `git fetch origin <branch>`
   - Confirm remote SHA matches local HEAD: `git rev-parse origin/<branch>`
3. **Contract Review**:
   - Read the applicable frozen architecture contract (e.g., `00_CANONICAL_Pathome_Phase1_Staff_Scope_Architecture_V1.1.md`, Phase 2A/3A specifications).
   - Confirm frozen invariants, database schema constraints, and user journey boundaries.
   - **STOP** if any unexpected uncommitted diff or branch divergence exists.

---

## Stage 2: Architecture & Edge-Case Design
Before writing production code:
1. **Identify Failure Modes & Boundaries**:
   - Concurrency & race conditions: concurrent ownership transfer, relink, session cancellation.
   - Idempotency & retries: double-submit, transient network loss.
   - Information disclosure: ensure missing vs unauthorized entities produce indistinguishable generic 404 responses.
   - Transaction boundaries: determine if `Propagation.REQUIRES_NEW` or `Isolation.REPEATABLE_READ` is required for single-snapshot guarantees.
2. **Scope Boundary Check**:
   - Verify that all planned changes fall strictly within the approved package boundary.
   - If any new product rule, API contract change, or migration is required, STOP and submit an `APPROVAL REQUIRED` report.

---

## Stage 3: Test-First & Bounded Implementation
1. **Write Regression & Unit Tests First**:
   - Add focused unit tests (JUnit 5 / Node test runner) covering normal and edge-case paths.
   - Create real PostgreSQL tests utilizing disposable test schemas (e.g., UUID-based schemas with V1→V49 migrations).
2. **Implement Production Changes**:
   - Keep classes, methods, and components small, focused, and single-purpose.
   - Use compile-time static Pattern constants for regex.
   - Never call unbounded `repository.findAll()` in HTTP handlers.
   - Enforce server-side ownership and active staff scope on all queries.

---

## Stage 4: Self-Review & Hygiene Verification
1. **Code Hygiene**:
   - Check compilation: `mvn -q -f backend/pom.xml -DskipTests test-compile` (or `npm run build` for frontend).
   - Check whitespace & formatting: `git diff --check`.
2. **Security & Isolation Review**:
   - Verify zero cross-tenant / cross-user data leakage.
   - Confirm locks follow deterministic order (root Session first, then child Request).
   - Ensure response headers include `Cache-Control: no-store` on sensitive operational routes.
   - Scan diff for secrets, tokens, credentials, or PII.

---

## Stage 5: Regression Batch & Independent Review Gate
1. **Execute Focused Test Batch**:
   - Run the package-specific test suite plus affected regression modules:
     ```bash
     PATHOME_VISIT_OPERATIONS_POSTGRES_TEST=true mvn -f backend/pom.xml -Dtest=<TargetTests> test
     ```
   - Verify 0 failures, 0 errors, 0 skipped.
2. **Independent Review / Security Gate**:
   - Submit for independent review (e.g., Sol / GPT-6 review) where required.
   - Address any findings with deterministic negative controls and PostgreSQL lock-wait evidence.

---

## Stage 6: AI Changelog Logging
1. Update `chat_gpt_changes` (or repository agent log) with an append-only entry:
   - India local timestamp (IST).
   - Rationale and package scope.
   - Exact files added and modified.
   - Concrete test commands, outcomes, and log paths.
   - Outstanding / NOT TESTED items honestly documented.
   - Zero secrets or credentials included.

---

## Stage 7: Conditional Checkpoint & Push
Only after all gates pass:
1. Stage exact files explicitly:
   ```bash
   git add -- <path1> <path2> ...
   ```
2. Verify staged diff:
   ```bash
   git diff --cached --name-status
   git diff --cached --stat
   git diff --cached --check
   git status --short
   ```
3. Commit with approved semantic message:
   ```bash
   git commit -m "<type>(<scope>): <summary>"
   ```
4. Push to feature branch:
   ```bash
   git push origin <branch>
   ```
5. Verify remote branch SHA:
   ```bash
   git ls-remote origin refs/heads/<branch>
   ```
6. Confirm clean worktree (`git status --short` returns empty).
