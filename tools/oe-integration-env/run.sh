#!/usr/bin/env bash
#
# Pathome OE integration environment - single entry point.
#
# Creates, migrates, provisions, verifies and destroys one disposable PostgreSQL database
# that is completely separate from the preserved `pathome_db` development database. Every
# server-touching step runs through lib/db-guard.sh first and aborts on any refusal.
#
#   run.sh doctor        report tooling, target designation and guard verdict
#   run.sh fingerprint   record the protected reference digest (pathome_db)
#   run.sh create        create the designated disposable database (refuses if it exists)
#   run.sh recreate      guarded teardown + create (the repeatability cycle)
#   run.sh backend       start Spring Boot against the disposable database
#   run.sh stop-backend  stop the Spring Boot instance this tool started
#   run.sh provision     provision disposable staff identities through supported APIs
#   run.sh verify        run the isolation, migration, provisioning and immutability proofs
#   run.sh teardown      drop the designated disposable database (guarded, destructive)
#   run.sh status        show the current state of the disposable environment

set -euo pipefail

OE_TOOL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OE_REPO_ROOT="$(cd "$OE_TOOL_DIR/../.." && pwd)"
OE_ENV_FILE="${PATHOME_OE_TEST_ENV_FILE:-$OE_TOOL_DIR/oe-test.env}"
OE_LEGACY_BASELINE="$OE_REPO_ROOT/backend/src/test/resources/db/baseline/pathome-v1-legacy-core.sql"
OE_MARKER_SQL="$OE_TOOL_DIR/sql/00-disposable-marker.sql"

# The preserved local development database. Never a valid target in this environment.
OE_PROTECTED_DEV_DB='pathome_db'

OE_BACKEND_PORT="${PATHOME_OE_TEST_BACKEND_PORT:-8080}"
OE_API_BASE_URL="http://localhost:$OE_BACKEND_PORT/api/v1"

# shellcheck source=lib/db-guard.sh
. "$OE_TOOL_DIR/lib/db-guard.sh"
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
  if [ ! -f "$OE_ENV_FILE" ]; then
    oe_die "No target designation found at $OE_ENV_FILE. Copy oe-test.env or set PATHOME_OE_TEST_ENV_FILE. Nothing is defaulted."
  fi
  # shellcheck disable=SC1090
  set -a
  . "$OE_ENV_FILE"
  set +a
}

oe_require_tools() {
  local tool
  for tool in psql pg_dump curl jq mvn java; do
    command -v "$tool" >/dev/null 2>&1 || oe_die "Required tool '$tool' is not on PATH."
  done
}

oe_ensure_run_dir() {
  mkdir -p "$PATHOME_OE_TEST_RUN_DIR/evidence"
}

oe_random_secret() {
  od -An -N24 -tx1 /dev/urandom | tr -d ' \n'
}

# Disposable credentials live only in the ephemeral run directory, never in the repository.
oe_ensure_secrets() {
  local secrets="$PATHOME_OE_TEST_RUN_DIR/secrets.env"
  if [ -s "$secrets" ]; then
    # shellcheck disable=SC1090
    set -a
    . "$secrets"
    set +a
    return 0
  fi
  umask 077
  {
    printf 'OE_BOOTSTRAP_ADMIN_EMAIL=%s\n' 'codex.oe.env.admin@pathome.test'
    printf 'OE_BOOTSTRAP_ADMIN_PASSWORD=%s\n' "$(oe_random_secret)"
    printf 'OE_BOOTSTRAP_OPERATOR_REFERENCE=%s\n' 'CODEX_OE_INTEGRATION_ENV'
    printf 'OE_STAFF_PASSWORD=%s\n' "$(oe_random_secret)"
  } > "$secrets"
  chmod 600 "$secrets"
  # shellcheck disable=SC1090
  set -a
  . "$secrets"
  set +a
}

oe_psql_admin() { # <database> <psql args...>
  local database="$1"
  shift
  oe_guard_psql "$database" "$PATHOME_OE_TEST_DB_ADMIN_USER" "$@"
}

oe_psql_app() { # <database> <psql args...>
  local database="$1"
  shift
  oe_guard_psql "$database" "$PATHOME_OE_TEST_DB_APP_USER" "$@"
}

oe_pg_dump() { # <database> [pg_dump args...]
  local database="$1"
  shift
  if [ -n "${PATHOME_OE_TEST_DB_PASSWORD:-}" ]; then
    PGPASSWORD="$PATHOME_OE_TEST_DB_PASSWORD" pg_dump \
      -h "$PATHOME_OE_TEST_DB_HOST" -p "$PATHOME_OE_TEST_DB_PORT" \
      -U "$PATHOME_OE_TEST_DB_APP_USER" -d "$database" \
      --no-owner --no-privileges --schema=public "$@"
  else
    pg_dump -h "$PATHOME_OE_TEST_DB_HOST" -p "$PATHOME_OE_TEST_DB_PORT" \
      -U "$PATHOME_OE_TEST_DB_APP_USER" -d "$database" \
      --no-owner --no-privileges --schema=public "$@"
  fi
}

oe_database_exists() {
  local count
  count="$(oe_psql_admin postgres -Atc "select count(*) from pg_database where datname = '$PATHOME_OE_TEST_DB'")"
  [ "$count" = '1' ]
}

# Read-only digest of the preserved development database, used to prove it is untouched.
oe_protected_fingerprint() {
  local data schema
  data="$(oe_pg_dump "$OE_PROTECTED_DEV_DB" --data-only | shasum -a 256 | awk '{print $1}')"
  schema="$(oe_pg_dump "$OE_PROTECTED_DEV_DB" --schema-only | shasum -a 256 | awk '{print $1}')"
  printf 'database=%s\ndata_sha256=%s\nschema_sha256=%s\n' "$OE_PROTECTED_DEV_DB" "$data" "$schema"
}

cmd_doctor() {
  oe_load_designation
  oe_log '== tooling =='
  local tool
  for tool in psql pg_dump curl jq mvn java node; do
    if command -v "$tool" >/dev/null 2>&1; then
      oe_log "present  $tool -> $(command -v "$tool")"
    else
      oe_log "MISSING  $tool"
    fi
  done

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
  oe_log '== local server =='
  if oe_psql_admin postgres -Atc 'select version()' >/dev/null 2>&1; then
    oe_log "reachable  $(oe_psql_admin postgres -Atc 'select current_user')@$(oe_guard_jdbc_url)"
    oe_log "databases  $(oe_psql_admin postgres -Atc "select string_agg(datname, ', ' order by datname) from pg_database")"
    if oe_database_exists; then
      oe_log "disposable  $PATHOME_OE_TEST_DB exists"
    else
      oe_log "disposable  $PATHOME_OE_TEST_DB does not exist yet"
    fi
  else
    oe_log "unreachable  $PATHOME_OE_TEST_DB_HOST:$PATHOME_OE_TEST_DB_PORT"
  fi
}

cmd_fingerprint() {
  local label="${1:-current}"
  oe_load_designation
  oe_guard_resolve
  oe_require_tools
  oe_ensure_run_dir
  local file="$PATHOME_OE_TEST_RUN_DIR/evidence/protected-db-$label.txt"
  oe_protected_fingerprint | tee "$file"
  oe_log "written to $file"
}

cmd_create() {
  oe_load_designation
  oe_guard_resolve
  oe_require_tools
  oe_ensure_run_dir

  if oe_database_exists; then
    oe_die "Disposable database '$PATHOME_OE_TEST_DB' already exists. Use 'recreate' for the guarded teardown+create cycle, or 'teardown' to remove it."
  fi

  oe_log "creating disposable database '$PATHOME_OE_TEST_DB' (owner $PATHOME_OE_TEST_DB_APP_USER)"
  oe_psql_admin postgres -c "CREATE DATABASE \"$PATHOME_OE_TEST_DB\" OWNER \"$PATHOME_OE_TEST_DB_APP_USER\""

  # Marker first: from this point on the database is positively identifiable as disposable.
  oe_apply_marker

  oe_log 'applying the supported legacy v1 baseline'
  [ -f "$OE_LEGACY_BASELINE" ] || oe_die "Legacy baseline missing at $OE_LEGACY_BASELINE"
  oe_psql_app "$PATHOME_OE_TEST_DB" -q -f "$OE_LEGACY_BASELINE" >/dev/null

  oe_log 'creating canonical supported cities (migration-seeded, not hand-written fixtures)'
  oe_log "CREATE OK  $PATHOME_OE_TEST_DB ready; Flyway V2-V49 run on first backend start"
}

oe_apply_marker() {
  oe_psql_app "$PATHOME_OE_TEST_DB" \
    -q \
    -v "oe_database=$PATHOME_OE_TEST_DB" \
    -v "oe_guard_version=$OE_GUARD_VERSION" \
    -v "oe_run_id=$(date +%Y%m%dT%H%M%S)-$$" \
    -f "$OE_MARKER_SQL" >/dev/null
}

cmd_recreate() {
  oe_load_designation
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
    oe_log "nothing to do: '$PATHOME_OE_TEST_DB' does not exist"
    return 0
  fi

  # Destructive path: the target must be provably disposable before anything is dropped.
  oe_guard_check_live_identity "$PATHOME_OE_TEST_DB" "$PATHOME_OE_TEST_DB_APP_USER"
  oe_guard_check_sentinel "$PATHOME_OE_TEST_DB" "$PATHOME_OE_TEST_DB_APP_USER"

  oe_log "dropping disposable database '$PATHOME_OE_TEST_DB' (marker verified)"
  oe_psql_admin postgres -c "DROP DATABASE IF EXISTS \"$PATHOME_OE_TEST_DB\" WITH (FORCE)"
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

  if ! oe_database_exists; then
    oe_die "Disposable database '$PATHOME_OE_TEST_DB' does not exist. Run 'create' first."
  fi

  if [ -f "$PATHOME_OE_TEST_RUN_DIR/backend.pid" ] && \
     kill -0 "$(cat "$PATHOME_OE_TEST_RUN_DIR/backend.pid")" 2>/dev/null; then
    oe_log "backend already running with pid $(cat "$PATHOME_OE_TEST_RUN_DIR/backend.pid")"
    return 0
  fi

  local log="$PATHOME_OE_TEST_RUN_DIR/backend.log"
  : > "$log"

  (
    set -a
    if [ -f "$OE_REPO_ROOT/.env.local" ]; then
      # shellcheck disable=SC1091
      . "$OE_REPO_ROOT/.env.local"
    fi
    set +a

    # Re-point the application at the designated disposable database only.
    export SPRING_DATASOURCE_URL="$jdbc_url"
    export SPRING_DATASOURCE_USERNAME="$PATHOME_OE_TEST_DB_APP_USER"
    export PATHOME_DATABASE_ALLOW_FLYWAY_BASELINE=true

    # The disposable schema is authoritative from Flyway, never from Hibernate schema tooling.
    # Upstream application.yml defaults ddl-auto=update, which cannot converge a database built
    # purely from the supported migrations: the v1 legacy baseline declares
    # property_media_assets.caption as TEXT while the entity maps varchar(255), and PostgreSQL
    # refuses that ALTER because V9's property_media_debug_view depends on the column. The
    # preserved pathome_db only boots because earlier Hibernate runs had already converged it.
    # Pinning `none` here is a launch-time property for this environment only - it is the same
    # setting the repository's existing PostgreSQL integration tests use, and it changes no
    # source file, entity, migration, or application default.
    export SPRING_JPA_HIBERNATE_DDL_AUTO=none

    export APP_FIRST_ADMIN_PROVISIONING_ENABLED=true
    export APP_BOOTSTRAP_ADMIN_EMAIL="$OE_BOOTSTRAP_ADMIN_EMAIL"
    export APP_BOOTSTRAP_ADMIN_PASSWORD="$OE_BOOTSTRAP_ADMIN_PASSWORD"
    export APP_BOOTSTRAP_OPERATOR_REFERENCE="$OE_BOOTSTRAP_OPERATOR_REFERENCE"
    export SERVER_PORT="$OE_BACKEND_PORT"

    case "$SPRING_DATASOURCE_URL" in
      *"/$PATHOME_OE_TEST_DB") ;;
      *) printf 'REFUSED: datasource override did not take effect\n' >&2; exit 90 ;;
    esac
    case "$SPRING_DATASOURCE_URL" in
      *"/$OE_PROTECTED_DEV_DB"*) printf 'REFUSED: reserved development database\n' >&2; exit 90 ;;
    esac

    exec mvn -o -q -f "$OE_REPO_ROOT/backend/pom.xml" spring-boot:run
  ) >> "$log" 2>&1 &

  local pid=$!
  printf '%s\n' "$pid" > "$PATHOME_OE_TEST_RUN_DIR/backend.pid"
  oe_log "backend starting (pid $pid) against $jdbc_url"
  oe_log "log: $log"

  local attempt code
  for attempt in $(seq 1 120); do
    if ! kill -0 "$pid" 2>/dev/null; then
      oe_log 'backend process exited before becoming ready; last log lines:'
      tail -30 "$log" >&2 || true
      oe_die 'backend failed to start'
    fi
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$OE_API_BASE_URL/staff/me/capabilities" || true)"
    if [ "$code" = '401' ]; then
      oe_log "BACKEND READY  $OE_API_BASE_URL (after ${attempt}s)"
      return 0
    fi
    sleep 1
  done

  tail -40 "$log" >&2 || true
  oe_die 'backend did not become ready within 120s'
}

cmd_stop_backend() {
  oe_load_designation
  local pidfile="$PATHOME_OE_TEST_RUN_DIR/backend.pid"
  if [ ! -f "$pidfile" ]; then
    oe_log 'no backend pid recorded'
    return 0
  fi
  local pid
  pid="$(cat "$pidfile")"
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 30); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$pid" 2>/dev/null; then
      kill -9 "$pid" 2>/dev/null || true
    fi
    oe_log "stopped backend pid $pid"
  else
    oe_log "backend pid $pid is not running"
  fi
  rm -f "$pidfile"
}

cmd_provision() {
  oe_load_designation
  oe_ensure_run_dir
  oe_require_tools
  oe_ensure_secrets
  oe_provision_main "$@"
}

cmd_verify() {
  oe_load_designation
  oe_ensure_run_dir
  oe_require_tools
  oe_ensure_secrets
  oe_verify_main "$@"
}

cmd_status() {
  oe_load_designation
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
  doctor)       shift; cmd_doctor "$@" ;;
  fingerprint)  shift; cmd_fingerprint "$@" ;;
  create)       shift; cmd_create "$@" ;;
  recreate)     shift; cmd_recreate "$@" ;;
  backend)      shift; cmd_backend "$@" ;;
  stop-backend) shift; cmd_stop_backend "$@" ;;
  provision)    shift; cmd_provision "$@" ;;
  verify)       shift; cmd_verify "$@" ;;
  teardown)     shift; cmd_teardown "$@" ;;
  status)       shift; cmd_status "$@" ;;
  *)
    sed -n '2,17p' "$0" >&2
    exit 2
    ;;
esac
