#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
compose_config="$(docker compose -f "$ROOT_DIR/infra/docker-compose.yml" config)"

for setting in log_lock_waits=on deadlock_timeout=1s; do
    if ! grep -Fq -- "- ${setting}" <<< "$compose_config"; then
        echo "rendered PostgreSQL command omitted ${setting}" >&2
        exit 1
    fi
done
