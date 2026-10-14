#!/usr/bin/env bash
#
# Pathome listing-review authority extension + genuine Lessor -> Tenant -> OE milestone.
#
# Runs on the same Package 1/3A disposable environment: one designated database, the same guard, the same
# ephemeral secrets. Every server-touching step passes the target guard first, so nothing here can act on
# any database other than the approved disposable one.
#
#   package4.sh status           guard verdict, backend state, journey artifact state
#   package4.sh verify-unit      focused unit + controller-security matrix (no database)
#   package4.sh verify-postgres  guard preflight + the real HTTP/PostgreSQL adversarial suite
#   package4.sh journey          the genuine end-to-end journey over real HTTP (no SQL seeding)
#   package4.sh journey-browser  controlled Chromium pass over the journey's OE tail
#
# No commit, push, merge, stash or reset is performed by anything in this directory.

set -euo pipefail

OE_TOOL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OE_REPO_ROOT="$(cd "$OE_TOOL_DIR/../.." && pwd)"
OE_ENV_FILE="${PATHOME_OE_TEST_ENV_FILE:-$OE_TOOL_DIR/oe-test.env}"
OE_BACKEND_PORT="${PATHOME_OE_TEST_BACKEND_PORT:-8080}"
OE_API_BASE_URL="http://localhost:$OE_BACKEND_PORT/api/v1"

OE_P4_FOCUSED_UNIT_TESTS='ListingReviewAuthorityTest,LandlordReviewServiceTest,LandlordReviewSecurityTest'
OE_P4_POSTGRES_TESTS='ListingReviewAuthorityHttpPostgresTest'

# shellcheck source=lib/db-guard.sh
. "$OE_TOOL_DIR/lib/db-guard.sh"

oe_log() { printf '%s\n' "$*"; }
oe_die() { printf 'ERROR  %s\n' "$*" >&2; exit 1; }

oe_load_designation() {
  [ -f "$OE_ENV_FILE" ] || oe_die "No target designation found at $OE_ENV_FILE."
  # shellcheck disable=SC1090
  set -a
  . "$OE_ENV_FILE"
  set +a
}

oe_p4_dir() { printf '%s' "$PATHOME_OE_TEST_RUN_DIR/package4"; }
oe_p4_state() { printf '%s' "$(oe_p4_dir)/journey-state.json"; }

oe_p4_ensure_secrets() {
  local secrets="$PATHOME_OE_TEST_RUN_DIR/secrets.env"
  [ -s "$secrets" ] || oe_die "No disposable secrets at $secrets. Run 'run.sh backend' first."
  # shellcheck disable=SC1090
  set -a
  . "$secrets"
  set +a
}

oe_p4_require_backend() {
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$OE_API_BASE_URL/staff/me/capabilities" || true)"
  [ "$code" = '401' ] || [ "$code" = '403' ] || \
    oe_die "no disposable backend answered $OE_API_BASE_URL (HTTP '$code'); run 'run.sh backend'"
}

cmd_status() {
  oe_load_designation
  mkdir -p "$(oe_p4_dir)"
  oe_log "designation   $PATHOME_OE_TEST_DB"
  oe_log "jdbc target   $(oe_guard_jdbc_url)"
  oe_log "api base      $OE_API_BASE_URL"
  if oe_guard_check_sentinel "$PATHOME_OE_TEST_DB" "$PATHOME_OE_TEST_DB_APP_USER" 2>/dev/null; then
    oe_log 'marker        DISPOSABLE (verified)'
  else
    oe_log 'marker        MISSING - destructive operations will refuse'
  fi
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$OE_API_BASE_URL/staff/me/capabilities" || true)"
  if [ "$code" = '401' ]; then
    oe_log 'backend       running and answering'
  else
    oe_log "backend       not answering (HTTP '$code')"
  fi
  if [ -f "$(oe_p4_state)" ]; then
    oe_log "journey state $(oe_p4_state)"
  else
    oe_log 'journey state not produced yet'
  fi
}

cmd_verify_unit() {
  oe_load_designation
  oe_log 'focused unit + controller-security matrix (no database)'
  mvn -f "$OE_REPO_ROOT/backend/pom.xml" -o \
    -Dtest="$OE_P4_FOCUSED_UNIT_TESTS" -DfailIfNoTests=false test
}

cmd_verify_postgres() {
  oe_load_designation
  local jdbc_url
  jdbc_url="$(oe_guard_jdbc_url)"

  export PATHOME_VISIT_OPERATIONS_POSTGRES_TEST=true
  oe_log "preflight     verifying $jdbc_url against the approved disposable acceptance policy"
  oe_guard_verify_package3_acceptance "$jdbc_url" || return 1
  oe_guard_package3_selftest || return 1
  oe_log "marker        $(oe_guard_psql "$PATHOME_OE_TEST_DB" "$PATHOME_OE_TEST_DB_APP_USER" -Atc \
    "select env_marker || ' recorded for ' || db_name from $OE_GUARD_SENTINEL_TABLE \
     where env_marker = '$OE_GUARD_SENTINEL_MARKER' and db_name = current_database()")"

  oe_log "real HTTP/PostgreSQL listing-review authority matrix against $jdbc_url"
  (
    set -a
    [ -f "$OE_REPO_ROOT/.env.local" ] && . "$OE_REPO_ROOT/.env.local"
    set +a
    export PATHOME_VISIT_OPERATIONS_POSTGRES_TEST=true
    export SPRING_DATASOURCE_URL="$jdbc_url"
    export SPRING_DATASOURCE_USERNAME="$PATHOME_OE_TEST_DB_APP_USER"
    export PATHOME_OE_TEST_DB
    mvn -f "$OE_REPO_ROOT/backend/pom.xml" -o \
      -Dtest="$OE_P4_POSTGRES_TESTS" -DfailIfNoTests=false test
  )
}

cmd_journey() {
  oe_load_designation
  mkdir -p "$(oe_p4_dir)"
  oe_p4_ensure_secrets
  oe_p4_require_backend

  local media="$OE_REPO_ROOT/sample_upload_media/interior_living.jpg"
  [ -f "$media" ] || oe_die "the journey needs a real photo to upload at $media"

  oe_log 'genuine lessor -> tenant -> OE journey over real HTTP (nothing seeded through SQL)'
  P4_BACKEND_URL="http://localhost:$OE_BACKEND_PORT" \
  P4_ADMIN_EMAIL="$OE_BOOTSTRAP_ADMIN_EMAIL" \
  P4_ADMIN_PASSWORD="$OE_BOOTSTRAP_ADMIN_PASSWORD" \
  P4_STAFF_PASSWORD="$OE_STAFF_PASSWORD" \
  P4_MEDIA_FILE="$media" \
  P4_STATE_FILE="$(oe_p4_state)" \
  node "$OE_TOOL_DIR/journey.mjs"
  chmod 600 "$(oe_p4_state)" 2>/dev/null || true
}

cmd_journey_browser() {
  oe_load_designation
  local harness="$OE_REPO_ROOT/frontend-web/browser-verification/listing-publication-journey/run.mjs"
  [ -f "$harness" ] || oe_die "the journey browser harness is missing"
  [ -f "$(oe_p4_state)" ] || oe_die "run 'package4.sh journey' first: no journey state found"
  [ -d "$OE_REPO_ROOT/frontend-web/dist" ] || \
    oe_die "frontend-web/dist is missing; run 'npm --prefix frontend-web run build'"
  oe_p4_require_backend

  oe_log 'controlled Chromium pass over the genuine journey tail'
  P4_STATE_FILE="$(oe_p4_state)" node "$harness"
}

case "${1:-}" in
  status)           shift; cmd_status "$@" ;;
  verify-unit)      shift; cmd_verify_unit "$@" ;;
  verify-postgres)  shift; cmd_verify_postgres "$@" ;;
  journey)          shift; cmd_journey "$@" ;;
  journey-browser)  shift; cmd_journey_browser "$@" ;;
  *)
    sed -n '3,20p' "$0" >&2
    exit 2
    ;;
esac
