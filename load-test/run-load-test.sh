#!/bin/bash

set -euo pipefail

readonly RESULTS_DIR="results"
readonly LOAD_TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly GO_LOADTOOL_PROFILES_DIR="${LOAD_TEST_DIR}/profiles"
readonly PROFILE_SNAPSHOT_FILENAME="profile.json"
readonly EXECUTION_PLAN_FILENAME="execution-plan.json"
readonly SCRIPTS_DIR="${SCRIPTS_DIR:-scripts}"
readonly PROVISION_FUNDS_SCRIPT="${PROVISION_FUNDS_SCRIPT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/scripts/provision-funds.sh}"
readonly LOADTOOL_CERT_SCRIPT="${LOADTOOL_CERT_SCRIPT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/infra/certs/generate-local-mtls-certs.sh}"
readonly LOADTOOL_CA_CERT="${LOADTOOL_CA_CERT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/infra/certs/local/ca/ca.crt}"
readonly SPI_CONTAINER="spi"
readonly KAFKA_PRODUCER_CONTAINER="kafka-producer"
readonly NOTIFICATION_GATEWAY_CONTAINER="notification-gateway"
readonly KAFKA_CONTAINER="kafka"
readonly SPI_PAYMENT_REQUEST_CONSUMER_GROUP="spi-payment-request-consumer-group"
readonly SPI_STATUS_REPORT_CONSUMER_GROUP="spi-status-report-consumer-group"
readonly NOTIFICATION_GATEWAY_CONSUMER_GROUP="notification-gateway-group"
readonly SPI_PAYMENT_REQUEST_TOPIC="spi-payment-requests"
readonly SPI_STATUS_REPORT_TOPIC="spi-payment-status-reports"
readonly PSP_NOTIFICATIONS_TOPIC="psp-notifications"
readonly POSTGRES_STATEMENTS_FILE="postgres-statements.csv"
readonly POSTGRES_STATEMENTS_LOG="postgres-statements.log"
readonly GRAFANA_BASE_URL="${GRAFANA_BASE_URL:-http://localhost:3000}"
readonly GRAFANA_DASHBOARD_PATH="/d/load-test/load-test"
readonly KAFKA_CLI_TIMEOUT_SECONDS="${KAFKA_CLI_TIMEOUT_SECONDS:-15}"

RUN_TAG=""
PROFILE_NAME="uniform-smoke"
PROFILE_PATH=""
PROFILE_SCHEMA_VERSION=""
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
PROFILE_SCENARIO_PAYER_BALANCES=()
PROFILE_SCENARIO_RECEIVER_BALANCES=()
PROFILE_SCENARIO_RESET_BEHAVIORS=()
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
LOADTOOL_VALIDATION_FILE=""
LOADTOOL_CERT_ROOT=""
LOADTOOL_GATEWAY_CA_CERT=""
LOADTOOL_GATEWAY_SERVER_NAME="${LOADTOOL_GATEWAY_SERVER_NAME:-localhost}"
LOADTOOL_CENTRAL_TRANSFER_CA_CERT=""
LOADTOOL_CENTRAL_TRANSFER_SERVER_NAME="${LOADTOOL_CENTRAL_TRANSFER_SERVER_NAME:-localhost}"

usage() {
    echo "Usage: $(basename "$0") [--profile NAME] [--jfr] [--spi-trace] [--postgres-statements] [--provision-funds|--no-provision-funds] <run-tag>"
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
    local loadtool_finished_at="$3"
    local grafana_available_at_run_start="$4"

    python3 - \
        "$RUN_TAG" \
        "$target_dir" \
        "$run_started_at" \
        "$loadtool_finished_at" \
        "$grafana_available_at_run_start" \
        "$GRAFANA_BASE_URL" \
        "$GRAFANA_DASHBOARD_PATH" \
        "$PROFILE_NAME" \
        "$PROFILE_SNAPSHOT_FILENAME" \
        "$EXECUTION_PLAN_FILENAME" <<'PY'
import json
import os
import sys
from urllib.parse import quote

tag = sys.argv[1]
target_dir = sys.argv[2]
run_started_at = sys.argv[3]
loadtool_finished_at = sys.argv[4]
grafana_available = sys.argv[5].lower() == "true"
base_url = sys.argv[6]
dashboard_path = sys.argv[7]
profile_name = sys.argv[8]
profile_snapshot = sys.argv[9]
execution_plan = sys.argv[10]
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

def dashboard_url(start, end):
    return f"{base_url}{dashboard_path}?from={quote(start, safe='')}&to={quote(end, safe='')}"

payload["tag"] = tag
payload["result_dir"] = target_dir
payload["profile"].update({"snapshot": profile_snapshot, "execution_plan": execution_plan})
payload["artifacts"] = {
    "starts": "go-loadtool/starts.csv",
    "events": "go-loadtool/events.csv",
    "replays": "go-loadtool/replays.csv",
    "status_starts": "go-loadtool/status-starts.csv",
    "report": "sla-report.json",
}
window["run_started_at"] = run_started_at
window.pop("drain_finished_at", None)
window["loadtool_finished_at"] = loadtool_finished_at
payload["grafana"] = {
    "available_at_run_start": grafana_available,
    "base_url": base_url,
    "full_run_url": dashboard_url(run_started_at, loadtool_finished_at),
    "active_window_url": dashboard_url(window["active_started_at"], window["generation_ended_at"]),
}

temporary_path = path + ".tmp"
with open(temporary_path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, indent=2)
    handle.write("\n")
os.replace(temporary_path, path)
PY
}

print_grafana_links() {
    local target_dir="$1"
    local -a urls
    mapfile -t urls < <(python3 - "${target_dir}/run-window.json" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as handle:
    grafana = json.load(handle)["grafana"]
print(grafana["full_run_url"])
print(grafana["active_window_url"])
PY
)
    log_phase "Grafana full run: ${urls[0]}"
    log_phase "Grafana active window: ${urls[1]}"
}

cleanup() {
    trap - EXIT INT TERM
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
    if type(profile.get("schemaVersion")) is not int or profile["schemaVersion"] != 1:
        raise ValueError("schemaVersion must be 1")
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
    str(data["schemaVersion"]),
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
    provisioning = scenario["provisioning"]
    print("\t".join([
        "scenario",
        scenario["name"],
        str(scenario["share"]),
        str(participants["pairNumberStart"]),
        str(participants["hotPairCount"]),
        str(participants["coldPairCount"]),
        str(provisioning["payerBalance"]),
        str(provisioning["receiverBalance"]),
        str(provisioning["resetIfExists"]).lower(),
    ]))
PY
)

    local record_kind returned_profile
    if [[ "${#records[@]}" -lt 2 ]]; then
        echo "Go loadtool returned invalid normalized metadata for profile '${PROFILE_NAME}'." >&2
        return 1
    fi
    IFS=$'\t' read -r record_kind returned_profile PROFILE_SCHEMA_VERSION PROFILE_WARMUP_SECONDS PROFILE_ACTIVE_SECONDS PROFILE_DRAIN_SECONDS PROFILE_PACS008_REPLAY_SHARE PROFILE_PACS008_REPLAY_DELAY_SECONDS PROFILE_PACS002_REPLAY_SHARE PROFILE_PACS002_REPLAY_DELAY_SECONDS <<< "${records[0]}"
    if [[ "$record_kind" != metadata || "$returned_profile" != "$PROFILE_NAME" ]]; then
        echo "Go loadtool returned invalid normalized metadata for profile '${PROFILE_NAME}'." >&2
        return 1
    fi

    PROFILE_SCENARIO_NAMES=()
    PROFILE_SCENARIO_SHARES=()
    PROFILE_SCENARIO_PAIR_NUMBER_STARTS=()
    PROFILE_SCENARIO_HOT_PAIR_COUNTS=()
    PROFILE_SCENARIO_COLD_PAIR_COUNTS=()
    PROFILE_SCENARIO_PAYER_BALANCES=()
    PROFILE_SCENARIO_RECEIVER_BALANCES=()
    PROFILE_SCENARIO_RESET_BEHAVIORS=()

    local scenario_name scenario_share pair_number_start hot_pair_count cold_pair_count payer_balance receiver_balance reset_if_exists
    local record
    for record in "${records[@]:1}"; do
        IFS=$'\t' read -r record_kind scenario_name scenario_share pair_number_start hot_pair_count cold_pair_count payer_balance receiver_balance reset_if_exists <<< "$record"
        if [[ "$record_kind" != scenario || -z "$scenario_name" || -z "$pair_number_start" || -z "$hot_pair_count" || -z "$cold_pair_count" || -z "$payer_balance" || -z "$receiver_balance" || -z "$reset_if_exists" ]]; then
            echo "Go loadtool returned invalid normalized scenario metadata for profile '${PROFILE_NAME}'." >&2
            return 1
        fi
        PROFILE_SCENARIO_NAMES+=("$scenario_name")
        PROFILE_SCENARIO_SHARES+=("$scenario_share")
        PROFILE_SCENARIO_PAIR_NUMBER_STARTS+=("$pair_number_start")
        PROFILE_SCENARIO_HOT_PAIR_COUNTS+=("$hot_pair_count")
        PROFILE_SCENARIO_COLD_PAIR_COUNTS+=("$cold_pair_count")
        PROFILE_SCENARIO_PAYER_BALANCES+=("$payer_balance")
        PROFILE_SCENARIO_RECEIVER_BALANCES+=("$receiver_balance")
        PROFILE_SCENARIO_RESET_BEHAVIORS+=("$reset_if_exists")
    done
    log_phase "profile validated: name=${PROFILE_NAME} schema=${PROFILE_SCHEMA_VERSION} scenarios=${#PROFILE_SCENARIO_NAMES[@]}"
}

consumer_group_topic_lag() {
    local consumer_group="$1"
    local topic="$2"
    local group_output lag

    if ! group_output="$(timeout "$KAFKA_CLI_TIMEOUT_SECONDS" docker exec "$KAFKA_CONTAINER" kafka-consumer-groups \
            --bootstrap-server kafka:9092 \
            --describe \
            --group "$consumer_group" 2>&1)"; then
        echo "Failed to read Kafka consumer group lag for ${consumer_group} within ${KAFKA_CLI_TIMEOUT_SECONDS}s." >&2
        echo "$group_output" >&2
        return 1
    fi

    lag="$(echo "$group_output" |
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
    local offset_output

    if ! offset_output="$(timeout "$KAFKA_CLI_TIMEOUT_SECONDS" docker exec "$KAFKA_CONTAINER" kafka-get-offsets \
        --bootstrap-server kafka:9092 \
        --topic "$topic" \
        --time -1 2>&1)"; then
        echo "Failed to read Kafka end offsets for ${topic} within ${KAFKA_CLI_TIMEOUT_SECONDS}s." >&2
        echo "$offset_output" >&2
        return 1
    fi

    echo "$offset_output" |
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

current_notification_gateway_lag() {
    consumer_group_topic_lag "$NOTIFICATION_GATEWAY_CONSUMER_GROUP" "$PSP_NOTIFICATIONS_TOPIC"
}

assert_no_initial_kafka_lag() {
    local lag
    lag="$(current_spi_input_lag)"

    if (( lag > 0 )); then
        echo "Refusing to start load test: SPI input consumer groups have ${lag} messages of lag." >&2
        echo "Checked ${SPI_PAYMENT_REQUEST_CONSUMER_GROUP}/${SPI_PAYMENT_REQUEST_TOPIC} and ${SPI_STATUS_REPORT_CONSUMER_GROUP}/${SPI_STATUS_REPORT_TOPIC}." >&2
        echo "Wait for the backlog to drain or reset the Kafka/Postgres test environment before starting a new measured run." >&2
        exit 1
    fi

    lag="$(current_notification_gateway_lag)"

    if (( lag > 0 )); then
        echo "Refusing to start load test: notification-gateway has ${lag} messages of lag on ${PSP_NOTIFICATIONS_TOPIC}." >&2
        echo "Old PSP notifications would be delivered during the new run and contaminate SLA results." >&2
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
        log_phase "Postgres statement stats enabled"
    fi
}

prepare_loadtool_binary() {
    LOADTOOL_BUILD_DIR="$(mktemp -d)"
    LOADTOOL_BIN="${LOADTOOL_BUILD_DIR}/go-loadtool"
}

prepare_run_workspace() {
    local target_dir="$1"

    mkdir -p "$target_dir"
    copy_profile_snapshot "$target_dir"
    cp "$LOADTOOL_VALIDATION_FILE" "${target_dir}/${EXECUTION_PLAN_FILENAME}"
}

copy_profile_snapshot() {
    local target_dir="$1"

    cp "$PROFILE_PATH" "${target_dir}/${PROFILE_SNAPSHOT_FILENAME}"
}

run_preflight_checks() {
    log_phase "checking initial Kafka lag"
    assert_no_initial_kafka_lag
    log_phase "initial Kafka lag is zero"

    log_phase "ensuring SPI trace is stopped"
    stop_spi_trace ""
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

provision_funds_if_enabled() {
    local target_dir="$1"
    local scenario_index pair_number_start pair_count last_pair pair_number suffix
    local role balance
    local -a funding_args

    if [[ "$PROVISION_FUNDS" == true ]]; then
        log_phase "provisioning funds"
        : > "${target_dir}/provision-funds.log"
        for scenario_index in "${!PROFILE_SCENARIO_NAMES[@]}"; do
            for role in payer receiver; do
                if [[ "$role" == payer ]]; then
                    balance="${PROFILE_SCENARIO_PAYER_BALANCES[scenario_index]}"
                else
                    balance="${PROFILE_SCENARIO_RECEIVER_BALANCES[scenario_index]}"
                fi
                funding_args=(--balance "$balance")
                if [[ "${PROFILE_SCENARIO_RESET_BEHAVIORS[scenario_index]}" == true ]]; then
                    funding_args+=(--reset-if-exists)
                else
                    funding_args+=(--preserve-if-exists)
                fi
                pair_number_start="${PROFILE_SCENARIO_PAIR_NUMBER_STARTS[scenario_index]}"
                pair_count=$((PROFILE_SCENARIO_HOT_PAIR_COUNTS[scenario_index] + PROFILE_SCENARIO_COLD_PAIR_COUNTS[scenario_index]))
                last_pair=$((pair_number_start + pair_count - 1))
                for pair_number in $(seq "$pair_number_start" "$last_pair"); do
                    suffix="$(printf "%06d" "$pair_number")"
                    if [[ "$role" == payer ]]; then
                        funding_args+=(--ispb "10${suffix}")
                    else
                        funding_args+=(--ispb "20${suffix}")
                    fi
                done
                "$PROVISION_FUNDS_SCRIPT" "${funding_args[@]}" >> "${target_dir}/provision-funds.log" 2>&1
            done
        done
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
    ) | tee "${target_dir}/go-loadtool-output.txt"
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
    raise SystemExit(f"invalid SLA report {path!r}: {error}")

violations = []

def visit(value, location="$"):
    if isinstance(value, dict):
        for key, child in value.items():
            child_location = f"{location}.{key}"
            if key == "violations":
                if type(child) is not int or child < 0:
                    raise SystemExit(
                        f"invalid SLA report {path!r}: {child_location} must be a non-negative integer"
                    )
                violations.append((child_location, child))
            visit(child, child_location)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            visit(child, f"{location}[{index}]")

visit(document)
if not violations:
    raise SystemExit(f"invalid SLA report {path!r}: no violations fields found")

failed = [(location, count) for location, count in violations if count > 0]
if failed:
    details = ", ".join(f"{location}={count}" for location, count in failed)
    raise SystemExit(f"SLA report contains violations: {details}")
PY
}

main() {
    parse_args "$@"
    resolve_profile
    prepare_loadtool_binary
    trap cleanup EXIT INT TERM
    build_loadtool "$LOADTOOL_BIN"
    validate_profile_with_loadtool

    local timestamp target_dir
    local run_started_at loadtool_finished_at grafana_available_at_run_start
    local loadtool_status diagnostics_status
    timestamp="$(date +%Y%m%d_%H%M%S)"
    target_dir="${RESULTS_DIR}/${RUN_TAG}/${timestamp}"
    run_started_at="$(iso_now)"

    prepare_run_workspace "$target_dir"
    prepare_loadtool_certificates "$target_dir"
    if grafana_available; then
        grafana_available_at_run_start=true
    else
        grafana_available_at_run_start=false
    fi

    log_selected_options "$target_dir"
    log_grafana_status "$grafana_available_at_run_start"
    run_preflight_checks
    provision_funds_if_enabled "$target_dir"
    start_optional_diagnostics "$target_dir"
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
    if ((loadtool_status != 0)); then
        return "$loadtool_status"
    fi
    if ((diagnostics_status != 0)); then
        return "$diagnostics_status"
    fi
    write_run_window_json "$target_dir" "$run_started_at" "$loadtool_finished_at" "$grafana_available_at_run_start"
    validate_sla_report "${target_dir}/sla-report.json"
    print_grafana_links "$target_dir"
    log_phase "results written to ${target_dir}"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
