#!/bin/bash

set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPTS_DIR="${SCRIPTS_DIR:-${SCRIPT_DIR}}"
readonly SPI_CONTAINER="spi"
readonly KAFKA_PRODUCER_CONTAINER="kafka-producer"
readonly NOTIFICATION_GATEWAY_CONTAINER="notification-gateway"
readonly POSTGRES_STATEMENTS_FILE="postgres-statements.csv"
readonly POSTGRES_STATEMENTS_LOG="postgres-statements.log"
readonly POSTGRES_ACTIVITY_FILE="postgres-activity.csv"
readonly POSTGRES_IO_FILE="postgres-io.csv"
readonly POSTGRES_RUNTIME_LOG="postgres-runtime.log"
readonly POSTGRES_SERVER_LOG="postgres-server.log"
readonly CONTAINER_STATS_FILE="container-stats.csv"
readonly CONTAINER_STATS_LOG="container-stats.log"

ENABLE_JFR=true
ENABLE_POSTGRES_STATEMENTS=true
JFR_ACTIVE=false
POSTGRES_STATEMENTS_ACTIVE=false
POSTGRES_ACTIVITY_PID=""
POSTGRES_SERVER_LOG_SINCE=""
CONTAINER_STATS_PID=""
JFR_TARGET_DIR=""
POSTGRES_STATEMENTS_TARGET_DIR=""
RUN_DIR=""
COMMAND=()

usage() {
    cat <<EOF
Usage: $(basename "$0") run --run-dir DIR [--no-jfr] [--no-postgres-statements] -- COMMAND...
EOF
}

log_phase() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

iso_now() {
    date '+%Y-%m-%dT%H:%M:%S.%N%:z'
}

parse_args() {
    if [[ $# -eq 0 || "$1" != run ]]; then
        usage >&2
        return 2
    fi
    shift
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --run-dir)
                if [[ $# -lt 2 || -z "$2" ]]; then
                    echo "--run-dir requires a directory." >&2
                    return 2
                fi
                RUN_DIR="$2"
                shift 2
                ;;
            --no-jfr) ENABLE_JFR=false; shift ;;
            --no-postgres-statements) ENABLE_POSTGRES_STATEMENTS=false; shift ;;
            --)
                shift
                COMMAND=("$@")
                break
                ;;
            -h|--help)
                usage
                return 0
                ;;
            *)
                echo "Unknown diagnostic option: $1" >&2
                return 2
                ;;
        esac
    done
    if [[ -z "$RUN_DIR" ]]; then
        echo "--run-dir is required." >&2
        return 2
    fi
    if [[ ${#COMMAND[@]} -eq 0 ]]; then
        echo "A command is required after --." >&2
        return 2
    fi
}

enable_postgres_statement_stats() {
    local target_dir="$1"
    local log_file="$target_dir/logs/$POSTGRES_STATEMENTS_LOG"
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
    local output="$target_dir/diagnostics/$POSTGRES_STATEMENTS_FILE"
    local log_file="$target_dir/logs/$POSTGRES_STATEMENTS_LOG"
    log_phase "capturing Postgres statement stats"
    "${SCRIPTS_DIR}/postgres-statements.sh" snapshot "$output" >> "$log_file" 2>&1
}

disable_postgres_statement_stats() {
    local target_dir="$1"
    local log_file="/dev/null"
    [[ -z "$target_dir" ]] || log_file="$target_dir/logs/$POSTGRES_STATEMENTS_LOG"
    log_phase "disabling Postgres statement stats"
    local status=0
    "${SCRIPTS_DIR}/postgres-statements.sh" disable >> "$log_file" 2>&1 || status=$?
    POSTGRES_STATEMENTS_ACTIVE=false
    return "$status"
}

wait_for_sampler_file() {
    local pid="$1" output="$2" name="$3"
    for _ in {1..100}; do
        if [[ -s "$output" ]] && kill -0 "$pid" 2>/dev/null; then
            return 0
        fi
        if ! kill -0 "$pid" 2>/dev/null; then
            wait "$pid" 2>/dev/null || true
            echo "$name stopped before producing $output." >&2
            return 1
        fi
        sleep 0.01
    done
    echo "Timed out waiting for $name to produce $output." >&2
    return 1
}

start_postgres_runtime_diagnostics() {
    local target_dir="$1"
    local activity="$target_dir/diagnostics/$POSTGRES_ACTIVITY_FILE"
    local io="$target_dir/diagnostics/$POSTGRES_IO_FILE"
    local log_file="$target_dir/logs/$POSTGRES_RUNTIME_LOG"
    mkdir -p "$target_dir/diagnostics"
    log_phase "capturing initial Postgres I/O snapshot"
    "${SCRIPTS_DIR}/postgres-runtime.sh" snapshot-io before "$io" > "$log_file" 2>&1
    log_phase "starting Postgres activity sampling"
    "${SCRIPTS_DIR}/postgres-runtime.sh" sample-activity "$activity" >> "$log_file" 2>&1 &
    POSTGRES_ACTIVITY_PID=$!
    wait_for_sampler_file "$POSTGRES_ACTIVITY_PID" "$activity" "Postgres activity sampler"
}

stop_postgres_activity_sampler() {
    local target_dir="$1" pid="$POSTGRES_ACTIVITY_PID" failed=0 status=0
    local log_file="/dev/null"
    [[ -n "$pid" ]] || return 0
    [[ -z "$target_dir" ]] || log_file="$target_dir/logs/$POSTGRES_RUNTIME_LOG"
    log_phase "stopping Postgres activity sampling"
    if kill -0 "$pid" 2>/dev/null; then
        kill -TERM "$pid" 2>/dev/null || failed=1
    else
        failed=1
    fi
    wait "$pid" || { status=$?; failed=1; }
    ((status == 0)) || echo "Postgres activity sampler exited with status $status." >> "$log_file"
    POSTGRES_ACTIVITY_PID=""
    return "$failed"
}

capture_final_postgres_io() {
    local target_dir="$1"
    log_phase "capturing final Postgres I/O snapshot"
    "${SCRIPTS_DIR}/postgres-runtime.sh" snapshot-io after "$target_dir/diagnostics/$POSTGRES_IO_FILE" >> "$target_dir/logs/$POSTGRES_RUNTIME_LOG" 2>&1
}

start_postgres_server_log_capture() {
    POSTGRES_SERVER_LOG_SINCE="$(iso_now)"
}

capture_postgres_server_log() {
    local target_dir="$1" until="$2" status=0
    local output="$target_dir/logs/$POSTGRES_SERVER_LOG"
    local runtime_log="$target_dir/logs/$POSTGRES_RUNTIME_LOG"
    log_phase "capturing Postgres server logs"
    if [[ -z "$POSTGRES_SERVER_LOG_SINCE" ]]; then
        echo "Postgres server-log capture has no start boundary." >> "$runtime_log"
        return 2
    fi
    "${SCRIPTS_DIR}/postgres-server-log.sh" capture "$POSTGRES_SERVER_LOG_SINCE" "$until" "$output" >> "$runtime_log" 2>&1 || status=$?
    POSTGRES_SERVER_LOG_SINCE=""
    return "$status"
}

start_container_stats() {
    local target_dir="$1"
    local output="$target_dir/diagnostics/$CONTAINER_STATS_FILE"
    log_phase "starting container resource sampling"
    "${SCRIPTS_DIR}/container-stats.sh" sample "$output" > "$target_dir/logs/$CONTAINER_STATS_LOG" 2>&1 &
    CONTAINER_STATS_PID=$!
    wait_for_sampler_file "$CONTAINER_STATS_PID" "$output" "Container resource sampler"
}

stop_container_stats() {
    local target_dir="$1" pid="$CONTAINER_STATS_PID" failed=0 status=0
    [[ -n "$pid" ]] || return 0
    log_phase "stopping container resource sampling"
    if kill -0 "$pid" 2>/dev/null; then
        kill -TERM "$pid" 2>/dev/null || failed=1
    else
        failed=1
    fi
    wait "$pid" || { status=$?; failed=1; }
    ((status == 0)) || echo "Container resource sampler exited with status $status." >> "$target_dir/logs/$CONTAINER_STATS_LOG"
    CONTAINER_STATS_PID=""
    return "$failed"
}

start_container_jfr() {
    local container="$1" name="$2" container_file="$3" log_file="$4"
    shift 4
    log_phase "starting $container JFR recording"
    "${SCRIPTS_DIR}/container-jfr.sh" start "$container" "$name" "$container_file" "$@" > "$log_file" 2>&1
}

stop_container_jfr() {
    local container="$1" name="$2" container_file="$3" output="$4" log_file="$5"
    log_phase "stopping $container JFR recording"
    "${SCRIPTS_DIR}/container-jfr.sh" stop "$container" "$name" "$container_file" "$output" >> "$log_file" 2>&1
}

start_jfr_recordings() {
    local target_dir="$1"
    mkdir -p "$target_dir/logs/jfr" "$target_dir/diagnostics/jfr"
    JFR_TARGET_DIR="$target_dir"
    JFR_ACTIVE=true
    start_container_jfr "$KAFKA_PRODUCER_CONTAINER" kafka-producer-load-test /tmp/kafka-producer-load-test.jfr "$target_dir/logs/jfr/kafka-producer.log" 'jdk.TLSHandshake#enabled=true'
    start_container_jfr "$SPI_CONTAINER" spi-load-test /tmp/spi-load-test.jfr "$target_dir/logs/jfr/spi.log"
    start_container_jfr "$NOTIFICATION_GATEWAY_CONTAINER" notification-gateway-load-test /tmp/notification-gateway-load-test.jfr "$target_dir/logs/jfr/notification-gateway.log"
}

stop_jfr_recordings() {
    local target_dir="$1" failed=0
    stop_container_jfr "$KAFKA_PRODUCER_CONTAINER" kafka-producer-load-test /tmp/kafka-producer-load-test.jfr "$target_dir/diagnostics/jfr/kafka-producer.jfr" "$target_dir/logs/jfr/kafka-producer.log" || failed=1
    stop_container_jfr "$SPI_CONTAINER" spi-load-test /tmp/spi-load-test.jfr "$target_dir/diagnostics/jfr/spi.jfr" "$target_dir/logs/jfr/spi.log" || failed=1
    stop_container_jfr "$NOTIFICATION_GATEWAY_CONTAINER" notification-gateway-load-test /tmp/notification-gateway-load-test.jfr "$target_dir/diagnostics/jfr/notification-gateway.jfr" "$target_dir/logs/jfr/notification-gateway.log" || failed=1
    JFR_ACTIVE=false
    return "$failed"
}

start_optional_diagnostics() {
    local target_dir="$1"
    if [[ "$ENABLE_POSTGRES_STATEMENTS" == true ]]; then
        start_postgres_server_log_capture
        enable_postgres_statement_stats "$target_dir"
        start_postgres_runtime_diagnostics "$target_dir"
        start_container_stats "$target_dir"
    fi
    [[ "$ENABLE_JFR" != true ]] || start_jfr_recordings "$target_dir"
}

collect_optional_diagnostics() {
    local target_dir="$1" failed=0 until=""
    [[ "$ENABLE_JFR" != true ]] || stop_jfr_recordings "$target_dir" || failed=1
    if [[ "$ENABLE_POSTGRES_STATEMENTS" == true ]]; then
        until="$(iso_now)"
        stop_postgres_activity_sampler "$target_dir" || failed=1
        stop_container_stats "$target_dir" || failed=1
        capture_final_postgres_io "$target_dir" || failed=1
        capture_postgres_statement_stats "$target_dir" || failed=1
        disable_postgres_statement_stats "$target_dir" || failed=1
        capture_postgres_server_log "$target_dir" "$until" || failed=1
    fi
    return "$failed"
}

cleanup_diagnostics() {
    trap - EXIT INT TERM
    [[ -z "$POSTGRES_ACTIVITY_PID" ]] || stop_postgres_activity_sampler "$POSTGRES_STATEMENTS_TARGET_DIR" || true
    [[ -z "$CONTAINER_STATS_PID" ]] || stop_container_stats "$POSTGRES_STATEMENTS_TARGET_DIR" || true
    [[ "$JFR_ACTIVE" != true ]] || stop_jfr_recordings "$JFR_TARGET_DIR" || true
    [[ "$POSTGRES_STATEMENTS_ACTIVE" != true ]] || disable_postgres_statement_stats "$POSTGRES_STATEMENTS_TARGET_DIR" || true
}

run_command() {
    local -a statuses
    set +e
    "${COMMAND[@]}" 2>&1 | tee "$RUN_DIR/logs/loadtool.log"
    statuses=("${PIPESTATUS[@]}")
    set -e
    COMMAND_STATUS="${statuses[0]}"
    TEE_STATUS="${statuses[1]}"
}

main() {
    parse_args "$@" || return $?
    if [[ ! -d "$RUN_DIR" ]]; then
        echo "Run directory does not exist: $RUN_DIR" >&2
        return 2
    fi
    RUN_DIR="$(cd "$RUN_DIR" && pwd)"
    mkdir -p "$RUN_DIR/logs" "$RUN_DIR/diagnostics"
    trap cleanup_diagnostics EXIT INT TERM

    if ! start_optional_diagnostics "$RUN_DIR"; then
        return 2
    fi
    local COMMAND_STATUS=0 TEE_STATUS=0 diagnostics_status=0
    run_command
    collect_optional_diagnostics "$RUN_DIR" || diagnostics_status=1
    trap - EXIT INT TERM

    if ((COMMAND_STATUS != 0)); then
        return "$COMMAND_STATUS"
    fi
    if ((TEE_STATUS != 0 || diagnostics_status != 0)); then
        return 2
    fi
    return 0
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
