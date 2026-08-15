#!/bin/bash

set -euo pipefail

readonly CONTAINER_STATS_HEADER="sampled_at_ns,container,cpu_percent,memory_usage,network_io,block_io"
readonly CONTAINERS=(postgres kafka kafka-producer spi notification-gateway)

usage() {
    echo "Usage: $(basename "$0") sample <output.csv>"
}

sample_containers() {
    local output_file="$1"
    local interval_ms="${CONTAINER_STATS_INTERVAL_MS:-1000}"
    local max_samples="${CONTAINER_STATS_MAX_SAMPLES:-0}"
    local samples=0
    local stopping=false
    local bounded_stop=false
    local sampled_at_ns=0 last_sampled_at_ns=0 interval_ns
    local line row container cpu_percent memory_usage network_io block_io
    local stats_fd docker_pid docker_status=0 failed=0
    declare -A observed=()
    declare -a cycle_rows=()

    if [[ ! "$max_samples" =~ ^[0-9]+$ ]]; then
        echo "CONTAINER_STATS_MAX_SAMPLES must be a non-negative integer." >&2
        return 2
    fi
    if [[ ! "$interval_ms" =~ ^[0-9]+$ ]]; then
        echo "CONTAINER_STATS_INTERVAL_MS must be a non-negative integer." >&2
        return 2
    fi
    interval_ns=$((interval_ms * 1000000))

    mkdir -p "$(dirname "$output_file")"
    printf '%s\n' "$CONTAINER_STATS_HEADER" > "$output_file"
    exec {stats_fd}< <(docker stats \
        --format '{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}\t{{.BlockIO}}' \
        "${CONTAINERS[@]}")
    docker_pid=$!
    trap 'stopping=true; kill -TERM "$docker_pid" 2>/dev/null || true' INT TERM

    while IFS= read -r -u "$stats_fd" line; do
        line="${line//$'\033[H'/}"
        line="${line//$'\033[J'/}"
        line="${line//$'\033[K'/}"
        if [[ -z "$line" ]]; then
            continue
        fi
        IFS=$'\t' read -r container cpu_percent memory_usage network_io block_io <<< "$line"
        case "$container" in
            postgres|kafka|kafka-producer|spi|notification-gateway) ;;
            *)
                echo "Unexpected container in Docker stats: $container" >&2
                failed=1
                break
                ;;
        esac
        if [[ -n "${observed[$container]:-}" ]]; then
            echo "Docker stats returned duplicate container in one sample: $container" >&2
            failed=1
            break
        fi
        observed[$container]=1
        cycle_rows+=("$container,$cpu_percent,$memory_usage,$network_io,$block_io")

        if ((${#cycle_rows[@]} == ${#CONTAINERS[@]})); then
            for container in "${CONTAINERS[@]}"; do
                if [[ -z "${observed[$container]:-}" ]]; then
                    echo "Docker stats omitted container: $container" >&2
                    failed=1
                    break 2
                fi
            done

            sampled_at_ns="$(date +%s%N)"
            if ((last_sampled_at_ns == 0 || sampled_at_ns - last_sampled_at_ns >= interval_ns)); then
                for row in "${cycle_rows[@]}"; do
                    printf '%s,%s\n' "$sampled_at_ns" "$row" >> "$output_file"
                done
                last_sampled_at_ns="$sampled_at_ns"
                samples=$((samples + 1))
            fi
            for container in "${CONTAINERS[@]}"; do
                unset 'observed[$container]'
            done
            cycle_rows=()

            if ((max_samples > 0 && samples >= max_samples)); then
                bounded_stop=true
                break
            fi
        fi
        if [[ "$stopping" == true ]]; then
            break
        fi
    done

    exec {stats_fd}<&-
    if [[ "$bounded_stop" == true || "$stopping" == true || "$failed" -ne 0 ]]; then
        kill -TERM "$docker_pid" 2>/dev/null || true
    fi
    if wait "$docker_pid"; then
        docker_status=0
    else
        docker_status=$?
    fi
    if [[ "$bounded_stop" == false && "$stopping" == false && "$failed" -eq 0 ]]; then
        echo "Docker stats stream stopped unexpectedly with status ${docker_status}." >&2
        failed=1
    fi
    if ((${#cycle_rows[@]} != 0)); then
        echo "Docker stats stream ended with a partial container sample." >&2
        failed=1
    fi
    trap - INT TERM
    return "$failed"
}

main() {
    if [[ $# -lt 1 ]]; then
        usage >&2
        return 2
    fi

    case "$1" in
        sample)
            if [[ $# -ne 2 ]]; then
                usage >&2
                return 2
            fi
            sample_containers "$2"
            ;;
        -h|--help)
            usage
            ;;
        *)
            usage >&2
            echo "Unknown action: $1" >&2
            return 2
            ;;
    esac
}

main "$@"
