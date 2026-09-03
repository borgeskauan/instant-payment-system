#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

assert_flags() {
    local want_jfr="$1"
    local want_system="$2"
    shift 2

    (
        source "${ROOT_DIR}/scripts/diagnostics/run-diagnostics.sh"
        parse_args run --run-dir /tmp/diagnostic-test "$@" -- true
        [[ "$ENABLE_JFR" == "$want_jfr" ]]
        [[ "$ENABLE_SYSTEM_DIAGNOSTICS" == "$want_system" ]]
    )
}

assert_flags true true
assert_flags false true --no-jfr
assert_flags true false --no-system-diagnostics
assert_flags false false --no-jfr --no-system-diagnostics

usage_output="$({ source "${ROOT_DIR}/scripts/diagnostics/run-diagnostics.sh"; usage; })"
for flag in --no-jfr --no-system-diagnostics; do
    if ! grep -Fq -- "$flag" <<< "$usage_output"; then
        echo "runner usage does not list ${flag}" >&2
        exit 1
    fi
done
