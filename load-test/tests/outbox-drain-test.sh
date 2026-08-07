#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

source "${ROOT_DIR}/run-load-test.sh"

PENDING_ROWS=0
docker() {
    if [[ "$*" == *"-tAc SELECT count(*)"* ]]; then
        echo "$PENDING_ROWS"
        return
    fi
    if [[ "$*" == *"--csv"* ]]; then
        printf 'publication_status,rows,oldest_created_at,latest_updated_at\nPUBLISHED,2,2026-08-04 12:00:00+00,2026-08-04 12:00:01+00\n'
        return
    fi
    echo "unexpected docker invocation: $*" >&2
    return 1
}

capture_and_assert_outbox_drained "$tmp_dir"

if [[ ! -s "$tmp_dir/notification-outbox-state.csv" ]]; then
    echo "notification outbox state file missing" >&2
    exit 1
fi

PENDING_ROWS=3
if capture_and_assert_outbox_drained "$tmp_dir" >/dev/null 2>&1; then
    echo "pending outbox rows should fail the load-test drain assertion" >&2
    exit 1
fi
