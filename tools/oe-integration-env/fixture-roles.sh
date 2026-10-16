#!/usr/bin/env bash
# Explicit disposable fixture exception: two freshly registered synthetic users only. No production
# provisioning path is changed. Staff activation/grants still use the application's audited API.
set -euo pipefail
OE_TOOL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OE_REPO_ROOT="$(cd "$OE_TOOL_DIR/../.." && pwd)"
OE_ENV_FILE="${PATHOME_OE_TEST_ENV_FILE:-$OE_TOOL_DIR/oe-test.env}"
OE_BACKEND_PORT="${PATHOME_OE_TEST_BACKEND_PORT:-8080}"
. "$OE_TOOL_DIR/lib/db-guard.sh"
oe_runtime_load_designation
[ "${PATHOME_OE_TEST_FIXTURE_ROLES:-}" = true ] || oe_iso_refuse 'explicit disposable fixture-role opt-in is required'
[ "$#" = 3 ] || oe_iso_refuse 'fixture role command requires two IDs and a run tag'
[[ "$1" =~ ^[1-9][0-9]{0,14}$ ]] && [[ "$2" =~ ^[1-9][0-9]{0,14}$ ]] && [[ "$3" =~ ^[a-f0-9]{10}$ ]] || oe_iso_refuse 'invalid synthetic actor identifiers'
[ "$1" != "$2" ] || oe_iso_refuse 'fixture actors must differ'
oe_runtime_load_secrets
oe_runtime_require_backend
oe_guard_require_run
oe_guard_psql "$PATHOME_OE_TEST_DB" "$PATHOME_OE_TEST_DB_APP_USER" \
  -v "target_db=$PATHOME_OE_TEST_DB" -v "run_id=$OE_RUN_ID" -v "admin_id=$1" -v "ge_id=$2" -v "tag=$3" \
  -f "$OE_TOOL_DIR/sql/fixture-roles.sql"
