#!/usr/bin/env bash
# Pathome OE integration environment - isolation and invariant proofs.
#
# Every proof below is executed against the live local server. Nothing is reported as
# passing unless the command actually ran and produced the stated result.

OE_VERIFY_RESPONSE=''

# Schema comparison queries. The disposable marker table is excluded because it is
# infrastructure for this environment and intentionally not part of the application schema.
OE_VERIFY_COLUMN_NAMES="select string_agg(table_name || '.' || column_name, ',' order by table_name, column_name)
  from information_schema.columns
  where table_schema = 'public' and table_name <> 'pathome_oe_test_env'"

OE_VERIFY_COLUMN_TYPES="select table_name || '.' || column_name || ' = ' || data_type ||
    coalesce('(' || character_maximum_length || ')', '')
  from information_schema.columns
  where table_schema = 'public' and table_name <> 'pathome_oe_test_env'
  order by table_name, column_name"

# Minimal API helper. Writes the body to OE_VERIFY_RESPONSE and prints the HTTP status.
oe_verify_api() {
  local method="$1" path="$2" token="${3:-}" body="${4:-}"
  local args=(-sS -o "$OE_VERIFY_RESPONSE" -w '%{http_code}' -X "$method" "$OE_API_BASE_URL$path")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  curl "${args[@]}"
}

# Static-only guard decision for an arbitrary candidate name.
oe_verify_static_exit() {
  local candidate="$1" code=0
  ( export PATHOME_OE_TEST_DB="$candidate"; oe_guard_resolve ) >/dev/null 2>&1 || code=$?
  printf '%s' "$code"
}

# Full destructive guard decision (static rules, live identity, disposable marker).
oe_verify_destructive_exit() {
  local candidate="$1" code=0
  ( export PATHOME_OE_TEST_DB="$candidate"; oe_guard_verify --destructive ) >/dev/null 2>&1 || code=$?
  printf '%s' "$code"
}

# Runs a psql statement expected to be rejected by a protection trigger. Prints the
# server message, or "ALLOWED" when the statement unexpectedly succeeded.
oe_verify_rejection() {
  local statement="$1"
  local output
  if output="$(oe_psql_app "$PATHOME_OE_TEST_DB" -q -c "$statement" 2>&1)"; then
    printf 'ALLOWED'
  else
    printf '%s' "$output" | tr '\n' ' '
  fi
}

# Classifies a rejection result as rejected / allowed / rejected-for-another-reason.
oe_verify_rejection_outcome() {
  local outcome="$1" needle="$2"
  if [ "$outcome" = 'ALLOWED' ]; then
    printf 'allowed'
  elif printf '%s' "$outcome" | grep -q "$needle"; then
    printf 'rejected'
  else
    printf 'rejected-other'
  fi
}

# PostgreSQL prints booleans as t/f; the proofs compare human-readable text instead.
oe_verify_bool() { # <sql predicate over the disposable database>
  oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
    "select case when ($1) then 'true' else 'false' end"
}

oe_verify_main() {
  local failures=0
  local evidence_dir="$PATHOME_OE_TEST_RUN_DIR/evidence"
  local evidence="$evidence_dir/verification.txt"
  OE_VERIFY_RESPONSE="$PATHOME_OE_TEST_RUN_DIR/verify-response.json"
  mkdir -p "$evidence_dir"

  # Mirror everything into the evidence file while still printing to the terminal.
  exec > >(tee "$evidence") 2>&1

  check() { # LABEL EXPECTED ACTUAL
    if [ "$2" = "$3" ]; then
      printf 'PASS  %-80s %s\n' "$1" "$3"
    else
      printf 'FAIL  %-80s expected=%s actual=%s\n' "$1" "$2" "$3"
      failures=$((failures + 1))
    fi
  }

  printf '=== Pathome OE integration environment verification ===\n'
  printf 'designation     %s\n' "$PATHOME_OE_TEST_DB"
  printf 'jdbc target     %s\n' "$(oe_guard_jdbc_url)"
  printf 'guard version   %s\n' "$OE_GUARD_VERSION"
  printf 'started         %s\n\n' "$(date '+%Y-%m-%d %H:%M:%S %Z')"

  # -----------------------------------------------------------------------------------
  printf -- '--- 1. the guard refuses every target that is not the designated one -------\n'
  # -----------------------------------------------------------------------------------
  oe_guard_selftest || failures=$((failures + 1))
  printf '\n'
  check 'the guard accepts the explicitly designated disposable database' '0' \
    "$(oe_guard_static_exit_code PATHOME_OE_TEST_ALLOW_DISPOSABLE='true' \
       PATHOME_OE_TEST_DB="$PATHOME_OE_TEST_DB")"
  printf '\n'

  # -----------------------------------------------------------------------------------
  printf -- '--- 2. the live connection is the designated database ----------------------\n'
  # -----------------------------------------------------------------------------------
  local live_code=0
  oe_guard_verify --destructive >/dev/null 2>&1 || live_code=$?
  check 'live identity, static rules and the disposable marker all verify' '0' "$live_code"
  check 'the connected database reports its own name as the designation' "$PATHOME_OE_TEST_DB" \
    "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc 'select current_database()')"
  check 'the positive DISPOSABLE marker is present exactly once' '1' \
    "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
      "select count(*) from pathome_oe_test_env where env_marker='DISPOSABLE' and db_name=current_database()")"
  printf '\n'

  # -----------------------------------------------------------------------------------
  printf -- '--- 3. an unmarked database cannot be destroyed -----------------------------\n'
  # -----------------------------------------------------------------------------------
  local probe="pathome_oe_test_guardprobe_$(date +%s)"
  local code
  code="$(oe_verify_static_exit "$probe")"
  check "a freshly named disposable target passes the static rules ($probe)" '0' "$code"

  oe_psql_admin postgres -c "CREATE DATABASE \"$probe\" OWNER \"$PATHOME_OE_TEST_DB_APP_USER\"" >/dev/null
  code="$(oe_verify_destructive_exit "$probe")"
  check 'destruction of that database is REFUSED while it lacks the marker' \
    "$OE_GUARD_ERR_MISSING_SENTINEL" "$code"

  oe_psql_app "$probe" -q \
    -v "oe_database=$probe" -v "oe_guard_version=$OE_GUARD_VERSION" -v "oe_run_id=guardprobe" \
    -f "$OE_TOOL_DIR/sql/00-disposable-marker.sql" >/dev/null
  code="$(oe_verify_destructive_exit "$probe")"
  check 'destruction is permitted once the disposable marker is present' '0' "$code"

  oe_psql_admin postgres -c "DROP DATABASE IF EXISTS \"$probe\" WITH (FORCE)" >/dev/null
  check 'the probe database was removed again' '0' \
    "$(oe_psql_admin postgres -Atc "select count(*) from pg_database where datname = '$probe'")"
  printf '\n'

  # -----------------------------------------------------------------------------------
  printf -- '--- 4. the normal schema was reproduced by supported Flyway migrations ------\n'
  # -----------------------------------------------------------------------------------
  local disposable_max preserved_max disposable_count preserved_count
  disposable_max="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
    'select version from flyway_schema_history where success order by installed_rank desc limit 1')"
  preserved_max="$(oe_psql_app "$OE_PROTECTED_DEV_DB" -Atc \
    'select version from flyway_schema_history where success order by installed_rank desc limit 1')"
  disposable_count="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
    'select count(*) from flyway_schema_history')"
  preserved_count="$(oe_psql_app "$OE_PROTECTED_DEV_DB" -Atc \
    'select count(*) from flyway_schema_history')"

  check 'completed Flyway history matches the preserved development database' "$preserved_count" "$disposable_count"
  check 'highest applied migration matches the preserved development database' "$preserved_max" "$disposable_max"
  check 'no failed migration is recorded' '0' \
    "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
      'select count(*) from flyway_schema_history where not success')"
  check 'the v1 legacy baseline row is present' '1' \
    "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
      "select count(*) from flyway_schema_history where version = '1'")"

  local disposable_versions preserved_versions
  disposable_versions="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
    'select string_agg(version, E'"'"','"'"' order by installed_rank) from flyway_schema_history')"
  preserved_versions="$(oe_psql_app "$OE_PROTECTED_DEV_DB" -Atc \
    'select string_agg(version, E'"'"','"'"' order by installed_rank) from flyway_schema_history')"
  check 'the applied migration sequence is identical' "$preserved_versions" "$disposable_versions"

  local disposable_tables preserved_tables
  disposable_tables="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
    "select string_agg(table_name, ',' order by table_name) from information_schema.tables
      where table_schema='public' and table_name <> 'pathome_oe_test_env'")"
  preserved_tables="$(oe_psql_app "$OE_PROTECTED_DEV_DB" -Atc \
    "select string_agg(table_name, ',' order by table_name) from information_schema.tables
      where table_schema='public'")"
  if [ "$disposable_tables" = "$preserved_tables" ]; then
    check 'the public table set matches the preserved development database' 'identical' 'identical'
  else
    check 'the public table set matches the preserved development database' 'identical' 'differs'
    printf '       present only in disposable: %s\n' \
      "$(comm -13 <(printf '%s' "$preserved_tables" | tr ',' '\n' | sort) \
                  <(printf '%s' "$disposable_tables" | tr ',' '\n' | sort) | paste -sd, -)"
    printf '       present only in preserved:  %s\n' \
      "$(comm -23 <(printf '%s' "$preserved_tables" | tr ',' '\n' | sort) \
                  <(printf '%s' "$disposable_tables" | tr ',' '\n' | sort) | paste -sd, -)"
  fi

  check 'the staff-governance tables exist in the disposable schema' '3' \
    "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
      "select count(*) from information_schema.tables where table_schema='public'
        and table_name in ('staff_access_grants','operational_audit_events','employee_profiles')")"

  local disposable_columns preserved_columns
  disposable_columns="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc "$OE_VERIFY_COLUMN_NAMES")"
  preserved_columns="$(oe_psql_app "$OE_PROTECTED_DEV_DB" -Atc "$OE_VERIFY_COLUMN_NAMES")"
  check 'the public column set matches the preserved development database' \
    "$(printf '%s' "$preserved_columns" | shasum -a 256 | awk '{print $1}')" \
    "$(printf '%s' "$disposable_columns" | shasum -a 256 | awk '{print $1}')"

  # Column *types* are reported separately. The preserved database was converged over time by
  # Hibernate schema updates, so a Flyway-only database can legitimately differ here; the
  # difference is measured and printed rather than hidden.
  local type_diff
  type_diff="$(comm -3 \
    <(oe_psql_app "$OE_PROTECTED_DEV_DB" -Atc "$OE_VERIFY_COLUMN_TYPES" | sort) \
    <(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc "$OE_VERIFY_COLUMN_TYPES" | sort))"
  if [ -z "$type_diff" ]; then
    printf 'NOTE  no column-type divergence from the preserved development database\n'
  else
    printf 'NOTE  column types converged by earlier Hibernate runs in the preserved database,\n'
    printf '      and therefore absent from a Flyway-only build (informational):\n'
    printf '%s\n' "$type_diff" | sed -e 's/^\t/      disposable: /' -e 's/^\([^ 	]\)/      preserved:  \1/'
  fi
  printf '\n'

  # -----------------------------------------------------------------------------------
  printf -- '--- 5. genuine authenticated staff provisioning ----------------------------\n'
  # -----------------------------------------------------------------------------------
  if [ -f "$evidence_dir/provisioning.json" ] && \
     [ "$(jq -r '.failures' < "$evidence_dir/provisioning.json")" = '0' ]; then
    check 'the recorded provisioning run is green' 'green' 'green'
  else
    check 'the recorded provisioning run is green' 'green' 'missing-or-failing'
  fi

  check 'bootstrap Admin signs in against the running application' '200' \
    "$(oe_verify_api POST /auth/login '' \
      "$(jq -nc --arg e "$OE_BOOTSTRAP_ADMIN_EMAIL" --arg p "$OE_BOOTSTRAP_ADMIN_PASSWORD" \
          '{email:$e, password:$p}')")"

  local email token status
  for email in codex.oe.env.cityadmin@pathome.test codex.oe.env.intake@pathome.test codex.oe.env.nogrant@pathome.test; do
    check "staff identity $email exists on the disposable database" '1' \
      "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
        "select count(*) from users where email = '$email'")"
    check "staff identity $email is absent from the preserved database" '0' \
      "$(oe_psql_app "$OE_PROTECTED_DEV_DB" -Atc \
        "select count(*) from users where email = '$email'")"
  done

  oe_verify_api POST /auth/login '' \
    "$(jq -nc --arg e 'codex.oe.env.cityadmin@pathome.test' --arg p "$OE_STAFF_PASSWORD" \
        '{email:$e, password:$p}')" >/dev/null
  token="$(jq -r '.token // empty' < "$OE_VERIFY_RESPONSE")"
  status="$(oe_verify_api GET /staff/me/capabilities "$token")"
  check 'the provisioned Sub-Admin reads CITY_TEAM_ADMIN@CITY(indore) from the live API' '1' \
    "$(jq -r '[.capabilities[] | select(.capability=="CITY_TEAM_ADMIN" and .scopeType=="CITY"
                                  and .cityDisplayName=="Indore")] | length' < "$OE_VERIFY_RESPONSE")"
  check 'the provisioned Sub-Admin staff profile is active in the database' 'true' \
    "$(oe_verify_bool \
      "select staff_active from employee_profiles p join users u on u.id = p.user_id
        where u.email = 'codex.oe.env.cityadmin@pathome.test'")"
  printf '\n'

  # -----------------------------------------------------------------------------------
  printf -- '--- 6. immutable staff grants and audit history -----------------------------\n'
  # -----------------------------------------------------------------------------------
  local grants_before audits_before grants_after audits_after outcome
  grants_before="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc 'select count(*) from staff_access_grants')"
  audits_before="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc 'select count(*) from operational_audit_events')"

  check 'no staff grant was recorded outside the Admin API and the bootstrap' '0' \
    "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
      "select count(*) from staff_access_grants
        where provisioning_source not in ('ADMIN_API','INITIAL_BOOTSTRAP')")"
  check 'operational audit history recorded the provisioning' 'true' \
    "$(oe_verify_bool \
      "select count(*) > 0 from operational_audit_events where target_type = 'USER'")"

  outcome="$(oe_verify_rejection \
    'DELETE FROM staff_access_grants WHERE id = (SELECT max(id) FROM staff_access_grants)')"
  check 'deleting a staff grant history row is rejected' 'rejected' \
    "$(oe_verify_rejection_outcome "$outcome" 'cannot be deleted')"

  outcome="$(oe_verify_rejection \
    "UPDATE staff_access_grants SET capability = 'STAFF_ADMIN' WHERE id = (SELECT max(id) FROM staff_access_grants)")"
  check 'rewriting a staff grant fact is rejected' 'rejected' \
    "$(oe_verify_rejection_outcome "$outcome" 'immutable')"

  outcome="$(oe_verify_rejection \
    'DELETE FROM operational_audit_events WHERE id = (SELECT max(id) FROM operational_audit_events)')"
  check 'deleting an operational audit event is rejected' 'rejected' \
    "$(oe_verify_rejection_outcome "$outcome" 'immutable')"

  grants_after="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc 'select count(*) from staff_access_grants')"
  audits_after="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc 'select count(*) from operational_audit_events')"
  check 'the rejected probes changed no grant history' "$grants_before" "$grants_after"
  check 'the rejected probes changed no audit history' "$audits_before" "$audits_after"
  check 'this run revoked nothing: grants accumulate, they are never reset' '0' \
    "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
      'select count(*) from staff_access_grants where revoked_at is not null')"
  printf '\n'

  # -----------------------------------------------------------------------------------
  printf -- '--- 7. the preserved development database is unchanged ---------------------\n'
  # -----------------------------------------------------------------------------------
  local before_file="$evidence_dir/protected-db-before.txt"
  if [ -f "$before_file" ]; then
    local expected actual
    expected="$(cat "$before_file")"
    actual="$(oe_protected_fingerprint)"
    if [ "$expected" = "$actual" ]; then
      check 'pathome_db data and schema digests match the pre-run recording' 'identical' 'identical'
    else
      check 'pathome_db data and schema digests match the pre-run recording' 'identical' 'CHANGED'
      printf '       before:\n%s\n       after:\n%s\n' "$expected" "$actual"
    fi
  else
    check "a pre-run fingerprint was recorded at $before_file" 'present' 'missing'
  fi
  printf '\n'

  printf '=== VERIFICATION SUMMARY: failures=%s ===\n' "$failures"
  printf 'evidence: %s\n' "$evidence"
  return "$failures"
}
