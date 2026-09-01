# Current-only Load-tool Artifact Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove compatibility-only code for artifact contracts that predate the current load-test bundle.

**Architecture:** Keep the active pipeline unchanged: the simulator writes four current CSV artifacts, then the reporter reads those same files and atomically publishes the report. Simplify the event and run-window readers to a single current schema and expose one report builder/printer that always receives all four populations.

**Tech Stack:** Go, encoding/csv, encoding/json, existing load-tool shell tests.

## Global Constraints

- Support only the current run bundle and artifact schemas.
- Do not add tests or fixtures dedicated to accepting or rejecting historical formats.
- Preserve workload generation, report JSON, CSV schemas, audit artifacts and the public runner interface.
- Keep all changes unstaged and uncommitted in the current `load-testing-profiles` branch.

---

### Task 1: Simplify the event artifact contract

**Files:**

- Modify: `load-test/go-loadtool/internal/events/events.go`
- Modify: `load-test/go-loadtool/internal/events/events_test.go`
- Modify: current callers and fixtures that still initialize `Replay.PayerISPB`

**Interfaces:**

- Consumes: the current CSV headers emitted by `StartWriter`, `StatusStartWriter`, `NotificationWriter` and `ReplayWriter`.
- Produces: a `Replay` model with `SenderISPB` as its only sender identity and strict current-header readers.

- [ ] Remove tests and fixtures whose only purpose is a former CSV header.
- [ ] Update current replay fixtures to populate `SenderISPB`.
- [ ] Run `go test ./internal/events ./internal/report ./internal/sim` to establish the current-contract baseline.
- [ ] Remove legacy header constants, conditional parsing and the `Replay.PayerISPB` writer/report fallbacks.
- [ ] Run the focused packages again and fix only current-contract callers.

### Task 2: Simplify the authoritative run window

**Files:**

- Modify: `load-test/go-loadtool/internal/runwindow/runwindow.go`
- Modify: `load-test/go-loadtool/internal/runwindow/runwindow_test.go`

**Interfaces:**

- Consumes: `Document` with the current `SchemaVersion` and four authoritative window timestamps.
- Produces: `Resolve(Document, profileName, warmup, duration, drain, replay) (Window, error)` with no schema migration branch.

- [ ] Remove the fixture and test dedicated to schema zero.
- [ ] Run `go test ./internal/runwindow` to establish the current-schema baseline.
- [ ] Remove legacy timestamp fields and make `Resolve` validate and return only the current window.
- [ ] Run `go test ./internal/runwindow ./cmd/go-loadtool` and confirm current run-window enrichment still works.

### Task 3: Make all four report populations explicit

**Files:**

- Modify: `load-test/go-loadtool/internal/report/report.go`
- Modify: `load-test/go-loadtool/internal/report/report_test.go`
- Modify: `load-test/go-loadtool/cmd/go-loadtool/run.go`
- Modify: `load-test/go-loadtool/cmd/go-loadtool/run_test.go` if required by the internal API rename

**Interfaces:**

- Produces: `Build(starts, notifications, statusStarts, replays, options) (Summary, error)`.
- Produces: `Print(pacs008StartsPath, notificationsPath, pacs002StartsPath, replaysPath, options, output) error`.

- [ ] Change current tests and the command caller to the single `Build`/`Print` API and run focused tests to observe the compile failure against the old API.
- [ ] Replace incremental builder entry points with `Build` and replace `PrintWithArtifacts` with `Print`.
- [ ] Make `Print` unconditionally read all four paths; an empty current artifact remains a valid header-only CSV, not an omitted path.
- [ ] Run `go test ./internal/report ./cmd/go-loadtool` until green.

### Task 4: Align documentation and verify the whole slice

**Files:**

- Modify: `docs/board/Atividades/agora/cenarios-realistas-reprocessamento-load-tool.md`
- Modify: `docs/superpowers/specs/2026-08-11-load-test-run-bundle-layout-design.md`

**Interfaces:**

- Produces: documentation that describes auditability and in-run reporting without historical/offline regeneration promises.

- [ ] Remove statements that imply regeneration of historical reports and record the current-only contract in the active task.
- [ ] Run `gofmt` on changed Go files.
- [ ] Run `env GOCACHE=/tmp/instant-payment-system-go-cache go test -count=1 ./...` in `load-test/go-loadtool`.
- [ ] Run every `load-test/tests/*.sh` script.
- [ ] Run `bash -n load-test/run-load-test.sh load-test/scripts/*.sh load-test/tests/*.sh`.
- [ ] Run `git diff --check` and confirm `git diff --cached --exit-code` remains clean.
