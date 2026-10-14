#!/usr/bin/env bash
# Package 3B verification: focused routing tests against the designated disposable database, and a
# genuine Chromium pass over the approved routing surface. Every step passes the target guard first.

P3B_POSTGRES_TESTS='OperationalIntakeRoutingReadServiceTest,OperationalIntakeRoutingControllerTest,OperationalIntakeRoutingHttpPostgresTest'

oe_p3b_verify_postgres_main() {
  oe_load_designation
  local jdbc_url
  jdbc_url="$(oe_guard_jdbc_url)"

  export PATHOME_VISIT_OPERATIONS_POSTGRES_TEST=true
  oe_log "preflight     verifying $jdbc_url against the approved Package 3B disposable policy"
  oe_guard_verify_package3_acceptance "$jdbc_url" || return 1
  oe_guard_package3_selftest || return 1
  oe_log "marker        $(oe_guard_psql "$PATHOME_OE_TEST_DB" "$PATHOME_OE_TEST_DB_APP_USER" -Atc \
    "select env_marker || ' recorded for ' || db_name from $OE_GUARD_SENTINEL_TABLE \
     where env_marker = '$OE_GUARD_SENTINEL_MARKER' and db_name = current_database()")"

  oe_log "focused Package 3B PostgreSQL/HTTP tests against $jdbc_url"
  (
    set -a
    [ -f "$OE_REPO_ROOT/.env.local" ] && . "$OE_REPO_ROOT/.env.local"
    set +a
    export PATHOME_VISIT_OPERATIONS_POSTGRES_TEST=true
    export SPRING_DATASOURCE_URL="$jdbc_url"
    export SPRING_DATASOURCE_USERNAME="$PATHOME_OE_TEST_DB_APP_USER"
    export PATHOME_OE_TEST_DB
    mvn -f "$OE_REPO_ROOT/backend/pom.xml" -o \
      -Dtest="$P3B_POSTGRES_TESTS" -DfailIfNoTests=false test
  )
}

oe_p3b_verify_browser_main() {
  local harness="$OE_REPO_ROOT/frontend-web/browser-verification/operations-governed-intake/run.mjs"
  [ -f "$harness" ] || oe_die "the governed intake browser harness is missing"
  [ -f "$OE_P3_IDENTITIES" ] || oe_die "run 'package3.sh provision' first: no ephemeral identities found"
  [ -d "$OE_REPO_ROOT/frontend-web/dist" ] || oe_die "frontend-web/dist is missing; run 'npm --prefix frontend-web run build'"
  (
    set -a
    [ -f "$PATHOME_OE_TEST_RUN_DIR/secrets.env" ] && . "$PATHOME_OE_TEST_RUN_DIR/secrets.env"
    # shellcheck disable=SC1090
    . "$OE_P3_IDENTITIES"
    set +a
    export P3B_BACKEND_URL="http://localhost:$OE_BACKEND_PORT"
    export P3B_ADMIN_EMAIL="$OE_BOOTSTRAP_ADMIN_EMAIL"
    export P3B_ADMIN_PASSWORD="$OE_BOOTSTRAP_ADMIN_PASSWORD"
    export P3B_CITY_ID="$P3A_CITY_ID"
    node "$harness"
  )
}
