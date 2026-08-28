#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNNER="$ROOT_DIR/run-load-test.sh"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

mkdir -p "$tmp_dir/bin" "$tmp_dir/prepared/uniform-smoke/inputs" "$tmp_dir/prepared/uniform-smoke/certs"
printf '{"name":"uniform-smoke"}\n' > "$tmp_dir/prepared/uniform-smoke/inputs/profile.json"
printf '{"profile":"uniform-smoke"}\n' > "$tmp_dir/prepared/uniform-smoke/inputs/execution-plan.json"
printf '%s\n' 'event=spi_runtime_configuration payment_request_listener_concurrency=1' > "$tmp_dir/prepared/uniform-smoke/inputs/spi-runtime-config.log"
export RESULTS_DIR="$tmp_dir/results"
export PREPARED_ENVIRONMENT_ROOT="$tmp_dir/prepared"
export RUST_LOADTOOL_TARGET_DIR="$tmp_dir/target"
export DIAGNOSTICS_SCRIPT="$tmp_dir/diagnostics"
export LOADTOOL_COMMAND_LOG="$tmp_dir/loadtool.log"

cat > "$tmp_dir/bin/cargo" <<'SH'
#!/bin/bash
set -euo pipefail
mkdir -p "$RUST_LOADTOOL_TARGET_DIR/release"
cat > "$RUST_LOADTOOL_TARGET_DIR/release/rust-loadtool" <<'BIN'
#!/bin/bash
set -euo pipefail
printf '%s\n' "$*" >> "$LOADTOOL_COMMAND_LOG"
exit "${LOADTOOL_STATUS:-0}"
BIN
chmod +x "$RUST_LOADTOOL_TARGET_DIR/release/rust-loadtool"
SH
chmod +x "$tmp_dir/bin/cargo"

cat > "$tmp_dir/diagnostics" <<'SH'
#!/bin/bash
set -euo pipefail
while [[ "$1" != -- ]]; do shift; done
shift
"$@"
SH
chmod +x "$tmp_dir/diagnostics"
export PATH="$tmp_dir/bin:$PATH"

if "$RUNNER" --profile ../escape invalid-tag >"$tmp_dir/invalid.log" 2>&1; then
    echo "runner accepted an invalid profile name" >&2
    exit 1
fi
test ! -e "$RESULTS_DIR/invalid-tag"
grep -q 'Invalid profile name' "$tmp_dir/invalid.log"

if "$RUNNER" --profile missing missing-tag >"$tmp_dir/missing.log" 2>&1; then
    echo "runner accepted an absent prepared profile" >&2
    exit 1
fi
test ! -e "$RESULTS_DIR/missing-tag"
grep -q 'Prepared environment' "$tmp_dir/missing.log"

"$RUNNER" default-tag >/dev/null
result="$(find "$RESULTS_DIR/default-tag" -mindepth 1 -maxdepth 1 -type d -print -quit)"
cmp "$tmp_dir/prepared/uniform-smoke/inputs/profile.json" "$result/inputs/profile.json"
cmp "$tmp_dir/prepared/uniform-smoke/inputs/execution-plan.json" "$result/inputs/execution-plan.json"
cmp "$tmp_dir/prepared/uniform-smoke/inputs/spi-runtime-config.log" "$result/inputs/spi-runtime-config.log"

python3 - "$LOADTOOL_COMMAND_LOG" "$result" "$tmp_dir/prepared/uniform-smoke/certs" <<'PY'
import shlex
import sys

command = shlex.split(open(sys.argv[1], encoding="utf-8").read())
assert command == [
    "run", "--run-dir", sys.argv[2], "--client-cert-root", sys.argv[3]
], command
PY
