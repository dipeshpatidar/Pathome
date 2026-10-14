#!/usr/bin/env bash
# Package 3A guarded fixtures: governed Team creation and genuine OE provisioning.
#
# Everything here goes through the application's own APIs:
#   * POST /api/v1/auth/register
#   * POST /api/v1/staff/admin/users/{id}/activate
#   * POST /api/v1/staff/admin/users/{id}/grants
#   * POST /api/v1/staff/admin/cities/{cityId}/operating-teams        (Package 3A)
#
# operating_teams, staff_access_grants and operational_audit_events are never written, edited or
# revoked by hand, and re-running reuses what already exists instead of rewriting it.

OE_P3_RESPONSE=''
OE_P3_DIR=''
OE_P3_STATE=''
OE_P3_IDENTITIES=''

oe_p3_api() { # METHOD PATH TOKEN BODY -> prints HTTP status, body in OE_P3_RESPONSE
  local method="$1" path="$2" token="${3:-}" body="${4:-}"
  local args=(-sS -o "$OE_P3_RESPONSE" -w '%{http_code}' -X "$method" "$OE_API_BASE_URL$path")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  curl "${args[@]}"
}

oe_p3_sign_in() { # EMAIL PASSWORD -> prints "<userId> <token>"
  local status
  status="$(oe_p3_api POST /auth/login '' \
    "$(jq -nc --arg e "$1" --arg p "$2" '{email:$e, password:$p}')")"
  [ "$status" = '200' ] || return 1
  jq -r '"\(.userId) \(.token)"' < "$OE_P3_RESPONSE"
}

oe_p3_prepare() {
  OE_P3_DIR="$PATHOME_OE_TEST_RUN_DIR/package3"
  mkdir -p "$OE_P3_DIR"
  umask 077
  OE_P3_STATE="$OE_P3_DIR/state.json"
  OE_P3_IDENTITIES="$OE_P3_DIR/browser-identities.env"
  OE_P3_RESPONSE="$OE_P3_DIR/last-response.json"
  [ -f "$OE_P3_STATE" ] || printf '{"teams":{},"users":{}}\n' > "$OE_P3_STATE"
}

# Refuses to operate on anything that is not the designated disposable backend: the identity is
# proven by creating through the API and reading the same row back from the designated database.
oe_p3_require_disposable_backend() {
  local pair admin_token db_id api_id probe_city_id
  pair="$(oe_p3_sign_in "$OE_BOOTSTRAP_ADMIN_EMAIL" "$OE_BOOTSTRAP_ADMIN_PASSWORD")" || \
    oe_die "the disposable bootstrap Admin cannot sign in against $OE_API_BASE_URL; refusing to continue"
  admin_token="${pair#* }"
  probe_city_id="$(oe_p3_city_id)"
  [ -n "$probe_city_id" ] || oe_die "the designated database has no active $OE_PROVISION_CITY_CODE City"
  api_id="$(oe_p3_ensure_team "$admin_token" "$probe_city_id" 'p3a-identity-probe' 'P3A Identity Probe')"
  db_id="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
    "select id from operating_teams where city_id = $probe_city_id and code = 'p3a-identity-probe'")"
  if [ -z "$db_id" ] || [ "$db_id" != "$api_id" ]; then
    oe_die "the backend served Team id '$api_id' but the designated database holds '$db_id': refusing an unverified backend"
  fi
  oe_log "backend proven disposable: Team id $api_id exists in $PATHOME_OE_TEST_DB after API creation"
}

oe_p3_city_id() {
  oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
    "select id from supported_cities where code = '$OE_PROVISION_CITY_CODE' and active"
}

oe_p3_city_version() {
  oe_psql_app "$PATHOME_OE_TEST_DB" -Atc "select version from supported_cities where id = $1"
}

# Creates one active Team through the governed API, or reuses the existing one. Prints the Team id.
oe_p3_ensure_team() { # ADMIN_TOKEN CITY_ID CODE DISPLAY_NAME -> team id
  local token="$1" city_id="$2" code="$3" display_name="$4" status existing
  status="$(oe_p3_api POST "/staff/admin/cities/$city_id/operating-teams" "$token" \
    "$(jq -nc --arg c "$code" --arg n "$display_name" --argjson v "$(oe_p3_city_version "$city_id")" \
        '{code:$c, displayName:$n, expectedCityVersion:$v, reasonCode:"PACKAGE3_TEAM_ONBOARDING"}')")"
  case "$status" in
    201) jq -r '.id' < "$OE_P3_RESPONSE" ;;
    409)
      existing="$(oe_psql_app "$PATHOME_OE_TEST_DB" -Atc \
        "select id from operating_teams where city_id = $city_id and code = '$code'")"
      [ -n "$existing" ] || oe_die "Team '$code' conflicts but is not present in the designated database"
      printf '%s' "$existing"
      ;;
    *) oe_die "Team '$code' creation answered HTTP $status: $(cat "$OE_P3_RESPONSE")" ;;
  esac
}

oe_p3_ensure_user() { # EMAIL PASSWORD FULL_NAME -> user id
  local email="$1" password="$2" full_name="$3" status pair
  status="$(oe_p3_api POST /auth/register '' \
    "$(jq -nc --arg e "$email" --arg p "$password" --arg n "$full_name" \
        '{email:$e, password:$p, fullName:$n}')")"
  if [ "$status" = '200' ]; then
    jq -r '.userId' < "$OE_P3_RESPONSE"
    return 0
  fi
  pair="$(oe_p3_sign_in "$email" "$password")" || oe_die "identity $email exists but cannot sign in"
  printf '%s' "${pair%% *}"
}

oe_p3_ensure_staff_active() { # ADMIN_TOKEN USER_ID
  local token="$1" user_id="$2" status version
  status="$(oe_p3_api GET "/staff/admin/users/$user_id" "$token")"
  [ "$status" = '200' ] || oe_die "staff inspection for user $user_id answered HTTP $status"
  if [ "$(jq -r '.staffActive' < "$OE_P3_RESPONSE")" = 'true' ]; then
    return 0
  fi
  version="$(jq -r '.version // "null"' < "$OE_P3_RESPONSE")"
  status="$(oe_p3_api POST "/staff/admin/users/$user_id/activate" "$token" \
    "{\"expectedVersion\":$version,\"reasonCode\":\"PACKAGE3_STAFF_ACTIVATION\"}")"
  [ "$status" = '200' ] || oe_die "staff activation for user $user_id answered HTTP $status"
}

oe_p3_ensure_grant() { # ADMIN_TOKEN USER_ID CAPABILITY SCOPE_TYPE {CITY_ID|TEAM_ID}
  local token="$1" user_id="$2" capability="$3" scope_type="$4" scope_id="$5" status body existing
  status="$(oe_p3_api GET "/staff/admin/users/$user_id" "$token")"
  existing="$(jq -r --arg c "$capability" --arg s "$scope_type" \
    '[.grants[] | select(.capability==$c and .scopeType==$s and .revokedAt==null)] | length' < "$OE_P3_RESPONSE")"
  if [ "${existing:-0}" -ge 1 ]; then
    return 0
  fi
  if [ "$scope_type" = 'CITY' ]; then
    body="$(jq -nc --arg c "$capability" --argjson id "$scope_id" \
      '{capability:$c, scopeType:"CITY", cityId:$id, reasonCode:"PACKAGE3_OE_ONBOARDING"}')"
  else
    body="$(jq -nc --arg c "$capability" --argjson id "$scope_id" \
      '{capability:$c, scopeType:"TEAM", teamId:$id, reasonCode:"PACKAGE3_OE_ONBOARDING"}')"
  fi
  status="$(oe_p3_api POST "/staff/admin/users/$user_id/grants" "$token" "$body")"
  [ "$status" = '200' ] || oe_die "grant $capability@$scope_type for user $user_id answered HTTP $status: $(cat "$OE_P3_RESPONSE")"
}

oe_p3_provision_main() {
  oe_p3_prepare
  oe_p3_require_disposable_backend

  local pair admin_token city_id team_alpha team_bravo
  pair="$(oe_p3_sign_in "$OE_BOOTSTRAP_ADMIN_EMAIL" "$OE_BOOTSTRAP_ADMIN_PASSWORD")"
  admin_token="${pair#* }"
  city_id="$(oe_p3_city_id)"
  team_alpha="$(oe_p3_ensure_team "$admin_token" "$city_id" 'p3a-team-alpha' 'P3A Team Alpha')"
  team_bravo="$(oe_p3_ensure_team "$admin_token" "$city_id" 'p3a-team-bravo' 'P3A Team Bravo')"

  local entries=(
    "oe_alpha|codex.p3a.oe.alpha@pathome.test|P3A OE Alpha|OPS_COORDINATE|TEAM|$team_alpha"
    "oe_bravo|codex.p3a.oe.bravo@pathome.test|P3A OE Bravo|OPS_COORDINATE|TEAM|$team_bravo"
    "staff_nogrant|codex.p3a.nogrant@pathome.test|P3A Staff No Grant|||"
    "city_admin|codex.p3a.cityadmin@pathome.test|P3A City Admin|CITY_TEAM_ADMIN|CITY|$city_id"
  )
  local entry key email full_name capability scope_type scope_id user_id
  local state='{"teams":{},"users":{}}'
  : > "$OE_P3_IDENTITIES"
  for entry in "${entries[@]}"; do
    IFS='|' read -r key email full_name capability scope_type scope_id <<< "$entry"
    user_id="$(oe_p3_ensure_user "$email" "$OE_STAFF_PASSWORD" "$full_name")"
    oe_p3_ensure_staff_active "$admin_token" "$user_id"
    if [ -n "$capability" ]; then
      oe_p3_ensure_grant "$admin_token" "$user_id" "$capability" "$scope_type" "$scope_id"
    fi
    state="$(printf '%s' "$state" | jq -c --arg k "$key" --argjson v "$user_id" '.users[$k]=$v')"
    state="$(printf '%s' "$state" | jq -c --arg k "$key" --arg v "$email" '.users[$k + "_email"]=$v')"
    printf '%s_EMAIL=%s\n%s_PASSWORD=%s\n' "$key" "$email" "$key" "$OE_STAFF_PASSWORD" >> "$OE_P3_IDENTITIES"
    oe_log "provisioned $key (user $user_id${capability:+, $capability@$scope_type($scope_id)})"
  done
  state="$(printf '%s' "$state" | jq -c --argjson c "$city_id" '.cityId=$c')"
  state="$(printf '%s' "$state" | jq -c --argjson t "$team_alpha" '.teams.alpha=$t')"
  state="$(printf '%s' "$state" | jq -c --argjson t "$team_bravo" '.teams.bravo=$t')"
  state="$(printf '%s' "$state" | jq -c --arg v "$OE_STAFF_PASSWORD" '.staffPassword=$v')"
  printf '%s\n' "$state" > "$OE_P3_STATE"
  printf 'P3A_CITY_ID=%s\nP3A_TEAM_ALPHA_ID=%s\nP3A_TEAM_BRAVO_ID=%s\n' \
    "$city_id" "$team_alpha" "$team_bravo" >> "$OE_P3_IDENTITIES"
  chmod 600 "$OE_P3_IDENTITIES" "$OE_P3_STATE"
  oe_log "governed Teams ready: alpha=$team_alpha bravo=$team_bravo in city=$city_id"
  oe_log "ephemeral identity file written to $OE_P3_IDENTITIES (never printed, never committed)"
}
