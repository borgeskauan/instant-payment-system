#!/bin/bash

set -euo pipefail

readonly SPI_CONTAINER="${SPI_CONTAINER:-spi}"
readonly SPI_TRACE_CONTAINER_FILE="${SPI_TRACE_CONTAINER_FILE:-/tmp/spi-trace.csv}"
readonly SPI_INTERNAL_BASE_URL="${SPI_INTERNAL_BASE_URL:-http://localhost:8002}"

usage() {
    echo "Usage: $(basename "$0") start|stop|copy <target-dir>"
}

start_trace() {
    echo "Starting SPI trace at $(date --iso-8601=seconds)"
    curl -fsS -X POST "${SPI_INTERNAL_BASE_URL}/internal/spi-trace/start"
}

stop_trace() {
    echo "Stopping SPI trace at $(date --iso-8601=seconds)"
    curl -fsS -X POST "${SPI_INTERNAL_BASE_URL}/internal/spi-trace/stop"
}

copy_trace() {
    local target_dir="$1"

    echo "Copying SPI trace at $(date --iso-8601=seconds)"
    if docker exec "$SPI_CONTAINER" test -s "$SPI_TRACE_CONTAINER_FILE"; then
        docker cp "${SPI_CONTAINER}:${SPI_TRACE_CONTAINER_FILE}" "$target_dir/spi-trace.csv"
        echo "SPI trace copied to ${target_dir}/spi-trace.csv"
    else
        echo "SPI trace file was not created or is empty."
        echo "Make sure SPI trace was started successfully before the simulator ran."
    fi
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
            start_trace
            ;;
        stop)
            if [[ $# -ne 1 ]]; then
                usage >&2
                exit 2
            fi
            stop_trace
            ;;
        copy)
            if [[ $# -ne 2 ]]; then
                usage >&2
                exit 2
            fi
            copy_trace "$2"
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
