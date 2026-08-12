# Load-test Environment Preparation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract Kafka quiescence and mandatory profile-derived fund provisioning into one internal `prepare-environment.sh` command invoked automatically by the public runner.

**Architecture:** `go-loadtool validate-profile` remains the authoritative resolver and persists `execution-plan.json` in the run directory. The internal preparation script validates the plan fields it consumes, checks Kafka lag, and only then invokes the existing funding API adapter. `run-load-test.sh` keeps public orchestration but no longer knows Kafka topology or funding instructions.

**Tech Stack:** Bash, Python 3 JSON parsing, Docker Kafka CLI, existing Go load-tool.

## Global Constraints

- Keep `run-load-test.sh` as the only public entry point.
- Make environment preparation automatic and mandatory.
- Treat Kafka lag as a best-effort heuristic, not proof of quiescence.
- Keep semantic profile validation and funding resolution authoritative in Go.
- Remove both `--provision-funds` and `--no-provision-funds`.
- Keep certificate generation, Grafana, SPI trace, JFR and PostgreSQL statement diagnostics outside this extraction.
- Do not commit or stage changes.

---

### Task 1: Characterize the internal preparation command

**Files:**
- Create: `load-test/tests/prepare-environment-test.sh`
- Modify: `load-test/tests/kafka-lag-preflight-test.sh` (remove after coverage moves)

**Interfaces:**
- Consumes: `prepare-environment.sh --run-dir DIR`, `DIR/execution-plan.json`, an internal `PROVISION_FUNDS_SCRIPT` override and a fake `docker` on `PATH`.
- Produces: behavioral coverage for validation-before-side-effects, Kafka lag decisions, exact funding arguments and failure propagation.

- [x] Write a shell test with a literal execution-plan fixture containing two scenarios and hand-derived expected provisioning calls.
- [x] Prove missing/malformed plans fail before fake Docker or funding calls.
- [x] Prove nonzero and unreadable SPI/gateway lag fail before funding.
- [x] Prove zero lag provisions payer and receiver ranges with the resolved balances and reset behavior.
- [x] Prove a funding-adapter failure is returned.
- [x] Run the test and observe failure because `prepare-environment.sh` does not exist.

### Task 2: Implement the internal preparation command

**Files:**
- Create: `load-test/scripts/prepare-environment.sh`
- Delete: `load-test/tests/kafka-lag-preflight-test.sh`

**Interfaces:**
- Consumes: `--run-dir DIR` and the fixed `execution-plan.json` bundle member.
- Produces: `check_kafka_quiescence`, `provision_profile_funding` and a guarded `main` entry point.

- [x] Parse exactly `--run-dir DIR` and require a regular `execution-plan.json`.
- [x] Parse and structurally validate every consumed scenario field before any external command; store normalized provisioning records in memory.
- [x] Move Kafka container, broker, topics, consumer groups, timeout and lag functions from the runner.
- [x] Run the two SPI-input checks and notification-gateway check, rejecting nonzero or unreadable lag.
- [x] Convert each resolved participant range into payer `10xxxxxx` and receiver `20xxxxxx` calls to `provision-funds.sh`.
- [x] Run the focused test until it passes, then run `bash -n` on the new script.

### Task 3: Migrate the public runner

**Files:**
- Modify: `load-test/run-load-test.sh`
- Modify: `load-test/tests/run-window-test.sh`
- Modify: `load-test/tests/profile-contract-test.sh`
- Modify: `load-test/tests/observable-outcome-flow-test.sh`
- Modify: `load-test/tests/runner-exit-test.sh`

**Interfaces:**
- Consumes: `${SCRIPTS_DIR}/prepare-environment.sh --run-dir ABSOLUTE_DIR`.
- Produces: one automatic preparation call whose output is captured in `prepare-environment.log` and whose failure prevents diagnostics and load generation.

- [x] Add failing runner-flow coverage proving preparation occurs before `go-loadtool run` and preparation failure preserves its exit status without starting diagnostics or the workload.
- [x] Add parser coverage proving both provisioning flags are rejected.
- [x] Remove Kafka constants/functions, funding state/functions and funding-specific normalized arrays from the runner.
- [x] Add `prepare_environment` to invoke the internal script once, capture its log and preserve its status.
- [x] Keep the runner's existing SPI-trace preflight separate and invoke mandatory environment preparation before optional diagnostics.
- [x] Move exact provisioning assertions out of `profile-contract-test.sh`; retain plan snapshot and certificate-allocation coverage there.
- [x] Run all focused runner tests until they pass.

### Task 4: Update documentation and verify

**Files:**
- Modify: `docs/board/Atividades/agora/cenarios-realistas-reprocessamento-load-tool.md`
- Preserve: `docs/superpowers/specs/2026-08-11-load-test-environment-preparation-design.md`

**Interfaces:**
- Consumes: completed behavior from Tasks 1–3.
- Produces: current documentation with mandatory automatic preparation and no removed flags.

- [x] Describe automatic environment preparation through the resolved plan. Preserve the unrelated `payment-service-provider/start-psp.sh --no-provision-funds` documentation.
- [x] Record the extracted preparation boundary in the active workload task without claiming strong quiescence.
- [x] Run every `load-test/tests/*.sh` script.
- [x] Run `go test ./...` from `load-test/go-loadtool`.
- [x] Run `bash -n` on the runner, load-test scripts and shell tests.
- [x] Run `git diff --check`, inspect `git status --short`, and leave all changes uncommitted.
