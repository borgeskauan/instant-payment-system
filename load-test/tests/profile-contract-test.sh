#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

cat > "$tmp_dir/fake-provision-funds" <<'SH'
#!/bin/bash
set -euo pipefail
printf '%s\n' "$*" >> "$FUNDING_COMMAND_LOG"
SH
chmod +x "$tmp_dir/fake-provision-funds"

cat > "$tmp_dir/fake-cert-generator" <<'SH'
#!/bin/bash
set -euo pipefail
printf '%s\n' "$*" >> "$CERT_COMMAND_LOG"
SH
chmod +x "$tmp_dir/fake-cert-generator"
touch "$tmp_dir/ca.crt"

export FUNDING_COMMAND_LOG="$tmp_dir/funding-commands.log"
export CERT_COMMAND_LOG="$tmp_dir/cert-commands.log"
export PROVISION_FUNDS_SCRIPT="$tmp_dir/fake-provision-funds"
export LOADTOOL_CERT_SCRIPT="$tmp_dir/fake-cert-generator"
export LOADTOOL_CA_CERT="$tmp_dir/ca.crt"
source "$ROOT_DIR/run-load-test.sh"
trap 'cleanup; rm -rf "$tmp_dir"' EXIT

cat > "$tmp_dir/validating-loadtool" <<'SH'
#!/bin/bash
set -euo pipefail
if [[ "$1" != "validate-profile" || "$2" != "--profile" || "$3" != "uniform-smoke" ]]; then
    echo "unexpected validate-profile invocation: $*" >&2
    exit 1
fi
cat <<'JSON'
{
  "profile": "uniform-smoke",
  "schemaVersion": 1,
  "warmupSeconds": 60,
  "activeSeconds": 60,
  "drainSeconds": 30,
  "scenarios": [
    {
      "type": "happy-path",
      "share": 1.0,
      "participants": {
        "firstPair": 41,
        "hotPairCount": 2,
        "coldPairCount": 1
      },
      "funding": {
        "balance": 1000000000,
        "resetIfExists": true
      }
    }
  ]
}
JSON
SH
chmod +x "$tmp_dir/validating-loadtool"

PROFILE_NAME="uniform-smoke"
LOADTOOL_BUILD_DIR="$tmp_dir"
LOADTOOL_BIN="$tmp_dir/validating-loadtool"
validate_profile_with_loadtool

if [[ "$PROFILE_SCHEMA_VERSION" != 1 || "$PROFILE_WARMUP_SECONDS" != 60 || "$PROFILE_ACTIVE_SECONDS" != 60 || "$PROFILE_DRAIN_SECONDS" != 30 ]]; then
    echo "runner did not consume the normalized execution window" >&2
    exit 1
fi
if [[ "${#PROFILE_SCENARIO_TYPES[@]}" != 1 || "${PROFILE_SCENARIO_TYPES[0]}" != happy-path || "${PROFILE_SCENARIO_SHARES[0]}" != 1.0 ]]; then
    echo "runner did not consume normalized scenario metadata" >&2
    exit 1
fi
if [[ "${PROFILE_SCENARIO_FIRST_PAIRS[0]}" != 41 || "${PROFILE_SCENARIO_HOT_PAIR_COUNTS[0]}" != 2 || "${PROFILE_SCENARIO_COLD_PAIR_COUNTS[0]}" != 1 ]]; then
    echo "runner did not consume normalized participant range" >&2
    exit 1
fi
if [[ "${PROFILE_SCENARIO_FUNDING_BALANCES[0]}" != 1000000000 || "${PROFILE_SCENARIO_FUNDING_RESET_BEHAVIORS[0]}" != true ]]; then
    echo "runner did not consume normalized funding settings" >&2
    exit 1
fi

mkdir -p "$tmp_dir/result"
prepare_loadtool_certificates "$tmp_dir/result"
PROVISION_FUNDS=true
provision_funds_if_enabled "$tmp_dir/result"
PROFILE_SCENARIO_FUNDING_RESET_BEHAVIORS[0]=false
provision_funds_if_enabled "$tmp_dir/result"

cat > "$tmp_dir/expected-funding-commands.log" <<'EOF'
--balance 1000000000 --reset-if-exists --ispb 10000041 --ispb 20000041 --ispb 10000042 --ispb 20000042 --ispb 10000043 --ispb 20000043
--balance 1000000000 --preserve-if-exists --ispb 10000041 --ispb 20000041 --ispb 10000042 --ispb 20000042 --ispb 10000043 --ispb 20000043
EOF
if ! diff -u "$tmp_dir/expected-funding-commands.log" "$FUNDING_COMMAND_LOG"; then
    echo "runner did not pass normalized funding settings to provision-funds" >&2
    exit 1
fi

cat > "$tmp_dir/expected-cert-commands.log" <<EOF
--psp-root $tmp_dir/result/certs psp 10000041
--psp-root $tmp_dir/result/certs psp 20000041
--psp-root $tmp_dir/result/certs psp 10000042
--psp-root $tmp_dir/result/certs psp 20000042
--psp-root $tmp_dir/result/certs psp 10000043
--psp-root $tmp_dir/result/certs psp 20000043
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
main --profile uniform-smoke semantic-order-test
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
