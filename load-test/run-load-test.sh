#!/bin/bash

set -euo pipefail

readonly RESULTS_DIR="results"
readonly GO_LOADTOOL_CONFIG="go-loadtool/loadtool-config.json"
readonly SCRIPTS_DIR="${SCRIPTS_DIR:-scripts}"
readonly SPI_CONTAINER="spi"
readonly KAFKA_PRODUCER_CONTAINER="kafka-producer"
readonly NOTIFICATION_GATEWAY_CONTAINER="notification-gateway"
readonly KAFKA_CONTAINER="kafka"
readonly SPI_PAYMENT_REQUEST_CONSUMER_GROUP="spi-payment-request-consumer-group"
readonly SPI_STATUS_REPORT_CONSUMER_GROUP="spi-status-report-consumer-group"
readonly SPI_PAYMENT_REQUEST_TOPIC="spi-payment-requests"
readonly SPI_STATUS_REPORT_TOPIC="spi-payment-status-reports"
readonly POSTGRES_STATEMENTS_FILE="postgres-statements.csv"
readonly POSTGRES_STATEMENTS_LOG="postgres-statements.log"
readonly GRAFANA_BASE_URL="${GRAFANA_BASE_URL:-http://localhost:3000}"
readonly GRAFANA_DASHBOARD_PATH="/d/load-test/load-test"

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
LOADTOOL_BUILD_DIR=""
LOADTOOL_BIN=""

usage() {
    echo "Usage: $(basename "$0") [--jfr] [--spi-trace] [--postgres-statements] [--provision-funds|--no-provision-funds] <run-tag>"
    echo "Edit ${GO_LOADTOOL_CONFIG} to change rate, duration, drain, PSP distribution, or SLA."
}

log_phase() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

iso_now() {
    date --iso-8601=seconds
}

iso_after_seconds() {
    local base_time="$1"
    local seconds="$2"

    date --iso-8601=seconds --date="${base_time} + ${seconds} seconds"
}

url_encode() {
    python3 -c '
import sys
from urllib.parse import quote

print(quote(sys.argv[1], safe=""))
' "$1"
}

grafana_dashboard_url() {
    local from="$1"
    local to="$2"
    local encoded_from encoded_to

    encoded_from="$(url_encode "$from")"
    encoded_to="$(url_encode "$to")"
    printf "%s%s?from=%s&to=%s\n" "$GRAFANA_BASE_URL" "$GRAFANA_DASHBOARD_PATH" "$encoded_from" "$encoded_to"
}

grafana_available() {
    curl -fsS --max-time 2 "${GRAFANA_BASE_URL}/api/health" >/dev/null 2>&1
}

log_grafana_status() {
    local grafana_available_at_run_start="$1"

    log_phase "Grafana available at run start: ${grafana_available_at_run_start}"
    if [[ "$grafana_available_at_run_start" != true ]]; then
        log_phase "Grafana is offline; start observability with: cd ../infra && docker compose --profile observability up -d"
        log_phase "Grafana URL after startup: ${GRAFANA_BASE_URL}"
    fi
}

write_run_window_json() {
    local target_dir="$1"
    local run_started_at="$2"
    local active_started_at="$3"
    local active_finished_at="$4"
    local drain_finished_at="$5"
    local grafana_available_at_run_start="$6"
    local full_run_url active_window_url

    full_run_url="$(grafana_dashboard_url "$run_started_at" "$drain_finished_at")"
    active_window_url="$(grafana_dashboard_url "$active_started_at" "$active_finished_at")"

    python3 - \
        "$RUN_TAG" \
        "$target_dir" \
        "$run_started_at" \
        "$active_started_at" \
        "$active_finished_at" \
        "$drain_finished_at" \
        "$grafana_available_at_run_start" \
        "$GRAFANA_BASE_URL" \
        "$full_run_url" \
        "$active_window_url" <<'PY'
import json
import sys

tag = sys.argv[1]
target_dir = sys.argv[2]
run_started_at = sys.argv[3]
active_started_at = sys.argv[4]
active_finished_at = sys.argv[5]
drain_finished_at = sys.argv[6]
grafana_available = sys.argv[7].lower() == "true"
base_url = sys.argv[8]
full_run_url = sys.argv[9]
active_window_url = sys.argv[10]

payload = {
    "tag": tag,
    "result_dir": target_dir,
    "window": {
        "run_started_at": run_started_at,
        "active_started_at": active_started_at,
        "active_finished_at": active_finished_at,
        "drain_finished_at": drain_finished_at,
    },
    "grafana": {
        "available_at_run_start": grafana_available,
        "base_url": base_url,
        "full_run_url": full_run_url,
        "active_window_url": active_window_url,
    },
}

with open(f"{target_dir}/run-window.json", "w", encoding="utf-8") as handle:
    json.dump(payload, handle, indent=2)
    handle.write("\n")
PY
}

print_grafana_links() {
    local run_started_at="$1"
    local active_started_at="$2"
    local active_finished_at="$3"
    local drain_finished_at="$4"

    log_phase "Grafana full run: $(grafana_dashboard_url "$run_started_at" "$drain_finished_at")"
    log_phase "Grafana active window: $(grafana_dashboard_url "$active_started_at" "$active_finished_at")"
}

cleanup() {
    if [[ "$SPI_TRACE_ACTIVE" == true ]]; then
        stop_spi_trace "" || true
    fi
    if [[ "$JFR_ACTIVE" == true && -n "$JFR_TARGET_DIR" ]]; then
        stop_jfr_recordings "$JFR_TARGET_DIR" || true
    fi
    if [[ "$POSTGRES_STATEMENTS_ACTIVE" == true ]]; then
        disable_postgres_statement_stats "$POSTGRES_STATEMENTS_TARGET_DIR" || true
    fi
    if [[ -n "$LOADTOOL_BUILD_DIR" && "$LOADTOOL_BUILD_DIR" == /tmp/* ]]; then
        rm -rf "$LOADTOOL_BUILD_DIR"
    fi
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

match = re.fullmatch(r"([0-9]+)([smh]?)", value)
if not match:
    raise SystemExit(f"{sys.argv[2]} must be a duration like 60s, 5m, or 1h.")

amount = int(match.group(1))
unit = match.group(2) or "s"
multipliers = {"s": 1, "m": 60, "h": 3600}
print(amount * multipliers[unit])
' "$GO_LOADTOOL_CONFIG" "$key"
}

consumer_group_topic_lag() {
    local consumer_group="$1"
    local topic="$2"
    local lag

    lag="$({
        docker exec "$KAFKA_CONTAINER" kafka-consumer-groups \
            --bootstrap-server kafka:9092 \
            --describe \
            --group "$consumer_group" 2>/dev/null || true
    } |
        awk -v topic="$topic" '
            $2 == topic && $6 ~ /^[0-9]+$/ {
                found = 1
                lag += $6
            }
            END {
                if (found) {
                    print lag + 0
                } else {
                    print "NO_OFFSETS"
                }
            }
        ')"

    if [[ "$lag" == "NO_OFFSETS" ]]; then
        topic_end_offset "$topic"
        return
    fi

    echo "$lag"
}

topic_end_offset() {
    local topic="$1"

    docker exec "$KAFKA_CONTAINER" kafka-get-offsets \
        --bootstrap-server kafka:9092 \
        --topic "$topic" \
        --time -1 2>/dev/null |
        awk -F: '
            $3 ~ /^[0-9]+$/ { offset += $3 }
            END { print offset + 0 }
        '
}

current_spi_input_lag() {
    local payment_lag status_lag

    payment_lag="$(consumer_group_topic_lag "$SPI_PAYMENT_REQUEST_CONSUMER_GROUP" "$SPI_PAYMENT_REQUEST_TOPIC")"
    status_lag="$(consumer_group_topic_lag "$SPI_STATUS_REPORT_CONSUMER_GROUP" "$SPI_STATUS_REPORT_TOPIC")"

    echo $(( payment_lag + status_lag ))
}

assert_no_initial_spi_lag() {
    local lag
    lag="$(current_spi_input_lag)"

    if (( lag > 0 )); then
        echo "Refusing to start load test: SPI input consumer groups have ${lag} messages of lag." >&2
        echo "Checked ${SPI_PAYMENT_REQUEST_CONSUMER_GROUP}/${SPI_PAYMENT_REQUEST_TOPIC} and ${SPI_STATUS_REPORT_CONSUMER_GROUP}/${SPI_STATUS_REPORT_TOPIC}." >&2
        echo "Wait for the backlog to drain or reset the Kafka/Postgres test environment before starting a new measured run." >&2
        exit 1
    fi
}

start_spi_trace() {
    local log_file="$1"

    log_phase "starting SPI trace collection"
    "${SCRIPTS_DIR}/spi-trace.sh" start > "$log_file" 2>&1
    SPI_TRACE_ACTIVE=true
}

stop_spi_trace() {
    local target_dir="$1"
    local log_file="/dev/null"
    if [[ -n "$target_dir" ]]; then
        log_file="$target_dir/spi-trace.log"
    fi

    log_phase "stopping SPI trace collection"
    "${SCRIPTS_DIR}/spi-trace.sh" stop >> "$log_file" 2>&1
    SPI_TRACE_ACTIVE=false
}

copy_spi_trace() {
    local target_dir="$1"
    local log_file="$target_dir/spi-trace.log"

    log_phase "copying SPI trace"
    "${SCRIPTS_DIR}/spi-trace.sh" copy "$target_dir" >> "$log_file" 2>&1
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

start_container_jfr() {
    local container="$1"
    local recording_name="$2"
    local container_file="$3"
    local log_file="$4"

    log_phase "starting ${container} JFR recording"
    "${SCRIPTS_DIR}/container-jfr.sh" start "$container" "$recording_name" "$container_file" > "$log_file" 2>&1
}

stop_container_jfr() {
    local container="$1"
    local recording_name="$2"
    local container_file="$3"
    local output_file="$4"
    local log_file="$5"

    log_phase "stopping ${container} JFR recording"
    "${SCRIPTS_DIR}/container-jfr.sh" stop "$container" "$recording_name" "$container_file" "$output_file" >> "$log_file" 2>&1
}

start_jfr_recordings() {
    local target_dir="$1"

    JFR_TARGET_DIR="$target_dir"
    JFR_ACTIVE=true

    start_container_jfr "$KAFKA_PRODUCER_CONTAINER" "kafka-producer-load-test" "/tmp/kafka-producer-load-test.jfr" "${target_dir}/kafka-producer-jfr.log"
    start_container_jfr "$SPI_CONTAINER" "spi-load-test" "/tmp/spi-load-test.jfr" "${target_dir}/spi-jfr.log"
    start_container_jfr "$NOTIFICATION_GATEWAY_CONTAINER" "notification-gateway-load-test" "/tmp/notification-gateway-load-test.jfr" "${target_dir}/notification-gateway-jfr.log"
}

stop_jfr_recordings() {
    local target_dir="$1"
    local failed=0

    stop_container_jfr "$KAFKA_PRODUCER_CONTAINER" "kafka-producer-load-test" "/tmp/kafka-producer-load-test.jfr" "${target_dir}/kafka-producer-load-test.jfr" "${target_dir}/kafka-producer-jfr.log" || failed=1
    stop_container_jfr "$SPI_CONTAINER" "spi-load-test" "/tmp/spi-load-test.jfr" "${target_dir}/spi-load-test.jfr" "${target_dir}/spi-jfr.log" || failed=1
    stop_container_jfr "$NOTIFICATION_GATEWAY_CONTAINER" "notification-gateway-load-test" "/tmp/notification-gateway-load-test.jfr" "${target_dir}/notification-gateway-load-test.jfr" "${target_dir}/notification-gateway-jfr.log" || failed=1

    JFR_ACTIVE=false
    return "$failed"
}

build_loadtool() {
    local output_bin="$1"

    log_phase "building Go loadtool"
    (
        cd go-loadtool
        GOPATH="${GOPATH:-/tmp/go}" GOCACHE="${GOCACHE:-/tmp/go-build-cache}" go build -o "$output_bin" ./cmd/go-loadtool
    )
    log_phase "Go loadtool built"
}

log_selected_options() {
    local target_dir="$1"

    log_phase "starting load test: tag=${RUN_TAG} output=${target_dir}"
    log_phase "using config: ${GO_LOADTOOL_CONFIG}"
    if [[ "$ENABLE_JFR" == true ]]; then
        log_phase "JFR enabled for kafka-producer, SPI, and notification-gateway"
    fi
    if [[ "$ENABLE_SPI_TRACE" == true ]]; then
        log_phase "SPI trace collection enabled"
    fi
    if [[ "$ENABLE_POSTGRES_STATEMENTS" == true ]]; then
        log_phase "Postgres statement stats enabled"
    fi
}

prepare_run_workspace() {
    local tool_out="$1"

    mkdir -p "$tool_out"
    LOADTOOL_BUILD_DIR="$(mktemp -d)"
    LOADTOOL_BIN="${LOADTOOL_BUILD_DIR}/go-loadtool"
}

run_preflight_checks() {
    log_phase "checking initial SPI Kafka lag"
    assert_no_initial_spi_lag
    log_phase "initial SPI Kafka lag is zero"

    log_phase "ensuring SPI trace is stopped"
    stop_spi_trace ""
}

provision_funds_if_enabled() {
    local target_dir="$1"

    if [[ "$PROVISION_FUNDS" == true ]]; then
        log_phase "provisioning funds"
        "${SCRIPTS_DIR}/provision-funds.sh" > "${target_dir}/provision-funds.log" 2>&1
        log_phase "funds provisioned"
    else
        log_phase "skipping funds provisioning"
    fi
}

start_optional_diagnostics() {
    local target_dir="$1"

    if [[ "$ENABLE_POSTGRES_STATEMENTS" == true ]]; then
        enable_postgres_statement_stats "$target_dir"
    fi
    if [[ "$ENABLE_JFR" == true ]]; then
        start_jfr_recordings "$target_dir"
    fi
    if [[ "$ENABLE_SPI_TRACE" == true ]]; then
        start_spi_trace "${target_dir}/spi-trace.log"
    fi
}

collect_optional_diagnostics() {
    local target_dir="$1"

    if [[ "$ENABLE_JFR" == true ]]; then
        stop_jfr_recordings "$target_dir"
    fi
    if [[ "$ENABLE_SPI_TRACE" == true ]]; then
        stop_spi_trace "$target_dir"
        copy_spi_trace "$target_dir"
    fi
    if [[ "$ENABLE_POSTGRES_STATEMENTS" == true ]]; then
        capture_postgres_statement_stats "$target_dir"
        disable_postgres_statement_stats "$target_dir"
    fi
}

run_simulator() {
    local target_dir="$1"
    local tool_out="$2"

    log_phase "starting simulator"
    (
        cd go-loadtool
        "$LOADTOOL_BIN" simulate \
            --out "../${tool_out}"
    ) | tee "${target_dir}/go-loadtool-output.txt"
}

generate_sla_report() {
    local target_dir="$1"
    local tool_out="$2"

    log_phase "simulator finished; generating SLA report"
    (
        cd go-loadtool
        "$LOADTOOL_BIN" report \
            --starts "../${tool_out}/starts.csv" \
            --events "../${tool_out}/events.csv"
    ) | tee "${target_dir}/sla-report.json"
    log_phase "SLA report generated"
}

main() {
    parse_args "$@"

    local timestamp target_dir tool_out warmup_seconds active_seconds
    local run_started_at active_started_at active_finished_at drain_finished_at grafana_available_at_run_start
    timestamp="$(date +%Y%m%d_%H%M%S)"
    target_dir="${RESULTS_DIR}/${RUN_TAG}/${timestamp}"
    tool_out="${target_dir}/go-loadtool"
    warmup_seconds="$(duration_seconds "warmup")"
    active_seconds="$(duration_seconds "duration")"
    run_started_at="$(iso_now)"
    active_started_at="$(iso_after_seconds "$run_started_at" "$warmup_seconds")"
    active_finished_at="$(iso_after_seconds "$active_started_at" "$active_seconds")"

    prepare_run_workspace "$tool_out"
    trap cleanup EXIT
    if grafana_available; then
        grafana_available_at_run_start=true
    else
        grafana_available_at_run_start=false
    fi

    log_selected_options "$target_dir"
    log_grafana_status "$grafana_available_at_run_start"
    build_loadtool "$LOADTOOL_BIN"
    run_preflight_checks
    provision_funds_if_enabled "$target_dir"
    start_optional_diagnostics "$target_dir"
    run_simulator "$target_dir" "$tool_out"
    drain_finished_at="$(iso_now)"
    collect_optional_diagnostics "$target_dir"
    generate_sla_report "$target_dir" "$tool_out"
    write_run_window_json "$target_dir" "$run_started_at" "$active_started_at" "$active_finished_at" "$drain_finished_at" "$grafana_available_at_run_start"
    print_grafana_links "$run_started_at" "$active_started_at" "$active_finished_at" "$drain_finished_at"
    log_phase "results written to ${target_dir}"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
