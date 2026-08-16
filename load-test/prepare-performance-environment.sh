#!/bin/bash

set -euo pipefail

readonly LOAD_TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "${LOAD_TEST_DIR}/.." && pwd)"
readonly COMPOSE_FILE="${REPOSITORY_ROOT}/infra/docker-compose.yml"
readonly RUN_LOAD_TEST_SCRIPT="${RUN_LOAD_TEST_SCRIPT:-${LOAD_TEST_DIR}/run-load-test.sh}"
readonly STACK_READINESS_SCRIPT="${STACK_READINESS_SCRIPT:-${LOAD_TEST_DIR}/scripts/wait-for-performance-stack.sh}"
readonly KAFKA_QUIESCENCE_SCRIPT="${KAFKA_QUIESCENCE_SCRIPT:-${LOAD_TEST_DIR}/scripts/check-kafka-quiescence.sh}"
readonly SMOKE_QUALIFIER_SCRIPT="${SMOKE_QUALIFIER_SCRIPT:-${LOAD_TEST_DIR}/scripts/qualify-smoke-report.py}"
readonly PERFORMANCE_RESULTS_DIR="${PERFORMANCE_RESULTS_DIR:-${LOAD_TEST_DIR}/results}"
readonly SLEEP_COMMAND="${SLEEP_COMMAND:-sleep}"
readonly PREPARATION_RUN_ID="${PREPARATION_RUN_ID:-$(date +%Y%m%d_%H%M%S)-$$}"
readonly MAX_SMOKE_ATTEMPTS=3
readonly RETRYABLE_SMOKE_EXIT=10
readonly INVALID_SMOKE_EXIT=20

DIAGNOSTIC_ARGS=()

usage() {
    echo "Usage: $(basename "$0") [--no-jfr] [--no-spi-trace] [--no-postgres-statements]"
}

log_phase() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --no-jfr|--no-spi-trace|--no-postgres-statements)
                DIAGNOSTIC_ARGS+=("$1")
                shift
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            *)
                usage >&2
                echo "Unknown option: $1" >&2
                return 2
                ;;
        esac
    done
}

validate_dependencies() {
    local dependency
    for dependency in \
        "$RUN_LOAD_TEST_SCRIPT" \
        "$STACK_READINESS_SCRIPT" \
        "$KAFKA_QUIESCENCE_SCRIPT" \
        "$SMOKE_QUALIFIER_SCRIPT" \
        "$SLEEP_COMMAND"; do
        if [[ ! -x "$dependency" ]] && ! command -v "$dependency" >/dev/null 2>&1; then
            echo "Required performance-environment dependency is not executable: $dependency" >&2
            return 1
        fi
    done
}

reset_and_start_stack() {
    local local_uid local_gid
    local_uid="$(id -u)"
    local_gid="$(id -g)"

    log_phase "removing prior load-test containers and volumes"
    if ! LOCAL_UID="$local_uid" LOCAL_GID="$local_gid" \
        docker compose -f "$COMPOSE_FILE" down -v --remove-orphans; then
        return 1
    fi

    log_phase "building and starting the performance stack"
    LOCAL_UID="$local_uid" LOCAL_GID="$local_gid" \
        docker compose -f "$COMPOSE_FILE" up -d --build
}

resolve_attempt_run_dir() {
    local attempt_tag="$1"
    local tag_dir="${PERFORMANCE_RESULTS_DIR}/${attempt_tag}"
    local -a run_dirs=()

    if [[ -d "$tag_dir" ]]; then
        mapfile -t run_dirs < <(find "$tag_dir" -mindepth 1 -maxdepth 1 -type d -print | sort)
    fi
    if [[ "${#run_dirs[@]}" -ne 1 ]]; then
        echo "Smoke attempt ${attempt_tag} produced ${#run_dirs[@]} result directories; expected exactly one." >&2
        return 1
    fi
    printf '%s\n' "${run_dirs[0]}"
}

run_smoke_attempts() {
    local attempt attempt_tag run_dir
    local runner_status qualifier_status

    for ((attempt = 1; attempt <= MAX_SMOKE_ATTEMPTS; attempt++)); do
        attempt_tag="environment-setup-${PREPARATION_RUN_ID}-attempt-${attempt}"
        log_phase "running functional warmup smoke: attempt=${attempt}/${MAX_SMOKE_ATTEMPTS} tag=${attempt_tag}"

        if (
            cd "$LOAD_TEST_DIR"
            "$RUN_LOAD_TEST_SCRIPT" \
                --profile mixed-outcomes-smoke \
                "${DIAGNOSTIC_ARGS[@]}" \
                "$attempt_tag"
        ); then
            runner_status=0
        else
            runner_status=$?
        fi

        if ((runner_status != 0 && runner_status != 1)); then
            echo "Smoke runner failed operationally with status ${runner_status}." >&2
            return 1
        fi
        if ! run_dir="$(resolve_attempt_run_dir "$attempt_tag")"; then
            return 1
        fi

        if "$SMOKE_QUALIFIER_SCRIPT" "$run_dir"; then
            qualifier_status=0
        else
            qualifier_status=$?
        fi
        case "$qualifier_status" in
            0)
                log_phase "functional warmup smoke qualified: ${run_dir}"
                return 0
                ;;
            "$RETRYABLE_SMOKE_EXIT")
                if ((attempt == MAX_SMOKE_ATTEMPTS)); then
                    echo "Functional smoke remained partial after ${MAX_SMOKE_ATTEMPTS} attempts." >&2
                    return 1
                fi
                log_phase "functional smoke was correct but partial; retrying"
                ;;
            "$INVALID_SMOKE_EXIT")
                echo "Functional smoke failed qualification; refusing to retry a correctness failure." >&2
                return 1
                ;;
            *)
                echo "Smoke qualifier returned unsupported status ${qualifier_status}." >&2
                return 1
                ;;
        esac
    done
}

main() {
    if ! parse_args "$@"; then
        return 2
    fi
    if ! validate_dependencies; then
        return 1
    fi
    if ! reset_and_start_stack; then
        return 1
    fi

    log_phase "waiting for the performance stack to become ready"
    if ! "$STACK_READINESS_SCRIPT"; then
        return 1
    fi
    log_phase "performance stack is ready; waiting 10s before warmup"
    "$SLEEP_COMMAND" 10

    if ! run_smoke_attempts; then
        return 1
    fi

    log_phase "waiting 10s for asynchronous work to settle"
    "$SLEEP_COMMAND" 10
    log_phase "checking post-smoke Kafka quiescence"
    if ! "$KAFKA_QUIESCENCE_SCRIPT"; then
        return 1
    fi

    log_phase "performance environment is qualified and remains running"
    echo "Start measured runs with:"
    echo "  ./run-load-test.sh --profile mixed-outcomes-2k-diagnostic <run-tag>"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
