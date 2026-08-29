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

case "$*" in
    *"FROM pg_stat_activity"*)
        printf '%s\n' '1000000000,42,spi,active,123,10.000,20.000,Lock,transactionid,7'
        ;;
    *"FROM pg_stat_io"*)
        printf '%s\n' \
            'pg_stat_io,client backend|relation|normal,reads,4' \
            'pg_stat_database,postgres,deadlocks,0' \
            'pg_stat_database,postgres,xact_commit,100' \
            'pg_stat_database,postgres,xact_rollback,2'
        ;;
esac
SH
chmod +x "$tmp_dir/bin/docker"

export PATH="$tmp_dir/bin:$PATH"
export POSTGRES_ACTIVITY_INTERVAL_MS=0
export POSTGRES_ACTIVITY_MAX_SAMPLES=2

activity_started_at_ns="$(date +%s%N)"
bash "$ROOT_DIR/scripts/diagnostics/postgres-runtime.sh" \
    sample-activity "$tmp_dir/postgres-activity.csv"
activity_elapsed_ns=$(($(date +%s%N) - activity_started_at_ns))
if ((activity_elapsed_ns >= 200000000)); then
    echo "PostgreSQL activity sampler ignored its millisecond cadence" >&2
    exit 1
fi
bash "$ROOT_DIR/scripts/diagnostics/postgres-runtime.sh" \
    snapshot-io before "$tmp_dir/postgres-io.csv"
bash "$ROOT_DIR/scripts/diagnostics/postgres-runtime.sh" \
    snapshot-io after "$tmp_dir/postgres-io.csv"
bash "$ROOT_DIR/scripts/diagnostics/postgres-statements.sh" \
    snapshot "$tmp_dir/postgres-statements.csv"

expected_activity_header='sampled_at_ns,pid,application_name,state,query_id,query_age_ms,transaction_age_ms,wait_event_type,wait_event,blocking_pids'
if [[ "$(head -n 1 "$tmp_dir/postgres-activity.csv")" != "$expected_activity_header" ]]; then
    echo "unexpected PostgreSQL activity header" >&2
    exit 1
fi
if [[ "$(wc -l < "$tmp_dir/postgres-activity.csv")" -ne 3 ]]; then
    echo "bounded PostgreSQL activity sampling produced the wrong row count" >&2
    exit 1
fi
if ! grep -q ',Lock,transactionid,7$' "$tmp_dir/postgres-activity.csv"; then
    echo "PostgreSQL activity sampling omitted wait/blocker evidence" >&2
    exit 1
fi

expected_io_header='phase,sampled_at_ns,source,scope,metric,value'
if [[ "$(head -n 1 "$tmp_dir/postgres-io.csv")" != "$expected_io_header" ]]; then
    echo "unexpected PostgreSQL I/O header" >&2
    exit 1
fi
if [[ "$(grep -c '^phase,' "$tmp_dir/postgres-io.csv")" -ne 1 ]]; then
    echo "PostgreSQL I/O snapshots duplicated their header" >&2
    exit 1
fi
if [[ "$(grep -c '^before,' "$tmp_dir/postgres-io.csv")" -ne 4 || "$(grep -c '^after,' "$tmp_dir/postgres-io.csv")" -ne 4 ]]; then
    echo "PostgreSQL I/O snapshots did not preserve both phases" >&2
    exit 1
fi

for metric in xact_commit xact_rollback; do
    if ! grep -q "$metric" "$DOCKER_INVOCATIONS_LOG"; then
        echo "PostgreSQL I/O snapshot query omitted $metric" >&2
        exit 1
    fi
    if [[ "$(grep -c ",pg_stat_database,postgres,$metric," "$tmp_dir/postgres-io.csv")" -ne 2 ]]; then
        echo "PostgreSQL I/O snapshots did not preserve $metric in both phases" >&2
        exit 1
    fi
done

for column in \
    shared_blk_read_time \
    shared_blk_write_time \
    temp_blk_read_time \
    temp_blk_write_time; do
    if ! grep -q "$column" "$DOCKER_INVOCATIONS_LOG"; then
        echo "PostgreSQL statement snapshot omitted $column" >&2
        exit 1
    fi
done

if ! grep -q 'pg_blocking_pids' "$DOCKER_INVOCATIONS_LOG"; then
    echo "PostgreSQL activity query omitted blocker discovery" >&2
    exit 1
fi
if ! grep -q 'statement_timestamp' "$DOCKER_INVOCATIONS_LOG"; then
    echo "PostgreSQL activity rows do not share one timestamp per sample" >&2
    exit 1
fi

if bash "$ROOT_DIR/scripts/diagnostics/postgres-runtime.sh" snapshot-io middle "$tmp_dir/invalid.csv" >/dev/null 2>&1; then
    echo "PostgreSQL I/O snapshot accepted an invalid phase" >&2
    exit 1
fi
