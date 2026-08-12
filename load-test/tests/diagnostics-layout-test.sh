#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

mkdir -p "$tmp_dir/scripts" "$tmp_dir/result/logs" "$tmp_dir/result/diagnostics"

cat > "$tmp_dir/scripts/spi-trace.sh" <<'SH'
#!/bin/bash
set -euo pipefail
echo "spi trace $1"
if [[ "$1" == copy ]]; then
    printf 'timestamp,event\n' > "$2/spi-trace.csv"
fi
SH

cat > "$tmp_dir/scripts/postgres-statements.sh" <<'SH'
#!/bin/bash
set -euo pipefail
echo "postgres statements $1"
if [[ "$1" == snapshot ]]; then
    printf 'query,calls\n' > "$2"
fi
SH

chmod +x "$tmp_dir/scripts/spi-trace.sh" "$tmp_dir/scripts/postgres-statements.sh"

export SCRIPTS_DIR="$tmp_dir/scripts"
source "$ROOT_DIR/run-load-test.sh"

start_spi_trace "$tmp_dir/result/logs/spi-trace.log"
stop_spi_trace "$tmp_dir/result"
copy_spi_trace "$tmp_dir/result"

enable_postgres_statement_stats "$tmp_dir/result"
capture_postgres_statement_stats "$tmp_dir/result"
disable_postgres_statement_stats "$tmp_dir/result"

for artifact in \
    "$tmp_dir/result/diagnostics/spi-trace.csv" \
    "$tmp_dir/result/diagnostics/postgres-statements.csv" \
    "$tmp_dir/result/logs/spi-trace.log" \
    "$tmp_dir/result/logs/postgres-statements.log"; do
    if [[ ! -f "$artifact" ]]; then
        echo "missing diagnostic bundle artifact: $artifact" >&2
        exit 1
    fi
done

for legacy_artifact in \
    "$tmp_dir/result/spi-trace.csv" \
    "$tmp_dir/result/spi-trace.log" \
    "$tmp_dir/result/postgres-statements.csv" \
    "$tmp_dir/result/postgres-statements.log"; do
    if [[ -e "$legacy_artifact" ]]; then
        echo "legacy root diagnostic artifact still produced: $legacy_artifact" >&2
        exit 1
    fi
done
