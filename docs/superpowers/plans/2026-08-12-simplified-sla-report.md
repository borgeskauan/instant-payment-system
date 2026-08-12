# Simplified SLA Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the redundant `sla-report.json` shape with the approved scenario-centered contract and make the public runner consume its explicit `valid` decision.

**Architecture:** Keep the existing four event CSVs and report calculation entry point. Replace only the aggregate output model: original generation remains global, payments and original `pacs.002` are grouped by scenario, replay accounting remains global, and performance remains active-window-only. Derive `valid` from every preserved functional violation and let the runner consume that boolean instead of recursively inspecting the document.

**Tech Stack:** Go standard library, Bash, Python 3 JSON parsing used by the shell runner.

## Global Constraints

- Preserve workload generation, the four current CSV schemas, the bundle layout and the public `run-load-test.sh` interface.
- Preserve `started` for initiated HTTP attempts and `accepted` for HTTP `2xx` responses.
- Keep correctness validation full-run and performance metrics active-window-only.
- Keep scenario traffic and outcomes together; keep replay accounting global.
- Keep at-least-once notification semantics: repeated compatible frames count once, absence and any contradiction are violations.
- Do not add final latency, CPU, memory or throughput gates.
- Replace the old JSON contract directly; do not add compatibility parsing, aliases or rejection tests for the removed shape.
- Leave all changes uncommitted and unstaged for review through `git diff`.

---

### Task 1: Express the new report model through failing tests

**Files:**
- Modify: `load-test/go-loadtool/internal/report/report_test.go`
- Modify: `load-test/go-loadtool/internal/report/mixed_outcomes_characterization_test.go`
- Modify: `load-test/go-loadtool/cmd/go-loadtool/run_test.go`

**Interfaces:**
- Consumes: existing `report.Build(starts, notifications, statusStarts, replays, options)`.
- Produces: compile-time expectations for `Summary.Valid`, `Generation`, scenario `Traffic`, `Outcome`, `Performance`, global `Replays`, and global `Performance`.

- [ ] Rewrite focused assertions to use the semantic fields `Valid`, `Generation`, `Scenarios[].Traffic`, `Scenarios[].Outcome`, `Scenarios[].Performance`, `Replays`, and `Performance`.
- [ ] Add explicit tests that original `pacs.002` attempts are attributed by `ScenarioName`, and that a non-`2xx` or deadline-violating status increments only its scenario's violation total.
- [ ] Protect logical contradiction semantics: one matching and one incompatible payer notification produce `matched: 1`, `contradictory: 1`, scenario violations, and `valid: false`.
- [ ] Protect rounding of published floating-point metrics to three decimal places.
- [ ] Update the real renderer command test to decode `valid` and `generation`, not removed `run` or `load_generation` blocks.
- [ ] Run `go test ./internal/report ./cmd/go-loadtool` from `load-test/go-loadtool` and confirm the tests fail against the old model.

### Task 2: Replace the report output model and calculations

**Files:**
- Modify: `load-test/go-loadtool/internal/report/report.go`

**Interfaces:**
- Consumes: current event types, `config.Scenario`, `config.Replay`, and authoritative `runwindow.Window`.
- Produces: `Build(...) (Summary, error)` with the approved JSON model; `Print(...) error` remains unchanged for callers.

- [ ] Replace the output structs with this root shape:

```go
type Summary struct {
    Valid       bool               `json:"valid"`
    Generation  GenerationSummary  `json:"generation"`
    Scenarios   []ScenarioSummary  `json:"scenarios"`
    Replays     ReplaySummary      `json:"replays"`
    Performance PerformanceSummary `json:"performance"`
}
```

- [ ] Define typed nested structs for `traffic.payments`, `traffic.pacs002`, `outcome.expected`, logical outcome counts, scenario performance, global active TPS and latency. Retain replay `Violations` while renaming `Attempted` to `Started`.
- [ ] Initialize scenario identity, configured `share`, and expected status/reason codes from profile configuration without copying HTTP or delivery-semantics declarations into the JSON.
- [ ] Aggregate full-run payment attempts by the `ScenarioName` in each start and full-run original `pacs.002` attempts by the `ScenarioName` in each status-start event. Reject unknown or empty scenario names as invalid report inputs.
- [ ] Preserve HTTP and deadline validation while accumulating original `pacs.002` failures into the owning scenario's `Violations`.
- [ ] Replace `observed`, separate mismatch counters and aggregate transaction aliases with logical `matched`, `missing` and `contradictory` counts. Increment contradiction once per payment even when both status and reasons are incompatible.
- [ ] Preserve all existing replay validation and rates, exposing replay attempts as `started`.
- [ ] Move active-window metrics into global `performance` and scenario metrics into `scenarios[].performance`; remove configuration, diagnostic and alias blocks.
- [ ] Round rates and latency values to three decimal places with `math.Round(value*1000) / 1000`.
- [ ] Set `summary.Valid` after all aggregation only when generation, every scenario and both replay types have zero violations.
- [ ] Run `gofmt` on changed Go files and run `go test ./internal/report ./cmd/go-loadtool` until green.

### Task 3: Make the runner consume the explicit validity decision

**Files:**
- Modify: `load-test/run-load-test.sh`
- Modify: `load-test/tests/runner-exit-test.sh`

**Interfaces:**
- Consumes: root JSON boolean `valid` in `sla-report.json`.
- Produces: `validate_sla_report PATH` succeeds only for `{"valid": true}` and causes the public runner to return nonzero for `false`, absent, non-boolean or malformed values.

- [ ] Rewrite the shell fixtures so valid and violating fake runs write `{"valid":true}` and `{"valid":false}` respectively.
- [ ] Add malformed fixtures for missing `valid`, string `"true"`, numeric `1`, JSON arrays and invalid JSON.
- [ ] Run `load-test/tests/runner-exit-test.sh` and confirm it fails against recursive `violations` discovery.
- [ ] Replace the recursive Python traversal in `validate_sla_report` with strict root-object and boolean validation.
- [ ] Run `load-test/tests/runner-exit-test.sh` and `bash -n load-test/run-load-test.sh load-test/tests/runner-exit-test.sh` until green.

### Task 4: Update the active project contract and verify the whole slice

**Files:**
- Modify: `docs/board/Atividades/agora/cenarios-realistas-reprocessamento-load-tool.md`
- Verify: `docs/superpowers/specs/2026-08-12-simplified-sla-report-design.md`

**Interfaces:**
- Consumes: implemented JSON model and runner behavior.
- Produces: current task state that names the simplified report as the active contract without promising legacy compatibility.

- [ ] Add a completed Fatia 2C.7 describing the scenario-centered report, global generation and replays, active-window performance, explicit `valid`, retained CSV auditability, and removed duplicate blocks.
- [ ] Update state bullets that currently describe recursive `violations` scanning or the old aggregate report shape.
- [ ] Run the complete Go suite from `load-test/go-loadtool` with `go test -count=1 ./...`.
- [ ] Run every existing load-test shell test with `for test_script in load-test/tests/*.sh; do bash "$test_script"; done`.
- [ ] Run `bash -n load-test/run-load-test.sh load-test/tests/*.sh` and `git diff --check`.
- [ ] Inspect one rendered JSON document and confirm the old top-level keys are absent and the approved keys are present.
- [ ] Review `git diff` and `git status --short`; do not stage or commit any file.
