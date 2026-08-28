#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

assert_flags() {
    local want_jfr="$1"
    local want_postgres="$2"
    shift 2

    (
        source "${ROOT_DIR}/scripts/run-diagnostics.sh"
        parse_args run --run-dir /tmp/diagnostic-test "$@" -- true
        [[ "$ENABLE_JFR" == "$want_jfr" ]]
        [[ "$ENABLE_POSTGRES_STATEMENTS" == "$want_postgres" ]]
    )
}

assert_flags true true
assert_flags false true --no-jfr
assert_flags true false --no-postgres-statements
assert_flags false false --no-jfr --no-postgres-statements

usage_output="$({ source "${ROOT_DIR}/scripts/run-diagnostics.sh"; usage; })"
for flag in --no-jfr --no-postgres-statements; do
    if ! grep -Fq -- "$flag" <<< "$usage_output"; then
        echo "runner usage does not list ${flag}" >&2
        exit 1
    fi
done

if grep -Fq -- '--no-spi-trace' <<< "$usage_output"; then
    echo "runner usage still lists obsolete option --no-spi-trace" >&2
    exit 1
fi

for obsolete_flag in --jfr --spi-trace --no-spi-trace --postgres-statements; do
    if grep -Eq -- "(^|[[:space:]])${obsolete_flag}([[:space:]]|$)" <<< "$usage_output"; then
        echo "runner usage still lists obsolete option ${obsolete_flag}" >&2
        exit 1
    fi
done
