#!/usr/bin/env bash
# Package 3A verification: genuine OE admission over real HTTP, negative controls, and a database
# cross-check that every authority fact came from the supported provisioning APIs.

OE_P3_VERIFY_FAILURES=0

oe_p3_check() { # LABEL EXPECTED ACTUAL
  if [ "$2" = "$3" ]; then
    printf 'PASS  %-78s %s\n' "$1" "$3"
  else
    printf 'FAIL  %-78s expected=%s actual=%s\n' "$1" "$2" "$3"
    OE_P3_VERIFY_FAILURES=$((OE_P3_VERIFY_FAILURES + 1))
  fi
}

oe_p3_check_true() { # LABEL CONDITION
  if [ "$2" = 'true' ]; then
    printf 'PASS  %-78s %s\n' "$1" 'true'
  else
    printf 'FAIL  %-78s expected=true actual=%s\n' "$1" "$2"
    OE_P3_VERIFY_FAILURES=$((OE_P3_VERIFY_FAILURES + 1))
  fi
}

oe_p3_session() { # EMAIL PASSWORD -> token
  local pair
  pair="$(oe_p3_sign_in "$1" "$2")" || return 1
  printf '%s' "${pair#* }"
}

oe_p3_capability_count() { # TOKEN CAPABILITY SCOPE_TYPE -> count
  oe_p3_api GET /staff/me/capabilities "$1" >/dev/null
  jq -r --arg c "$2" --arg s "$3" \
    '[.capabilities[] | select(.capability==$c and .scopeType==$s)] | length' < "$OE_P3_RESPONSE"
}

# A bounded negative-control command body, built with jq so no shell quoting can corrupt it.
oe_p3_control_body() { # CODE DISPLAY_NAME CITY_ID
  jq -nc --arg c "$1" --arg n "$2" --argjson v "$(oe_p3_city_version "$3")" \
    '{code:$c, displayName:$n, expectedCityVersion:$v, reasonCode:"PACKAGE3_NEGATIVE_CONTROL"}'
}

oe_p3_verify_http_main() {
  oe_p3_prepare
  local alpha_email="$1" alpha_password="$2" bravo_email="$3" no_grant_email="$4" city_admin_email="$5"
  local password="$6" city_id="$7" team_alpha="$8" team_bravo="$9"
  local alpha_token bravo_token nogrant_token cityadmin_token tenant_email tenant_token status body

  printf '\n=== Package 3A: governed Teams exist in the designated database ===\n'
  oe_p3_check 'Team alpha is persisted and active' 'true' \
    "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
        "select (active)::text from operating_teams where id = $team_alpha and city_id = $city_id")"
  oe_p3_check 'Team bravo is persisted and active' 'true' \
    "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
        "select (active)::text from operating_teams where id = $team_bravo and city_id = $city_id")"
  oe_p3_check 'Team codes are canonical lowercase slugs' 'true' \
    "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
        "select (code ~ '^[a-z0-9]+(-[a-z0-9]+)*\$')::text from operating_teams where id = $team_alpha")"
  oe_p3_check 'every Package 3A grant is ADMIN_API provisioned, never hand-written' '0' \
    "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
        "select count(*) from staff_access_grants g join operating_teams t on t.id = g.team_id \
         where t.code like 'p3a-%' and g.provisioning_source <> 'ADMIN_API'")"
  oe_p3_check 'a creation audit event exists for Team alpha' '1' \
    "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
        "select count(*) from operational_audit_events where action_code = 'OPERATING_TEAM_CREATED' \
         and target_type = 'OPERATING_TEAM' and target_id = $team_alpha")"

  printf '\n=== Package 3A: genuine OE admission (real login, real capability, real protected read) ===\n'
  alpha_token="$(oe_p3_session "$alpha_email" "$alpha_password")" || {
    printf 'FAIL  authorised OE cannot sign in over HTTP\n'; OE_P3_VERIFY_FAILURES=$((OE_P3_VERIFY_FAILURES + 1)); return 1; }
  bravo_token="$(oe_p3_session "$bravo_email" "$alpha_password")"
  nogrant_token="$(oe_p3_session "$no_grant_email" "$alpha_password")"
  cityadmin_token="$(oe_p3_session "$city_admin_email" "$alpha_password")"

  status="$(oe_p3_api GET /staff/me/capabilities "$alpha_token")"
  oe_p3_check 'authorised OE capability read answers 200' '200' "$status"
  oe_p3_check 'the OE staff profile is active' 'true' "$(jq -r '.staffActive' < "$OE_P3_RESPONSE")"
  oe_p3_check 'the OE holds OPS_COORDINATE@TEAM(alpha)' '1' \
    "$(jq -r --argjson t "$team_alpha" \
        '[.capabilities[] | select(.capability=="OPS_COORDINATE" and .scopeType=="TEAM" and .teamId==$t)] | length' \
        < "$OE_P3_RESPONSE")"

  status="$(oe_p3_api GET '/operations/claimable-work?page=0&size=20' "$alpha_token")"
  oe_p3_check 'admitted OE reads claimable work (200) with an empty, truthful page' '200' "$status"
  oe_p3_check 'the claimable page reports zero Team-routed items (Package 3B is not implemented)' '0' \
    "$(jq -r '.totalCount' < "$OE_P3_RESPONSE")"
  check_no_store='no-store'
  oe_p3_check 'protected read responses are not cacheable' "$check_no_store" \
    "$(curl -sS -D - -o /dev/null -H "Authorization: Bearer $alpha_token" \
        "$OE_API_BASE_URL/operations/claimable-work" | tr -d '\r' | awk 'tolower($1)=="cache-control:" {print $2}')"
  status="$(oe_p3_api GET '/operations/my-work?page=0&size=20' "$alpha_token")"
  oe_p3_check 'admitted OE reads My Work (200)' '200' "$status"

  status="$(oe_p3_api GET /staff/me/capabilities '')"
  oe_p3_check 'the capability endpoint refuses an anonymous caller (401)' '401' "$status"
  status="$(oe_p3_api GET /operations/claimable-work '')"
  oe_p3_check 'a protected Operations read refuses an anonymous caller (401)' '401' "$status"

  printf '\n=== Package 3A: negative controls ===\n'
  tenant_email="codex.p3a.tenant@pathome.test"
  local tenant_id
  tenant_id="$(oe_p3_ensure_user "$tenant_email" "$password" 'P3A Tenant Control')"
  tenant_token="$(oe_p3_session "$tenant_email" "$password")"
  oe_p3_check 'the ordinary Tenant control holds no staff profile' 'false' \
    "$(oe_p3_api GET /staff/me/capabilities "$tenant_token" >/dev/null; jq -r '.staffActive' < "$OE_P3_RESPONSE")"
  oe_p3_check 'the ordinary Tenant holds no OPS_COORDINATE grant' '0' \
    "$(oe_p3_capability_count "$tenant_token" OPS_COORDINATE TEAM)"
  oe_p3_check 'the ordinary Tenant is refused claimable work (403)' '403' \
    "$(oe_p3_api GET /operations/claimable-work "$tenant_token")"
  oe_p3_check 'the ordinary Tenant is refused My Work (403)' '403' \
    "$(oe_p3_api GET /operations/my-work "$tenant_token")"

  oe_p3_check 'active staff without a grant is admitted to the capability endpoint but holds no OE grant' '0' \
    "$(oe_p3_capability_count "$nogrant_token" OPS_COORDINATE TEAM)"
  oe_p3_check 'active staff without a grant is refused claimable work (403)' '403' \
    "$(oe_p3_api GET /operations/claimable-work "$nogrant_token")"
  oe_p3_check 'a City Team Admin holds no OE coordinate grant' '0' \
    "$(oe_p3_capability_count "$cityadmin_token" OPS_COORDINATE TEAM)"
  oe_p3_check 'a City Team Admin is refused claimable work (403)' '403' \
    "$(oe_p3_api GET /operations/claimable-work "$cityadmin_token")"
  oe_p3_check 'a City Team Admin cannot create Teams through the governed API' '403' \
    "$(oe_p3_api POST "/staff/admin/cities/$city_id/operating-teams" "$cityadmin_token" \
        "$(oe_p3_control_body 'p3a-city-admin-attempt' 'Denied' "$city_id")")"
  oe_p3_check 'the refused creation produced no Team' '0' \
    "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
        "select count(*) from operating_teams where code = 'p3a-city-admin-attempt'")"
  oe_p3_check 'the ordinary Tenant cannot read the governed City list' '403' \
    "$(oe_p3_api GET /staff/admin/operating-teams/cities "$tenant_token")"
  oe_p3_check 'the OE itself cannot create Teams' '403' \
    "$(oe_p3_api POST "/staff/admin/cities/$city_id/operating-teams" "$alpha_token" \
        "$(oe_p3_control_body 'p3a-oe-attempt' 'Denied' "$city_id")")"
  oe_p3_check 'the refused OE creation produced no Team' '0' \
    "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
        "select count(*) from operating_teams where code = 'p3a-oe-attempt'")"

  printf '\n=== Package 3A: Team scope isolation in the live capability truth ===\n'
  oe_p3_check 'OE bravo holds exactly one OPS_COORDINATE grant' '1' \
    "$(oe_p3_capability_count "$bravo_token" OPS_COORDINATE TEAM)"
  oe_p3_check 'OE bravo cannot see Team alpha in its own capability truth' '0' \
    "$(oe_p3_api GET /staff/me/capabilities "$bravo_token" >/dev/null; \
       jq -r --argjson t "$team_alpha" \
       '[.capabilities[] | select(.teamId==$t)] | length' < "$OE_P3_RESPONSE")"
  oe_p3_check 'OE bravo is admitted to its own Team scope' '200' \
    "$(oe_p3_api GET /operations/claimable-work "$bravo_token")"
  oe_p3_check 'the refused Team cannot be reached through a Team-scoped grant' '0' \
    "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
        "select count(*) from staff_access_grants g join operating_teams t on t.id=g.team_id \
         where t.code='p3a-team-bravo' and g.team_id = $team_alpha")"

  printf '\n=== Package 3A: the Tenant control is an ordinary account only ===\n'
  oe_p3_check 'the Tenant control has no Team-scoped grant of any capability' '0' \
    "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
        "select count(*) from staff_access_grants g join operating_teams t on t.id=g.team_id \
         where g.user_id = $tenant_id and t.code like 'p3a-%'")"

  if [ "$OE_P3_VERIFY_FAILURES" -ne 0 ]; then
    printf '\nPackage 3A HTTP verification: %s failure(s)\n' "$OE_P3_VERIFY_FAILURES" >&2
    return 1
  fi
  printf '\nPackage 3A HTTP verification: all checks passed\n'
}

oe_p3_verify_postgres_main() {
  oe_load_designation
  local jdbc_url
  jdbc_url="$(oe_guard_jdbc_url)"

  # Fail-closed preflight, performed independently of the tests: static rules, the exact approved
  # disposable database and port, the live identity, the live disposable marker, and the datasource that
  # is about to be handed to Maven. Nothing reaches Maven unless every check passes, and the tests repeat
  # the whole policy themselves when they are invoked through Maven without this wrapper.
  export PATHOME_VISIT_OPERATIONS_POSTGRES_TEST=true
  oe_log "preflight     verifying $jdbc_url against the approved Package 3A disposable policy"
  oe_guard_verify_package3_acceptance "$jdbc_url" || return 1
  oe_guard_package3_selftest || return 1
  oe_log "marker        $(oe_guard_psql "$PATHOME_OE_TEST_DB" "$PATHOME_OE_TEST_DB_APP_USER" -Atc \
    "select env_marker || ' recorded for ' || db_name from $OE_GUARD_SENTINEL_TABLE \
     where env_marker = '$OE_GUARD_SENTINEL_MARKER' and db_name = current_database()")"
  oe_log "discriminated a marker recorded for another database is not accepted: matches=$(oe_guard_psql \
    "$PATHOME_OE_TEST_DB" "$PATHOME_OE_TEST_DB_APP_USER" -Atc \
    "select count(*) from $OE_GUARD_SENTINEL_TABLE where env_marker = '$OE_GUARD_SENTINEL_MARKER' \
     and db_name = 'pathome_db'")"

  # The datasource is supplied and asserted here so the focused tests can never fall back to the
  # preserved development database: every inherited destination is neutralised first, and the
  # datasource is repinned to the designated disposable database by lib/env-isolation.sh.
  oe_log "focused Package 3A PostgreSQL tests against $jdbc_url"
  (
    oe_iso_apply_test_env "$jdbc_url"
    mvn -f "$OE_REPO_ROOT/backend/pom.xml" -o \
      -Dtest='OperatingTeamProvisioningServiceTest,DisposableIntegrationTargetGuardTest,DisposableIntegrationTargetLiveGuardTest,OperatingTeamProvisioningHttpPostgresTest' \
      -DfailIfNoTests=false test
  )
}

oe_p3_verify_browser_main() {
  local harness="$OE_REPO_ROOT/frontend-web/browser-verification/operations-governed-provisioning/run.mjs"
  [ -f "$harness" ] || oe_die "the governed provisioning browser harness is missing"
  [ -f "$OE_P3_IDENTITIES" ] || oe_die "run 'package3.sh provision' first: no ephemeral identities found"
  [ -d "$OE_REPO_ROOT/frontend-web/dist" ] || oe_die "frontend-web/dist is missing; run 'npm --prefix frontend-web run build'"
  (
    oe_data_read "$OE_P3_IDENTITIES" 'oe_alpha_EMAIL oe_alpha_PASSWORD oe_bravo_EMAIL oe_bravo_PASSWORD staff_nogrant_EMAIL staff_nogrant_PASSWORD city_admin_EMAIL city_admin_PASSWORD P3A_CITY_ID P3A_TEAM_ALPHA_ID P3A_TEAM_BRAVO_ID'
    export P3A_BACKEND_URL="http://127.0.0.1:$OE_BACKEND_PORT"
    node "$harness"
  )
}
