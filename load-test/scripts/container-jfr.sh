#!/bin/bash

set -euo pipefail

usage() {
    echo "Usage: $(basename "$0") start <container> <recording-name> <container-jfr-file>"
    echo "       $(basename "$0") stop <container> <recording-name> <container-jfr-file> <host-output-file>"
}

run_jcmd() {
    local container="$1"
    shift

    docker exec "$container" jcmd 1 "$@"
}

start_jfr() {
    local container="$1"
    local recording_name="$2"
    local container_file="$3"

    echo "Starting ${container} JFR recording at $(date --iso-8601=seconds)"
    run_jcmd "$container" JFR.stop name="$recording_name" || true
    docker exec "$container" rm -f "$container_file" || true
    run_jcmd "$container" JFR.start \
        name="$recording_name" \
        settings=profile \
        filename="$container_file" \
        dumponexit=true
}

stop_jfr() {
    local container="$1"
    local recording_name="$2"
    local container_file="$3"
    local host_output_file="$4"

    echo "Stopping ${container} JFR recording at $(date --iso-8601=seconds)"
    run_jcmd "$container" JFR.stop name="$recording_name" filename="$container_file"
    docker cp "${container}:${container_file}" "$host_output_file"
    echo "${container} JFR saved: ${host_output_file}"
}

main() {
    if [[ $# -lt 1 ]]; then
        usage >&2
        exit 2
    fi

    case "$1" in
        start)
            if [[ $# -ne 4 ]]; then
                usage >&2
                exit 2
            fi
            start_jfr "$2" "$3" "$4"
            ;;
        stop)
            if [[ $# -ne 5 ]]; then
                usage >&2
                exit 2
            fi
            stop_jfr "$2" "$3" "$4" "$5"
            ;;
        -h|--help)
            usage
            ;;
        *)
            usage >&2
            echo "Unknown action: $1" >&2
            exit 2
            ;;
    esac
}

main "$@"
