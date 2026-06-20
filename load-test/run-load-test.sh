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
readonly SPI_INPUT_TOPICS=("spi-payment-requests" "spi-payment-status-reports")
readonly SPI_JFR_CONTAINER_FILE="/tmp/spi-load-test.jfr"
readonly SPI_JFR_NAME="spi-load-test"
readonly SPI_TRACE_CONTAINER_FILE="/tmp/spi-trace.csv"
readonly SPI_INTERNAL_BASE_URL="${SPI_INTERNAL_BASE_URL:-http://localhost:8002}"
readonly POSTGRES_STATEMENTS_FILE="postgres-statements.csv"
readonly POSTGRES_STATEMENTS_LOG="postgres-statements.log"

RUN_TAG=""
PROVISION_FUNDS=true
ENABLE_JFR=false
ENABLE_SPI_TRACE=false
ENABLE_POSTGRES_STATEMENTS=false
SPI_TRACE_ACTIVE=false
JFR_ACTIVE=false
POSTGRES_STATEMENTS_ACTIVE=false
JFR_TARGET_DIR=""
POSTGRES_STATEMENTS_TARGET_DIR=""
SYSTEM_STATS_PID=""
PROCESS_STATS_PID=""
KAFKA_LAG_PID=""

usage() {
    echo "Usage: $(basename "$0") [--jfr] [--spi-trace] [--postgres-statements] [--provision-funds|--no-provision-funds] <run-tag>"
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
    if [[ "$SPI_TRACE_ACTIVE" == true ]]; then
        stop_spi_trace "" || true
    fi
    if [[ "$JFR_ACTIVE" == true && -n "$JFR_TARGET_DIR" ]]; then
        stop_spi_jfr "$JFR_TARGET_DIR" || true
    fi
    if [[ "$POSTGRES_STATEMENTS_ACTIVE" == true ]]; then
        disable_postgres_statement_stats "$POSTGRES_STATEMENTS_TARGET_DIR" || true
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
            --spi-trace)
                ENABLE_SPI_TRACE=true
                shift
                ;;
            --postgres-statements)
                ENABLE_POSTGRES_STATEMENTS=true
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
        awk -v topics="${SPI_INPUT_TOPICS[*]}" '
            BEGIN {
                split(topics, topic_list, " ")
                for (i in topic_list) {
                    watched[topic_list[i]] = 1
                }
            }
            watched[$2] && $6 ~ /^[0-9]+$/ { lag += $6 }
            END { print lag + 0 }
        '
}

assert_no_initial_spi_lag() {
    local lag
    lag="$(current_spi_input_lag)"

    if (( lag > 0 )); then
        echo "Refusing to start load test: ${SPI_CONSUMER_GROUP} has ${lag} messages of lag on SPI input topics: ${SPI_INPUT_TOPICS[*]}." >&2
        echo "Wait for the backlog to drain or reset the Kafka/Postgres test environment before starting a new measured run." >&2
        exit 1
    fi
}

run_spi_jcmd() {
    docker exec "$SPI_CONTAINER" jcmd 1 "$@"
}

start_spi_trace() {
    local log_file="$1"

    log_phase "starting SPI trace collection"
    {
        echo "Starting SPI trace at $(date --iso-8601=seconds)"
        curl -fsS -X POST "${SPI_INTERNAL_BASE_URL}/internal/spi-trace/start"
    } > "$log_file" 2>&1
    SPI_TRACE_ACTIVE=true
}

stop_spi_trace() {
    local target_dir="$1"
    local log_file="/dev/null"
    if [[ -n "$target_dir" ]]; then
        log_file="$target_dir/spi-trace.log"
    fi

    log_phase "stopping SPI trace collection"
    {
        echo "Stopping SPI trace at $(date --iso-8601=seconds)"
        curl -fsS -X POST "${SPI_INTERNAL_BASE_URL}/internal/spi-trace/stop"
    } >> "$log_file" 2>&1
    SPI_TRACE_ACTIVE=false
}

copy_spi_trace() {
    local target_dir="$1"
    local log_file="$target_dir/spi-trace.log"

    log_phase "copying SPI trace"
    {
        echo "Copying SPI trace at $(date --iso-8601=seconds)"
        if docker exec "$SPI_CONTAINER" test -s "$SPI_TRACE_CONTAINER_FILE"; then
            docker cp "${SPI_CONTAINER}:${SPI_TRACE_CONTAINER_FILE}" "$target_dir/spi-trace.csv"
            echo "SPI trace copied to ${target_dir}/spi-trace.csv"
        else
            echo "SPI trace file was not created or is empty."
            echo "Make sure SPI trace was started successfully before the simulator ran."
        fi
    } >> "$log_file" 2>&1
}

enable_postgres_statement_stats() {
    local target_dir="$1"
    local log_file="${target_dir}/${POSTGRES_STATEMENTS_LOG}"

    log_phase "enabling Postgres statement stats"
    {
        echo "Enabling Postgres statement stats at $(date --iso-8601=seconds)"
        "${SCRIPTS_DIR}/postgres-statements.sh" enable-and-reset
    } > "$log_file" 2>&1
    POSTGRES_STATEMENTS_ACTIVE=true
    POSTGRES_STATEMENTS_TARGET_DIR="$target_dir"
}

capture_postgres_statement_stats() {
    local target_dir="$1"
    local output_file="${target_dir}/${POSTGRES_STATEMENTS_FILE}"
    local log_file="${target_dir}/${POSTGRES_STATEMENTS_LOG}"

    log_phase "capturing Postgres statement stats"
    {
        echo "Capturing Postgres statement stats at $(date --iso-8601=seconds)"
        "${SCRIPTS_DIR}/postgres-statements.sh" snapshot "$output_file"
        echo "Postgres statement stats saved to ${output_file}"
    } >> "$log_file" 2>&1
}

disable_postgres_statement_stats() {
    local target_dir="$1"
    local log_file="/dev/null"
    if [[ -n "$target_dir" ]]; then
        log_file="${target_dir}/${POSTGRES_STATEMENTS_LOG}"
    fi

    log_phase "disabling Postgres statement stats"
    {
        echo "Disabling Postgres statement stats at $(date --iso-8601=seconds)"
        "${SCRIPTS_DIR}/postgres-statements.sh" disable
    } >> "$log_file" 2>&1
    POSTGRES_STATEMENTS_ACTIVE=false
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
    if [[ "$ENABLE_SPI_TRACE" == true ]]; then
        log_phase "SPI trace collection enabled"
    fi
    if [[ "$ENABLE_POSTGRES_STATEMENTS" == true ]]; then
        log_phase "Postgres statement stats enabled"
    fi

    log_phase "checking initial SPI Kafka lag"
    assert_no_initial_spi_lag
    log_phase "initial SPI Kafka lag is zero"

    log_phase "ensuring SPI trace is stopped"
    stop_spi_trace ""

    if [[ "$PROVISION_FUNDS" == true ]]; then
        log_phase "provisioning funds"
        "${SCRIPTS_DIR}/provision-funds.sh" > "${target_dir}/provision-funds.log" 2>&1
        log_phase "funds provisioned"
    else
        log_phase "skipping funds provisioning"
    fi

    if [[ "$ENABLE_POSTGRES_STATEMENTS" == true ]]; then
        enable_postgres_statement_stats "$target_dir"
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

    if [[ "$ENABLE_SPI_TRACE" == true ]]; then
        start_spi_trace "${target_dir}/spi-trace.log"
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

    if [[ "$ENABLE_SPI_TRACE" == true ]]; then
        stop_spi_trace "$target_dir"
        copy_spi_trace "$target_dir"
    fi

    if [[ "$ENABLE_POSTGRES_STATEMENTS" == true ]]; then
        capture_postgres_statement_stats "$target_dir"
        disable_postgres_statement_stats "$target_dir"
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
