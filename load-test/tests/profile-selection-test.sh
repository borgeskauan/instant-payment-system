#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

source "${ROOT_DIR}/run-load-test.sh"

tmp_dir="$(mktemp -d)"
trap 'cleanup; rm -rf "$tmp_dir"' EXIT

printf '%s\n' '{"schemaVersion":1}' > "$tmp_dir/missing-name.json"
if shallow_validate_profile "$tmp_dir/missing-name.json" selected-profile >"$tmp_dir/missing-name.log" 2>&1; then
    echo "profile without embedded name should fail shallow validation" >&2
    exit 1
fi
if ! grep -q "name must be 'selected-profile'" "$tmp_dir/missing-name.log"; then
    echo "missing embedded name error was not clear" >&2
    exit 1
fi

printf '%s\n' '{"name":"other-profile","schemaVersion":1}' > "$tmp_dir/mismatched-name.json"
if shallow_validate_profile "$tmp_dir/mismatched-name.json" selected-profile >"$tmp_dir/mismatched-name.log" 2>&1; then
    echo "profile with mismatched embedded name should fail shallow validation" >&2
    exit 1
fi
if ! grep -q "name must be 'selected-profile'" "$tmp_dir/mismatched-name.log"; then
    echo "mismatched embedded name error was not clear" >&2
    exit 1
fi

RUN_TAG=""
PROFILE_NAME="uniform-smoke"
PROFILE_PATH=""
parse_args default-profile
resolve_profile
if [[ "$PROFILE_NAME" != "uniform-smoke" || "$PROFILE_PATH" != "${ROOT_DIR}/profiles/uniform-smoke.json" ]]; then
    echo "omitted --profile did not resolve uniform-smoke" >&2
    exit 1
fi

RUN_TAG=""
PROFILE_NAME="uniform-smoke"
PROFILE_PATH=""
parse_args --profile uniform-smoke explicit-profile
resolve_profile
if [[ "$PROFILE_NAME" != "uniform-smoke" || "$PROFILE_PATH" != "${ROOT_DIR}/profiles/uniform-smoke.json" ]]; then
    echo "explicit --profile uniform-smoke did not resolve correctly" >&2
    exit 1
fi

mkdir -p "$tmp_dir/invalid-run" "$tmp_dir/unknown-run"
if (cd "$tmp_dir/invalid-run" && "$ROOT_DIR/run-load-test.sh" --profile ../escape invalid-tag) >"$tmp_dir/invalid.log" 2>&1; then
    echo "invalid profile name should fail" >&2
    exit 1
fi
if [[ -e "$tmp_dir/invalid-run/results" ]]; then
    echo "invalid profile caused result-directory side effects" >&2
    exit 1
fi
if ! grep -q "Invalid profile name" "$tmp_dir/invalid.log"; then
    echo "invalid profile error was not clear" >&2
    exit 1
fi

if (cd "$tmp_dir/unknown-run" && "$ROOT_DIR/run-load-test.sh" --profile unknown-profile unknown-tag) >"$tmp_dir/unknown.log" 2>&1; then
    echo "unknown profile should fail" >&2
    exit 1
fi
if [[ -e "$tmp_dir/unknown-run/results" ]]; then
    echo "unknown profile caused result-directory side effects" >&2
    exit 1
fi
if ! grep -q "Profile 'unknown-profile' not found" "$tmp_dir/unknown.log"; then
    echo "unknown profile error was not clear" >&2
    exit 1
fi

snapshot_dir="$tmp_dir/snapshot"
mkdir -p "$snapshot_dir"
copy_profile_snapshot "$snapshot_dir"
if ! cmp -s "$PROFILE_PATH" "$snapshot_dir/inputs/profile.json"; then
    echo "profile snapshot is not byte-identical to the selected profile" >&2
    exit 1
fi

cat > "$tmp_dir/fake-go-loadtool" <<'SH'
#!/bin/bash
set -euo pipefail
printf '%s\n' "$*" >> "$LOADTOOL_COMMAND_LOG"
echo "loadtool stdout"
echo "loadtool stderr" >&2
exit "${LOADTOOL_FAKE_EXIT_CODE:-0}"
SH
chmod +x "$tmp_dir/fake-go-loadtool"

export LOADTOOL_COMMAND_LOG="$tmp_dir/go-loadtool-commands.log"
LOADTOOL_BIN="$tmp_dir/fake-go-loadtool"
PROFILE_NAME="mixed-outcomes-smoke"
LOADTOOL_CENTRAL_TRANSFER_CA_CERT="central-ca.crt"
LOADTOOL_CERT_ROOT="$tmp_dir/client-certs"
LOADTOOL_GATEWAY_CA_CERT="gateway-ca.crt"
mkdir -p "$tmp_dir/result/logs"

run_loadtool "$tmp_dir/result"

if [[ "$(cat "$tmp_dir/result/logs/loadtool.log")" != $'loadtool stdout\nloadtool stderr' ]]; then
    echo "loadtool.log did not capture both stdout and stderr" >&2
    exit 1
fi

python3 - "$LOADTOOL_COMMAND_LOG" "$tmp_dir/result" <<'PY'
import shlex
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    commands = [shlex.split(line) for line in handle if line.strip()]

assert len(commands) == 1, commands
command = commands[0]
assert command[0] == "run", commands
run_dir_index = command.index("--run-dir")
assert command[run_dir_index + 1] == sys.argv[2], command
for removed in (
    "--profile",
    "--config",
    "--out",
    "--run-window",
    "--starts",
    "--events",
    "--status-starts",
    "--replays",
    "--seed",
):
    assert removed not in command, command
for required in (
    "--central-transfer-ca-cert",
    "--central-transfer-client-cert-root",
    "--central-transfer-server-name",
    "--gateway-ca-cert",
    "--gateway-client-cert-root",
    "--gateway-server-name",
):
    assert required in command, command
PY

export LOADTOOL_FAKE_EXIT_CODE=23
if run_loadtool "$tmp_dir/result"; then
    echo "run_loadtool should preserve the Go command failure" >&2
    exit 1
else
    loadtool_status=$?
fi
if [[ "$loadtool_status" -ne 23 ]]; then
    echo "run_loadtool returned $loadtool_status, want Go exit code 23" >&2
    exit 1
fi
