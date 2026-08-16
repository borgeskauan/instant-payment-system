#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PREPARER="${ROOT_DIR}/prepare-performance-environment.sh"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

mkdir -p "$tmp_dir/fake-bin" "$tmp_dir/results" "$tmp_dir/state"
export FLOW_LOG="$tmp_dir/flow.log"
export PERFORMANCE_RESULTS_DIR="$tmp_dir/results"
export RUN_LOAD_TEST_SCRIPT="$tmp_dir/fake-runner"
export STACK_READINESS_SCRIPT="$tmp_dir/fake-readiness"
export KAFKA_QUIESCENCE_SCRIPT="$tmp_dir/fake-quiescence"
export SMOKE_QUALIFIER_SCRIPT="$tmp_dir/fake-qualifier"
export SLEEP_COMMAND="$tmp_dir/fake-sleep"
export FAKE_STATE_DIR="$tmp_dir/state"

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

cat > "$tmp_dir/fake-sleep" <<'SH'
#!/bin/bash
set -euo pipefail
printf 'sleep %s\n' "$*" >> "$FLOW_LOG"
SH
chmod +x "$tmp_dir/fake-sleep"

cat > "$tmp_dir/fake-runner" <<'SH'
#!/bin/bash
set -euo pipefail
printf 'runner %s\n' "$*" >> "$FLOW_LOG"

state_file="$FAKE_STATE_DIR/runner-count"
count=0
if [[ -f "$state_file" ]]; then count="$(<"$state_file")"; fi
printf '%s\n' "$((count + 1))" > "$state_file"

tag="${!#}"
case "${RESULT_DIR_MODE:-one}" in
    one)
        mkdir -p "$PERFORMANCE_RESULTS_DIR/$tag/20260816_000000"
        printf '%s\n' '{"valid":false}' > "$PERFORMANCE_RESULTS_DIR/$tag/20260816_000000/sla-report.json"
        ;;
    multiple)
        mkdir -p "$PERFORMANCE_RESULTS_DIR/$tag/one" "$PERFORMANCE_RESULTS_DIR/$tag/two"
        ;;
    none)
        ;;
esac

IFS=',' read -r -a statuses <<< "${RUNNER_STATUSES:-1}"
index="$count"
if ((index >= ${#statuses[@]})); then index=$((${#statuses[@]} - 1)); fi
exit "${statuses[index]}"
SH
chmod +x "$tmp_dir/fake-runner"

cat > "$tmp_dir/fake-qualifier" <<'SH'
#!/bin/bash
set -euo pipefail
printf 'qualifier %s\n' "$*" >> "$FLOW_LOG"

state_file="$FAKE_STATE_DIR/qualifier-count"
count=0
if [[ -f "$state_file" ]]; then count="$(<"$state_file")"; fi
printf '%s\n' "$((count + 1))" > "$state_file"

IFS=',' read -r -a statuses <<< "${QUALIFIER_STATUSES:-0}"
index="$count"
if ((index >= ${#statuses[@]})); then index=$((${#statuses[@]} - 1)); fi
exit "${statuses[index]}"
SH
chmod +x "$tmp_dir/fake-qualifier"

cat > "$tmp_dir/fake-quiescence" <<'SH'
#!/bin/bash
set -euo pipefail
printf '%s\n' quiescence >> "$FLOW_LOG"
exit "${QUIESCENCE_STATUS:-0}"
SH
chmod +x "$tmp_dir/fake-quiescence"

export PATH="$tmp_dir/fake-bin:$PATH"

reset_case() {
    rm -rf "$tmp_dir/results" "$tmp_dir/state"
    mkdir -p "$tmp_dir/results" "$tmp_dir/state"
    : > "$FLOW_LOG"
}

run_case() {
    local case_id="$1"
    shift
    local status

    set +e
    PREPARATION_RUN_ID="$case_id" "$PREPARER" "$@" \
        >"$tmp_dir/${case_id}.out" 2>"$tmp_dir/${case_id}.err"
    status=$?
    set -e
    printf '%s\n' "$status"
}

assert_single_initial_down() {
    if [[ "$(grep -c ' down -v --remove-orphans$' "$FLOW_LOG")" -ne 1 ]]; then
        echo "preparer did not perform exactly one initial destructive reset" >&2
        exit 1
    fi
}

reset_case
if [[ "$(run_case success)" -ne 0 ]]; then
    echo "preparer rejected a qualified smoke" >&2
    cat "$tmp_dir/success.err" >&2
    exit 1
fi
cat > "$tmp_dir/success.expected" <<EOF
docker compose -f ${ROOT_DIR%/load-test}/infra/docker-compose.yml down -v --remove-orphans
docker compose -f ${ROOT_DIR%/load-test}/infra/docker-compose.yml up -d --build
readiness
sleep 10
runner --profile mixed-outcomes-smoke environment-setup-success-attempt-1
qualifier $tmp_dir/results/environment-setup-success-attempt-1/20260816_000000
sleep 10
quiescence
EOF
diff -u "$tmp_dir/success.expected" "$FLOW_LOG"
assert_single_initial_down

reset_case
if [[ "$(RUNNER_STATUSES=2 run_case runner-failure)" -eq 0 ]]; then
    echo "preparer accepted an operational runner failure" >&2
    exit 1
fi
if grep -qE 'qualifier|quiescence' "$FLOW_LOG"; then
    echo "preparer continued after an operational runner failure" >&2
    exit 1
fi
assert_single_initial_down

reset_case
if [[ "$(QUALIFIER_STATUSES=10,0 run_case retry-success)" -ne 0 ]]; then
    echo "preparer did not accept a qualified second smoke" >&2
    exit 1
fi
if [[ "$(grep -c '^runner ' "$FLOW_LOG")" -ne 2 ]] || [[ "$(grep -c '^qualifier ' "$FLOW_LOG")" -ne 2 ]]; then
    echo "preparer did not execute exactly two smoke attempts" >&2
    exit 1
fi

reset_case
if [[ "$(QUALIFIER_STATUSES=10,10,10 run_case retries-exhausted)" -eq 0 ]]; then
    echo "preparer accepted three partial smokes" >&2
    exit 1
fi
if [[ "$(grep -c '^runner ' "$FLOW_LOG")" -ne 3 ]] || grep -q quiescence "$FLOW_LOG"; then
    echo "preparer did not stop after exactly three partial smokes" >&2
    exit 1
fi

reset_case
if [[ "$(QUALIFIER_STATUSES=20 run_case invalid-smoke)" -eq 0 ]]; then
    echo "preparer accepted a functionally invalid smoke" >&2
    exit 1
fi
if [[ "$(grep -c '^runner ' "$FLOW_LOG")" -ne 1 ]] || grep -q quiescence "$FLOW_LOG"; then
    echo "preparer retried a functionally invalid smoke" >&2
    exit 1
fi

reset_case
if [[ "$(READINESS_STATUS=1 run_case readiness-failure)" -eq 0 ]]; then
    echo "preparer accepted readiness failure" >&2
    exit 1
fi
if grep -qE '^sleep|^runner|^qualifier|^quiescence' "$FLOW_LOG"; then
    echo "preparer continued after readiness failure" >&2
    exit 1
fi

reset_case
if [[ "$(QUIESCENCE_STATUS=1 run_case quiescence-failure)" -eq 0 ]]; then
    echo "preparer accepted failed post-smoke quiescence" >&2
    exit 1
fi
assert_single_initial_down

reset_case
if [[ "$(run_case flags --no-jfr --no-spi-trace --no-postgres-statements)" -ne 0 ]]; then
    echo "preparer rejected supported negative diagnostic flags" >&2
    exit 1
fi
if ! grep -q '^runner --profile mixed-outcomes-smoke --no-jfr --no-spi-trace --no-postgres-statements environment-setup-flags-attempt-1$' "$FLOW_LOG"; then
    echo "preparer did not forward diagnostic flags unchanged" >&2
    exit 1
fi

reset_case
if [[ "$(RESULT_DIR_MODE=multiple run_case multiple-results)" -eq 0 ]]; then
    echo "preparer accepted ambiguous smoke result directories" >&2
    exit 1
fi

reset_case
if [[ "$(run_case rejected-option --profile mixed-outcomes-2k-diagnostic)" -eq 0 ]]; then
    echo "preparer accepted a measured profile option" >&2
    exit 1
fi
if [[ -s "$FLOW_LOG" ]]; then
    echo "preparer caused side effects before rejecting its CLI" >&2
    exit 1
fi
