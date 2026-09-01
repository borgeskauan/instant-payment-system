# Load-test Kafka-lag quiescence implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove preventive PostgreSQL state cleanup from the load-test runner while preserving the existing single-sample Kafka-lag preflight and API-based fund provisioning.

**Architecture:** The shell runner remains the owner of environment preflight and continues querying the three existing Kafka consumer-group lags. It no longer knows PostgreSQL connection settings or service-owned table names. No Go code or service endpoint changes are required.

**Tech Stack:** Bash, Docker Kafka CLI, existing Go test suite.

## Global Constraints

- Treat Kafka lag as a best-effort heuristic, not proof of quiescence.
- Keep exactly the existing single lag check; do not add repeated sampling or a stability window.
- Do not add SPI or notification-gateway observability endpoints.
- Preserve fund provisioning through the SPI administrative HTTP API.
- Preserve the opt-in `--postgres-statements` diagnostic; only state-cleanup PostgreSQL access is removed.
- Add no residual-work detector, classifier or automatic invalidation behavior.
- Leave every change uncommitted so it remains visible through `git diff`.

---

### Task 1: Remove the public reset-state behavior

**Files:**
- Modify: `load-test/tests/run-window-test.sh:100-114`
- Create: `load-test/tests/kafka-lag-preflight-test.sh`
- Modify: `load-test/run-load-test.sh:24-26,53,71-75,237-267,670-687,717-751,958-963`
- Delete: `load-test/tests/reset-state-test.sh`
- Modify: `load-test/tests/observable-outcome-flow-test.sh:28`
- Modify: `load-test/tests/runner-exit-test.sh:52`

**Interfaces:**
- Consumes: existing `parse_args`, `assert_no_initial_kafka_lag`, `run_preflight_checks` and `provision_funds_if_enabled` shell functions.
- Produces: public runner syntax without `--reset-state` or `--no-reset-state`; existing lag and provisioning behavior remains unchanged.

- [x] **Step 1: Change the parser test to reject both removed flags**

Replace the assertions that toggle `RESET_TEST_STATE` with:

```bash
for removed_flag in --reset-state --no-reset-state; do
    if (RUN_TAG=""; parse_args "$removed_flag" baseline) >/dev/null 2>&1; then
        echo "$removed_flag should not be accepted after PostgreSQL state cleanup removal" >&2
        exit 1
    fi
done
```

- [x] **Step 2: Run the focused test and verify that the new expectation fails**

Run:

```bash
bash load-test/tests/run-window-test.sh
```

Expected: exit status `1` with `--reset-state should not be accepted after PostgreSQL state cleanup removal`, because the runner still accepts the legacy flag.

- [x] **Step 3: Add characterization coverage for the retained Kafka-lag heuristic**

Create `load-test/tests/kafka-lag-preflight-test.sh` with isolated child shells so `set -e` preserves unreadable-lag failures:

```bash
#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

run_preflight() {
    SPI_LAG="$1" GATEWAY_LAG="$2" ROOT_DIR="$ROOT_DIR" bash -c '
set -euo pipefail
source "${ROOT_DIR}/run-load-test.sh"
current_spi_input_lag() {
    [[ "$SPI_LAG" != unreadable ]] || return 1
    printf "%s\n" "$SPI_LAG"
}
current_notification_gateway_lag() {
    [[ "$GATEWAY_LAG" != unreadable ]] || return 1
    printf "%s\n" "$GATEWAY_LAG"
}
assert_no_initial_kafka_lag
'
}

run_preflight 0 0

for fixture in "1 0" "0 1" "unreadable 0" "0 unreadable"; do
    read -r spi_lag gateway_lag <<< "$fixture"
    if run_preflight "$spi_lag" "$gateway_lag" >/dev/null 2>&1; then
        echo "Kafka preflight accepted spi=$spi_lag gateway=$gateway_lag" >&2
        exit 1
    fi
done
```

Run:

```bash
bash load-test/tests/kafka-lag-preflight-test.sh
```

Expected: exit status `0`, characterizing zero-lag acceptance and nonzero/unreadable-lag rejection before the refactor.

- [x] **Step 4: Remove reset-state parsing, state and SQL from the runner**

Apply these exact behavioral changes to `load-test/run-load-test.sh`:

```text
Remove POSTGRES_CONTAINER, POSTGRES_USER and POSTGRES_DB from the runner header.
Remove RESET_TEST_STATE.
Remove --reset-state and --no-reset-state from usage and parse_args.
Remove reset-state logging from log_selected_options.
Delete reset_persistent_test_state_if_enabled in full.
Remove its call between run_preflight_checks and provision_funds_if_enabled.
Do not alter --postgres-statements or load-test/scripts/postgres-statements.sh.
```

The active preparation sequence must become:

```bash
run_preflight_checks
provision_funds_if_enabled "$target_dir"
start_optional_diagnostics "$target_dir"
```

- [x] **Step 5: Remove tests and stubs that describe the deleted function**

Delete `load-test/tests/reset-state-test.sh`. Remove only these no-op declarations from the two runner-flow tests:

```bash
reset_persistent_test_state_if_enabled() { :; }
```

Do not change their remaining test doubles or expectations.

- [x] **Step 6: Run the focused parser, preflight and runner-flow tests**

Run:

```bash
bash load-test/tests/run-window-test.sh
bash load-test/tests/kafka-lag-preflight-test.sh
bash load-test/tests/observable-outcome-flow-test.sh
bash load-test/tests/runner-exit-test.sh
```

Expected: all four scripts exit `0`; both reset-state flags are rejected as unknown options, the current Kafka-lag decisions remain characterized, and runner success, failure and diagnostic collection behavior remain covered.

- [x] **Step 7: Confirm the remaining PostgreSQL references belong only to diagnostics**

Run:

```bash
rg -n '(POSTGRES_CONTAINER|POSTGRES_USER|POSTGRES_DB|RESET_TEST_STATE|reset-state|reset_persistent_test_state|reset-test-state\.log)' load-test --glob '!results/**'
```

Expected: production matches only in `load-test/scripts/postgres-statements.sh` for the three `POSTGRES_*` diagnostic settings; `run-window-test.sh` still names both removed flags to assert their rejection, and no reset-state implementation match remains.

### Task 2: Document the accepted heuristic and verify the repository

**Files:**
- Modify: `docs/board/Atividades/agora/cenarios-realistas-reprocessamento-load-tool.md:38-47`
- Preserve: `docs/superpowers/specs/2026-08-11-load-test-kafka-lag-quiescence-design.md`

**Interfaces:**
- Consumes: the runner behavior completed in Task 1.
- Produces: current task status that accurately states the lack of database cleanup and the best-effort nature of the preflight.

- [x] **Step 1: Record the runner decision in the active workload task**

Add this completed-state bullet without changing unrelated slices:

```markdown
- o runner não trunca mais estado persistente antes dos runs: o preflight usa somente o lag atual dos três consumer groups Kafka como heurística best-effort, sem alegar quiescência forte ou detectar trabalho residual em outbox/delivery.
```

- [x] **Step 2: Run every load-test shell test**

Run:

```bash
for test_file in load-test/tests/*.sh; do
    bash "$test_file"
done
```

Expected: every remaining script exits `0`.

- [x] **Step 3: Run the Go load-tool suite**

Run:

```bash
cd load-test/go-loadtool
go test ./...
```

Expected: every package passes. No Go source changes are expected, but this protects the complete run/report contract.

- [x] **Step 4: Run syntax and whitespace verification**

Run from the repository root:

```bash
bash -n load-test/run-load-test.sh load-test/tests/*.sh
git diff --check
```

Expected: both commands exit `0` with no output.

- [x] **Step 5: Review the final uncommitted diff**

Run:

```bash
git status --short
git diff -- load-test/run-load-test.sh load-test/tests docs/board/Atividades/agora/cenarios-realistas-reprocessamento-load-tool.md
```

Expected: the diff contains only the reset-state removal, its test updates, and the documentation change. Do not stage or commit any file.
