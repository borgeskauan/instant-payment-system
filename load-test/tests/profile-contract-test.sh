#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNNER="$ROOT_DIR/run-load-test.sh"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

prepared="$tmp_dir/prepared/current"
mkdir -p "$tmp_dir/profiles" "$prepared/inputs" "$prepared/certs" "$tmp_dir/bin"
printf '{"name":"uniform-smoke"}\n' > "$prepared/inputs/profile.json"
cp "$prepared/inputs/profile.json" "$tmp_dir/profiles/uniform-smoke.json"
printf '{"profile":"uniform-smoke"}\n' > "$prepared/inputs/execution-plan.json"
printf '%s\n' 'event=spi_runtime_configuration payment_request_listener_concurrency=1' > "$prepared/inputs/spi-runtime-config.log"
export RESULTS_DIR="$tmp_dir/results"
export LOADTOOL_PROFILES_DIR="$tmp_dir/profiles"
export PREPARED_ENVIRONMENT_ROOT="$tmp_dir/prepared"
export RUST_LOADTOOL_TARGET_DIR="$tmp_dir/target"
export FLOW_LOG="$tmp_dir/flow.log"
export DIAGNOSTICS_SCRIPT="$tmp_dir/diagnostics"

cat > "$tmp_dir/bin/cargo" <<'SH'
#!/bin/bash
set -euo pipefail
printf 'cargo %s\n' "$*" >> "$FLOW_LOG"
mkdir -p "$RUST_LOADTOOL_TARGET_DIR/release"
printf '#!/bin/bash\nexit 0\n' > "$RUST_LOADTOOL_TARGET_DIR/release/rust-loadtool"
chmod +x "$RUST_LOADTOOL_TARGET_DIR/release/rust-loadtool"
SH
chmod +x "$tmp_dir/bin/cargo"
cat > "$tmp_dir/diagnostics" <<'SH'
#!/bin/bash
set -euo pipefail
printf 'diagnostics %s\n' "$*" >> "$FLOW_LOG"
exit 0
SH
chmod +x "$tmp_dir/diagnostics"
export PATH="$tmp_dir/bin:$PATH"

"$RUNNER" --profile uniform-smoke first >/dev/null
"$RUNNER" --profile uniform-smoke second >/dev/null
test -d "$RESULTS_DIR/first"
test -d "$RESULTS_DIR/second"
if grep -Eq '^(docker|readiness|funding|cert) ' "$FLOW_LOG"; then
    echo "runner retained environment preparation responsibilities" >&2
    exit 1
fi

rm "$prepared/inputs/execution-plan.json"
if "$RUNNER" incomplete >"$tmp_dir/incomplete.log" 2>&1; then
    echo "runner accepted incomplete prepared inputs" >&2
    exit 1
fi
test ! -e "$RESULTS_DIR/incomplete"
