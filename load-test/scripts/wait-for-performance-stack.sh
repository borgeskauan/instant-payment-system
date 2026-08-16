#!/bin/bash

set -euo pipefail

readonly READINESS_TIMEOUT_SECONDS="${READINESS_TIMEOUT_SECONDS:-120}"
readonly READINESS_POLL_SECONDS="${READINESS_POLL_SECONDS:-2}"
readonly KAFKA_CONTAINER="${KAFKA_CONTAINER:-kafka}"
readonly KAFKA_BOOTSTRAP_SERVER="${KAFKA_BOOTSTRAP_SERVER:-kafka:9092}"
readonly KAFKA_CLI_TIMEOUT_SECONDS="${KAFKA_CLI_TIMEOUT_SECONDS:-5}"
readonly SPI_PAYMENT_REQUEST_CONSUMER_GROUP="${SPI_PAYMENT_REQUEST_CONSUMER_GROUP:-spi-payment-request-consumer-group}"
readonly SPI_STATUS_REPORT_CONSUMER_GROUP="${SPI_STATUS_REPORT_CONSUMER_GROUP:-spi-status-report-consumer-group}"
readonly NOTIFICATION_GATEWAY_CONSUMER_GROUP="${NOTIFICATION_GATEWAY_CONSUMER_GROUP:-notification-gateway-group}"

container_healthy() {
    local container="$1"
    [[ "$(docker inspect --format '{{.State.Health.Status}}' "$container" 2>/dev/null)" == healthy ]]
}

infrastructure_healthy() {
    container_healthy postgres && container_healthy kafka
}

container_running() {
    local container="$1"
    [[ "$(docker inspect --format '{{.State.Running}}' "$container" 2>/dev/null)" == true ]]
}

applications_running() {
    container_running kafka-producer \
        && container_running spi \
        && container_running notification-gateway
}

port_accepting() {
    local port="$1"
    timeout 1 bash -c '</dev/tcp/127.0.0.1/$1' _ "$port" >/dev/null 2>&1
}

application_ports_accepting() {
    port_accepting 8001 && port_accepting 8002 && port_accepting 9090
}

consumer_groups_stable() {
    local output

    if ! output="$(timeout "$KAFKA_CLI_TIMEOUT_SECONDS" docker exec "$KAFKA_CONTAINER" kafka-consumer-groups \
            --bootstrap-server "$KAFKA_BOOTSTRAP_SERVER" \
            --list \
            --state 2>/dev/null)"; then
        return 1
    fi

    awk \
        -v payment_group="$SPI_PAYMENT_REQUEST_CONSUMER_GROUP" \
        -v status_group="$SPI_STATUS_REPORT_CONSUMER_GROUP" \
        -v gateway_group="$NOTIFICATION_GATEWAY_CONSUMER_GROUP" '
        $1 == payment_group && $2 == "Stable" { payment_stable = 1 }
        $1 == status_group && $2 == "Stable" { status_stable = 1 }
        $1 == gateway_group && $2 == "Stable" { gateway_stable = 1 }
        END { exit(payment_stable && status_stable && gateway_stable ? 0 : 1) }
    ' <<< "$output"
}

readiness_now() {
    printf '%s\n' "$SECONDS"
}

readiness_sleep() {
    sleep "$1"
}

wait_for_performance_stack() {
    local deadline now
    deadline=$(( $(readiness_now) + READINESS_TIMEOUT_SECONDS ))

    while :; do
        if infrastructure_healthy \
            && applications_running \
            && application_ports_accepting \
            && consumer_groups_stable; then
            return 0
        fi

        now="$(readiness_now)"
        if ((now >= deadline)); then
            echo "Performance stack did not become ready within ${READINESS_TIMEOUT_SECONDS}s." >&2
            return 1
        fi
        readiness_sleep "$READINESS_POLL_SECONDS"
    done
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    wait_for_performance_stack
fi
