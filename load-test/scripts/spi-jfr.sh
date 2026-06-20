#!/bin/bash

set -euo pipefail

readonly SPI_CONTAINER="${SPI_CONTAINER:-spi}"
readonly SPI_JFR_CONTAINER_FILE="${SPI_JFR_CONTAINER_FILE:-/tmp/spi-load-test.jfr}"
readonly SPI_JFR_NAME="${SPI_JFR_NAME:-spi-load-test}"

usage() {
    echo "Usage: $(basename "$0") start|stop <target-dir>"
}

run_spi_jcmd() {
    docker exec "$SPI_CONTAINER" jcmd 1 "$@"
}

start_jfr() {
    echo "Starting SPI JFR recording at $(date --iso-8601=seconds)"
    run_spi_jcmd JFR.stop name="$SPI_JFR_NAME" || true
    docker exec "$SPI_CONTAINER" rm -f "$SPI_JFR_CONTAINER_FILE" || true
    run_spi_jcmd JFR.start \
        name="$SPI_JFR_NAME" \
        settings=profile \
        filename="$SPI_JFR_CONTAINER_FILE" \
        dumponexit=true
}

stop_jfr() {
    local target_dir="$1"

    echo "Stopping SPI JFR recording at $(date --iso-8601=seconds)"
    run_spi_jcmd JFR.stop name="$SPI_JFR_NAME" filename="$SPI_JFR_CONTAINER_FILE"
    docker cp "${SPI_CONTAINER}:${SPI_JFR_CONTAINER_FILE}" "$target_dir/spi-load-test.jfr"
    echo "SPI JFR saved: ${target_dir}/spi-load-test.jfr"
}

main() {
    if [[ $# -lt 1 ]]; then
        usage >&2
        exit 2
    fi

    case "$1" in
        start)
            if [[ $# -ne 1 ]]; then
                usage >&2
                exit 2
            fi
            start_jfr
            ;;
        stop)
            if [[ $# -ne 2 ]]; then
                usage >&2
                exit 2
            fi
            stop_jfr "$2"
            ;;
        -h|--help)
            usage
            ;;
        *)
            usage >&2
            echo "Unknown action: $1" >&2
            exit 2
            ;;
    esac
}

main "$@"
