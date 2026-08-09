#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

source "${ROOT_DIR}/run-load-test.sh"

tmp_dir="$(mktemp -d)"
trap 'cleanup; rm -rf "$tmp_dir"' EXIT

RUN_TAG=""
PROFILE_NAME="uniform-smoke"
PROFILE_PATH=""
parse_args default-profile
resolve_profile
if [[ "$PROFILE_NAME" != "uniform-smoke" || "$PROFILE_PATH" != "${ROOT_DIR}/go-loadtool/profiles/uniform-smoke.json" ]]; then
    echo "omitted --profile did not resolve uniform-smoke" >&2
    exit 1
fi

RUN_TAG=""
PROFILE_NAME="uniform-smoke"
PROFILE_PATH=""
parse_args --profile uniform-smoke explicit-profile
resolve_profile
if [[ "$PROFILE_NAME" != "uniform-smoke" || "$PROFILE_PATH" != "${ROOT_DIR}/go-loadtool/profiles/uniform-smoke.json" ]]; then
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
if ! cmp -s "$PROFILE_PATH" "$snapshot_dir/profile.json"; then
    echo "profile snapshot is not byte-identical to the selected profile" >&2
    exit 1
fi

cat > "$tmp_dir/fake-go-loadtool" <<'SH'
#!/bin/bash
set -euo pipefail
printf '%s\n' "$*" >> "$LOADTOOL_COMMAND_LOG"
SH
chmod +x "$tmp_dir/fake-go-loadtool"

export LOADTOOL_COMMAND_LOG="$tmp_dir/go-loadtool-commands.log"
LOADTOOL_BIN="$tmp_dir/fake-go-loadtool"
PROFILE_NAME="mixed-outcomes-smoke"
LOADTOOL_CENTRAL_TRANSFER_CA_CERT="central-ca.crt"
LOADTOOL_CERT_ROOT="$tmp_dir/client-certs"
LOADTOOL_GATEWAY_CA_CERT="gateway-ca.crt"
mkdir -p "$tmp_dir/result"

run_simulator "$tmp_dir/result" "$tmp_dir/tool-output"
generate_sla_report "$tmp_dir/result" "$tmp_dir/tool-output"

python3 - "$LOADTOOL_COMMAND_LOG" <<'PY'
import shlex
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    commands = [shlex.split(line) for line in handle if line.strip()]

assert len(commands) == 2, commands
assert commands[0][0] == "simulate", commands
assert commands[1][0] == "report", commands
for command in commands:
    assert "--seed" not in command, command
    profile_index = command.index("--profile")
    assert command[profile_index + 1] == "mixed-outcomes-smoke", command
PY
