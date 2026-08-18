#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

mkdir -p "$tmp_dir/bin"
export DOCKER_INVOCATIONS_LOG="$tmp_dir/docker-invocations.log"

cat > "$tmp_dir/bin/docker" <<'SH'
#!/bin/bash
set -euo pipefail
printf '%s\n' "$*" >> "$DOCKER_INVOCATIONS_LOG"
if [[ "${FAKE_POSTGRES_LOG_FAIL:-}" == true ]]; then
    echo "docker log failure" >&2
    exit 37
fi
printf '%s\n' \
    '2026-08-16T20:00:01.000000000Z LOG: process 154 still waiting' \
    '2026-08-16T20:00:01.000000000Z CONTEXT: while updating tuple (1,2) in relation "funds_bucket_entity"'
SH
chmod +x "$tmp_dir/bin/docker"
export PATH="$tmp_dir/bin:$PATH"

since='2026-08-16T19:59:59.000000000Z'
until='2026-08-16T20:00:02.000000000Z'
output="$tmp_dir/postgres-server.log"

bash "$ROOT_DIR/scripts/postgres-server-log.sh" capture "$since" "$until" "$output"

grep -Fq 'relation "funds_bucket_entity"' "$output"
grep -Fqx "logs --timestamps --since $since --until $until postgres" "$DOCKER_INVOCATIONS_LOG"

export FAKE_POSTGRES_LOG_FAIL=true
if bash "$ROOT_DIR/scripts/postgres-server-log.sh" \
        capture "$since" "$until" "$tmp_dir/failed.log" >/dev/null 2>&1; then
    echo "PostgreSQL server-log capture hid docker failure" >&2
    exit 1
fi
if [[ -e "$tmp_dir/failed.log" ]]; then
    echo "failed PostgreSQL capture published a partial artifact" >&2
    exit 1
fi
unset FAKE_POSTGRES_LOG_FAIL

if bash "$ROOT_DIR/scripts/postgres-server-log.sh" capture "$since" "$until" >/dev/null 2>&1; then
    echo "PostgreSQL capture accepted a missing output path" >&2
    exit 1
fi
