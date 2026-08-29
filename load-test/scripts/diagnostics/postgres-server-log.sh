#!/bin/bash

set -euo pipefail

readonly POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-postgres}"

usage() {
    echo "Usage: $(basename "$0") capture <since> <until> <output>"
}

capture() {
    local since="$1"
    local until="$2"
    local output_file="$3"
    local temporary_file status

    mkdir -p "$(dirname "$output_file")"
    temporary_file="$(mktemp "${output_file}.tmp.XXXXXX")"
    if docker logs --timestamps \
            --since "$since" \
            --until "$until" \
            "$POSTGRES_CONTAINER" > "$temporary_file" 2>&1; then
        mv "$temporary_file" "$output_file"
        return 0
    else
        status=$?
    fi

    cat "$temporary_file" >&2
    rm -f "$temporary_file"
    return "$status"
}

if [[ $# -ne 4 || "$1" != capture ]]; then
    usage >&2
    exit 2
fi

capture "$2" "$3" "$4"
