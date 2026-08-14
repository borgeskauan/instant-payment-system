#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

source "${ROOT_DIR}/run-load-test.sh"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
export EXPECTED_RESULT_DIR="$tmp_dir"

runner_now="$(iso_now)"
python3 - "$runner_now" <<'PY'
from datetime import datetime
import sys
value = sys.argv[1]
assert "." in value, value
datetime.fromisoformat(value)
PY

RUN_TAG="baseline-2000"
cat > "$tmp_dir/run-window.json" <<'JSON'
{
  "schema_version": 2,
  "profile": {"name": "uniform-smoke"},
  "window": {
    "generation_started_at": "2026-06-20T20:06:15.123456789-03:00",
    "active_started_at": "2026-06-20T20:06:16.123456789-03:00",
    "generation_ended_at": "2026-06-20T20:21:16.123456789-03:00",
    "replay_deadline_at": "2026-06-20T20:21:46.123456789-03:00"
  }
}
JSON
write_run_window_json \
    "$tmp_dir" \
    "2026-06-20T20:05:46-03:00" \
    "2026-06-20T20:21:46.223456789-03:00" \
    "true"

python3 - "$tmp_dir/run-window.json" <<'PY'
import json
import os
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)

assert data["tag"] == "baseline-2000"
assert data["result_dir"] == os.environ["EXPECTED_RESULT_DIR"]
assert data["profile"]["name"] == "uniform-smoke"
assert data["profile"]["snapshot"] == "inputs/profile.json"
assert data["profile"]["execution_plan"] == "inputs/execution-plan.json"
assert data["artifacts"] == {
    "pacs008_starts": "events/pacs008-starts.csv",
    "pacs002_starts": "events/pacs002-starts.csv",
    "notifications": "events/notifications.csv",
    "replays": "events/replays.csv",
    "report": "sla-report.json",
}
assert data["window"]["run_started_at"] == "2026-06-20T20:05:46-03:00"
assert data["window"]["generation_started_at"] == "2026-06-20T20:06:15.123456789-03:00"
assert data["window"]["active_started_at"] == "2026-06-20T20:06:16.123456789-03:00"
assert data["window"]["generation_ended_at"] == "2026-06-20T20:21:16.123456789-03:00"
assert data["window"]["replay_deadline_at"] == "2026-06-20T20:21:46.123456789-03:00"
assert data["window"]["loadtool_finished_at"] == "2026-06-20T20:21:46.223456789-03:00"
assert data["grafana"]["available_at_run_start"] is True
assert data["grafana"]["base_url"] == "http://localhost:3000"

full_url = data["grafana"]["full_run_url"]
active_url = data["grafana"]["active_window_url"]
assert full_url.startswith("http://localhost:3000/d/load-test/load-test?")
assert "from=2026-06-20T20%3A05%3A46-03%3A00" in full_url
assert "to=2026-06-20T20%3A21%3A46.223456789-03%3A00" in full_url
assert "from=2026-06-20T20%3A06%3A16.123456789-03%3A00" in active_url
assert "to=2026-06-20T20%3A21%3A16.123456789-03%3A00" in active_url
PY

offline_log="$(log_grafana_status false)"
if [[ "$offline_log" != *"Grafana available at run start: false"* ]]; then
    echo "offline Grafana log missing availability status" >&2
    exit 1
fi
if [[ "$offline_log" != *"cd ../infra && docker compose --profile observability up -d"* ]]; then
    echo "offline Grafana log missing profile startup hint" >&2
    exit 1
fi
if [[ "$offline_log" != *"http://localhost:3000"* ]]; then
    echo "offline Grafana log missing Grafana URL" >&2
    exit 1
fi

RUN_TAG=""
PROFILE_NAME="uniform-smoke"
parse_args --profile uniform-smoke explicit-profile
if [[ "$RUN_TAG" != "explicit-profile" || "$PROFILE_NAME" != "uniform-smoke" ]]; then
    echo "--profile was not parsed correctly" >&2
    exit 1
fi
