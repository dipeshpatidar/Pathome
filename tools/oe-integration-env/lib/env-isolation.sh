#!/usr/bin/env bash
# Pathome OE integration environment - runtime configuration isolation.
#
# The disposable environment must never inherit a production destination from `.env.local`, from the
# operator's shell, or from any other implicit source. This file therefore:
#
#   1. NEVER sources `.env.local` (sourcing it executes arbitrary shell and imports production
#      datasource, storage, media and secret values). It only *reads key names* from it, to record
#      which destinations were neutralised, and it never prints a value.
#   2. Classifies every configuration key against an explicit destination register and unsets every
#      destination that is present in the process environment before the application is launched.
#   3. Repins the destinations this environment does own - the disposable datasource and a per-run
#      disposable JWT signing secret - from the designation file and the ephemeral run secrets only.
#   4. Fails closed for every destination it cannot safely substitute. Media upload is a real
#      external side effect (Cloudinary); it requires an explicitly approved disposable media account
#      and otherwise the launch is refused. The shipped production cloud name is refused outright.
#   5. Accepts additional configuration only from an explicit test overrides file whose keys are
#      validated one by one; an unknown key or any destination key in that file is refused.
#
# This file contains no database, network or media call of its own. It is sourced by run.sh,
# package3.sh and package4.sh; it can also be invoked directly for a static report:
#   env-isolation.sh report
#   env-isolation.sh selftest

OE_ISO_VERSION='1.0.0'
OE_ISO_SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
. "$(dirname "$OE_ISO_SELF")/runtime.sh"

# Launcher-level refusal code, shared with run.sh's own refusals.
OE_ISO_ERR_REFUSED=90

# --- destination register -------------------------------------------------------------------------
# Keys that name a database, an object store, a media CDN, a token signing secret or an external
# service. None of them may be inherited.
OE_ISO_DB_KEYS='SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD'
OE_ISO_TOKEN_KEYS='JWT_SECRET'
OE_ISO_MEDIA_KEYS='CLOUDINARY_CLOUD_NAME CLOUDINARY_API_KEY CLOUDINARY_API_SECRET'
# Keys that would silently change *which* configuration and behaviour the application loads. An
# inherited production profile would change storage, cache and media behaviour, so it is neutralised too.
OE_ISO_PROFILE_KEYS='SPRING_PROFILES_ACTIVE SPRING_CONFIG_LOCATION SPRING_CONFIG_ADDITIONAL_LOCATION
SPRING_CONFIG_IMPORT SPRING_CONFIG_NAME'
OE_ISO_STORAGE_KEYS='PATHOME_STAGING_REQUIRE_DURABLE PATHOME_STAGING_LOCAL_PATH
PATHOME_STAGING_S3_BUCKET PATHOME_STAGING_S3_REGION PATHOME_STAGING_S3_ENDPOINT
PATHOME_STAGING_S3_ACCESS_KEY PATHOME_STAGING_S3_SECRET_KEY PATHOME_STAGING_S3_PATH_STYLE
PATHOME_STAGING_S3_PATH_STYLE_ACCESS PATHOME_DRAFT_STAGING_S3_BUCKET PATHOME_DRAFT_STAGING_S3_REGION
PATHOME_DRAFT_STAGING_S3_ENDPOINT PATHOME_DRAFT_STAGING_S3_ACCESS_KEY
PATHOME_DRAFT_STAGING_S3_SECRET_KEY PATHOME_DRAFT_STAGING_S3_PATH_STYLE'
OE_ISO_SERVICE_KEY_PATTERN='^((AWS|S3|AZURE|GCP|GOOGLE|FIREBASE|FCM|MAIL|SMTP|SENDGRID|POSTMARK|TWILIO|MSG91|SMS|WHATSAPP|RAZORPAY|STRIPE|PAYTM|PHONEPE|CASHFREE|KAFKA|RABBIT|REDIS|ELASTIC|KINESIS|META|MAPTILER|MAPBOX|SENTRY|DATADOG|NEWRELIC)_)'

# The media provider shipped as the application default. It is the real account: refused outright.
OE_ISO_PROTECTED_MEDIA_NAMES='hbgzetjy'

# Additional configuration may only come from these explicit, key-by-key validated sources.
OE_ISO_OVERRIDE_ALLOW_PATTERN='^(PATHOME_VISIT_MIN_CONTACT_ATTEMPTS|PATHOME_VISIT_CONTACT_SPACING_MINUTES)$'

oe_iso_log() { printf '%s\n' "$*"; }

oe_iso_refuse() {
  printf 'REFUSED [%s] %s\n' "$OE_ISO_ERR_REFUSED" "$*" >&2
  exit "$OE_ISO_ERR_REFUSED"
}

# Prints the category of one configuration key. `other` means "not a destination".
oe_iso_classify_key() {
  local key="$1" candidate
  case "$key" in
    SPRING_APPLICATION_JSON|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|MAVEN_*|NODE_OPTIONS|BASH_ENV|ENV|PSQLRC|PG*|CURL_*|*PROXY|*proxy|APP_JWT_SECRET|SPRING_*)
      case "$key" in SPRING_DATASOURCE_URL|SPRING_DATASOURCE_USERNAME|SPRING_DATASOURCE_PASSWORD) printf 'database' ;; *) printf 'application-profile' ;; esac
      return 0 ;;
  esac
  case " $OE_ISO_DB_KEYS " in *" $key "*) printf 'database'; return 0 ;; esac
  case " $OE_ISO_TOKEN_KEYS " in *" $key "*) printf 'token-signing'; return 0 ;; esac
  case " $OE_ISO_MEDIA_KEYS " in *" $key "*) printf 'media-cdn'; return 0 ;; esac
  for candidate in $OE_ISO_PROFILE_KEYS; do
    [ "$key" = "$candidate" ] && { printf 'application-profile'; return 0; }
  done
  for candidate in $OE_ISO_STORAGE_KEYS; do
    [ "$key" = "$candidate" ] && { printf 'object-storage'; return 0; }
  done
  if printf '%s' "$key" | grep -Eq "$OE_ISO_SERVICE_KEY_PATTERN"; then
    printf 'external-service'
    return 0
  fi
  printf 'other'
}

oe_iso_is_destination() {
  [ "$(oe_iso_classify_key "$1")" != 'other' ]
}

# Names only. Values are never read, printed, copied or logged.
oe_iso_key_names_in_file() {
  local file="$1"
  [ -f "$file" ] || return 0
  sed -n 's/^[[:space:]]*\([A-Za-z_][A-Za-z0-9_]*\)[[:space:]]*=.*/\1/p' "$file" | sort -u
}

# Redacted record of what `.env.local` would have contributed, and what was therefore neutralised.
oe_iso_report_environment_file() { # [file] [label]
  local file="${1:-}" label="${2:-env.local}" key category neutralised=0 present=0
  if [ -z "$file" ] || [ ! -f "$file" ]; then
    oe_iso_log "source     $label: absent"
    return 0
  fi
  oe_iso_log "source     $label: present at $file (read for key names only; never sourced, never printed)"
  while IFS= read -r key; do
    [ -n "$key" ] || continue
    present=$((present + 1))
    category="$(oe_iso_classify_key "$key")"
    if [ "$category" != 'other' ]; then
      neutralised=$((neutralised + 1))
      oe_iso_log "neutralised $label: $key ($category) -> not inherited"
    fi
  done <<< "$(oe_iso_key_names_in_file "$file")"
  oe_iso_log "source     $label: keys=$present destinations-neutralised=$neutralised"
}

# Removes every destination key from the process environment. Called in the launching subshell, so it
# affects only that subshell and everything it spawns.
oe_iso_neutralise_environment() {
  local key category removed=0
  while IFS= read -r key; do
    [ -n "$key" ] || continue
    category="$(oe_iso_classify_key "$key")"
    case "$category" in
      database|token-signing|media-cdn|application-profile|object-storage|external-service)
        unset "$key"
        removed=$((removed + 1))
        ;;
    esac
  done <<< "$(env | sed -n 's/^\([A-Za-z_][A-Za-z0-9_]*\)=.*/\1/p' | sort -u)"
  OE_ISO_NEUTRALISED_COUNT="$removed"
  export OE_ISO_NEUTRALISED_COUNT
  return 0
}

# Imports an explicit test overrides file: `KEY=VALUE` lines only, never sourced. Every key must be on
# the allow pattern and must not be a destination; anything else is refused.
oe_iso_import_overrides() { # <file>
  local file="$1" line key value imported=0
  [ -f "$file" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      ''|'#'*) continue ;;
    esac
    case "$line" in
      *=*) ;;
      *) oe_iso_refuse "malformed line in overrides file $file (expected KEY=VALUE): refusing." ;;
    esac
    key="${line%%=*}"
    value="${line#*=}"
    case "$key" in
      *[!A-Za-z0-9_]*|'') oe_iso_refuse "invalid key '$key' in overrides file $file." ;;
    esac
    if oe_iso_is_destination "$key"; then
      oe_iso_refuse "overrides file $file sets the destination '$key' ($(oe_iso_classify_key "$key")). Test overrides may only tune non-destination behaviour."
    fi
    if ! printf '%s' "$key" | grep -Eq "$OE_ISO_OVERRIDE_ALLOW_PATTERN"; then
      oe_iso_refuse "overrides file $file sets '$key', which is not on the approved test-override list. Nothing is imported implicitly."
    fi
    export "$key=$value"
    imported=$((imported + 1))
  done < "$file"
  oe_iso_log "overrides  $file: imported=$imported (destinations are refused by name)"
}

# Pins the destinations this environment owns. Requires the caller to have loaded the designation and
# the ephemeral run secrets.
oe_iso_pin_disposable_destinations() { # <jdbc-url>
  local jdbc_url="${1:?jdbc url required}"

  [ -n "${PATHOME_OE_TEST_DB_APP_USER:-}" ] || \
    oe_iso_refuse "no disposable application user is designated (PATHOME_OE_TEST_DB_APP_USER is empty)."
  [ -n "${OE_JWT_SECRET:-}" ] || \
    oe_iso_refuse "the disposable run holds no signing secret at \$PATHOME_OE_TEST_RUN_DIR/secrets.env. Run 'run.sh backend', which generates one; a launch never reuses another environment's secret."

  export SPRING_DATASOURCE_URL="$jdbc_url"
  export SPRING_DATASOURCE_USERNAME="$PATHOME_OE_TEST_DB_APP_USER"
  export SPRING_DATASOURCE_PASSWORD="${PATHOME_OE_TEST_DB_PASSWORD:-}"

  # The disposable run signs its own tokens with its own secret; the development/execution secret is
  # never reused, so a token minted here cannot be honoured anywhere else.
  export JWT_SECRET="$OE_JWT_SECRET"
  export APP_JWT_SECRET="$OE_JWT_SECRET"
  export SPRING_DATASOURCE_HIKARI_JDBC_URL="$jdbc_url"
  export SPRING_FLYWAY_URL="$jdbc_url"
  export SPRING_FLYWAY_USER="$PATHOME_OE_TEST_DB_APP_USER"
  export SPRING_FLYWAY_PASSWORD="${PATHOME_OE_TEST_DB_PASSWORD:-}"
  export SPRING_CONFIG_LOCATION='classpath:/application.yml'
  export SPRING_PROFILES_ACTIVE='oe-disposable'
  export SPRING_PROFILES_INCLUDE=''
  export PATHOME_STAGING_LOCAL_PATH="$PATHOME_OE_TEST_RUN_DIR/media-staging"
  export PATHOME_STAGING_S3_BUCKET='' PATHOME_DRAFT_STAGING_S3_BUCKET=''
}

# Media is a real external side effect. Approval plus a disposable account are both required, and the
# shipped production cloud name is refused.
oe_iso_pin_media_destination() {
  local mode="${PATHOME_OE_TEST_MEDIA_MODE:-fail-closed}" name

  if [ "$mode" != 'disposable-account' ]; then
    oe_iso_refuse "media destination is not approved (PATHOME_OE_TEST_MEDIA_MODE=$mode). The application cannot start without a media provider, and the only provider configured in this repository is a real external Cloudinary account. A run therefore requires an approved disposable media account: set PATHOME_OE_TEST_MEDIA_MODE=disposable-account, PATHOME_OE_TEST_ALLOW_EXTERNAL_MEDIA_UPLOAD=true and OE_TEST_CLOUDINARY_CLOUD_NAME / OE_TEST_CLOUDINARY_API_KEY / OE_TEST_CLOUDINARY_API_SECRET to that throwaway account."
  fi

  if [ "${PATHOME_OE_TEST_ALLOW_EXTERNAL_MEDIA_UPLOAD:-}" != 'true' ]; then
    oe_iso_refuse "media mode 'disposable-account' still requires the explicit opt-in PATHOME_OE_TEST_ALLOW_EXTERNAL_MEDIA_UPLOAD=true."
  fi

  for name in OE_TEST_CLOUDINARY_CLOUD_NAME OE_TEST_CLOUDINARY_API_KEY OE_TEST_CLOUDINARY_API_SECRET; do
    eval "local value=\"\${$name:-}\""
    [ -n "$value" ] || oe_iso_refuse "media mode 'disposable-account' requires $name, which is empty."
  done
  [[ "$OE_TEST_CLOUDINARY_CLOUD_NAME" =~ ^[a-z0-9_-]+$ ]] || oe_iso_refuse 'media cloud must be a canonical lowercase account name'

  case " $OE_ISO_PROTECTED_MEDIA_NAMES " in
    *" $OE_TEST_CLOUDINARY_CLOUD_NAME "*)
      oe_iso_refuse "cloud name '$OE_TEST_CLOUDINARY_CLOUD_NAME' is the account shipped in application.yml and is never used by this environment."
      ;;
  esac
  case "$OE_TEST_CLOUDINARY_CLOUD_NAME" in
    *prod*|*live*|*stage*)
      oe_iso_refuse "cloud name '$OE_TEST_CLOUDINARY_CLOUD_NAME' names a production-like destination."
      ;;
  esac

  export CLOUDINARY_CLOUD_NAME="$OE_TEST_CLOUDINARY_CLOUD_NAME"
  export CLOUDINARY_API_KEY="$OE_TEST_CLOUDINARY_API_KEY"
  export CLOUDINARY_API_SECRET="$OE_TEST_CLOUDINARY_API_SECRET"
  oe_iso_log "media      disposable media account '$OE_TEST_CLOUDINARY_CLOUD_NAME' (explicit opt-in)"
}

# Full isolation for a Spring Boot launch (run.sh backend).
oe_iso_apply_backend_env() { # <jdbc-url>
  local jdbc_url="${1:?jdbc url required}" env_file="${2:-${OE_REPO_ROOT:-}/.env.local}"
  oe_iso_report_environment_file "$env_file" 'env.local'
  oe_iso_neutralise_environment
  oe_iso_import_overrides "${PATHOME_OE_TEST_OVERRIDES_FILE:-$PATHOME_OE_TEST_RUN_DIR/test-overrides.env}"
  oe_iso_pin_disposable_destinations "$jdbc_url"
  oe_iso_pin_media_destination
  oe_iso_log "isolation  neutralised=$OE_ISO_NEUTRALISED_COUNT inherited destinations; datasource repinned to the designation"
}

# Full isolation for a Maven test run: tests build the Spring context themselves, set their own JWT and
# media fixtures through test properties, and must still be unable to inherit a real destination.
oe_iso_apply_test_env() { # <jdbc-url>
  local jdbc_url="${1:?jdbc url required}" env_file="${2:-${OE_REPO_ROOT:-}/.env.local}"
  oe_iso_report_environment_file "$env_file" 'env.local'
  oe_iso_neutralise_environment
  oe_iso_import_overrides "${PATHOME_OE_TEST_OVERRIDES_FILE:-$PATHOME_OE_TEST_RUN_DIR/test-overrides.env}"
  # A Spring test context must not fall back to the shipped real Cloudinary cloud name either.
  # Its test properties may supply inert credentials, but the destination still requires approval.
  oe_runtime_load_secrets
  oe_iso_pin_media_destination
  export JWT_SECRET="$OE_JWT_SECRET" APP_JWT_SECRET="$OE_JWT_SECRET"
  unset APP_BOOTSTRAP_ADMIN_EMAIL APP_BOOTSTRAP_ADMIN_PASSWORD APP_BOOTSTRAP_OPERATOR_REFERENCE
  export APP_FIRST_ADMIN_PROVISIONING_ENABLED=false
  export SERVER_ADDRESS=127.0.0.1 SERVER_PORT=0
  [ -n "${PATHOME_OE_TEST_DB_APP_USER:-}" ] || \
    oe_iso_refuse "no disposable application user is designated (PATHOME_OE_TEST_DB_APP_USER is empty)."
  export SPRING_DATASOURCE_URL="$jdbc_url"
  export SPRING_DATASOURCE_USERNAME="$PATHOME_OE_TEST_DB_APP_USER"
  export SPRING_DATASOURCE_PASSWORD="${PATHOME_OE_TEST_DB_PASSWORD:-}"
  export SPRING_JPA_HIBERNATE_DDL_AUTO=none
  export SPRING_DATASOURCE_HIKARI_JDBC_URL="$jdbc_url"
  export SPRING_FLYWAY_URL="$jdbc_url" SPRING_FLYWAY_USER="$PATHOME_OE_TEST_DB_APP_USER"
  export SPRING_FLYWAY_PASSWORD="${PATHOME_OE_TEST_DB_PASSWORD:-}"
  export SPRING_CONFIG_LOCATION='classpath:/application.yml'
  export SPRING_PROFILES_ACTIVE='oe-disposable' SPRING_PROFILES_INCLUDE=''
  export PATHOME_STAGING_LOCAL_PATH="$PATHOME_OE_TEST_RUN_DIR/media-staging"
  export PATHOME_STAGING_S3_BUCKET='' PATHOME_DRAFT_STAGING_S3_BUCKET=''
  export PATHOME_OE_TEST_DB
  export PATHOME_VISIT_OPERATIONS_POSTGRES_TEST=true
  oe_iso_log "isolation  neutralised=$OE_ISO_NEUTRALISED_COUNT inherited destinations; datasource repinned to the designation"
}

# Static report: the register and its disposition. No server, database or network access.
oe_iso_report() {
  oe_iso_log "isolation_version=$OE_ISO_VERSION"
  oe_iso_log ''
  oe_iso_log 'destination register (never inherited):'
  oe_iso_log "  database           $OE_ISO_DB_KEYS   -> repinned to the designated disposable database"
  oe_iso_log "  token-signing      $OE_ISO_TOKEN_KEYS   -> replaced by a per-run disposable secret"
  oe_iso_log "  media-cdn          $OE_ISO_MEDIA_KEYS   -> approved disposable account only; refused otherwise"
  oe_iso_log "  application-profile $OE_ISO_PROFILE_KEYS -> unset (the application's own non-production default is used)"
  oe_iso_log '  object-storage     PATHOME_STAGING_S3_* / PATHOME_DRAFT_STAGING_S3_* -> unset (local staging fallback)'
  oe_iso_log "  external-service   keys matching $OE_ISO_SERVICE_KEY_PATTERN -> unset"
  oe_iso_log "  protected media    $OE_ISO_PROTECTED_MEDIA_NAMES (application.yml default) -> refused"
  oe_iso_log ''
  oe_iso_log 'explicit sources only:'
  oe_iso_log "  designation        \$PATHOME_OE_TEST_ENV_FILE or tools/oe-integration-env/oe-test.env"
  oe_iso_log "  ephemeral secrets  \$PATHOME_OE_TEST_RUN_DIR/secrets.env (generated, mode 600)"
  oe_iso_log "  test overrides     \${PATHOME_OE_TEST_OVERRIDES_FILE:-\$PATHOME_OE_TEST_RUN_DIR/test-overrides.env}"
  oe_iso_log "                     allowed keys: $OE_ISO_OVERRIDE_ALLOW_PATTERN"
  oe_iso_log '  nothing else is imported; .env.local is never sourced'
  oe_iso_log ''
  oe_iso_log "media mode (default fail-closed) = ${PATHOME_OE_TEST_MEDIA_MODE:-fail-closed}"
}

# Static, hermetic self test of the isolation rules. Never connects to anything.
oe_iso_selftest() {
  local failures=0 actual

  expect_iso_refusal() { # description expected_exit assignments...
    local description="$1" expected="$2"
    shift 2
    actual="$(env -u PATHOME_OE_TEST_MEDIA_MODE -u PATHOME_OE_TEST_ALLOW_EXTERNAL_MEDIA_UPLOAD \
        -u OE_TEST_CLOUDINARY_CLOUD_NAME -u OE_TEST_CLOUDINARY_API_KEY -u OE_TEST_CLOUDINARY_API_SECRET \
        -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD \
        -u JWT_SECRET -u CLOUDINARY_API_KEY -u CLOUDINARY_API_SECRET -u CLOUDINARY_CLOUD_NAME \
        "$@" bash -c "source '$OE_ISO_SELF'; oe_iso_pin_media_destination" >/dev/null 2>&1; printf '%s' "$?")"
    if [ "$actual" = "$expected" ]; then
      printf 'PASS  %s -> exit %s\n' "$description" "$actual"
    else
      printf 'FAIL  %s -> expected %s, saw %s\n' "$description" "$expected" "$actual"
      failures=$((failures + 1))
    fi
  }

  expect_iso_accept() { # description assignments...
    local description="$1"
    shift
    actual="$(env -u PATHOME_OE_TEST_MEDIA_MODE -u PATHOME_OE_TEST_ALLOW_EXTERNAL_MEDIA_UPLOAD \
        -u OE_TEST_CLOUDINARY_CLOUD_NAME -u OE_TEST_CLOUDINARY_API_KEY -u OE_TEST_CLOUDINARY_API_SECRET \
        "$@" bash -c "source '$OE_ISO_SELF'; oe_iso_pin_media_destination" >/dev/null 2>&1; printf '%s' "$?")"
    if [ "$actual" = '0' ]; then
      printf 'PASS  %s -> accepted\n' "$description"
    else
      printf 'FAIL  %s -> expected acceptance, saw exit %s\n' "$description" "$actual"
      failures=$((failures + 1))
    fi
  }

  expect_classification() { # description expected key
    actual="$(oe_iso_classify_key "$3")"
    if [ "$actual" = "$2" ]; then
      printf 'PASS  %s -> %s\n' "$1" "$actual"
    else
      printf 'FAIL  %s -> expected %s, saw %s\n' "$1" "$2" "$actual"
      failures=$((failures + 1))
    fi
  }

  expect_classification 'the datasource URL is a destination' 'database' 'SPRING_DATASOURCE_URL'
  expect_classification 'the JWT signing secret is a destination' 'token-signing' 'JWT_SECRET'
  expect_classification 'Cloudinary credentials are a destination' 'media-cdn' 'CLOUDINARY_API_KEY'
  expect_classification 'the staging bucket is a destination' 'object-storage' 'PATHOME_STAGING_S3_BUCKET'
  expect_classification 'draft staging credentials are a destination' 'object-storage' 'PATHOME_DRAFT_STAGING_S3_SECRET_KEY'
  expect_classification 'an SMTP host is a destination' 'external-service' 'SMTP_HOST'
  expect_classification 'an SMS provider key is a destination' 'external-service' 'MSG91_AUTH_KEY'
  expect_classification 'a map provider key is a destination' 'external-service' 'MAPTILER_API_KEY'
  expect_classification 'an inherited activation profile is neutralised' 'application-profile' 'SPRING_PROFILES_ACTIVE'
  expect_classification 'a visit threshold is not a destination' 'other' 'PATHOME_VISIT_MIN_CONTACT_ATTEMPTS'
  expect_classification 'the disposable password is not a destination' 'other' 'PATHOME_OE_TEST_DB_PASSWORD'

  expect_iso_refusal 'refuses to launch without an approved media destination' "$OE_ISO_ERR_REFUSED"
  expect_iso_refusal 'refuses an opt-in without a disposable media account' "$OE_ISO_ERR_REFUSED" \
    PATHOME_OE_TEST_MEDIA_MODE='disposable-account' PATHOME_OE_TEST_ALLOW_EXTERNAL_MEDIA_UPLOAD='true'
  expect_iso_refusal 'refuses the shipped production cloud name' "$OE_ISO_ERR_REFUSED" \
    PATHOME_OE_TEST_MEDIA_MODE='disposable-account' PATHOME_OE_TEST_ALLOW_EXTERNAL_MEDIA_UPLOAD='true' \
    OE_TEST_CLOUDINARY_CLOUD_NAME="$OE_ISO_PROTECTED_MEDIA_NAMES" \
    OE_TEST_CLOUDINARY_API_KEY='k' OE_TEST_CLOUDINARY_API_SECRET='s'
  expect_iso_refusal 'refuses a production-like cloud name' "$OE_ISO_ERR_REFUSED" \
    PATHOME_OE_TEST_MEDIA_MODE='disposable-account' PATHOME_OE_TEST_ALLOW_EXTERNAL_MEDIA_UPLOAD='true' \
    OE_TEST_CLOUDINARY_CLOUD_NAME='pathome-prod-media' \
    OE_TEST_CLOUDINARY_API_KEY='k' OE_TEST_CLOUDINARY_API_SECRET='s'
  expect_iso_accept 'accepts an explicitly approved disposable media account' \
    PATHOME_OE_TEST_MEDIA_MODE='disposable-account' PATHOME_OE_TEST_ALLOW_EXTERNAL_MEDIA_UPLOAD='true' \
    OE_TEST_CLOUDINARY_CLOUD_NAME='pathome-oe-test-media' \
    OE_TEST_CLOUDINARY_API_KEY='k' OE_TEST_CLOUDINARY_API_SECRET='s'

  # The neutralisation must actually clear a destination that is present in the environment.
  actual="$(env SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/pathome_db' \
    CLOUDINARY_API_KEY='real' SMTP_HOST='smtp.example.com' SPRING_PROFILES_ACTIVE='prod' \
    bash -c "source '$OE_ISO_SELF'; oe_iso_neutralise_environment >/dev/null; \
      printf '%s %s %s %s' \"\${SPRING_DATASOURCE_URL:-unset}\" \"\${CLOUDINARY_API_KEY:-unset}\" \"\${SMTP_HOST:-unset}\" \"\${SPRING_PROFILES_ACTIVE:-unset}\"")"
  if [ "$actual" = 'unset unset unset unset' ]; then
    printf 'PASS  %s -> %s\n' 'neutralisation clears inherited destinations' "$actual"
  else
    printf 'FAIL  %s -> %s\n' 'neutralisation clears inherited destinations' "$actual"
    failures=$((failures + 1))
  fi

  # A destination in an overrides file must be refused, and a tuning key must be imported.
  local tmp_dir tmp_file
  tmp_dir="$(mktemp -d)"
  printf 'SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/pathome_db\n' > "$tmp_dir/destination.env"
  printf 'PATHOME_VISIT_MIN_CONTACT_ATTEMPTS=2\n' > "$tmp_dir/tuning.env"
  actual="$(env -u SPRING_DATASOURCE_URL bash -c "source '$OE_ISO_SELF'; oe_iso_import_overrides '$tmp_dir/destination.env'" >/dev/null 2>&1; printf '%s' "$?")"
  if [ "$actual" = "$OE_ISO_ERR_REFUSED" ]; then
    printf 'PASS  %s -> exit %s\n' 'an overrides file may not set a destination' "$actual"
  else
    printf 'FAIL  %s -> expected %s, saw %s\n' 'an overrides file may not set a destination' "$OE_ISO_ERR_REFUSED" "$actual"
    failures=$((failures + 1))
  fi
  actual="$(env bash -c "source '$OE_ISO_SELF'; oe_iso_import_overrides '$tmp_dir/tuning.env' >/dev/null; printf '%s' \"\$PATHOME_VISIT_MIN_CONTACT_ATTEMPTS\"" 2>/dev/null)"
  if [ "$actual" = '2' ]; then
    printf 'PASS  %s -> %s\n' 'an approved tuning override is imported' "$actual"
  else
    printf 'FAIL  %s -> %s\n' 'an approved tuning override is imported' "$actual"
    failures=$((failures + 1))
  fi
  rm -rf "$tmp_dir"

  return "$failures"
}

if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
  case "${1:-}" in
    report)   oe_iso_report ;;
    selftest) oe_iso_selftest ;;
    *)        printf 'usage: %s {report | selftest}\n' "$0" >&2; exit 2 ;;
  esac
fi
