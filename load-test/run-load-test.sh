#!/bin/bash

set -euo pipefail

readonly RESULTS_DIR="results"
readonly SYSTEM_STATS_INTERVAL_SECONDS=5
readonly PROCESS_STATS_INTERVAL_SECONDS=5
readonly KAFKA_LAG_INTERVAL_SECONDS=2
readonly GO_LOADTOOL_CONFIG="go-loadtool/loadtool-config.json"
readonly SCRIPTS_DIR="scripts"
readonly SPI_CONTAINER="spi"
readonly KAFKA_CONTAINER="kafka"
readonly SPI_CONSUMER_GROUP="spi-consumer-group"
readonly SPI_INPUT_TOPIC="spi-payment-requests"
readonly SPI_JFR_CONTAINER_FILE="/tmp/spi-load-test.jfr"
readonly SPI_JFR_NAME="spi-load-test"

RUN_TAG=""
PROVISION_FUNDS=true
ENABLE_JFR=false
JFR_ACTIVE=false
JFR_TARGET_DIR=""
SYSTEM_STATS_PID=""
PROCESS_STATS_PID=""
KAFKA_LAG_PID=""

usage() {
    echo "Usage: $(basename "$0") [--jfr] [--provision-funds|--no-provision-funds] <run-tag>"
    echo "Edit ${GO_LOADTOOL_CONFIG} to change rate, duration, drain, PSP distribution, or SLA."
}

log_phase() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

stop_samplers() {
    for pid in "$SYSTEM_STATS_PID" "$PROCESS_STATS_PID" "$KAFKA_LAG_PID"; do
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
            wait "$pid" 2>/dev/null || true
        fi
    done
}

cleanup() {
    if [[ "$JFR_ACTIVE" == true && -n "$JFR_TARGET_DIR" ]]; then
        stop_spi_jfr "$JFR_TARGET_DIR" || true
    fi
    stop_samplers
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --provision-funds)
                PROVISION_FUNDS=true
                shift
                ;;
            --no-provision-funds)
                PROVISION_FUNDS=false
                shift
                ;;
            --jfr)
                ENABLE_JFR=true
                shift
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            --*)
                usage
                echo "Unknown option: $1" >&2
                exit 2
                ;;
            *)
                if [[ -n "$RUN_TAG" ]]; then
                    usage
                    echo "Only one run tag is allowed." >&2
                    exit 2
                fi
                RUN_TAG="$1"
                shift
                ;;
        esac
    done

    if [[ -z "$RUN_TAG" ]]; then
        usage
        echo "Run tag is required." >&2
        exit 2
    fi
}

duration_seconds() {
    local key="$1"
    python3 -c '
import json
import re
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    value = str(json.load(handle)[sys.argv[2]])

match = re.fullmatch(r"([0-9]+)s?", value)
if not match:
    raise SystemExit(f"{sys.argv[2]} must be seconds, for example 60s.")

print(match.group(1))
' "$GO_LOADTOOL_CONFIG" "$key"
}

current_spi_input_lag() {
    docker exec "$KAFKA_CONTAINER" kafka-consumer-groups \
        --bootstrap-server kafka:9092 \
        --describe \
        --group "$SPI_CONSUMER_GROUP" 2>/dev/null |
        awk -v topic="$SPI_INPUT_TOPIC" '$2 == topic && $6 ~ /^[0-9]+$/ { lag += $6 } END { print lag + 0 }'
}

assert_no_initial_spi_lag() {
    local lag
    lag="$(current_spi_input_lag)"

    if (( lag > 0 )); then
        echo "Refusing to start load test: ${SPI_CONSUMER_GROUP} has ${lag} messages of lag on ${SPI_INPUT_TOPIC}." >&2
        echo "Wait for the backlog to drain or reset the Kafka/Postgres test environment before starting a new measured run." >&2
        exit 1
    fi
}

run_spi_jcmd() {
    docker exec "$SPI_CONTAINER" jcmd 1 "$@"
}

start_spi_jfr() {
    local log_file="$1"

    log_phase "starting SPI JFR recording"
    {
        echo "Starting SPI JFR recording at $(date --iso-8601=seconds)"
        run_spi_jcmd JFR.stop name="$SPI_JFR_NAME" || true
        docker exec "$SPI_CONTAINER" rm -f "$SPI_JFR_CONTAINER_FILE" || true
        run_spi_jcmd JFR.start \
            name="$SPI_JFR_NAME" \
            settings=profile \
            filename="$SPI_JFR_CONTAINER_FILE" \
            dumponexit=true
    } > "$log_file" 2>&1
    JFR_ACTIVE=true
}

stop_spi_jfr() {
    local target_dir="$1"
    local log_file="$target_dir/spi-jfr.log"

    log_phase "stopping SPI JFR recording"
    {
        echo "Stopping SPI JFR recording at $(date --iso-8601=seconds)"
        run_spi_jcmd JFR.stop name="$SPI_JFR_NAME" filename="$SPI_JFR_CONTAINER_FILE"
        docker cp "${SPI_CONTAINER}:${SPI_JFR_CONTAINER_FILE}" "$target_dir/spi-load-test.jfr"
    } >> "$log_file" 2>&1
    JFR_ACTIVE=false
    log_phase "SPI JFR saved: ${target_dir}/spi-load-test.jfr"
}

main() {
    parse_args "$@"

    local timestamp target_dir tool_out active_seconds drain_seconds sampler_duration
    timestamp="$(date +%Y%m%d_%H%M%S)"
    target_dir="${RESULTS_DIR}/${RUN_TAG}/${timestamp}"
    tool_out="${target_dir}/go-loadtool"
    active_seconds="$(duration_seconds "duration")"
    drain_seconds="$(duration_seconds "drain")"
    sampler_duration=$((active_seconds + drain_seconds + 20))

    mkdir -p "$tool_out"
    cp "$GO_LOADTOOL_CONFIG" "${target_dir}/loadtool-config.json"
    trap cleanup EXIT

    log_phase "starting load test: tag=${RUN_TAG} output=${target_dir}"
    log_phase "using config: ${GO_LOADTOOL_CONFIG}"
    if [[ "$ENABLE_JFR" == true ]]; then
        log_phase "SPI JFR enabled"
    fi

    log_phase "checking initial SPI Kafka lag"
    assert_no_initial_spi_lag
    log_phase "initial SPI Kafka lag is zero"

    if [[ "$PROVISION_FUNDS" == true ]]; then
        log_phase "provisioning funds"
        "${SCRIPTS_DIR}/provision-funds.sh" > "${target_dir}/provision-funds.log" 2>&1
        log_phase "funds provisioned"
    else
        log_phase "skipping funds provisioning"
    fi

    log_phase "starting samplers: duration=${sampler_duration}s"
    "${SCRIPTS_DIR}/sample-system-stats.sh" "$SYSTEM_STATS_INTERVAL_SECONDS" "$sampler_duration" "${target_dir}/system-stats.csv" > "${target_dir}/system-stats.log" 2>&1 &
    SYSTEM_STATS_PID="$!"

    "${SCRIPTS_DIR}/sample-process-stats.sh" "$PROCESS_STATS_INTERVAL_SECONDS" "$sampler_duration" "${target_dir}/process-stats.csv" 20 > "${target_dir}/process-stats.log" 2>&1 &
    PROCESS_STATS_PID="$!"

    "${SCRIPTS_DIR}/sample-kafka-lag.sh" "$KAFKA_LAG_INTERVAL_SECONDS" "$sampler_duration" "${target_dir}/kafka-lag.csv" > "${target_dir}/kafka-lag.log" 2>&1 &
    KAFKA_LAG_PID="$!"

    if [[ "$ENABLE_JFR" == true ]]; then
        JFR_TARGET_DIR="$target_dir"
        start_spi_jfr "${target_dir}/spi-jfr.log"
    fi

    log_phase "starting simulator"
    (
        cd go-loadtool
        GOPATH="${GOPATH:-/tmp/go}" GOCACHE="${GOCACHE:-/tmp/go-build-cache}" go run ./cmd/go-loadtool simulate \
            --out "../${tool_out}"
    ) | tee "${target_dir}/go-loadtool-output.txt"

    if [[ "$ENABLE_JFR" == true ]]; then
        stop_spi_jfr "$target_dir"
    fi

    log_phase "simulator finished; generating SLA report"
    (
        cd go-loadtool
        GOPATH="${GOPATH:-/tmp/go}" GOCACHE="${GOCACHE:-/tmp/go-build-cache}" go run ./cmd/go-loadtool report \
            --starts "../${tool_out}/starts.csv" \
            --events "../${tool_out}/events.csv"
    ) | tee "${target_dir}/sla-report.json"

    log_phase "SLA report generated"
    log_phase "results written to ${target_dir}"
}

main "$@"
