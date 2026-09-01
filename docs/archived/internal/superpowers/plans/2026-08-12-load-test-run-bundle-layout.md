# Load-Test Run Bundle Layout — Implementation Plan

**Goal:** Reorganize every load-test run into a fixed, readable bundle without changing workload semantics, CSV schemas, result-directory naming, or the public runner contract.

**Architecture:** Make `internal/runbundle.Layout` the typed source of truth for Go-owned paths. The shell runner owns workspace preparation, logs, and optional diagnostics; `go-loadtool run` owns event artifacts, the run window, and the report. Both use the same fixed relative layout, and the runner records those relative paths in `run-window.json`.

**Compatibility:** Preserve current profile names, runner arguments, workload behavior, report semantics, and CSV-format compatibility. Do not support legacy artifact paths or create empty placeholders.

---

## Task 1: Introduce the typed bundle layout

**Files:**

- Modify: `load-test/go-loadtool/internal/runbundle/runbundle.go`
- Modify: `load-test/go-loadtool/internal/runbundle/runbundle_test.go`
- Modify: `load-test/go-loadtool/cmd/go-loadtool/run.go`
- Modify: `load-test/go-loadtool/cmd/go-loadtool/run_test.go`
- Modify: `load-test/go-loadtool/internal/sim/simulator.go`
- Modify: affected simulator tests

1. Change layout tests to expect `inputs/`, `events/`, `logs/`, and `diagnostics/` paths and the semantic CSV filenames.
2. Require both `inputs/profile.json` and `inputs/execution-plan.json` before Go-owned output begins.
3. Run focused tests and confirm they fail for the old layout.
4. Implement the new typed layout and make `PrepareOutputs` create only `events/`.
5. Rename simulator-generated CSV files to `pacs008-starts.csv`, `pacs002-starts.csv`, `notifications.csv`, and `replays.csv`.
6. Update `go-loadtool run` to consume the new input paths and render from the new event paths.
7. Run focused Go tests until green.

## Task 2: Migrate the runner workspace and manifest

**Files:**

- Modify: `load-test/run-load-test.sh`
- Modify: `load-test/prepare-environment.sh`
- Modify: `load-test/tests/run-window-test.sh`
- Modify: `load-test/tests/profile-contract-test.sh`
- Modify: `load-test/tests/prepare-environment-test.sh`
- Modify: other affected runner shell tests

1. Update shell tests to require profile and execution-plan snapshots under `inputs/`, event paths under `events/`, preparation output under `logs/`, and `logs/loadtool.log` containing both stdout and stderr.
2. Run focused shell tests and confirm they fail for the old layout.
3. Make workspace preparation create `inputs/` and `logs/`, then copy the snapshots byte-for-byte into `inputs/`.
4. Make environment preparation consume `inputs/execution-plan.json` and log to `logs/prepare-environment.log`.
5. Make the public runner capture `go-loadtool run` stdout and stderr in `logs/loadtool.log` while preserving the Go command's exit status.
6. Update `run-window.json` artifact metadata to the new fixed relative paths and semantic names.
7. Run focused shell tests until green.

## Task 3: Separate diagnostic data from diagnostic logs

**Files:**

- Modify: `load-test/run-load-test.sh`
- Modify: `load-test/tests/jfr-diagnostics-test.sh`
- Modify: affected SPI trace and PostgreSQL diagnostic tests

1. Update tests so SPI trace and PostgreSQL CSV exports land in `diagnostics/`, their command logs land in `logs/`, JFR recordings land in `diagnostics/jfr/`, and JFR command logs land in `logs/jfr/`.
2. Run focused tests and confirm they fail for the old paths.
3. Create optional diagnostic directories only when the corresponding collection is enabled and reached.
4. Route each produced artifact to its approved boundary without changing collection behavior.
5. Keep the same retention behavior on success and failure; do not synthesize missing artifacts.
6. Run focused diagnostic tests until green.

## Task 4: Documentation and full verification

**Files:**

- Modify: current load-test documentation and active workload-matrix task where artifact paths are described

1. Update current documentation to show the fixed bundle tree and explain artifact ownership and retention.
2. Run `gofmt` on changed Go files.
3. Run focused tests, then `go test ./...`, all existing load-test shell tests, `bash -n` on changed scripts, and `git diff --check`.
4. Resolve and verify the exact `load-test/results` path, then permanently remove the existing old run results as explicitly authorized.
5. Execute one short functional smoke through `run-load-test.sh` and inspect the resulting bundle.
6. Re-run the relevant automated checks if smoke-driven fixes alter code.
7. Report the final layout, verification evidence, smoke outcome, and deletion of the old unrecoverable results. Leave every change uncommitted and unstaged.
