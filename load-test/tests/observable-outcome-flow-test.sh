#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

source "${ROOT_DIR}/run-load-test.sh"

export FLOW_LOG="$tmp_dir/flow.log"

parse_args() { RUN_TAG="observable-flow"; }
resolve_profile() { :; }
prepare_loadtool_binary() { LOADTOOL_BUILD_DIR=""; LOADTOOL_BIN="fake"; }
build_loadtool() { :; }
validate_profile_with_loadtool() {
    PROFILE_WARMUP_SECONDS=0
    PROFILE_ACTIVE_SECONDS=1
    PROFILE_DRAIN_SECONDS=0
}
prepare_run_workspace() { :; }
prepare_loadtool_certificates() { :; }
log_selected_options() { :; }
run_preflight_checks() { :; }
prepare_environment() { echo prepare-environment >> "$FLOW_LOG"; }
start_optional_diagnostics() { :; }
run_loadtool() { echo run >> "$FLOW_LOG"; }
capture_and_assert_outbox_drained() {
    echo outbox-validation >> "$FLOW_LOG"
    return 1
}
collect_optional_diagnostics() { echo diagnostics >> "$FLOW_LOG"; }
write_run_window_json() { echo run-window >> "$FLOW_LOG"; }
validate_sla_report() { echo validate-report >> "$FLOW_LOG"; }

main observable-flow

if grep -q outbox-validation "$FLOW_LOG"; then
    echo "runner still performs persisted outbox validation" >&2
    exit 1
fi

cat > "$tmp_dir/expected-flow.log" <<'EOF'
prepare-environment
run
diagnostics
run-window
validate-report
EOF

if ! diff -u "$tmp_dir/expected-flow.log" "$FLOW_LOG"; then
    echo "runner did not preserve the single-run observable flow" >&2
    exit 1
fi
