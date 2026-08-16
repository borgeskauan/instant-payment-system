#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK_KAFKA_QUIESCENCE="${ROOT_DIR}/scripts/check-kafka-quiescence.sh"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

mkdir -p "$tmp_dir/fake-bin" "$tmp_dir/state"
export DOCKER_CALLS="$tmp_dir/docker-calls.log"
export SLEEP_CALLS="$tmp_dir/sleep-calls.log"
export KAFKA_TEST_STATE_DIR="$tmp_dir/state"

cat > "$tmp_dir/fake-bin/docker" <<'SH'
#!/bin/bash
set -euo pipefail

printf '%s\n' "$*" >> "$DOCKER_CALLS"

sequence_value() {
    local sequence="$1"
    local index="$2"
    local -a values
    IFS=',' read -r -a values <<< "$sequence"
    if ((index >= ${#values[@]})); then
        index=$((${#values[@]} - 1))
    fi
    printf '%s\n' "${values[index]}"
}

if [[ "$*" == *"kafka-consumer-groups"* ]]; then
    if [[ "$*" == *"--all-groups"* ]]; then
        state_file="${KAFKA_TEST_STATE_DIR}/all-groups.count"
        index=0
        if [[ -f "$state_file" ]]; then
            index="$(<"$state_file")"
        fi
        printf '%s\n' "$((index + 1))" > "$state_file"
        if [[ "${KAFKA_UNREADABLE:-false}" == true ]]; then
            echo "consumer groups unavailable" >&2
            exit 41
        fi

        printf 'GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID\n'
        if [[ "${KAFKA_NO_OFFSETS_GROUP:-}" != spi-payment-request-consumer-group ]]; then
            lag="$(sequence_value "${SPI_PAYMENT_LAGS:-0,0,0}" "$index")"
            printf 'spi-payment-request-consumer-group spi-payment-requests 0 10 %s %s consumer\n' "$((10 + lag))" "$lag"
        fi
        if [[ "${KAFKA_NO_OFFSETS_GROUP:-}" != spi-status-report-consumer-group ]]; then
            lag="$(sequence_value "${SPI_STATUS_LAGS:-0,0,0}" "$index")"
            printf 'spi-status-report-consumer-group spi-payment-status-reports 0 10 %s %s consumer\n' "$((10 + lag))" "$lag"
        fi
        if [[ "${KAFKA_NO_OFFSETS_GROUP:-}" != notification-gateway-group ]]; then
            lag="$(sequence_value "${GATEWAY_LAGS:-0,0,0}" "$index")"
            printf 'notification-gateway-group psp-notifications 0 10 %s %s consumer\n' "$((10 + lag))" "$lag"
        fi
        exit 0
    fi

    group="${!#}"
    state_file="${KAFKA_TEST_STATE_DIR}/${group}.count"
    index=0
    if [[ -f "$state_file" ]]; then
        index="$(<"$state_file")"
    fi
    printf '%s\n' "$((index + 1))" > "$state_file"

    if [[ "${KAFKA_UNREADABLE_GROUP:-}" == "$group" ]]; then
        echo "consumer group unavailable" >&2
        exit 41
    fi
    if [[ "${KAFKA_NO_OFFSETS_GROUP:-}" == "$group" ]]; then
        printf 'GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID\n'
        exit 0
    fi

    case "$group" in
        spi-payment-request-consumer-group)
            topic="spi-payment-requests"
            lag="$(sequence_value "${SPI_PAYMENT_LAGS:-0,0,0}" "$index")"
            ;;
        spi-status-report-consumer-group)
            topic="spi-payment-status-reports"
            lag="$(sequence_value "${SPI_STATUS_LAGS:-0,0,0}" "$index")"
            ;;
        notification-gateway-group)
            topic="psp-notifications"
            lag="$(sequence_value "${GATEWAY_LAGS:-0,0,0}" "$index")"
            ;;
        *)
            echo "unexpected group: $group" >&2
            exit 42
            ;;
    esac
    printf 'GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID\n'
    printf '%s %s 0 10 %s %s consumer\n' "$group" "$topic" "$((10 + lag))" "$lag"
    exit 0
fi

if [[ "$*" == *"kafka-get-offsets"* ]]; then
    topic="${!#}"
    printf '%s:0:%s\n' "$topic" "${KAFKA_TOPIC_END_OFFSET:-0}"
    exit 0
fi

echo "unexpected docker command: $*" >&2
exit 43
SH
chmod +x "$tmp_dir/fake-bin/docker"

cat > "$tmp_dir/fake-bin/sleep" <<'SH'
#!/bin/bash
set -euo pipefail
printf '%s\n' "$*" >> "$SLEEP_CALLS"
SH
chmod +x "$tmp_dir/fake-bin/sleep"

export PATH="$tmp_dir/fake-bin:$PATH"

reset_case() {
    rm -f "$tmp_dir/state"/*.count
    : > "$DOCKER_CALLS"
    : > "$SLEEP_CALLS"
}

reset_case
"$CHECK_KAFKA_QUIESCENCE" >/dev/null
if [[ "$(grep -c kafka-consumer-groups "$DOCKER_CALLS")" -ne 3 ]] \
    || [[ "$(grep -c -- --all-groups "$DOCKER_CALLS")" -ne 3 ]]; then
    echo "quiescence did not read all three groups in one call per sample" >&2
    exit 1
fi
if [[ "$(wc -l < "$SLEEP_CALLS")" -ne 2 ]] || grep -vq '^1$' "$SLEEP_CALLS"; then
    echo "quiescence did not wait one second between its three samples" >&2
    exit 1
fi

reset_case
if SPI_PAYMENT_LAGS=1,0,0 "$CHECK_KAFKA_QUIESCENCE" >/dev/null 2>&1; then
    echo "quiescence accepted nonzero lag in the first sample" >&2
    exit 1
fi
if [[ -s "$SLEEP_CALLS" ]]; then
    echo "quiescence slept after the first sample already failed" >&2
    exit 1
fi

reset_case
if SPI_STATUS_LAGS=0,2,0 "$CHECK_KAFKA_QUIESCENCE" >/dev/null 2>&1; then
    echo "quiescence accepted nonzero lag in the second sample" >&2
    exit 1
fi
if [[ "$(wc -l < "$SLEEP_CALLS")" -ne 1 ]]; then
    echo "quiescence did not stop after the second sample failed" >&2
    exit 1
fi

reset_case
if KAFKA_UNREADABLE=true \
    "$CHECK_KAFKA_QUIESCENCE" >/dev/null 2>&1; then
    echo "quiescence accepted unreadable consumer groups" >&2
    exit 1
fi

reset_case
KAFKA_NO_OFFSETS_GROUP=spi-payment-request-consumer-group \
    KAFKA_TOPIC_END_OFFSET=0 \
    "$CHECK_KAFKA_QUIESCENCE" >/dev/null
if [[ "$(grep -c kafka-get-offsets "$DOCKER_CALLS")" -ne 3 ]]; then
    echo "quiescence did not inspect the fresh topic end offset" >&2
    exit 1
fi

reset_case
if KAFKA_NO_OFFSETS_GROUP=spi-payment-request-consumer-group \
    KAFKA_TOPIC_END_OFFSET=1 \
    "$CHECK_KAFKA_QUIESCENCE" >/dev/null 2>&1; then
    echo "quiescence accepted an unconsumed fresh topic" >&2
    exit 1
fi
