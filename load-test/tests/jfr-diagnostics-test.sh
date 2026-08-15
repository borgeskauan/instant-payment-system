#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

mkdir -p "$tmp_dir/bin" "$tmp_dir/scripts" "$tmp_dir/results"

export DOCKER_COMMAND_LOG="$tmp_dir/docker-commands.log"
cat > "$tmp_dir/bin/docker" <<'SH'
#!/bin/bash
set -euo pipefail
echo "$*" >> "$DOCKER_COMMAND_LOG"
SH
chmod +x "$tmp_dir/bin/docker"

if ! PATH="$tmp_dir/bin:$PATH" \
    bash "$ROOT_DIR/scripts/container-jfr.sh" \
        start kafka-producer test /tmp/test.jfr \
        'jdk.TLSHandshake#enabled=true'; then
    echo "container JFR helper rejected an event-setting override" >&2
    exit 1
fi

expected_start='exec kafka-producer jcmd 1 JFR.start name=test settings=profile filename=/tmp/test.jfr dumponexit=true jdk.TLSHandshake#enabled=true'
if [[ "$(tail -n 1 "$DOCKER_COMMAND_LOG")" != "$expected_start" ]]; then
    echo "container JFR helper did not forward the event-setting override" >&2
    exit 1
fi

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
start kafka-producer kafka-producer-load-test /tmp/kafka-producer-load-test.jfr jdk.TLSHandshake#enabled=true
start spi spi-load-test /tmp/spi-load-test.jfr
start notification-gateway notification-gateway-load-test /tmp/notification-gateway-load-test.jfr
stop kafka-producer kafka-producer-load-test /tmp/kafka-producer-load-test.jfr $tmp_dir/results/diagnostics/jfr/kafka-producer.jfr
stop spi spi-load-test /tmp/spi-load-test.jfr $tmp_dir/results/diagnostics/jfr/spi.jfr
stop notification-gateway notification-gateway-load-test /tmp/notification-gateway-load-test.jfr $tmp_dir/results/diagnostics/jfr/notification-gateway.jfr
EOF

if ! diff -u "$expected" "$JFR_COMMAND_LOG"; then
    echo "JFR helper commands did not match expected container recordings" >&2
    exit 1
fi

for component in kafka-producer spi notification-gateway; do
    if [[ ! -f "$tmp_dir/results/logs/jfr/${component}.log" ]]; then
        echo "missing JFR command log for ${component}" >&2
        exit 1
    fi
done
