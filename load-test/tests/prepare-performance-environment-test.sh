#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PREPARER="${ROOT_DIR}/prepare-performance-environment.sh"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

mkdir -p "$tmp_dir/fake-bin"
export FLOW_LOG="$tmp_dir/flow.log"
export STACK_READINESS_SCRIPT="$tmp_dir/fake-readiness"
export RUN_LOAD_TEST_SCRIPT="$tmp_dir/forbidden-runner"

cat > "$tmp_dir/fake-bin/docker" <<'SH'
#!/bin/bash
set -euo pipefail
printf 'docker %s\n' "$*" >> "$FLOW_LOG"
if [[ "$*" == *" down -v --remove-orphans" ]]; then
    exit "${DOCKER_DOWN_STATUS:-0}"
fi
if [[ "$*" == *" up -d --build" ]]; then
    exit "${DOCKER_UP_STATUS:-0}"
fi
exit 40
SH
chmod +x "$tmp_dir/fake-bin/docker"

cat > "$tmp_dir/fake-readiness" <<'SH'
#!/bin/bash
set -euo pipefail
printf '%s\n' readiness >> "$FLOW_LOG"
exit "${READINESS_STATUS:-0}"
SH
chmod +x "$tmp_dir/fake-readiness"

cat > "$tmp_dir/forbidden-runner" <<'SH'
#!/bin/bash
set -euo pipefail
printf 'runner %s\n' "$*" >> "$FLOW_LOG"
exit 41
SH
chmod +x "$tmp_dir/forbidden-runner"

export PATH="$tmp_dir/fake-bin:$PATH"

run_case() {
    local case_id="$1"
    shift
    local status
    : > "$FLOW_LOG"
    set +e
    "$PREPARER" "$@" >"$tmp_dir/${case_id}.out" 2>"$tmp_dir/${case_id}.err"
    status=$?
    set -e
    printf '%s\n' "$status"
}

if [[ "$(run_case success)" -ne 0 ]]; then
    echo "preparer rejected a ready stack" >&2
    cat "$tmp_dir/success.err" >&2
    exit 1
fi
cat > "$tmp_dir/success.expected" <<EOF
docker compose -f ${ROOT_DIR%/load-test}/infra/docker-compose.yml down -v --remove-orphans
docker compose -f ${ROOT_DIR%/load-test}/infra/docker-compose.yml up -d --build
readiness
EOF
diff -u "$tmp_dir/success.expected" "$FLOW_LOG"

if [[ "$(DOCKER_DOWN_STATUS=17 run_case down-failure)" -eq 0 ]]; then
    echo "preparer accepted Docker reset failure" >&2
    exit 1
fi
if grep -qE 'up -d --build|readiness|runner' "$FLOW_LOG"; then
    echo "preparer continued after Docker reset failure" >&2
    exit 1
fi

if [[ "$(DOCKER_UP_STATUS=18 run_case up-failure)" -eq 0 ]]; then
    echo "preparer accepted Docker startup failure" >&2
    exit 1
fi
if grep -qE '^readiness$|^runner ' "$FLOW_LOG"; then
    echo "preparer continued after Docker startup failure" >&2
    exit 1
fi

if [[ "$(READINESS_STATUS=19 run_case readiness-failure)" -eq 0 ]]; then
    echo "preparer accepted readiness failure" >&2
    exit 1
fi
if grep -q '^runner ' "$FLOW_LOG"; then
    echo "preparer invoked a workload after readiness failure" >&2
    exit 1
fi

if [[ "$(run_case rejected-option --no-jfr)" -ne 2 ]]; then
    echo "preparer accepted removed diagnostic option" >&2
    exit 1
fi
if [[ -s "$FLOW_LOG" ]]; then
    echo "preparer caused side effects before rejecting its CLI" >&2
    exit 1
fi
