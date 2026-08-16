#!/bin/bash

set -euo pipefail

readonly KAFKA_CONTAINER="${KAFKA_CONTAINER:-kafka}"
readonly KAFKA_BOOTSTRAP_SERVER="${KAFKA_BOOTSTRAP_SERVER:-kafka:9092}"
readonly SPI_PAYMENT_REQUEST_CONSUMER_GROUP="${SPI_PAYMENT_REQUEST_CONSUMER_GROUP:-spi-payment-request-consumer-group}"
readonly SPI_STATUS_REPORT_CONSUMER_GROUP="${SPI_STATUS_REPORT_CONSUMER_GROUP:-spi-status-report-consumer-group}"
readonly NOTIFICATION_GATEWAY_CONSUMER_GROUP="${NOTIFICATION_GATEWAY_CONSUMER_GROUP:-notification-gateway-group}"
readonly SPI_PAYMENT_REQUEST_TOPIC="${SPI_PAYMENT_REQUEST_TOPIC:-spi-payment-requests}"
readonly SPI_STATUS_REPORT_TOPIC="${SPI_STATUS_REPORT_TOPIC:-spi-payment-status-reports}"
readonly PSP_NOTIFICATIONS_TOPIC="${PSP_NOTIFICATIONS_TOPIC:-psp-notifications}"
readonly KAFKA_CLI_TIMEOUT_SECONDS="${KAFKA_CLI_TIMEOUT_SECONDS:-15}"

consumer_group_lags() {
    local group_output lags
    local payment_lag status_lag gateway_lag

    if ! group_output="$(timeout "$KAFKA_CLI_TIMEOUT_SECONDS" docker exec "$KAFKA_CONTAINER" kafka-consumer-groups \
            --bootstrap-server "$KAFKA_BOOTSTRAP_SERVER" \
            --all-groups \
            --describe 2>&1)"; then
        echo "Failed to read Kafka consumer group lag within ${KAFKA_CLI_TIMEOUT_SECONDS}s." >&2
        echo "$group_output" >&2
        return 1
    fi

    lags="$(echo "$group_output" |
        awk \
            -v payment_group="$SPI_PAYMENT_REQUEST_CONSUMER_GROUP" \
            -v payment_topic="$SPI_PAYMENT_REQUEST_TOPIC" \
            -v status_group="$SPI_STATUS_REPORT_CONSUMER_GROUP" \
            -v status_topic="$SPI_STATUS_REPORT_TOPIC" \
            -v gateway_group="$NOTIFICATION_GATEWAY_CONSUMER_GROUP" \
            -v gateway_topic="$PSP_NOTIFICATIONS_TOPIC" '
            $1 == payment_group && $2 == payment_topic && $6 ~ /^[0-9]+$/ {
                payment_found = 1
                payment_lag += $6
            }
            $1 == status_group && $2 == status_topic && $6 ~ /^[0-9]+$/ {
                status_found = 1
                status_lag += $6
            }
            $1 == gateway_group && $2 == gateway_topic && $6 ~ /^[0-9]+$/ {
                gateway_found = 1
                gateway_lag += $6
            }
            END {
                printf "%s\t%s\t%s\n",
                    payment_found ? payment_lag + 0 : "NO_OFFSETS",
                    status_found ? status_lag + 0 : "NO_OFFSETS",
                    gateway_found ? gateway_lag + 0 : "NO_OFFSETS"
            }
        ')"
    IFS=$'\t' read -r payment_lag status_lag gateway_lag <<< "$lags"

    if [[ "$payment_lag" == NO_OFFSETS ]]; then
        payment_lag="$(topic_end_offset "$SPI_PAYMENT_REQUEST_TOPIC")" || return 1
    fi
    if [[ "$status_lag" == NO_OFFSETS ]]; then
        status_lag="$(topic_end_offset "$SPI_STATUS_REPORT_TOPIC")" || return 1
    fi
    if [[ "$gateway_lag" == NO_OFFSETS ]]; then
        gateway_lag="$(topic_end_offset "$PSP_NOTIFICATIONS_TOPIC")" || return 1
    fi

    printf '%s\t%s\t%s\n' "$payment_lag" "$status_lag" "$gateway_lag"
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

check_kafka_quiescence() {
    local sample lags payment_lag status_lag gateway_lag

    for sample in 1 2 3; do
        if ! lags="$(consumer_group_lags)"; then
            return 1
        fi
        IFS=$'\t' read -r payment_lag status_lag gateway_lag <<< "$lags"

        if ((payment_lag != 0 || status_lag != 0 || gateway_lag != 0)); then
            echo "Kafka is not quiescent at sample ${sample}: payment=${payment_lag} status=${status_lag} gateway=${gateway_lag}." >&2
            return 1
        fi

        if ((sample < 3)); then
            sleep 1
        fi
    done
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    check_kafka_quiescence
fi
