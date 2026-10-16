#!/usr/bin/env bash
# Pathome OE integration environment - isolation and invariant proofs.
#
# Every proof below is executed against the live local server. Nothing is reported as
# passing unless the command actually ran and produced the stated result.
#
# Disposable-only by construction: this suite reads, and compares against, the designated disposable
# database and repository artefacts only. It never inspects, dumps, fingerprints or compares against
# a protected database, and it therefore makes - and claims - nothing about one.

OE_VERIFY_RESPONSE=''

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
  # The no-marker refusal is covered by inert negative controls. Creating an intentionally
  # unmarked live database introduced an unrecoverable interruption window and is unnecessary.
  printf 'NOT TESTED  live unmarked-probe destruction (no extra database is created).\n'
  printf 'Guard refusal is verified by the nonconnecting regression batch.\n\n'

  # -----------------------------------------------------------------------------------
  printf -- '--- 4. the schema was reproduced by the repository-controlled migrations ----\n'
  # -----------------------------------------------------------------------------------
  # Every expectation below is read from a repository artefact - the Flyway migration files, the
  # supported legacy v1 baseline, and the committed expected-schema.txt. Nothing is compared with,
  # or derived from, any database outside this environment.
  local expected_versions applied_versions expected_count applied_count live_base expected_base
  local live_views expected_views missing_objects unexpected_objects

  expected_versions="$(oe_repo_expected_versions | paste -sd, -)"
  applied_versions="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
    "select string_agg(version, ',' order by installed_rank) from flyway_schema_history where success")"
  expected_count="$(oe_repo_expected_versions | wc -l | tr -d ' ')"
  applied_count="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
    'select count(*) from flyway_schema_history where success')"

  check 'the applied migration sequence equals the repository migration sequence' \
    "$expected_versions" "$applied_versions"
  check 'the applied migration count equals the repository migration count' \
    "$expected_count" "$applied_count"
  check 'the highest applied migration is the highest repository migration' \
    "$(oe_repo_migration_versions | tail -1)" \
    "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
      'select version from flyway_schema_history where success order by installed_rank desc limit 1')"
  check 'no failed migration is recorded' '0' \
    "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
      'select count(*) from flyway_schema_history where not success')"
  check 'the v1 legacy baseline row is present and successful' '1' \
    "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
      "select count(*) from flyway_schema_history where version = '1' and success")"

  expected_base="$( { oe_repo_expected_tables base; oe_repo_expected_tables infra; } | LC_ALL=C sort -u )"
  live_base="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
    "select table_name from information_schema.tables
      where table_schema = 'public' and table_type = 'BASE TABLE' order by table_name" | LC_ALL=C sort -u)"
  if [ "$live_base" = "$expected_base" ]; then
    check 'the public base-table set equals the repository-controlled expectation' 'identical' 'identical'
  else
    check 'the public base-table set equals the repository-controlled expectation' 'identical' 'differs'
    printf '       expected but absent: %s\n' \
      "$(comm -23 <(printf '%s\n' "$expected_base") <(printf '%s\n' "$live_base") | paste -sd, -)"
    printf '       present but unexpected: %s\n' \
      "$(comm -13 <(printf '%s\n' "$expected_base") <(printf '%s\n' "$live_base") | paste -sd, -)"
    printf '       update %s only with a reviewed repository change\n' "$OE_EXPECTED_SCHEMA"
  fi

  expected_views="$(oe_repo_expected_tables view | LC_ALL=C sort -u)"
  live_views="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
    "select table_name from information_schema.tables
      where table_schema = 'public' and table_type = 'VIEW' order by table_name" | LC_ALL=C sort -u)"
  check 'the public view set equals the repository-controlled expectation' \
    "$(printf '%s' "$expected_views" | shasum -a 256 | awk '{print $1}')" \
    "$(printf '%s' "$live_views" | shasum -a 256 | awk '{print $1}')"

  # The columns every approved Phase 2A Operations workflow depends on, all declared by migrations.
  # A missing column means the disposable schema is not the schema this environment claims to test.
  local required_columns='property_visit_requests.id
property_visit_requests.tenant_id
property_visit_requests.status
property_visit_requests.version
property_visit_requests.session_id
property_visit_requests.supported_city_id
property_visit_requests.operating_team_id
property_visit_requests.coordinator_user_id
property_visit_requests.operational_scope_ready
property_visit_requests.availability_start_at
property_visit_requests.availability_end_at
property_visit_requests.availability_zone_id
visit_sessions.id
visit_sessions.tenant_id
visit_sessions.status
visit_sessions.version
visit_sessions.scheduled_at
visit_sessions.zone_id
visit_sessions.representative_user_id
visit_sessions.supported_city_id
visit_sessions.operating_team_id
visit_sessions.coordinator_user_id
visit_sessions.operational_scope_ready
visit_session_items.id
visit_session_items.session_id
visit_session_items.listing_id
visit_session_items.position
visit_session_items.confirmation_status
visit_session_items.availability_start_at
visit_session_items.availability_end_at
visit_session_items.availability_zone_id
visit_session_items.availability_source
visit_session_items.confirmed_by_user_id
visit_session_items.removed_at
operating_teams.id
operating_teams.city_id
operating_teams.code
operating_teams.active
operating_teams.version
supported_cities.id
supported_cities.code
supported_cities.active
supported_cities.version
staff_access_grants.id
staff_access_grants.user_id
staff_access_grants.capability
staff_access_grants.scope_type
staff_access_grants.city_id
staff_access_grants.team_id
staff_access_grants.revoked_at
staff_access_grants.provisioning_source
operational_audit_events.id
operational_audit_events.action_code
operational_audit_events.target_type
operational_audit_events.target_id
employee_profiles.staff_active
employee_profiles.version
listings.id
listings.status
listings.canonical_locality_id
listings.published_at'
  missing_objects="$(while IFS= read -r column; do
    [ -n "$column" ] || continue
    table="${column%%.*}"
    name="${column#*.}"
    found="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
      "select count(*) from information_schema.columns
        where table_schema = 'public' and table_name = '$table' and column_name = '$name'")"
    [ "$found" = '1' ] || printf '%s,' "$column"
  done <<< "$required_columns")"
  check 'every column the Phase 2A workflows depend on exists' '' "$missing_objects"
  unexpected_objects="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
    "select count(*) from information_schema.columns
      where table_schema = 'public' and column_name in ('oe_database', 'oe_marker')")"
  check 'the disposable marker never leaks into an application table' '0' "$unexpected_objects"

  printf 'NOTE  repository schema-source fingerprint: %s\n' "$(oe_repo_schema_source_fingerprint)"
  printf '      (sha256 over the legacy baseline and every migration file: recorded as evidence)\n'
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
    check "staff identity $email exists exactly once on the disposable database" '1' \
      "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
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
  printf -- '--- 7. auditable evidence of the actual disposable test target ------------\n'
  # -----------------------------------------------------------------------------------
  # Disposable-only. This environment never reads, dumps, fingerprints or compares against a
  # protected database, so no proof here - and no proof anywhere in this run - is a claim about one.
  local attestation="$evidence_dir/target-attestation.txt"
  cmd_attest >/dev/null 2>&1 || true
  if [ -f "$attestation" ]; then
    check 'the target attestation records the live designation as its own name' '1' \
      "$(grep -c "^live_current_database=$PATHOME_OE_TEST_DB\$" "$attestation")"
    check 'the target attestation records the positive DISPOSABLE marker' '1' \
      "$(grep -c "^disposable_marker=DISPOSABLE db_name=$PATHOME_OE_TEST_DB " "$attestation")"
    check 'the target attestation records the guard deny rules' '1' \
      "$(grep -c '^protected_deny_rules=' "$attestation")"
    check 'the guard deny list still names the protected databases' '1' \
      "$(grep -c '^protected_deny_rules=.*pathome_db' "$attestation")"
    check 'the target attestation states that no protected database was inspected' '1' \
      "$(grep -c 'NOT evidence that any protected database is unchanged' "$attestation")"
    check 'the attestation records exactly one live database identity' '1' \
      "$(grep -c '^live_current_database=' "$attestation")"
  else
    check "the target attestation was produced at $attestation" 'present' 'missing'
  fi
  printf '\n'

  printf '=== VERIFICATION SUMMARY: failures=%s ===\n' "$failures"
  printf 'evidence: %s\n' "$evidence"
  return "$failures"
}
