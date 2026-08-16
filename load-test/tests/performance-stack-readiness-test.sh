#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
READINESS_SCRIPT="${ROOT_DIR}/scripts/wait-for-performance-stack.sh"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

run_polling_tests() (
    export READINESS_TIMEOUT_SECONDS=2
    export READINESS_POLL_SECONDS=1
    source "$READINESS_SCRIPT"

    FLOW_LOG="$tmp_dir/flow.log"
    READINESS_CASE=success
    FAKE_NOW=0
    POLL_COUNT=0

    infrastructure_healthy() {
        POLL_COUNT=$((POLL_COUNT + 1))
        if [[ "$READINESS_CASE" == transient ]]; then
            ((POLL_COUNT >= 2))
            return
        fi
        [[ "$READINESS_CASE" != postgres-unhealthy ]]
    }
    applications_running() { [[ "$READINESS_CASE" != app-stopped ]]; }
    application_ports_accepting() { [[ "$READINESS_CASE" != port-closed ]]; }
    consumer_groups_stable() { [[ "$READINESS_CASE" != group-rebalancing ]]; }
    readiness_now() { printf '%s\n' "$FAKE_NOW"; }
    readiness_sleep() {
        printf 'sleep %s\n' "$1" >> "$FLOW_LOG"
        FAKE_NOW=$((FAKE_NOW + $1))
    }

    run_loop_case() {
        local test_case="$1"
        local expected_status="$2"
        local expected_sleeps="$3"
        local status

        READINESS_CASE="$test_case"
        FAKE_NOW=0
        POLL_COUNT=0
        : > "$FLOW_LOG"
        set +e
        wait_for_performance_stack >"$tmp_dir/${test_case}.out" 2>"$tmp_dir/${test_case}.err"
        status=$?
        set -e
        if [[ "$status" -ne "$expected_status" ]]; then
            echo "readiness case ${test_case} returned ${status}, want ${expected_status}" >&2
            exit 1
        fi
        if [[ "$(wc -l < "$FLOW_LOG")" -ne "$expected_sleeps" ]]; then
            echo "readiness case ${test_case} slept an unexpected number of times" >&2
            exit 1
        fi
    }

    run_loop_case success 0 0
    run_loop_case transient 0 1
    run_loop_case postgres-unhealthy 1 2
    run_loop_case app-stopped 1 2
    run_loop_case port-closed 1 2
    run_loop_case group-rebalancing 1 2
)

run_polling_tests
source "$READINESS_SCRIPT"

mkdir -p "$tmp_dir/fake-bin"
export DOCKER_CALLS="$tmp_dir/docker-calls.log"
export PORT_CALLS="$tmp_dir/port-calls.log"

cat > "$tmp_dir/fake-bin/docker" <<'SH'
#!/bin/bash
set -euo pipefail

printf '%s\n' "$*" >> "$DOCKER_CALLS"
if [[ "$1" == inspect ]]; then
    container="${!#}"
    if [[ "$*" == *"Health.Status"* ]]; then
        if [[ "${UNHEALTHY_CONTAINER:-}" == "$container" ]]; then
            printf 'unhealthy\n'
        else
            printf 'healthy\n'
        fi
    elif [[ "$*" == *"State.Running"* ]]; then
        if [[ "${STOPPED_CONTAINER:-}" == "$container" ]]; then
            printf 'false\n'
        else
            printf 'true\n'
        fi
    else
        exit 40
    fi
    exit 0
fi

if [[ "$*" == *"kafka-consumer-groups"* ]]; then
    if [[ "$*" == *"--list"* && "$*" == *"--state"* ]]; then
        if [[ "${KAFKA_STATE_UNREADABLE:-false}" == true ]]; then
            exit 41
        fi
        printf 'GROUP STATE\n'
        for group in \
            notification-gateway-group \
            spi-status-report-consumer-group \
            spi-payment-request-consumer-group; do
            if [[ "${MISSING_GROUP:-}" == "$group" ]]; then
                continue
            fi
            printf '%s %s\n' "$group" "${GROUP_STATE:-Stable}"
        done
        exit 0
    fi

    group=""
    while [[ $# -gt 0 ]]; do
        if [[ "$1" == --group ]]; then
            group="$2"
            break
        fi
        shift
    done
    [[ -n "$group" ]]
    if [[ "${UNREADABLE_GROUP:-}" == "$group" ]]; then
        exit 41
    fi
    printf 'GROUP COORDINATOR (ID) ASSIGNMENT-STRATEGY STATE #MEMBERS\n'
    printf '%s kafka:9092 (1) range %s %s\n' \
        "$group" "${GROUP_STATE:-Stable}" "${GROUP_MEMBERS:-2}"
    exit 0
fi

exit 42
SH
chmod +x "$tmp_dir/fake-bin/docker"

cat > "$tmp_dir/fake-bin/timeout" <<'SH'
#!/bin/bash
set -euo pipefail

shift
if [[ "$1" == bash ]]; then
    port="${*: -1}"
    printf '%s\n' "$port" >> "$PORT_CALLS"
    [[ "${CLOSED_PORT:-}" != "$port" ]]
    exit
fi
"$@"
SH
chmod +x "$tmp_dir/fake-bin/timeout"

export PATH="$tmp_dir/fake-bin:$PATH"

: > "$DOCKER_CALLS"
infrastructure_healthy
if UNHEALTHY_CONTAINER=postgres infrastructure_healthy; then
    echo "readiness accepted unhealthy Postgres" >&2
    exit 1
fi
if UNHEALTHY_CONTAINER=kafka infrastructure_healthy; then
    echo "readiness accepted unhealthy Kafka" >&2
    exit 1
fi

applications_running
for container in kafka-producer spi notification-gateway; do
    if STOPPED_CONTAINER="$container" applications_running; then
        echo "readiness accepted stopped application ${container}" >&2
        exit 1
    fi
done

: > "$PORT_CALLS"
application_ports_accepting
cat > "$tmp_dir/expected-ports.log" <<'EOF'
8001
8002
9090
EOF
diff -u "$tmp_dir/expected-ports.log" "$PORT_CALLS"
if CLOSED_PORT=8002 application_ports_accepting; then
    echo "readiness accepted a closed application port" >&2
    exit 1
fi

: > "$DOCKER_CALLS"
consumer_groups_stable
if [[ "$(grep -c kafka-consumer-groups "$DOCKER_CALLS")" -ne 1 ]] \
    || ! grep -q -- '--list --state' "$DOCKER_CALLS"; then
    echo "readiness did not inspect all consumer-group states in one call" >&2
    exit 1
fi
if GROUP_STATE=PreparingRebalance consumer_groups_stable; then
    echo "readiness accepted a rebalancing consumer group" >&2
    exit 1
fi
if MISSING_GROUP=spi-status-report-consumer-group consumer_groups_stable; then
    echo "readiness accepted a missing consumer group" >&2
    exit 1
fi
if KAFKA_STATE_UNREADABLE=true consumer_groups_stable; then
    echo "readiness accepted unreadable consumer-group state" >&2
    exit 1
fi
