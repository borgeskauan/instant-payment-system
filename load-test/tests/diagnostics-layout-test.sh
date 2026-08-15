#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

mkdir -p "$tmp_dir/scripts" "$tmp_dir/result/logs" "$tmp_dir/result/diagnostics"

cat > "$tmp_dir/scripts/spi-trace.sh" <<'SH'
#!/bin/bash
set -euo pipefail
echo "spi trace $1"
if [[ "$1" == copy ]]; then
    printf 'timestamp,event\n' > "$2/spi-trace.csv"
fi
SH

cat > "$tmp_dir/scripts/postgres-statements.sh" <<'SH'
#!/bin/bash
set -euo pipefail
echo "postgres statements $1"
if [[ "${FAKE_POSTGRES_STATEMENTS_FAIL_ACTION:-}" == "$1" ]]; then
    exit 31
fi
if [[ "$1" == snapshot ]]; then
    printf 'query,calls\n' > "$2"
fi
SH

cat > "$tmp_dir/scripts/postgres-runtime.sh" <<'SH'
#!/bin/bash
set -euo pipefail

case "$1" in
    sample-activity)
        printf '%s\n' 'sampled_at_ns,pid,application_name,state,query_id,query_age_ms,transaction_age_ms,wait_event_type,wait_event,blocking_pids' > "$2"
        if [[ "${FAKE_POSTGRES_ACTIVITY_FAIL_AFTER_READY:-}" == true ]]; then
            sleep 0.05
            exit 32
        fi
        trap 'exit 0' INT TERM
        while :; do sleep 0.1; done
        ;;
    snapshot-io)
        phase="$2"
        output="$3"
        if [[ ! -s "$output" ]]; then
            printf '%s\n' 'phase,sampled_at_ns,source,scope,metric,value' > "$output"
        fi
        printf '%s\n' "$phase,1,pg_stat_database,postgres,deadlocks,0" >> "$output"
        ;;
    *)
        exit 2
        ;;
esac
SH

cat > "$tmp_dir/scripts/container-stats.sh" <<'SH'
#!/bin/bash
set -euo pipefail

if [[ "$1" != sample ]]; then
    exit 2
fi
printf '%s\n' 'sampled_at_ns,container,cpu_percent,memory_usage,network_io,block_io' > "$2"
trap 'exit 0' INT TERM
while :; do sleep 0.1; done
SH

chmod +x \
    "$tmp_dir/scripts/spi-trace.sh" \
    "$tmp_dir/scripts/postgres-statements.sh" \
    "$tmp_dir/scripts/postgres-runtime.sh" \
    "$tmp_dir/scripts/container-stats.sh"

export SCRIPTS_DIR="$tmp_dir/scripts"
source "$ROOT_DIR/run-load-test.sh"

start_spi_trace "$tmp_dir/result/logs/spi-trace.log"
stop_spi_trace "$tmp_dir/result"
copy_spi_trace "$tmp_dir/result"

ENABLE_POSTGRES_STATEMENTS=true
start_optional_diagnostics "$tmp_dir/result"
collect_optional_diagnostics "$tmp_dir/result"

for artifact in \
    "$tmp_dir/result/diagnostics/spi-trace.csv" \
    "$tmp_dir/result/diagnostics/postgres-statements.csv" \
    "$tmp_dir/result/diagnostics/postgres-activity.csv" \
    "$tmp_dir/result/diagnostics/postgres-io.csv" \
    "$tmp_dir/result/diagnostics/container-stats.csv" \
    "$tmp_dir/result/logs/spi-trace.log" \
    "$tmp_dir/result/logs/postgres-statements.log" \
    "$tmp_dir/result/logs/postgres-runtime.log" \
    "$tmp_dir/result/logs/container-stats.log"; do
    if [[ ! -f "$artifact" ]]; then
        echo "missing diagnostic bundle artifact: $artifact" >&2
        exit 1
    fi
done

if [[ "$(grep -c '^before,' "$tmp_dir/result/diagnostics/postgres-io.csv")" -ne 1 || \
      "$(grep -c '^after,' "$tmp_dir/result/diagnostics/postgres-io.csv")" -ne 1 ]]; then
    echo "PostgreSQL diagnostic lifecycle omitted an I/O snapshot phase" >&2
    exit 1
fi

mkdir -p "$tmp_dir/failing-result/logs" "$tmp_dir/failing-result/diagnostics"
export FAKE_POSTGRES_STATEMENTS_FAIL_ACTION=snapshot
start_optional_diagnostics "$tmp_dir/failing-result"
if collect_optional_diagnostics "$tmp_dir/failing-result"; then
    echo "PostgreSQL statement snapshot failure was hidden" >&2
    exit 1
fi
unset FAKE_POSTGRES_STATEMENTS_FAIL_ACTION

mkdir -p "$tmp_dir/sampler-failure/logs" "$tmp_dir/sampler-failure/diagnostics"
export FAKE_POSTGRES_ACTIVITY_FAIL_AFTER_READY=true
start_optional_diagnostics "$tmp_dir/sampler-failure"
sleep 0.1
if collect_optional_diagnostics "$tmp_dir/sampler-failure"; then
    echo "PostgreSQL activity sampler failure was hidden" >&2
    exit 1
fi
unset FAKE_POSTGRES_ACTIVITY_FAIL_AFTER_READY

if [[ -n "$POSTGRES_ACTIVITY_PID" || -n "$CONTAINER_STATS_PID" ]]; then
    echo "diagnostic collection retained a sampler PID" >&2
    exit 1
fi
