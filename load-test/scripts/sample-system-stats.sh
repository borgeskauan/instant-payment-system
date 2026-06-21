#!/bin/bash

set -euo pipefail

export LC_ALL=C

readonly DEFAULT_INTERVAL_SECONDS=5
readonly DEFAULT_OUTPUT_FILE="system-stats.csv"

usage() {
    echo "Usage: $(basename "$0") [interval-seconds] [duration-seconds] [output-file]"
    echo
    echo "Examples:"
    echo "  $(basename "$0")"
    echo "  $(basename "$0") 5 120 results/system-stats.csv"
    echo
    echo "Columns:"
    echo "  timestamp,source,name,cpu_percent,cpu_limit_percent,mem_used_mb,mem_limit_mb,mem_available_mb,load_1m,block_io,net_io"
}

error_exit() {
    echo "Error: $1" >&2
    exit 1
}

validate_positive_integer() {
    local name="$1"
    local value="$2"

    if [[ ! "$value" =~ ^[1-9][0-9]*$ ]]; then
        error_exit "$name must be a positive integer."
    fi
}

check_requirements() {
    if ! command -v docker &>/dev/null; then
        error_exit "docker not found."
    fi

    if [[ ! -r /proc/stat ]]; then
        error_exit "/proc/stat is not readable."
    fi
}

write_header() {
    local output_file="$1"
    echo "timestamp,source,name,cpu_percent,cpu_limit_percent,mem_used_mb,mem_limit_mb,mem_available_mb,load_1m,block_io,net_io" > "$output_file"
}

read_cpu_totals() {
    awk '
        /^cpu / {
            idle=$5 + $6
            total=0
            for (i = 2; i <= NF; i++) {
                total += $i
            }
            printf "%s %s\n", idle, total
        }
    ' /proc/stat
}

read_mem_mb() {
    awk '
        /^MemTotal:/ { total=$2 }
        /^MemAvailable:/ { available=$2 }
        END {
            used=total-available
            printf "%.0f %.0f %.0f\n", used/1024, total/1024, available/1024
        }
    ' /proc/meminfo
}

read_load_1m() {
    awk '{ print $1 }' /proc/loadavg
}

sample_host() {
    local timestamp="$1"
    local previous_idle="$2"
    local previous_total="$3"
    local output_file="$4"
    local current_idle current_total idle_delta total_delta cpu_percent mem_used_mb mem_limit_mb mem_available_mb load_1m

    read -r current_idle current_total < <(read_cpu_totals)
    read -r mem_used_mb mem_limit_mb mem_available_mb < <(read_mem_mb)
    load_1m=$(read_load_1m)

    idle_delta=$((current_idle - previous_idle))
    total_delta=$((current_total - previous_total))

    if (( total_delta > 0 )); then
        cpu_percent=$(awk -v idle="$idle_delta" -v total="$total_delta" 'BEGIN { printf "%.2f", (1 - idle / total) * 100 }')
    else
        cpu_percent="0.00"
    fi

    printf '%s,host,host,%s,,%s,%s,%s,%s,,\n' \
        "$timestamp" "$cpu_percent" "$mem_used_mb" "$mem_limit_mb" "$mem_available_mb" "$load_1m" >> "$output_file"

    printf "%s %s\n" "$current_idle" "$current_total"
}

normalize_mb() {
    local value="$1"
    local unit="$2"

    awk -v value="$value" -v unit="$unit" '
        BEGIN {
            if (unit == "KiB") printf "%.2f", value / 1024
            else if (unit == "MiB") printf "%.2f", value
            else if (unit == "GiB") printf "%.2f", value * 1024
            else if (unit == "TiB") printf "%.2f", value * 1024 * 1024
            else printf ""
        }
    '
}

parse_mem_usage_mb() {
    local mem_usage="$1"
    local used="" used_unit="" limit="" limit_unit=""

    if [[ "$mem_usage" =~ ^([0-9.]+)([KMGT]iB)[[:space:]]/[[:space:]]([0-9.]+)([KMGT]iB)$ ]]; then
        used=$(normalize_mb "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}")
        limit=$(normalize_mb "${BASH_REMATCH[3]}" "${BASH_REMATCH[4]}")
        printf "%s %s\n" "$used" "$limit"
    else
        printf " \n"
    fi
}

container_cpu_limit_percent() {
    local name="$1"
    local nano_cpus cpu_quota cpu_period

    read -r nano_cpus cpu_quota cpu_period < <(
        docker inspect --format '{{.HostConfig.NanoCpus}} {{.HostConfig.CpuQuota}} {{.HostConfig.CpuPeriod}}' "$name" 2>/dev/null ||
            printf '0 0 0\n'
    )

    awk -v nano_cpus="$nano_cpus" -v cpu_quota="$cpu_quota" -v cpu_period="$cpu_period" '
        BEGIN {
            if (nano_cpus > 0) printf "%.2f", nano_cpus / 1000000000 * 100
            else if (cpu_quota > 0 && cpu_period > 0) printf "%.2f", cpu_quota / cpu_period * 100
            else printf ""
        }
    '
}

sample_containers() {
    local timestamp="$1"
    local output_file="$2"

    docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.BlockIO}}|{{.NetIO}}' |
        while IFS='|' read -r name cpu_percent mem_usage block_io net_io; do
            local cpu_limit_percent mem_used_mb mem_limit_mb
            cpu_percent="${cpu_percent%\%}"
            cpu_limit_percent=$(container_cpu_limit_percent "$name")
            read -r mem_used_mb mem_limit_mb < <(parse_mem_usage_mb "$mem_usage")
            printf '%s,container,%s,%s,%s,%s,%s,,,%s,%s\n' \
                "$timestamp" "$name" "$cpu_percent" "$cpu_limit_percent" "$mem_used_mb" "$mem_limit_mb" "$block_io" "$net_io" >> "$output_file"
        done
}

sample_once() {
    local output_file="$1"
    local previous_idle="$2"
    local previous_total="$3"
    local timestamp
    local current_totals

    timestamp=$(date --iso-8601=seconds)
    current_totals=$(sample_host "$timestamp" "$previous_idle" "$previous_total" "$output_file")
    sample_containers "$timestamp" "$output_file"
    echo "$current_totals"
}

main() {
    if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
        usage
        exit 0
    fi

    local interval_seconds="${1:-$DEFAULT_INTERVAL_SECONDS}"
    local duration_seconds="${2:-}"
    local output_file="${3:-$DEFAULT_OUTPUT_FILE}"

    validate_positive_integer "interval-seconds" "$interval_seconds"

    if [[ -n "$duration_seconds" ]]; then
        validate_positive_integer "duration-seconds" "$duration_seconds"
    fi

    check_requirements
    mkdir -p "$(dirname "$output_file")"
    write_header "$output_file"

    echo "Sampling host and container stats every ${interval_seconds}s into ${output_file}"

    local started_at previous_idle previous_total current_totals
    started_at=$(date +%s)
    read -r previous_idle previous_total < <(read_cpu_totals)

    while true; do
        sleep "$interval_seconds"
        current_totals=$(sample_once "$output_file" "$previous_idle" "$previous_total")
        read -r previous_idle previous_total <<< "$current_totals"

        if [[ -n "$duration_seconds" ]]; then
            local now
            now=$(date +%s)
            if (( now - started_at >= duration_seconds )); then
                break
            fi
        fi
    done
}

main "$@"
