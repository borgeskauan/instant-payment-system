#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
compose_config="$(docker compose -f "$ROOT_DIR/infra/docker-compose.yml" config)"

if ! grep -Fq 'SPI_KAFKA_LISTENER_CONCURRENCY: "8"' <<< "$compose_config"; then
    echo "rendered SPI service does not use one consumer per Kafka partition" >&2
    exit 1
fi
