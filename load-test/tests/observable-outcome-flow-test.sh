#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNNER="$ROOT_DIR/run-load-test.sh"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

mkdir -p "$tmp_dir/prepared/uniform-smoke/inputs" "$tmp_dir/prepared/uniform-smoke/certs" "$tmp_dir/bin"
printf '{"name":"uniform-smoke"}\n' > "$tmp_dir/prepared/uniform-smoke/inputs/profile.json"
printf '{"profile":"uniform-smoke"}\n' > "$tmp_dir/prepared/uniform-smoke/inputs/execution-plan.json"
printf '%s\n' 'event=spi_runtime_configuration payment_request_listener_concurrency=1' > "$tmp_dir/prepared/uniform-smoke/inputs/spi-runtime-config.log"
export RESULTS_DIR="$tmp_dir/results"
export PREPARED_ENVIRONMENT_ROOT="$tmp_dir/prepared"
export RUST_LOADTOOL_TARGET_DIR="$tmp_dir/target"
export DIAGNOSTICS_SCRIPT="$tmp_dir/diagnostics"
export FLOW_LOG="$tmp_dir/flow.log"

cat > "$tmp_dir/bin/cargo" <<'SH'
#!/bin/bash
set -euo pipefail
printf '%s\n' build >> "$FLOW_LOG"
mkdir -p "$RUST_LOADTOOL_TARGET_DIR/release"
cat > "$RUST_LOADTOOL_TARGET_DIR/release/rust-loadtool" <<'BIN'
#!/bin/bash
set -euo pipefail
printf 'run %s\n' "$*" >> "$FLOW_LOG"
BIN
chmod +x "$RUST_LOADTOOL_TARGET_DIR/release/rust-loadtool"
SH
chmod +x "$tmp_dir/bin/cargo"
cat > "$tmp_dir/diagnostics" <<'SH'
#!/bin/bash
set -euo pipefail
printf 'diagnostics %s\n' "$*" >> "$FLOW_LOG"
while [[ "$1" != -- ]]; do shift; done
shift
"$@"
SH
chmod +x "$tmp_dir/diagnostics"
export PATH="$tmp_dir/bin:$PATH"

"$RUNNER" --no-jfr --no-postgres-statements observable >/dev/null
cat > "$tmp_dir/expected" <<'EOF'
build
diagnostics run --run-dir RESULT --no-jfr --no-postgres-statements -- BINARY run --run-dir RESULT --client-cert-root CERTS
run run --run-dir RESULT --client-cert-root CERTS
EOF
sed -E \
    -e "s#--run-dir [^ ]+#--run-dir RESULT#g" \
    -e "s#--client-cert-root [^ ]+#--client-cert-root CERTS#g" \
    -e "s#-- /[^ ]+/rust-loadtool#-- BINARY#" \
    "$FLOW_LOG" > "$tmp_dir/normalized"
diff -u "$tmp_dir/expected" "$tmp_dir/normalized"
