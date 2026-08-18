#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
QUALIFIER="${ROOT_DIR}/scripts/qualify-smoke-report.py"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

mkdir -p "$tmp_dir/base"
cat > "$tmp_dir/base/sla-report.json" <<'JSON'
{
  "valid": false,
  "generation": {
    "target_tps": 100,
    "started": 1000,
    "rolling_window_seconds": 1,
    "average_tps": 100.0,
    "minimum_observed_tps": 0,
    "maximum_observed_tps": 200,
    "sustained_minimum_met": false,
    "outside_window": 0
  },
  "scenarios": [
    {
      "name": "happy-path",
      "share": 0.8,
      "traffic": {
        "payments": {"started": 1000, "accepted": 1000},
        "pacs002": {"started": 1000, "accepted": 1000}
      },
      "outcome": {
        "expected": {"status": "ACSC", "reason_codes": []},
        "matched": 1000,
        "missing": 0,
        "contradictory": 0
      },
      "violations": 0
    },
    {
      "name": "insufficient-funds",
      "share": 0.2,
      "traffic": {
        "payments": {"started": 250, "accepted": 250},
        "pacs002": {"started": 0, "accepted": 0}
      },
      "outcome": {
        "expected": {"status": "RJCT", "reason_codes": ["AM04"]},
        "matched": 250,
        "missing": 0,
        "contradictory": 0
      },
      "violations": 0
    }
  ],
  "replays": {
    "pacs008": {"started": 63, "accepted": 63, "violations": 0},
    "pacs002": {"started": 63, "accepted": 63, "violations": 0}
  },
  "performance": {
    "threshold_ms": 1000,
    "within_sla": false
  }
}
JSON

mkdir -p "$tmp_dir/base/events"
{
    echo 'end_to_end_id,payer_ispb,receiver_ispb,created_at_ns,request_started_at_ns,request_done_at_ns,http_status,scenario_name,pacs008_replay_selected,connection_acquired_at_ns,request_written_at_ns,connection_reused'
    for ((index = 1; index <= 1000; index++)); do
        echo "happy-${index},10000001,20000001,1,1,2,200,happy-path,false,1,1,true"
    done
    for ((index = 1; index <= 250; index++)); do
        echo "insufficient-${index},10000041,20000041,1,1,2,200,insufficient-funds,false,1,1,true"
    done
} > "$tmp_dir/base/events/pacs008-starts.csv"
echo 'end_to_end_id,ispb,event_type,received_at_ns,status_code,reason_codes' \
    > "$tmp_dir/base/events/notifications.csv"

assert_status() {
    local expected="$1"
    local run_dir="$2"
    local status

    set +e
    "$QUALIFIER" "$run_dir" >"$tmp_dir/qualifier.out" 2>"$tmp_dir/qualifier.err"
    status=$?
    set -e
    if [[ "$status" -ne "$expected" ]]; then
        echo "qualifier returned ${status}, want ${expected} for ${run_dir}" >&2
        cat "$tmp_dir/qualifier.out" >&2
        cat "$tmp_dir/qualifier.err" >&2
        exit 1
    fi
}

mutated_case() {
    local name="$1"
    local filter="$2"
    local run_dir="$tmp_dir/$name"
    mkdir -p "$run_dir"
    cp -R "$tmp_dir/base/events" "$run_dir/events"
    jq "$filter" "$tmp_dir/base/sla-report.json" > "$run_dir/sla-report.json"
    printf '%s\n' "$run_dir"
}

assert_status 0 "$tmp_dir/base"

partial_dir="$(mutated_case partial '
  .scenarios[1].traffic.payments.started = 249 |
  .scenarios[1].traffic.payments.accepted = 249 |
  .scenarios[1].outcome.matched = 249')"
sed '$d' "$partial_dir/events/pacs008-starts.csv" > "$partial_dir/events/pacs008-starts.csv.tmp"
mv "$partial_dir/events/pacs008-starts.csv.tmp" "$partial_dir/events/pacs008-starts.csv"
assert_status 10 "$partial_dir"

over_dir="$(mutated_case over-planned '
  .scenarios[0].traffic.payments.started = 1001 |
  .scenarios[0].traffic.payments.accepted = 1001 |
  .scenarios[0].traffic.pacs002.started = 1001 |
  .scenarios[0].traffic.pacs002.accepted = 1001 |
  .scenarios[0].outcome.matched = 1001')"
echo 'happy-1001,10000001,20000001,1,1,2,200,happy-path,false,1,1,true' \
    >> "$over_dir/events/pacs008-starts.csv"
assert_status 20 "$over_dir"

assert_status 20 "$(mutated_case http-mismatch '.scenarios[0].traffic.payments.accepted = 999')"
assert_status 20 "$(mutated_case pacs002-start-mismatch '.scenarios[0].traffic.pacs002.started = 999')"
assert_status 20 "$(mutated_case pacs002-accept-mismatch '.scenarios[0].traffic.pacs002.accepted = 999')"
assert_status 20 "$(mutated_case unexpected-insufficient-pacs002 '.scenarios[1].traffic.pacs002.started = 1 | .scenarios[1].traffic.pacs002.accepted = 1')"
assert_status 20 "$(mutated_case missing-outcome '.scenarios[0].outcome.matched = 999 | .scenarios[0].outcome.missing = 1')"
assert_status 20 "$(mutated_case contradictory-outcome '.scenarios[0].outcome.contradictory = 1')"
assert_status 20 "$(mutated_case wrong-happy-status '.scenarios[0].outcome.expected.status = "RJCT"')"
assert_status 20 "$(mutated_case wrong-rejection-reason '.scenarios[1].outcome.expected.reason_codes = ["XX00"]')"
assert_status 20 "$(mutated_case scenario-violation '.scenarios[1].violations = 1')"
assert_status 20 "$(mutated_case replay-mismatch '.replays.pacs008.accepted = 62')"
assert_status 20 "$(mutated_case replay-violation '.replays.pacs002.violations = 1')"
assert_status 20 "$(mutated_case missing-field 'del(.scenarios[0].traffic.payments.accepted)')"
assert_status 20 "$(mutated_case missing-valid 'del(.valid)')"

timeout_dir="$(mutated_case retryable-timeouts '
  .scenarios[0].traffic.payments.started = 660 |
  .scenarios[0].traffic.payments.accepted = 659 |
  .scenarios[0].traffic.pacs002.started = 660 |
  .scenarios[0].traffic.pacs002.accepted = 660 |
  .scenarios[0].outcome.matched = 659 |
  .scenarios[0].violations = 1 |
  .scenarios[1].traffic.payments.started = 167 |
  .scenarios[1].traffic.payments.accepted = 166 |
  .scenarios[1].outcome.matched = 166 |
  .scenarios[1].violations = 1')"
{
    head -1 "$tmp_dir/base/events/pacs008-starts.csv"
    for ((index = 1; index <= 659; index++)); do
        echo "cold-happy-${index},10000039,20000039,1,1,2,200,happy-path,false,1,1,true"
    done
    echo 'timeout-happy,10000039,20000039,1,1,2,0,happy-path,false,1,1,true'
    for ((index = 1; index <= 166; index++)); do
        echo "cold-insufficient-${index},10000045,20000045,1,1,2,200,insufficient-funds,false,1,1,true"
    done
    echo 'timeout-insufficient,10000045,20000045,1,1,2,0,insufficient-funds,false,1,1,true'
} > "$timeout_dir/events/pacs008-starts.csv"
cat > "$timeout_dir/events/notifications.csv" <<'CSV'
end_to_end_id,ispb,event_type,received_at_ns,status_code,reason_codes
timeout-happy,10000039,pacs002_received,3,ACSC,[]
timeout-insufficient,10000045,pacs002_received,3,RJCT,"[""AM04""]"
CSV
assert_status 10 "$timeout_dir"

duplicate_timeout_outcome_dir="$tmp_dir/duplicate-timeout-outcome"
cp -R "$timeout_dir" "$duplicate_timeout_outcome_dir"
echo 'timeout-happy,10000039,pacs002_received,4,ACSC,[]' \
    >> "$duplicate_timeout_outcome_dir/events/notifications.csv"
assert_status 10 "$duplicate_timeout_outcome_dir"

explicit_4xx_dir="$tmp_dir/explicit-4xx"
cp -R "$timeout_dir" "$explicit_4xx_dir"
sed 's/timeout-happy,10000039,20000039,1,1,2,0,/timeout-happy,10000039,20000039,1,1,2,400,/' \
    "$explicit_4xx_dir/events/pacs008-starts.csv" > "$explicit_4xx_dir/events/pacs008-starts.csv.tmp"
mv "$explicit_4xx_dir/events/pacs008-starts.csv.tmp" "$explicit_4xx_dir/events/pacs008-starts.csv"
assert_status 20 "$explicit_4xx_dir"

missing_timeout_outcome_dir="$tmp_dir/missing-timeout-outcome"
cp -R "$timeout_dir" "$missing_timeout_outcome_dir"
sed '/^timeout-happy,/d' "$missing_timeout_outcome_dir/events/notifications.csv" \
    > "$missing_timeout_outcome_dir/events/notifications.csv.tmp"
mv "$missing_timeout_outcome_dir/events/notifications.csv.tmp" \
    "$missing_timeout_outcome_dir/events/notifications.csv"
assert_status 20 "$missing_timeout_outcome_dir"

wrong_timeout_outcome_dir="$tmp_dir/wrong-timeout-outcome"
cp -R "$timeout_dir" "$wrong_timeout_outcome_dir"
sed 's/timeout-happy,10000039,pacs002_received,3,ACSC,\[\]/timeout-happy,10000039,pacs002_received,3,RJCT,[]/' \
    "$wrong_timeout_outcome_dir/events/notifications.csv" > "$wrong_timeout_outcome_dir/events/notifications.csv.tmp"
mv "$wrong_timeout_outcome_dir/events/notifications.csv.tmp" \
    "$wrong_timeout_outcome_dir/events/notifications.csv"
assert_status 20 "$wrong_timeout_outcome_dir"

malformed_notification_dir="$tmp_dir/malformed-notification"
cp -R "$timeout_dir" "$malformed_notification_dir"
printf '%s\n' \
    'end_to_end_id,ispb,event_type,received_at_ns,status_code,reason_codes' \
    'timeout-happy,10000039,pacs002_received' \
    > "$malformed_notification_dir/events/notifications.csv"
assert_status 20 "$malformed_notification_dir"

missing_artifact_dir="$(mutated_case missing-artifact '.')"
rm "$missing_artifact_dir/events/pacs008-starts.csv"
assert_status 20 "$missing_artifact_dir"

malformed_csv_dir="$(mutated_case malformed-csv '.')"
printf '%s\n' 'wrong,header' > "$malformed_csv_dir/events/pacs008-starts.csv"
assert_status 20 "$malformed_csv_dir"

mkdir -p "$tmp_dir/missing-report" "$tmp_dir/malformed-report"
printf '%s\n' '{' > "$tmp_dir/malformed-report/sla-report.json"
assert_status 20 "$tmp_dir/missing-report"
assert_status 20 "$tmp_dir/malformed-report"
