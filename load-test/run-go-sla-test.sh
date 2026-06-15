#!/bin/bash

set -euo pipefail

readonly SUMMARY_DIR="summary"
readonly SYSTEM_STATS_INTERVAL_SECONDS=5
readonly PROCESS_STATS_INTERVAL_SECONDS=5
readonly KAFKA_LAG_INTERVAL_SECONDS=2
readonly GO_LOADTOOL_CONFIG="go-loadtool/loadtool-config.json"

RUN_TAG=""
PROVISION_FUNDS=true
SYSTEM_STATS_PID=""
PROCESS_STATS_PID=""
KAFKA_LAG_PID=""

usage() {
    echo "Usage: $(basename "$0") [--provision-funds|--no-provision-funds] <run-tag>"
    echo "Edit ${GO_LOADTOOL_CONFIG} to change rate, duration, drain, PSP distribution, or SLA."
}

log_phase() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

stop_samplers() {
    for pid in "$SYSTEM_STATS_PID" "$PROCESS_STATS_PID" "$KAFKA_LAG_PID"; do
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
            wait "$pid" 2>/dev/null || true
        fi
    done
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --provision-funds)
                PROVISION_FUNDS=true
                shift
                ;;
            --no-provision-funds)
                PROVISION_FUNDS=false
                shift
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            --*)
                usage
                echo "Unknown option: $1" >&2
                exit 2
                ;;
            *)
                if [[ -n "$RUN_TAG" ]]; then
                    usage
                    echo "Only one run tag is allowed." >&2
                    exit 2
                fi
                RUN_TAG="$1"
                shift
                ;;
        esac
    done

    if [[ -z "$RUN_TAG" ]]; then
        usage
        echo "Run tag is required." >&2
        exit 2
    fi
}

duration_seconds() {
    local key="$1"
    python3 -c '
import json
import re
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    value = str(json.load(handle)[sys.argv[2]])

match = re.fullmatch(r"([0-9]+)s?", value)
if not match:
    raise SystemExit(f"{sys.argv[2]} must be seconds, for example 60s.")

print(match.group(1))
' "$GO_LOADTOOL_CONFIG" "$key"
}

main() {
    parse_args "$@"

    local timestamp target_dir tool_out active_seconds drain_seconds sampler_duration
    timestamp="$(date +%Y%m%d_%H%M%S)"
    target_dir="${SUMMARY_DIR}/${RUN_TAG}/${timestamp}"
    tool_out="${target_dir}/go-loadtool"
    active_seconds="$(duration_seconds "duration")"
    drain_seconds="$(duration_seconds "drain")"
    sampler_duration=$((active_seconds + drain_seconds + 20))

    mkdir -p "$tool_out"
    cp "$GO_LOADTOOL_CONFIG" "${target_dir}/loadtool-config.json"
    trap stop_samplers EXIT

    log_phase "starting go SLA test: tag=${RUN_TAG} output=${target_dir}"
    log_phase "using config: ${GO_LOADTOOL_CONFIG}"

    if [[ "$PROVISION_FUNDS" == true ]]; then
        log_phase "provisioning funds"
        ./provision-funds.sh > "${target_dir}/provision-funds.log" 2>&1
        log_phase "funds provisioned"
    else
        log_phase "skipping funds provisioning"
    fi

    log_phase "starting samplers: duration=${sampler_duration}s"
    ./sample-system-stats.sh "$SYSTEM_STATS_INTERVAL_SECONDS" "$sampler_duration" "${target_dir}/system-stats.csv" > "${target_dir}/system-stats.log" 2>&1 &
    SYSTEM_STATS_PID="$!"

    ./sample-process-stats.sh "$PROCESS_STATS_INTERVAL_SECONDS" "$sampler_duration" "${target_dir}/process-stats.csv" 20 > "${target_dir}/process-stats.log" 2>&1 &
    PROCESS_STATS_PID="$!"

    ./sample-kafka-lag.sh "$KAFKA_LAG_INTERVAL_SECONDS" "$sampler_duration" "${target_dir}/kafka-lag.csv" > "${target_dir}/kafka-lag.log" 2>&1 &
    KAFKA_LAG_PID="$!"

    log_phase "starting simulator"
    (
        cd go-loadtool
        GOPATH="${GOPATH:-/tmp/go}" GOCACHE="${GOCACHE:-/tmp/go-build-cache}" go run ./cmd/go-loadtool simulate \
            --out "../${tool_out}"
    ) | tee "${target_dir}/go-loadtool-output.txt"

    log_phase "simulator finished; generating SLA report"
    (
        cd go-loadtool
        GOPATH="${GOPATH:-/tmp/go}" GOCACHE="${GOCACHE:-/tmp/go-build-cache}" go run ./cmd/go-loadtool report \
            --starts "../${tool_out}/starts.csv" \
            --events "../${tool_out}/events.csv"
    ) | tee "${target_dir}/sla-report.json"

    log_phase "SLA report generated"
    log_phase "results written to ${target_dir}"
}

main "$@"
