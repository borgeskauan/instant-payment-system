#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

source "${ROOT_DIR}/run-load-test.sh"

docker() {
    printf '%s\n' "$*" > "$tmp_dir/docker-command.log"
    cat > "$tmp_dir/docker-stdin.sql"
}

RESET_TEST_STATE=true
reset_persistent_test_state_if_enabled "$tmp_dir"

if [[ ! -f "$tmp_dir/reset-test-state.log" ]]; then
    echo "reset log was not created" >&2
    exit 1
fi

if [[ "$(cat "$tmp_dir/docker-command.log")" != "exec -i postgres psql -U postgres -d postgres -v ON_ERROR_STOP=1" ]]; then
    echo "unexpected docker command" >&2
    cat "$tmp_dir/docker-command.log" >&2
    exit 1
fi

if ! grep -q "TRUNCATE TABLE notification_delivery" "$tmp_dir/docker-stdin.sql"; then
    echo "notification_delivery truncate missing" >&2
    exit 1
fi

if ! grep -q "TRUNCATE TABLE payment_transaction_entity" "$tmp_dir/docker-stdin.sql"; then
    echo "payment_transaction_entity truncate missing" >&2
    exit 1
fi

rm -f "$tmp_dir/docker-command.log" "$tmp_dir/docker-stdin.sql"
RESET_TEST_STATE=false
reset_persistent_test_state_if_enabled "$tmp_dir"

if [[ -e "$tmp_dir/docker-command.log" || -e "$tmp_dir/docker-stdin.sql" ]]; then
    echo "docker should not be called when reset is disabled" >&2
    exit 1
fi
