#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

source "${ROOT_DIR}/run-load-test.sh"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
export EXPECTED_RESULT_DIR="$tmp_dir"

RUN_TAG="baseline-2000"
write_run_window_json \
    "$tmp_dir" \
    "2026-06-20T20:05:46-03:00" \
    "2026-06-20T20:06:16-03:00" \
    "2026-06-20T20:21:16-03:00" \
    "2026-06-20T20:21:31-03:00" \
    "true"

python3 - "$tmp_dir/run-window.json" <<'PY'
import json
import os
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)

assert data["tag"] == "baseline-2000"
assert data["result_dir"] == os.environ["EXPECTED_RESULT_DIR"]
assert data["window"]["run_started_at"] == "2026-06-20T20:05:46-03:00"
assert data["window"]["active_started_at"] == "2026-06-20T20:06:16-03:00"
assert data["window"]["active_finished_at"] == "2026-06-20T20:21:16-03:00"
assert data["window"]["drain_finished_at"] == "2026-06-20T20:21:31-03:00"
assert data["grafana"]["available_at_run_start"] is True
assert data["grafana"]["base_url"] == "http://localhost:3000"

full_url = data["grafana"]["full_run_url"]
active_url = data["grafana"]["active_window_url"]
assert full_url.startswith("http://localhost:3000/d/load-test/load-test?")
assert "from=2026-06-20T20%3A05%3A46-03%3A00" in full_url
assert "to=2026-06-20T20%3A21%3A31-03%3A00" in full_url
assert "from=2026-06-20T20%3A06%3A16-03%3A00" in active_url
assert "to=2026-06-20T20%3A21%3A16-03%3A00" in active_url
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

if (RUN_TAG=""; parse_args --process-stats baseline) >/dev/null 2>&1; then
    echo "--process-stats should not be accepted after Prometheus/Grafana cleanup" >&2
    exit 1
fi

RUN_TAG=""
RESET_TEST_STATE=true
parse_args --no-reset-state baseline
if [[ "$RUN_TAG" != "baseline" || "$RESET_TEST_STATE" != false ]]; then
    echo "--no-reset-state was not parsed correctly" >&2
    exit 1
fi

RUN_TAG=""
RESET_TEST_STATE=false
parse_args --reset-state baseline
if [[ "$RUN_TAG" != "baseline" || "$RESET_TEST_STATE" != true ]]; then
    echo "--reset-state was not parsed correctly" >&2
    exit 1
fi
