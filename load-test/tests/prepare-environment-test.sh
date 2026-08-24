#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PREPARE_ENVIRONMENT="${ROOT_DIR}/scripts/prepare-environment.sh"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

mkdir -p "$tmp_dir/fake-bin" "$tmp_dir/run/inputs"
export DOCKER_CALLS="$tmp_dir/docker-calls.log"
export FUNDING_CALLS="$tmp_dir/funding-calls.log"

cat > "$tmp_dir/fake-bin/docker" <<'SH'
#!/bin/bash
set -euo pipefail

printf '%s\n' "$*" >> "$DOCKER_CALLS"

if [[ "$*" == *"kafka-consumer-groups"* ]]; then
    group="${!#}"
    if [[ "${KAFKA_UNREADABLE_GROUP:-}" == "$group" ]]; then
        echo "consumer group unavailable" >&2
        exit 41
    fi
    case "$group" in
        spi-payment-request-consumer-group)
            topic="spi-payment-requests"
            lag="${SPI_PAYMENT_LAG:-0}"
            ;;
        spi-status-report-consumer-group)
            topic="spi-payment-status-reports"
            lag="${SPI_STATUS_LAG:-0}"
            ;;
        notification-gateway-group)
            topic="psp-notifications-v1"
            lag="${GATEWAY_LAG:-0}"
            ;;
        *)
            echo "unexpected group: $group" >&2
            exit 42
            ;;
    esac
    printf 'GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID\n'
    printf '%s %s 0 10 %s %s consumer\n' "$group" "$topic" "$((10 + lag))" "$lag"
    exit 0
fi

echo "unexpected docker command: $*" >&2
exit 43
SH
chmod +x "$tmp_dir/fake-bin/docker"

cat > "$tmp_dir/fake-provision-funds" <<'SH'
#!/bin/bash
set -euo pipefail

printf '%s\n' "$*" >> "$FUNDING_CALLS"
if [[ "${FAIL_FUNDING:-false}" == true ]]; then
    exit 37
fi
SH
chmod +x "$tmp_dir/fake-provision-funds"

export PATH="$tmp_dir/fake-bin:$PATH"
export PROVISION_FUNDS_SCRIPT="$tmp_dir/fake-provision-funds"

write_valid_plan() {
    cat > "$tmp_dir/run/inputs/execution-plan.json" <<'JSON'
{
  "profile": "mixed-outcomes-smoke",
  "warmupSeconds": 5,
  "activeSeconds": 10,
  "drainSeconds": 10,
  "scenarios": [
    {
      "name": "happy-path",
      "participants": {
        "pairNumberStart": 1,
        "hotPairCount": 2,
        "coldPairCount": 1
      },
      "provisioning": {
        "payerBalance": "123.45",
        "receiverBalance": "0.00",
        "resetIfExists": true
      }
    },
    {
      "name": "insufficient-funds",
      "participants": {
        "pairNumberStart": 4,
        "hotPairCount": 1,
        "coldPairCount": 1
      },
      "provisioning": {
        "payerBalance": "0.00",
        "receiverBalance": "0.00",
        "resetIfExists": false
      }
    }
  ]
}
JSON
}

clear_calls() {
    : > "$DOCKER_CALLS"
    : > "$FUNDING_CALLS"
}

assert_no_external_calls() {
    if [[ -s "$DOCKER_CALLS" || -s "$FUNDING_CALLS" ]]; then
        echo "environment preparation caused external calls before validating its execution plan" >&2
        exit 1
    fi
}

clear_calls
if "$PREPARE_ENVIRONMENT" --run-dir "$tmp_dir/missing" >/dev/null 2>&1; then
    echo "environment preparation accepted a missing run directory" >&2
    exit 1
fi
assert_no_external_calls

mkdir -p "$tmp_dir/malformed/inputs"
printf '%s\n' '{' > "$tmp_dir/malformed/inputs/execution-plan.json"
clear_calls
if "$PREPARE_ENVIRONMENT" --run-dir "$tmp_dir/malformed" >/dev/null 2>&1; then
    echo "environment preparation accepted malformed execution-plan.json" >&2
    exit 1
fi
assert_no_external_calls

mkdir -p "$tmp_dir/unusable/inputs"
cat > "$tmp_dir/unusable/inputs/execution-plan.json" <<'JSON'
{
  "scenarios": [
    {
      "participants": {
        "pairNumberStart": 1,
        "hotPairCount": 0,
        "coldPairCount": 0
      },
      "provisioning": {
        "payerBalance": "0.00",
        "receiverBalance": "0.00",
        "resetIfExists": true
      }
    }
  ]
}
JSON
clear_calls
if "$PREPARE_ENVIRONMENT" --run-dir "$tmp_dir/unusable" >/dev/null 2>&1; then
    echo "environment preparation accepted an execution plan without participants" >&2
    exit 1
fi
assert_no_external_calls

write_valid_plan
clear_calls
"$PREPARE_ENVIRONMENT" --run-dir "$tmp_dir/run" >/dev/null
if [[ -s "$DOCKER_CALLS" ]]; then
    echo "environment preparation still probes Kafka before funding" >&2
    exit 1
fi
cat > "$tmp_dir/expected-funding-calls.log" <<'EOF'
--balance 123.45 --reset-if-exists --ispb 10000001 --ispb 10000002 --ispb 10000003
--balance 0.00 --reset-if-exists --ispb 20000001 --ispb 20000002 --ispb 20000003
--balance 0.00 --preserve-if-exists --ispb 10000004 --ispb 10000005
--balance 0.00 --preserve-if-exists --ispb 20000004 --ispb 20000005
EOF
if ! diff -u "$tmp_dir/expected-funding-calls.log" "$FUNDING_CALLS"; then
    echo "environment preparation did not provision the resolved execution plan" >&2
    exit 1
fi

clear_calls
set +e
FAIL_FUNDING=true "$PREPARE_ENVIRONMENT" --run-dir "$tmp_dir/run" >/dev/null 2>&1
funding_status=$?
set -e
if [[ "$funding_status" -ne 37 ]]; then
    echo "environment preparation returned $funding_status, want funding failure 37" >&2
    exit 1
fi
