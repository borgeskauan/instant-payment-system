#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

mkdir -p "$tmp_dir/bin"
cat > "$tmp_dir/bin/docker" <<'SH'
#!/bin/bash
set -euo pipefail

if [[ "$1" != stats || "$*" == *--no-stream* ]]; then
    echo "unexpected docker invocation: $*" >&2
    exit 2
fi

for _ in 1 2; do
    if [[ "$_" -eq 1 ]]; then
        prefix=$'\033[H'
    else
        prefix=$'\033[J\033[H'
    fi
    printf '%s\n' \
        "${prefix}"$'postgres\t99.00%\t440MiB / 512MiB\t1GB / 2GB\t3GB / 4GB\033[K' \
        $'kafka\t98.00%\t650MiB / 2GiB\t2GB / 3GB\t4GB / 5GB\033[K' \
        $'kafka-producer\t75.00%\t250MiB / 384MiB\t3GB / 4GB\t5GB / 6GB\033[K' \
        $'spi\t20.00%\t500MiB / 768MiB\t4GB / 5GB\t6GB / 7GB\033[K' \
        $'notification-gateway\t10.00%\t300MiB / 512MiB\t5GB / 6GB\t7GB / 8GB\033[K' \
        $'\033[K'
done
SH
chmod +x "$tmp_dir/bin/docker"

export PATH="$tmp_dir/bin:$PATH"
export CONTAINER_STATS_INTERVAL_MS=0
export CONTAINER_STATS_MAX_SAMPLES=2

bash "$ROOT_DIR/scripts/container-stats.sh" \
    sample "$tmp_dir/container-stats.csv"

expected_header='sampled_at_ns,container,cpu_percent,memory_usage,network_io,block_io'
if [[ "$(head -n 1 "$tmp_dir/container-stats.csv")" != "$expected_header" ]]; then
    echo "unexpected container stats header" >&2
    exit 1
fi
if [[ "$(wc -l < "$tmp_dir/container-stats.csv")" -ne 11 ]]; then
    echo "bounded container sampling produced the wrong row count" >&2
    exit 1
fi
for container in postgres kafka kafka-producer spi notification-gateway; do
    if [[ "$(grep -c ",$container," "$tmp_dir/container-stats.csv")" -ne 2 ]]; then
        echo "container stats did not produce two samples for $container" >&2
        exit 1
    fi
done

if CONTAINER_STATS_INTERVAL_MS=100000 CONTAINER_STATS_MAX_SAMPLES=2 \
    bash "$ROOT_DIR/scripts/container-stats.sh" sample "$tmp_dir/throttled.csv" >/dev/null 2>&1; then
    echo "container stats emitted immediate duplicate stream refreshes" >&2
    exit 1
fi

if CONTAINER_STATS_MAX_SAMPLES=invalid \
    bash "$ROOT_DIR/scripts/container-stats.sh" sample "$tmp_dir/invalid.csv" >/dev/null 2>&1; then
    echo "container stats accepted an invalid sample limit" >&2
    exit 1
fi
