#!/usr/bin/env bash
set -euo pipefail

OE_TOOL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OE_REPO_ROOT="$(cd "$OE_TOOL_DIR/../.." && pwd)"
export PATHOME_OE_TEST_ENV_FILE="$OE_TOOL_DIR/routing-acceptance-target.env"
export PATHOME_OE_TEST_TARGET_FILE="$PATHOME_OE_TEST_ENV_FILE"
export PATHOME_OE_TEST_ACCEPTANCE='routing-20261023'
OE_ENV_FILE="$PATHOME_OE_TEST_ENV_FILE"

. "$OE_TOOL_DIR/lib/db-guard.sh"

oe_log() { printf '%s\n' "$*"; }
oe_die() { printf 'ERROR  %s\n' "$*" >&2; exit 1; }

cmd_gate1() {
  oe_runtime_load_designation
  [ "$(shasum -a 256 "$PATHOME_OE_TEST_TARGET_FILE" | awk '{print $1}')" = \
      "$(shasum -a 256 "$OE_REPO_ROOT/tools/oe-integration-env/routing-acceptance-target.env" | awk '{print $1}')" ] || \
    oe_die 'routing target designation drift'
  bash -n "$OE_TOOL_DIR/routing-acceptance.sh"
  bash -n "$OE_TOOL_DIR/lib/runtime.sh"
  bash -n "$OE_TOOL_DIR/lib/db-guard.sh"
  bash -n "$OE_TOOL_DIR/lib/env-isolation.sh"
  bash -n "$OE_TOOL_DIR/run.sh"
  node --test --test-reporter=tap "$OE_TOOL_DIR/tests/routing-acceptance-guard.test.mjs"
  oe_log 'GATE 1 PASS: inert routing target and negative controls passed; no database contacted.'
}

cmd_bootstrap() {
  oe_runtime_load_designation
  export PATHOME_OE_TEST_ALLOW_LOCAL_BUILD=true
  bash "$OE_TOOL_DIR/run.sh" preflight
  bash "$OE_TOOL_DIR/run.sh" create
  bash "$OE_TOOL_DIR/run.sh" backend
}

cmd_attest() {
  oe_runtime_load_designation
  oe_runtime_load_secrets
  oe_guard_verify --destructive
  oe_guard_require_run
  node "$OE_TOOL_DIR/lib/runtime.mjs" verify
  local evidence="$PATHOME_OE_TEST_RUN_DIR/evidence/target-attestation.txt"
  mkdir -p "$(dirname "$evidence")"
  {
    printf 'attested_at=%s\n' "$(date '+%Y-%m-%d %H:%M:%S %Z')"
    printf 'acceptance=%s\n' "$PATHOME_OE_TEST_ACCEPTANCE"
    printf 'database=%s\n' "$PATHOME_OE_TEST_DB"
    printf 'host=%s\n' "$PATHOME_OE_TEST_DB_HOST"
    printf 'port=%s\n' "$PATHOME_OE_TEST_DB_PORT"
    printf 'run_id=%s\n' "$PATHOME_OE_TEST_RUN_ID"
    printf 'schema=%s\n' "$PATHOME_OE_TEST_SCHEMA"
    printf 'migrator_role=%s\n' "$PATHOME_OE_TEST_DB_MIGRATOR_USER"
    printf 'runtime_role=%s\n' "$PATHOME_OE_TEST_DB_APP_USER"
    printf 'backend_origin=http://127.0.0.1:%s\n' "${PATHOME_OE_TEST_BACKEND_PORT:-8080}"
    printf 'backend_listener_pid=%s\n' "$(node -e 'const fs=require("node:fs"); process.stdout.write(String(JSON.parse(fs.readFileSync(process.env.PATHOME_OE_TEST_RUN_DIR+"/backend-binding.json")).listenerPid))')"
    printf 'live_current_database=%s\n' "$(oe_guard_psql "$PATHOME_OE_TEST_DB" "$PATHOME_OE_TEST_DB_APP_USER" -Atc 'select current_database()')"
    printf 'marker=%s\n' "$(oe_guard_psql "$PATHOME_OE_TEST_DB" "$PATHOME_OE_TEST_DB_APP_USER" -Atc \
      "select env_marker || ':' || db_name || ':' || run_id from public.pathome_oe_test_env where env_marker='DISPOSABLE'")"
    printf 'runtime_privileges=%s\n' "$(oe_guard_psql "$PATHOME_OE_TEST_DB" "$PATHOME_OE_TEST_DB_APP_USER" -Atc \
      "select has_schema_privilege(current_user,'$PATHOME_OE_TEST_SCHEMA','CREATE')::text || ':' ||
              has_schema_privilege(current_user,'public','CREATE')::text || ':' ||
              has_database_privilege(current_user,current_database(),'CREATE')::text")"
    printf 'schema_owner=%s\n' "$(oe_guard_psql "$PATHOME_OE_TEST_DB" "$PATHOME_OE_TEST_DB_ADMIN_USER" -Atc \
      "select owner.rolname from pg_namespace n join pg_roles owner on owner.oid=n.nspowner where n.nspname='$PATHOME_OE_TEST_SCHEMA'")"
  } | tee "$evidence"
  chmod 600 "$evidence"
  grep -qx "runtime_privileges=false:false:false" "$evidence" || oe_die 'runtime privileges exceed restriction'
  grep -qx "schema_owner=$PATHOME_OE_TEST_DB_MIGRATOR_USER" "$evidence" || oe_die 'schema owner is not migrator'
  oe_log "GATE 2 ATTESTATION PASS: $evidence"
}

cmd_snapshot() {
  oe_runtime_load_designation
  umask 077
  chmod 700 "$PATHOME_OE_TEST_RUN_DIR"
  local output="$PATHOME_OE_TEST_RUN_DIR/evidence/source-baseline-before-acceptance.sha256"
  mkdir -p "$(dirname "$output")"
  (
    cd "$OE_REPO_ROOT"
    find backend/src/main backend/src/test/java/com/indore/pathome/spaces/support \
      frontend-web/src tools/oe-integration-env/lib tools/oe-integration-env/tests \
      -type f ! -path '*/target/*' -print0 |
      sort -z |
      xargs -0 shasum -a 256
  ) > "$output"
  shasum -a 256 "$output" > "$output.sha256"
  oe_log "SOURCE SNAPSHOT $output.sha256"
}

cmd_status() {
  oe_runtime_load_designation
  bash "$OE_TOOL_DIR/run.sh" status
}

cmd_cleanup() {
  oe_runtime_load_designation
  bash "$OE_TOOL_DIR/run.sh" stop-backend
  bash "$OE_TOOL_DIR/run.sh" teardown
}

case "${1:-}" in
  gate1) cmd_gate1 ;;
  bootstrap) cmd_bootstrap ;;
  attest) cmd_attest ;;
  snapshot) cmd_snapshot ;;
  status) cmd_status ;;
  cleanup) cmd_cleanup ;;
  *)
    printf 'usage: %s {gate1|snapshot|bootstrap|attest|status|cleanup}\n' "$0" >&2
    exit 2
    ;;
esac
