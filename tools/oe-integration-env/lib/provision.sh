#!/usr/bin/env bash
# Pathome OE integration environment - genuine disposable staff provisioning.
#
# Identities are created only through supported mechanisms:
#   * the first-Admin bootstrap that runs at application start (APP_BOOTSTRAP_* -> DataInitializer
#     -> StaffAdminBootstrapService, provisioning_source INITIAL_BOOTSTRAP)
#   * POST /api/v1/auth/register                      (public registration, role ROLE_TENANT)
#   * POST /api/v1/staff/admin/users/{id}/activate
#   * POST /api/v1/staff/admin/users/{id}/grants      (provisioning_source ADMIN_API)
#
# staff_access_grants is never written, edited or deleted by hand, and no audit trigger is
# bypassed. Re-running is idempotent: existing identities are discovered and reused, never
# recreated, so grant and audit history accumulates instead of being reset.

OE_PROVISION_RESPONSE=''
OE_PROVISION_CITY_CODE='indore'

oe_provision_api() { # METHOD PATH TOKEN BODY -> prints HTTP status, body in OE_PROVISION_RESPONSE
  local method="$1" path="$2" token="${3:-}" body="${4:-}"
  local args=(-sS -o "$OE_PROVISION_RESPONSE" -w '%{http_code}' -X "$method" "$OE_API_BASE_URL$path")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  curl "${args[@]}"
}

oe_provision_sign_in() { # EMAIL PASSWORD -> prints "<userId> <token>", fails when rejected
  local status
  status="$(oe_provision_api POST /auth/login '' \
    "$(jq -nc --arg e "$1" --arg p "$2" '{email:$e, password:$p}')")"
  [ "$status" = '200' ] || return 1
  jq -r '"\(.userId) \(.token)"' < "$OE_PROVISION_RESPONSE"
}

oe_provision_main() {
  local failures=0
  local evidence_dir="$PATHOME_OE_TEST_RUN_DIR/evidence"
  local evidence="$evidence_dir/provisioning.json"
  local resp="$PATHOME_OE_TEST_RUN_DIR/provision-response.json"
  OE_PROVISION_RESPONSE="$resp"

  check() { # LABEL EXPECTED ACTUAL
    if [ "$2" = "$3" ]; then
      printf 'PASS  %-78s %s\n' "$1" "$3"
    else
      printf 'FAIL  %-78s expected=%s actual=%s\n' "$1" "$2" "$3"
      failures=$((failures + 1))
    fi
  }

  # ---- 0. the application must be the disposable one --------------------------------
  local pair admin_token status
  if ! pair="$(oe_provision_sign_in "$OE_BOOTSTRAP_ADMIN_EMAIL" "$OE_BOOTSTRAP_ADMIN_PASSWORD")"; then
    printf 'FAIL  bootstrap Admin could not sign in against %s\n' "$OE_API_BASE_URL" >&2
    cat "$resp" >&2 || true
    return 1
  fi
  admin_token="${pair#* }"
  check 'bootstrap Admin signs in (first-Admin bootstrap on the disposable database)' 'ok' 'ok'

  status="$(oe_provision_api GET /staff/me/capabilities "$admin_token")"
  check 'bootstrap Admin capabilities endpoint answers' '200' "$status"
  check 'bootstrap Admin staff profile is active' 'true' "$(jq -r '.staffActive' < "$resp")"
  check 'bootstrap Admin holds an effective STAFF_ADMIN@GLOBAL grant' '1' \
    "$(jq -r '[.capabilities[] | select(.capability=="STAFF_ADMIN" and .scopeType=="GLOBAL")] | length' < "$resp")"

  # ---- 1. the City scope used here is migration-seeded, not a hand-written fixture ----
  local city_id
  city_id="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
    "select id from supported_cities where code = '$OE_PROVISION_CITY_CODE' and active")"
  check "City scope '$OE_PROVISION_CITY_CODE' resolves from the migration-seeded registry" 'true' \
    "$(printf '%s' "$city_id" | grep -Eq '^[0-9]+$' && echo true || echo false)"

  # ---- 2. register or reuse the disposable identities --------------------------------
  local entries=(
    "cityadmin|codex.oe.env.cityadmin@pathome.test|Codex OE Env City Admin|CITY_TEAM_ADMIN"
    "intake|codex.oe.env.intake@pathome.test|Codex OE Env Intake|OPS_INTAKE"
    "nogrant|codex.oe.env.nogrant@pathome.test|Codex OE Env No Grant|"
  )
  local entry key email full_name capability user_id register_status token
  local profile_version staff_active role_name existing
  local state_json='{}'

  for entry in "${entries[@]}"; do
    IFS='|' read -r key email full_name capability <<< "$entry"

    register_status="$(oe_provision_api POST /auth/register '' \
      "$(jq -nc --arg e "$email" --arg p "$OE_STAFF_PASSWORD" --arg n "$full_name" \
          '{email:$e, password:$p, fullName:$n}')")"
    if [ "$register_status" = '200' ]; then
      user_id="$(jq -r '.userId' < "$resp")"
      role_name="$(jq -r '.role' < "$resp")"
      check "register $email through the public registration endpoint" '200' '200'
      check "public registration keeps the tenant-only role for $key" 'ROLE_TENANT' "$role_name"
    else
      check "register $email answers EMAIL_ALREADY_REGISTERED on a re-run" 'EMAIL_ALREADY_REGISTERED' \
        "$(jq -r '.error // "unexpected"' < "$resp")"
      if ! pair="$(oe_provision_sign_in "$email" "$OE_STAFF_PASSWORD")"; then
        printf 'FAIL  existing identity %s cannot sign in\n' "$email" >&2
        failures=$((failures + 1))
        continue
      fi
      user_id="${pair%% *}"
    fi
    check "identity $key resolves to a stable numeric User id" 'true' \
      "$(printf '%s' "$user_id" | grep -Eq '^[0-9]+$' && echo true || echo false)"

    # ---- 3. staff activation through Staff Administration ----------------------------
    status="$(oe_provision_api GET "/staff/admin/users/$user_id" "$admin_token")"
    staff_active="$(jq -r '.staffActive' < "$resp")"
    profile_version="$(jq -r '.version // "null"' < "$resp")"
    if [ "$staff_active" = 'true' ]; then
      check "activate $key - already active, no re-provisioning needed (re-run)" 'true' 'true'
    else
      status="$(oe_provision_api POST "/staff/admin/users/$user_id/activate" "$admin_token" \
        "{\"expectedVersion\":$profile_version,\"reasonCode\":\"STAFF_ONBOARDING\"}")"
      check "activate $key through POST /staff/admin/users/{id}/activate" '200' "$status"
    fi

    # ---- 4. grant through Staff Administration ---------------------------------------
    if [ -n "$capability" ]; then
      status="$(oe_provision_api GET "/staff/admin/users/$user_id" "$admin_token")"
      existing="$(jq -r --arg c "$capability" \
        '[.grants[] | select(.capability==$c and .revokedAt==null)] | length' < "$resp")"
      if [ "$existing" -ge 1 ]; then
        check "grant $capability@CITY($OE_PROVISION_CITY_CODE) to $key - already present, not rewritten (re-run)" '1' \
          "$(jq -r --arg c "$capability" '[.grants[] | select(.capability==$c and .provisioningSource=="ADMIN_API" and .revokedAt==null)] | length' < "$resp")"
      else
        status="$(oe_provision_api POST "/staff/admin/users/$user_id/grants" "$admin_token" \
          "$(jq -nc --arg c "$capability" --argjson city "$city_id" \
              '{capability:$c, scopeType:"CITY", cityId:$city, reasonCode:"OE_ENV_ONBOARDING"}')")"
        check "grant $capability@CITY($OE_PROVISION_CITY_CODE) to $key via the Admin API" '200' "$status"
        check "grant for $key is recorded with provisioning_source ADMIN_API" 'ADMIN_API' \
          "$(jq -r '.provisioningSource // "missing"' < "$resp")"
      fi
    fi

    # ---- 5. effective capability state is readable by the identity itself -------------
    token="$(oe_provision_api POST /auth/login '' \
      "$(jq -nc --arg e "$email" --arg p "$OE_STAFF_PASSWORD" '{email:$e, password:$p}')" >/dev/null; \
      jq -r '.token // empty' < "$resp")"
    if [ -z "$token" ]; then
      printf 'FAIL  %s cannot sign in with its disposable credentials\n' "$email" >&2
      failures=$((failures + 1))
      continue
    fi
    status="$(oe_provision_api GET /staff/me/capabilities "$token")"
    check "$key reads its own staff profile (staffActive)" 'true' "$(jq -r '.staffActive' < "$resp")"
    if [ -n "$capability" ]; then
      check "$key capabilities expose $capability@CITY" '1' \
        "$(jq -r --arg c "$capability" \
          '[.capabilities[] | select(.capability==$c and .scopeType=="CITY")] | length' < "$resp")"
    else
      check "$key is active staff carrying no grant at all" '0' "$(jq -r '.capabilities | length' < "$resp")"
    fi
    state_json="$(jq -c --arg k "$key" --argjson id "$user_id" --arg e "$email" \
      --arg c "$capability" --argjson city "$city_id" \
      '. + {($k): {userId:$id, email:$e, capability:$c, cityId:$city}}' <<< "$state_json")"
  done

  # ---- 6. authority was created only by supported mechanisms -------------------------
  check 'every staff grant carries a supported provisioning_source' '0' \
    "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
      "select count(*) from staff_access_grants where provisioning_source not in ('ADMIN_API','INITIAL_BOOTSTRAP','OPERATOR_RECOVERY')")"
  check 'no Team-scoped grant exists (Team provisioning is outside this package)' '0' \
    "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
      "select count(*) from staff_access_grants where scope_type = 'TEAM'")"
  check 'the bootstrap Admin grant was recorded against the deployment operator reference' '1' \
    "$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
      "select count(*) from staff_access_grants where provisioning_source = 'INITIAL_BOOTSTRAP'")"

  jq -n --arg database "$PATHOME_OE_TEST_DB" --arg runDir "$PATHOME_OE_TEST_RUN_DIR" \
        --argjson identities "$state_json" --argjson failures "$failures" \
        '{database:$database, runDir:$runDir, identities:$identities, failures:$failures}' > "$evidence.tmp"
  mv "$evidence.tmp" "$evidence"

  printf '\nPROVISIONING SUMMARY: failures=%s  evidence=%s\n' "$failures" "$evidence"
  [ "$failures" -eq 0 ]
}
