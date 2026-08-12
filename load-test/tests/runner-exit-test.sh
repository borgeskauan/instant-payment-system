#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

source "${ROOT_DIR}/run-load-test.sh"

write_report() {
    local path="$1"
    local content="$2"
    printf '%s\n' "$content" > "$path"
}

write_report "$tmp_dir/valid.json" '{"valid":true}'
validate_sla_report "$tmp_dir/valid.json"

for fixture in \
    '{"valid":false}' \
    '{"valid":"true"}' \
    '{"valid":1}' \
    '{}' \
    '[]' \
    '{'; do
    write_report "$tmp_dir/invalid.json" "$fixture"
    if validate_sla_report "$tmp_dir/invalid.json" >/dev/null 2>&1; then
        echo "invalid SLA report was accepted: $fixture" >&2
        exit 1
    fi
done

export RUNNER_EXIT_TEST_ROOT_DIR="$ROOT_DIR"
export RUNNER_EXIT_TEST_TMP_DIR="$tmp_dir"

cat > "$tmp_dir/driver.sh" <<'SH'
#!/bin/bash
set -euo pipefail

source "${RUNNER_EXIT_TEST_ROOT_DIR}/run-load-test.sh"

parse_args() { RUN_TAG="runner-exit-${RUNNER_TEST_MODE}"; }
resolve_profile() { :; }
prepare_loadtool_binary() { LOADTOOL_BUILD_DIR=""; LOADTOOL_BIN="fake"; }
build_loadtool() { :; }
validate_profile_with_loadtool() { :; }
prepare_run_workspace() { mkdir -p "$1"; }
prepare_loadtool_certificates() { :; }
grafana_available() { return 1; }
log_selected_options() { :; }
log_grafana_status() { :; }
run_preflight_checks() { :; }
prepare_environment() {
    printf '%s\n' prepare-environment >> "$RUNNER_FLOW_LOG"
    if [[ "$RUNNER_TEST_MODE" == preparation-failure ]]; then
        return 17
    fi
}
start_optional_diagnostics() { :; }
print_grafana_links() { :; }
iso_now() { printf '%s\n' '2026-08-11T12:00:00.000000000-03:00'; }

run_loadtool() {
    local target_dir="$1"
    printf '%s\n' run >> "$RUNNER_FLOW_LOG"
    case "$RUNNER_TEST_MODE" in
        valid)
            printf '%s\n' '{"valid":true}' > "${target_dir}/sla-report.json"
            ;;
        violation)
            printf '%s\n' '{"valid":false}' > "${target_dir}/sla-report.json"
            ;;
        go-failure)
            return 23
            ;;
        *)
            return 99
            ;;
    esac
}

collect_optional_diagnostics() {
    printf '%s\n' diagnostics >> "$RUNNER_FLOW_LOG"
    if [[ "$RUNNER_TEST_MODE" == go-failure ]]; then
        return 19
    fi
}
write_run_window_json() { printf '%s\n' enriched >> "$RUNNER_FLOW_LOG"; }

cd "$RUNNER_EXIT_TEST_TMP_DIR"
main test
SH
chmod +x "$tmp_dir/driver.sh"

run_driver() {
    local mode="$1"
    local flow_log="$tmp_dir/${mode}.flow"
    RUNNER_TEST_MODE="$mode" RUNNER_FLOW_LOG="$flow_log" "$tmp_dir/driver.sh"
}

run_driver valid
cat > "$tmp_dir/valid.expected" <<'EOF'
prepare-environment
run
diagnostics
enriched
EOF
diff -u "$tmp_dir/valid.expected" "$tmp_dir/valid.flow"

if run_driver violation >"$tmp_dir/violation.log" 2>&1; then
    echo "runner accepted an SLA report with violations" >&2
    exit 1
fi
if ! grep -q '^enriched$' "$tmp_dir/violation.flow"; then
    echo "runner did not enrich a technically completed run with violations" >&2
    exit 1
fi

set +e
run_driver go-failure >"$tmp_dir/go-failure.log" 2>&1
go_failure_status=$?
set -e
if [[ "$go_failure_status" -ne 23 ]]; then
    echo "runner returned $go_failure_status, want original Go exit code 23" >&2
    exit 1
fi
cat > "$tmp_dir/go-failure.expected" <<'EOF'
prepare-environment
run
diagnostics
EOF
diff -u "$tmp_dir/go-failure.expected" "$tmp_dir/go-failure.flow"

set +e
run_driver preparation-failure >"$tmp_dir/preparation-failure.log" 2>&1
preparation_failure_status=$?
set -e
if [[ "$preparation_failure_status" -ne 17 ]]; then
    echo "runner returned $preparation_failure_status, want environment-preparation exit code 17" >&2
    exit 1
fi
cat > "$tmp_dir/preparation-failure.expected" <<'EOF'
prepare-environment
EOF
diff -u "$tmp_dir/preparation-failure.expected" "$tmp_dir/preparation-failure.flow"
