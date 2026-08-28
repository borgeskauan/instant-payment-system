#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

mkdir -p "$tmp_dir/scripts" "$tmp_dir/result/logs" "$tmp_dir/result/diagnostics"

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

cat > "$tmp_dir/scripts/postgres-server-log.sh" <<'SH'
#!/bin/bash
set -euo pipefail

printf '%s\n' "$*" >> "$FAKE_POSTGRES_SERVER_LOG_INVOCATIONS"
if [[ "${FAKE_POSTGRES_SERVER_LOG_FAIL:-}" == true ]]; then
    exit 33
fi
printf '%s\n' \
    '2026-08-16T20:00:01.000000000Z LOG: process 154 still waiting' \
    '2026-08-16T20:00:01.000000000Z CONTEXT: while updating tuple in relation "funds_bucket_entity"' \
    > "$4"
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
    "$tmp_dir/scripts/postgres-statements.sh" \
    "$tmp_dir/scripts/postgres-server-log.sh" \
    "$tmp_dir/scripts/postgres-runtime.sh" \
    "$tmp_dir/scripts/container-stats.sh"

export SCRIPTS_DIR="$tmp_dir/scripts"
export FAKE_POSTGRES_SERVER_LOG_INVOCATIONS="$tmp_dir/postgres-server-log-invocations.log"
source "$ROOT_DIR/scripts/run-diagnostics.sh"

ENABLE_JFR=false
ENABLE_POSTGRES_STATEMENTS=true
start_optional_diagnostics "$tmp_dir/result"
collect_optional_diagnostics "$tmp_dir/result"

for artifact in \
    "$tmp_dir/result/diagnostics/postgres-statements.csv" \
    "$tmp_dir/result/diagnostics/postgres-activity.csv" \
    "$tmp_dir/result/diagnostics/postgres-io.csv" \
    "$tmp_dir/result/diagnostics/container-stats.csv" \
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

test -f "$tmp_dir/result/logs/postgres-server.log"
grep -Fq 'relation "funds_bucket_entity"' "$tmp_dir/result/logs/postgres-server.log"
grep -Eq '^capture .+ .+ .+/logs/postgres-server\.log$' \
    "$FAKE_POSTGRES_SERVER_LOG_INVOCATIONS"

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

mkdir -p "$tmp_dir/server-log-failure/logs" "$tmp_dir/server-log-failure/diagnostics"
export FAKE_POSTGRES_SERVER_LOG_FAIL=true
start_optional_diagnostics "$tmp_dir/server-log-failure"
if collect_optional_diagnostics "$tmp_dir/server-log-failure"; then
    echo "PostgreSQL server-log capture failure was hidden" >&2
    exit 1
fi
unset FAKE_POSTGRES_SERVER_LOG_FAIL

if [[ -n "$POSTGRES_ACTIVITY_PID" || -n "$CONTAINER_STATS_PID" ]]; then
    echo "diagnostic collection retained a sampler PID" >&2
    exit 1
fi

mkdir -p "$tmp_dir/disabled/logs" "$tmp_dir/disabled/diagnostics"
ENABLE_JFR=false
ENABLE_POSTGRES_STATEMENTS=false
start_optional_diagnostics "$tmp_dir/disabled"
collect_optional_diagnostics "$tmp_dir/disabled"
test ! -e "$tmp_dir/disabled/logs/postgres-server.log"

mkdir -p "$tmp_dir/command-failure"
set +e
    "$ROOT_DIR/scripts/run-diagnostics.sh" run \
    --run-dir "$tmp_dir/command-failure" \
    --no-jfr --no-postgres-statements \
    -- bash -c 'exit 17'
command_status=$?
set -e
if [[ "$command_status" -ne 17 ]]; then
    echo "diagnostic wrapper returned $command_status, want child status 17" >&2
    exit 1
fi
test -f "$tmp_dir/command-failure/logs/loadtool.log"

export FAKE_POSTGRES_STATEMENTS_FAIL_ACTION=snapshot
for child_status in 0 17; do
    result="$tmp_dir/precedence-$child_status"
    mkdir -p "$result"
    set +e
    "$ROOT_DIR/scripts/run-diagnostics.sh" run \
        --run-dir "$result" --no-jfr \
        -- bash -c "exit $child_status"
    wrapper_status=$?
    set -e
    expected=2
    [[ "$child_status" -eq 0 ]] || expected="$child_status"
    if [[ "$wrapper_status" -ne "$expected" ]]; then
        echo "diagnostic wrapper returned $wrapper_status, want $expected" >&2
        exit 1
    fi
done
unset FAKE_POSTGRES_STATEMENTS_FAIL_ACTION
