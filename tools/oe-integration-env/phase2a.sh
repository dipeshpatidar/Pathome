#!/usr/bin/env bash
#
# Pathome Phase 2A - Operations full-stack acceptance entry point.
#
# Runs the approved Phase 2A Operations workflows against the real Spring Boot backend on the single
# designated disposable PostgreSQL database, over real HTTP. Fixtures use supported APIs except the
# explicit audited Admin/GE role fixture. Every server-touching step passes the target guard, and launch is
# isolated by lib/env-isolation.sh.
#
#   phase2a.sh plan           fully static: the scenario manifest, prerequisites and deny rules
#   phase2a.sh verify-static  code-only checks: route existence, isolation, guard self tests, syntax
#   phase2a.sh acceptance     OWNER-APPROVED live run against the disposable environment
#   phase2a.sh browser        genuine React acceptance after all 82 API entries pass
#
# `plan` and `verify-static` connect to nothing: no database, no backend, no external service.
# `acceptance` requires the disposable environment to be created, launched, provisioned and attested,
# plus an explicitly approved disposable media destination for the genuine cover photo.
#
# No commit, push, merge, stash or reset is performed by anything here.

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

oe_log() { printf '%s\n' "$*"; }
oe_die() { printf 'ERROR  %s\n' "$*" >&2; exit 1; }

oe_load_designation() {
  oe_runtime_load_designation
}

oe_ensure_secrets() {
  local secrets="$PATHOME_OE_TEST_RUN_DIR/secrets.env"
  [ -s "$secrets" ] || oe_die "No disposable secrets at $secrets. Run 'run.sh backend' first."
  oe_runtime_load_secrets
}

oe_require_backend() {
  oe_runtime_require_backend
}

cmd_plan() {
  oe_load_designation
  mkdir -p "$PATHOME_OE_TEST_RUN_DIR/evidence"
  local plan="$PATHOME_OE_TEST_RUN_DIR/evidence/phase2a-plan.txt"
  {
    oe_log '=== Pathome Phase 2A full-stack acceptance - static plan (nothing is contacted) ==='
    oe_log ''
    oe_log '-- guard deny rules --'
    oe_guard_describe
    oe_log ''
    oe_log '-- runtime configuration isolation --'
    oe_iso_report
    oe_log ''
    oe_log '-- scenario manifest --'
    node "$OE_TOOL_DIR/phase2a/manifest-report.mjs"
    oe_log ''
    oe_log '-- environmental prerequisites --'
    oe_log '1. PostgreSQL reachable on the designated loopback host/port.'
    oe_log '2. An approved disposable media destination (PATHOME_OE_TEST_MEDIA_MODE=disposable-account,'
    oe_log '   PATHOME_OE_TEST_ALLOW_EXTERNAL_MEDIA_UPLOAD=true, OE_TEST_CLOUDINARY_* for a throwaway'
    oe_log '   account). The application cannot start without a media provider and this environment will'
    oe_log '   never use the shipped production account.'
    oe_log '3. PATHOME_OE_TEST_DB_PASSWORD if the disposable role is not trust-authenticated locally.'
    oe_log '4. frontend-web/dist only for the separate browser passes; the Phase 2A acceptance suite is HTTP-only.'
    oe_log ''
    oe_log '-- future sequence (only after independent safety review; see PHASE2A.md) --'
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

cmd_verify_static() {
  oe_log 'code-only checks (no database, no backend, no container, no external service)'
  bash -n "$OE_TOOL_DIR/run.sh"
  bash -n "$OE_TOOL_DIR/package3.sh"
  bash -n "$OE_TOOL_DIR/package4.sh"
  bash -n "$OE_TOOL_DIR/phase2a.sh"
  bash -n "$OE_TOOL_DIR/lib/db-guard.sh"
  bash -n "$OE_TOOL_DIR/lib/env-isolation.sh"
  local script
  for script in "$OE_TOOL_DIR"/lib/*.sh; do bash -n "$script"; done
  oe_log 'PASS  every harness script parses (bash -n)'
  node --test --test-reporter=tap "$OE_TOOL_DIR/tests/oe-harness-static.test.mjs" "$OE_TOOL_DIR/tests/oe-blocker-closure.test.mjs"
  bash "$OE_TOOL_DIR/lib/db-guard.sh" selftest
  bash "$OE_TOOL_DIR/lib/db-guard.sh" selftest-package3
  bash "$OE_TOOL_DIR/lib/env-isolation.sh" selftest
  oe_log 'PASS  guard and isolation self tests'
}

cmd_acceptance() {
  oe_load_designation
  oe_require_tools_acceptance
  oe_guard_resolve
  oe_guard_assert_not_protected "$PATHOME_OE_TEST_DB" 'the designated database'
  mkdir -p "$PATHOME_OE_TEST_RUN_DIR/evidence"
  oe_ensure_secrets

  # Fail closed unless the run is explicitly authorised, and unless the target is proven disposable by
  # the same live checks the destructive path uses.
  [ "${PATHOME_OE_TEST_PHASE2A_LIVE:-}" = 'true' ] || \
    oe_die "the live acceptance run is not authorised. Set PATHOME_OE_TEST_PHASE2A_LIVE=true only after explicit owner approval."
  oe_guard_verify --destructive
  oe_require_backend

  local attestation="$PATHOME_OE_TEST_RUN_DIR/evidence/target-attestation.txt"
  [ -f "$attestation" ] || oe_die "no target attestation at $attestation. Run 'run.sh attest' first."
  grep -q "^live_current_database=$PATHOME_OE_TEST_DB\$" "$attestation" || \
    oe_die "the attestation at $attestation does not identify '$PATHOME_OE_TEST_DB'. Re-run 'run.sh attest'."

  # The media upload fixture is genuine, so the approved disposable media destination is required.
  [ "${PATHOME_OE_TEST_MEDIA_MODE:-fail-closed}" = 'disposable-account' ] || \
    oe_die "the acceptance suite uploads a real cover photo and requires PATHOME_OE_TEST_MEDIA_MODE=disposable-account with an approved throwaway media account."

  local media="${PATHOME_OE_TEST_MEDIA_FILE:-$OE_REPO_ROOT/sample_upload_media/interior_living.jpg}"
  [ -f "$media" ] || oe_die "the acceptance suite needs a real image at $media"
  command -v node >/dev/null 2>&1 || oe_die "node is not on PATH"

  oe_log "live Phase 2A acceptance against $OE_API_BASE_URL on $PATHOME_OE_TEST_DB"
  P4_BACKEND_URL="http://127.0.0.1:$OE_BACKEND_PORT" \
  P4_ADMIN_EMAIL="$OE_BOOTSTRAP_ADMIN_EMAIL" \
  P4_ADMIN_PASSWORD="$OE_BOOTSTRAP_ADMIN_PASSWORD" \
  P4_STAFF_PASSWORD="$OE_STAFF_PASSWORD" \
  P4_MEDIA_FILE="$media" \
  P4_ATTESTATION_FILE="$attestation" \
  node "$OE_TOOL_DIR/phase2a/fullstack.mjs" "$@"
}

cmd_scheduling_tests() {
  oe_load_designation
  oe_ensure_secrets
  oe_require_backend
  oe_guard_verify --destructive
  oe_guard_require_run
  local jdbc_url
  jdbc_url="$(oe_guard_jdbc_url)"
  # spring-boot:run uses backend/target/classes. A concurrent compiler can briefly remove classes
  # from the running application. Compile a private source snapshot, never that live classpath.
  local test_build="$PATHOME_OE_TEST_RUN_DIR/test-build-$(uuidgen | tr '[:upper:]' '[:lower:]')"
  mkdir -m 700 "$test_build"
  cp "$OE_REPO_ROOT/backend/pom.xml" "$test_build/pom.xml"
  cp -R "$OE_REPO_ROOT/backend/src" "$test_build/src"
  oe_iso_apply_test_env "$jdbc_url"
  oe_log "Private scheduling test source/report root: $test_build"
  mvn -o -q -f "$test_build/pom.xml" \
    -Dtest=OwnedOperationsHttpPostgresTest,VisitOperationsPostgresIntegrationTest \
    -DfailIfNoTests=true test
}

cmd_scheduling_snapshot() {
  oe_load_designation
  oe_ensure_secrets
  oe_require_backend
  [ "$#" = 1 ] && [[ "$1" =~ ^[1-9][0-9]{0,17}$ ]] || oe_die 'positive Session ID required'
  oe_guard_verify
  oe_guard_require_run
  oe_guard_psql "$PATHOME_OE_TEST_DB" "$PATHOME_OE_TEST_DB_APP_USER" -Atc \
    "select json_build_object('sessionId',s.id,'status',s.status,'version',s.version,
      'scheduledAt',s.scheduled_at,'reservedEndAt',s.reserved_end_at,'representativeUserId',s.representative_user_id,
      'commands',(select count(*) from visit_scheduling_commands c where c.session_id=s.id),
      'completedCommands',(select count(*) from visit_scheduling_commands c where c.session_id=s.id and response_json is not null),
      'decisions',(select count(*) from visit_scheduling_decisions d where d.session_id=s.id),
      'outbox',(select count(*) from visit_notification_outbox o where o.event_key like '%:' || s.id || ':v%'),
      'notifications',(select count(*) from system_notifications n where n.event_key like '%:' || s.id || ':v%'),
      'audit',(select count(*) from operational_audit_events)) from visit_sessions s where s.id=$1"
}

cmd_browser() {
  oe_load_designation
  [ "${PATHOME_OE_TEST_PHASE2A_LIVE:-}" = true ] || oe_die 'explicit live opt-in required'
  oe_ensure_secrets
  oe_require_backend
  P4_BACKEND_URL="http://127.0.0.1:$OE_BACKEND_PORT" node "$OE_TOOL_DIR/phase2a/browser.mjs" "$@"
}

# Test-only interruption, scoped to one Session on the positively identified disposable target.
# application_name identifies only the transaction that entered this Session-specific trigger.
cmd_scheduling_fault() {
  oe_load_designation
  [ "${PATHOME_OE_TEST_PHASE2A_LIVE:-}" = true ] || oe_die 'explicit live fault opt-in required'
  oe_ensure_secrets
  oe_require_backend
  [ "$#" = 2 ] && [[ "$2" =~ ^[1-9][0-9]{0,17}$ ]] || oe_die 'fault action and positive Session ID required'
  oe_guard_verify --destructive
  oe_guard_require_run
  local action="$1" sid="$2" sql
  case "$action" in
    arm) sql="CREATE FUNCTION b09_pause_${sid}() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN IF NEW.session_id=${sid} THEN PERFORM set_config('application_name','pathome-b09-fault-${sid}',true); PERFORM pg_sleep(20); END IF; RETURN NEW; END \$\$; CREATE TRIGGER b09_pause_${sid} BEFORE INSERT ON visit_scheduling_decisions FOR EACH ROW EXECUTE FUNCTION b09_pause_${sid}();" ;;
    terminate) sql="SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND application_name='pathome-b09-fault-${sid}' AND wait_event='PgSleep' AND query LIKE '%visit_scheduling_decisions%' AND pid<>pg_backend_pid();" ;;
    clear) sql="DROP TRIGGER IF EXISTS b09_pause_${sid} ON visit_scheduling_decisions; DROP FUNCTION IF EXISTS b09_pause_${sid}();" ;;
    *) oe_die 'unknown fault action' ;;
  esac
  oe_guard_psql "$PATHOME_OE_TEST_DB" "$PATHOME_OE_TEST_DB_APP_USER" -Atc "$sql"
}

cmd_b09_browser() {
  oe_load_designation
  [ "${PATHOME_OE_TEST_PHASE2A_LIVE:-}" = true ] || oe_die 'explicit live opt-in required'
  oe_ensure_secrets
  oe_require_backend
  node "$OE_TOOL_DIR/phase2a/b09-closure.mjs" "$@"
}

oe_require_tools_acceptance() {
  local tool
  for tool in psql curl jq node; do
    command -v "$tool" >/dev/null 2>&1 || oe_die "Required tool '$tool' is not on PATH."
  done
}

case "${1:-}" in acceptance|browser|scheduling-tests|b09-fixtures|b09-browser) oe_runtime_supervise "$OE_TOOL_DIR/phase2a.sh" "$@" ;; esac
case "${1:-}" in
  plan)          shift; cmd_plan "$@" ;;
  verify-static) shift; cmd_verify_static "$@" ;;
  acceptance)    shift; cmd_acceptance "$@" ;;
  browser)       shift; cmd_browser "$@" ;;
  scheduling-tests) shift; cmd_scheduling_tests "$@" ;;
  scheduling-snapshot) shift; cmd_scheduling_snapshot "$@" ;;
  scheduling-fault) shift; cmd_scheduling_fault "$@" ;;
  b09-fixtures) shift; cmd_acceptance --b09-fixtures "$@" ;;
  b09-browser) shift; cmd_b09_browser "$@" ;;
  *)
    sed -n '3,17p' "$0" >&2
    exit 2
    ;;
esac
