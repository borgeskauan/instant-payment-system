#!/bin/bash

set -euo pipefail

export LC_ALL=C

readonly DEFAULT_INTERVAL_SECONDS=5
readonly DEFAULT_OUTPUT_FILE="process-stats.csv"
readonly DEFAULT_TOP_N=20

usage() {
    echo "Usage: $(basename "$0") [interval-seconds] [duration-seconds] [output-file] [top-n]"
    echo
    echo "Examples:"
    echo "  $(basename "$0")"
    echo "  $(basename "$0") 5 120 summary/process-stats.csv 20"
    echo
    echo "Columns:"
    echo "  timestamp,rank,pid,ppid,command,cpu_percent,mem_percent,rss_mb"
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
    if ! command -v ps &>/dev/null; then
        error_exit "ps not found."
    fi
}

write_header() {
    local output_file="$1"
    echo "timestamp,rank,pid,ppid,command,cpu_percent,mem_percent,rss_mb" > "$output_file"
}

sample_once() {
    local output_file="$1"
    local top_n="$2"
    local timestamp

    timestamp=$(date --iso-8601=seconds)

    ps -eo pid=,ppid=,comm=,pcpu=,pmem=,rss= --sort=-pcpu |
        awk -v timestamp="$timestamp" -v top_n="$top_n" '
            $3 == "ps" || $3 == "awk" || $3 ~ /^sample-process/ {
                next
            }
            ++rank <= top_n {
                rss_mb = $6 / 1024
                printf "%s,%d,%s,%s,%s,%.2f,%.2f,%.2f\n",
                    timestamp, rank, $1, $2, $3, $4, $5, rss_mb
            }
        ' >> "$output_file"
}

main() {
    if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
        usage
        exit 0
    fi

    local interval_seconds="${1:-$DEFAULT_INTERVAL_SECONDS}"
    local duration_seconds="${2:-}"
    local output_file="${3:-$DEFAULT_OUTPUT_FILE}"
    local top_n="${4:-$DEFAULT_TOP_N}"

    validate_positive_integer "interval-seconds" "$interval_seconds"
    validate_positive_integer "top-n" "$top_n"

    if [[ -n "$duration_seconds" ]]; then
        validate_positive_integer "duration-seconds" "$duration_seconds"
    fi

    check_requirements
    mkdir -p "$(dirname "$output_file")"
    write_header "$output_file"

    echo "Sampling top ${top_n} host processes every ${interval_seconds}s into ${output_file}"

    local started_at
    started_at=$(date +%s)

    while true; do
        sample_once "$output_file" "$top_n"

        if [[ -n "$duration_seconds" ]]; then
            local now
            now=$(date +%s)
            if (( now - started_at >= duration_seconds )); then
                break
            fi
        fi

        sleep "$interval_seconds"
    done
}

main "$@"
