#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

mkdir -p "$tmp_dir/scripts" "$tmp_dir/results"
cat > "$tmp_dir/scripts/container-jfr.sh" <<'SH'
#!/bin/bash
set -euo pipefail
echo "$*" >> "${JFR_COMMAND_LOG}"
SH
chmod +x "$tmp_dir/scripts/container-jfr.sh"

export SCRIPTS_DIR="$tmp_dir/scripts"
export JFR_COMMAND_LOG="$tmp_dir/jfr-commands.log"

source "${ROOT_DIR}/run-load-test.sh"

start_jfr_recordings "$tmp_dir/results"

if [[ "$JFR_ACTIVE" != true ]]; then
    echo "JFR_ACTIVE should be true after starting JFR recordings" >&2
    exit 1
fi

stop_jfr_recordings "$tmp_dir/results"

if [[ "$JFR_ACTIVE" != false ]]; then
    echo "JFR_ACTIVE should be false after stopping JFR recordings" >&2
    exit 1
fi

expected="$tmp_dir/expected.log"
cat > "$expected" <<EOF
start kafka-producer kafka-producer-load-test /tmp/kafka-producer-load-test.jfr
start spi spi-load-test /tmp/spi-load-test.jfr
start notification-gateway notification-gateway-load-test /tmp/notification-gateway-load-test.jfr
stop kafka-producer kafka-producer-load-test /tmp/kafka-producer-load-test.jfr $tmp_dir/results/kafka-producer-load-test.jfr
stop spi spi-load-test /tmp/spi-load-test.jfr $tmp_dir/results/spi-load-test.jfr
stop notification-gateway notification-gateway-load-test /tmp/notification-gateway-load-test.jfr $tmp_dir/results/notification-gateway-load-test.jfr
EOF

if ! diff -u "$expected" "$JFR_COMMAND_LOG"; then
    echo "JFR helper commands did not match expected container recordings" >&2
    exit 1
fi
