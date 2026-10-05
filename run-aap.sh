#!/usr/bin/env bash

# ==============================================================================
# Pathome - Unified Application Launcher (Your Dreams, Our Efforts)
# Starts PostgreSQL health check, Spring Boot Backend (8080) & Vite Frontend (5173)
# ==============================================================================

set -e

# ANSI Color Formatter
BOLD="\033[1m"
GREEN="\033[32m"
CYAN="\033[36m"
YELLOW="\033[33m"
RED="\033[31m"
RESET="\033[0m"

echo -e "${BOLD}${CYAN}"
echo "=========================================================================="
echo "          🏢 PATHOME - YOUR DREAMS, OUR EFFORTS (LOCAL LAUNCHER)          "
echo "=========================================================================="
echo -e "${RESET}"

# Requirement 1: Resolve repository root from script location, independent of caller CWD
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT_DIR="${SCRIPT_DIR}"
BACKEND_DIR="${ROOT_DIR}/backend"
FRONTEND_DIR="${ROOT_DIR}/frontend-web"
ENV_FILE="${ROOT_DIR}/.env.local"

# Strict Worktree Validation
EXPECTED_WORKTREE="/Users/dipeshpatidar/Documents/Pathome-tenant-ui-b1"
if [ "${ROOT_DIR}" != "${EXPECTED_WORKTREE}" ]; then
    echo -e "${RED}[Pathome] WRONG WORKTREE${RESET}"
    echo -e "${RED}Expected:${RESET}"
    echo -e "  ${EXPECTED_WORKTREE}"
    echo -e "${RED}Actual:${RESET}"
    echo -e "  ${ROOT_DIR}"
    exit 1
fi

# Strict Branch Validation
EXPECTED_BRANCH="feature/tenant-ui-b1-integration"
CURRENT_BRANCH="$(git -C "${ROOT_DIR}" branch --show-current 2>/dev/null || true)"
if [ "${CURRENT_BRANCH}" != "${EXPECTED_BRANCH}" ]; then
    echo -e "${RED}[Pathome] WRONG BRANCH${RESET}"
    echo -e "${RED}Expected:${RESET}"
    echo -e "  ${EXPECTED_BRANCH}"
    echo -e "${RED}Actual:${RESET}"
    echo -e "  ${CURRENT_BRANCH}"
    exit 1
fi

# Resolve current short HEAD
CURRENT_HEAD="$(git -C "${ROOT_DIR}" rev-parse --short HEAD 2>/dev/null || true)"

# Directory Existence Validation (Abort before checking DB or handling processes)
if [ ! -d "${BACKEND_DIR}" ]; then
    echo -e "${RED}[Pathome] ERROR: Backend directory not found:${RESET}"
    echo -e "  ${BACKEND_DIR}"
    exit 1
fi

if [ ! -d "${FRONTEND_DIR}" ]; then
    echo -e "${RED}[Pathome] ERROR: Frontend directory not found:${RESET}"
    echo -e "  ${FRONTEND_DIR}"
    exit 1
fi

if [ ! -f "${FRONTEND_DIR}/package.json" ]; then
    echo -e "${RED}[Pathome] ERROR: Frontend package.json not found:${RESET}"
    echo -e "  ${FRONTEND_DIR}/package.json"
    exit 1
fi

# Prominent Startup Identity Block
echo -e "${BOLD}${CYAN}========================================================"
echo "PATHOME UI INTEGRATION INSTANCE"
echo "Worktree: ${ROOT_DIR}"
echo "Branch:   ${CURRENT_BRANCH}"
echo "HEAD:     ${CURRENT_HEAD}"
echo "Frontend: ${FRONTEND_DIR}"
echo "Backend:  ${BACKEND_DIR}"
echo -e "========================================================${RESET}\n"

# Requirement 2: Fast failure if .env.local is missing (no silent fallback)
if [ ! -f "${ENV_FILE}" ]; then
    echo -e "${RED}[Pathome] ERROR: Pathome .env.local not found at ${ENV_FILE}.${RESET}"
    echo -e "${YELLOW}Create it from .env.local.example and populate local secrets.${RESET}"
    exit 1
fi

chmod 600 "${ENV_FILE}"
set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

# Requirement 3: Never silently generate or overwrite JWT_SECRET
if [ -z "${JWT_SECRET:-}" ]; then
    echo -e "${RED}[Pathome] ERROR: JWT_SECRET is missing from .env.local.${RESET}"
    echo -e "${YELLOW}Please add a stable JWT_SECRET (at least 32 characters) to .env.local.${RESET}"
    exit 1
fi

if [ "${#JWT_SECRET}" -lt 32 ]; then
    echo -e "${RED}[Pathome] ERROR: JWT_SECRET must contain at least 32 characters.${RESET}"
    exit 1
fi

# Requirement 4: Validate required configuration variables (names only, no values)
REQUIRED_ENVIRONMENT_VARIABLES=(
    JWT_SECRET
    SPRING_DATASOURCE_PASSWORD
    CLOUDINARY_API_KEY
    CLOUDINARY_API_SECRET
)
MISSING_ENVIRONMENT_VARIABLES=()
for VARIABLE_NAME in "${REQUIRED_ENVIRONMENT_VARIABLES[@]}"; do
    if [ -z "${!VARIABLE_NAME:-}" ]; then
        MISSING_ENVIRONMENT_VARIABLES+=("${VARIABLE_NAME}")
    fi
done

if [ "${#MISSING_ENVIRONMENT_VARIABLES[@]}" -gt 0 ]; then
    echo -e "${RED}[Pathome] ERROR: Missing required Pathome environment variables:${RESET}"
    for VAR in "${MISSING_ENVIRONMENT_VARIABLES[@]}"; do
        echo -e "  - ${VAR}"
    done
    exit 1
fi

if [[ ",${SPRING_PROFILES_ACTIVE:-}," == *",google-login,"* ]]; then
    if [ -z "${GOOGLE_CLIENT_ID:-}" ] || [ -z "${GOOGLE_CLIENT_SECRET:-}" ]; then
        echo -e "${RED}[Pathome] ERROR: The google-login profile requires GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET.${RESET}"
        exit 1
    fi
fi

# Requirement 5: Concise local environment summary (zero secrets displayed)
echo -e "\n${BOLD}${CYAN}[Pathome] Local environment status:${RESET}"
echo -e "  ${GREEN}✓${RESET} .env.local loaded"
echo -e "  ${GREEN}✓${RESET} JWT configuration present"
echo -e "  ${GREEN}✓${RESET} Database credentials present"
echo -e "  ${GREEN}✓${RESET} Cloudinary configuration present"
if [ -z "${PATHOME_STAGING_S3_BUCKET:-}" ]; then
    echo -e "  ${YELLOW}○${RESET} Staging S3 not configured (optional)"
else
    echo -e "  ${GREEN}✓${RESET} Staging S3 configured"
fi

# Requirement 6: Check PostgreSQL reachability before starting Spring Boot
DB_URL="${SPRING_DATASOURCE_URL:-jdbc:postgresql://localhost:5432/pathome_db}"
DB_HOST_PORT="$(echo "${DB_URL}" | sed -E 's|^jdbc:postgresql://([^/]+).*|\1|')"
DB_HOST="$(echo "${DB_HOST_PORT}" | cut -d':' -f1)"
DB_PORT="$(echo "${DB_HOST_PORT}" | cut -s -d':' -f2)"
DB_PORT="${DB_PORT:-5432}"

echo -e "\n${BOLD}${YELLOW}[1/4] Checking PostgreSQL status (${DB_HOST}:${DB_PORT})...${RESET}"
if pg_isready -h "${DB_HOST}" -p "${DB_PORT}" -t 2 2>/dev/null || \
   nc -w 2 -z "${DB_HOST}" "${DB_PORT}" 2>/dev/null; then
    echo -e "${GREEN}✓ PostgreSQL database is online and reachable.${RESET}"
else
    echo -e "${RED}[Pathome] ERROR: PostgreSQL is not reachable on ${DB_HOST}:${DB_PORT}.${RESET}"
    echo -e "${YELLOW}Please ensure PostgreSQL service is running (e.g. 'brew services start postgresql@16').${RESET}"
    exit 1
fi

# Capture descendants before signalling so the SIGKILL fallback can still reach
# children if their parent exits first.
process_tree_pids() {
    local PARENT_PID="$1"
    if ! kill -0 "${PARENT_PID}" 2>/dev/null; then
        return 0
    fi
    local CHILD
    for CHILD in $(pgrep -P "${PARENT_PID}" 2>/dev/null || true); do
        process_tree_pids "${CHILD}"
    done
    printf '%s\n' "${PARENT_PID}"
}

stop_process_tree() {
    local PIDS PID ALIVE
    PIDS=$(process_tree_pids "$1")
    [ -n "${PIDS}" ] || return 0
    for PID in ${PIDS}; do
        kill -TERM "${PID}" 2>/dev/null || true
    done
    for _ in {1..15}; do
        ALIVE=false
        for PID in ${PIDS}; do
            if kill -0 "${PID}" 2>/dev/null; then
                ALIVE=true
                break
            fi
        done
        [ "${ALIVE}" = false ] && return 0
        sleep 0.2
    done
    for PID in ${PIDS}; do
        if kill -0 "${PID}" 2>/dev/null; then
            kill -KILL "${PID}" 2>/dev/null || true
        fi
    done
}

# Requirement 7 & 13: Safe Port Handling - Identify strictly Pathome processes; never kill unrelated apps
check_and_handle_port() {
    local PORT="$1"
    local SERVICE_NAME="$2"
    local PIDS
    PIDS=$(lsof -nP -t -iTCP:"${PORT}" -sTCP:LISTEN 2>/dev/null || true)
    if [ -z "${PIDS}" ]; then
        return 0
    fi

    for PID in ${PIDS}; do
        local CMD
        CMD=$(ps -p "${PID}" -o command= 2>/dev/null || true)
        local PROC_CWD
        PROC_CWD=$(lsof -p "${PID}" -a -d cwd -Fn 2>/dev/null | sed -n 's/^n//p')
        local IS_PATHOME=false

        if [[ "${PORT}" == "8080" ]]; then
            if [[ "${CMD}" == *"pathome-spaces-backend"* || \
                  "${CMD}" == *"PathomeSpacesApplication"* || \
                  "${CMD}" == *"com.indore.pathome"* || \
                  "${CMD}" == *"/backend"* || \
                  "${PROC_CWD}" == *"/backend"* ]]; then
                IS_PATHOME=true
            fi
        elif [[ "${PORT}" == "5173" ]]; then
            if [[ "${CMD}" == *"frontend-web"* || \
                  "${PROC_CWD}" == *"/frontend-web"* || \
                  "${CMD}" == *"pathome-spaces-frontend-web"* ]]; then
                IS_PATHOME=true
            fi
        fi

        if [ "${IS_PATHOME}" = true ]; then
            local RUNNING_WORKTREE=""
            if [ -n "${PROC_CWD}" ]; then
                local TRIMMED="${PROC_CWD%/backend}"
                TRIMMED="${TRIMMED%/frontend-web}"
                RUNNING_WORKTREE="${TRIMMED}"
            fi

            if [ -z "${RUNNING_WORKTREE}" ]; then
                local EXTRACTED
                EXTRACTED="$(echo "${CMD}" | grep -oE '/[^ ]+/(backend|frontend-web)' | head -n1 || true)"
                if [ -n "${EXTRACTED}" ]; then
                    local TRIMMED="${EXTRACTED%/backend}"
                    TRIMMED="${TRIMMED%/frontend-web}"
                    RUNNING_WORKTREE="${TRIMMED}"
                fi
            fi

            if [ "${RUNNING_WORKTREE}" = "${ROOT_DIR}" ]; then
                echo -e "${YELLOW}[Pathome] Terminating stale Pathome ${SERVICE_NAME} listener from current worktree (PID: ${PID})...${RESET}"
                stop_process_tree "${PID}"
            else
                echo -e "${RED}[Pathome] Another Pathome worktree is currently running.${RESET}"
                echo -e "  PID:                ${PID}"
                echo -e "  Process CWD:        ${PROC_CWD:-unknown}"
                echo -e "  Requested worktree: ${ROOT_DIR}"
                echo -e "  Running worktree:   ${RUNNING_WORKTREE:-unknown}"
                echo -e "${YELLOW}Please stop the other instance before starting the UI integration app.${RESET}"
                exit 1
            fi
        else
            echo -e "${RED}[Pathome] ERROR: Port ${PORT} is already in use by another process (PID: ${PID}).${RESET}"
            echo -e "${YELLOW}Please stop the conflicting process or free port ${PORT} before starting Pathome.${RESET}"
            exit 1
        fi
    done
}

echo -e "\n${BOLD}${YELLOW}[2/4] Verifying port availability (8080 & 5173)...${RESET}"
check_and_handle_port 8080 "Backend"
check_and_handle_port 5173 "Frontend"
echo -e "${GREEN}✓ Ports 8080 & 5173 ready.${RESET}"

# Requirement 12: Clean process tracking and recursive graceful shutdown
BACKEND_PID=""
FRONTEND_PID=""

cleanup() {
    local EXIT_CODE=$?
    trap - EXIT INT TERM
    echo ""
    echo -e "${BOLD}${RED}Shutting down Pathome services...${RESET}"
    if [ -n "$BACKEND_PID" ]; then
        echo -e "${YELLOW}Stopping Spring Boot Backend (PID: ${BACKEND_PID})...${RESET}"
        stop_process_tree "$BACKEND_PID"
    fi
    if [ -n "$FRONTEND_PID" ]; then
        echo -e "${YELLOW}Stopping Vite Frontend (PID: ${FRONTEND_PID})...${RESET}"
        stop_process_tree "$FRONTEND_PID"
    fi
    echo -e "${GREEN}✓ All services stopped cleanly.${RESET}"
    exit "${EXIT_CODE}"
}

trap cleanup EXIT INT TERM

# Requirement 8 & 9: Launch Spring Boot Backend and verify readiness
echo -e "\n${BOLD}${YELLOW}[3/4] Starting Spring Boot Backend (http://localhost:8080)...${RESET}"
echo -e "[Pathome] Starting backend..."
cd "${BACKEND_DIR}"
mvn spring-boot:run &
BACKEND_PID=$!
echo -e "${CYAN}Waiting for backend readiness at http://127.0.0.1:8080/api/v1/properties...${RESET}"

BACKEND_READY=false
for ((ATTEMPT = 1; ATTEMPT <= 60; ATTEMPT += 1)); do
    if ! kill -0 "${BACKEND_PID}" 2>/dev/null; then
        wait "${BACKEND_PID}" || true
        echo -e "${RED}[Pathome] ERROR: Backend failed to start. Review logs above.${RESET}"
        exit 1
    fi
    if curl --silent --fail --max-time 2 "http://127.0.0.1:8080/api/v1/properties" >/dev/null 2>&1; then
        BACKEND_READY=true
        break
    fi
    sleep 1
done

if [ "${BACKEND_READY}" != "true" ]; then
    echo -e "${RED}[Pathome] ERROR: Backend did not become ready within 60 seconds.${RESET}"
    exit 1
fi

echo -e "${GREEN}✓ Backend is healthy and accepting requests.${RESET}"

# Requirement 10 & 11: Launch Vite Frontend and verify readiness
echo -e "\n${BOLD}${YELLOW}[4/4] Starting Vite React Frontend (http://localhost:5173)...${RESET}"
echo -e "[Pathome] Starting frontend..."
cd "${FRONTEND_DIR}"
npm run dev &
FRONTEND_PID=$!

FRONTEND_READY=false
for ((ATTEMPT = 1; ATTEMPT <= 30; ATTEMPT += 1)); do
    if ! kill -0 "${FRONTEND_PID}" 2>/dev/null; then
        wait "${FRONTEND_PID}" || true
        echo -e "${RED}[Pathome] ERROR: Frontend failed to start. Review logs above.${RESET}"
        exit 1
    fi
    if curl --silent --fail --max-time 2 "http://localhost:5173/" >/dev/null 2>&1 || \
       curl --silent --fail --max-time 2 "http://127.0.0.1:5173/" >/dev/null 2>&1; then
        FRONTEND_READY=true
        break
    fi
    sleep 1
done

if [ "${FRONTEND_READY}" != "true" ]; then
    echo -e "${RED}[Pathome] ERROR: Frontend did not become ready within 30 seconds.${RESET}"
    exit 1
fi

echo -e "${GREEN}✓ Frontend is healthy and accepting requests.${RESET}"

LOCAL_HOSTNAME="$(scutil --get LocalHostName 2>/dev/null || hostname -s 2>/dev/null || true)"
LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"

echo -e "\n${BOLD}${GREEN}========================================"
echo "Pathome is ready"
echo "========================================"
echo ""
echo "Frontend:"
echo "http://localhost:5173/"
if [ -n "${LOCAL_HOSTNAME}" ]; then
    echo "Mobile:   http://${LOCAL_HOSTNAME}.local:5173/"
fi
if [ -n "${LAN_IP}" ] && [ "${LAN_IP}" != "192.0.0.2" ]; then
    echo "Network:  http://${LAN_IP}:5173/"
fi
echo ""
echo "Backend:"
echo "http://localhost:8080/"
echo "API:      http://localhost:8080/api/v1/properties"
echo ""
echo "Press Ctrl+C to stop Pathome."
echo -e "========================================${RESET}\n"

# Monitor both services; cleanly exit if either stops
while kill -0 "${BACKEND_PID}" 2>/dev/null && kill -0 "${FRONTEND_PID}" 2>/dev/null; do
    sleep 2
done

echo -e "${RED}[Pathome] A service stopped unexpectedly.${RESET}"
exit 1
