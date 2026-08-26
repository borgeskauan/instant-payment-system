# Rust Load-Tool Boundaries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Rust the only load-tool implementation, with Cargo-enforced separation between workload generation and post-run reporting, while removing retrospective report work from the generator hot path.

**Architecture:** A small `rust-loadtool` composition root depends on sibling `loadtool-contract`, `loadtool-generator`, and `loadtool-report` crates. The generator writes facts and self-qualification metrics to the run bundle, then is fully shut down and dropped before the reporter reads those persisted artifacts. Shell remains responsible for environment preparation and diagnostics; Go remains an oracle until the final cutover and is then removed.

**Tech Stack:** Rust 1.89, Cargo workspace, Tokio, Hyper HTTP/2, Tonic gRPC, Serde/serde_json, csv, time, hdrhistogram, Bash runner tests, temporary Go parity oracle.

**Spec:** `docs/superpowers/specs/2026-08-26-rust-load-tool-boundaries-design.md`

## Global Constraints

- `loadtool-generator` must never depend on `loadtool-report`.
- The persisted run bundle is the only handoff from generation to reporting; no callback, channel, shared accumulator, or runtime reference may cross the boundary.
- Report execution begins only after Pulls, `TaskTracker`, recorder, and generator runtime have stopped.
- Active/drain payer outcomes are recorded but not interpreted by the generator; warmup outcome matching remains only because it controls the active gate.
- Global payment state contains only `COMMITTED` and `PACS002_CLAIMED`; warmup-only outcome state is sized only for warmup slots.
- Keep absolute no-catch-up pacing, the precomputed planner, bounded queues, one native pacer, one single-writer recorder, fixed drain, and existing workload semantics.
- Preserve `profile.json`, `execution-plan.json`, four event CSVs, `run-window.json`, `generator-metrics.json`, and `sla-report.json` contracts unless a field is explicitly identified below as duplicate post-run aggregation.
- Preserve public runner exit codes: `0` for a valid completed run, `1` for a completed invalid report, and `2` for operational failure.
- Do not add a standalone historical `report` command, an engine selector, worker pools, actors, binary evidence, or runtime tuning knobs.
- Keep stack preparation, certificate generation, funding, JFR, SPI trace, PostgreSQL diagnostics, and container diagnostics in shell.

---

## File Structure

The final Rust workspace is:

```text
load-test/rust-loadtool/
├── Cargo.toml                         # workspace + rust-loadtool application package
├── Cargo.lock
├── rust-toolchain.toml
├── src/
│   ├── lib.rs                        # application orchestration exposed for CLI tests
│   ├── main.rs                       # clap and public exit behavior only
│   └── profile.rs                    # profile resolution, validation, normalization
├── crates/
│   ├── loadtool-contract/
│   │   ├── Cargo.toml
│   │   └── src/
│   │       ├── lib.rs
│   │       ├── bundle.rs             # fixed run-dir layout and atomic report publication
│   │       ├── event.rs              # persisted CSV row contracts and strict readers
│   │       ├── generator_metrics.rs  # persisted generator self-metrics document
│   │       ├── model.rs              # profile and normalized execution-plan documents
│   │       └── run_window.rs         # authoritative execution window document
│   ├── loadtool-generator/
│   │   ├── Cargo.toml
│   │   ├── build.rs                  # notification proto compilation
│   │   └── src/                      # current pacing/networking/workload implementation
│   └── loadtool-report/
│       ├── Cargo.toml
│       └── src/
│           ├── lib.rs                # bundle-to-report entry point
│           ├── generation.rs         # rolling throughput
│           ├── outcome.rs            # payer outcomes and latency
│           ├── replay.rs             # replay invariants
│           └── summary.rs            # serialized SLA report contract
├── benches/pacer_hot_path.rs
└── tests/
```

The current generator source files move mechanically to `crates/loadtool-generator/src/`; do not split them merely to reduce file length. Existing integration tests remain at the root and import the owning crate explicitly.

---

### Task 1: Establish Cargo-enforced crate boundaries

**Files:**
- Modify: `load-test/rust-loadtool/Cargo.toml`
- Move: `load-test/rust-loadtool/build.rs` → `load-test/rust-loadtool/crates/loadtool-generator/build.rs`
- Create: `load-test/rust-loadtool/crates/loadtool-contract/Cargo.toml`
- Create: `load-test/rust-loadtool/crates/loadtool-contract/src/lib.rs`
- Move: `load-test/rust-loadtool/src/bundle.rs` → `load-test/rust-loadtool/crates/loadtool-contract/src/bundle.rs`
- Move: `load-test/rust-loadtool/src/event.rs` → `load-test/rust-loadtool/crates/loadtool-contract/src/event.rs`
- Move: `load-test/rust-loadtool/src/generator_metrics.rs` → `load-test/rust-loadtool/crates/loadtool-contract/src/generator_metrics.rs`
- Move: `load-test/rust-loadtool/src/model.rs` → `load-test/rust-loadtool/crates/loadtool-contract/src/model.rs`
- Move: `load-test/rust-loadtool/src/run_window.rs` → `load-test/rust-loadtool/crates/loadtool-contract/src/run_window.rs`
- Create: `load-test/rust-loadtool/crates/loadtool-generator/Cargo.toml`
- Create: `load-test/rust-loadtool/crates/loadtool-generator/src/lib.rs`
- Move: remaining generator modules from `load-test/rust-loadtool/src/` to `load-test/rust-loadtool/crates/loadtool-generator/src/`
- Modify: `load-test/rust-loadtool/src/lib.rs`
- Modify: `load-test/rust-loadtool/src/main.rs`
- Modify: `load-test/rust-loadtool/benches/pacer_hot_path.rs`
- Modify: `load-test/rust-loadtool/tests/*.rs`

**Interfaces:**
- Produces: `loadtool_contract::{bundle, event, generator_metrics, model, run_window}`.
- Produces: `loadtool_generator::run(layout: BundleLayout, prepared: PreparedRun, options: SimulationOptions) -> anyhow::Result<()>`.
- Constraint: `cargo tree -p loadtool-generator` contains no `loadtool-report` package.

- [ ] **Step 1: Add a dependency-boundary test before moving code**

Create `load-test/rust-loadtool/tests/dependency_boundary.rs`:

```rust
#[test]
fn generator_manifest_has_no_report_dependency() {
    let manifest = std::fs::read_to_string(
        concat!(env!("CARGO_MANIFEST_DIR"), "/crates/loadtool-generator/Cargo.toml"),
    )
    .expect("read generator manifest");
    assert!(!manifest.contains("loadtool-report"));
}
```

- [ ] **Step 2: Run the test to verify the target boundary does not yet exist**

Run: `cd load-test/rust-loadtool && cargo test --test dependency_boundary`

Expected: FAIL because `crates/loadtool-generator/Cargo.toml` is absent.

- [ ] **Step 3: Convert the root manifest into a workspace and application package**

Keep package name `rust-loadtool`, add workspace members for the two initial crates, and replace runtime dependencies moved with the generator by path dependencies:

```toml
[workspace]
members = [
  ".",
  "crates/loadtool-contract",
  "crates/loadtool-generator",
]
resolver = "2"

[dependencies]
anyhow = "1.0"
clap = { version = "4.5", features = ["derive"] }
loadtool-contract = { path = "crates/loadtool-contract" }
loadtool-generator = { path = "crates/loadtool-generator" }
tokio = { version = "1.47", features = ["macros", "rt-multi-thread"] }
```

Move each dependency to the crate that actually uses it. Keep application-binary reproducibility in the single checked-in workspace `Cargo.lock`.

- [ ] **Step 4: Move neutral persisted contracts without changing their JSON/CSV representation**

Expose only focused modules from `loadtool-contract/src/lib.rs`:

```rust
pub mod bundle;
pub mod event;
pub mod generator_metrics;
pub mod model;
pub mod run_window;
```

Change imports in generator code from `crate::model`/`crate::event` to `loadtool_contract::model`/`loadtool_contract::event`. Move proto compilation and `notification_proto` into `loadtool-generator`.

- [ ] **Step 5: Make the application the composition root**

Change root `src/lib.rs` to expose an application entry point without re-exporting internal generator modules:

```rust
pub async fn simulate(args: SimulateOptions) -> anyhow::Result<()> {
    let layout = loadtool_contract::bundle::BundleLayout::resolve(&args.run_dir)?;
    let prepared = layout.load_prepared()?;
    loadtool_generator::run(layout, prepared, args.into()).await
}
```

Keep the existing `simulate` CLI behavior in this task. It is removed only during final cutover.

- [ ] **Step 6: Update integration tests and benchmark imports**

Use `loadtool_contract::...` for bundle/model contracts and `loadtool_generator::...` for planner, pacer, HTTP, replay, and simulator APIs. Do not add compatibility re-exports to `rust_loadtool`.

- [ ] **Step 7: Verify the mechanical boundary refactor**

Run:

```bash
cd load-test/rust-loadtool
cargo fmt --all --check
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
cargo tree -p loadtool-generator
```

Expected: all existing 56+ tests and the new boundary test pass; dependency tree contains no report crate.

- [ ] **Step 8: Commit**

```bash
git add load-test/rust-loadtool
git commit -m "refactor(load-test): isolate the Rust load generator"
```

---

### Task 2: Remove retrospective interpretation from the generator

**Files:**
- Modify: `load-test/rust-loadtool/crates/loadtool-contract/src/generator_metrics.rs`
- Modify: `load-test/rust-loadtool/crates/loadtool-generator/src/payment_state.rs`
- Modify: `load-test/rust-loadtool/crates/loadtool-generator/src/phase_tracker.rs`
- Modify: `load-test/rust-loadtool/crates/loadtool-generator/src/recorder.rs`
- Modify: `load-test/rust-loadtool/crates/loadtool-generator/src/simulator.rs`
- Test: `load-test/rust-loadtool/tests/lifecycle_contract.rs`
- Test: `load-test/rust-loadtool/tests/evidence_contract.rs`
- Test: `load-test/rust-loadtool/tests/payment_state.rs` (create if current unit coverage is embedded elsewhere)

**Interfaces:**
- Produces: `PaymentStates::{commit, is_committed, claim_pacs002}` only.
- Produces: `WarmupOutcomes::observe(sequence, matches_expected) -> Option<WarmupObservation>` inside the warmup gate module.
- Produces: `EventRecorder::close(self) -> anyhow::Result<RecorderSummary>` containing only generator-owned HTTP start lateness.
- Preserves: persisted CSV columns and generator validity from pacing/admission/capacity violations.

- [ ] **Step 1: Write failing payment-state and warmup-scope tests**

Assert that global payment state exposes only causal operations and that warmup outcome state is independently sized:

```rust
let states = PaymentStates::new(2);
assert!(states.commit(0));
assert!(states.claim_pacs002(0));
assert!(!states.claim_pacs002(0));

let outcomes = WarmupOutcomes::new(1);
assert_eq!(outcomes.observe(0, true), WarmupObservation::MatchedFirst);
assert_eq!(outcomes.observe(0, true), WarmupObservation::MatchedAgain);
assert!(outcomes.observe(1, true).is_none());
```

Add a lifecycle test proving a contradictory active notification is still recorded and does not become a generator failure, while the same contradiction during warmup fails the gate.

- [ ] **Step 2: Run focused tests and confirm failure**

Run:

```bash
cd load-test/rust-loadtool
cargo test --test lifecycle_contract
cargo test --test payment_state
```

Expected: FAIL because outcome flags still live in `PaymentStates` and all payer outcomes are interpreted.

- [ ] **Step 3: Restrict global payment state to causal bits**

Remove `EXPECTED_OUTCOME_SEEN`, `CONTRADICTION_SEEN`, `OutcomeObservation`, and `observe_outcome` from `payment_state.rs`. Add warmup-only atomic outcome storage next to `PhaseTracker`, not to a generic payment lifecycle:

```rust
pub struct WarmupOutcomes {
    states: Vec<AtomicU8>,
}

pub enum WarmupObservation {
    MatchedFirst,
    MatchedAgain,
    ContradictionFirst,
    ContradictionAgain,
}
```

`observe` returns `None` for a sequence outside the warmup population. Only `MatchedFirst` completes the pre-registered outcome obligation; `ContradictionFirst` fails the warmup gate.

- [ ] **Step 4: Stop interpreting active and drain outcomes**

In `process_pulled_notification`, always record a payer PACS.002. Perform scenario lookup, reason-code comparison, and warmup outcome observation only when `sequence < warmup_slots`:

```rust
runtime.recorder.record(notification_event)?;
if sequence < runtime.warmup_slots {
    runtime.observe_warmup_outcome(sequence, participant, status, reason_codes)?;
}
```

Receiver PACS.008 handling and the `PACS002_CLAIMED` operation remain unchanged because they create causal workload.

- [ ] **Step 5: Remove duplicate HTTP aggregation from the recorder**

Delete the `http_duration` histogram from `record_loop`. Preserve the existing HTTP start-lateness summary because it measures generator admission rather than SPI outcome latency. Make `write_event` serialize facts and update only start lateness for PACS.008:

```rust
pub struct RecorderSummary {
    pub http_start_lateness: HistogramSummary,
}

pub fn close(mut self) -> Result<RecorderSummary> {
    // join worker and surface writer failure
}
```

Remove `http_duration` from `GeneratorMetrics`. Preserve start/done timestamps exactly in CSV; Task 4 derives outcome latency after generation.

- [ ] **Step 6: Run focused and full Rust verification**

Run:

```bash
cd load-test/rust-loadtool
cargo test --test lifecycle_contract --test evidence_contract --test payment_state
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
```

Expected: active outcomes require no report comparison in generator; warmup gate behavior and CSV contract remain covered.

- [ ] **Step 7: Run the pacer microbenchmark as a regression check**

Run: `cd load-test/rust-loadtool && cargo bench --bench pacer_hot_path`

Expected: benchmark completes with the same planned/admitted semantics; no threshold is invented from one local sample.

- [ ] **Step 8: Commit**

```bash
git add load-test/rust-loadtool
git commit -m "perf(load-test): keep reporting out of load generation"
```

---

### Task 3: Add strict post-run artifact readers

**Files:**
- Modify: `load-test/rust-loadtool/Cargo.toml`
- Create: `load-test/rust-loadtool/crates/loadtool-report/Cargo.toml`
- Create: `load-test/rust-loadtool/crates/loadtool-report/src/lib.rs`
- Create: `load-test/rust-loadtool/crates/loadtool-report/src/generation.rs`
- Modify: `load-test/rust-loadtool/crates/loadtool-contract/src/bundle.rs`
- Modify: `load-test/rust-loadtool/crates/loadtool-contract/src/event.rs`
- Modify: `load-test/rust-loadtool/crates/loadtool-contract/src/generator_metrics.rs`
- Modify: `load-test/rust-loadtool/crates/loadtool-contract/src/run_window.rs`
- Create: `load-test/rust-loadtool/tests/report_artifacts.rs`
- Create: `load-test/testdata/report-parity/inputs/profile.json`
- Create: `load-test/testdata/report-parity/inputs/execution-plan.json`
- Create: `load-test/testdata/report-parity/events/*.csv`
- Create: `load-test/testdata/report-parity/run-window.json`
- Create: `load-test/testdata/report-parity/diagnostics/loadtool/generator-metrics.json`

**Interfaces:**
- Produces: `BundleLayout::load_completed() -> Result<CompletedRun>`.
- Produces: strict CSV readers returning `Vec<Pacs008Start>`, `Vec<Pacs002Start>`, `Vec<Notification>`, and `Vec<Replay>`.
- Produces: `generation::summarize(starts: &[Pacs008Start], window: &RunWindow, offered_tps: u64, required_minimum_tps: u64) -> GenerationSummary`.

- [ ] **Step 1: Write failing strict-reader tests**

Cover the exact four headers, malformed numeric/boolean/JSON reason fields, unknown columns, missing files, run-window profile mismatch, and missing/malformed generator metrics. Include one valid completed fixture bundle.

- [ ] **Step 2: Run the tests and verify failure**

Run: `cd load-test/rust-loadtool && cargo test --test report_artifacts`

Expected: FAIL because completed-bundle readers and report crate do not exist.

- [ ] **Step 3: Add the report crate with a one-way dependency**

Add `loadtool-report` to workspace members and give it only post-processing dependencies:

```toml
[dependencies]
anyhow = "1.0"
loadtool-contract = { path = "../loadtool-contract" }
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"
```

The report crate must not depend on Hyper, Tokio, Tonic, rustls, or `loadtool-generator`.

- [ ] **Step 4: Implement strict completed-bundle loading**

Keep generator preparation validation separate from completed-run validation. `load_completed` requires all input/evidence/window/metrics files and requires `sla-report.json` to be absent before rendering.

Derive `Deserialize` for the persisted generator metrics types. CSV readers must compare headers exactly and reject `null` reason-code arrays.

- [ ] **Step 5: Port rolling one-second throughput in O(n log n)**

Sort active `request_started_at_ns` timestamps, then use the existing two-pointer semantics for every continuous one-second candidate window. Preserve half-open active bounds and three-decimal average rounding. Tests must cover edge timestamps, a gap not aligned to `activeStart`, and over-target spikes that do not compensate a below-target rolling window.

- [ ] **Step 6: Verify artifact parsing and dependency direction**

Run:

```bash
cd load-test/rust-loadtool
cargo test --test report_artifacts
cargo test -p loadtool-report
cargo tree -p loadtool-report
cargo tree -p loadtool-generator
```

Expected: report has no networking/runtime dependencies; generator has no report dependency.

- [ ] **Step 7: Commit**

```bash
git add load-test/rust-loadtool load-test/testdata/report-parity
git commit -m "feat(load-test): read completed Rust run bundles"
```

---

### Task 4: Port SLA and business-outcome reporting with Go parity

**Files:**
- Create: `load-test/rust-loadtool/crates/loadtool-report/src/outcome.rs`
- Create: `load-test/rust-loadtool/crates/loadtool-report/src/replay.rs`
- Create: `load-test/rust-loadtool/crates/loadtool-report/src/summary.rs`
- Modify: `load-test/rust-loadtool/crates/loadtool-report/src/lib.rs`
- Modify: `load-test/rust-loadtool/crates/loadtool-contract/src/bundle.rs`
- Create: `load-test/rust-loadtool/tests/report_contract.rs`
- Create: `load-test/testdata/report-parity/expected-sla-report.json`
- Create: `load-test/go-loadtool/internal/report/rust_parity_fixture_test.go` (temporary oracle test)

**Interfaces:**
- Produces: `loadtool_report::build(completed: CompletedRun) -> Result<SlaReport>`.
- Produces: `loadtool_report::write(layout: &BundleLayout) -> Result<SlaReport>`, publishing `sla-report.json` atomically without overwrite.
- Preserves: existing scenario-centered JSON keys and three-decimal rounding.

- [ ] **Step 1: Create one shared parity fixture and assert it with Go**

The fixture must contain happy-path and insufficient-funds payments, at-least-once duplicate notifications, PACS.008 and PACS.002 replays, active-window boundaries, and Pull batch counts. Add a Go test that renders the fixture and deep-compares its decoded JSON with `expected-sla-report.json`.

- [ ] **Step 2: Run the Go oracle test**

Run: `cd load-test/go-loadtool && GOPATH=/tmp/go GOCACHE=/tmp/go-build-cache go test ./internal/report -run RustParityFixture`

Expected: PASS, proving the checked-in expected document represents current behavior.

- [ ] **Step 3: Write failing Rust report-contract tests**

Deep-compare Rust output to the same expected JSON and port focused cases for:

- at-least-one matching payer notification;
- missing and contradictory status/reasons;
- HTTP non-2xx;
- replay not selected, missing, duplicate, early, late, wrong sender/scenario;
- PACS.002 created after generation end;
- Pull batch p50/p95/max and above-15 violation;
- p50/p95/p99/max interpolation and three-decimal rounding;
- generator metrics with `valid:false` forcing final `valid:false`.

- [ ] **Step 4: Run Rust tests and verify failure**

Run: `cd load-test/rust-loadtool && cargo test --test report_contract`

Expected: FAIL because report construction is incomplete.

- [ ] **Step 5: Port report semantics without adding a report command**

Implement the same top-level contract:

```rust
pub struct SlaReport {
    pub valid: bool,
    pub generation: GenerationSummary,
    pub scenarios: Vec<ScenarioSummary>,
    pub replays: ReplaySummary,
    pub notification_pull: NotificationPullSummary,
    pub performance: PerformanceSummary,
}
```

Use the earliest matching payer PACS.002 for latency, allow repeated matching deliveries, and count any mismatching status or reasons as contradictory. Performance metrics use active-window starts only; correctness validation uses the entire run.

Include generator validity only in the final `valid` conjunction. Detailed pacing violations remain in `generator-metrics.json`, not duplicated into the SLA report.

- [ ] **Step 6: Publish the report atomically**

Write pretty JSON plus trailing newline to a temporary file in the run root, `sync_all`, then use a no-overwrite publication operation. A pre-existing `sla-report.json` is an error.

- [ ] **Step 7: Verify Go/Rust parity**

Run:

```bash
cd load-test/go-loadtool
GOPATH=/tmp/go GOCACHE=/tmp/go-build-cache go test ./internal/report

cd ../rust-loadtool
cargo test -p loadtool-report
cargo test --test report_contract
cargo test --workspace
```

Expected: both implementations deep-match the shared fixture and all focused semantic tests pass.

- [ ] **Step 8: Commit**

```bash
git add load-test/rust-loadtool load-test/testdata/report-parity load-test/go-loadtool/internal/report/rust_parity_fixture_test.go
git commit -m "feat(load-test): render SLA reports in Rust"
```

---

### Task 5: Port profile validation and execution-plan normalization

**Files:**
- Create: `load-test/rust-loadtool/src/profile.rs`
- Modify: `load-test/rust-loadtool/src/lib.rs`
- Modify: `load-test/rust-loadtool/src/main.rs`
- Modify: `load-test/rust-loadtool/crates/loadtool-contract/src/model.rs`
- Modify: `load-test/rust-loadtool/crates/loadtool-generator/src/planner.rs`
- Create: `load-test/rust-loadtool/tests/profile_contract.rs`
- Modify: `load-test/rust-loadtool/tests/cli_contract.rs`
- Reference oracle: `load-test/go-loadtool/internal/config/config_test.go`
- Reference oracle: `load-test/go-loadtool/cmd/go-loadtool/main_test.go`

**Interfaces:**
- Produces: `profile::compile(profiles_dir: &Path, name: &str) -> Result<ExecutionPlan>`.
- Produces: `profile::validate_name(name: &str) -> Result<()>`.
- Produces CLI: `rust-loadtool validate-profile [--profile NAME]`, defaulting to `uniform-smoke` and printing normalized execution-plan JSON.
- Produces: `loadtool_generator::derive_provisioning(&ExecutionPlan) -> Result<Vec<Provisioning>>`, using the same deterministic planner as generation.

- [ ] **Step 1: Write table-driven Rust profile tests from the Go contract**

Cover default/explicit selection, unsafe names, unknown files, malformed/extra JSON, mismatched embedded name, missing connections, whole-second durations, positive rates, required minimum not exceeding offered rate, drain covering replay delay, replay/scenario shares selecting whole positions in 100-entry blocks, shares summing to 100, unique scenario names, automatic consecutive participant ranges, suffix overflow, amount ranges, funding modes/balances, at-least-once expectations, PACS codes/reasons, and positive `slaThresholdMs`.

- [ ] **Step 2: Add Go/Rust normalization parity for every checked-in profile**

In the test, invoke the still-present Go binary and the Rust library for:

```text
uniform-smoke
mixed-outcomes-smoke
mixed-outcomes-2k-diagnostic
mixed-outcomes-2k-6m
mixed-outcomes-2k-15m
```

Deep-compare decoded normalized JSON, including derived provisioning. Treat Go invocation as a temporary test oracle removed in Task 7.

- [ ] **Step 3: Run tests and verify failure**

Run: `cd load-test/rust-loadtool && cargo test --test profile_contract`

Expected: FAIL because Rust does not yet compile source profiles.

- [ ] **Step 4: Implement strict profile decoding and validation**

Use `#[serde(deny_unknown_fields)]` on every source-profile object and reject trailing JSON values. Keep profile names internal to `profiles/<name>.json`; do not add `--config` or a public arbitrary path.

Parse durations into whole seconds and balances into canonical two-decimal strings with checked integer cents. Assign `pair_number_start` consecutively beginning at 1.

- [ ] **Step 5: Reuse the deterministic planner for funding**

Expose a provisioning derivation function that iterates the maximum planned warmup plus active population using the same scenario/pair/amount sequence as generation. Preserve current normalized balances byte-for-byte, including the existing funding safety multiplier, but name it by purpose rather than by the removed balance-bucket implementation.

- [ ] **Step 6: Add the `validate-profile` CLI**

The command accepts only `--profile NAME`, rejects positional arguments, defaults to `uniform-smoke`, and prints pretty normalized JSON plus newline. For production resolution, the runner executes from `load-test/rust-loadtool`, so profiles resolve to `../profiles/<name>.json`; tests call the library with an explicit temporary directory.

- [ ] **Step 7: Verify profile parity**

Run:

```bash
cd load-test/rust-loadtool
cargo test --test profile_contract --test cli_contract
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
```

Expected: every checked-in profile normalizes identically and all negative contract cases return clear errors.

- [ ] **Step 8: Commit**

```bash
git add load-test/rust-loadtool
git commit -m "feat(load-test): validate profiles in Rust"
```

---

### Task 6: Add the final Rust run command and migrate the public runner

**Files:**
- Modify: `load-test/rust-loadtool/src/lib.rs`
- Modify: `load-test/rust-loadtool/src/main.rs`
- Modify: `load-test/rust-loadtool/tests/cli_contract.rs`
- Create: `load-test/rust-loadtool/tests/run_contract.rs`
- Modify: `load-test/run-load-test.sh`
- Modify: `load-test/tests/profile-selection-test.sh`
- Modify: `load-test/tests/profile-contract-test.sh`
- Modify: `load-test/tests/observable-outcome-flow-test.sh`
- Modify: `load-test/tests/runner-exit-test.sh`
- Modify: `load-test/tests/runner-diagnostics-defaults-test.sh`
- Modify: `README.md`

**Interfaces:**
- Produces CLI: `rust-loadtool run --run-dir DIR` plus current hidden connection/certificate overrides.
- Removes CLI: `rust-loadtool simulate`.
- Preserves runner: `run-load-test.sh --profile NAME RUN_TAG` and public result layout/exit behavior.

- [ ] **Step 1: Write failing final-run lifecycle tests**

Inject generator and reporter functions into the application orchestration test and assert exact order:

```text
load prepared bundle
generator
generator runtime dropped
reporter
report published
```

Assert generator failure skips report, generator `valid:false` still produces report, report failure is operational, and pre-existing outputs are rejected before network work.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `cd load-test/rust-loadtool && cargo test --test run_contract --test cli_contract`

Expected: FAIL because only `simulate` exists.

- [ ] **Step 3: Implement one final Rust run command**

The composition root performs:

```rust
pub async fn run(options: RunOptions) -> anyhow::Result<()> {
    let layout = BundleLayout::resolve(&options.run_dir)?;
    let prepared = layout.load_prepared()?;
    loadtool_generator::run(layout.clone(), prepared, options.generator).await?;
    loadtool_report::write(&layout)?;
    Ok(())
}
```

Ensure all generator-owned values are dropped before calling the reporter. Remove public `simulate`; do not add `report`.

- [ ] **Step 4: Change runner build and invocation from Go to Rust**

Use a persistent target cache and copy only the release binary into the per-run temporary directory:

```bash
RUST_TARGET_DIR="${RUST_LOADTOOL_TARGET_DIR:-/tmp/rust-loadtool-target}"
cargo build --locked --release --manifest-path rust-loadtool/Cargo.toml --target-dir "$RUST_TARGET_DIR"
cp "$RUST_TARGET_DIR/release/rust-loadtool" "$output_bin"
```

Rename `GO_LOADTOOL_PROFILES_DIR` and Go-specific log/error strings. Invoke `validate-profile` and `run` from `rust-loadtool/`. Preserve certificate overrides, `tee` status handling, diagnostics collection after failure, run-window enrichment, and post-run `sla-report.json.valid` exit mapping.

- [ ] **Step 5: Remove the Go-only runtime diagnostic flag**

Remove `--diagnose-loadtool`, `ENABLE_LOADTOOL_RUNTIME_DIAGNOSTICS`, and `--runtime-diagnostics` propagation. Rust generator self-metrics remain always enabled and qualifying; do not replace the deleted Go pprof sampler with a new in-process profiler. Update README and shell tests accordingly.

- [ ] **Step 6: Run automated runner verification**

Run:

```bash
cd load-test
for test in tests/*-test.sh; do bash "$test"; done
bash -n run-load-test.sh prepare-performance-environment.sh scripts/*.sh

cd rust-loadtool
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
cargo build --locked --release
```

Expected: shell tests prove only the Rust binary is built/invoked and preserve exit `0/1/2` semantics.

- [ ] **Step 7: Run the public functional smoke**

Run from `load-test/` after preparing a clean environment:

```bash
./run-load-test.sh --profile mixed-outcomes-smoke rust-public-smoke
```

Expected: exit 0; report valid; expected happy-path and insufficient-funds outcomes; valid PACS.008/PACS.002 replay and Pull sections; all bundle artifacts present.

- [ ] **Step 8: Commit the cutover**

```bash
git add load-test/rust-loadtool load-test/run-load-test.sh load-test/tests README.md
git commit -m "feat(load-test): run the complete load test in Rust"
```

---

### Task 7: Remove Go and qualify the final generator

**Files:**
- Delete: `load-test/go-loadtool/`
- Delete: temporary `load-test/go-loadtool/internal/report/rust_parity_fixture_test.go`
- Modify: `docs/board/Atividades/agora/estabilizar-teste-carga-budget-cpu.md`
- Modify: `docs/superpowers/plans/2026-08-25-rust-load-tool-simulator.md` only to mark it superseded; do not rewrite its historical steps
- Modify: repository/load-test documentation containing current-tense Go instructions

**Interfaces:**
- Final public implementation: one `rust-loadtool` binary plus the existing shell orchestration.
- Final dependency condition: no load-test production or active test path builds or invokes `go-loadtool`.

- [ ] **Step 1: Verify the cutover before deletion**

Run `rg -n 'go-loadtool|Go loadtool|Go load-tool|GOPATH|GOCACHE' load-test README.md docs/board/Atividades/agora`. Classify historical completed-task text separately; every current production/test/documentation reference must already be gone or scheduled in this task.

- [ ] **Step 2: Delete the Go module and temporary parity oracle**

Remove the entire `load-test/go-loadtool/` tree only after Task 6 smoke passed. Do not retain generated protobuf code, duplicate proto, Go report tests, or a dormant Go simulator.

- [ ] **Step 3: Update current project status**

Append the successful final Rust measurements to the active stabilization task, superseding its stale statement that the Rust candidate did not qualify. Record the crate boundary, generator/report lifecycle, public smoke result, and diagnostic qualification result. Preserve prior A/B entries as historical evidence rather than rewriting them.

- [ ] **Step 4: Run a representative diagnostic workload through the final public path**

Run:

```bash
cd load-test
./run-load-test.sh --profile mixed-outcomes-2k-diagnostic rust-final-diagnostic
```

Acceptance:

- zero missed original slots and zero semantic admission/capacity violations;
- `minimum_observed_tps >= 2000`;
- correct happy-path and insufficient-funds outcomes;
- valid replay and Pull invariants;
- no regression relative to `rust-shared-planner-diagnostic/20260826_175105` in generator CPU/RSS or pacing/dispatch lateness beyond ordinary host noise;
- report produced only after generator shutdown.

If host noise makes the single diagnostic inconclusive, report that evidence; do not tune or repeat indefinitely.

- [ ] **Step 5: Run final verification**

Run:

```bash
cd load-test/rust-loadtool
cargo fmt --all --check
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
cargo build --locked --release

cd ..
for test in tests/*-test.sh; do bash "$test"; done
bash -n run-load-test.sh prepare-performance-environment.sh scripts/*.sh
! rg -n 'go-loadtool|Go loadtool|Go load-tool|GOPATH|GOCACHE' run-load-test.sh tests ../README.md

cd ..
git diff --check
git status --short
```

Expected: all checks pass, no active Go load-tool reference remains, and only intended generated/ignored run evidence exists outside Git status.

- [ ] **Step 6: Commit removal and evidence**

```bash
git add -A load-test/go-loadtool load-test/rust-loadtool load-test/run-load-test.sh load-test/tests README.md docs/board/Atividades/agora docs/superpowers/plans/2026-08-25-rust-load-tool-simulator.md
git commit -m "refactor(load-test): complete the Rust load-tool migration"
```
