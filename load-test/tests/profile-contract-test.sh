#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

cat > "$tmp_dir/fake-prepare-environment" <<'SH'
#!/bin/bash
set -euo pipefail
printf '%s\n' "$*" >> "$PREPARE_ENVIRONMENT_COMMAND_LOG"
printf '%s\n' "prepared $*"
exit "${PREPARE_ENVIRONMENT_FAKE_EXIT_CODE:-0}"
SH
chmod +x "$tmp_dir/fake-prepare-environment"

cat > "$tmp_dir/fake-cert-generator" <<'SH'
#!/bin/bash
set -euo pipefail
printf '%s\n' "$*" >> "$CERT_COMMAND_LOG"
SH
chmod +x "$tmp_dir/fake-cert-generator"
touch "$tmp_dir/ca.crt"

export CERT_COMMAND_LOG="$tmp_dir/cert-commands.log"
export PREPARE_ENVIRONMENT_COMMAND_LOG="$tmp_dir/prepare-environment-commands.log"
export PREPARE_ENVIRONMENT_SCRIPT="$tmp_dir/fake-prepare-environment"
export LOADTOOL_CERT_SCRIPT="$tmp_dir/fake-cert-generator"
export LOADTOOL_CA_CERT="$tmp_dir/ca.crt"
source "$ROOT_DIR/run-load-test.sh"
trap 'cleanup; rm -rf "$tmp_dir"' EXIT

cat > "$tmp_dir/validating-loadtool" <<'SH'
#!/bin/bash
set -euo pipefail
if [[ "$1" != "validate-profile" || "$2" != "--profile" || "$3" != "mixed-outcomes-smoke" ]]; then
    echo "unexpected validate-profile invocation: $*" >&2
    exit 1
fi
cat <<'JSON'
{
  "profile": "mixed-outcomes-smoke",
  "schemaVersion": 1,
  "warmupSeconds": 60,
  "activeSeconds": 60,
  "drainSeconds": 30,
  "replay": {
    "pacs008": {
      "share": 0.1,
      "delaySeconds": 10
    },
    "pacs002": {
      "share": 0.2,
      "delaySeconds": 11
    }
  },
  "scenarios": [
    {
      "name": "happy-path",
      "share": 0.8,
      "participants": {
        "pairNumberStart": 1,
        "hotPairCount": 2,
        "coldPairCount": 1,
        "hotTrafficShare": 0.8
      },
      "amount": {
        "minimum": 100,
        "maximum": 100098
      },
      "funding": {
        "payer": {"mode": "cover-generated-debits"},
        "receiver": {"mode": "fixed", "balance": "0.00"},
        "resetIfExists": true
      },
      "provisioning": {
        "payerBalance": "123.45",
        "receiverBalance": "0.00",
        "resetIfExists": true
      },
      "expectations": {
        "httpStatus": "2xx",
        "payerNotification": {
          "deliverySemantics": "at-least-once",
          "status": "ACSC",
          "reasonCodes": []
        }
      }
    },
    {
      "name": "insufficient-funds",
      "share": 0.2,
      "participants": {
        "pairNumberStart": 4,
        "hotPairCount": 1,
        "coldPairCount": 1,
        "hotTrafficShare": 0.8
      },
      "amount": {
        "minimum": 100,
        "maximum": 100098
      },
      "funding": {
        "payer": {"mode": "fixed", "balance": "0.00"},
        "receiver": {"mode": "fixed", "balance": "0.00"},
        "resetIfExists": true
      },
      "provisioning": {
        "payerBalance": "0.00",
        "receiverBalance": "0.00",
        "resetIfExists": true
      },
      "expectations": {
        "httpStatus": "2xx",
        "payerNotification": {
          "deliverySemantics": "at-least-once",
          "status": "RJCT",
          "reasonCodes": ["AM04"]
        }
      }
    }
  ]
}
JSON
SH
chmod +x "$tmp_dir/validating-loadtool"

PROFILE_NAME="mixed-outcomes-smoke"
LOADTOOL_BUILD_DIR="$tmp_dir"
LOADTOOL_BIN="$tmp_dir/validating-loadtool"
validate_profile_with_loadtool

if [[ "$PROFILE_SCHEMA_VERSION" != 1 || "$PROFILE_WARMUP_SECONDS" != 60 || "$PROFILE_ACTIVE_SECONDS" != 60 || "$PROFILE_DRAIN_SECONDS" != 30 ]]; then
    echo "runner did not consume the normalized execution window" >&2
    exit 1
fi
if [[ "$PROFILE_PACS008_REPLAY_SHARE" != 0.1 || "$PROFILE_PACS008_REPLAY_DELAY_SECONDS" != 10 ]]; then
    echo "runner did not consume normalized replay settings" >&2
    exit 1
fi
if [[ "$PROFILE_PACS002_REPLAY_SHARE" != 0.2 || "$PROFILE_PACS002_REPLAY_DELAY_SECONDS" != 11 ]]; then
    echo "runner did not consume normalized PACS.002 replay settings" >&2
    exit 1
fi
if [[ "${#PROFILE_SCENARIO_NAMES[@]}" != 2 || "${PROFILE_SCENARIO_NAMES[0]}" != happy-path || "${PROFILE_SCENARIO_SHARES[0]}" != 0.8 || "${PROFILE_SCENARIO_NAMES[1]}" != insufficient-funds ]]; then
    echo "runner did not consume normalized scenario metadata" >&2
    exit 1
fi
if [[ "${PROFILE_SCENARIO_PAIR_NUMBER_STARTS[0]}" != 1 || "${PROFILE_SCENARIO_PAIR_NUMBER_STARTS[1]}" != 4 || "${PROFILE_SCENARIO_HOT_PAIR_COUNTS[0]}" != 2 || "${PROFILE_SCENARIO_COLD_PAIR_COUNTS[0]}" != 1 ]]; then
    echo "runner did not consume normalized participant range" >&2
    exit 1
fi
if ! python3 - "$LOADTOOL_VALIDATION_FILE" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    scenarios = json.load(handle)["scenarios"]

assert scenarios[0]["expectations"]["payerNotification"] == {
    "deliverySemantics": "at-least-once",
    "status": "ACSC",
    "reasonCodes": [],
}
assert scenarios[1]["expectations"]["payerNotification"] == {
    "deliverySemantics": "at-least-once",
    "status": "RJCT",
    "reasonCodes": ["AM04"],
}
PY
then
    echo "execution plan did not preserve observable payer-notification expectations" >&2
    exit 1
fi

PROFILE_PATH="$ROOT_DIR/profiles/mixed-outcomes-smoke.json"
prepare_run_workspace "$tmp_dir/workspace"
if ! cmp -s "$LOADTOOL_VALIDATION_FILE" "$tmp_dir/workspace/execution-plan.json"; then
    echo "execution-plan.json is not byte-identical to validate-profile output" >&2
    exit 1
fi
if ! cmp -s "$PROFILE_PATH" "$tmp_dir/workspace/profile.json"; then
    echo "profile.json is not byte-identical to the selected profile" >&2
    exit 1
fi
if [[ -e "$tmp_dir/workspace/go-loadtool" ]]; then
    echo "runner prepared generated go-loadtool output before the Go run" >&2
    exit 1
fi

prepare_environment "$tmp_dir/workspace"
if [[ "$(cat "$PREPARE_ENVIRONMENT_COMMAND_LOG")" != "--run-dir $tmp_dir/workspace" ]]; then
    echo "runner did not pass the fixed run directory to environment preparation" >&2
    exit 1
fi
if ! grep -q "prepared --run-dir $tmp_dir/workspace" "$tmp_dir/workspace/prepare-environment.log"; then
    echo "runner did not capture environment preparation output" >&2
    exit 1
fi

export PREPARE_ENVIRONMENT_FAKE_EXIT_CODE=29
if prepare_environment "$tmp_dir/workspace" >/dev/null 2>&1; then
    echo "runner accepted an environment preparation failure" >&2
    exit 1
else
    preparation_status=$?
fi
unset PREPARE_ENVIRONMENT_FAKE_EXIT_CODE
if [[ "$preparation_status" -ne 29 ]]; then
    echo "runner returned $preparation_status, want environment preparation exit code 29" >&2
    exit 1
fi

mkdir -p "$tmp_dir/result"
prepare_loadtool_certificates "$tmp_dir/result"

cat > "$tmp_dir/expected-cert-commands.log" <<EOF
--psp-root $tmp_dir/result/certs psp 10000001
--psp-root $tmp_dir/result/certs psp 20000001
--psp-root $tmp_dir/result/certs psp 10000002
--psp-root $tmp_dir/result/certs psp 20000002
--psp-root $tmp_dir/result/certs psp 10000003
--psp-root $tmp_dir/result/certs psp 20000003
--psp-root $tmp_dir/result/certs psp 10000004
--psp-root $tmp_dir/result/certs psp 20000004
--psp-root $tmp_dir/result/certs psp 10000005
--psp-root $tmp_dir/result/certs psp 20000005
EOF
if ! diff -u "$tmp_dir/expected-cert-commands.log" "$CERT_COMMAND_LOG"; then
    echo "runner did not generate certificates for the normalized participant range" >&2
    exit 1
fi

cat > "$tmp_dir/failing-loadtool" <<'SH'
#!/bin/bash
set -euo pipefail
echo "semantic profile validation failed" >&2
exit 1
SH
chmod +x "$tmp_dir/failing-loadtool"

export PROFILE_CONTRACT_TEST_TMP_DIR="$tmp_dir"
export PROFILE_CONTRACT_TEST_ROOT_DIR="$ROOT_DIR"
cat > "$tmp_dir/run-semantic-order-test" <<'SH'
#!/bin/bash
set -euo pipefail
source "$PROFILE_CONTRACT_TEST_ROOT_DIR/run-load-test.sh"
build_loadtool() {
    LOADTOOL_BIN="$PROFILE_CONTRACT_TEST_TMP_DIR/failing-loadtool"
}
prepare_run_workspace() {
    touch "$PROFILE_CONTRACT_TEST_TMP_DIR/result-side-effect"
}
main --profile mixed-outcomes-smoke semantic-order-test
SH
chmod +x "$tmp_dir/run-semantic-order-test"

if "$tmp_dir/run-semantic-order-test" >"$tmp_dir/semantic-order.log" 2>&1; then
    echo "runner should fail when authoritative Go validation fails" >&2
    exit 1
fi
if [[ -e "$tmp_dir/result-side-effect" ]]; then
    echo "runner created its result workspace before authoritative Go validation" >&2
    exit 1
fi
if ! grep -q "semantic profile validation failed" "$tmp_dir/semantic-order.log"; then
    echo "runner did not surface the Go validation failure" >&2
    exit 1
fi
