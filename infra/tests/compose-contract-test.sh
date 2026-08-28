#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/infra/docker-compose.yml"
compose_config="$(docker compose -f "$COMPOSE_FILE" config)"
compose_source="$(<"$COMPOSE_FILE")"

for setting in log_lock_waits=on deadlock_timeout=1s; do
    if ! grep -Fq -- "- ${setting}" <<< "$compose_config"; then
        echo "rendered PostgreSQL command omitted ${setting}" >&2
        exit 1
    fi
done

for contract in \
    "ensure_topic spi-payment-requests 8" \
    "ensure_topic spi-payment-requests.dlq 8" \
    "ensure_topic spi-payment-status-reports 8" \
    "ensure_topic spi-payment-status-reports.dlq 8" \
    "ensure_notification_log psp-notifications-v1 8" \
    "retention.ms=604800000" \
    "retention.bytes=-1" \
    "postgres-data:/var/lib/postgresql/data" \
    "kafka-data:/var/lib/kafka/data"; do
    if ! grep -Fq -- "$contract" <<< "$compose_source"; then
        echo "Compose source omitted contract: $contract" >&2
        exit 1
    fi
done
