#!/bin/bash

set -euo pipefail

readonly RESULTS_DIR="results"
readonly LOAD_TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly GO_LOADTOOL_PROFILES_DIR="${LOAD_TEST_DIR}/profiles"
readonly PROFILE_SNAPSHOT_RELATIVE_PATH="inputs/profile.json"
readonly EXECUTION_PLAN_RELATIVE_PATH="inputs/execution-plan.json"
readonly SCRIPTS_DIR="${SCRIPTS_DIR:-scripts}"
readonly PREPARE_ENVIRONMENT_SCRIPT="${PREPARE_ENVIRONMENT_SCRIPT:-${SCRIPTS_DIR}/prepare-environment.sh}"
readonly LOADTOOL_CERT_SCRIPT="${LOADTOOL_CERT_SCRIPT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/infra/certs/generate-local-mtls-certs.sh}"
readonly LOADTOOL_CA_CERT="${LOADTOOL_CA_CERT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/infra/certs/local/ca/ca.crt}"
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
readonly INVALID_REPORT_EXIT=1
readonly OPERATIONAL_FAILURE_EXIT=2

RUN_TAG=""
PROFILE_NAME="uniform-smoke"
PROFILE_PATH=""
PROFILE_WARMUP_SECONDS=""
PROFILE_ACTIVE_SECONDS=""
PROFILE_DRAIN_SECONDS=""
PROFILE_PACS008_REPLAY_SHARE="-"
PROFILE_PACS008_REPLAY_DELAY_SECONDS="-"
PROFILE_PACS002_REPLAY_SHARE="-"
PROFILE_PACS002_REPLAY_DELAY_SECONDS="-"
PROFILE_SCENARIO_NAMES=()
PROFILE_SCENARIO_SHARES=()
PROFILE_SCENARIO_PAIR_NUMBER_STARTS=()
PROFILE_SCENARIO_HOT_PAIR_COUNTS=()
PROFILE_SCENARIO_COLD_PAIR_COUNTS=()
ENABLE_JFR=true
ENABLE_SPI_TRACE=true
ENABLE_POSTGRES_STATEMENTS=true
SPI_TRACE_ACTIVE=false
JFR_ACTIVE=false
POSTGRES_STATEMENTS_ACTIVE=false
POSTGRES_ACTIVITY_PID=""
POSTGRES_SERVER_LOG_SINCE=""
CONTAINER_STATS_PID=""
JFR_TARGET_DIR=""
POSTGRES_STATEMENTS_TARGET_DIR=""
LOADTOOL_BUILD_DIR=""
LOADTOOL_BIN=""
LOADTOOL_VALIDATION_FILE=""
LOADTOOL_CERT_ROOT=""
LOADTOOL_GATEWAY_CA_CERT=""
LOADTOOL_GATEWAY_SERVER_NAME="${LOADTOOL_GATEWAY_SERVER_NAME:-localhost}"
LOADTOOL_CENTRAL_TRANSFER_CA_CERT=""
LOADTOOL_CENTRAL_TRANSFER_SERVER_NAME="${LOADTOOL_CENTRAL_TRANSFER_SERVER_NAME:-localhost}"

usage() {
    echo "Usage: $(basename "$0") [--profile NAME] [--no-jfr] [--no-spi-trace] [--no-postgres-statements] <run-tag>"
    echo "Examples:"
    echo "  $(basename "$0") --profile uniform-smoke smoke-run"
    echo "  $(basename "$0") smoke-run  # defaults to uniform-smoke"
}

log_phase() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

iso_now() {
    date '+%Y-%m-%dT%H:%M:%S.%N%:z'
}

write_run_window_json() {
    local target_dir="$1"
    local run_started_at="$2"
    local loadtool_finished_at="$3"

    python3 - \
        "$RUN_TAG" \
        "$target_dir" \
        "$run_started_at" \
        "$loadtool_finished_at" \
        "$PROFILE_NAME" \
        "$PROFILE_SNAPSHOT_RELATIVE_PATH" \
        "$EXECUTION_PLAN_RELATIVE_PATH" <<'PY'
import json
import os
import sys

tag = sys.argv[1]
target_dir = sys.argv[2]
run_started_at = sys.argv[3]
loadtool_finished_at = sys.argv[4]
profile_name = sys.argv[5]
profile_snapshot = sys.argv[6]
execution_plan = sys.argv[7]
path = f"{target_dir}/run-window.json"

with open(path, encoding="utf-8") as handle:
    payload = json.load(handle)
if payload.get("schema_version") != 2:
    raise SystemExit("simulator run-window.json must use schema_version 2")
if payload.get("profile", {}).get("name") != profile_name:
    raise SystemExit("simulator run-window.json profile does not match the selected profile")
window = payload.get("window", {})
for field in ("generation_started_at", "active_started_at", "generation_ended_at", "replay_deadline_at"):
    if not isinstance(window.get(field), str) or not window[field]:
        raise SystemExit(f"simulator run-window.json is missing window.{field}")

payload["tag"] = tag
payload["result_dir"] = target_dir
payload["profile"].update({"snapshot": profile_snapshot, "execution_plan": execution_plan})
payload["artifacts"] = {
    "pacs008_starts": "events/pacs008-starts.csv",
    "pacs002_starts": "events/pacs002-starts.csv",
    "notifications": "events/notifications.csv",
    "replays": "events/replays.csv",
    "report": "sla-report.json",
}
window["run_started_at"] = run_started_at
window["loadtool_finished_at"] = loadtool_finished_at

temporary_path = path + ".tmp"
with open(temporary_path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, indent=2)
    handle.write("\n")
os.replace(temporary_path, path)
PY
}

cleanup() {
    trap - EXIT INT TERM
    if [[ -n "$POSTGRES_ACTIVITY_PID" ]]; then
        stop_postgres_activity_sampler "$POSTGRES_STATEMENTS_TARGET_DIR" || true
    fi
    if [[ -n "$CONTAINER_STATS_PID" ]]; then
        stop_container_stats "$POSTGRES_STATEMENTS_TARGET_DIR" || true
    fi
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
    if [[ -n "$LOADTOOL_CERT_ROOT" && -d "$LOADTOOL_CERT_ROOT" ]]; then
        rm -rf "$LOADTOOL_CERT_ROOT"
    fi
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --no-jfr)
                ENABLE_JFR=false
                shift
                ;;
            --no-spi-trace)
                ENABLE_SPI_TRACE=false
                shift
                ;;
            --no-postgres-statements)
                ENABLE_POSTGRES_STATEMENTS=false
                shift
                ;;
            --profile)
                if [[ $# -lt 2 ]]; then
                    usage
                    echo "--profile requires a profile name." >&2
                    exit 2
                fi
                PROFILE_NAME="$2"
                shift 2
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

shallow_validate_profile() {
    local path="$1"
    local name="$2"

    python3 - "$path" "$name" <<'PY'
import json
import sys

path = sys.argv[1]
name = sys.argv[2]

try:
    with open(path, encoding="utf-8") as handle:
        profile = json.load(handle)
    if not isinstance(profile, dict):
        raise ValueError("root must be a JSON object")
    if profile.get("name") != name:
        raise ValueError(f"name must be '{name}'")
except (OSError, json.JSONDecodeError, ValueError) as error:
    raise SystemExit(f"Profile '{name}' failed shallow validation: {error}")
PY
}

resolve_profile() {
    if [[ ! "$PROFILE_NAME" =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
        echo "Invalid profile name '${PROFILE_NAME}': use only lowercase letters, digits, and hyphens, beginning with a letter or digit." >&2
        return 2
    fi

    local candidate="${GO_LOADTOOL_PROFILES_DIR}/${PROFILE_NAME}.json"
    if [[ ! -f "$candidate" ]]; then
        echo "Profile '${PROFILE_NAME}' not found." >&2
        return 2
    fi

    local validation_error
    if ! validation_error="$(shallow_validate_profile "$candidate" "$PROFILE_NAME" 2>&1)"; then
        echo "$validation_error" >&2
        return 2
    fi

    PROFILE_PATH="$candidate"
}

validate_profile_with_loadtool() {
    local -a records

    LOADTOOL_VALIDATION_FILE="${LOADTOOL_BUILD_DIR}/profile-validation.json"
    log_phase "validating profile with Go loadtool"
    (
        cd go-loadtool
        "$LOADTOOL_BIN" validate-profile --profile "$PROFILE_NAME"
    ) > "$LOADTOOL_VALIDATION_FILE"

    mapfile -t records < <(python3 - "$LOADTOOL_VALIDATION_FILE" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)

pacs008_replay = data.get("replay", {}).get("pacs008")
pacs002_replay = data.get("replay", {}).get("pacs002")
print("\t".join([
    "metadata",
    data["profile"],
    str(data["warmupSeconds"]),
    str(data["activeSeconds"]),
    str(data["drainSeconds"]),
    str(pacs008_replay["share"]) if pacs008_replay else "-",
    str(pacs008_replay["delaySeconds"]) if pacs008_replay else "-",
    str(pacs002_replay["share"]) if pacs002_replay else "-",
    str(pacs002_replay["delaySeconds"]) if pacs002_replay else "-",
]))
for scenario in data["scenarios"]:
    participants = scenario["participants"]
    print("\t".join([
        "scenario",
        scenario["name"],
        str(scenario["share"]),
        str(participants["pairNumberStart"]),
        str(participants["hotPairCount"]),
        str(participants["coldPairCount"]),
    ]))
PY
)

    local record_kind returned_profile
    if [[ "${#records[@]}" -lt 2 ]]; then
        echo "Go loadtool returned invalid normalized metadata for profile '${PROFILE_NAME}'." >&2
        return 1
    fi
    IFS=$'\t' read -r record_kind returned_profile PROFILE_WARMUP_SECONDS PROFILE_ACTIVE_SECONDS PROFILE_DRAIN_SECONDS PROFILE_PACS008_REPLAY_SHARE PROFILE_PACS008_REPLAY_DELAY_SECONDS PROFILE_PACS002_REPLAY_SHARE PROFILE_PACS002_REPLAY_DELAY_SECONDS <<< "${records[0]}"
    if [[ "$record_kind" != metadata || "$returned_profile" != "$PROFILE_NAME" ]]; then
        echo "Go loadtool returned invalid normalized metadata for profile '${PROFILE_NAME}'." >&2
        return 1
    fi

    PROFILE_SCENARIO_NAMES=()
    PROFILE_SCENARIO_SHARES=()
    PROFILE_SCENARIO_PAIR_NUMBER_STARTS=()
    PROFILE_SCENARIO_HOT_PAIR_COUNTS=()
    PROFILE_SCENARIO_COLD_PAIR_COUNTS=()

    local scenario_name scenario_share pair_number_start hot_pair_count cold_pair_count
    local record
    for record in "${records[@]:1}"; do
        IFS=$'\t' read -r record_kind scenario_name scenario_share pair_number_start hot_pair_count cold_pair_count <<< "$record"
        if [[ "$record_kind" != scenario || -z "$scenario_name" || -z "$pair_number_start" || -z "$hot_pair_count" || -z "$cold_pair_count" ]]; then
            echo "Go loadtool returned invalid normalized scenario metadata for profile '${PROFILE_NAME}'." >&2
            return 1
        fi
        PROFILE_SCENARIO_NAMES+=("$scenario_name")
        PROFILE_SCENARIO_SHARES+=("$scenario_share")
        PROFILE_SCENARIO_PAIR_NUMBER_STARTS+=("$pair_number_start")
        PROFILE_SCENARIO_HOT_PAIR_COUNTS+=("$hot_pair_count")
        PROFILE_SCENARIO_COLD_PAIR_COUNTS+=("$cold_pair_count")
    done
    log_phase "profile validated: name=${PROFILE_NAME} scenarios=${#PROFILE_SCENARIO_NAMES[@]}"
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
        log_file="$target_dir/logs/spi-trace.log"
    fi

    log_phase "stopping SPI trace collection"
    "${SCRIPTS_DIR}/spi-trace.sh" stop >> "$log_file" 2>&1
    SPI_TRACE_ACTIVE=false
}

copy_spi_trace() {
    local target_dir="$1"
    local log_file="$target_dir/logs/spi-trace.log"

    log_phase "copying SPI trace"
    mkdir -p "$target_dir/diagnostics"
    "${SCRIPTS_DIR}/spi-trace.sh" copy "$target_dir/diagnostics" >> "$log_file" 2>&1
}

enable_postgres_statement_stats() {
    local target_dir="$1"
    local log_file="${target_dir}/logs/${POSTGRES_STATEMENTS_LOG}"
    local status

    log_phase "enabling Postgres statement stats"
    if {
        echo "Enabling Postgres statement stats at $(date --iso-8601=seconds)"
        "${SCRIPTS_DIR}/postgres-statements.sh" enable-and-reset
    } > "$log_file" 2>&1; then
        status=0
    else
        status=$?
    fi
    if ((status != 0)); then
        return "$status"
    fi
    POSTGRES_STATEMENTS_ACTIVE=true
    POSTGRES_STATEMENTS_TARGET_DIR="$target_dir"
}

capture_postgres_statement_stats() {
    local target_dir="$1"
    local output_file="${target_dir}/diagnostics/${POSTGRES_STATEMENTS_FILE}"
    local log_file="${target_dir}/logs/${POSTGRES_STATEMENTS_LOG}"
    local status=0

    log_phase "capturing Postgres statement stats"
    mkdir -p "$target_dir/diagnostics"
    {
        echo "Capturing Postgres statement stats at $(date --iso-8601=seconds)"
        if "${SCRIPTS_DIR}/postgres-statements.sh" snapshot "$output_file"; then
            echo "Postgres statement stats saved to ${output_file}"
        else
            status=$?
        fi
    } >> "$log_file" 2>&1
    return "$status"
}

disable_postgres_statement_stats() {
    local target_dir="$1"
    local log_file="/dev/null"
    if [[ -n "$target_dir" ]]; then
        log_file="${target_dir}/logs/${POSTGRES_STATEMENTS_LOG}"
    fi

    log_phase "disabling Postgres statement stats"
    local status=0
    if {
        echo "Disabling Postgres statement stats at $(date --iso-8601=seconds)"
        "${SCRIPTS_DIR}/postgres-statements.sh" disable
    } >> "$log_file" 2>&1; then
        status=0
    else
        status=$?
    fi
    POSTGRES_STATEMENTS_ACTIVE=false
    return "$status"
}

wait_for_sampler_file() {
    local pid="$1"
    local output_file="$2"
    local sampler_name="$3"

    for _ in {1..100}; do
        if [[ -s "$output_file" ]] && kill -0 "$pid" 2>/dev/null; then
            return 0
        fi
        if ! kill -0 "$pid" 2>/dev/null; then
            wait "$pid" 2>/dev/null || true
            echo "${sampler_name} stopped before producing ${output_file}." >&2
            return 1
        fi
        sleep 0.01
    done

    echo "Timed out waiting for ${sampler_name} to produce ${output_file}." >&2
    return 1
}

start_postgres_runtime_diagnostics() {
    local target_dir="$1"
    local activity_file="${target_dir}/diagnostics/${POSTGRES_ACTIVITY_FILE}"
    local io_file="${target_dir}/diagnostics/${POSTGRES_IO_FILE}"
    local log_file="${target_dir}/logs/${POSTGRES_RUNTIME_LOG}"

    mkdir -p "$target_dir/diagnostics"
    log_phase "capturing initial Postgres I/O snapshot"
    {
        echo "Capturing initial Postgres I/O snapshot at $(date --iso-8601=seconds)"
        "${SCRIPTS_DIR}/postgres-runtime.sh" snapshot-io before "$io_file"
    } > "$log_file" 2>&1

    log_phase "starting Postgres activity sampling"
    "${SCRIPTS_DIR}/postgres-runtime.sh" sample-activity "$activity_file" >> "$log_file" 2>&1 &
    POSTGRES_ACTIVITY_PID=$!
    wait_for_sampler_file "$POSTGRES_ACTIVITY_PID" "$activity_file" "Postgres activity sampler"
}

stop_postgres_activity_sampler() {
    local target_dir="$1"
    local log_file="/dev/null"
    local pid="$POSTGRES_ACTIVITY_PID"
    local failed=0
    local wait_status=0

    if [[ -z "$pid" ]]; then
        return 0
    fi
    if [[ -n "$target_dir" ]]; then
        log_file="${target_dir}/logs/${POSTGRES_RUNTIME_LOG}"
    fi

    log_phase "stopping Postgres activity sampling"
    echo "Stopping Postgres activity sampling at $(date --iso-8601=seconds)" >> "$log_file"
    if kill -0 "$pid" 2>/dev/null; then
        if ! kill -TERM "$pid" 2>/dev/null; then
            failed=1
        fi
    else
        echo "Postgres activity sampler stopped before collection." >> "$log_file"
        failed=1
    fi
    if wait "$pid"; then
        wait_status=0
    else
        wait_status=$?
        failed=1
    fi
    if ((wait_status != 0)); then
        echo "Postgres activity sampler exited with status ${wait_status}." >> "$log_file"
    fi
    POSTGRES_ACTIVITY_PID=""
    return "$failed"
}

capture_final_postgres_io() {
    local target_dir="$1"
    local io_file="${target_dir}/diagnostics/${POSTGRES_IO_FILE}"
    local log_file="${target_dir}/logs/${POSTGRES_RUNTIME_LOG}"

    log_phase "capturing final Postgres I/O snapshot"
    {
        echo "Capturing final Postgres I/O snapshot at $(date --iso-8601=seconds)"
        "${SCRIPTS_DIR}/postgres-runtime.sh" snapshot-io after "$io_file"
    } >> "$log_file" 2>&1
}

start_postgres_server_log_capture() {
    POSTGRES_SERVER_LOG_SINCE="$(iso_now)"
}

capture_postgres_server_log() {
    local target_dir="$1"
    local until="$2"
    local output_file="${target_dir}/logs/${POSTGRES_SERVER_LOG}"
    local runtime_log="${target_dir}/logs/${POSTGRES_RUNTIME_LOG}"
    local status=0

    log_phase "capturing Postgres server logs"
    if [[ -z "$POSTGRES_SERVER_LOG_SINCE" ]]; then
        echo "Postgres server-log capture has no start boundary." >> "$runtime_log"
        return 2
    fi
    if "${SCRIPTS_DIR}/postgres-server-log.sh" \
            capture "$POSTGRES_SERVER_LOG_SINCE" "$until" "$output_file" \
            >> "$runtime_log" 2>&1; then
        status=0
    else
        status=$?
    fi
    POSTGRES_SERVER_LOG_SINCE=""
    return "$status"
}

start_container_stats() {
    local target_dir="$1"
    local output_file="${target_dir}/diagnostics/${CONTAINER_STATS_FILE}"
    local log_file="${target_dir}/logs/${CONTAINER_STATS_LOG}"

    mkdir -p "$target_dir/diagnostics"
    log_phase "starting container resource sampling"
    "${SCRIPTS_DIR}/container-stats.sh" sample "$output_file" > "$log_file" 2>&1 &
    CONTAINER_STATS_PID=$!
    wait_for_sampler_file "$CONTAINER_STATS_PID" "$output_file" "Container resource sampler"
}

stop_container_stats() {
    local target_dir="$1"
    local log_file="/dev/null"
    local pid="$CONTAINER_STATS_PID"
    local failed=0
    local wait_status=0

    if [[ -z "$pid" ]]; then
        return 0
    fi
    if [[ -n "$target_dir" ]]; then
        log_file="${target_dir}/logs/${CONTAINER_STATS_LOG}"
    fi

    log_phase "stopping container resource sampling"
    echo "Stopping container resource sampling at $(date --iso-8601=seconds)" >> "$log_file"
    if kill -0 "$pid" 2>/dev/null; then
        if ! kill -TERM "$pid" 2>/dev/null; then
            failed=1
        fi
    else
        echo "Container resource sampler stopped before collection." >> "$log_file"
        failed=1
    fi
    if wait "$pid"; then
        wait_status=0
    else
        wait_status=$?
        failed=1
    fi
    if ((wait_status != 0)); then
        echo "Container resource sampler exited with status ${wait_status}." >> "$log_file"
    fi
    CONTAINER_STATS_PID=""
    return "$failed"
}

start_container_jfr() {
    local container="$1"
    local recording_name="$2"
    local container_file="$3"
    local log_file="$4"
    shift 4
    local -a event_settings=("$@")

    log_phase "starting ${container} JFR recording"
    "${SCRIPTS_DIR}/container-jfr.sh" \
        start "$container" "$recording_name" "$container_file" \
        "${event_settings[@]}" > "$log_file" 2>&1
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

    mkdir -p "$target_dir/logs/jfr" "$target_dir/diagnostics/jfr"
    JFR_TARGET_DIR="$target_dir"
    JFR_ACTIVE=true

    start_container_jfr \
        "$KAFKA_PRODUCER_CONTAINER" \
        "kafka-producer-load-test" \
        "/tmp/kafka-producer-load-test.jfr" \
        "${target_dir}/logs/jfr/kafka-producer.log" \
        'jdk.TLSHandshake#enabled=true'
    start_container_jfr "$SPI_CONTAINER" "spi-load-test" "/tmp/spi-load-test.jfr" "${target_dir}/logs/jfr/spi.log"
    start_container_jfr "$NOTIFICATION_GATEWAY_CONTAINER" "notification-gateway-load-test" "/tmp/notification-gateway-load-test.jfr" "${target_dir}/logs/jfr/notification-gateway.log"
}

stop_jfr_recordings() {
    local target_dir="$1"
    local failed=0

    stop_container_jfr "$KAFKA_PRODUCER_CONTAINER" "kafka-producer-load-test" "/tmp/kafka-producer-load-test.jfr" "${target_dir}/diagnostics/jfr/kafka-producer.jfr" "${target_dir}/logs/jfr/kafka-producer.log" || failed=1
    stop_container_jfr "$SPI_CONTAINER" "spi-load-test" "/tmp/spi-load-test.jfr" "${target_dir}/diagnostics/jfr/spi.jfr" "${target_dir}/logs/jfr/spi.log" || failed=1
    stop_container_jfr "$NOTIFICATION_GATEWAY_CONTAINER" "notification-gateway-load-test" "/tmp/notification-gateway-load-test.jfr" "${target_dir}/diagnostics/jfr/notification-gateway.jfr" "${target_dir}/logs/jfr/notification-gateway.log" || failed=1

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
    local scenario_index pair_count

    log_phase "starting load test: tag=${RUN_TAG} profile=${PROFILE_NAME} output=${target_dir}"
    log_phase "using profile: ${PROFILE_NAME}"
    log_phase "execution window: warmup=${PROFILE_WARMUP_SECONDS}s active=${PROFILE_ACTIVE_SECONDS}s drain=${PROFILE_DRAIN_SECONDS}s"
    if [[ "$PROFILE_PACS008_REPLAY_SHARE" != - ]]; then
        log_phase "pacs.008 replay: share=${PROFILE_PACS008_REPLAY_SHARE} delay=${PROFILE_PACS008_REPLAY_DELAY_SECONDS}s"
    fi
    if [[ "$PROFILE_PACS002_REPLAY_SHARE" != - ]]; then
        log_phase "pacs.002 replay: share=${PROFILE_PACS002_REPLAY_SHARE} delay=${PROFILE_PACS002_REPLAY_DELAY_SECONDS}s"
    fi
    for scenario_index in "${!PROFILE_SCENARIO_NAMES[@]}"; do
        pair_count=$((PROFILE_SCENARIO_HOT_PAIR_COUNTS[scenario_index] + PROFILE_SCENARIO_COLD_PAIR_COUNTS[scenario_index]))
        log_phase "scenario: name=${PROFILE_SCENARIO_NAMES[scenario_index]} share=${PROFILE_SCENARIO_SHARES[scenario_index]} pair_number_start=${PROFILE_SCENARIO_PAIR_NUMBER_STARTS[scenario_index]} pairs=${pair_count}"
    done
    log_phase "load-tool notification mTLS enabled: server_name=${LOADTOOL_GATEWAY_SERVER_NAME}"
    log_phase "load-tool central transfer mTLS enabled: server_name=${LOADTOOL_CENTRAL_TRANSFER_SERVER_NAME}"
    if [[ "$ENABLE_JFR" == true ]]; then
        log_phase "JFR enabled for kafka-producer, SPI, and notification-gateway"
    fi
    if [[ "$ENABLE_SPI_TRACE" == true ]]; then
        log_phase "SPI trace collection enabled"
    fi
    if [[ "$ENABLE_POSTGRES_STATEMENTS" == true ]]; then
        log_phase "Postgres statement, activity, I/O, lock-wait logs, and container stats enabled"
    fi
}

prepare_loadtool_binary() {
    LOADTOOL_BUILD_DIR="$(mktemp -d)"
    LOADTOOL_BIN="${LOADTOOL_BUILD_DIR}/go-loadtool"
}

prepare_run_workspace() {
    local target_dir="$1"

    mkdir -p "$target_dir/inputs" "$target_dir/logs"
    copy_profile_snapshot "$target_dir"
    cp "$LOADTOOL_VALIDATION_FILE" "${target_dir}/${EXECUTION_PLAN_RELATIVE_PATH}"
}

copy_profile_snapshot() {
    local target_dir="$1"

    mkdir -p "$target_dir/inputs"
    cp "$PROFILE_PATH" "${target_dir}/${PROFILE_SNAPSHOT_RELATIVE_PATH}"
}

run_preflight_checks() {
    log_phase "ensuring SPI trace is stopped"
    stop_spi_trace ""
}

prepare_environment() {
    local target_dir="$1"
    local absolute_target_dir log_file status

    absolute_target_dir="$(cd "$target_dir" && pwd)"
    log_file="${target_dir}/logs/prepare-environment.log"
    log_phase "preparing load-test environment"
    if "$PREPARE_ENVIRONMENT_SCRIPT" --run-dir "$absolute_target_dir" > "$log_file" 2>&1; then
        log_phase "load-test environment prepared"
        return 0
    else
        status=$?
    fi
    cat "$log_file" >&2
    return "$status"
}

prepare_loadtool_certificates() {
    local target_dir="$1"
    local cert_script ca_cert target_dir_abs
    local scenario_index pair_number_start pair_count last_pair pair_number suffix total_psps=0

    target_dir_abs="$(cd "$target_dir" && pwd)"
    LOADTOOL_CERT_ROOT="${target_dir_abs}/certs"
    cert_script="$LOADTOOL_CERT_SCRIPT"
    ca_cert="$LOADTOOL_CA_CERT"

    if [[ ! -f "$ca_cert" ]]; then
        echo "Local mTLS CA not found: $ca_cert" >&2
        echo "Run from repo root: LOCAL_UID=\$(id -u) LOCAL_GID=\$(id -g) docker compose -f infra/docker-compose.yml up certs-init" >&2
        exit 1
    fi
    if [[ ! -x "$cert_script" ]]; then
        echo "Certificate generator not found or not executable: $cert_script" >&2
        exit 1
    fi

    mkdir -p "$LOADTOOL_CERT_ROOT"
    LOADTOOL_GATEWAY_CA_CERT="$(cd "$(dirname "$ca_cert")" && pwd)/$(basename "$ca_cert")"
    LOADTOOL_CENTRAL_TRANSFER_CA_CERT="$LOADTOOL_GATEWAY_CA_CERT"

    for scenario_index in "${!PROFILE_SCENARIO_NAMES[@]}"; do
        pair_count=$((PROFILE_SCENARIO_HOT_PAIR_COUNTS[scenario_index] + PROFILE_SCENARIO_COLD_PAIR_COUNTS[scenario_index]))
        total_psps=$((total_psps + pair_count * 2))
    done
    log_phase "generating ephemeral load-tool PSP certificates: psps=${total_psps} root=${LOADTOOL_CERT_ROOT}"
    for scenario_index in "${!PROFILE_SCENARIO_NAMES[@]}"; do
        pair_number_start="${PROFILE_SCENARIO_PAIR_NUMBER_STARTS[scenario_index]}"
        pair_count=$((PROFILE_SCENARIO_HOT_PAIR_COUNTS[scenario_index] + PROFILE_SCENARIO_COLD_PAIR_COUNTS[scenario_index]))
        last_pair=$((pair_number_start + pair_count - 1))
        for pair_number in $(seq "$pair_number_start" "$last_pair"); do
            suffix="$(printf "%06d" "$pair_number")"
            "$cert_script" --psp-root "$LOADTOOL_CERT_ROOT" psp "10${suffix}" >/dev/null
            "$cert_script" --psp-root "$LOADTOOL_CERT_ROOT" psp "20${suffix}" >/dev/null
        done
    done
    log_phase "ephemeral load-tool PSP certificates generated"
}

start_optional_diagnostics() {
    local target_dir="$1"

    if [[ "$ENABLE_POSTGRES_STATEMENTS" == true ]]; then
        start_postgres_server_log_capture
        enable_postgres_statement_stats "$target_dir"
        start_postgres_runtime_diagnostics "$target_dir"
        start_container_stats "$target_dir"
    fi
    if [[ "$ENABLE_JFR" == true ]]; then
        start_jfr_recordings "$target_dir"
    fi
    if [[ "$ENABLE_SPI_TRACE" == true ]]; then
        start_spi_trace "${target_dir}/logs/spi-trace.log"
    fi
}

collect_optional_diagnostics() {
    local target_dir="$1"
    local failed=0
    local postgres_server_log_until=""

    if [[ "$ENABLE_JFR" == true ]]; then
        stop_jfr_recordings "$target_dir" || failed=1
    fi
    if [[ "$ENABLE_SPI_TRACE" == true ]]; then
        stop_spi_trace "$target_dir" || failed=1
        copy_spi_trace "$target_dir" || failed=1
    fi
    if [[ "$ENABLE_POSTGRES_STATEMENTS" == true ]]; then
        postgres_server_log_until="$(iso_now)"
        stop_postgres_activity_sampler "$target_dir" || failed=1
        stop_container_stats "$target_dir" || failed=1
        capture_final_postgres_io "$target_dir" || failed=1
        capture_postgres_statement_stats "$target_dir" || failed=1
        disable_postgres_statement_stats "$target_dir" || failed=1
        capture_postgres_server_log "$target_dir" "$postgres_server_log_until" || failed=1
    fi
    return "$failed"
}

run_loadtool() {
    local target_dir="$1"
    local absolute_target_dir
    local -a pipeline_status

    absolute_target_dir="$(cd "$target_dir" && pwd)"

    log_phase "starting load-tool run"
    (
        cd go-loadtool
        "$LOADTOOL_BIN" run \
            --run-dir "$absolute_target_dir" \
            --central-transfer-ca-cert "$LOADTOOL_CENTRAL_TRANSFER_CA_CERT" \
            --central-transfer-client-cert-root "$LOADTOOL_CERT_ROOT" \
            --central-transfer-server-name "$LOADTOOL_CENTRAL_TRANSFER_SERVER_NAME" \
            --gateway-ca-cert "$LOADTOOL_GATEWAY_CA_CERT" \
            --gateway-client-cert-root "$LOADTOOL_CERT_ROOT" \
            --gateway-server-name "$LOADTOOL_GATEWAY_SERVER_NAME"
    ) 2>&1 | tee "${target_dir}/logs/loadtool.log"
    pipeline_status=("${PIPESTATUS[@]}")
    if ((pipeline_status[0] != 0)); then
        return "${pipeline_status[0]}"
    fi
    return "${pipeline_status[1]}"
}

validate_sla_report() {
    local report_path="$1"

    python3 - "$report_path" <<'PY'
import json
import sys

path = sys.argv[1]
try:
    with open(path, encoding="utf-8") as handle:
        document = json.load(handle)
except (OSError, json.JSONDecodeError) as error:
    print(f"invalid SLA report {path!r}: {error}", file=sys.stderr)
    raise SystemExit(2)

if not isinstance(document, dict):
    print(f"invalid SLA report {path!r}: root must be an object", file=sys.stderr)
    raise SystemExit(2)
if type(document.get("valid")) is not bool:
    print(f"invalid SLA report {path!r}: valid must be a boolean", file=sys.stderr)
    raise SystemExit(2)
if not document["valid"]:
    print("SLA report is invalid", file=sys.stderr)
    raise SystemExit(1)
PY
}

main() {
    parse_args "$@"
    if ! resolve_profile; then
        return "$OPERATIONAL_FAILURE_EXIT"
    fi
    if ! prepare_loadtool_binary; then
        return "$OPERATIONAL_FAILURE_EXIT"
    fi
    trap cleanup EXIT INT TERM
    if ! build_loadtool "$LOADTOOL_BIN"; then
        return "$OPERATIONAL_FAILURE_EXIT"
    fi
    if ! validate_profile_with_loadtool; then
        return "$OPERATIONAL_FAILURE_EXIT"
    fi

    local timestamp target_dir
    local run_started_at loadtool_finished_at
    local loadtool_status diagnostics_status
    timestamp="$(date +%Y%m%d_%H%M%S)"
    target_dir="${RESULTS_DIR}/${RUN_TAG}/${timestamp}"
    run_started_at="$(iso_now)"

    if ! prepare_run_workspace "$target_dir"; then
        return "$OPERATIONAL_FAILURE_EXIT"
    fi
    if ! prepare_loadtool_certificates "$target_dir"; then
        return "$OPERATIONAL_FAILURE_EXIT"
    fi

    log_selected_options "$target_dir"
    if ! run_preflight_checks; then
        return "$OPERATIONAL_FAILURE_EXIT"
    fi
    if ! prepare_environment "$target_dir"; then
        return "$OPERATIONAL_FAILURE_EXIT"
    fi
    if ! start_optional_diagnostics "$target_dir"; then
        return "$OPERATIONAL_FAILURE_EXIT"
    fi
    if run_loadtool "$target_dir"; then
        loadtool_status=0
    else
        loadtool_status=$?
    fi
    loadtool_finished_at="$(iso_now)"
    if collect_optional_diagnostics "$target_dir"; then
        diagnostics_status=0
    else
        diagnostics_status=$?
    fi
    if ((loadtool_status != 0 || diagnostics_status != 0)); then
        return "$OPERATIONAL_FAILURE_EXIT"
    fi
    if ! write_run_window_json "$target_dir" "$run_started_at" "$loadtool_finished_at"; then
        return "$OPERATIONAL_FAILURE_EXIT"
    fi
    local report_status
    if validate_sla_report "${target_dir}/sla-report.json"; then
        report_status=0
    else
        report_status=$?
    fi
    if ((report_status == INVALID_REPORT_EXIT)); then
        return "$INVALID_REPORT_EXIT"
    fi
    if ((report_status != 0)); then
        return "$OPERATIONAL_FAILURE_EXIT"
    fi
    log_phase "results written to ${target_dir}"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
