#!/bin/bash

set -euo pipefail

readonly POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-postgres}"
readonly POSTGRES_USER="${POSTGRES_USER:-postgres}"
readonly POSTGRES_DB="${POSTGRES_DB:-postgres}"
readonly DIAGNOSTIC_APPLICATION_NAME="load-test-postgres-diagnostics"
readonly ACTIVITY_HEADER="sampled_at_ns,pid,application_name,state,query_id,query_age_ms,transaction_age_ms,wait_event_type,wait_event,blocking_pids"
readonly IO_HEADER="phase,sampled_at_ns,source,scope,metric,value"

usage() {
    echo "Usage: $(basename "$0") sample-activity <output.csv>|snapshot-io <before|after> <output.csv>"
}

run_psql() {
    docker exec \
        -e "PGAPPNAME=${DIAGNOSTIC_APPLICATION_NAME}" \
        -i "$POSTGRES_CONTAINER" \
        psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"
}

sample_activity() {
    local output_file="$1"
    local interval_ms="${POSTGRES_ACTIVITY_INTERVAL_MS:-250}"
    local max_samples="${POSTGRES_ACTIVITY_MAX_SAMPLES:-0}"
    local samples=0
    local stopping=false
    local interval_ns sample_started_at_ns elapsed_ns remaining_ns sleep_seconds

    if [[ ! "$max_samples" =~ ^[0-9]+$ ]]; then
        echo "POSTGRES_ACTIVITY_MAX_SAMPLES must be a non-negative integer." >&2
        return 2
    fi
    if [[ ! "$interval_ms" =~ ^[0-9]+$ ]]; then
        echo "POSTGRES_ACTIVITY_INTERVAL_MS must be a non-negative integer." >&2
        return 2
    fi
    interval_ns=$((interval_ms * 1000000))

    mkdir -p "$(dirname "$output_file")"
    printf '%s\n' "$ACTIVITY_HEADER" > "$output_file"
    trap 'stopping=true' INT TERM

    while [[ "$stopping" == false ]]; do
        sample_started_at_ns="$(date +%s%N)"
        run_psql -v ON_ERROR_STOP=1 -q -c "\copy (
            SELECT
                floor(extract(epoch FROM statement_timestamp()) * 1000000000)::numeric::text,
                pid,
                application_name,
                state,
                query_id,
                CASE WHEN query_start IS NULL THEN NULL
                     ELSE round(extract(epoch FROM (statement_timestamp() - query_start)) * 1000, 3)
                END,
                CASE WHEN xact_start IS NULL THEN NULL
                     ELSE round(extract(epoch FROM (statement_timestamp() - xact_start)) * 1000, 3)
                END,
                wait_event_type,
                wait_event,
                array_to_string(pg_blocking_pids(pid), ';')
            FROM pg_stat_activity
            WHERE datname = current_database()
              AND backend_type = 'client backend'
              AND pid <> pg_backend_pid()
              AND application_name <> '${DIAGNOSTIC_APPLICATION_NAME}'
            ORDER BY pid
        ) TO STDOUT WITH CSV" >> "$output_file"

        samples=$((samples + 1))
        if ((max_samples > 0 && samples >= max_samples)); then
            break
        fi
        elapsed_ns=$(($(date +%s%N) - sample_started_at_ns))
        remaining_ns=$((interval_ns - elapsed_ns))
        if ((remaining_ns > 0)); then
            printf -v sleep_seconds '%d.%09d' \
                $((remaining_ns / 1000000000)) \
                $((remaining_ns % 1000000000))
            sleep "$sleep_seconds" || true
        fi
    done

    trap - INT TERM
}

snapshot_io() {
    local phase="$1"
    local output_file="$2"
    local sampled_at_ns

    if [[ "$phase" != before && "$phase" != after ]]; then
        echo "PostgreSQL I/O snapshot phase must be 'before' or 'after'." >&2
        return 2
    fi

    mkdir -p "$(dirname "$output_file")"
    if [[ ! -s "$output_file" ]]; then
        printf '%s\n' "$IO_HEADER" > "$output_file"
    fi
    sampled_at_ns="$(date +%s%N)"

    run_psql -v ON_ERROR_STOP=1 -q -c "\copy (
        SELECT
            'pg_stat_io' AS source,
            concat_ws('|', backend_type, object, context) AS scope,
            metric,
            value
        FROM pg_stat_io
        CROSS JOIN LATERAL (VALUES
            ('reads', reads::numeric),
            ('read_time_ms', read_time::numeric),
            ('writes', writes::numeric),
            ('write_time_ms', write_time::numeric),
            ('writebacks', writebacks::numeric),
            ('writeback_time_ms', writeback_time::numeric),
            ('extends', extends::numeric),
            ('extend_time_ms', extend_time::numeric),
            ('fsyncs', fsyncs::numeric),
            ('fsync_time_ms', fsync_time::numeric)
        ) AS metrics(metric, value)
        WHERE value IS NOT NULL
        UNION ALL
        SELECT
            'pg_stat_database',
            datname,
            metric,
            value
        FROM pg_stat_database
        CROSS JOIN LATERAL (VALUES
            ('blks_read', blks_read::numeric),
            ('blks_hit', blks_hit::numeric),
            ('blk_read_time_ms', blk_read_time::numeric),
            ('blk_write_time_ms', blk_write_time::numeric),
            ('temp_files', temp_files::numeric),
            ('temp_bytes', temp_bytes::numeric),
            ('deadlocks', deadlocks::numeric),
            ('xact_commit', xact_commit::numeric),
            ('xact_rollback', xact_rollback::numeric)
        ) AS metrics(metric, value)
        WHERE datname = current_database()
    ) TO STDOUT WITH CSV" |
        while IFS= read -r row; do
            printf '%s,%s,%s\n' "$phase" "$sampled_at_ns" "$row"
        done >> "$output_file"
}

main() {
    if [[ $# -lt 1 ]]; then
        usage >&2
        return 2
    fi

    case "$1" in
        sample-activity)
            if [[ $# -ne 2 ]]; then
                usage >&2
                return 2
            fi
            sample_activity "$2"
            ;;
        snapshot-io)
            if [[ $# -ne 3 ]]; then
                usage >&2
                return 2
            fi
            snapshot_io "$2" "$3"
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
