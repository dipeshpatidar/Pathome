# Pathome OE integration environment (Package 1)

A dedicated, reproducible PostgreSQL environment for genuine Spring Boot / PostgreSQL
integration work on the Operations (OE) surface, completely separate from the preserved
`pathome_db` development database.

This package delivers **only** the safe test environment: the target guard, the disposable
database lifecycle, genuine staff provisioning through supported APIs, the isolation and
invariant proofs, and this documentation. It implements no login change, no Team routing,
no Team transfer, no Workforce Administration, and no automatic Team selection.

## What it never touches

| Protected | Why |
| --- | --- |
| `pathome_db` | The preserved local development database. Fingerprinted before and after every run. |
| `pathome_autonomous_v2_test`, `divyavastu_db`, `hotel_booking`, `postgres`, `template0/1` | Existing local databases that are not designated disposable. |
| Any remote host | A remote target can only be production or staging, so it is refused outright. |
| `/private/tmp/pathome-dev/reset.sh` | Never executed. The existing harness there and its results are left exactly as found. |
| `frontend-web/browser-verification/**` | Both existing browser harnesses and their results are preserved unmodified. |
| Staff grants, audit events | Never written, rewritten or deleted outside `POST /api/v1/staff/admin/**` and the first-Admin bootstrap. |

## Layout

```text
tools/oe-integration-env/
├── README.md                     this file
├── oe-test.env                   the explicit designation and opt-in (no secrets)
├── run.sh                        single entry point for every command
├── lib/db-guard.sh               fail-closed target identity verification
├── lib/provision.sh             genuine staff provisioning through supported APIs
├── lib/verify.sh                 the isolation and invariant proofs
└── sql/00-disposable-marker.sql  the positive disposable marker
```

Runtime artefacts live outside the repository, in `PATHOME_OE_TEST_RUN_DIR`
(default `/private/tmp/pathome-oe-test`): `secrets.env`, `backend.log`, `backend.pid`, and
`evidence/`. Disposable credentials are generated there at run time and are never committed.

## Quick start

```bash
bash tools/oe-integration-env/run.sh doctor        # tooling, designation, guard verdict
bash tools/oe-integration-env/run.sh fingerprint before
bash tools/oe-integration-env/run.sh create        # create + marker + legacy v1 baseline
bash tools/oe-integration-env/run.sh backend       # Flyway V2-V49 + first-Admin bootstrap
bash tools/oe-integration-env/run.sh provision     # genuine staff identities
bash tools/oe-integration-env/run.sh verify        # isolation and invariant proofs
bash tools/oe-integration-env/run.sh teardown      # guarded drop
```

To repeat the whole environment deterministically, replace `create` with `recreate`
(guarded teardown followed by create). Re-running `provision` against a live database is
idempotent: existing identities are signed in and existing grants are left untouched, so
grant and audit history accumulates rather than being reset.

## The target guard

`lib/db-guard.sh` is the single gate in front of every server-touching operation. It is
positive verification only: a target is usable solely when it is *proven* to be the
designated disposable database. Anything unstated, mistyped, remote, protected or ambiguous
is refused before any DDL or destructive statement runs.

Rules, in order:

1. `PATHOME_OE_TEST_ALLOW_DISPOSABLE` must be exactly `true` (explicit opt-in).
2. `PATHOME_OE_TEST_DB` must be set (explicit designation; nothing is defaulted).
3. The host must be a local loopback address.
4. The name must match `^pathome_oe_test_[a-z0-9_]{1,40}$`.
5. The name must not be a protected local database.
6. The name must contain no ambiguous environment token (`prod`, `stage`, `live`, `dev`, …).
7. The live connection must report `current_database()` equal to the designation.
8. For destructive operations only, the database must carry the positive marker
   `pathome_oe_test_env.env_marker = 'DISPOSABLE'` for its own name.

Refusal codes are stable so they can be asserted:

| Code | Meaning |
| --- | --- |
| 10 | Disposable opt-in not set |
| 11 | No target designated |
| 12 | Non-local host |
| 13 | Name does not match the disposable pattern |
| 14 | Protected local database |
| 15 | Ambiguous environment token in the name |
| 16 | Live connection is not the designated database |
| 17 | Positive disposable marker missing (destructive operations only) |
| 18 | Connection failed |
| 19 | Application datasource is not the designated disposable database |

Codes 14 and 19 are defensive: rule 4 already rejects every protected name, and the JDBC
check re-asserts the datasource immediately before the backend is launched.

`run.sh backend` constructs the JDBC URL from the designation itself and refuses to start
otherwise, and the launching subshell re-asserts that the override took effect and that
`pathome_db` did not leak in from `.env.local`.

## Schema strategy

The disposable database is built the same supported way `pathome_db` was:

1. the recorded pre-Flyway legacy schema (`backend/src/test/resources/db/baseline/pathome-v1-legacy-core.sql`),
2. `PATHOME_DATABASE_ALLOW_FLYWAY_BASELINE=true` so `SafeFlywayMigrationConfig` verifies the legacy
   tables and baselines the history at version 1,
3. Flyway migrations `V2`–`V49` applied by the application at start-up.

`verify` proves this reproduced the normal schema: identical migration count, identical
applied version sequence, no failed migration, identical public table set, and identical
public column set as `pathome_db`.

### Required launch property: `spring.jpa.hibernate.ddl-auto=none`

`run.sh backend` pins `SPRING_JPA_HIBERNATE_DDL_AUTO=none` for the disposable launch. This is
necessary, not cosmetic:

* `application.yml` defaults `spring.jpa.hibernate.ddl-auto` to `update`.
* The v1 legacy baseline declares `property_media_assets.caption` as `TEXT` while the entity
  maps `varchar(255)`, so Hibernate emits `ALTER TABLE property_media_assets ALTER COLUMN caption …`.
* PostgreSQL refuses that ALTER with `cannot alter type of a column used by a view or rule`,
  because migration `V9` created `property_media_debug_view` over that column. The connection
  is then marked broken and start-up fails.
* `pathome_db` only boots because earlier Hibernate `update` runs had already converged those
  columns. A freshly migrated database cannot be converged this way.

In this environment the schema is authoritative from Flyway, so Hibernate schema tooling is
disabled for the launch. This is a launch-time property only: no source file, entity,
migration, or application default is changed, and it is the same setting the repository's
existing PostgreSQL integration tests already use
(`OwnedOperationsHttpPostgresTest`, `VisitOperationsPostgresIntegrationTest`).

Changing the shipped `ddl-auto` default, or reconciling the legacy baseline with the entity
mapping, is a production configuration change and is deliberately **not** made here.

## Provisioned identities

Authority is created only by the first-Admin bootstrap and `POST /api/v1/staff/admin/**`
(`provisioning_source` `INITIAL_BOOTSTRAP` / `ADMIN_API`). `staff_access_grants` is never
written, edited or deleted by hand.

| Identity | Authority | Purpose |
| --- | --- | --- |
| `codex.oe.env.admin@pathome.test` | `STAFF_ADMIN@GLOBAL` (bootstrap) | provisions and inspects |
| `codex.oe.env.cityadmin@pathome.test` | `CITY_TEAM_ADMIN@CITY(indore)` | city-scoped Sub-Admin |
| `codex.oe.env.intake@pathome.test` | `OPS_INTAKE@CITY(indore)` | city-scoped intake |
| `codex.oe.env.nogrant@pathome.test` | active staff profile, no grant | authorised-but-unprivileged control |

The City scope is resolved from the migration-seeded `supported_cities` registry
(`V43`), so the environment creates no hand-written fixture rows at all. `OPS_COORDINATE`
and `OPS_SUPERVISE` require `TEAM` scope and an `operating_teams` row; Team records are
provisioned by a later package, and `verify` asserts that no Team-scoped grant exists here.

## Verification

`run.sh verify` executes every proof against the live server and exits non-zero on any
failure. Nothing is reported as passing unless it actually ran.

1. **Guard refusals** — `pathome_db`, an unrelated local database, the maintenance database,
   a staging-shaped name, an ambiguous name, an unprefixed name and a remote host are all
   refused with their documented codes; opt-in and designation are both required.
2. **Live identity** — the connection reports its own name as the designation and carries
   exactly one `DISPOSABLE` marker.
3. **Unmarked databases cannot be destroyed** — a freshly created probe database is refused
   with code 17 while it has no marker, accepted once the marker is planted, and removed.
4. **Schema reproduction** — migration count, applied sequence, table set and column set all
   match `pathome_db`; column-type divergence is measured and printed rather than hidden.
5. **Genuine provisioning** — the bootstrap Admin signs in, each identity exists on the
   disposable database and is absent from the preserved one, and the provisioned Sub-Admin
   reads its `CITY_TEAM_ADMIN@CITY(indore)` capability from the live API.
6. **Immutable grants and audit history** — `DELETE` on `staff_access_grants`, an update of a
   grant fact, and `DELETE` on `operational_audit_events` are each rejected by the database
   triggers, grant and audit row counts are unchanged by those rejected probes, and nothing
   was revoked, so history accumulates across runs.
7. **Preservation** — the `pathome_db` data and schema digests are identical to the
   pre-run recording taken by `run.sh fingerprint before`.

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| `REFUSED [11]` | `PATHOME_OE_TEST_DB` is unset. Source `oe-test.env` or set `PATHOME_OE_TEST_ENV_FILE`. |
| `REFUSED [17]` | The database has no disposable marker. It was not created by `run.sh create`, so it is not provably disposable; remove it manually and recreate. |
| `REFUSED [19]` | The application datasource is not the designated database. Only `run.sh backend` is supported; never export `SPRING_DATASOURCE_URL` by hand. |
| `REFUSED [12]` | `PATHOME_OE_TEST_DB_HOST` is not loopback. Remote targets are never valid here. |
| Backend exits with `cannot alter type of a column used by a view or rule` | `ddl-auto` is not `none`. Use `run.sh backend`, which pins it. |
| `backend failed to start` | Read `$PATHOME_OE_TEST_RUN_DIR/backend.log`. Port 8080 in use is the usual cause. |
| `pathome_db ... CHANGED` | Something reached the preserved database outside this tooling. Investigate before continuing. |
| `could not connect to server` | PostgreSQL is not running on `PATHOME_OE_TEST_DB_HOST:PATHOME_OE_TEST_DB_PORT`. |

## Boundaries

* Not covered: Team provisioning, Team routing, Team transfer, Workforce Administration,
  login changes, automatic Team selection, and any production application behaviour.
* `run.sh verify` and the browser harnesses are complementary. The browser suites in
  `frontend-web/browser-verification/` are preserved but are exercised by a later package.
* No commit, push, merge, stash or reset is performed by anything in this directory.
