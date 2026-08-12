#!/bin/bash

set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
readonly PROVISION_FUNDS_SCRIPT="${PROVISION_FUNDS_SCRIPT:-${REPOSITORY_ROOT}/scripts/provision-funds.sh}"
readonly KAFKA_CONTAINER="${KAFKA_CONTAINER:-kafka}"
readonly KAFKA_BOOTSTRAP_SERVER="${KAFKA_BOOTSTRAP_SERVER:-kafka:9092}"
readonly SPI_PAYMENT_REQUEST_CONSUMER_GROUP="${SPI_PAYMENT_REQUEST_CONSUMER_GROUP:-spi-payment-request-consumer-group}"
readonly SPI_STATUS_REPORT_CONSUMER_GROUP="${SPI_STATUS_REPORT_CONSUMER_GROUP:-spi-status-report-consumer-group}"
readonly NOTIFICATION_GATEWAY_CONSUMER_GROUP="${NOTIFICATION_GATEWAY_CONSUMER_GROUP:-notification-gateway-group}"
readonly SPI_PAYMENT_REQUEST_TOPIC="${SPI_PAYMENT_REQUEST_TOPIC:-spi-payment-requests}"
readonly SPI_STATUS_REPORT_TOPIC="${SPI_STATUS_REPORT_TOPIC:-spi-payment-status-reports}"
readonly PSP_NOTIFICATIONS_TOPIC="${PSP_NOTIFICATIONS_TOPIC:-psp-notifications}"
readonly KAFKA_CLI_TIMEOUT_SECONDS="${KAFKA_CLI_TIMEOUT_SECONDS:-15}"

RUN_DIR=""
EXECUTION_PLAN=""
PROVISIONING_RECORDS=()

usage() {
    echo "Usage: $(basename "$0") --run-dir DIR"
}

parse_args() {
    if [[ $# -ne 2 || "$1" != --run-dir || -z "$2" ]]; then
        usage >&2
        return 2
    fi
    RUN_DIR="$2"
}

resolve_execution_plan() {
    if [[ ! -d "$RUN_DIR" ]]; then
        echo "Run directory does not exist or is not a directory: $RUN_DIR" >&2
        return 1
    fi
    RUN_DIR="$(cd "$RUN_DIR" && pwd)"
    EXECUTION_PLAN="${RUN_DIR}/inputs/execution-plan.json"
    if [[ ! -f "$EXECUTION_PLAN" ]]; then
        echo "Execution plan does not exist or is not a regular file: $EXECUTION_PLAN" >&2
        return 1
    fi
    if [[ ! -x "$PROVISION_FUNDS_SCRIPT" ]]; then
        echo "Fund provisioning adapter does not exist or is not executable: $PROVISION_FUNDS_SCRIPT" >&2
        return 1
    fi
}

load_provisioning_records() {
    local normalized
    if ! normalized="$(python3 - "$EXECUTION_PLAN" <<'PY'
import json
import sys

path = sys.argv[1]

try:
    with open(path, encoding="utf-8") as handle:
        document = json.load(handle)
    if not isinstance(document, dict):
        raise ValueError("root must be a JSON object")
    scenarios = document.get("scenarios")
    if not isinstance(scenarios, list) or not scenarios:
        raise ValueError("scenarios must be a non-empty array")

    records = []
    for index, scenario in enumerate(scenarios):
        location = f"scenarios[{index}]"
        if not isinstance(scenario, dict):
            raise ValueError(f"{location} must be an object")
        participants = scenario.get("participants")
        provisioning = scenario.get("provisioning")
        if not isinstance(participants, dict):
            raise ValueError(f"{location}.participants must be an object")
        if not isinstance(provisioning, dict):
            raise ValueError(f"{location}.provisioning must be an object")

        numeric_fields = []
        for field in ("pairNumberStart", "hotPairCount", "coldPairCount"):
            value = participants.get(field)
            if type(value) is not int:
                raise ValueError(f"{location}.participants.{field} must be an integer")
            numeric_fields.append(value)
        pair_number_start, hot_pair_count, cold_pair_count = numeric_fields
        pair_count = hot_pair_count + cold_pair_count
        if pair_number_start < 1 or hot_pair_count < 0 or cold_pair_count < 0 or pair_count < 1:
            raise ValueError(f"{location}.participants does not describe a usable participant range")
        if pair_number_start + pair_count - 1 > 999999:
            raise ValueError(f"{location}.participants exceeds the six-digit ISPB suffix range")

        balances = []
        for field in ("payerBalance", "receiverBalance"):
            value = provisioning.get(field)
            if not isinstance(value, str) or not value or any(character.isspace() for character in value):
                raise ValueError(f"{location}.provisioning.{field} must be a non-empty string without whitespace")
            balances.append(value)

        reset_if_exists = provisioning.get("resetIfExists")
        if type(reset_if_exists) is not bool:
            raise ValueError(f"{location}.provisioning.resetIfExists must be a boolean")

        records.append((*numeric_fields, *balances, str(reset_if_exists).lower()))
except (OSError, json.JSONDecodeError, ValueError) as error:
    raise SystemExit(f"Invalid execution plan {path!r}: {error}")

for record in records:
    print("\t".join(str(value) for value in record))
PY
)"; then
        echo "$normalized" >&2
        return 1
    fi
    mapfile -t PROVISIONING_RECORDS <<< "$normalized"
}

consumer_group_topic_lag() {
    local consumer_group="$1"
    local topic="$2"
    local group_output lag

    if ! group_output="$(timeout "$KAFKA_CLI_TIMEOUT_SECONDS" docker exec "$KAFKA_CONTAINER" kafka-consumer-groups \
            --bootstrap-server "$KAFKA_BOOTSTRAP_SERVER" \
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
            --bootstrap-server "$KAFKA_BOOTSTRAP_SERVER" \
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
    local payment_lag status_lag status

    if payment_lag="$(consumer_group_topic_lag "$SPI_PAYMENT_REQUEST_CONSUMER_GROUP" "$SPI_PAYMENT_REQUEST_TOPIC")"; then
        :
    else
        status=$?
        return "$status"
    fi
    if status_lag="$(consumer_group_topic_lag "$SPI_STATUS_REPORT_CONSUMER_GROUP" "$SPI_STATUS_REPORT_TOPIC")"; then
        :
    else
        status=$?
        return "$status"
    fi
    echo $((payment_lag + status_lag))
}

current_notification_gateway_lag() {
    consumer_group_topic_lag "$NOTIFICATION_GATEWAY_CONSUMER_GROUP" "$PSP_NOTIFICATIONS_TOPIC"
}

check_kafka_quiescence() {
    local lag status

    if lag="$(current_spi_input_lag)"; then
        :
    else
        status=$?
        return "$status"
    fi
    if ((lag > 0)); then
        echo "Refusing to start load test: SPI input consumer groups have ${lag} messages of lag." >&2
        echo "Checked ${SPI_PAYMENT_REQUEST_CONSUMER_GROUP}/${SPI_PAYMENT_REQUEST_TOPIC} and ${SPI_STATUS_REPORT_CONSUMER_GROUP}/${SPI_STATUS_REPORT_TOPIC}." >&2
        echo "Wait for the Kafka backlog to drain before starting a new measured run." >&2
        return 1
    fi

    if lag="$(current_notification_gateway_lag)"; then
        :
    else
        status=$?
        return "$status"
    fi
    if ((lag > 0)); then
        echo "Refusing to start load test: notification-gateway has ${lag} messages of lag on ${PSP_NOTIFICATIONS_TOPIC}." >&2
        echo "Old PSP notifications would be delivered during the new run and contaminate SLA results." >&2
        echo "Wait for the Kafka backlog to drain before starting a new measured run." >&2
        return 1
    fi
}

provision_role() {
    local pair_number_start="$1"
    local pair_count="$2"
    local role_prefix="$3"
    local balance="$4"
    local reset_if_exists="$5"
    local pair_number suffix status
    local -a funding_args=(--balance "$balance")

    if [[ "$reset_if_exists" == true ]]; then
        funding_args+=(--reset-if-exists)
    else
        funding_args+=(--preserve-if-exists)
    fi

    for ((pair_number = pair_number_start; pair_number < pair_number_start + pair_count; pair_number++)); do
        suffix="$(printf '%06d' "$pair_number")"
        funding_args+=(--ispb "${role_prefix}${suffix}")
    done

    "$PROVISION_FUNDS_SCRIPT" "${funding_args[@]}" || {
        status=$?
        return "$status"
    }
}

provision_profile_funding() {
    local record pair_number_start hot_pair_count cold_pair_count payer_balance receiver_balance reset_if_exists
    local pair_count

    for record in "${PROVISIONING_RECORDS[@]}"; do
        IFS=$'\t' read -r pair_number_start hot_pair_count cold_pair_count payer_balance receiver_balance reset_if_exists <<< "$record"
        pair_count=$((hot_pair_count + cold_pair_count))
        provision_role "$pair_number_start" "$pair_count" 10 "$payer_balance" "$reset_if_exists"
        provision_role "$pair_number_start" "$pair_count" 20 "$receiver_balance" "$reset_if_exists"
    done
}

main() {
    parse_args "$@"
    resolve_execution_plan
    load_provisioning_records

    echo "Checking initial Kafka lag"
    check_kafka_quiescence
    echo "Initial Kafka lag is zero"

    echo "Provisioning funds from execution-plan.json"
    provision_profile_funding
    echo "Environment preparation completed"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
