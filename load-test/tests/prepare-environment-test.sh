#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROVISION="${ROOT_DIR}/scripts/provision-profile-funds.sh"
EXTRACT="${ROOT_DIR}/scripts/execution-plan-participants.py"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

mkdir -p "$tmp_dir/bin"
export FUNDING_CALLS="$tmp_dir/funding-calls.log"
cat > "$tmp_dir/bin/curl" <<'SH'
#!/bin/bash
set -euo pipefail
method="" url="" body=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        -s) shift ;;
        -o|-w|-H) shift 2 ;;
        -X) method="$2"; shift 2 ;;
        -d) body="$2"; shift 2 ;;
        *) url="$1"; shift ;;
    esac
done
printf '%s %s %s\n' "$method" "$url" "$body" >> "$FUNDING_CALLS"
printf '%s' "${CURL_HTTP_STATUS:-204}"
exit "${CURL_STATUS:-0}"
SH
chmod +x "$tmp_dir/bin/curl"
export PATH="$tmp_dir/bin:$PATH"

cat > "$tmp_dir/execution-plan.json" <<'JSON'
{
  "scenarios": [
    {
      "participants": {"pairNumberStart": 1, "hotPairCount": 2, "coldPairCount": 1},
      "provisioning": {"payerBalance": "123.45", "receiverBalance": "0.00", "resetIfExists": true}
    },
    {
      "participants": {"pairNumberStart": 4, "hotPairCount": 1, "coldPairCount": 1},
      "provisioning": {"payerBalance": "0.00", "receiverBalance": "0.00", "resetIfExists": false}
    }
  ]
}
JSON

printf '1\t2\t1\t123.45\t0.00\ttrue\n4\t1\t1\t0.00\t0.00\tfalse\n' > "$tmp_dir/expected-rows"
"$EXTRACT" "$tmp_dir/execution-plan.json" > "$tmp_dir/actual-rows"
diff -u "$tmp_dir/expected-rows" "$tmp_dir/actual-rows"

"$PROVISION" --execution-plan "$tmp_dir/execution-plan.json" >/dev/null
cat > "$tmp_dir/expected-funding" <<'EOF'
PUT http://localhost:8002/internal/funds/10000001 {"balance":123.45,"resetIfExists":true}
PUT http://localhost:8002/internal/funds/10000002 {"balance":123.45,"resetIfExists":true}
PUT http://localhost:8002/internal/funds/10000003 {"balance":123.45,"resetIfExists":true}
PUT http://localhost:8002/internal/funds/20000001 {"balance":0.00,"resetIfExists":true}
PUT http://localhost:8002/internal/funds/20000002 {"balance":0.00,"resetIfExists":true}
PUT http://localhost:8002/internal/funds/20000003 {"balance":0.00,"resetIfExists":true}
PUT http://localhost:8002/internal/funds/10000004 {"balance":0.00,"resetIfExists":false}
PUT http://localhost:8002/internal/funds/10000005 {"balance":0.00,"resetIfExists":false}
PUT http://localhost:8002/internal/funds/20000004 {"balance":0.00,"resetIfExists":false}
PUT http://localhost:8002/internal/funds/20000005 {"balance":0.00,"resetIfExists":false}
EOF
diff -u "$tmp_dir/expected-funding" "$FUNDING_CALLS"

printf '{' > "$tmp_dir/malformed.json"
if "$PROVISION" --execution-plan "$tmp_dir/malformed.json" >/dev/null 2>&1; then
    echo "fund provisioning accepted malformed JSON" >&2
    exit 1
fi

set +e
CURL_STATUS=37 "$PROVISION" --execution-plan "$tmp_dir/execution-plan.json" >/dev/null 2>&1
status=$?
set -e
if [[ "$status" -ne 37 ]]; then
    echo "fund provisioning returned $status, want 37" >&2
    exit 1
fi
