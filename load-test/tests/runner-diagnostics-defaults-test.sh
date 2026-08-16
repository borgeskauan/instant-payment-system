#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

assert_flags() {
    local want_jfr="$1"
    local want_trace="$2"
    local want_postgres="$3"
    shift 3

    (
        source "${ROOT_DIR}/run-load-test.sh"
        parse_args "$@" test-run
        [[ "$ENABLE_JFR" == "$want_jfr" ]]
        [[ "$ENABLE_SPI_TRACE" == "$want_trace" ]]
        [[ "$ENABLE_POSTGRES_STATEMENTS" == "$want_postgres" ]]
    )
}

assert_flags true true true
assert_flags false true true --no-jfr
assert_flags true false true --no-spi-trace
assert_flags true true false --no-postgres-statements
assert_flags false false false --no-jfr --no-spi-trace --no-postgres-statements

usage_output="$({ source "${ROOT_DIR}/run-load-test.sh"; usage; })"
for flag in --no-jfr --no-spi-trace --no-postgres-statements; do
    if ! grep -Fq -- "$flag" <<< "$usage_output"; then
        echo "runner usage does not list ${flag}" >&2
        exit 1
    fi
done

for obsolete_flag in --jfr --spi-trace --postgres-statements; do
    if grep -Eq -- "(^|[[:space:]])${obsolete_flag}([[:space:]]|$)" <<< "$usage_output"; then
        echo "runner usage still lists obsolete option ${obsolete_flag}" >&2
        exit 1
    fi
done
