#!/bin/bash

set -euo pipefail

readonly POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-postgres}"
readonly POSTGRES_USER="${POSTGRES_USER:-postgres}"
readonly POSTGRES_DB="${POSTGRES_DB:-postgres}"

usage() {
    echo "Usage: $(basename "$0") enable-and-reset|snapshot <output.csv>|disable"
}

run_psql() {
    docker exec -i "$POSTGRES_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"
}

require_pg_stat_statements_preloaded() {
    local preload_libraries
    preload_libraries="$(run_psql -At -c "SHOW shared_preload_libraries;" | tr -d '[:space:]')"

    if [[ "$preload_libraries" != *pg_stat_statements* ]]; then
        echo "pg_stat_statements is not preloaded in Postgres." >&2
        echo "Recreate the postgres container so shared_preload_libraries=pg_stat_statements takes effect." >&2
        exit 1
    fi
}

wait_for_track_setting() {
    local expected="$1"

    for _ in {1..50}; do
        local current
        current="$(run_psql -At -c "SHOW pg_stat_statements.track;" | tr -d '[:space:]')"
        if [[ "$current" == "$expected" ]]; then
            return 0
        fi
        sleep 0.1
    done

    echo "Timed out waiting for pg_stat_statements.track=${expected}." >&2
    echo "Current value: $(run_psql -At -c "SHOW pg_stat_statements.track;" | tr -d '[:space:]')" >&2
    exit 1
}

enable_and_reset_stats() {
    require_pg_stat_statements_preloaded

    run_psql -v ON_ERROR_STOP=1 -q <<'SQL'
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
ALTER SYSTEM SET pg_stat_statements.track = 'all';
SELECT pg_reload_conf();
SELECT pg_stat_statements_reset();
SQL
    wait_for_track_setting "all"
}

disable_stats() {
    require_pg_stat_statements_preloaded

    run_psql -v ON_ERROR_STOP=1 -q <<'SQL'
ALTER SYSTEM SET pg_stat_statements.track = 'none';
SELECT pg_reload_conf();
SQL
    wait_for_track_setting "none"
}

snapshot_stats() {
    local output_file="$1"

    mkdir -p "$(dirname "$output_file")"
    run_psql -v ON_ERROR_STOP=1 -q -c "\copy (
        SELECT
            row_number() OVER (ORDER BY total_exec_time DESC) AS rank,
            queryid,
            calls,
            round(total_exec_time::numeric, 3) AS total_exec_time_ms,
            round(mean_exec_time::numeric, 3) AS mean_exec_time_ms,
            round(max_exec_time::numeric, 3) AS max_exec_time_ms,
            rows,
            shared_blks_hit,
            shared_blks_read,
            shared_blks_dirtied,
            shared_blks_written,
            temp_blks_read,
            temp_blks_written,
            wal_records,
            wal_bytes,
            regexp_replace(query, '[[:space:]]+', ' ', 'g') AS query
        FROM pg_stat_statements
        WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
        ORDER BY total_exec_time DESC
        LIMIT 50
    ) TO STDOUT WITH CSV HEADER" > "$output_file"
}

main() {
    if [[ $# -lt 1 ]]; then
        usage >&2
        exit 2
    fi

    case "$1" in
        enable-and-reset)
            if [[ $# -ne 1 ]]; then
                usage >&2
                exit 2
            fi
            enable_and_reset_stats
            ;;
        snapshot)
            if [[ $# -ne 2 ]]; then
                usage >&2
                exit 2
            fi
            snapshot_stats "$2"
            ;;
        disable)
            if [[ $# -ne 1 ]]; then
                usage >&2
                exit 2
            fi
            disable_stats
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
