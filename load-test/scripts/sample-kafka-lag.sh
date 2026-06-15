#!/bin/bash

set -euo pipefail

readonly KAFKA_CONTAINER="kafka"
readonly BOOTSTRAP_SERVER="kafka:9092"
readonly DEFAULT_INTERVAL_SECONDS=2
readonly DEFAULT_OUTPUT_FILE="kafka-lag.csv"
readonly KAFKA_CONSUMER_GROUPS=(
    "spi-consumer-group"
    "notification-gateway-group"
)

usage() {
    echo "Usage: $(basename "$0") [interval-seconds] [duration-seconds] [output-file]"
    echo
    echo "Examples:"
    echo "  $(basename "$0")"
    echo "  $(basename "$0") 2 120 results/kafka-lag.csv"
    echo
    echo "Columns:"
    echo "  timestamp,group,topic,partition,current_offset,log_end_offset,lag"
}

error_exit() {
    echo "Error: $1" >&2
    exit 1
}

validate_positive_integer() {
    local name="$1"
    local value="$2"

    if [[ ! "$value" =~ ^[1-9][0-9]*$ ]]; then
        error_exit "$name must be a positive integer."
    fi
}

check_requirements() {
    if ! command -v docker &>/dev/null; then
        error_exit "docker not found."
    fi

    if ! docker ps --format '{{.Names}}' | grep -qx "$KAFKA_CONTAINER"; then
        error_exit "Kafka container '$KAFKA_CONTAINER' is not running."
    fi
}

write_header() {
    local output_file="$1"
    echo "timestamp,group,topic,partition,current_offset,log_end_offset,lag" > "$output_file"
}

sample_group_lag() {
    local group="$1"
    local timestamp="$2"
    local output_file="$3"

    docker exec "$KAFKA_CONTAINER" kafka-consumer-groups \
        --bootstrap-server "$BOOTSTRAP_SERVER" \
        --describe \
        --group "$group" 2>/dev/null |
        awk -v timestamp="$timestamp" '
            $1 == "GROUP" { next }
            NF >= 6 && $3 ~ /^[0-9]+$/ {
                printf "%s,%s,%s,%s,%s,%s,%s\n", timestamp, $1, $2, $3, $4, $5, $6
            }
        ' >> "$output_file"
}

sample_once() {
    local output_file="$1"
    local timestamp
    timestamp=$(date --iso-8601=seconds)

    for group in "${KAFKA_CONSUMER_GROUPS[@]}"; do
        sample_group_lag "$group" "$timestamp" "$output_file"
    done
}

main() {
    if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
        usage
        exit 0
    fi

    local interval_seconds="${1:-$DEFAULT_INTERVAL_SECONDS}"
    local duration_seconds="${2:-}"
    local output_file="${3:-$DEFAULT_OUTPUT_FILE}"

    validate_positive_integer "interval-seconds" "$interval_seconds"

    if [[ -n "$duration_seconds" ]]; then
        validate_positive_integer "duration-seconds" "$duration_seconds"
    fi

    check_requirements
    mkdir -p "$(dirname "$output_file")"
    write_header "$output_file"

    echo "Sampling Kafka lag every ${interval_seconds}s into ${output_file}"
    echo "Groups: ${KAFKA_CONSUMER_GROUPS[*]}"

    local started_at
    started_at=$(date +%s)

    while true; do
        sample_once "$output_file"

        if [[ -n "$duration_seconds" ]]; then
            local now
            now=$(date +%s)
            if (( now - started_at >= duration_seconds )); then
                break
            fi
        fi

        sleep "$interval_seconds"
    done
}

main "$@"
