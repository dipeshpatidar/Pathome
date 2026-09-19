#!/usr/bin/env bash

# ==============================================================================
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

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="${ROOT_DIR}/backend"
FRONTEND_DIR="${ROOT_DIR}/frontend-web"
ENV_FILE="${ROOT_DIR}/.env.local"

if [ ! -f "${ENV_FILE}" ]; then
    echo -e "${RED}Local configuration is missing: ${ENV_FILE}${RESET}"
    echo -e "${YELLOW}Copy .env.local.example to .env.local and add the required local credentials.${RESET}"
    exit 1
fi

chmod 600 "${ENV_FILE}"
set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

if [ -z "${JWT_SECRET:-}" ]; then
    if ! command -v openssl >/dev/null 2>&1; then
        echo -e "${RED}JWT configuration is missing and OpenSSL is unavailable.${RESET}"
        exit 1
    fi

    JWT_SECRET="$(openssl rand -hex 48)"
    export JWT_SECRET
    TEMP_ENV_FILE="$(mktemp "${ENV_FILE}.XXXXXX")"
    awk -v secret="${JWT_SECRET}" '
        BEGIN { updated = 0 }
        /^JWT_SECRET=/ { print "JWT_SECRET=" secret; updated = 1; next }
        { print }
        END { if (!updated) print "JWT_SECRET=" secret }
    ' "${ENV_FILE}" > "${TEMP_ENV_FILE}"
    chmod 600 "${TEMP_ENV_FILE}"
    mv "${TEMP_ENV_FILE}" "${ENV_FILE}"
    echo -e "${GREEN}✓ Generated and securely stored the local JWT signing secret.${RESET}"
fi

if [ "${#JWT_SECRET}" -lt 32 ]; then
    echo -e "${RED}JWT_SECRET must contain at least 32 characters.${RESET}"
    exit 1
fi

REQUIRED_ENVIRONMENT_VARIABLES=(
    SPRING_DATASOURCE_PASSWORD
    JWT_SECRET
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
    echo -e "${RED}Required local configuration is incomplete.${RESET}"
    printf '  - %s\n' "${MISSING_ENVIRONMENT_VARIABLES[@]}"
    exit 1
fi

if [[ ",${SPRING_PROFILES_ACTIVE:-}," == *",google-login,"* ]]; then
    if [ -z "${GOOGLE_CLIENT_ID:-}" ] || [ -z "${GOOGLE_CLIENT_SECRET:-}" ]; then
        echo -e "${RED}The google-login profile requires GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET.${RESET}"
        exit 1
    fi
fi

# 1. PostgreSQL Database Connectivity Check
echo -e "${BOLD}${YELLOW}[1/4] Checking PostgreSQL Database Status (Port 5432)...${RESET}"
if nc -z localhost 5432 2>/dev/null || pg_isready -h localhost -p 5432 2>/dev/null; then
    echo -e "${GREEN}✓ PostgreSQL database is online and accepting connections on port 5432.${RESET}"
else
    echo -e "${RED}⚠️ WARNING: PostgreSQL database is NOT reachable on port 5432!${RESET}"
    echo -e "${YELLOW}Please ensure PostgreSQL service is running (e.g. 'brew services start postgresql@16')${RESET}"
    echo -e "${YELLOW}Spring Boot will attempt connection on startup.${RESET}"
fi

echo ""

# 2. Cleanup Existing Stale Port Listeners (8080 & 5173)
echo -e "${BOLD}${YELLOW}[2/4] Clearing stale processes on ports 8080 & 5173...${RESET}"
PORT_8080_PID=$(lsof -ti:8080 || true)
if [ -n "$PORT_8080_PID" ]; then
    echo -e "${CYAN}Terminating process on port 8080 (PID: ${PORT_8080_PID})...${RESET}"
    kill -9 $PORT_8080_PID 2>/dev/null || true
fi

PORT_5173_PID=$(lsof -ti:5173 || true)
if [ -n "$PORT_5173_PID" ]; then
    echo -e "${CYAN}Terminating process on port 5173 (PID: ${PORT_5173_PID})...${RESET}"
    kill -9 $PORT_5173_PID 2>/dev/null || true
fi

echo -e "${GREEN}✓ Ports 8080 & 5173 cleared.${RESET}"
echo ""

# 3. Graceful Process Cleanup Trap on Ctrl+C
BACKEND_PID=""
FRONTEND_PID=""

cleanup() {
    EXIT_CODE=$?
    trap - EXIT INT TERM
    echo ""
    echo -e "${BOLD}${RED}Shutting down Pathome services...${RESET}"
    if [ -n "$BACKEND_PID" ]; then
        echo -e "${YELLOW}Stopping Spring Boot Backend (PID: ${BACKEND_PID})...${RESET}"
        kill -15 "$BACKEND_PID" 2>/dev/null || kill -9 "$BACKEND_PID" 2>/dev/null || true
    fi
    if [ -n "$FRONTEND_PID" ]; then
        echo -e "${YELLOW}Stopping Vite Frontend (PID: ${FRONTEND_PID})...${RESET}"
        kill -15 "$FRONTEND_PID" 2>/dev/null || kill -9 "$FRONTEND_PID" 2>/dev/null || true
    fi
    echo -e "${GREEN}✓ All services stopped cleanly.${RESET}"
    exit "${EXIT_CODE}"
}

trap cleanup EXIT INT TERM

# 4. Launch Spring Boot Backend
echo -e "${BOLD}${YELLOW}[3/4] Starting Spring Boot Backend (http://localhost:8080)...${RESET}"
cd "${BACKEND_DIR}"
mvn spring-boot:run &
BACKEND_PID=$!
echo -e "${CYAN}Waiting for the backend to finish its startup checks...${RESET}"

BACKEND_READY=false
for ((ATTEMPT = 1; ATTEMPT <= 90; ATTEMPT += 1)); do
    if ! kill -0 "${BACKEND_PID}" 2>/dev/null; then
        wait "${BACKEND_PID}" || true
        echo -e "${RED}Backend startup failed. Review the error above.${RESET}"
        exit 1
    fi
    if curl --silent --fail --max-time 2 "http://127.0.0.1:8080/api/v1/properties" >/dev/null 2>&1; then
        BACKEND_READY=true
        break
    fi
    sleep 1
done

if [ "${BACKEND_READY}" != "true" ]; then
    echo -e "${RED}Backend did not become ready within 90 seconds.${RESET}"
    exit 1
fi

echo -e "${GREEN}✓ Backend is healthy and accepting requests.${RESET}"
echo ""

# 5. Launch Vite React Frontend
echo -e "${BOLD}${YELLOW}[4/4] Starting Vite React Frontend (http://localhost:5173)...${RESET}"
cd "${FRONTEND_DIR}"
npm run dev &
FRONTEND_PID=$!

FRONTEND_READY=false
for ((ATTEMPT = 1; ATTEMPT <= 30; ATTEMPT += 1)); do
    if ! kill -0 "${FRONTEND_PID}" 2>/dev/null; then
        wait "${FRONTEND_PID}" || true
        echo -e "${RED}Frontend startup failed. Review the error above.${RESET}"
        exit 1
    fi
    if curl --silent --fail --max-time 2 "http://127.0.0.1:5173" >/dev/null 2>&1; then
        FRONTEND_READY=true
        break
    fi
    sleep 1
done

if [ "${FRONTEND_READY}" != "true" ]; then
    echo -e "${RED}Frontend did not become ready within 30 seconds.${RESET}"
    exit 1
fi

echo -e "${GREEN}✓ Frontend is healthy and accepting requests.${RESET}"
echo ""

LOCAL_HOSTNAME="$(scutil --get LocalHostName 2>/dev/null || hostname -s)"
LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"

echo -e "${BOLD}${GREEN}"
echo "=========================================================================="
echo "🚀 PATHOME APPLICATION IS LIVE! (Your Dreams, Our Efforts)"
echo "   - Frontend (Local):   http://localhost:5173"
if [ -n "${LOCAL_HOSTNAME}" ]; then
echo "   - Frontend (Mobile):  http://${LOCAL_HOSTNAME}.local:5173"
fi
if [ -n "${LAN_IP}" ] && [ "${LAN_IP}" != "192.0.0.2" ]; then
echo "   - Frontend (Wi-Fi):   http://${LAN_IP}:5173"
fi
echo "   - Backend REST APIs:  http://localhost:8080/api/v1/properties"
echo "   - Press Ctrl+C in this terminal to stop both servers cleanly."
echo "=========================================================================="
echo -e "${RESET}"

# Keep monitoring both services. If either exits, stop the other and return a failure.
while kill -0 "${BACKEND_PID}" 2>/dev/null && kill -0 "${FRONTEND_PID}" 2>/dev/null; do
    sleep 2
done

echo -e "${RED}A Pathome service stopped unexpectedly.${RESET}"
exit 1
