#!/usr/bin/env bash
# Shared harness boundary. Configuration files are data, never shell programs.
OE_RUNTIME_PATH='/opt/homebrew/opt/openjdk@17/bin:/usr/local/apache-maven/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin'
OE_RUNTIME_HOME="${OE_OPERATOR_HOME:-${HOME:?HOME is required to locate the existing offline Maven cache}}"
export OE_OPERATOR_HOME="$OE_RUNTIME_HOME"
OE_DATA_DESIGNATION='PATHOME_OE_TEST_ALLOW_DISPOSABLE PATHOME_OE_TEST_DB PATHOME_OE_TEST_DB_HOST PATHOME_OE_TEST_DB_PORT PATHOME_OE_TEST_DB_ADMIN_USER PATHOME_OE_TEST_DB_APP_USER PATHOME_OE_TEST_RUN_DIR'
OE_DATA_SECRETS='OE_RUN_ID OE_BOOTSTRAP_ADMIN_EMAIL OE_BOOTSTRAP_ADMIN_PASSWORD OE_BOOTSTRAP_OPERATOR_REFERENCE OE_STAFF_PASSWORD OE_JWT_SECRET PATHOME_OE_TEST_MEDIA_MODE PATHOME_OE_TEST_ALLOW_EXTERNAL_MEDIA_UPLOAD OE_TEST_CLOUDINARY_CLOUD_NAME OE_TEST_CLOUDINARY_API_KEY OE_TEST_CLOUDINARY_API_SECRET'

oe_data_read() { # file, exact allowed key names; literal values only
  local file="$1" allowed="$2" line key value seen=' '
  [ -f "$file" ] && [ ! -L "$file" ] || oe_iso_refuse 'configuration file missing or symlinked'
  case "$file" in */.env.local|.env.local) oe_iso_refuse '.env.local is never an input' ;; esac
  for key in $allowed; do unset "$key"; done
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue ;; esac
    case "$line" in *=*) ;; *) oe_iso_refuse 'expected literal KEY=VALUE' ;; esac
    key="${line%%=*}"; value="${line#*=}"
    case " $allowed " in *" $key "*) ;; *) oe_iso_refuse "unapproved configuration key: $key" ;; esac
    case "$seen" in *" $key "*) oe_iso_refuse "duplicate configuration key: $key" ;; esac
    case "$value" in *'`'*|*'$'*|*'"'*|*"'"*|*';'*|*'\\'*|*$'\r'*) oe_iso_refuse "nonliteral value for $key" ;; esac
    export "$key=$value"; seen="$seen$key "
  done < "$file"
}

oe_runtime_validate_dir() {
  local root="${PATHOME_OE_TEST_RUN_DIR:-}" part cursor=''
  case "$root" in /private/tmp/pathome-oe-*|/tmp/pathome-oe-*) ;; *) oe_iso_refuse 'run directory must be an external /private/tmp/pathome-oe-* directory' ;; esac
  [[ "$root" =~ ^/[A-Za-z0-9_/-]+$ ]] || oe_iso_refuse 'invalid run directory'
  case "$root/" in *'/../'*|*'/./'*) oe_iso_refuse 'noncanonical run directory' ;; esac
  IFS=/ read -r -a oe_dir_parts <<< "$root"
  for part in "${oe_dir_parts[@]}"; do
    [ -n "$part" ] || continue
    cursor="$cursor/$part"
    # /tmp itself is an OS symlink on macOS; children may never be symlinks.
    [ "$cursor" = '/tmp' ] || [ ! -L "$cursor" ] || oe_iso_refuse 'symlinked run path'
  done
  if [ -e "$root" ]; then [ -d "$root" ] && [ -O "$root" ] || oe_iso_refuse 'run directory is not owned by this user'; fi
}

oe_runtime_load_designation() {
  oe_data_read "$OE_ENV_FILE" "$OE_DATA_DESIGNATION"
  export PATHOME_OE_TEST_ENV_FILE="$OE_ENV_FILE"
  oe_guard_resolve
  oe_runtime_validate_dir
  oe_guard_port "${OE_BACKEND_PORT:-8080}"
  OE_API_BASE_URL="http://127.0.0.1:${OE_BACKEND_PORT:-8080}/api/v1"
  export OE_API_BASE_URL
}

oe_runtime_load_secrets() {
  local file="$PATHOME_OE_TEST_RUN_DIR/secrets.env"
  [ -O "$file" ] && [ "$(stat -f %Lp "$file" 2>/dev/null || stat -c %a "$file")" = '600' ] || oe_iso_refuse 'secrets require owner-only mode 600'
  oe_data_read "$file" "$OE_DATA_SECRETS"
  [ -n "${OE_RUN_ID:-}" ] && [ "$OE_RUN_ID" = "$(< "$PATHOME_OE_TEST_RUN_DIR/run-id")" ] || oe_iso_refuse 'secrets belong to another run'
  for key in OE_BOOTSTRAP_ADMIN_PASSWORD OE_STAFF_PASSWORD OE_JWT_SECRET; do
    [[ "${!key}" =~ ^[a-f0-9]{48}$ ]] || oe_iso_refuse 'only generated per-run credentials are accepted'
  done
  [[ "${OE_BOOTSTRAP_ADMIN_EMAIL:-}" =~ ^[A-Za-z0-9._+-]+@pathome\.test$ ]] || oe_iso_refuse 'bootstrap identity must be synthetic'
}

# No inherited environment is forwarded. Only explicit harness inputs and values prepared by the
# isolation function are passed. HOME and JVM user.home cannot load operator rc/configuration files.
oe_runtime_exec() { # profile, executable, args...
  local profile="$1" executable="$2" key value binary
  shift 2
  binary="$(PATH="$OE_RUNTIME_PATH" type -P "$executable")" || oe_iso_refuse "missing executable: $executable"
  local root="${PATHOME_OE_TEST_RUN_DIR:-/private/tmp/pathome-oe-static}"
  local vars=("PATH=$OE_RUNTIME_PATH" "HOME=$root/home" 'LANG=en_US.UTF-8' 'LC_ALL=C' 'TZ=Asia/Kolkata')
  local keys="$OE_DATA_DESIGNATION PATHOME_OE_TEST_ENV_FILE PATHOME_OE_TEST_DB_PASSWORD PATHOME_OE_TEST_PHASE2A_LIVE PATHOME_OE_TEST_FIXTURE_ROLES PATHOME_OE_TEST_BACKEND_PORT PATHOME_OE_TEST_MEDIA_ENV_FILE PATHOME_OE_TEST_MEDIA_FILE PATHOME_OE_TEST_ALLOW_LOCAL_BUILD OE_OPERATOR_HOME OE_COMMAND_SUPERVISED OE_COMMAND_ATTEMPT_DIR OE_API_BASE_URL"
  case "$profile" in
    maven)
      [ ! -d "$OE_REPO_ROOT/.mvn" ] && [ ! -d "$OE_REPO_ROOT/backend/.mvn" ] || oe_iso_refuse 'unreviewed .mvn configuration'
      vars+=("MAVEN_SKIP_RC=true" "MAVEN_OPTS=-Duser.home=$root/home -Djava.io.tmpdir=$root/tmp")
      keys="$keys PATHOME_VISIT_MIN_CONTACT_ATTEMPTS PATHOME_VISIT_CONTACT_SPACING_MINUTES"
      keys="$keys SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD SPRING_DATASOURCE_HIKARI_JDBC_URL SPRING_FLYWAY_URL SPRING_FLYWAY_USER SPRING_FLYWAY_PASSWORD SPRING_JPA_HIBERNATE_DDL_AUTO SPRING_CONFIG_LOCATION SPRING_PROFILES_ACTIVE SPRING_PROFILES_INCLUDE JWT_SECRET APP_JWT_SECRET CLOUDINARY_CLOUD_NAME CLOUDINARY_API_KEY CLOUDINARY_API_SECRET PATHOME_STAGING_LOCAL_PATH PATHOME_STAGING_S3_BUCKET PATHOME_DRAFT_STAGING_S3_BUCKET APP_FIRST_ADMIN_PROVISIONING_ENABLED APP_BOOTSTRAP_ADMIN_EMAIL APP_BOOTSTRAP_ADMIN_PASSWORD APP_BOOTSTRAP_OPERATOR_REFERENCE SERVER_PORT SERVER_ADDRESS PATHOME_DATABASE_ALLOW_FLYWAY_BASELINE PATHOME_VISIT_OPERATIONS_POSTGRES_TEST"
      ;;
    node)
      vars+=("PLAYWRIGHT_BROWSERS_PATH=$OE_RUNTIME_HOME/Library/Caches/ms-playwright")
      keys="$keys P4_BACKEND_URL P4_ADMIN_EMAIL P4_ADMIN_PASSWORD P4_STAFF_PASSWORD P4_MEDIA_FILE P4_STATE_FILE P4_ATTESTATION_FILE P4_CITY_CODE P4_LOCALITY_CITY P4_LOCALITY_TERM P4_VISIT_DURATION_MINUTES P3A_BACKEND_URL P3B_BACKEND_URL P3B_ADMIN_EMAIL P3B_ADMIN_PASSWORD P3B_CITY_ID P3A_CITY_ID P3A_TEAM_ALPHA_ID P3A_TEAM_BRAVO_ID oe_alpha_EMAIL oe_alpha_PASSWORD oe_bravo_EMAIL oe_bravo_PASSWORD staff_nogrant_EMAIL staff_nogrant_PASSWORD city_admin_EMAIL city_admin_PASSWORD"
      ;;
    build)
      [ ! -e "$root/home/.npmrc" ] && [ ! -L "$root/home/.npmrc" ] || oe_iso_refuse 'unreviewed private npm configuration'
      keys=''; vars+=("NPM_CONFIG_USERCONFIG=$root/home/.npmrc" 'NPM_CONFIG_GLOBALCONFIG=/dev/null' 'NPM_CONFIG_OFFLINE=true' 'NPM_CONFIG_IGNORE_SCRIPTS=true' 'VITE_API_BASE_URL=/api/v1' 'VITE_OAUTH_BASE_URL=' 'VITE_GOOGLE_LOGIN_ENABLED=false') ;;
    curl) keys='' ;;
    psql) keys=''; vars+=("PGPASSWORD=${PATHOME_OE_TEST_DB_PASSWORD:-}" 'PGCONNECT_TIMEOUT=5' 'PGOPTIONS=-c search_path=public -c statement_timeout=15000 -c lock_timeout=5000') ;;
    *) oe_iso_refuse 'unknown subprocess profile' ;;
  esac
  for key in $keys; do
    if [ "${!key+x}" = x ]; then value="${!key}"; vars+=("$key=$value"); fi
  done
  case "$profile" in
    maven)
      if [ "${OE_RUNTIME_REPLACE:-false}" = true ]; then exec env -i "${vars[@]}" "$binary" "-Dmaven.repo.local=$OE_RUNTIME_HOME/.m2/repository" "$@"; fi
      command env -i "${vars[@]}" "$binary" "-Dmaven.repo.local=$OE_RUNTIME_HOME/.m2/repository" "$@" ;;
    curl) command env -i "${vars[@]}" "$binary" -q --noproxy '*' --proxy '' --proto '=http' --connect-timeout 3 --max-time 20 --max-redirs 0 "$@" ;;
    *) command env -i "${vars[@]}" "$binary" "$@" ;;
  esac
}

node() { oe_runtime_exec node node "$@"; }
mvn() { oe_runtime_exec maven mvn "$@"; }
curl() {
  local arg value anonymous=true destination='' method=GET
  local args=()
  # Parse only the forms used by the harness. Values are consumed as data, never reconsidered as
  # options. In particular --url[=], -K/--config, --next and extra destinations are not accepted.
  while [ "$#" -gt 0 ]; do
    arg="$1"; shift
    case "$arg" in
      -s|-sS) args+=("$arg") ;;
      -o|-D|-w|-X|-H|-d|--max-time)
        [ "$#" -gt 0 ] || oe_iso_refuse 'missing HTTP argument value'
        value="$1"; shift
        case "$arg" in
          -X) case "$value" in GET|POST|PUT|PATCH|DELETE) method="$value" ;; *) oe_iso_refuse 'unapproved HTTP method' ;; esac ;;
          -H) case "$value" in 'Authorization: Bearer '*|'Content-Type: application/json') ;; *) oe_iso_refuse 'unapproved HTTP header' ;; esac
              case "$value" in *$'\r'*|*$'\n'*) oe_iso_refuse 'invalid HTTP header' ;; esac ;;
          -d) case "$value" in @*) oe_iso_refuse 'HTTP body files are not accepted' ;; esac ;;
          --max-time) [[ "$value" =~ ^[1-9][0-9]?$ ]] && [ "$value" -le 20 ] || oe_iso_refuse 'invalid HTTP deadline' ;;
          -w) [ "$value" = '%{http_code}' ] || oe_iso_refuse 'unapproved HTTP output format' ;;
          -o|-D) case "$value" in /dev/null|-) ;; "$PATHOME_OE_TEST_RUN_DIR"/*) case "$value" in *'/../'*|*'/./'*) oe_iso_refuse 'noncanonical HTTP output path' ;; esac ;; *) oe_iso_refuse 'HTTP output must stay in the private run directory' ;; esac ;;
        esac
        case "$arg" in -X|-H|-d) anonymous=false ;; esac
        args+=("$arg" "$value") ;;
      http://*)
        [ -z "$destination" ] || oe_iso_refuse 'exactly one HTTP destination is permitted'
        [[ "$arg" =~ ^http://127\.0\.0\.1:[0-9]+/api/v1/ ]] && [ "${arg%%/api/v1/*}" = "http://127.0.0.1:${OE_BACKEND_PORT:-8080}" ] || oe_iso_refuse 'HTTP origin differs from designation'
        case "$arg" in *$'\r'*|*$'\n'*|*'#'*|*'{'*|*'}'*|*'['*|*']'*) oe_iso_refuse 'invalid HTTP destination' ;; esac
        destination="$arg" ;;
      *) oe_iso_refuse 'unapproved HTTP argument or destination' ;;
    esac
  done
  [ -n "$destination" ] || oe_iso_refuse 'exactly one HTTP destination is required'
  # The sole pre-binding HTTP operation is an anonymous readiness probe. It carries no credential
  # and cannot mutate state. Every other curl request re-proves the process/listener binding.
  if [ "$anonymous" != true ] || [ "$destination" != "${OE_API_BASE_URL:-}/staff/me/capabilities" ]; then
    node "$OE_TOOL_DIR/lib/runtime.mjs" verify
  fi
  oe_runtime_exec curl curl --globoff "${args[@]}" "$destination"
}

oe_runtime_require_backend() {
  oe_guard_verify --destructive
  oe_guard_require_run
  node "$OE_TOOL_DIR/lib/runtime.mjs" verify
}

oe_runtime_supervise() {
  if [ "${OE_COMMAND_SUPERVISED:-}" = true ]; then
    node "$OE_TOOL_DIR/lib/lifecycle.mjs" verify-supervision "$$" "$2"
    return "$?"
  fi
  oe_runtime_load_designation
  umask 077
  mkdir -p "$PATHOME_OE_TEST_RUN_DIR"
  node "$OE_TOOL_DIR/lib/lifecycle.mjs" supervise "$1" "${@:2}"
  exit "$?"
}

oe_runtime_checkpoint() { node "$OE_TOOL_DIR/lib/lifecycle.mjs" checkpoint "$1"; }
