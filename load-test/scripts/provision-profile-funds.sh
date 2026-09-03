#!/bin/bash

set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PARTICIPANTS_SCRIPT="${PARTICIPANTS_SCRIPT:-${SCRIPT_DIR}/execution-plan-participants.py}"
readonly SPI_BASE_URL="${SPI_BASE_URL:-http://localhost:8002}"

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
    if ! command -v curl >/dev/null 2>&1; then
        echo "curl is required." >&2
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

provision_ispb() {
    local ispb="$1" balance="$2" reset_if_exists="$3"
    local body status curl_status
    body="$(printf '{"balance":%s,"resetIfExists":%s}' "$balance" "$reset_if_exists")"

    if status="$(curl -s -o /dev/null -w '%{http_code}' \
            -X PUT "${SPI_BASE_URL}/internal/funds/${ispb}" \
            -H 'Content-Type: application/json' \
            -d "$body")"; then
        :
    else
        curl_status=$?
        echo "Failed to call SPI while provisioning ISPB $ispb." >&2
        return "$curl_status"
    fi
    if [[ "$status" != 204 ]]; then
        echo "Failed to provision ISPB $ispb. HTTP status: $status" >&2
        return 1
    fi
}

provision_role() {
    local pair_number_start="$1" pair_count="$2" role_prefix="$3" balance="$4" reset_if_exists="$5"
    local pair_number suffix

    for ((pair_number = pair_number_start; pair_number < pair_number_start + pair_count; pair_number++)); do
        suffix="$(printf '%06d' "$pair_number")"
        provision_ispb "${role_prefix}${suffix}" "$balance" "$reset_if_exists"
    done
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
