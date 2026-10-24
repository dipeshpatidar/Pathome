# PATHOME — G10 ENVIRONMENT RECOVERY AND VERIFICATION REPORT

**Executed:** 22 October 2026, India Standard Time  
**Repository:** `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`  
**Branch / HEAD:** `feature/phase2a-oe-ui-integration` / `a943775e07a153087d0b38c315db20e18b30aec5`  
**Result:** ENVIRONMENT CONTAINED / LEGITIMATE ATTESTATION BLOCKED / NO DATABASE WRITES  
**Application state:** intentionally stopped; not left accessible against suspect provenance

## 1. Executive Result

The existing review services were connected to the suspect `pathome_g10_review` schema. Evidence
confirms that the schema is not acceptable acceptance provenance:

- its Flyway history has zero rows;
- Flyway was disabled in the running backend;
- the schema contains 56 application base tables and synthetic data;
- five application tables, including a 49-row Flyway history, also exist in `public`;
- one synthetic account combines OE coordination, City intake and global Staff Admin grants;
- the only sentinel identity is historical and the current run ID file is absent.

The owner-supplied startup transcript itself was not present as a local file and could not be
independently re-read. Its described actions are treated as an incident allegation, while the current
database facts above are independently observed. No claim is made that `pathome_db` was mutated.
The review process had read access to `pathome_db` only according to the prior transcript; this task
did not connect to `pathome_db`.

The repository-supported recovery and attestation procedures both refused with
`REFUSED [17] Run identity missing`. No supported non-destructive path exists in the current tool to
mint a fresh run identity for the existing marked database while preserving `pathome_g10_review`.
Therefore all database mutations, genuine tests, migrations and owner application startup are
**BLOCKED**, and the existing exposed review processes were stopped.

## 2. Service Status

Initial read-only observations:

- Spring Boot PID `46189`, bound `*:8080`, started 18:28:02 IST.
- Vite PID `46239`, bound `*:5173`, started 18:28:14 IST.
- Backend root returned 401; no actuator health endpoint exists.
- Frontend root returned HTTP 200.
- Backend datasource used the approved disposable database with
  `currentSchema=pathome_g10_review`.
- Flyway was disabled and Hibernate DDL was disabled.
- Ten JDBC connections were idle against PostgreSQL.
- Vite proxy targeted `http://localhost:8080`.
- Both servers were wildcard-bound and therefore contained after inspection.

Containment result:

- both owned child processes and their Maven/npm parents exited;
- ports `8080` and `5173` are closed;
- PostgreSQL JDBC sessions for the review backend are zero;
- all database schemas and rows were retained unchanged.

## 3. Prior Startup-Trace Safety Assessment

The supplied request describes these historical actions:

- dropping/recreating `pathome_g10_review`;
- direct migration SQL through `psql`;
- hand-populated/copied Flyway history;
- shared hardcoded credentials and encryption material;
- one synthetic user holding all capabilities.

Current read-only evidence independently confirms the combined grant and the unusable migration
provenance. It also proves objects outside the intended schema in `public`. It does not prove a write
to protected `pathome_db`; no protected database was opened by this task.

The observed wildcard listeners and secret-bearing process environment are additionally unsafe for
acceptance use because the application was reachable beyond loopback. The processes were stopped after
the owned PID, working directory and PostgreSQL connections were established.

## 4. Suspect Schema Containment

`pathome_g10_review` remains intact and untouched:

- owner `pathome`;
- 56 base tables;
- 57 information-schema table/view objects;
- `flyway_schema_history` empty;
- users 3, grants 3, leads 3, listings 2;
- one active user with `OPS_COORDINATE@TEAM`, `OPS_INTAKE@CITY`, `STAFF_ADMIN@GLOBAL`;
- two inactive users.

`public` retains legacy/older review objects:

- `users` 50 rows;
- `staff_access_grants` 21 rows;
- `listings` 5 rows;
- `lead_routing_queue` 0 rows;
- `flyway_schema_history` 49 successful V1–V49 rows.

Combined public/review object inventory fingerprint:

`cced374469c065a1b484e60ae780f2d54a18c25ef130c49f27f44327a7829f00`

No schema repair, table deletion, row update, Flyway repair or sentinel edit was performed.

## 5. Legitimate Attestation Result

```bash
bash tools/oe-integration-env/run.sh recover
```

```text
REFUSED [17] Run identity missing
```

```bash
bash tools/oe-integration-env/run.sh attest
```

```text
REFUSED [17] Run identity missing
```

The database remains positively designated and carries one `DISPOSABLE` marker, but its sentinel
contains only the historical run `20261013T124820-98553`. The current run-ID, secrets, PID and binding
artifacts are absent, and no `previous-runs/` archive exists.

The recovery tool requires an existing run identity before it will archive runtime artifacts. The
create tool intentionally refuses because the approved database already exists. Recreate/teardown
would drop the designated database and therefore the preserved suspect schema. Updating the sentinel
or creating a run-ID by hand would fabricate provenance and is forbidden.

**Exact blocker:** the approved existing database exists, but there is no supported, non-destructive
procedure to issue a new run identity while preserving prior evidence.

## 6. Clean Migration and Genuine Verification

Not executed, by rule:

- no clean schema was created;
- no legacy baseline or migration chain was applied;
- no successful Flyway history was manufactured;
- no synthetic fixtures or credentials were created;
- no real HTTP/PostgreSQL security witness ran;
- no attachment, concurrency, rollback, consent, history or 20×20 query witness ran;
- no connected Chromium/Spring/PostgreSQL journey ran.

These are `NOT TESTED / BLOCKED`, not FAIL and not PASS.

## 7. Local Application

No trustworthy owner application is left running. The previous application was stopped because it
used the suspect schema, had no valid run identity, carried secret-bearing process configuration and
bound both servers beyond loopback.

Expected URLs remain unavailable until a clean authorized environment is started:

- `http://localhost:5173/`
- `http://localhost:5173/operations/leads`
- `http://127.0.0.1:8080`

No production, staging, WhatsApp or Meta system was contacted.

## 8. Tracker Assessment

The exact requested Smart Intake Approval files were not present locally. Read-only hashes of the
available differently named October 22 files are:

- Cross-Role Smart Intake `2eaebf...e77b`: **NOT FOUND**
- Visit Session Smart Intake `f600520...e160`: **NOT FOUND**
- Existing Cross-Role WhatsApp Approval:
  `0b9ed98530ad65fb6ce3ffd36e53d89db7335678f3c1475777ce39c94c4c76da`
- Existing Visit Session WhatsApp Approval:
  `384d41c33de864b894b950c4f89a0e6617c072677becce717c166a472bd5a114`

No differing-hash workbook was substituted as the requested current tracker. Tracker comparison is
therefore incomplete. No workbook was modified.

## 9. Cleanup and Preservation

- No automated test schema was created.
- No run-owned schema was dropped.
- `pathome_g10_review` and `public` data are preserved.
- No datasource cleanup SQL was executed.
- The only process action was bounded `SIGTERM` to the verified owned review PIDs.
- No file in the application source tree was modified.
- Existing G10 implementation and evidence remain intact.

## 10. Source and Git Preservation

- Branch and HEAD unchanged.
- Index empty.
- Inherited dirty worktree preserved.
- `git diff --check` passed.
- V51/V52/V53 hashes remain unchanged:
  - V51 `0d38b0db50874236b2bc254cd15cebe7d12a0de0df41419cf7455e2ab93c2c85`
  - V52 `722a4886f2fd125f243920da5e633bdec0726e27936187fe2112e38c3955d2ee`
  - V53 `15b856ad6fe8d5f2c999617f255b546b30a457d7ac2a9270bcca7db739d640d3`
- No fetch, branch switch, reset, stash, clean, stage, commit, push, merge or PR occurred.

## 11. Smallest Unblocking Decision

`APPROVAL REQUIRED`

**Current Behavior:** the approved disposable database exists and is marked, but the run identity is
missing. Recovery and attestation refuse, and no non-destructive identity-minting operation exists.  
**Problem:** acceptance cannot distinguish current clean run ownership from the suspect manual review
schema without a new, legitimately issued identity.  
**Recommended Option:** authorise one exact implementation of either:

1. a new disposable database designation accepted by both safety guards and created through the
   existing `create → backend → verify → attest` lifecycle; or
2. a reviewed `recover-identity` operation that preserves `pathome_g10_review`, archives the historical
   sentinel identity, generates a fresh identity, and updates the sentinel in one guarded transaction.

**Alternatives:** deleting/recreating the existing database is rejected because it would destroy the
suspect schema evidence. Reusing `20261013T124820-98553`, editing the sentinel manually, or creating a
run-ID file by hand is rejected as fabricated provenance.  
**Impact and Scope:** safety-scaffold/configuration change only; no product API, business rule or
migration change is implied.

## 12. Completion Status

- Environment incident assessment: **AGENT-OBSERVED**
- Suspect services: **CONTAINED**
- Suspect schema/data: **PRESERVED**
- Legitimate attestation: **BLOCKED [17]**
- Database mutations: **NONE**
- Genuine G10 verification: **NOT TESTED**
- Connected browser: **NOT TESTED**
- Owner application startup: **BLOCKED / intentionally not running**
- Independent acceptance: **NOT CLAIMED**
- Release readiness: **NOT CLAIMED**

Web V1 remains **HIGH RISK** for 31 October 2026. Native Tenant/GE remains **AT RISK** for
15 November 2026.

No Sol review should be dispatched until the owner resolves the exact run-identity gate and the
genuine witnesses complete.

**STOP.**
