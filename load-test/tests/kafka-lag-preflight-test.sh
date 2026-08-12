#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

run_preflight() {
    SPI_LAG="$1" GATEWAY_LAG="$2" ROOT_DIR="$ROOT_DIR" bash -c '
set -euo pipefail
source "${ROOT_DIR}/run-load-test.sh"
current_spi_input_lag() {
    [[ "$SPI_LAG" != unreadable ]] || return 1
    printf "%s\n" "$SPI_LAG"
}
current_notification_gateway_lag() {
    [[ "$GATEWAY_LAG" != unreadable ]] || return 1
    printf "%s\n" "$GATEWAY_LAG"
}
assert_no_initial_kafka_lag
'
}

run_preflight 0 0

for fixture in "1 0" "0 1" "unreadable 0" "0 unreadable"; do
    read -r spi_lag gateway_lag <<< "$fixture"
    if run_preflight "$spi_lag" "$gateway_lag" >/dev/null 2>&1; then
        echo "Kafka preflight accepted spi=$spi_lag gateway=$gateway_lag" >&2
        exit 1
    fi
done
