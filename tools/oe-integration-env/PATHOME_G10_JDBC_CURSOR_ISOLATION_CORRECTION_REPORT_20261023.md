# PATHOME G10 JDBC Cursor Isolation Correction

**Date:** 23 October 2026 IST

**Status:** IMPLEMENTED / AGENT-TESTED / GENUINE-INTEGRATION-VERIFIED

**Independent acceptance:** NOT CLAIMED

## 1. Outcome

The approved P2 result-returning cursor escape is closed in
`RunOwnedSchemaDataSource`. Any JDBC method result that is a `ResultSet`, including callable
`getObject(...)` and a cursor returned by another guarded cursor, is now wrapped at the runtime return
boundary. Nulls, scalars, supported conversions and driver exceptions remain unchanged.

The correction is isolated to the approved JDBC test support and its focused tests. No production
business, API, DTO, migration, frontend, permission, encryption, tracker or deployment change was
made.

## 2. Repository and preflight

- Repository: `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`
- Branch: `feature/phase2a-oe-ui-integration`
- Starting and current HEAD: `a943775e07a153087d0b38c315db20e18b30aec5`
- Remote branch at preflight: the same SHA.
- The inherited worktree was already substantially dirty. All existing changes were preserved.
- Git index entries before and after: `0`.
- Designated database: `pathome_oe_test_g10_20261022`
- Endpoint: `jdbc:postgresql://127.0.0.1:5432/pathome_oe_test_g10_20261022`
- Run identity: `17cdb6d3-ef16-4b67-9f53-73725c859a41`
- Positive disposable marker: exactly one
- Existing Flyway version: 53
- Existing retained backend PID: 38262, left running and untouched.

Read-only preflight evidence:
`tools/oe-integration-env/evidence/g10-jdbc-cursor-isolation-20261023/preflight-status.log`.

## 3. Original reproduction and root cause

The former `StatementHandler` wrapped a returned `ResultSet` only when the invoked method name was
`executeQuery`, `getResultSet` or `getGeneratedKeys`. PostgreSQL callable cursors can return the same
capability through `getObject(int)`, typed `getObject(int, ResultSet.class)` and other overloads, so
the returned raw cursor escaped.

The former cursor handler also delegated nested `ResultSet.getObject(...)` results directly. A raw
cursor could therefore expose another raw cursor, and then its raw Statement and Connection.

Before production correction, the inert callable/nested tests ran against the unchanged wrapper:

```text
Tests run: 9, Failures: 2, Errors: 0, Skipped: 0
```

Both expected failures were the raw-ResultSet discriminator. The exact pre-fix wrapper and log are in:

- `tools/oe-integration-env/evidence/g10-jdbc-cursor-isolation-20261023/pre-fix/RunOwnedSchemaDataSource.java`
- `tools/oe-integration-env/evidence/g10-jdbc-cursor-isolation-20261023/pre-fix/unit-reproduction.log`

The pre-fix wrapper SHA-256 is
`88b12d06765693c8f7b20768629c5e39d66a939b1a067247e55ca64ac3400c5e`.

## 4. Architecture and implementation

The implementation keeps the existing datasource, disposable identity checks and PostgreSQL role
boundary. It changes only result handling:

1. Every successful `Statement`/`PreparedStatement`/`CallableStatement` method result is inspected
   at runtime for `ResultSet`.
2. Every successful guarded `ResultSet` method result receives the same inspection.
3. `guardResult` wraps only actual `ResultSet` instances, preserving null and scalar values.
4. A reusable `ResultSetHandler` recursively guards nested cursor results.
5. Guarded cursor and statement detection prevents double wrapping.
6. Cursor `getStatement()` returns the existing guarded statement; Statement `getConnection()`
   re-verifies facts and returns a guarded Connection.
7. Cursor and statement `unwrap(...)` remain refused.
8. Delegate invocation unwraps `InvocationTargetException`, preserving the original JDBC exception.
9. Close remains direct delegation, preserving normal resource lifecycle and pooled reuse.

The PostgreSQL-enforced least-privilege boundary remains authoritative. The JDBC wrapper is
additional defense, not a replacement.

## 5. Exact source changes

`backend/src/test/java/com/indore/pathome/spaces/support/RunOwnedSchemaDataSource.java`

- Lines 196-198: idempotent guarded Statement reuse.
- Lines 239-240: runtime guarding of every Statement result, replacing the method-name allowlist.
- Lines 244-248: guarded Statement recognition.
- Lines 260-277: guarded ResultSet recognition and the central runtime `guardResult` boundary.
- Lines 279-310: reusable ResultSet handler with recursive nested-result guarding, guarded
  Statement identity and refused unwrap.

Final source SHA-256:
`b3dbb81555bb2fa31be39c52f6dd2f259296277deb688249c3d1ebd4da5d57e6`.

`backend/src/test/java/com/indore/pathome/spaces/support/RunOwnedSchemaDataSourceTest.java`

- Lines 144-181: callable `getObject(int)`, typed and named nested cursor chain, guarded identity,
  no double wrapping and close propagation.
- Lines 183-218: null, scalar, typed, map/conversion and original driver-exception behavior.
- Lines 220-260: generated keys and shared guarded-cursor/statement/connection assertions.

`backend/src/test/java/com/indore/pathome/spaces/support/RunOwnedSchemaDataSourcePostgresTest.java`

- Lines 64-77: pooled reborrow after search-path drift is repinned.
- Lines 163-192: genuine transaction-scoped PostgreSQL refcursor through untyped and typed
  `getObject`.
- Lines 194-212: guarded handle chain, refused unwraps, normal iteration/close, scalar reads,
  supported typed conversion and an actual unsupported conversion exception.

Final test hashes:

```text
f21037d435c79dd85a46346a7fbe43363db549c65a0175f2092d2664cce63b86  RunOwnedSchemaDataSourceTest.java
9c68dd47ea02ce4acbbaec737e15a5714db9fa8257a0c7c301b49699b99d5774  RunOwnedSchemaDataSourcePostgresTest.java
```

## 6. Required failure-class matrix

| Scenario | Result | Evidence |
|---|---|---|
| Callable `getObject(int)` returns ResultSet | PASS | Inert unit negative control + genuine PostgreSQL |
| Callable typed `getObject(int, ResultSet.class)` | PASS | Inert unit + genuine PostgreSQL |
| Supported named `getObject` overload | PASS | Guarded nested cursor `getObject(String)` unit; genuine typed named `ResultSet` scalar conversion in PostgreSQL |
| Map/conversion overload | PASS | Inert unit preserves supported conversion and guards a returned ResultSet |
| `getObject()` returns null | PASS | Inert unit |
| String/number/other scalar | PASS | Inert unit + genuine PostgreSQL scalar reads |
| Unsupported typed conversion | PASS | Original inert exception identity + genuine PostgreSQL SQLException |
| Outer ResultSet returns inner cursor | PASS | Inert unit |
| Repeated nested cursor chain | PASS | Three-level inert chain; every boundary guarded |
| Cursor `getStatement()` | PASS | Unit + genuine PostgreSQL |
| Statement `getConnection()` | PASS | Unit + genuine PostgreSQL |
| Cursor and related `unwrap()` | PASS | Cursor, Statement and Connection refusals in unit + PostgreSQL |
| Existing metadata `getTables()` / `getColumns()` | PASS | Existing wrapper PostgreSQL regression |
| Prepared/callable query and interface typing | PASS | Existing wrapper unit/PostgreSQL regression |
| Generated keys | PASS | Inert unit, guarded cursor and close |
| Cursor/Statement/Connection close | PASS | Raw-delegate close verification and genuine close/iteration |
| Connection reborrow/replacement | PASS | Genuine search-path drift then reborrow repinning |
| Schema drift before operation | PASS | Focused genuine missing-schema/search-path refusals |
| PostgreSQL transaction-scoped refcursor | PASS | Real disposable PostgreSQL function and transaction |
| Restricted suite-role privilege | PASS | Every provisioning call ran exact role/schema/privilege verification; lifecycle suite passed |
| Removed guard discriminator | PASS | Pre-fix isolated copy failed 2/9; corrected wrapper passed |

PostgreSQL JDBC 42.7.5 explicitly reports
`PgCallableStatement.getObject(String, Class<T>)` as unsupported. No genuine execution is claimed for
that unavailable overload; the available method/result class is covered by unit behavior and the
driver exception remains untouched.

## 7. Genuine PostgreSQL evidence

Every write-bearing run verified the exact database, loopback endpoint, approved port, single
disposable sentinel and run ID before Maven started. Environment destinations from `.env.local` were
neutralised by the existing isolation harness.

The refcursor test created its function only inside a generated run-owned schema, used the restricted
generated suite login, opened real transaction-scoped refcursors, iterated rows and then relied on the
existing owned-schema cleanup. It did not write retained `public` data.

Final focused result:

```text
RunOwnedSchemaDataSourceTest                 10 tests, 0 failures, 0 errors, 0 skipped
RunOwnedSchemaDataSourcePostgresTest          8 tests, 0 failures, 0 errors, 0 skipped
OwnedTestSchemaRolePostgresTest               3 tests, 0 failures, 0 errors, 0 skipped
LeadHttpPostgresTest V52->V53 bootstrap       1 test,  0 failures, 0 errors, 0 skipped
Total                                        22 tests, 0 failures, 0 errors, 0 skipped
```

Suite-role verification executed by each `OwnedTestSchemaRole.provision` asserted exact user and
database identity, owned-schema CREATE, no `public` CREATE, `public` USAGE and sentinel SELECT only,
no sentinel writes, no database CREATE, database TEMP, no role memberships, exact owned-schema count,
and no elevated role flags.

## 8. Negative control and regressions

- The isolated pre-fix copy failed the callable and nested escape discriminator exactly as expected.
- After correction, the same direct callable, typed, named nested and repeated-chain cases pass.
- Existing metadata, callable query, multi-statement refusal, generated-key and missing-schema paths
  remain green.
- Flyway validated 53 migrations and passed the V52 to V53 bootstrap through
  `forFrameworkBootstrap`.
- `mvn -q -f backend/pom.xml -DskipTests test-compile` passed.
- Scoped whitespace checks passed. The target Java files are untracked in the inherited worktree, so
  they were checked with `git diff --no-index --check /dev/null <file>`; no whitespace diagnostics
  were emitted.

## 9. Cleanup and preservation

Final read-only catalog result:

```text
suite_roles=0
owned_test_schemas=0
disabled_noninternal_triggers=0
disposable_markers=1
```

No suite role, generated schema, foreign probe schema or disabled non-internal trigger remains.
Retained backend PID 38262 remains running and was not restarted. No retained Lead, consent row,
history, migration or permission model was modified.

No access was made to `pathome_db` or the historical
`pathome_oe_test_integration`. No production database permission, public property origin, encryption
key, provider, frontend file, API/DTO or schema migration was changed.

## 10. Commands and evidence

Representative preflight:

```bash
PATHOME_OE_TEST_ENV_FILE=tools/oe-integration-env/oe-test-g10-20261022.env \
  bash tools/oe-integration-env/run.sh status
```

Pre-fix inert reproduction:

```bash
mvn -f backend/pom.xml -Dtest=RunOwnedSchemaDataSourceTest test
```

Final focused run, launched through the existing designation and isolation helpers:

```bash
mvn -o -q -f backend/pom.xml \
  -Dtest="RunOwnedSchemaDataSourceTest,RunOwnedSchemaDataSourcePostgresTest,OwnedTestSchemaRolePostgresTest,LeadHttpPostgresTest#v52LegacySourceRowsRemainExplicitlyVersionedThroughV53Upgrade" \
  -DfailIfNoTests=false test
```

Evidence directory:
`tools/oe-integration-env/evidence/g10-jdbc-cursor-isolation-20261023/`

Important evidence hashes:

```text
816486c58ffebd850cc8fb9279d71f4e9cce1ca46b48655f3fd6db503de1f61e  pre-fix/unit-reproduction.log
e391c33466d1ba0bc720c091f59e9c994d2139048160d94fc1572a3baed37a4b  focused-regression.log
eb323f840c7b4faf9697d69aab004649f05d4bffd4ee3cf4be32e280cb5fc234  cleanup-audit.log
```

## 11. Checks not run

- Full G10 frontend/browser regression: `NOT TESTED`; no frontend dependency was demonstrated.
- Physical-device testing: `NOT TESTED`; no UI/device change.
- Production/performance testing: `NOT TESTED`; test-only support correction.
- GPT-6.1 Sol High independent recheck: `NOT TESTED` by design.
- Canonical tracker synchronization: `NOT TESTED`; exact workbooks were not found locally and were
  neither substituted nor edited.

The first two genuine attempts stopped in test-function setup because PostgreSQL/JDBC rejected the
initial cursor-variable SQL. The test was corrected to a local PL/pgSQL refcursor declaration. Those
attempts made no product claim; the final genuine run above passed.

## 12. Scope, security, performance and UI

- Scope: only the approved test helper, focused tests, evidence and `chat_gpt_changes`.
- Authorization/isolation: unchanged server-side and PostgreSQL-enforced least privilege.
- Cache/query impact: none; no production query or cache changed.
- Runtime overhead: limited to test-only proxy checks for returned JDBC results.
- UI/UX: not applicable; no user-facing behavior changed.
- Git: no stage, commit, push, merge, reset, stash, clean or branch switch.

## 13. Tracker and delivery impact

The exact canonical Cross-Role and Visit Session workbooks were not present locally, so no outdated
workbook was substituted and neither tracker was edited. Proposed synchronization:

- G10 isolation issue and Finding 4 P2 callable/nested cursor correction: implemented and
  agent/genuine verified.
- Finding 4 independent acceptance: remains open pending focused Sol recheck.
- Remaining five-finding independent acceptance: still pending.
- OE #10 / Batch B and Batch D status: no status promotion from this test-only correction.
- Web V1 delivery: unchanged; full release acceptance remains open.
- 31 October Web V1: HIGH RISK.
- 15 November native Tenant/GE: AT RISK.

## 14. Remaining issue and recommended independent recheck

No remaining confirmed defect in the approved callable/nested ResultSet failure class was found.
Independent acceptance is the remaining gate, not an implementation claim.

Recommended exact Sol scope:

1. Inspect `RunOwnedSchemaDataSource` runtime `guardResult`, guarded-result idempotence and recursive
   cursor handling.
2. Rerun `RunOwnedSchemaDataSourceTest` and `RunOwnedSchemaDataSourcePostgresTest`.
3. Verify `getObject(int)`, typed `getObject(int, ResultSet.class)`, nested cursor chains,
   generated keys, metadata reads, unwrap refusal, close/reborrow behavior and genuine refcursor
   iteration.
4. Confirm role/schema cleanup and the absence of production, permission, tracker or Git changes.

Do not dispatch Sol automatically. This report stops at the approved independent-review boundary.
