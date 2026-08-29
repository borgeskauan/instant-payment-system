#!/bin/bash

set -euo pipefail

readonly LOAD_TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "${LOAD_TEST_DIR}/.." && pwd)"
readonly COMPOSE_FILE="${REPOSITORY_ROOT}/infra/docker-compose.yml"
readonly LOADTOOL_PROFILES_DIR="${LOADTOOL_PROFILES_DIR:-${LOAD_TEST_DIR}/profiles}"
readonly PREPARED_ENVIRONMENT_ROOT="${PREPARED_ENVIRONMENT_ROOT:-${LOAD_TEST_DIR}/.prepared-environment}"
readonly PREPARED_ENVIRONMENT_DIR="${PREPARED_ENVIRONMENT_ROOT}/current"
readonly PREPARED_ENVIRONMENT_STAGING_DIR="${PREPARED_ENVIRONMENT_ROOT}/.staging"
readonly RUST_LOADTOOL_TARGET_DIR="${RUST_LOADTOOL_TARGET_DIR:-/tmp/rust-loadtool-target}"
readonly STACK_READINESS_SCRIPT="${STACK_READINESS_SCRIPT:-${LOAD_TEST_DIR}/scripts/wait-for-performance-stack.sh}"
readonly PROVISION_PROFILE_FUNDS_SCRIPT="${PROVISION_PROFILE_FUNDS_SCRIPT:-${LOAD_TEST_DIR}/scripts/provision-profile-funds.sh}"
readonly PARTICIPANTS_SCRIPT="${PARTICIPANTS_SCRIPT:-${LOAD_TEST_DIR}/scripts/execution-plan-participants.py}"
readonly LOADTOOL_CERT_SCRIPT="${LOADTOOL_CERT_SCRIPT:-${REPOSITORY_ROOT}/infra/certs/generate-local-mtls-certs.sh}"
readonly LOADTOOL_CA_CERT="${LOADTOOL_CA_CERT:-${REPOSITORY_ROOT}/infra/certs/local/ca/ca.crt}"

PROFILE_NAME="uniform-smoke"
PROFILE_PATH=""
LOADTOOL_BIN=""

usage() {
    cat <<EOF
Usage: $(basename "$0") [--profile NAME]

Prepare a fresh stack, funds, and certificates for one load-test profile.
The default profile is uniform-smoke.
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
            -h|--help)
                usage
                exit 0
                ;;
            *)
                echo "Unknown argument: $1" >&2
                usage >&2
                return 2
                ;;
        esac
    done
    if [[ ! "$PROFILE_NAME" =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
        echo "Invalid profile name '$PROFILE_NAME'." >&2
        return 2
    fi
    PROFILE_PATH="${LOADTOOL_PROFILES_DIR}/${PROFILE_NAME}.json"
    if [[ ! -f "$PROFILE_PATH" ]]; then
        echo "Profile '$PROFILE_NAME' not found." >&2
        return 2
    fi
}

validate_dependencies() {
    command -v cargo >/dev/null 2>&1 || { echo "cargo is required." >&2; return 1; }
    command -v docker >/dev/null 2>&1 || { echo "docker is required." >&2; return 1; }
    for script in "$STACK_READINESS_SCRIPT" "$PROVISION_PROFILE_FUNDS_SCRIPT" "$PARTICIPANTS_SCRIPT" "$LOADTOOL_CERT_SCRIPT"; do
        if [[ ! -x "$script" ]]; then
            echo "Required script is not executable: $script" >&2
            return 1
        fi
    done
}

cleanup() {
    local status=$?
    trap - EXIT INT TERM
    if [[ -e "$PREPARED_ENVIRONMENT_STAGING_DIR" ]]; then
        rm -rf "$PREPARED_ENVIRONMENT_STAGING_DIR"
    fi
    exit "$status"
}

build_and_validate_profile() {
    log_phase "building Rust load-tool"
    cargo build \
        --locked \
        --release \
        --manifest-path "${LOAD_TEST_DIR}/rust-loadtool/Cargo.toml" \
        --target-dir "$RUST_LOADTOOL_TARGET_DIR"
    LOADTOOL_BIN="${RUST_LOADTOOL_TARGET_DIR}/release/rust-loadtool"
    if [[ ! -x "$LOADTOOL_BIN" ]]; then
        echo "Rust load-tool binary was not produced: $LOADTOOL_BIN" >&2
        return 1
    fi

    mkdir -p "${PREPARED_ENVIRONMENT_STAGING_DIR}/inputs"
    cp "$PROFILE_PATH" "${PREPARED_ENVIRONMENT_STAGING_DIR}/inputs/profile.json"
    log_phase "validating profile $PROFILE_NAME"
    (
        cd "${LOAD_TEST_DIR}/rust-loadtool"
        "$LOADTOOL_BIN" validate-profile --profile "$PROFILE_NAME"
    ) > "${PREPARED_ENVIRONMENT_STAGING_DIR}/inputs/execution-plan.json"
}

reset_and_start_stack() {
    local local_uid local_gid
    local_uid="$(id -u)"
    local_gid="$(id -g)"

    log_phase "removing the previous performance stack and volumes"
    docker compose -f "$COMPOSE_FILE" down -v --remove-orphans
    log_phase "building and starting the performance stack"
    LOCAL_UID="$local_uid" LOCAL_GID="$local_gid" \
        docker compose -f "$COMPOSE_FILE" up -d --build
}

capture_spi_runtime_configuration() {
    local configuration
    log_phase "capturing the effective SPI runtime configuration"
    if ! configuration="$(
        docker logs spi 2>&1 \
            | awk '/event=spi_runtime_configuration/{line=$0} END{if(line == "") exit 1; print line}'
    )"; then
        echo "SPI effective runtime configuration was not found in the startup log." >&2
        return 1
    fi
    printf '%s\n' "$configuration" > "${PREPARED_ENVIRONMENT_STAGING_DIR}/inputs/spi-runtime-config.log"
}

generate_certificates() {
    local plan="${PREPARED_ENVIRONMENT_STAGING_DIR}/inputs/execution-plan.json"
    local normalized first hot cold count pair suffix

    if [[ ! -f "$LOADTOOL_CA_CERT" ]]; then
        echo "Local mTLS CA not found after stack startup: $LOADTOOL_CA_CERT" >&2
        return 1
    fi
    if ! normalized="$("$PARTICIPANTS_SCRIPT" "$plan")"; then
        return 1
    fi
    mkdir -p "${PREPARED_ENVIRONMENT_STAGING_DIR}/certs"
    while IFS=$'\t' read -r first hot cold _; do
        count=$((hot + cold))
        for ((pair = first; pair < first + count; pair++)); do
            suffix="$(printf '%06d' "$pair")"
            "$LOADTOOL_CERT_SCRIPT" --psp-root "${PREPARED_ENVIRONMENT_STAGING_DIR}/certs" psp "10${suffix}" >/dev/null
            "$LOADTOOL_CERT_SCRIPT" --psp-root "${PREPARED_ENVIRONMENT_STAGING_DIR}/certs" psp "20${suffix}" >/dev/null
        done
    done <<< "$normalized"
}

main() {
    parse_args "$@"
    validate_dependencies
    mkdir -p "$PREPARED_ENVIRONMENT_ROOT"
    trap cleanup EXIT
    trap 'exit 130' INT TERM
    rm -rf "$PREPARED_ENVIRONMENT_DIR" "$PREPARED_ENVIRONMENT_STAGING_DIR"

    build_and_validate_profile
    reset_and_start_stack
    log_phase "waiting for the performance stack to become ready"
    "$STACK_READINESS_SCRIPT"
    capture_spi_runtime_configuration
    log_phase "provisioning profile funds"
    "$PROVISION_PROFILE_FUNDS_SCRIPT" --execution-plan "${PREPARED_ENVIRONMENT_STAGING_DIR}/inputs/execution-plan.json"
    log_phase "generating PSP certificates"
    generate_certificates
    mv "$PREPARED_ENVIRONMENT_STAGING_DIR" "$PREPARED_ENVIRONMENT_DIR"
    log_phase "prepared environment published: ${PREPARED_ENVIRONMENT_DIR}"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
