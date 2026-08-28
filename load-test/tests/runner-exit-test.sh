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

cat > "$tmp_dir/bin/cargo" <<'SH'
#!/bin/bash
set -euo pipefail
mkdir -p "$RUST_LOADTOOL_TARGET_DIR/release"
printf '#!/bin/bash\nexit "${LOADTOOL_STATUS:-0}"\n' > "$RUST_LOADTOOL_TARGET_DIR/release/rust-loadtool"
chmod +x "$RUST_LOADTOOL_TARGET_DIR/release/rust-loadtool"
SH
chmod +x "$tmp_dir/bin/cargo"
cat > "$tmp_dir/diagnostics" <<'SH'
#!/bin/bash
set -euo pipefail
exit "${WRAPPER_STATUS:-0}"
SH
chmod +x "$tmp_dir/diagnostics"
export PATH="$tmp_dir/bin:$PATH"

for status in 0 1 2 23; do
    set +e
    WRAPPER_STATUS="$status" "$RUNNER" "status-$status" >/dev/null 2>&1
    actual=$?
    set -e
    if [[ "$actual" -ne "$status" ]]; then
        echo "runner returned $actual, want wrapper status $status" >&2
        exit 1
    fi
done
