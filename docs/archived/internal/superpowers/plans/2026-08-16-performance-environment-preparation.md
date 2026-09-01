# Performance Environment Preparation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add one command that recreates, warms, and functionally qualifies the performance-test stack, then leaves it running for repeated measured load-test executions.

**Architecture:** Keep `run-load-test.sh` as the only measured-run entry point. Build the preparer from three focused internal adapters—a bounded stack-readiness gate, a shared Kafka-quiescence gate, and a smoke-report qualifier—then orchestrate them from a small top-level shell script. Enable existing diagnostics by default and give the runner unambiguous completed-invalid versus operational-failure exit codes so the preparer can accept a functionally correct smoke without hiding tooling failures.

**Tech Stack:** Bash 5, Python 3 standard library, Docker Compose, Kafka CLI, existing Go load-tool and JSON report contract.

## Global Constraints

- `prepare-performance-environment.sh` must never start a measured profile.
- Preparation always runs `docker compose -f infra/docker-compose.yml down -v --remove-orphans` followed by `up -d --build`; Docker images and build cache must not be deleted.
- Preparation always uses `mixed-outcomes-smoke`, leaves the stack running, and introduces no `fresh` or `reuse` mode.
- JFR, SPI trace, and PostgreSQL diagnostics are enabled by default for every profile; only `--no-jfr`, `--no-spi-trace`, and `--no-postgres-statements` disable them.
- The current smoke fixture plans 1,250 original payments. Treat 1,250 as a characterization of `mixed-outcomes-smoke`, not a general load-tool rule.
- A smoke may ignore performance validity. A timeout is retryable only when the payment still reaches its expected payer PACS.002; explicit HTTP non-2xx, missing/contradictory outcomes, replay, tooling, and instrumentation failures remain fatal.
- Kafka quiescence is a heuristic consisting of exactly three zero-lag observations one second apart. Any unreadable or nonzero observation fails the gate.
- Do not add a generic experiment framework, manifest, orchestration log, image inventory, volume snapshot, or direct database/outbox quiescence check.
- Do not start a 15-minute profile during verification.
- Do not create commits or stage files; the user intentionally reviews the complete implementation through `git diff`.

---

### Task 1: Make runner diagnostics default-on and classify exit status

**Files:**
- Modify: `load-test/run-load-test.sh`
- Modify: `load-test/tests/runner-exit-test.sh`
- Create: `load-test/tests/runner-diagnostics-defaults-test.sh`

**Interfaces:**
- Consumes: existing diagnostic lifecycle functions in `run-load-test.sh`.
- Produces: `run-load-test.sh [--no-jfr] [--no-spi-trace] [--no-postgres-statements] [--profile NAME] RUN_TAG`.
- Produces: exit `0` for a completed valid report, `1` for a completed invalid report, and `2` for argument, preparation, load-tool, or diagnostic failure.

- [ ] **Step 1: Write the failing diagnostic-default tests**

Create `runner-diagnostics-defaults-test.sh`, source the runner in a fresh subshell for each case, call `parse_args`, and assert the resulting booleans:

```bash
assert_flags() {
    local want_jfr="$1" want_trace="$2" want_postgres="$3"
    shift 3
    (
        source "${ROOT_DIR}/run-load-test.sh"
        parse_args "$@" test-run
        [[ "$ENABLE_JFR" == "$want_jfr" ]]
        [[ "$ENABLE_SPI_TRACE" == "$want_trace" ]]
        [[ "$ENABLE_POSTGRES_STATEMENTS" == "$want_postgres" ]]
    )
}

assert_flags true true true
assert_flags false true true --no-jfr
assert_flags true false true --no-spi-trace
assert_flags true true false --no-postgres-statements
assert_flags false false false --no-jfr --no-spi-trace --no-postgres-statements
```

Also assert that `usage` lists the three negative flags and no longer lists the three positive flags.

- [ ] **Step 2: Update the exit-flow test expectations before changing the runner**

Change `runner-exit-test.sh` so:

```text
valid report                 -> 0
valid:false report           -> 1 and enriched run-window
load-tool returns 23         -> 2 after diagnostic collection
diagnostics return 19        -> 2
environment preparation 17  -> 2 before running load-tool
```

Retain the existing flow assertions proving that diagnostics are collected after a load-tool failure and that invalid completed runs are enriched.

- [ ] **Step 3: Run the focused tests and confirm both fail for the intended reasons**

Run:

```bash
bash load-test/tests/runner-diagnostics-defaults-test.sh
bash load-test/tests/runner-exit-test.sh
```

Expected: the first test observes default `false` flags/unknown negative flags; the second observes the original operational exit codes 17, 19, and 23.

- [ ] **Step 4: Implement the negative diagnostic contract**

In `run-load-test.sh`, initialize all diagnostic switches to `true`, update `usage`, and invert only the selected flag:

```bash
ENABLE_JFR=true
ENABLE_SPI_TRACE=true
ENABLE_POSTGRES_STATEMENTS=true

case "$1" in
    --no-jfr) ENABLE_JFR=false; shift ;;
    --no-spi-trace) ENABLE_SPI_TRACE=false; shift ;;
    --no-postgres-statements) ENABLE_POSTGRES_STATEMENTS=false; shift ;;
    # existing --profile/help/positional handling remains
esac
```

Remove the positive options rather than retaining deprecated no-ops.

- [ ] **Step 5: Normalize runner outcomes without skipping diagnostics**

Add named constants:

```bash
readonly INVALID_REPORT_EXIT=1
readonly OPERATIONAL_FAILURE_EXIT=2
```

Wrap every operational stage in `main` explicitly so `set -e` cannot leak an internal status. Preserve the existing load-tool flow:

```bash
if run_loadtool "$target_dir"; then
    loadtool_status=0
else
    loadtool_status=$?
fi

if collect_optional_diagnostics "$target_dir"; then
    diagnostics_status=0
else
    diagnostics_status=$?
fi

if ((loadtool_status != 0 || diagnostics_status != 0)); then
    return "$OPERATIONAL_FAILURE_EXIT"
fi
```

After enriching the completed bundle, translate only report validity to exit 1:

```bash
if ! validate_sla_report "${target_dir}/sla-report.json"; then
    return "$INVALID_REPORT_EXIT"
fi
```

Argument/profile errors remain exit 2. Build, validation, workspace, certificate, preflight, environment, diagnostic startup, load-tool, diagnostic collection, and run-window enrichment failures must all be explicitly translated to exit 2.

- [ ] **Step 6: Run the focused runner tests**

Run:

```bash
bash load-test/tests/runner-diagnostics-defaults-test.sh
bash load-test/tests/runner-exit-test.sh
bash load-test/tests/observable-outcome-flow-test.sh
bash load-test/tests/profile-contract-test.sh
```

Expected: all pass, including preservation of diagnostics after load-tool failure and the observable-only run flow.

- [ ] **Step 7: Review the task diff without committing**

Run:

```bash
git diff --check
git diff -- load-test/run-load-test.sh load-test/tests/runner-exit-test.sh load-test/tests/runner-diagnostics-defaults-test.sh
```

Expected: no whitespace errors; leave all files unstaged.

---

### Task 2: Extract the shared three-sample Kafka quiescence gate

**Files:**
- Create: `load-test/scripts/check-kafka-quiescence.sh`
- Modify: `load-test/scripts/prepare-environment.sh`
- Create: `load-test/tests/kafka-quiescence-test.sh`
- Modify: `load-test/tests/prepare-environment-test.sh`

**Interfaces:**
- Produces: executable `check-kafka-quiescence.sh` with no positional arguments.
- Produces: exit `0` only after three complete zero-lag samples; exit nonzero on unreadable or nonzero lag.
- Consumes from callers: optional existing environment overrides for Kafka container, bootstrap server, group names, topic names, and CLI timeout.
- Produces for Task 5: `KAFKA_QUIESCENCE_SCRIPT` dependency override used by both environment preparation paths.

- [ ] **Step 1: Write failing tests for the standalone gate**

Create a fake `docker` executable that emits Kafka group rows from per-reading sequences such as:

```bash
SPI_PAYMENT_LAGS="0,0,0"
SPI_STATUS_LAGS="0,0,0"
GATEWAY_LAGS="0,0,0"
```

Use a fake `sleep` that records `1` without waiting. Cover:

```text
0/0/0 for every group -> success, three aggregated group reads, two sleeps
nonzero in sample 1  -> immediate failure, no funding side effect
nonzero in sample 2  -> failure after the first sleep
unreadable group     -> failure
NO_OFFSETS + topic end offset 0 -> accepted as zero
NO_OFFSETS + topic end offset >0 -> failure
```

- [ ] **Step 2: Run the new test and confirm the script is missing**

Run:

```bash
bash load-test/tests/kafka-quiescence-test.sh
```

Expected: FAIL because `scripts/check-kafka-quiescence.sh` does not exist.

- [ ] **Step 3: Move lag observation into the standalone script**

Move these responsibilities out of `prepare-environment.sh` and observe all required group/topic pairs through one `--all-groups --describe` call per sample:

```text
consumer_group_lags
topic_end_offset
```

Implement exactly three samples:

```bash
for sample in 1 2 3; do
    lags="$(consumer_group_lags)" || return 1
    IFS=$'\t' read -r payment_lag status_lag gateway_lag <<< "$lags"

    if ((payment_lag != 0 || status_lag != 0 || gateway_lag != 0)); then
        echo "Kafka is not quiescent at sample ${sample}: payment=${payment_lag} status=${status_lag} gateway=${gateway_lag}." >&2
        return 1
    fi
    if ((sample < 3)); then sleep 1; fi
done
```

Keep the existing `NO_OFFSETS` fallback to the topic end offset so a fresh empty topic is accepted but an unconsumed topic is not.

- [ ] **Step 4: Make per-run environment preparation call the shared gate**

In `prepare-environment.sh`, add:

```bash
readonly KAFKA_QUIESCENCE_SCRIPT="${KAFKA_QUIESCENCE_SCRIPT:-${SCRIPT_DIR}/check-kafka-quiescence.sh}"
```

Validate that it is executable before provisioning and invoke it before any funding mutation. Remove the duplicated Kafka constants and functions from this file.

Adapt `prepare-environment-test.sh` to inject a fake `KAFKA_QUIESCENCE_SCRIPT`. Assert one invocation before provisioning, failure propagation, and no funding calls when the gate fails. Kafka parsing remains covered only by `kafka-quiescence-test.sh`.

- [ ] **Step 5: Run the focused environment tests**

Run:

```bash
bash load-test/tests/kafka-quiescence-test.sh
bash load-test/tests/prepare-environment-test.sh
bash load-test/tests/profile-contract-test.sh
```

Expected: all pass; a normal per-run preparation now pays exactly the approved two-second observation interval.

- [ ] **Step 6: Review the task diff without committing**

Run:

```bash
bash -n load-test/scripts/check-kafka-quiescence.sh load-test/scripts/prepare-environment.sh
git diff --check
```

Expected: syntax and whitespace checks pass; leave files unstaged.

---

### Task 3: Add the bounded performance-stack readiness gate

**Files:**
- Create: `load-test/scripts/wait-for-performance-stack.sh`
- Create: `load-test/tests/performance-stack-readiness-test.sh`

**Interfaces:**
- Produces: executable `wait-for-performance-stack.sh` with no positional arguments.
- Produces: `wait_for_performance_stack() -> status`, using `READINESS_TIMEOUT_SECONDS` (default `120`) and `READINESS_POLL_SECONDS` (default `2`).
- Checks: healthy PostgreSQL/Kafka, running application containers, accepting ports 8001/8002/9090, and Stable Kafka groups with at least one member.
- Produces for Task 5: `STACK_READINESS_SCRIPT` dependency override.

- [ ] **Step 1: Write failing readiness unit tests by sourcing the script**

The test overrides the focused predicates and clock instead of faking all Docker output:

```bash
source "${ROOT_DIR}/scripts/wait-for-performance-stack.sh"

infrastructure_healthy() { [[ "$READINESS_CASE" != postgres-unhealthy ]]; }
applications_running() { [[ "$READINESS_CASE" != app-stopped ]]; }
application_ports_accepting() { [[ "$READINESS_CASE" != port-closed ]]; }
consumer_groups_stable() { [[ "$READINESS_CASE" != group-rebalancing ]]; }
readiness_now() { printf '%s\n' "$FAKE_NOW"; }
readiness_sleep() {
    printf 'sleep %s\n' "$1" >> "$FLOW_LOG"
    FAKE_NOW=$((FAKE_NOW + $1))
}
```

Cover immediate success, one transient failed poll followed by success, and timeout for each failed predicate. Set a short injected timeout in tests so no real sleep occurs.

- [ ] **Step 2: Run the test and confirm the script is missing**

Run:

```bash
bash load-test/tests/performance-stack-readiness-test.sh
```

Expected: FAIL because the readiness script does not exist.

- [ ] **Step 3: Implement the focused readiness predicates**

Use `docker inspect` for container state:

```text
postgres, kafka                   -> .State.Health.Status == healthy
kafka-producer, spi, gateway      -> .State.Running == true
```

Use a one-second bounded TCP connect for `127.0.0.1:8001`, `:8002`, and `:9090`. Do not require an application-specific HTTP response because 9090 is gRPC and readiness only needs to prove that the ingress ports accept connections.

Read all consumer-group states in one Kafka CLI process per readiness poll:

```bash
docker exec kafka kafka-consumer-groups \
  --bootstrap-server kafka:9092 \
  --list --state
```

Require one `Stable` row for each of the three exact group names. Kafka's `Stable` state implies an active assigned group; treat missing, empty, rebalancing, or unreadable groups as not ready during the polling interval.

- [ ] **Step 4: Implement the 120-second bounded polling loop**

Use an overridable clock whose production implementation returns Bash's `SECONDS` value. Keep readiness predicates side-effect free:

```bash
readiness_now() { printf '%s\n' "$SECONDS"; }

deadline=$(($(readiness_now) + READINESS_TIMEOUT_SECONDS))
while :; do
    if infrastructure_healthy && applications_running \
        && application_ports_accepting && consumer_groups_stable; then
        return 0
    fi
    if (( $(readiness_now) >= deadline)); then
        echo "Performance stack did not become ready within ${READINESS_TIMEOUT_SECONDS}s." >&2
        return 1
    fi
    readiness_sleep "$READINESS_POLL_SECONDS"
done
```

Guard `main` with `[[ "${BASH_SOURCE[0]}" == "$0" ]]` so the focused functions remain unit-testable.

- [ ] **Step 5: Run readiness tests and syntax validation**

Run:

```bash
bash load-test/tests/performance-stack-readiness-test.sh
bash -n load-test/scripts/wait-for-performance-stack.sh
```

Expected: all cases pass without starting Docker.

- [ ] **Step 6: Review the task diff without committing**

Run `git diff --check` and inspect only the readiness script and its test. Leave both unstaged.

---

### Task 4: Add the functional smoke-report qualifier

**Files:**
- Create: `load-test/scripts/qualify-smoke-report.py`
- Create: `load-test/tests/qualify-smoke-report-test.sh`

**Interfaces:**
- Consumes: `qualify-smoke-report.py RUN_DIR`, using the current `sla-report.json`, `events/pacs008-starts.csv`, and `events/notifications.csv` schemas.
- Produces: exit `0` for a fully qualified 1,250-original smoke, `10` for a retryable partial-but-correct smoke, and `20` for malformed or functionally invalid evidence.
- Produces for Task 5: output messages explaining qualified, retryable, or invalid status.

- [ ] **Step 1: Create report fixtures and failing CLI tests**

Build a valid report fixture with two scenarios and separate replay sections. Its essential values are:

```json
{
  "valid": false,
  "scenarios": [
    {
      "name": "happy-path",
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
      "traffic": {
        "payments": {"started": 250, "accepted": 250},
        "pacs002": {"started": 250, "accepted": 250}
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
  }
}
```

Assert exit 0 even though `valid` is false. Derive mutation fixtures from it to cover exit 10 for 1,249 fully matched originals and for timeout status `0` whose payer notification has the expected ACSC or RJCT/AM04. Cover exit 20 for: more than 1,250 starts, explicit 4xx/5xx, timeout without a final payer notification, timeout with a wrong final outcome, PACS.002 mismatch, missing outcome, contradictory outcome, wrong ACSC/RJCT/AM04 expectation, scenario/replay violation unrelated to the timeout count, malformed CSV/JSON, missing artifact, and missing required field.

- [ ] **Step 2: Run the qualifier test and confirm the CLI is missing**

Run:

```bash
bash load-test/tests/qualify-smoke-report-test.sh
```

Expected: FAIL because `qualify-smoke-report.py` does not exist.

- [ ] **Step 3: Implement strict standard-library JSON validation**

Define constants:

```python
PLANNED_ORIGINALS = 1_250
QUALIFIED = 0
RETRYABLE = 10
INVALID = 20
EXPECTED_OUTCOMES = {
    "happy-path": ("ACSC", []),
    "insufficient-funds": ("RJCT", ["AM04"]),
}
```

For a qualifying result, require non-negative integer counters and these equalities:

```text
payments.accepted == payments.started
pacs002.started == payments.started
pacs002.accepted == payments.started
outcome.matched == payments.started
outcome.missing == 0
outcome.contradictory == 0
violations == 0
```

Require exactly the expected outcome status/reason list. For both replay types require non-negative integers, `started == accepted`, and `violations == 0`.

For a retryable result, allow `payments.accepted < payments.started` only when the difference equals the number of status-0 rows for that scenario, every other PACS.008 row is 2xx, `pacs002.started == pacs002.accepted == payments.started`, `outcome.matched == payments.accepted`, `missing == contradictory == 0`, and the scenario violation count equals the timeout count. Every timed-out EndToEndId must have at least one payer `pacs002_received` with the configured expected status/reasons and no contradictory payer notification. Any explicit HTTP response outside 2xx is fatal.

Sum `payments.started` across the two scenarios:

```text
== 1,250 -> exit 0
 < 1,250 -> exit 10, after all functional equalities passed
 > 1,250 -> exit 20
```

Require the document root to be an object, top-level `valid` to be a boolean, `scenarios` to be an array, and `replays` to be an object. Do not inspect performance latency or rolling-throughput, and do not use the boolean value of top-level `valid` when assigning exit 0 versus 10.

- [ ] **Step 4: Run the qualifier test**

Run:

```bash
bash load-test/tests/qualify-smoke-report-test.sh
```

Expected: every structural, functional, replay, and partial case returns its exact status.

- [ ] **Step 5: Review the task diff without committing**

Run:

```bash
python3 -m py_compile load-test/scripts/qualify-smoke-report.py
git diff --check
```

Set executable permission on the CLI and leave all changes unstaged.

---

### Task 5: Orchestrate isolated setup, smoke retries, and post-smoke quiescence

**Files:**
- Create: `load-test/prepare-performance-environment.sh`
- Create: `load-test/tests/prepare-performance-environment-test.sh`

**Interfaces:**
- Consumes: Task 1 runner status contract.
- Consumes: `STACK_READINESS_SCRIPT`, `KAFKA_QUIESCENCE_SCRIPT`, and `SMOKE_QUALIFIER_SCRIPT` from Tasks 2–4.
- Consumes: optional `--no-jfr`, `--no-spi-trace`, and `--no-postgres-statements`.
- Produces: exit `0` only for a ready, smoke-qualified, quiescent stack left running; nonzero otherwise.
- Produces: uniquely tagged ordinary smoke bundles under `load-test/results/environment-setup-*/`.

- [ ] **Step 1: Write a fake-driven orchestration-flow test**

Inject dependencies through environment variables:

```text
RUN_LOAD_TEST_SCRIPT
STACK_READINESS_SCRIPT
KAFKA_QUIESCENCE_SCRIPT
SMOKE_QUALIFIER_SCRIPT
PERFORMANCE_RESULTS_DIR
PREPARATION_RUN_ID
SLEEP_COMMAND
```

Put fake `docker` and dependency scripts on the test path. Every fake appends one line to `FLOW_LOG`; the fake runner creates exactly one timestamped run directory for its received tag.

For immediate success, assert this order:

```text
docker compose -f ... down -v --remove-orphans
docker compose -f ... up -d --build
readiness
sleep 10
runner --profile mixed-outcomes-smoke environment-setup-<id>-attempt-1
qualifier <attempt-1-run-dir>
sleep 10
quiescence
```

Assert there is no measured-profile invocation and no teardown after success.

- [ ] **Step 2: Add failing orchestration cases**

Cover:

```text
readiness failure                     -> abort before sleep/smoke
runner exit 2                         -> abort without qualification/retry
runner exit 1 + qualifier 0           -> accept functional smoke
runner exit 1 + qualifier 10, then 0  -> run exactly two attempts
qualifier 10 three times              -> fail after exactly three attempts
qualifier 20                          -> abort immediately
quiescence failure                    -> fail and leave stack running
each --no-* flag                      -> forwarded to every runner attempt
```

Also assert the only `down` is the initial reset in success and failure flows.

- [ ] **Step 3: Run the test and confirm the preparer is missing**

Run:

```bash
bash load-test/tests/prepare-performance-environment-test.sh
```

Expected: FAIL because the top-level script does not exist.

- [ ] **Step 4: Implement dependency resolution and CLI parsing**

In the preparer, resolve paths from the script location and allow only test-time environment substitution:

```bash
readonly LOAD_TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "${LOAD_TEST_DIR}/.." && pwd)"
readonly COMPOSE_FILE="${REPOSITORY_ROOT}/infra/docker-compose.yml"
readonly RUN_LOAD_TEST_SCRIPT="${RUN_LOAD_TEST_SCRIPT:-${LOAD_TEST_DIR}/run-load-test.sh}"
readonly STACK_READINESS_SCRIPT="${STACK_READINESS_SCRIPT:-${LOAD_TEST_DIR}/scripts/wait-for-performance-stack.sh}"
readonly KAFKA_QUIESCENCE_SCRIPT="${KAFKA_QUIESCENCE_SCRIPT:-${LOAD_TEST_DIR}/scripts/check-kafka-quiescence.sh}"
readonly SMOKE_QUALIFIER_SCRIPT="${SMOKE_QUALIFIER_SCRIPT:-${LOAD_TEST_DIR}/scripts/qualify-smoke-report.py}"
```

Parse only help and the three negative diagnostic flags. Preserve the received flags in an array to pass unchanged to every smoke attempt. Reject profile names, run tags, positive diagnostic switches, and unknown options.

- [ ] **Step 5: Implement reset, cached build, readiness, and stabilization**

Run Compose from the repository root with the local UID/GID so generated certificates remain accessible:

```bash
LOCAL_UID="$(id -u)" LOCAL_GID="$(id -g)" \
    docker compose -f "$COMPOSE_FILE" down -v --remove-orphans
LOCAL_UID="$(id -u)" LOCAL_GID="$(id -g)" \
    docker compose -f "$COMPOSE_FILE" up -d --build
```

Do not pass `--rmi`, `docker builder prune`, or any cache-removal option. Invoke the readiness script, then sleep exactly 10 seconds once before the first attempt.

- [ ] **Step 6: Implement bounded smoke qualification**

Generate a unique base such as `environment-setup-${PREPARATION_RUN_ID}` and append `-attempt-1` through `-attempt-3`. For each attempt:

```bash
if "$RUN_LOAD_TEST_SCRIPT" \
    --profile mixed-outcomes-smoke \
    "${DIAGNOSTIC_ARGS[@]}" \
    "$attempt_tag"; then
    runner_status=0
else
    runner_status=$?
fi
```

Abort for runner status 2 or any status outside 0/1. Resolve exactly one result directory beneath `${PERFORMANCE_RESULTS_DIR}/${attempt_tag}`; zero or multiple directories is operational failure. Run the qualifier and branch only on 0/10/20. Status 10 retries until attempt 3; status 20 aborts immediately.

- [ ] **Step 7: Implement settling and the final shared quiescence gate**

After qualifier status 0, sleep exactly 10 seconds, invoke `KAFKA_QUIESCENCE_SCRIPT`, print the ordinary measured-run example, and return zero. Do not call Compose down in normal flow, error handling, or a trap.

- [ ] **Step 8: Run the orchestration and focused integration tests**

Run:

```bash
bash load-test/tests/prepare-performance-environment-test.sh
bash load-test/tests/runner-diagnostics-defaults-test.sh
bash load-test/tests/runner-exit-test.sh
bash load-test/tests/kafka-quiescence-test.sh
bash load-test/tests/performance-stack-readiness-test.sh
bash load-test/tests/qualify-smoke-report-test.sh
bash load-test/tests/prepare-environment-test.sh
```

Expected: all pass without Docker or real sleeping.

- [ ] **Step 9: Review the task diff without committing**

Run:

```bash
bash -n load-test/prepare-performance-environment.sh
git diff --check
```

Set executable permission on the preparer and leave it unstaged.

---

### Task 6: Document the fast repeated-test workflow

**Files:**
- Modify: `README.md`
- Modify: `docs/board/Atividades/agora/estabilizar-teste-carga-budget-cpu.md`
- Keep: `docs/superpowers/specs/2026-08-15-performance-environment-preparation-design.md`
- Keep: `docs/superpowers/plans/2026-08-16-performance-environment-preparation.md`

**Interfaces:**
- Documents: prepare once per deployed code state, run `mixed-outcomes-2k-diagnostic` repeatedly, prepare again after changing code, and prepare each side of a formal A/B independently.
- Documents: diagnostics default-on and negative opt-out flags.

- [ ] **Step 1: Update the obvious root README workflow**

Keep the README compact. Replace the manual smoke as the primary preparation path with:

```bash
cd load-test
./prepare-performance-environment.sh

./run-load-test.sh --profile mixed-outcomes-2k-diagnostic diagnostic-1
./run-load-test.sh --profile mixed-outcomes-2k-diagnostic diagnostic-2
```

State in one short paragraph that preparation removes PostgreSQL/Kafka volumes but preserves Docker images/build cache, leaves the stack running, and should be repeated after changing deployed code. Retain the official 15-minute command separately.

- [ ] **Step 2: Update the active stabilization task**

In the workload/method sections:

- replace the manual restart-plus-smoke checklist with the new preparation command;
- say diagnostics are collected by default and list only the three `--no-*` switches;
- describe repeated diagnostic runs as direct runner calls after one qualified preparation;
- retain historical A/B evidence, but rewrite literal positive-flag wording as “com JFR, SPI trace e diagnósticos PostgreSQL ativos” so the task does not advertise removed CLI options;
- do not change measured results, conclusions, or remaining performance work.

- [ ] **Step 3: Check current documentation for stale public commands**

Run:

```bash
rg -n -- '--jfr|--spi-trace|--postgres-statements' README.md docs/board/Atividades/agora load-test/run-load-test.sh
```

Expected: no positive diagnostic options remain in current public documentation or runner usage. Historical superpowers specs/plans are records and must not be rewritten.

- [ ] **Step 4: Review documentation without committing**

Run `git diff --check` and inspect the README/task diff to ensure no performance conclusion or task scope changed.

---

### Task 7: Run complete automated and real-path verification

**Files:**
- Verify all files changed in Tasks 1–6.
- Do not modify production behavior unless a failing verification exposes a defect covered by the approved specification.

**Interfaces:**
- Verifies: shell contracts, Go load-tool suite, real Compose readiness, real functional smoke qualification, and one short measured invocation.

- [ ] **Step 1: Run every load-test shell test**

Run:

```bash
for test_script in load-test/tests/*-test.sh; do
    echo "Running ${test_script}"
    bash "$test_script"
done
```

Expected: every script exits 0.

- [ ] **Step 2: Run Go tests and static shell checks**

Run:

```bash
(cd load-test/go-loadtool && go test ./...)
bash -n load-test/run-load-test.sh \
  load-test/prepare-performance-environment.sh \
  load-test/scripts/*.sh \
  load-test/tests/*.sh
python3 -m py_compile load-test/scripts/qualify-smoke-report.py
git diff --check
```

Expected: all commands exit 0.

- [ ] **Step 3: Execute one real preparation**

Run from `load-test/`:

```bash
./prepare-performance-environment.sh
```

Expected: Compose reset/build succeeds, readiness completes within 120 seconds, one of at most three smokes qualifies functionally, three post-settle lag observations are zero, and the stack remains running. Confirm the accepted smoke bundle contains diagnostics because no negative switches were supplied.

- [ ] **Step 4: Execute one short measured diagnostic run without preparing again**

Run from `load-test/`:

```bash
./run-load-test.sh --profile mixed-outcomes-2k-diagnostic preparation-workflow-verification
```

Expected: the runner starts directly against the prepared stack and writes a complete bundle. Exit 1 is acceptable only when the report is technically complete and `valid: false`; exit 2 is a verification failure. Do not run `mixed-outcomes-2k-15m`.

- [ ] **Step 5: Prove repeated-run semantics and final workspace state**

Confirm the preparation command was invoked once, both the smoke and measured bundles exist, and the stack is still running. Run:

```bash
docker compose -f infra/docker-compose.yml ps
git status --short
git diff --check
```

Expected: required containers are running; all implementation/spec/plan changes remain unstaged and uncommitted; no unrelated files changed.
