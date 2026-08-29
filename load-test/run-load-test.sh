#!/bin/bash

set -euo pipefail

readonly LOAD_TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly RESULTS_DIR="${RESULTS_DIR:-${LOAD_TEST_DIR}/results}"
readonly LOADTOOL_PROFILES_DIR="${LOADTOOL_PROFILES_DIR:-${LOAD_TEST_DIR}/profiles}"
readonly PREPARED_ENVIRONMENT_ROOT="${PREPARED_ENVIRONMENT_ROOT:-${LOAD_TEST_DIR}/.prepared-environment}"
readonly RUST_LOADTOOL_TARGET_DIR="${RUST_LOADTOOL_TARGET_DIR:-/tmp/rust-loadtool-target}"
readonly DIAGNOSTICS_SCRIPT="${DIAGNOSTICS_SCRIPT:-${LOAD_TEST_DIR}/scripts/diagnostics/run-diagnostics.sh}"

PROFILE_NAME="uniform-smoke"
RUN_TAG=""
ENABLE_JFR=true
ENABLE_SYSTEM_DIAGNOSTICS=true
PREPARED_DIR=""
RESULT_DIR=""
LOADTOOL_BIN=""

usage() {
    cat <<EOF
Usage: $(basename "$0") [--profile NAME] [--no-jfr] [--no-system-diagnostics] RUN_TAG

Run a previously prepared workload. The default profile is uniform-smoke.
EOF
}

log_phase() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --profile)
                if [[ $# -lt 2 || -z "$2" ]]; then
                    echo "--profile requires a profile name." >&2
                    return 2
                fi
                PROFILE_NAME="$2"
                shift 2
                ;;
            --no-jfr) ENABLE_JFR=false; shift ;;
            --no-system-diagnostics) ENABLE_SYSTEM_DIAGNOSTICS=false; shift ;;
            -h|--help)
                usage
                exit 0
                ;;
            --*)
                echo "Unknown option: $1" >&2
                return 2
                ;;
            *)
                if [[ -n "$RUN_TAG" ]]; then
                    echo "Only one run tag is allowed." >&2
                    return 2
                fi
                RUN_TAG="$1"
                shift
                ;;
        esac
    done
    if [[ -z "$RUN_TAG" ]]; then
        echo "Run tag is required." >&2
        return 2
    fi
    if [[ ! "$PROFILE_NAME" =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
        echo "Invalid profile name '$PROFILE_NAME'." >&2
        return 2
    fi
}

resolve_prepared_environment() {
    local profile_path="${LOADTOOL_PROFILES_DIR}/${PROFILE_NAME}.json"
    PREPARED_DIR="${PREPARED_ENVIRONMENT_ROOT}/current"
    if [[ ! -d "$PREPARED_DIR" ]]; then
        echo "Prepared environment does not exist: $PREPARED_DIR" >&2
        return 2
    fi
    if [[ ! -f "$profile_path" ]]; then
        echo "Profile '$PROFILE_NAME' not found." >&2
        return 2
    fi
    for input in profile.json execution-plan.json spi-runtime-config.log; do
        if [[ ! -f "$PREPARED_DIR/inputs/$input" ]]; then
            echo "Prepared environment is missing inputs/$input: $PREPARED_DIR" >&2
            return 2
        fi
    done
    if [[ ! -d "$PREPARED_DIR/certs" ]]; then
        echo "Prepared environment is missing certs: $PREPARED_DIR" >&2
        return 2
    fi
    if ! cmp -s "$profile_path" "$PREPARED_DIR/inputs/profile.json"; then
        echo "Prepared environment does not match profile '$PROFILE_NAME'. Run prepare-performance-environment.sh again." >&2
        return 2
    fi
    if [[ ! -x "$DIAGNOSTICS_SCRIPT" ]]; then
        echo "Diagnostic wrapper is not executable: $DIAGNOSTICS_SCRIPT" >&2
        return 2
    fi
}

build_loadtool() {
    log_phase "building Rust load-tool"
    if ! cargo build \
        --locked \
        --release \
        --manifest-path "${LOAD_TEST_DIR}/rust-loadtool/Cargo.toml" \
        --target-dir "$RUST_LOADTOOL_TARGET_DIR"; then
        return 2
    fi
    LOADTOOL_BIN="${RUST_LOADTOOL_TARGET_DIR}/release/rust-loadtool"
    if [[ ! -x "$LOADTOOL_BIN" ]]; then
        echo "Rust load-tool binary was not produced: $LOADTOOL_BIN" >&2
        return 2
    fi
}

create_result_bundle() {
    local timestamp
    timestamp="$(date '+%Y%m%d_%H%M%S')"
    RESULT_DIR="${RESULTS_DIR}/${RUN_TAG}/${timestamp}"
    if [[ -e "$RESULT_DIR" ]]; then
        echo "Result directory already exists: $RESULT_DIR" >&2
        return 2
    fi
    mkdir -p "$RESULT_DIR/inputs" "$RESULT_DIR/logs" "$RESULT_DIR/diagnostics"
    cp "$PREPARED_DIR/inputs/profile.json" "$RESULT_DIR/inputs/profile.json"
    cp "$PREPARED_DIR/inputs/execution-plan.json" "$RESULT_DIR/inputs/execution-plan.json"
    cp "$PREPARED_DIR/inputs/spi-runtime-config.log" "$RESULT_DIR/inputs/spi-runtime-config.log"
    RESULT_DIR="$(cd "$RESULT_DIR" && pwd)"
}

run_prepared_workload() {
    local -a diagnostics=(run --run-dir "$RESULT_DIR")
    [[ "$ENABLE_JFR" == true ]] || diagnostics+=(--no-jfr)
    [[ "$ENABLE_SYSTEM_DIAGNOSTICS" == true ]] || diagnostics+=(--no-system-diagnostics)
    diagnostics+=(-- "$LOADTOOL_BIN" run --run-dir "$RESULT_DIR" --client-cert-root "$PREPARED_DIR/certs")

    log_phase "starting prepared workload: profile=$PROFILE_NAME result=$RESULT_DIR"
    (
        cd "${LOAD_TEST_DIR}/rust-loadtool"
        "$DIAGNOSTICS_SCRIPT" "${diagnostics[@]}"
    )
}

main() {
    parse_args "$@" || return $?
    resolve_prepared_environment || return $?
    build_loadtool || return 2
    create_result_bundle || return $?

    local status=0
    run_prepared_workload || status=$?
    log_phase "load-test result: $RESULT_DIR"
    return "$status"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
