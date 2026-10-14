#!/usr/bin/env bash
# Pathome OE integration environment - PostgreSQL target guard.
#
# Fail-closed, positive verification of the PostgreSQL target identity. Every DDL or
# destructive operation in this test environment must run oe_guard_verify first, and must
# refuse to continue when this guard refuses.
#
# A target is usable only when it is *proven* to be the explicitly designated disposable
# integration database. Everything else - production, staging, the preserved `pathome_db`
# development database, a mistyped name, or an unstated target - is refused.
#
# This file is sourced by the other scripts and can also be invoked directly:
#   db-guard.sh check [--destructive]
#   db-guard.sh check-jdbc <jdbc-url>
#   db-guard.sh selftest

OE_GUARD_VERSION='1.0.0'

# Absolute path back to this file so the self test can re-source it in a clean subshell.
OE_GUARD_SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"

# Stable refusal codes. The verification suite asserts these exact values.
OE_GUARD_ERR_NO_OPT_IN=10
OE_GUARD_ERR_NO_DESIGNATION=11
OE_GUARD_ERR_NON_LOCAL_HOST=12
OE_GUARD_ERR_NAME_PATTERN=13
OE_GUARD_ERR_PROTECTED_NAME=14
OE_GUARD_ERR_AMBIGUOUS_NAME=15
OE_GUARD_ERR_LIVE_IDENTITY_MISMATCH=16
OE_GUARD_ERR_MISSING_SENTINEL=17
OE_GUARD_ERR_CONNECT_FAILED=18
OE_GUARD_ERR_JDBC_MISMATCH=19

# Package 3A acceptance policy. These two codes extend the table above for the stricter rule set the
# Package 3A PostgreSQL tests run under; the Java guard in
# backend/src/test/java/com/indore/pathome/spaces/support/DisposableIntegrationTarget.java mirrors this
# function rule for rule, and oe_guard_package3_selftest pins the designation file to the same values.
OE_GUARD_ERR_TEST_DISABLED=20
OE_GUARD_ERR_PORT_NOT_APPROVED=21
OE_GUARD_PACKAGE3_APPROVED_DB='pathome_oe_test_integration'
OE_GUARD_PACKAGE3_APPROVED_PORT='5432'
OE_GUARD_PACKAGE3_ENV_FILE="${PATHOME_OE_TEST_ENV_FILE:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/oe-test.env}"

# Only names of this shape can ever be treated as a disposable integration database.
OE_GUARD_NAME_PATTERN='^pathome_oe_test_[a-z0-9_]{1,40}$'

# Databases that must never be created, migrated, reset, or dropped by this tooling.
OE_GUARD_PROTECTED_NAMES='pathome_db pathome_autonomous_v2_test postgres template0 template1 divyavastu_db hotel_booking'

# Any of these substrings makes a name ambiguous, so it is refused even if it matches the
# disposable name pattern.
OE_GUARD_AMBIGUOUS_TOKENS='prod production stage staging live main master dev demo uat'

# Local-only hosts. A remote host can only be production or staging, so it is refused.
OE_GUARD_LOCAL_HOSTS='localhost 127.0.0.1 ::1 [::1]'

# Positive disposable marker written at creation time and required before destruction.
OE_GUARD_SENTINEL_TABLE='pathome_oe_test_env'
OE_GUARD_SENTINEL_MARKER='DISPOSABLE'

oe_guard_refuse() {
  local code="$1"
  shift
  printf 'REFUSED [%s] %s\n' "$code" "$*" >&2
  exit "$code"
}

oe_guard_info() {
  printf '%s\n' "$*" >&2
}

# Fills the target coordinate defaults. Defaults are inert; the designation itself is
# never defaulted.
oe_guard_env_defaults() {
  : "${PATHOME_OE_TEST_DB_HOST:=localhost}"
  : "${PATHOME_OE_TEST_DB_PORT:=5432}"
  : "${PATHOME_OE_TEST_DB_ADMIN_USER:=postgres}"
  : "${PATHOME_OE_TEST_DB_APP_USER:=pathome}"
  : "${PATHOME_OE_TEST_RUN_DIR:=/private/tmp/pathome-oe-test}"
  export PATHOME_OE_TEST_DB_HOST PATHOME_OE_TEST_DB_PORT
  export PATHOME_OE_TEST_DB_ADMIN_USER PATHOME_OE_TEST_DB_APP_USER
  export PATHOME_OE_TEST_RUN_DIR
}

oe_guard_host_is_local() {
  local host="$1" allowed
  for allowed in $OE_GUARD_LOCAL_HOSTS; do
    [ "$host" = "$allowed" ] && return 0
  done
  return 1
}

oe_guard_name_is_protected() {
  local name="$1" protected
  for protected in $OE_GUARD_PROTECTED_NAMES; do
    [ "$name" = "$protected" ] && return 0
  done
  return 1
}

oe_guard_name_is_ambiguous() {
  local name="$1" token
  for token in $OE_GUARD_AMBIGUOUS_TOKENS; do
    case "$name" in
      *"$token"*) return 0 ;;
    esac
  done
  return 1
}

# Static rules: explicit opt-in, explicit designation, local host, disposable-shaped name,
# and no protected or ambiguous name. No server connection is made here.
oe_guard_resolve() {
  oe_guard_env_defaults

  if [ "${PATHOME_OE_TEST_ALLOW_DISPOSABLE:-}" != 'true' ]; then
    oe_guard_refuse "$OE_GUARD_ERR_NO_OPT_IN" \
      "Disposable integration target is not opted in. Set PATHOME_OE_TEST_ALLOW_DISPOSABLE=true to confirm you intend to use a throwaway database."
  fi

  if [ -z "${PATHOME_OE_TEST_DB:-}" ]; then
    oe_guard_refuse "$OE_GUARD_ERR_NO_DESIGNATION" \
      "No disposable database designated. Set PATHOME_OE_TEST_DB to the exact target name; nothing is assumed and nothing is defaulted."
  fi

  if ! oe_guard_host_is_local "$PATHOME_OE_TEST_DB_HOST"; then
    oe_guard_refuse "$OE_GUARD_ERR_NON_LOCAL_HOST" \
      "Host '$PATHOME_OE_TEST_DB_HOST' is not a local loopback address. Remote targets are refused because they cannot be positively identified as disposable."
  fi

  if ! printf '%s' "$PATHOME_OE_TEST_DB" | grep -Eq "$OE_GUARD_NAME_PATTERN"; then
    oe_guard_refuse "$OE_GUARD_ERR_NAME_PATTERN" \
      "Database '$PATHOME_OE_TEST_DB' does not match the disposable integration name pattern $OE_GUARD_NAME_PATTERN."
  fi

  if oe_guard_name_is_protected "$PATHOME_OE_TEST_DB"; then
    oe_guard_refuse "$OE_GUARD_ERR_PROTECTED_NAME" \
      "Database '$PATHOME_OE_TEST_DB' is a protected local database and is never a valid integration target."
  fi

  if oe_guard_name_is_ambiguous "$PATHOME_OE_TEST_DB"; then
    oe_guard_refuse "$OE_GUARD_ERR_AMBIGUOUS_NAME" \
      "Database '$PATHOME_OE_TEST_DB' contains an environment token that makes its identity ambiguous."
  fi

  export PATHOME_OE_TEST_DB
  return 0
}

# psql helper honouring the optional password without ever printing it.
oe_guard_psql() {
  local database="$1" user="$2"
  shift 2
  if [ -n "${PATHOME_OE_TEST_DB_PASSWORD:-}" ]; then
    PGPASSWORD="$PATHOME_OE_TEST_DB_PASSWORD" \
      psql -h "$PATHOME_OE_TEST_DB_HOST" -p "$PATHOME_OE_TEST_DB_PORT" -U "$user" -d "$database" -v ON_ERROR_STOP=1 "$@"
  else
    psql -h "$PATHOME_OE_TEST_DB_HOST" -p "$PATHOME_OE_TEST_DB_PORT" -U "$user" -d "$database" -v ON_ERROR_STOP=1 "$@"
  fi
}

# Proves the live connection really reached the designated database.
oe_guard_check_live_identity() {
  local database="$1" user="$2" actual
  actual="$(oe_guard_psql "$database" "$user" -Atc 'select current_database()' 2>/dev/null)" || \
    oe_guard_refuse "$OE_GUARD_ERR_CONNECT_FAILED" \
      "Could not connect to '$database' on $PATHOME_OE_TEST_DB_HOST:$PATHOME_OE_TEST_DB_PORT as '$user'."

  if [ "$actual" != "$PATHOME_OE_TEST_DB" ]; then
    oe_guard_refuse "$OE_GUARD_ERR_LIVE_IDENTITY_MISMATCH" \
      "Connection resolved to database '$actual' but '$PATHOME_OE_TEST_DB' was designated."
  fi
  return 0
}

# Proves the connected database carries the positive disposable marker.
oe_guard_check_sentinel() { # <database> <user> [operation]
  local database="$1" user="$2" operation="${3:-Destruction}" count
  count="$(oe_guard_psql "$database" "$user" -Atc \
    "select count(*) from $OE_GUARD_SENTINEL_TABLE where env_marker = '$OE_GUARD_SENTINEL_MARKER' and db_name = current_database()" 2>/dev/null)" || \
    oe_guard_refuse "$OE_GUARD_ERR_MISSING_SENTINEL" \
      "Database '$database' has no positive disposable marker. $operation is refused because the target is not proven disposable."

  if [ "${count:-0}" != '1' ]; then
    oe_guard_refuse "$OE_GUARD_ERR_MISSING_SENTINEL" \
      "Database '$database' disposable marker is absent or ambiguous (matches=$count). $operation is refused."
  fi
  return 0
}

# Full verification. --destructive additionally requires the positive disposable marker.
oe_guard_verify() {
  local destructive='false'
  for argument in "$@"; do
    [ "$argument" = '--destructive' ] && destructive='true'
  done

  oe_guard_resolve
  oe_guard_check_live_identity "$PATHOME_OE_TEST_DB" "$PATHOME_OE_TEST_DB_APP_USER"
  if [ "$destructive" = 'true' ]; then
    oe_guard_check_sentinel "$PATHOME_OE_TEST_DB" "$PATHOME_OE_TEST_DB_APP_USER"
  fi
  return 0
}

# Refuses any application datasource that is not the designated disposable database.
# Used before the backend is started, so the application can never be pointed at a
# preserved or remote database by mistake.
oe_guard_verify_jdbc() {
  local jdbc_url="$1" database
  oe_guard_resolve

  database="${jdbc_url%%\?*}"
  database="${database##*/}"

  if [ "$database" != "$PATHOME_OE_TEST_DB" ]; then
    oe_guard_refuse "$OE_GUARD_ERR_JDBC_MISMATCH" \
      "Refusing to start against JDBC target '$database'. The only permitted target in this environment is the designated disposable database '$PATHOME_OE_TEST_DB'."
  fi
  case "$jdbc_url" in
    *"//$PATHOME_OE_TEST_DB_HOST"*) return 0 ;;
    *'//localhost'*|*'//127.0.0.1'*) return 0 ;;
  esac
  oe_guard_refuse "$OE_GUARD_ERR_JDBC_MISMATCH" \
    "JDBC URL '$jdbc_url' does not address a local host."
}

oe_guard_jdbc_url() {
  printf 'jdbc:postgresql://%s:%s/%s' \
    "$PATHOME_OE_TEST_DB_HOST" "$PATHOME_OE_TEST_DB_PORT" "$PATHOME_OE_TEST_DB"
}

# Static-only self check used by tooling that must not touch the server yet.
oe_guard_describe() {
  oe_guard_env_defaults
  printf 'guard_version=%s\n' "$OE_GUARD_VERSION"
  printf 'opt_in=%s\n' "${PATHOME_OE_TEST_ALLOW_DISPOSABLE:-<unset>}"
  printf 'designated_db=%s\n' "${PATHOME_OE_TEST_DB:-<unset>}"
  printf 'host=%s\n' "$PATHOME_OE_TEST_DB_HOST"
  printf 'port=%s\n' "$PATHOME_OE_TEST_DB_PORT"
  printf 'admin_user=%s\n' "$PATHOME_OE_TEST_DB_ADMIN_USER"
  printf 'app_user=%s\n' "$PATHOME_OE_TEST_DB_APP_USER"
  printf 'expected_jdbc=%s\n' "$(oe_guard_jdbc_url)"
}

# Package 3A acceptance preflight: one approved disposable database, one approved loopback port, the
# disposable marker verified live, and the datasource about to be handed to the test runner proven to be
# that same target - all before any test schema, DDL or mutable application context can be created.
#
# Ordered so that every static rule (including the datasource identity) refuses without a connection.
oe_guard_verify_package3_acceptance() { # <jdbc-url>
  local jdbc_url="${1:?jdbc url required}"

  oe_guard_resolve

  if [ "${PATHOME_VISIT_OPERATIONS_POSTGRES_TEST:-}" != 'true' ]; then
    oe_guard_refuse "$OE_GUARD_ERR_TEST_DISABLED" \
      "The Package 3A PostgreSQL tests are not explicitly enabled. Set PATHOME_VISIT_OPERATIONS_POSTGRES_TEST=true; nothing is assumed."
  fi

  if [ "$PATHOME_OE_TEST_DB" != "$OE_GUARD_PACKAGE3_APPROVED_DB" ]; then
    oe_guard_refuse "$OE_GUARD_ERR_NAME_PATTERN" \
      "Database '$PATHOME_OE_TEST_DB' is not the approved Package 3A disposable database '$OE_GUARD_PACKAGE3_APPROVED_DB'."
  fi

  if [ "$PATHOME_OE_TEST_DB_PORT" != "$OE_GUARD_PACKAGE3_APPROVED_PORT" ]; then
    oe_guard_refuse "$OE_GUARD_ERR_PORT_NOT_APPROVED" \
      "Port '$PATHOME_OE_TEST_DB_PORT' is not the approved disposable port '$OE_GUARD_PACKAGE3_APPROVED_PORT'; the port is never defaulted."
  fi

  # The datasource is validated statically before any connection is opened.
  oe_guard_verify_jdbc "$jdbc_url"

  # Live facts on the proven target: the connection really is the designation, and it really carries the
  # positive disposable marker (read-only; no test schema or DDL has run yet).
  oe_guard_check_live_identity "$PATHOME_OE_TEST_DB" "$PATHOME_OE_TEST_DB_APP_USER"
  oe_guard_check_sentinel "$PATHOME_OE_TEST_DB" "$PATHOME_OE_TEST_DB_APP_USER" 'The Package 3A test run'
  return 0
}

# Runs the Package 3A static acceptance rules in a hermetic subshell with exactly the given assignments,
# then prints the resulting exit code. Every case is a refusal, so no connection is ever attempted.
oe_guard_package3_static_exit_code() {
  env -u PATHOME_OE_TEST_DB -u PATHOME_OE_TEST_ALLOW_DISPOSABLE -u PATHOME_OE_TEST_DB_HOST \
      -u PATHOME_OE_TEST_DB_PORT -u PATHOME_OE_TEST_DB_ADMIN_USER -u PATHOME_OE_TEST_DB_APP_USER \
      -u PATHOME_VISIT_OPERATIONS_POSTGRES_TEST \
      "$@" bash -c "source '$OE_GUARD_SELF'; oe_guard_verify_package3_acceptance 'jdbc:postgresql://localhost:5432/pathome_oe_test_integration'" >/dev/null 2>&1
  printf '%s' "$?"
}

# Negative proofs for the Package 3A acceptance preflight plus a drift check between the designation file
# and the approved constants. Exits non-zero if any expectation is not met.
oe_guard_package3_selftest() {
  local failures=0 actual designated approved_port

  expect_p3_refusal() { # description expected_code assignments...
    local description="$1" expected="$2"
    shift 2
    actual="$(oe_guard_package3_static_exit_code "$@")"
    if [ "$actual" = "$expected" ]; then
      printf 'PASS  %s -> refused with code %s\n' "$description" "$actual"
    else
      printf 'FAIL  %s -> expected refusal %s, actual %s\n' "$description" "$expected" "$actual"
      failures=$((failures + 1))
    fi
  }

  expect_p3_equals() { # description expected actual
    if [ "$2" = "$3" ]; then
      printf 'PASS  %s -> %s\n' "$1" "$3"
    else
      printf 'FAIL  %s -> expected=%s actual=%s\n' "$1" "$2" "$3"
      failures=$((failures + 1))
    fi
  }

  local enabled='true' designated_db="$OE_GUARD_PACKAGE3_APPROVED_DB"
  expect_p3_refusal 'refuses when the disposable opt-in is missing' "$OE_GUARD_ERR_NO_OPT_IN" \
    PATHOME_VISIT_OPERATIONS_POSTGRES_TEST="$enabled" PATHOME_OE_TEST_DB="$designated_db"
  expect_p3_refusal 'refuses when the test runner is not explicitly enabled' "$OE_GUARD_ERR_TEST_DISABLED" \
    PATHOME_OE_TEST_ALLOW_DISPOSABLE="$enabled" PATHOME_OE_TEST_DB="$designated_db"
  expect_p3_refusal 'refuses when no database is designated' "$OE_GUARD_ERR_NO_DESIGNATION" \
    PATHOME_OE_TEST_ALLOW_DISPOSABLE="$enabled" PATHOME_VISIT_OPERATIONS_POSTGRES_TEST="$enabled"
  expect_p3_refusal 'refuses the preserved pathome_db development database' "$OE_GUARD_ERR_NAME_PATTERN" \
    PATHOME_OE_TEST_ALLOW_DISPOSABLE="$enabled" PATHOME_VISIT_OPERATIONS_POSTGRES_TEST="$enabled" \
    PATHOME_OE_TEST_DB='pathome_db'
  expect_p3_refusal 'refuses a remote host' "$OE_GUARD_ERR_NON_LOCAL_HOST" \
    PATHOME_OE_TEST_ALLOW_DISPOSABLE="$enabled" PATHOME_VISIT_OPERATIONS_POSTGRES_TEST="$enabled" \
    PATHOME_OE_TEST_DB="$designated_db" PATHOME_OE_TEST_DB_HOST='db.production.example.com'
  expect_p3_refusal 'refuses an unapproved disposable database name' "$OE_GUARD_ERR_NAME_PATTERN" \
    PATHOME_OE_TEST_ALLOW_DISPOSABLE="$enabled" PATHOME_VISIT_OPERATIONS_POSTGRES_TEST="$enabled" \
    PATHOME_OE_TEST_DB='pathome_oe_test_other'
  expect_p3_refusal 'refuses an ambiguous disposable name' "$OE_GUARD_ERR_AMBIGUOUS_NAME" \
    PATHOME_OE_TEST_ALLOW_DISPOSABLE="$enabled" PATHOME_VISIT_OPERATIONS_POSTGRES_TEST="$enabled" \
    PATHOME_OE_TEST_DB='pathome_oe_test_staging'
  expect_p3_refusal 'refuses an unapproved port' "$OE_GUARD_ERR_PORT_NOT_APPROVED" \
    PATHOME_OE_TEST_ALLOW_DISPOSABLE="$enabled" PATHOME_VISIT_OPERATIONS_POSTGRES_TEST="$enabled" \
    PATHOME_OE_TEST_DB="$designated_db" PATHOME_OE_TEST_DB_PORT='5433'

  # The designation file is the shared source of truth for the shell and Java halves of the policy; if it
  # ever changes, both halves must be reviewed together, and this check fails loudly first.
  if [ -f "$OE_GUARD_PACKAGE3_ENV_FILE" ]; then
    designated="$(sed -n 's/^PATHOME_OE_TEST_DB=//p' "$OE_GUARD_PACKAGE3_ENV_FILE" | tail -1)"
    approved_port="$(sed -n 's/^PATHOME_OE_TEST_DB_PORT=//p' "$OE_GUARD_PACKAGE3_ENV_FILE" | tail -1)"
    expect_p3_equals 'the designation file still names the approved disposable database' \
      "$OE_GUARD_PACKAGE3_APPROVED_DB" "$designated"
    expect_p3_equals 'the designation file still pins the approved disposable port' \
      "$OE_GUARD_PACKAGE3_APPROVED_PORT" "$approved_port"
  else
    printf 'FAIL  designation file %s is missing\n' "$OE_GUARD_PACKAGE3_ENV_FILE"
    failures=$((failures + 1))
  fi

  return "$failures"
}

# Runs the static guard rules in a hermetic subshell with exactly the given assignments and
# inherited guard variables removed, then prints the resulting exit code.
oe_guard_static_exit_code() {
  env -u PATHOME_OE_TEST_DB -u PATHOME_OE_TEST_ALLOW_DISPOSABLE -u PATHOME_OE_TEST_DB_HOST \
      -u PATHOME_OE_TEST_DB_PORT -u PATHOME_OE_TEST_DB_ADMIN_USER -u PATHOME_OE_TEST_DB_APP_USER \
      "$@" bash -c "source '$OE_GUARD_SELF'; oe_guard_resolve" >/dev/null 2>&1
  printf '%s' "$?"
}

# Negative and positive proofs. Exits non-zero if any expectation is not met, and reports
# one line per case so the result is auditable.
oe_guard_selftest() {
  local failures=0 actual

  expect_refusal() { # description expected_code assignments...
    local description="$1" expected="$2"
    shift 2
    actual="$(oe_guard_static_exit_code "$@")"
    if [ "$actual" = "$expected" ]; then
      printf 'PASS  %s -> refused with code %s\n' "$description" "$actual"
    else
      printf 'FAIL  %s -> expected refusal %s, actual %s\n' "$description" "$expected" "$actual"
      failures=$((failures + 1))
    fi
  }

  expect_accept() { # description assignments...
    local description="$1"
    shift
    actual="$(oe_guard_static_exit_code "$@")"
    if [ "$actual" = '0' ]; then
      printf 'PASS  %s -> accepted\n' "$description"
    else
      printf 'FAIL  %s -> expected acceptance, actual refusal %s\n' "$description" "$actual"
      failures=$((failures + 1))
    fi
  }

  expect_refusal 'refuses when disposable opt-in is not set' "$OE_GUARD_ERR_NO_OPT_IN" \
    PATHOME_OE_TEST_ALLOW_DISPOSABLE='' PATHOME_OE_TEST_DB='pathome_oe_test_guard'
  expect_refusal 'refuses when disposable opt-in is explicitly false' "$OE_GUARD_ERR_NO_OPT_IN" \
    PATHOME_OE_TEST_ALLOW_DISPOSABLE='false' PATHOME_OE_TEST_DB='pathome_oe_test_guard'
  expect_refusal 'refuses when no target is designated' "$OE_GUARD_ERR_NO_DESIGNATION" \
    PATHOME_OE_TEST_ALLOW_DISPOSABLE='true'
  expect_refusal 'refuses the preserved pathome_db development database' "$OE_GUARD_ERR_NAME_PATTERN" \
    PATHOME_OE_TEST_ALLOW_DISPOSABLE='true' PATHOME_OE_TEST_DB='pathome_db'
  expect_refusal 'refuses the pre-existing pathome_autonomous_v2_test database' "$OE_GUARD_ERR_NAME_PATTERN" \
    PATHOME_OE_TEST_ALLOW_DISPOSABLE='true' PATHOME_OE_TEST_DB='pathome_autonomous_v2_test'
  expect_refusal 'refuses an unrelated local database' "$OE_GUARD_ERR_NAME_PATTERN" \
    PATHOME_OE_TEST_ALLOW_DISPOSABLE='true' PATHOME_OE_TEST_DB='divyavastu_db'
  expect_refusal 'refuses the postgres maintenance database' "$OE_GUARD_ERR_NAME_PATTERN" \
    PATHOME_OE_TEST_ALLOW_DISPOSABLE='true' PATHOME_OE_TEST_DB='postgres'
  expect_refusal 'refuses a remote host' "$OE_GUARD_ERR_NON_LOCAL_HOST" \
    PATHOME_OE_TEST_ALLOW_DISPOSABLE='true' PATHOME_OE_TEST_DB='pathome_oe_test_guard' \
    PATHOME_OE_TEST_DB_HOST='db.production.example.com'
  expect_refusal 'refuses a staging-shaped name' "$OE_GUARD_ERR_NAME_PATTERN" \
    PATHOME_OE_TEST_ALLOW_DISPOSABLE='true' PATHOME_OE_TEST_DB='pathome_staging'
  expect_refusal 'refuses an ambiguous disposable name' "$OE_GUARD_ERR_AMBIGUOUS_NAME" \
    PATHOME_OE_TEST_ALLOW_DISPOSABLE='true' PATHOME_OE_TEST_DB='pathome_oe_test_staging'
  expect_refusal 'refuses a name with no disposable prefix' "$OE_GUARD_ERR_NAME_PATTERN" \
    PATHOME_OE_TEST_ALLOW_DISPOSABLE='true' PATHOME_OE_TEST_DB='oe_test_alpha'
  expect_accept 'accepts a correctly designated disposable target' \
    PATHOME_OE_TEST_ALLOW_DISPOSABLE='true' PATHOME_OE_TEST_DB='pathome_oe_test_guard'

  return "$failures"
}

if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
  case "${1:-}" in
    check)         shift; oe_guard_verify "$@" && echo "GUARD OK: $(oe_guard_jdbc_url)" ;;
    check-jdbc)    shift; oe_guard_verify_jdbc "${1:?jdbc url required}" && echo 'GUARD OK: datasource matches the designated disposable database' ;;
    check-package3) shift; oe_guard_verify_package3_acceptance "${1:?jdbc url required}" \
                     && echo 'GUARD OK: Package 3A acceptance target verified (name, port, live identity, marker, datasource)' ;;
    describe)      oe_guard_describe ;;
    selftest)      oe_guard_selftest ;;
    selftest-package3) oe_guard_package3_selftest ;;
    *)             printf 'usage: %s {check [--destructive] | check-jdbc <url> | check-package3 <url> | describe | selftest | selftest-package3}\n' "$0" >&2; exit 2 ;;
  esac
fi
