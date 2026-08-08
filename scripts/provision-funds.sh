#!/bin/bash

set -euo pipefail

readonly SCRIPT_NAME="$(basename "$0")"

BASE_URL="http://localhost:8002"
VUS=50
VUS_WAS_SET=false
BALANCE="1000000000"
RESET_IF_EXISTS=true
ISPBS=()

usage() {
    echo "Usage: $SCRIPT_NAME [--base-url URL] [--vus N | --ispb ISPB...] [--balance AMOUNT] [--reset-if-exists|--preserve-if-exists]"
    echo
    echo "Options:"
    echo "  --base-url URL          SPI base URL (default: $BASE_URL)"
    echo "  --vus N                 Number of VUs to provision as payer/receiver pairs (default: $VUS)"
    echo "  --ispb ISPB             Provision an explicit 8-digit ISPB; may be repeated"
    echo "  --balance AMOUNT        Balance used for provisioned accounts (default: $BALANCE)"
    echo "  --reset-if-exists       Reset existing accounts to the configured balance (default)"
    echo "  --preserve-if-exists    Keep existing balances when accounts already exist"
    echo
    echo "Examples:"
    echo "  $SCRIPT_NAME --vus 50 --balance 1000000000"
    echo "  $SCRIPT_NAME --ispb 11111111 --ispb 22222222 --balance 1000 --preserve-if-exists"
}

error_exit() {
    echo "Error: $1" >&2
    exit 1
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --base-url)
                BASE_URL="${2:-}"
                [[ -n "$BASE_URL" ]] || error_exit "--base-url requires a value"
                shift 2
                ;;
            --vus)
                VUS="${2:-}"
                [[ "$VUS" =~ ^[0-9]+$ ]] || error_exit "--vus requires a positive integer"
                [[ "$VUS" -gt 0 ]] || error_exit "--vus must be greater than zero"
                VUS_WAS_SET=true
                shift 2
                ;;
            --ispb)
                local ispb="${2:-}"
                [[ "$ispb" =~ ^[0-9]{8}$ ]] || error_exit "--ispb requires exactly 8 digits"
                ISPBS+=("$ispb")
                shift 2
                ;;
            --balance)
                BALANCE="${2:-}"
                [[ "$BALANCE" =~ ^[0-9]+([.][0-9]+)?$ ]] || error_exit "--balance requires a non-negative number"
                shift 2
                ;;
            --reset-if-exists)
                RESET_IF_EXISTS=true
                shift
                ;;
            --preserve-if-exists)
                RESET_IF_EXISTS=false
                shift
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            *)
                usage
                error_exit "Unknown option: $1"
                ;;
        esac
    done

    if [[ "${#ISPBS[@]}" -gt 0 ]] && $VUS_WAS_SET; then
        error_exit "--vus cannot be combined with --ispb"
    fi
}

require_curl() {
    if ! command -v curl &>/dev/null; then
        error_exit "curl not found"
    fi
}

provision_ispb() {
    local ispb="$1"
    local body

    body=$(printf '{"balance":%s,"resetIfExists":%s}' "$BALANCE" "$RESET_IF_EXISTS")

    local status
    if ! status=$(curl -s -o /dev/null -w "%{http_code}" \
        -X PUT "$BASE_URL/internal/funds/$ispb" \
        -H "Content-Type: application/json" \
        -d "$body"); then
        error_exit "Failed to call SPI while provisioning ISPB $ispb"
    fi

    if [[ "$status" != "204" ]]; then
        error_exit "Failed to provision ISPB $ispb. HTTP status: $status"
    fi
}

main() {
    parse_args "$@"
    require_curl

    echo "Provisioning funds through SPI admin API..."
    echo "SPI base URL: $BASE_URL"
    echo "Balance: $BALANCE"
    echo "Reset if exists: $RESET_IF_EXISTS"

    if [[ "${#ISPBS[@]}" -gt 0 ]]; then
        echo "Explicit ISPBs: ${ISPBS[*]}"
        for ispb in "${ISPBS[@]}"; do
            provision_ispb "$ispb"
        done

        echo "Provisioned ${#ISPBS[@]} settlement account(s)."
        return
    fi

    echo "VUs: $VUS"
    for vu in $(seq 1 "$VUS"); do
        suffix=$(printf "%06d" "$vu")
        provision_ispb "10${suffix}"
        provision_ispb "20${suffix}"
    done

    echo "Provisioned $((VUS * 2)) settlement accounts."
}

main "$@"
