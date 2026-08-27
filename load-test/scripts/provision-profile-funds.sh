#!/bin/bash

set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
readonly PARTICIPANTS_SCRIPT="${PARTICIPANTS_SCRIPT:-${SCRIPT_DIR}/execution-plan-participants.py}"
readonly PROVISION_FUNDS_SCRIPT="${PROVISION_FUNDS_SCRIPT:-${REPOSITORY_ROOT}/scripts/provision-funds.sh}"

EXECUTION_PLAN=""
PROVISIONING_RECORDS=()

usage() {
    echo "Usage: $(basename "$0") --execution-plan FILE"
}

parse_args() {
    if [[ $# -ne 2 || "$1" != --execution-plan || -z "$2" ]]; then
        usage >&2
        return 2
    fi
    EXECUTION_PLAN="$2"
}

validate_inputs() {
    if [[ ! -f "$EXECUTION_PLAN" ]]; then
        echo "Execution plan does not exist or is not a regular file: $EXECUTION_PLAN" >&2
        return 1
    fi
    EXECUTION_PLAN="$(cd "$(dirname "$EXECUTION_PLAN")" && pwd)/$(basename "$EXECUTION_PLAN")"
    if [[ ! -x "$PARTICIPANTS_SCRIPT" ]]; then
        echo "Execution-plan participant reader is not executable: $PARTICIPANTS_SCRIPT" >&2
        return 1
    fi
    if [[ ! -x "$PROVISION_FUNDS_SCRIPT" ]]; then
        echo "Fund provisioning adapter is not executable: $PROVISION_FUNDS_SCRIPT" >&2
        return 1
    fi
}

load_records() {
    local normalized
    if ! normalized="$("$PARTICIPANTS_SCRIPT" "$EXECUTION_PLAN")"; then
        return 1
    fi
    mapfile -t PROVISIONING_RECORDS <<< "$normalized"
}

provision_role() {
    local pair_number_start="$1"
    local pair_count="$2"
    local role_prefix="$3"
    local balance="$4"
    local reset_if_exists="$5"
    local pair_number suffix
    local -a args=(--balance "$balance")

    if [[ "$reset_if_exists" == true ]]; then
        args+=(--reset-if-exists)
    else
        args+=(--preserve-if-exists)
    fi
    for ((pair_number = pair_number_start; pair_number < pair_number_start + pair_count; pair_number++)); do
        suffix="$(printf '%06d' "$pair_number")"
        args+=(--ispb "${role_prefix}${suffix}")
    done
    "$PROVISION_FUNDS_SCRIPT" "${args[@]}"
}

provision_all() {
    local record first hot cold payer receiver reset count
    for record in "${PROVISIONING_RECORDS[@]}"; do
        IFS=$'\t' read -r first hot cold payer receiver reset <<< "$record"
        count=$((hot + cold))
        provision_role "$first" "$count" 10 "$payer" "$reset"
        provision_role "$first" "$count" 20 "$receiver" "$reset"
    done
}

main() {
    parse_args "$@"
    validate_inputs
    load_records
    provision_all
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
