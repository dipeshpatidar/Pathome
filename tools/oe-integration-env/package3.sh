#!/usr/bin/env bash
#
# Pathome Package 3A - governed Operating Team provisioning and genuine OE admission.
#
# Runs on top of the Package 1 disposable environment (same target, same guard, same ephemeral
# secrets). Every server-touching step passes the target guard first, so this script can only ever
# act on the designated disposable database and never on `pathome_db`.
#
#   package3.sh status           guard verdict, backend state, Package 3A fixture state
#   package3.sh provision        governed Team creation + genuine OE registration/activation/grants
#   package3.sh verify-http      genuine admission, protected reads and negative controls over HTTP
#   package3.sh verify-postgres  focused Package 3A unit + HTTP/PostgreSQL tests
#   package3.sh verify-browser   genuine Chromium login and admission (fresh context, empty storage)
#   package3.sh verify-routing   focused Package 3B routing unit + HTTP/PostgreSQL tests
#   package3.sh verify-routing-browser  genuine Chromium pass over the approved routing surface
#
# No commit, push, merge, stash or reset is performed by anything in this directory.

set -euo pipefail

OE_TOOL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OE_REPO_ROOT="$(cd "$OE_TOOL_DIR/../.." && pwd)"
OE_ENV_FILE="${PATHOME_OE_TEST_ENV_FILE:-$OE_TOOL_DIR/oe-test.env}"
OE_BACKEND_PORT="${PATHOME_OE_TEST_BACKEND_PORT:-8080}"
OE_API_BASE_URL="http://localhost:$OE_BACKEND_PORT/api/v1"

# shellcheck source=lib/db-guard.sh
. "$OE_TOOL_DIR/lib/db-guard.sh"
# shellcheck source=lib/env-isolation.sh
. "$OE_TOOL_DIR/lib/env-isolation.sh"
# shellcheck source=lib/provision.sh
. "$OE_TOOL_DIR/lib/provision.sh"
# shellcheck source=lib/package3-fixtures.sh
. "$OE_TOOL_DIR/lib/package3-fixtures.sh"
# shellcheck source=lib/package3-verify.sh
. "$OE_TOOL_DIR/lib/package3-verify.sh"
# shellcheck source=lib/package3b-verify.sh
. "$OE_TOOL_DIR/lib/package3b-verify.sh"

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
  for tool in psql curl jq mvn java node; do
    command -v "$tool" >/dev/null 2>&1 || oe_die "Required tool '$tool' is not on PATH."
  done
}

# Same guarded psql wrappers run.sh uses: the guard decides whether a connection is allowed at all, and
# the only non-designation database this environment opens is the `postgres` maintenance catalog.
oe_psql_admin() { # <database> <psql args...>
  local database="$1"
  shift
  [ "$database" = 'postgres' ] || oe_guard_assert_not_protected "$database" 'connection target'
  oe_guard_psql "$database" "$PATHOME_OE_TEST_DB_ADMIN_USER" "$@"
}

oe_psql_app() { # <database> <psql args...>
  local database="$1"
  shift
  oe_guard_assert_not_protected "$database" 'connection target'
  oe_guard_psql "$database" "$PATHOME_OE_TEST_DB_APP_USER" "$@"
}

oe_ensure_run_dir() {
  mkdir -p "$PATHOME_OE_TEST_RUN_DIR/package3"
}

oe_ensure_secrets() {
  local secrets="$PATHOME_OE_TEST_RUN_DIR/secrets.env"
  [ -s "$secrets" ] || oe_die "No disposable secrets at $secrets. Run 'run.sh backend' first."
  oe_runtime_load_secrets
}

oe_require_backend() {
  oe_runtime_require_backend
}

oe_p3_bootstrap() {
  oe_load_designation
  oe_ensure_run_dir
  oe_require_tools
  oe_ensure_secrets
  oe_require_backend
}

cmd_status() {
  oe_load_designation
  oe_ensure_run_dir
  oe_log "designation   $PATHOME_OE_TEST_DB"
  oe_log "jdbc target   $(oe_guard_jdbc_url)"
  oe_log "api base      $OE_API_BASE_URL"
  if oe_guard_check_sentinel "$PATHOME_OE_TEST_DB" "$PATHOME_OE_TEST_DB_APP_USER" 2>/dev/null; then
    oe_log 'marker        DISPOSABLE (verified)'
  else
    oe_log 'marker        MISSING - destructive operations will refuse'
  fi
  oe_log "p3a teams     $(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
    "select count(*) from operating_teams where code like 'p3a-%'" 2>/dev/null || echo 'n/a')"
  oe_log "p3a grants    $(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
    "select count(*) from staff_access_grants g join operating_teams t on t.id=g.team_id where t.code like 'p3a-%'" 2>/dev/null || echo 'n/a')"
  oe_log "p3a audits    $(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
    "select count(*) from operational_audit_events where action_code = 'OPERATING_TEAM_CREATED'" 2>/dev/null || echo 'n/a')"
  if [ -f "$PATHOME_OE_TEST_RUN_DIR/package3/state.json" ]; then
    oe_log "fixture state $PATHOME_OE_TEST_RUN_DIR/package3/state.json"
  else
    oe_log 'fixture state not provisioned yet'
  fi
}

cmd_provision() {
  oe_p3_bootstrap
  oe_p3_provision_main
}

cmd_verify_http() {
  oe_p3_bootstrap
  [ -f "$PATHOME_OE_TEST_RUN_DIR/package3/browser-identities.env" ] || \
    oe_die "run 'package3.sh provision' first"
  oe_p3_prepare
  # shellcheck disable=SC1090
  oe_data_read "$OE_P3_IDENTITIES" 'oe_alpha_EMAIL oe_alpha_PASSWORD oe_bravo_EMAIL oe_bravo_PASSWORD staff_nogrant_EMAIL staff_nogrant_PASSWORD city_admin_EMAIL city_admin_PASSWORD P3A_CITY_ID P3A_TEAM_ALPHA_ID P3A_TEAM_BRAVO_ID'
  local state="$OE_P3_STATE"
  local city_id team_alpha team_bravo
  city_id="$(jq -r '.cityId' < "$state")"
  team_alpha="$(jq -r '.teams.alpha' < "$state")"
  team_bravo="$(jq -r '.teams.bravo' < "$state")"
  oe_p3_verify_http_main "$oe_alpha_EMAIL" "$oe_alpha_PASSWORD" "$oe_bravo_EMAIL" \
    "$staff_nogrant_EMAIL" "$city_admin_EMAIL" "$oe_alpha_PASSWORD" \
    "$city_id" "$team_alpha" "$team_bravo"
}

cmd_verify_postgres() {
  oe_load_designation
  oe_require_tools
  oe_p3_verify_postgres_main
}

cmd_verify_browser() {
  oe_p3_bootstrap
  oe_p3_prepare
  oe_p3_verify_browser_main
}

cmd_verify_routing() {
  oe_load_designation
  oe_require_tools
  oe_p3b_verify_postgres_main
}

cmd_verify_routing_browser() {
  oe_p3_bootstrap
  oe_p3_prepare
  oe_p3b_verify_browser_main
}

case "${1:-}" in
  status)                  shift; cmd_status "$@" ;;
  provision)               shift; cmd_provision "$@" ;;
  verify-http)             shift; cmd_verify_http "$@" ;;
  verify-postgres)         shift; cmd_verify_postgres "$@" ;;
  verify-browser)          shift; cmd_verify_browser "$@" ;;
  verify-routing)          shift; cmd_verify_routing "$@" ;;
  verify-routing-browser)  shift; cmd_verify_routing_browser "$@" ;;
  *)
    sed -n '3,18p' "$0" >&2
    exit 2
    ;;
esac
