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

export FUNDING_COMMAND_LOG="$tmp_dir/funding-commands.log"
export PROVISION_FUNDS_SCRIPT="$tmp_dir/fake-provision-funds"
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
  "participants": {
    "hotPairCount": 10,
    "coldPairCount": 40
  },
  "funding": {
    "balance": 1000000000,
    "resetIfExists": true
  }
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
if [[ "$PROFILE_HOT_PAIR_COUNT" != 10 || "$PROFILE_COLD_PAIR_COUNT" != 40 ]]; then
    echo "runner did not consume normalized participant counts" >&2
    exit 1
fi
if [[ "$PROFILE_FUNDING_BALANCE" != 1000000000 || "$PROFILE_FUNDING_RESET_IF_EXISTS" != true ]]; then
    echo "runner did not consume normalized funding settings" >&2
    exit 1
fi

mkdir -p "$tmp_dir/result"
PROVISION_FUNDS=true
provision_funds_if_enabled "$tmp_dir/result"
PROFILE_FUNDING_RESET_IF_EXISTS=false
provision_funds_if_enabled "$tmp_dir/result"

cat > "$tmp_dir/expected-funding-commands.log" <<'EOF'
--vus 50 --balance 1000000000 --reset-if-exists
--vus 50 --balance 1000000000 --preserve-if-exists
EOF
if ! diff -u "$tmp_dir/expected-funding-commands.log" "$FUNDING_COMMAND_LOG"; then
    echo "runner did not pass normalized funding settings to provision-funds" >&2
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
