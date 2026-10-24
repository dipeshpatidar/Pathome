#!/usr/bin/env bash
#
# Pathome OE integration environment - single entry point.
#
# Creates, migrates, provisions, verifies and destroys one disposable PostgreSQL database
# that is provably separate from every protected local database. Every server-touching step runs
# through lib/db-guard.sh first and aborts on any refusal.
#
# This environment is disposable-only. It never reads, dumps, fingerprints or compares against a
# protected database, and no command here makes any claim about one. Protected database names are
# held as explicit deny rules in lib/db-guard.sh, and the launch configuration is isolated by
# lib/env-isolation.sh, which never sources `.env.local`.
#
#   run.sh doctor        report tooling, target designation and guard verdict
#   run.sh preflight     nonconnecting prerequisites, offline compile and approved local build
#   run.sh recover       prove ownership and archive interrupted/dead runtime artifacts
#   run.sh recover-probe quarantine a legacy unowned probe; never bypass marker/ownership proof
#   run.sh plan          static plan: deny rules, destination register, command sequence
#   run.sh create        create the designated disposable database (refuses if it exists)
#   run.sh recreate      guarded teardown + create (the repeatability cycle)
#   run.sh backend       start Spring Boot against the disposable database
#   run.sh stop-backend  stop the Spring Boot instance this tool started
#   run.sh provision     provision disposable staff identities through supported APIs
#   run.sh verify        run the isolation, migration, provisioning and immutability proofs
#   run.sh attest        record auditable evidence of the actual disposable test target
#   run.sh teardown      drop the designated disposable database (guarded, destructive)
#   run.sh status        show the current state of the disposable environment

set -euo pipefail

OE_TOOL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OE_REPO_ROOT="$(cd "$OE_TOOL_DIR/../.." && pwd)"
OE_ENV_FILE="${PATHOME_OE_TEST_ENV_FILE:-$OE_TOOL_DIR/oe-test.env}"
OE_LEGACY_BASELINE="$OE_REPO_ROOT/backend/src/test/resources/db/baseline/pathome-v1-legacy-core.sql"
OE_MARKER_SQL="$OE_TOOL_DIR/sql/00-disposable-marker.sql"
OE_MIGRATION_DIR="$OE_REPO_ROOT/backend/src/main/resources/db/migration"
OE_EXPECTED_SCHEMA="$OE_TOOL_DIR/expected-schema.txt"

OE_BACKEND_PORT="${PATHOME_OE_TEST_BACKEND_PORT:-8080}"
OE_API_BASE_URL="http://localhost:$OE_BACKEND_PORT/api/v1"

# shellcheck source=lib/db-guard.sh
. "$OE_TOOL_DIR/lib/db-guard.sh"
# shellcheck source=lib/env-isolation.sh
. "$OE_TOOL_DIR/lib/env-isolation.sh"
# shellcheck source=lib/provision.sh
. "$OE_TOOL_DIR/lib/provision.sh"
# shellcheck source=lib/verify.sh
. "$OE_TOOL_DIR/lib/verify.sh"

oe_log() {
  printf '%s\n' "$*"
}

oe_die() {
  printf 'ERROR  %s\n' "$*" >&2
  exit 1
}

oe_load_designation() {
  oe_runtime_load_designation
}

oe_require_tools() {
  local tool
  for tool in psql curl jq mvn java node npm uuidgen lsof; do
    PATH="$OE_RUNTIME_PATH" type -P "$tool" >/dev/null 2>&1 || oe_die "Required trusted tool '$tool' is missing."
  done
}

oe_ensure_run_dir() {
  oe_runtime_validate_dir
  umask 077
  mkdir -p "$PATHOME_OE_TEST_RUN_DIR"/{evidence,home,tmp}
  chmod 700 "$PATHOME_OE_TEST_RUN_DIR"
}

oe_random_secret() {
  od -An -N24 -tx1 /dev/urandom | tr -d ' \n'
}

oe_random_aes_key() {
  dd if=/dev/urandom bs=32 count=1 2>/dev/null | base64 | tr -d '\n'
}

# Disposable credentials live only in the ephemeral run directory, never in the repository.
# Every value is generated for this run: the bootstrap administrator, the shared staff password and
# the JWT signing secret this environment signs its own tokens with. No secret here is ever reused
# from `.env.local` or from another environment.
oe_ensure_secret_line() { # <file> <key> <value>
  grep -q "^$2=" "$1" 2>/dev/null || printf '%s=%s\n' "$2" "$3" >> "$1"
}

oe_ensure_contact_encryption_identity() { # <secrets-file>
  local file="$1"
  local has_key=0 has_id=0
  grep -q '^OE_CRM_CONTACT_ENCRYPTION_KEY=' "$file" 2>/dev/null && has_key=1
  grep -q '^OE_CRM_CONTACT_ENCRYPTION_KEY_ID=' "$file" 2>/dev/null && has_id=1
  if [ "$has_key" != "$has_id" ]; then
    oe_die 'CRM contact encryption identity is partial; existing key material is never replaced automatically'
  fi
  if [ "$has_key" = 0 ]; then
    {
      printf 'OE_CRM_CONTACT_ENCRYPTION_KEY=%s\n' "$(oe_random_aes_key)"
      printf 'OE_CRM_CONTACT_ENCRYPTION_KEY_ID=%s\n' 'primary'
    } >> "$file"
  fi
}

oe_ensure_secrets() {
  local secrets="$PATHOME_OE_TEST_RUN_DIR/secrets.env"
  umask 077
  [ ! -L "$secrets" ] || oe_die 'symlinked secrets file'
  local run_id
  run_id="$(< "$PATHOME_OE_TEST_RUN_DIR/run-id")"
  if [ -s "$secrets" ] && ! grep -qx "OE_RUN_ID=$run_id" "$secrets"; then
    oe_die 'credentials belong to another run; recreate generates fresh credentials'
  fi
  if [ ! -s "$secrets" ]; then
    {
      printf 'OE_RUN_ID=%s\n' "$run_id"
      printf 'OE_BOOTSTRAP_ADMIN_EMAIL=%s\n' 'codex.oe.env.admin@pathome.test'
      printf 'OE_BOOTSTRAP_ADMIN_PASSWORD=%s\n' "$(oe_random_secret)"
      printf 'OE_BOOTSTRAP_OPERATOR_REFERENCE=%s\n' 'CODEX_OE_INTEGRATION_ENV'
      printf 'OE_STAFF_PASSWORD=%s\n' "$(oe_random_secret)"
      printf 'OE_JWT_SECRET=%s\n' "$(oe_random_secret)"
      if [ "${PATHOME_OE_TEST_ACCEPTANCE:-}" = 'routing-20261023' ]; then
        printf 'PATHOME_OE_TEST_DB_PASSWORD=%s\n' "$(oe_random_secret)"
        printf 'PATHOME_OE_TEST_DB_MIGRATOR_PASSWORD=%s\n' "$(oe_random_secret)"
      fi
      # Approval is validated before setup; copy literal media values from the approved private file.
      # Routing acceptance performs no provider operation and uses inert local identities instead.
      if [ "${PATHOME_OE_TEST_ACCEPTANCE:-}" != 'routing-20261023' ]; then
        cat "$PATHOME_OE_TEST_MEDIA_ENV_FILE"
        printf '\n'
      fi
      printf '# Optional: only for an explicitly approved disposable media account.\n'
      printf '# PATHOME_OE_TEST_MEDIA_MODE=disposable-account\n'
      printf '# PATHOME_OE_TEST_ALLOW_EXTERNAL_MEDIA_UPLOAD=true\n'
      printf '# OE_TEST_CLOUDINARY_CLOUD_NAME=\n'
      printf '# OE_TEST_CLOUDINARY_API_KEY=\n'
      printf '# OE_TEST_CLOUDINARY_API_SECRET=\n'
    } > "$secrets"
  fi
  # Top up keys introduced after an earlier run so existing identities and grants stay valid.
  oe_ensure_secret_line "$secrets" 'OE_JWT_SECRET' "$(oe_random_secret)"
  if [ "${PATHOME_OE_TEST_ACCEPTANCE:-}" = 'routing-20261023' ]; then
    oe_ensure_secret_line "$secrets" 'PATHOME_OE_TEST_DB_PASSWORD' "$(oe_random_secret)"
    oe_ensure_secret_line "$secrets" 'PATHOME_OE_TEST_DB_MIGRATOR_PASSWORD' "$(oe_random_secret)"
  fi
  oe_ensure_contact_encryption_identity "$secrets"
  chmod 600 "$secrets"
  oe_runtime_load_secrets
}

oe_psql_admin() { # <database> <psql args...>
  local database="$1"
  shift
  # The only non-designation database this environment opens is the `postgres` maintenance catalog, and
  # only for catalog-level existence checks. Every other target must pass the deny rules below.
  [ "$database" = 'postgres' ] || oe_guard_assert_not_protected "$database" 'connection target'
  oe_guard_psql "$database" "$PATHOME_OE_TEST_DB_ADMIN_USER" "$@"
}

oe_psql_app() { # <database> <psql args...>
  local database="$1"
  shift
  oe_guard_assert_not_protected "$database" 'connection target'
  oe_guard_psql "$database" "$PATHOME_OE_TEST_DB_APP_USER" "$@"
}

oe_psql_migrator() { # <database> <psql args...>
  local database="$1"
  shift
  oe_guard_assert_not_protected "$database" 'connection target'
  oe_guard_psql "$database" "$PATHOME_OE_TEST_DB_MIGRATOR_USER" "$@"
}

oe_database_exists() {
  oe_guard_resolve
  local count
  count="$(oe_psql_admin postgres -Atc "select count(*) from pg_database where datname = '$PATHOME_OE_TEST_DB'")"
  [ "$count" = '1' ]
}

# --- repository-controlled schema expectations ----------------------------------------------------
# Everything the disposable database is compared against lives in the repository: the Flyway migration
# files, the supported legacy v1 baseline, and the committed expected-schema.txt. Nothing here is
# derived from, or compared with, any database outside this environment.
oe_repo_migration_files() {
  ls "$OE_MIGRATION_DIR"/V*__*.sql 2>/dev/null | awk -F/ '{n=$NF; sub(/^V/,"",n); sub(/__.*/,"",n); print n " " $0}' | sort -n -k1,1 | cut -d' ' -f2-
}

oe_repo_migration_versions() {
  oe_repo_migration_files | sed -E 's#.*/V([0-9]+)__.*#\1#'
}

oe_repo_expected_versions() { # includes the legacy v1 baseline row Flyway records
  printf '1\n'
  oe_repo_migration_versions
}

oe_repo_schema_source_fingerprint() {
  { shasum -a 256 "$OE_LEGACY_BASELINE" 2>/dev/null; shasum -a 256 $(oe_repo_migration_files) 2>/dev/null; } \
    | awk '{print $1}' | sort | shasum -a 256 | awk '{print $1}'
}

# Expected public objects, as declared by the migrations and the legacy baseline. The committed file
# is authoritative and is verified against the migration sources by the static test suite.
oe_repo_expected_tables() { # [base|view]
  local kind="${1:-base}"
  [ -f "$OE_EXPECTED_SCHEMA" ] || oe_die "Expected-schema file missing at $OE_EXPECTED_SCHEMA"
  awk -v kind="$kind" '$1 == kind { print $2 }' "$OE_EXPECTED_SCHEMA" | sort -u
}

cmd_doctor() {
  oe_load_designation
  oe_log '== tooling =='
  local tool
  for tool in psql curl jq mvn java node; do
    if command -v "$tool" >/dev/null 2>&1; then
      oe_log "present  $tool -> $(command -v "$tool")"
    else
      oe_log "MISSING  $tool"
    fi
  done
  oe_log 'note     no database dump utility is required: this environment never dumps any database.'

  oe_log ''
  oe_log '== target designation =='
  oe_guard_describe

  oe_log ''
  oe_log '== guard verdict =='
  if oe_guard_resolve 2>/dev/null; then
    oe_log "ACCEPTED  $(oe_guard_jdbc_url)"
  else
    oe_log 'REFUSED'
  fi

  oe_log ''
  oe_log '== guard refusal proofs =='
  oe_guard_selftest || true

  oe_log ''
  oe_log '== runtime configuration isolation =='
  oe_iso_report

  oe_log ''
  oe_log '== local server =='
  if oe_psql_admin postgres -Atc 'select version()' >/dev/null 2>&1; then
    oe_log "reachable  $(oe_psql_admin postgres -Atc 'select current_user')@$(oe_guard_jdbc_url)"
    oe_log "databases  $(oe_psql_admin postgres -Atc "select string_agg(datname, ', ' order by datname) from pg_database")"
    oe_log '           (catalog names only; no protected database is ever opened)'
    if oe_database_exists; then
      oe_log "disposable  $PATHOME_OE_TEST_DB exists"
    else
      oe_log "disposable  $PATHOME_OE_TEST_DB does not exist yet"
    fi
  else
    oe_log "unreachable  $PATHOME_OE_TEST_DB_HOST:$PATHOME_OE_TEST_DB_PORT"
  fi
}

# Static plan: what a run would do, what is denied, and which destinations are replaced. No server,
# database, container or external service is contacted.
cmd_plan() {
  oe_load_designation
  oe_require_tools
  oe_ensure_run_dir
  mkdir -p "$PATHOME_OE_TEST_RUN_DIR/evidence"
  local plan="$PATHOME_OE_TEST_RUN_DIR/evidence/static-plan.txt"
  {
    oe_log '=== Pathome OE disposable environment - static plan (no execution) ==='
    oe_log ''
    oe_log '-- guard deny rules (lib/db-guard.sh) --'
    oe_guard_describe
    oe_log ''
    oe_log '-- runtime configuration isolation (lib/env-isolation.sh) --'
    oe_iso_report
    oe_log ''
    oe_log '-- future sequence after independent safety review (see PHASE2A.md) --'
    oe_log '0. Configure the external mode-600 disposable-media file and explicit local-build approval; then run run.sh preflight'
    oe_log '1. bash tools/oe-integration-env/run.sh recreate'
    oe_log '   A current preflight receipt is mandatory before any destructive setup; never source credential files.'
    oe_log '2. bash tools/oe-integration-env/run.sh backend'
    oe_log '3. bash tools/oe-integration-env/run.sh provision'
    oe_log '4. bash tools/oe-integration-env/run.sh verify'
    oe_log '5. bash tools/oe-integration-env/run.sh attest'
    oe_log '6. PATHOME_OE_TEST_PHASE2A_LIVE=true PATHOME_OE_TEST_FIXTURE_ROLES=true bash tools/oe-integration-env/phase2a.sh acceptance'
    oe_log '7. PATHOME_OE_TEST_PHASE2A_LIVE=true bash tools/oe-integration-env/phase2a.sh browser'
    oe_log '8. bash tools/oe-integration-env/run.sh stop-backend'
    oe_log '9. bash tools/oe-integration-env/run.sh teardown'
  } | tee "$plan"
  oe_log ''
  oe_log "written to $plan"
}

cmd_create() {
  oe_load_designation
  oe_guard_resolve
  oe_require_tools
  oe_ensure_run_dir
  node "$OE_TOOL_DIR/lib/readiness.mjs" require
  oe_runtime_checkpoint PREFLIGHT_VERIFIED

  if oe_database_exists; then
    oe_die "Disposable database '$PATHOME_OE_TEST_DB' already exists. Use 'recreate' for the guarded teardown+create cycle, or 'teardown' to remove it."
  fi

  local create_owner="$PATHOME_OE_TEST_DB_APP_USER"
  [ "${PATHOME_OE_TEST_ACCEPTANCE:-}" = 'routing-20261023' ] && create_owner="$PATHOME_OE_TEST_DB_ADMIN_USER"
  oe_log "creating disposable database '$PATHOME_OE_TEST_DB' (owner $create_owner)"
  oe_psql_admin postgres -c "CREATE DATABASE \"$PATHOME_OE_TEST_DB\" OWNER \"$create_owner\""
  oe_runtime_checkpoint DATABASE_CREATED
  local previous="$PATHOME_OE_TEST_RUN_DIR/previous-runs/$(uuidgen | tr '[:upper:]' '[:lower:]')"
  mkdir -p "$previous"
  for artifact in run-id secrets.env; do
    if [ -f "$PATHOME_OE_TEST_RUN_DIR/$artifact" ]; then mv "$PATHOME_OE_TEST_RUN_DIR/$artifact" "$previous/$artifact"; fi
  done
  if [ "${PATHOME_OE_TEST_ACCEPTANCE:-}" = 'routing-20261023' ]; then
    printf '%s\n' "$PATHOME_OE_TEST_RUN_ID" > "$PATHOME_OE_TEST_RUN_DIR/run-id"
  else
    uuidgen | tr '[:upper:]' '[:lower:]' > "$PATHOME_OE_TEST_RUN_DIR/run-id"
  fi
  chmod 600 "$PATHOME_OE_TEST_RUN_DIR/run-id"
  # These are tool-owned ephemeral artifacts; a new database receives a new signing identity.

  oe_ensure_secrets

  # Marker first: from this point on the database is positively identifiable as disposable.
  oe_apply_marker
  oe_runtime_checkpoint SENTINEL_WRITTEN

  if [ "${PATHOME_OE_TEST_ACCEPTANCE:-}" = 'routing-20261023' ]; then
    oe_routing_bootstrap_roles
    oe_psql_admin "$PATHOME_OE_TEST_DB" -c \
      "CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public; CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public" >/dev/null
    oe_psql_admin "$PATHOME_OE_TEST_DB" -c \
      "REVOKE CREATE ON DATABASE \"$PATHOME_OE_TEST_DB\" FROM \"$PATHOME_OE_TEST_DB_MIGRATOR_USER\"" >/dev/null
    oe_log 'applying the supported legacy v1 baseline through the migrator role'
    [ -f "$OE_LEGACY_BASELINE" ] || oe_die "Legacy baseline missing at $OE_LEGACY_BASELINE"
    oe_psql_migrator "$PATHOME_OE_TEST_DB" -q -f "$OE_LEGACY_BASELINE" >/dev/null
  else
    oe_log 'applying the supported legacy v1 baseline'
    [ -f "$OE_LEGACY_BASELINE" ] || oe_die "Legacy baseline missing at $OE_LEGACY_BASELINE"
    oe_psql_app "$PATHOME_OE_TEST_DB" -q -f "$OE_LEGACY_BASELINE" >/dev/null
  fi

  oe_log 'creating canonical supported cities (migration-seeded, not hand-written fixtures)'
  oe_log "CREATE OK  $PATHOME_OE_TEST_DB ready; repository Flyway migrations run on first backend start"
}

oe_apply_marker() {
  local marker_user="$PATHOME_OE_TEST_DB_APP_USER"
  if [ "${PATHOME_OE_TEST_ACCEPTANCE:-}" = 'routing-20261023' ]; then
    marker_user="$PATHOME_OE_TEST_DB_ADMIN_USER"
  fi
  oe_guard_psql "$PATHOME_OE_TEST_DB" "$marker_user" \
    -q \
    -v "oe_database=$PATHOME_OE_TEST_DB" \
    -v "oe_guard_version=$OE_GUARD_VERSION" \
    -v "oe_run_id=$(< "$PATHOME_OE_TEST_RUN_DIR/run-id")" \
    -f "$OE_MARKER_SQL" >/dev/null
}

oe_routing_bootstrap_roles() {
  local roles_sql schema_sql grants_sql
  roles_sql="CREATE ROLE \"$PATHOME_OE_TEST_DB_MIGRATOR_USER\" LOGIN PASSWORD '$PATHOME_OE_TEST_DB_MIGRATOR_PASSWORD' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
CREATE ROLE \"$PATHOME_OE_TEST_DB_APP_USER\" LOGIN PASSWORD '$PATHOME_OE_TEST_DB_PASSWORD' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;"
  oe_psql_admin "$PATHOME_OE_TEST_DB" -c "$roles_sql" >/dev/null
  schema_sql="REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE CREATE, TEMP ON DATABASE \"$PATHOME_OE_TEST_DB\" FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM \"$PATHOME_OE_TEST_DB_APP_USER\";
GRANT CONNECT ON DATABASE \"$PATHOME_OE_TEST_DB\" TO \"$PATHOME_OE_TEST_DB_MIGRATOR_USER\", \"$PATHOME_OE_TEST_DB_APP_USER\";
GRANT CREATE ON DATABASE \"$PATHOME_OE_TEST_DB\" TO \"$PATHOME_OE_TEST_DB_MIGRATOR_USER\";
GRANT TEMP ON DATABASE \"$PATHOME_OE_TEST_DB\" TO \"$PATHOME_OE_TEST_DB_MIGRATOR_USER\";
GRANT USAGE ON SCHEMA public TO \"$PATHOME_OE_TEST_DB_MIGRATOR_USER\", \"$PATHOME_OE_TEST_DB_APP_USER\";
GRANT SELECT ON TABLE public.pathome_oe_test_env TO \"$PATHOME_OE_TEST_DB_APP_USER\";
CREATE SCHEMA \"$PATHOME_OE_TEST_SCHEMA\" AUTHORIZATION \"$PATHOME_OE_TEST_DB_MIGRATOR_USER\";
REVOKE ALL ON SCHEMA \"$PATHOME_OE_TEST_SCHEMA\" FROM PUBLIC;
GRANT USAGE ON SCHEMA \"$PATHOME_OE_TEST_SCHEMA\" TO \"$PATHOME_OE_TEST_DB_APP_USER\";
ALTER ROLE \"$PATHOME_OE_TEST_DB_MIGRATOR_USER\" IN DATABASE \"$PATHOME_OE_TEST_DB\" SET search_path = \"$PATHOME_OE_TEST_SCHEMA\", public;
ALTER ROLE \"$PATHOME_OE_TEST_DB_APP_USER\" IN DATABASE \"$PATHOME_OE_TEST_DB\" SET search_path = \"$PATHOME_OE_TEST_SCHEMA\", public;"
  oe_psql_admin "$PATHOME_OE_TEST_DB" -c "$schema_sql" >/dev/null
  grants_sql="ALTER DEFAULT PRIVILEGES FOR ROLE \"$PATHOME_OE_TEST_DB_MIGRATOR_USER\" IN SCHEMA \"$PATHOME_OE_TEST_SCHEMA\"
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO \"$PATHOME_OE_TEST_DB_APP_USER\";
ALTER DEFAULT PRIVILEGES FOR ROLE \"$PATHOME_OE_TEST_DB_MIGRATOR_USER\" IN SCHEMA \"$PATHOME_OE_TEST_SCHEMA\"
  GRANT USAGE, SELECT ON SEQUENCES TO \"$PATHOME_OE_TEST_DB_APP_USER\";
ALTER DEFAULT PRIVILEGES FOR ROLE \"$PATHOME_OE_TEST_DB_MIGRATOR_USER\" IN SCHEMA \"$PATHOME_OE_TEST_SCHEMA\"
  GRANT EXECUTE ON FUNCTIONS TO \"$PATHOME_OE_TEST_DB_APP_USER\";"
  oe_psql_admin "$PATHOME_OE_TEST_DB" -c "$grants_sql" >/dev/null
}

cmd_recreate() {
  oe_load_designation
  oe_guard_resolve
  oe_require_tools
  node "$OE_TOOL_DIR/lib/readiness.mjs" require
  if oe_database_exists; then
    cmd_teardown
  fi
  cmd_create
}

cmd_teardown() {
  oe_load_designation
  oe_guard_resolve
  oe_require_tools
  oe_ensure_run_dir
  if ! oe_database_exists; then
    for artifact in backend.pid backend-binding.json backend-startup.json; do
      if [ -e "$PATHOME_OE_TEST_RUN_DIR/$artifact" ] || [ -L "$PATHOME_OE_TEST_RUN_DIR/$artifact" ]; then
        oe_die 'database absent but runtime artifacts remain; preserve evidence for explicit ownership resolution'
      fi
    done
    oe_log "nothing to do: '$PATHOME_OE_TEST_DB' does not exist"
    return 0
  fi

  # Destructive path: the target must be provably disposable before anything is dropped.
  local proof_user="$PATHOME_OE_TEST_DB_APP_USER"
  [ "${PATHOME_OE_TEST_ACCEPTANCE:-}" = 'routing-20261023' ] && proof_user="$PATHOME_OE_TEST_DB_ADMIN_USER"
  oe_guard_check_live_identity "$PATHOME_OE_TEST_DB" "$proof_user"
  oe_guard_check_sentinel "$PATHOME_OE_TEST_DB" "$proof_user"
  oe_guard_require_run
  node "$OE_TOOL_DIR/lib/runtime.mjs" recover
  oe_runtime_checkpoint OWNED_BACKEND_STOPPED

  oe_log "dropping disposable database '$PATHOME_OE_TEST_DB' (marker verified)"
  oe_guard_drop
  if [ "${PATHOME_OE_TEST_ACCEPTANCE:-}" = 'routing-20261023' ]; then
    oe_psql_admin postgres -c "DROP ROLE IF EXISTS \"$PATHOME_OE_TEST_DB_APP_USER\"" >/dev/null
    oe_psql_admin postgres -c "DROP ROLE IF EXISTS \"$PATHOME_OE_TEST_DB_MIGRATOR_USER\"" >/dev/null
  fi
  oe_runtime_checkpoint DATABASE_DROPPED
  oe_log "TEARDOWN OK  $PATHOME_OE_TEST_DB removed"
}

cmd_backend() {
  oe_load_designation
  oe_guard_resolve
  oe_require_tools
  oe_ensure_run_dir
  oe_ensure_secrets

  local jdbc_url
  jdbc_url="$(oe_guard_jdbc_url)"
  oe_guard_verify_jdbc "$jdbc_url"
  # Validate media/configuration synchronously, before writing a PID or starting a subprocess.
  ( oe_iso_apply_backend_env "$jdbc_url" ) >/dev/null

  if ! oe_database_exists; then
    oe_die "Disposable database '$PATHOME_OE_TEST_DB' does not exist. Run 'create' first."
  fi
  oe_guard_verify --destructive
  oe_guard_require_run

  for artifact in backend.pid backend-binding.json backend-startup.json; do
    [ ! -L "$PATHOME_OE_TEST_RUN_DIR/$artifact" ] || oe_die 'symlinked runtime artifact'
  done

  if [ -f "$PATHOME_OE_TEST_RUN_DIR/backend.pid" ] && \
     kill -0 "$(cat "$PATHOME_OE_TEST_RUN_DIR/backend.pid")" 2>/dev/null; then
    oe_runtime_require_backend
    oe_log 'existing backend process, listener, signing identity and run verified'
    return 0
  fi
  [ ! -f "$PATHOME_OE_TEST_RUN_DIR/backend.pid" ] || oe_die 'stale PID file; inspect it and stop the previous owned run first'
  [ ! -e "$PATHOME_OE_TEST_RUN_DIR/backend-startup.json" ] && [ ! -e "$PATHOME_OE_TEST_RUN_DIR/backend-binding.json" ] || oe_die 'orphan runtime records; run recover first'
  command -v lsof >/dev/null || oe_die 'lsof is required to prove the listener identity'
  if /usr/sbin/lsof -nP -iTCP:"$OE_BACKEND_PORT" -sTCP:LISTEN -t >/dev/null 2>&1; then oe_die 'backend port is already occupied'; fi

  local log="${OE_COMMAND_ATTEMPT_DIR:?supervised backend command required}/backend.log"
  [ ! -L "$log" ] || oe_die 'symlinked backend log'
  : > "$log"

  (
    # `.env.local` is never sourced. Every destination is neutralised, then repinned to the
    # disposable target, and an unapproved media destination refuses the launch outright.
    oe_iso_apply_backend_env "$jdbc_url"

    export PATHOME_DATABASE_ALLOW_FLYWAY_BASELINE=true

    # The disposable schema is authoritative from Flyway, never from Hibernate schema tooling.
    # Upstream application.yml defaults ddl-auto=update, which cannot converge a database built
    # purely from the supported migrations: the v1 legacy baseline declares
    # property_media_assets.caption as TEXT while the entity maps varchar(255), and PostgreSQL
    # refuses that ALTER because V9's property_media_debug_view depends on the column. A preserved
    # development database only boots because earlier Hibernate runs had already converged it.
    # Pinning `none` here is a launch-time property for this environment only - it is the same
    # setting the repository's existing PostgreSQL integration tests use, and it changes no
    # source file, entity, migration, or application default.
    export SPRING_JPA_HIBERNATE_DDL_AUTO=none

    export APP_FIRST_ADMIN_PROVISIONING_ENABLED=true
    export APP_BOOTSTRAP_ADMIN_EMAIL="$OE_BOOTSTRAP_ADMIN_EMAIL"
    export APP_BOOTSTRAP_ADMIN_PASSWORD="$OE_BOOTSTRAP_ADMIN_PASSWORD"
    export APP_BOOTSTRAP_OPERATOR_REFERENCE="$OE_BOOTSTRAP_OPERATOR_REFERENCE"
    export SERVER_PORT="$OE_BACKEND_PORT"
    export SERVER_ADDRESS=127.0.0.1

    case "$SPRING_DATASOURCE_URL" in
      *"/$PATHOME_OE_TEST_DB") ;;
      *) printf 'REFUSED: datasource override did not take effect\n' >&2; exit 90 ;;
    esac
    # The datasource must not name any protected database, and the only host it may address is the
    # local loopback address the designation itself carries.
    case "$SPRING_DATASOURCE_URL" in
      *"//$PATHOME_OE_TEST_DB_HOST"*) ;;
      *) printf 'REFUSED: datasource host is not the designated local host\n' >&2; exit 90 ;;
    esac
    case "$SPRING_DATASOURCE_URL" in
      *"@"*) printf 'REFUSED: datasource URL must not embed credentials\n' >&2; exit 90 ;;
    esac
    jdbc_database="${SPRING_DATASOURCE_URL%%\?*}"
    jdbc_database="${jdbc_database##*/}"
    oe_guard_assert_not_protected "$jdbc_database" 'datasource database'

    OE_RUNTIME_REPLACE=true mvn -o -q -f "$OE_REPO_ROOT/backend/pom.xml" spring-boot:run
  ) >> "$log" 2>&1 &

  local pid=$!
  printf '%s\n' "$pid" > "$PATHOME_OE_TEST_RUN_DIR/backend.pid"
  node "$OE_TOOL_DIR/lib/runtime.mjs" record-startup
  oe_runtime_checkpoint STARTUP_IDENTITY_RECORDED
  oe_log "backend starting (pid $pid) against $jdbc_url"
  oe_log "log: $log"

  local attempt code deadline=$((SECONDS + 180))
  for attempt in $(seq 1 120); do
    [ "$SECONDS" -lt "$deadline" ] || break
    if ! kill -0 "$pid" 2>/dev/null; then
      oe_log 'backend process exited before becoming ready; last log lines:'
      tail -30 "$log" >&2 || true
      oe_die 'backend failed to start'
    fi
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$OE_API_BASE_URL/staff/me/capabilities" || true)"
    if [ "$code" = '401' ]; then
      oe_guard_require_run
      node "$OE_TOOL_DIR/lib/runtime.mjs" record
      oe_runtime_checkpoint BACKEND_BOUND
      oe_log "BACKEND READY  $OE_API_BASE_URL (readiness attempt $attempt)"
      return 0
    fi
    sleep 1
  done

  tail -40 "$log" >&2 || true
  oe_die 'backend did not become ready within the bounded startup window; run recover'
}

cmd_stop_backend() {
  oe_load_designation
  oe_guard_verify --destructive
  oe_guard_require_run
  node "$OE_TOOL_DIR/lib/runtime.mjs" stop
  oe_log 'verified disposable backend and listener stopped; PID/binding artifacts removed'
}

cmd_recover() {
  oe_load_designation
  oe_require_tools
  # A partial database with no sentinel is quarantined: recovery never creates proof to enable drop.
  if oe_database_exists; then
    oe_guard_verify --destructive
    oe_guard_require_run
  elif [ -e "$PATHOME_OE_TEST_RUN_DIR/backend.pid" ]; then
    oe_die 'runtime artifacts exist but database identity cannot be proven; preserve evidence for explicit operator resolution'
  fi
  node "$OE_TOOL_DIR/lib/runtime.mjs" recover
  oe_log 'RECOVERY OK: owned process stopped/dead artifacts archived; database and diagnostic evidence retained'
}

cmd_recover_probe() {
  oe_load_designation
  [ "$#" = 1 ] && [[ "$1" =~ ^pathome_oe_test_guardprobe_[0-9]+$ ]] || oe_die 'exact legacy guardprobe identifier required'
  # Previous harness probes had no per-run ownership journal. Names alone cannot prove ownership.
  printf 'QUARANTINED legacy probe %s: automatic cleanup refused; no sentinel or ownership evidence will be fabricated.\n' "$1"
  oe_die 'legacy unowned probe requires explicit operator resolution; the corrected verifier creates no probe database'
}

cmd_preflight() {
  oe_load_designation
  oe_require_tools
  oe_ensure_run_dir
  node "$OE_TOOL_DIR/lib/readiness.mjs" check
  [ "${PATHOME_OE_TEST_ALLOW_LOCAL_BUILD:-}" = true ] || oe_die 'preflight requires explicit approval PATHOME_OE_TEST_ALLOW_LOCAL_BUILD=true for the safe local frontend build'
  # Offline compilation and plugin help load build/runtime dependencies without executing tests or
  # starting Spring. The outer command supervisor bounds the entire prerequisite batch.
  ( oe_iso_neutralise_environment; mvn -o -q -f "$OE_REPO_ROOT/backend/pom.xml" -DskipTests test-compile spring-boot:help )
  oe_runtime_exec build npm --prefix "$OE_REPO_ROOT/frontend-web" run build
  node "$OE_TOOL_DIR/lib/readiness.mjs" record
  oe_runtime_checkpoint PREREQUISITES_COMPLETE
  oe_log 'PREFLIGHT OK: local tooling/cache/build/browser/image/media/private-directory checks passed; no database contacted'
}

cmd_provision() {
  oe_load_designation
  oe_ensure_run_dir
  oe_require_tools
  oe_ensure_secrets
  oe_runtime_require_backend
  oe_provision_main "$@"
}

cmd_verify() {
  oe_load_designation
  oe_ensure_run_dir
  oe_require_tools
  oe_ensure_secrets
  oe_runtime_require_backend
  oe_verify_main "$@"
}

# Auditable evidence of the actual test target. Disposable-only: this command reads nothing but the
# designated database and records explicitly that it makes no claim about any protected database.
cmd_attest() {
  oe_load_designation
  oe_guard_resolve
  oe_guard_assert_not_protected "$PATHOME_OE_TEST_DB" 'the designated database'
  oe_require_tools
  oe_ensure_run_dir
  oe_guard_verify --destructive
  oe_guard_require_run
  node "$OE_TOOL_DIR/lib/runtime.mjs" verify
  mkdir -p "$PATHOME_OE_TEST_RUN_DIR/evidence"

  local file="$PATHOME_OE_TEST_RUN_DIR/evidence/target-attestation.txt"
  local live live_user server_address server_port server_version sentinel highest applied live_tables
  local expected_tables expected_views expected_infra

  live="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc 'select current_database()')"
  [ "$live" = "$PATHOME_OE_TEST_DB" ] || oe_die "the live connection reports '$live', not the designation '$PATHOME_OE_TEST_DB'. Attestation refused."

  live_user="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc 'select current_user')"
  server_address="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc "select coalesce(inet_server_addr()::text, 'local-socket')")"
  server_port="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc 'select inet_server_port()::text')"
  server_version="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc 'select version()')"
  sentinel="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
    "select env_marker || ' db_name=' || db_name || ' guard=' || guard_version || ' run=' || run_id from $OE_GUARD_SENTINEL_TABLE where env_marker = '$OE_GUARD_SENTINEL_MARKER' and db_name = current_database()")"
  highest="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
    "select coalesce(version, 'baseline-only') from flyway_schema_history where success order by installed_rank desc limit 1")"
  applied="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc 'select count(*) from flyway_schema_history where success')"
  live_tables="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
    "select count(*) from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE'")"
  expected_tables="$(oe_repo_expected_tables base | wc -l | tr -d ' ')"
  expected_views="$(oe_repo_expected_tables view | wc -l | tr -d ' ')"
  expected_infra="$(oe_repo_expected_tables infra | wc -l | tr -d ' ')"

  {
    printf 'attested_at=%s\n' "$(date '+%Y-%m-%d %H:%M:%S %Z')"
    printf 'guard_version=%s\n' "$OE_GUARD_VERSION"
    printf 'isolation_version=%s\n' "$OE_ISO_VERSION"
    printf 'designation=%s\n' "$PATHOME_OE_TEST_DB"
    printf 'run_id=%s\n' "$(< "$PATHOME_OE_TEST_RUN_DIR/run-id")"
    printf 'backend_origin=http://127.0.0.1:%s\n' "$OE_BACKEND_PORT"
    printf 'backend_listener_pid=%s\n' "$(node -e 'const fs=require("node:fs"); process.stdout.write(String(JSON.parse(fs.readFileSync(process.env.PATHOME_OE_TEST_RUN_DIR+"/backend-binding.json")).listenerPid))')"
    printf 'jdbc_target=%s\n' "$(oe_guard_jdbc_url)"
    printf 'live_current_database=%s\n' "$live"
    printf 'live_current_user=%s\n' "$live_user"
    printf 'server_address=%s\n' "$server_address"
    printf 'server_port=%s\n' "$server_port"
    printf 'server_version=%s\n' "$server_version"
    printf 'disposable_marker=%s\n' "$sentinel"
    printf 'flyway_highest_applied=%s\n' "$highest"
    printf 'flyway_applied_count=%s\n' "$applied"
    printf 'expected_applied_count=%s\n' "$(oe_repo_expected_versions | wc -l | tr -d ' ')"
    printf 'public_base_tables=%s (expected base=%s infra=%s)\n' "$live_tables" "$expected_tables" "$expected_infra"
    printf 'expected_views=%s\n' "$expected_views"
    printf 'schema_source_fingerprint_sha256=%s\n' "$(oe_repo_schema_source_fingerprint)"
    printf 'protected_deny_rules=%s | %s\n' "$OE_GUARD_PROTECTED_NAMES" "$OE_GUARD_PROTECTED_PREFIXES"
    printf 'statement=%s\n' \
      'Disposable-only attestation. This run read, dumped, fingerprinted and compared only the designated disposable database. No protected database was inspected, and this evidence therefore proves only the identity of the disposable target; it is NOT evidence that any protected database is unchanged.'
  } | tee "$file"

  oe_log "written to $file"
}

cmd_status() {
  oe_load_designation
  oe_guard_resolve
  oe_log "designation   $PATHOME_OE_TEST_DB"
  oe_log "jdbc target   $(oe_guard_jdbc_url)"
  oe_log "run dir       $PATHOME_OE_TEST_RUN_DIR"
  if oe_database_exists; then
    oe_log 'database      exists'
    if oe_guard_check_sentinel "$PATHOME_OE_TEST_DB" "$PATHOME_OE_TEST_DB_APP_USER" 2>/dev/null; then
      oe_log 'marker        DISPOSABLE (verified)'
    else
      oe_log 'marker        MISSING - destructive operations will refuse'
    fi
    oe_log "tables        $(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc "select count(*) from information_schema.tables where table_schema='public'")"
    oe_log "flyway        $(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc "select coalesce(version,'(baseline only)') from flyway_schema_history where success order by installed_rank desc limit 1" 2>/dev/null || echo '(no history yet)')"
  else
    oe_log 'database      absent'
  fi
  local pidfile="$PATHOME_OE_TEST_RUN_DIR/backend.pid"
  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
    oe_log "backend       running (pid $(cat "$pidfile"))"
  else
    oe_log 'backend       not running'
  fi
}

case "${1:-}" in
  preflight|create|recreate|backend|stop-backend|recover|recover-probe|provision|verify|attest|teardown) oe_runtime_supervise "$OE_TOOL_DIR/run.sh" "$@" ;;
esac
case "${1:-}" in
  preflight)    shift; cmd_preflight "$@" ;;
  recover)      shift; cmd_recover "$@" ;;
  recover-probe) shift; cmd_recover_probe "$@" ;;
  doctor)       shift; cmd_doctor "$@" ;;
  plan)         shift; cmd_plan "$@" ;;
  create)       shift; cmd_create "$@" ;;
  recreate)     shift; cmd_recreate "$@" ;;
  backend)      shift; cmd_backend "$@" ;;
  stop-backend) shift; cmd_stop_backend "$@" ;;
  provision)    shift; cmd_provision "$@" ;;
  verify)       shift; cmd_verify "$@" ;;
  attest)       shift; cmd_attest "$@" ;;
  teardown)     shift; cmd_teardown "$@" ;;
  status)       shift; cmd_status "$@" ;;
  *)
    sed -n '2,24p' "$0" >&2
    exit 2
    ;;
esac
