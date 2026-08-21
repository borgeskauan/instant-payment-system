#!/bin/bash

set -euo pipefail

readonly LOAD_TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "${LOAD_TEST_DIR}/.." && pwd)"
readonly COMPOSE_FILE="${REPOSITORY_ROOT}/infra/docker-compose.yml"
readonly STACK_READINESS_SCRIPT="${STACK_READINESS_SCRIPT:-${LOAD_TEST_DIR}/scripts/wait-for-performance-stack.sh}"

usage() {
    echo "Usage: $(basename "$0")"
}

log_phase() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

parse_args() {
    if [[ $# -eq 0 ]]; then
        return 0
    fi
    if [[ $# -eq 1 && ("$1" == -h || "$1" == --help) ]]; then
        usage
        exit 0
    fi
    usage >&2
    return 2
}

validate_dependencies() {
    if ! command -v docker >/dev/null 2>&1; then
        echo "docker is required." >&2
        return 1
    fi
    if [[ ! -x "$STACK_READINESS_SCRIPT" ]]; then
        echo "Performance stack readiness script does not exist or is not executable: $STACK_READINESS_SCRIPT" >&2
        return 1
    fi
}

reset_and_start_stack() {
    local local_uid local_gid
    local_uid="$(id -u)"
    local_gid="$(id -g)"

    log_phase "removing the previous performance stack and volumes"
    if ! docker compose -f "$COMPOSE_FILE" down -v --remove-orphans; then
        return 1
    fi

    log_phase "building and starting the performance stack"
    if ! LOCAL_UID="$local_uid" LOCAL_GID="$local_gid" \
        docker compose -f "$COMPOSE_FILE" up -d --build; then
        return 1
    fi
}

main() {
    parse_args "$@" || return $?
    validate_dependencies || return 1
    reset_and_start_stack || return 1

    log_phase "waiting for the performance stack to become ready"
    "$STACK_READINESS_SCRIPT" || return 1
    log_phase "performance environment is ready"
    echo "Start a measured run with:"
    echo "  ./run-load-test.sh --profile mixed-outcomes-2k-diagnostic <run-tag>"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
