# Load Test Orchestration Simplification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the stateful shell runner and persisted generator telemetry with one complete profile preparer, a diagnostic execution wrapper, a thin runner, and a Rust report that qualifies generation only by planned originals and minimum rolling TPS.

**Architecture:** The preparer atomically publishes `.prepared-environment/<profile>` after stack readiness, funding, and certificates. The runner copies those inputs and runs Rust through an isolated diagnostic wrapper. The generator returns an in-memory `GenerationWindow`; the reporter reads closed CSV evidence, writes the SLA report, and determines exit status.

**Tech Stack:** Bash, Python 3 for trusted execution-plan extraction, Rust 2024, Tokio, serde, CSV, Cargo workspace, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-08-26-load-test-orchestration-simplification-design.md`

## Global Constraints

- `uniform-smoke` remains the default when `--profile` is omitted.
- Qualified runs require fresh preparation; repeated runs remain exploratory.
- Do not add historical report reruns, compatibility readers, qualification markers, or a second preparation path.
- Keep workload, pacing, HTTP/2, Pull, mTLS, warmup gate, replay semantics, and fixed drain unchanged.
- Keep reporting out of `loadtool-generator`; only closed CSVs and `GenerationWindow` cross the boundary.
- Persist no generator metrics, scheduler histograms, process CPU/RSS, Pull batch counts, or run-window artifact.
- Keep JFR, SPI trace, PostgreSQL diagnostics, and container stats enabled by default and outside Rust.
- Use TDD for behavioral changes and preserve unrelated worktree state.

---

### Task 1: In-memory generation window and minimal generation report

**Files:**
- Create: `load-test/rust-loadtool/crates/loadtool-contract/src/generation_window.rs`
- Modify: `load-test/rust-loadtool/crates/loadtool-contract/src/{lib.rs,bundle.rs}`
- Modify: `load-test/rust-loadtool/crates/loadtool-report/src/{generation.rs,summary.rs,lib.rs}`
- Test: `load-test/rust-loadtool/tests/{report_contract.rs,report_artifacts.rs}`

**Interfaces:**
- Produces `GenerationWindow { generation_started_at_ns, active_started_at_ns, generation_ended_at_ns, replay_deadline_at_ns }`.
- Produces `Bundle::load_completed(window: GenerationWindow) -> Result<CompletedRun>`.
- Produces `loadtool_report::write(bundle: &Bundle, window: GenerationWindow) -> Result<SlaReport>`.

- [x] **Step 1: Write failing tests for the minimal generation JSON**

Assert the report contains only the following generation data:

```rust
assert_eq!(report.generation.planned_originals, 4);
assert_eq!(report.generation.executed_originals, 4);
assert_eq!(report.generation.required_minimum_tps, 2);
assert_eq!(report.generation.minimum_rolling_tps, 2);
assert!(report.generation.valid);
```

Add a fixture with one active PACS.008 row removed and assert `executed_originals == 3` and `valid == false`. Retain boundary cases proving every continuous one-second interval is considered.

- [x] **Step 2: Run focused tests and observe failure**

```bash
cd load-test/rust-loadtool
cargo test --locked --test report_contract --test report_artifacts
```

Expected: FAIL because the report still loads persisted window/metrics artifacts and exposes average/maximum fields.

- [x] **Step 3: Implement the neutral window contract**

```rust
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct GenerationWindow {
    pub generation_started_at_ns: i64,
    pub active_started_at_ns: i64,
    pub generation_ended_at_ns: i64,
    pub replay_deadline_at_ns: i64,
}

impl GenerationWindow {
    pub fn validate(self, plan: &ExecutionPlan) -> Result<Self> {
        let warmup = plan.load.warmup.bootstrap.duration
            .checked_add(plan.load.warmup.steady.duration)
            .context("warmup duration overflows")?;
        let warmup_ns = i64::try_from(warmup.as_nanos())?;
        let active_ns = i64::try_from(plan.load.active_duration.as_nanos())?;
        let drain_ns = i64::try_from(plan.load.drain.as_nanos())?;
        ensure!(
            self.active_started_at_ns >= self.generation_started_at_ns.checked_add(warmup_ns).context("warmup boundary overflows")?,
            "active start precedes planned warmup end"
        );
        ensure!(
            self.generation_ended_at_ns == self.active_started_at_ns.checked_add(active_ns).context("active boundary overflows")?,
            "generation end is inconsistent with active duration"
        );
        ensure!(
            self.replay_deadline_at_ns == self.generation_ended_at_ns.checked_add(drain_ns).context("replay boundary overflows")?,
            "replay deadline is inconsistent with drain"
        );
        Ok(self)
    }
}
```

Require ordered timestamps, active start no earlier than planned warmup end, generation end equal to active start plus active duration, and replay deadline equal to generation end plus drain.

- [x] **Step 4: Make the bundle and reporter consume the supplied window**

Change `CompletedRun` to hold `GenerationWindow`. Make `Bundle::load_completed` and `loadtool_report::write` require it instead of reading an artifact.

- [x] **Step 5: Implement the minimal generation summary**

```rust
pub struct GenerationSummary {
    pub planned_originals: u64,
    pub executed_originals: usize,
    pub required_minimum_tps: u64,
    pub minimum_rolling_tps: usize,
    pub valid: bool,
}
```

Calculate planned originals with checked `offered_tx_rate * active_duration.as_secs()`. Filter starts to `[activeStart, generationEnd)`, sort timestamps, and use the existing two-pointer rolling scan. Remove offered/average/maximum/outside-window fields. Overall validity consumes `generation.valid`.

- [x] **Step 6: Run focused tests and commit**

```bash
cargo test --locked --test report_contract --test report_artifacts
cd ../..
git add load-test/rust-loadtool/crates/loadtool-contract load-test/rust-loadtool/crates/loadtool-report load-test/rust-loadtool/tests/report_contract.rs load-test/rust-loadtool/tests/report_artifacts.rs
git commit -m "refactor(load-test): minimize generation reporting"
```

### Task 2: Remove persisted generator telemetry and own run exit status in Rust

**Files:**
- Delete: `load-test/rust-loadtool/crates/loadtool-contract/src/{generator_metrics.rs,run_window.rs}`
- Delete: `load-test/rust-loadtool/crates/loadtool-generator/src/histogram.rs`
- Modify: `load-test/rust-loadtool/crates/loadtool-contract/src/bundle.rs`
- Modify: `load-test/rust-loadtool/crates/loadtool-generator/src/{lib.rs,pacer.rs,recorder.rs,simulator.rs}`
- Modify: `load-test/rust-loadtool/src/{lib.rs,main.rs}`
- Modify: `load-test/rust-loadtool/tests/{bundle_contract.rs,evidence_contract.rs,run_contract.rs,cli_contract.rs}`
- Delete: `load-test/tests/run-window-test.sh`

**Interfaces:**
- Consumes `GenerationWindow` and `loadtool_report::write(bundle, window)` from Task 1.
- Produces `simulator::run(...) -> Result<GenerationWindow>`.
- Produces `RunOutcome::{Valid, Invalid}`; run exits are valid `0`, invalid `1`, operational `2`.

- [x] **Step 1: Rewrite orchestration tests to require the in-memory window**

Make the fake generator return a known `GenerationWindow`; make the fake reporter receive that exact value. Stop copying run-window and metrics fixtures. Assert an invalid report returns `RunOutcome::Invalid` after writing `sla-report.json`. Assert operational CLI errors return `2` and completed invalid runs return `1`.

- [x] **Step 2: Run focused tests and observe failure**

```bash
cd load-test/rust-loadtool
cargo test --locked --test bundle_contract --test evidence_contract --test run_contract --test cli_contract
```

Expected: FAIL against the persisted metrics/window lifecycle and old exit mapping.

- [x] **Step 3: Remove observation infrastructure**

Delete serialized metrics, process resource sampling, Pull batch observation, histograms, in-flight observation, and recorder summaries. Retain only counters required by lifecycle/correctness. Reduce pacer output to an internal non-serialized result such as:

```rust
pub struct PacerResult {
    pub missed_slots: u64,
}
```

A warmup pacing miss must fail the warmup gate internally. Active missing originals are detected by the report count.

- [x] **Step 4: Return the window and compose reporting**

After fixed drain, task closure, recorder close, and operational checks, return the four Unix-nanosecond boundaries. Implement:

```rust
pub enum RunOutcome { Valid, Invalid }

let window = simulator::run(bundle.clone(), options.generator).await?;
let report = loadtool_report::write(&bundle, window)?;
Ok(if report.valid { RunOutcome::Valid } else { RunOutcome::Invalid })
```

Map outcomes to `0`/`1` and operational failures to `2` in `main.rs`.

- [x] **Step 5: Remove obsolete artifacts and pass focused tests**

Remove metrics/window paths and checks from `Bundle`, delete their modules, fixtures, and `run-window-test.sh`. Do not add legacy-format rejection tests.

```bash
cargo test --locked --test bundle_contract --test evidence_contract --test run_contract --test cli_contract
```

Expected: PASS.

- [x] **Step 6: Commit**

```bash
cd ../..
git add -A load-test/rust-loadtool load-test/tests/run-window-test.sh
git commit -m "refactor(load-test): remove generator telemetry artifacts"
```

### Task 3: Publish a complete prepared profile environment

**Files:**
- Create: `load-test/scripts/execution-plan-participants.py`
- Rename: `load-test/scripts/prepare-environment.sh` to `load-test/scripts/provision-profile-funds.sh`
- Modify: `load-test/prepare-performance-environment.sh`
- Modify: `load-test/tests/{prepare-environment-test.sh,prepare-performance-environment-test.sh}`
- Modify: `.gitignore`

**Interfaces:**
- Produces `.prepared-environment/<profile>/inputs/{profile.json,execution-plan.json}` and `certs/`.
- Produces `provision-profile-funds.sh --execution-plan FILE`.
- `execution-plan-participants.py FILE` emits trusted normalized participant/provisioning rows as TSV.

- [x] **Step 1: Write failing complete-preparation tests**

Use fake Cargo/loadtool, Docker, readiness, certificate, and funding adapters. Assert the successful flow is `validate-profile → docker down → docker up → readiness → funding → certificates → publish`. Assert omitted profile selects `uniform-smoke`, explicit names select their own directory, and any failure leaves no final prepared root.

- [x] **Step 2: Run tests and observe failure**

```bash
bash load-test/tests/prepare-environment-test.sh
bash load-test/tests/prepare-performance-environment-test.sh
```

Expected: FAIL because preparation currently starts only the stack and funding consumes a run directory.

- [x] **Step 3: Extract trusted participant rows once**

Implement a small Python consumer that calls `json.load`, indexes the normalized fields directly, and prints:

```text
pairNumberStart<TAB>hotPairCount<TAB>coldPairCount<TAB>payerBalance<TAB>receiverBalance<TAB>resetIfExists
```

It must not implement scenario discriminators, range/share rules, or business validation.

- [x] **Step 4: Rename and narrow funding**

Change the adapter CLI to `provision-profile-funds.sh --execution-plan FILE`. Preserve payer/receiver ISPB expansion and reset/preserve behavior; remove generic preparation terminology.

- [x] **Step 5: Implement atomic complete preparation**

Add `--profile` with `uniform-smoke` default, safe internal-name validation, staging under a configurable prepared root, cached release build, profile snapshot, plan generation, stack reset/start, readiness, funding, and certificate generation. Invalidate the prior final root before stack mutation and rename staging only after every stage succeeds. A trap removes failed staging.

- [x] **Step 6: Run tests and commit**

```bash
bash load-test/tests/prepare-environment-test.sh
bash load-test/tests/prepare-performance-environment-test.sh
git add .gitignore load-test/prepare-performance-environment.sh load-test/scripts load-test/tests/prepare-environment-test.sh load-test/tests/prepare-performance-environment-test.sh
git commit -m "refactor(load-test): prepare complete profile environments"
```

### Task 4: Isolate diagnostics behind one command wrapper

**Files:**
- Create: `load-test/scripts/run-diagnostics.sh`
- Modify: `load-test/tests/{diagnostics-layout-test.sh,jfr-diagnostics-test.sh,postgres-diagnostics-test.sh,runner-diagnostics-defaults-test.sh}`

**Interfaces:**
- Produces `run-diagnostics.sh run --run-dir DIR [--no-jfr] [--no-spi-trace] [--no-postgres-statements] -- COMMAND...`.
- Returns the child status when nonzero; returns `2` when the child succeeds but diagnostics fail.

- [x] **Step 1: Rewrite tests against the wrapper**

Stop sourcing `run-load-test.sh`. Assert default-enabled collection, disabled paths, success artifacts, child failure plus collection failure, collection-only failure, and cleanup of sampler PIDs.

- [x] **Step 2: Run tests and observe failure**

```bash
bash load-test/tests/diagnostics-layout-test.sh
bash load-test/tests/jfr-diagnostics-test.sh
bash load-test/tests/postgres-diagnostics-test.sh
bash load-test/tests/runner-diagnostics-defaults-test.sh
```

Expected: FAIL because the wrapper does not exist.

- [x] **Step 3: Move the diagnostic lifecycle**

Move the current JFR, SPI trace, PostgreSQL statement/activity/I/O/log, container stats, PID cleanup, and failure-precedence functions without changing their adapters. Parse all options before side effects. Execute the child through `tee` and preserve `PIPESTATUS`:

```bash
if ((command_status != 0)); then return "$command_status"; fi
if ((tee_status != 0 || diagnostics_status != 0)); then return 2; fi
return 0
```

- [x] **Step 4: Run tests and commit**

```bash
bash load-test/tests/diagnostics-layout-test.sh
bash load-test/tests/jfr-diagnostics-test.sh
bash load-test/tests/postgres-diagnostics-test.sh
bash load-test/tests/runner-diagnostics-defaults-test.sh
git add load-test/scripts/run-diagnostics.sh load-test/tests/diagnostics-layout-test.sh load-test/tests/jfr-diagnostics-test.sh load-test/tests/postgres-diagnostics-test.sh load-test/tests/runner-diagnostics-defaults-test.sh
git commit -m "refactor(load-test): isolate run diagnostics"
```

### Task 5: Replace the runner with a prepared-workload runner

**Files:**
- Rewrite: `load-test/run-load-test.sh`
- Modify: `load-test/rust-loadtool/src/main.rs`
- Modify: `load-test/rust-loadtool/crates/loadtool-generator/src/simulator.rs`
- Modify: `load-test/tests/{profile-selection-test.sh,profile-contract-test.sh,runner-exit-test.sh,observable-outcome-flow-test.sh}`

**Interfaces:**
- Consumes `.prepared-environment/<profile>`, the cached Rust binary, and `run-diagnostics.sh`.
- Rust run CLI accepts `--run-dir DIR --client-cert-root DIR` as its only orchestration paths.

- [x] **Step 1: Write the thin-runner contract tests**

Assert invalid names and absent/mismatched preparation fail before result creation; prepared inputs are copied byte-for-byte; no Docker/readiness/funding/cert command is invoked; diagnostic disable flags are forwarded; Rust receives only run dir and client cert root; statuses `0`, `1`, and `2` are preserved; and two sequential runs can reuse one prepared environment.

- [x] **Step 2: Run tests and observe failure**

```bash
bash load-test/tests/profile-selection-test.sh
bash load-test/tests/profile-contract-test.sh
bash load-test/tests/runner-exit-test.sh
bash load-test/tests/observable-outcome-flow-test.sh
```

Expected: FAIL because the runner still validates/provisions/generates certificates/enriches window/parses report.

- [x] **Step 3: Reduce TLS override surface**

Replace the six hidden CLI arguments and `SimulationOptions` fields with one `client_cert_root: Option<PathBuf>`, used for both Central Transfer and Gateway. Continue reading CA and server names from the profile.

- [x] **Step 4: Rewrite the runner**

Keep only argument parsing, prepared-directory resolution, cached Cargo build, result creation, input copying, diagnostic wrapper invocation, result path logging, and status propagation. Use the release binary directly; remove all Python, temporary binary/certificate cleanup, plan decomposition, environment preparation, and report parsing.

- [x] **Step 5: Run tests and commit**

```bash
bash load-test/tests/profile-selection-test.sh
bash load-test/tests/profile-contract-test.sh
bash load-test/tests/runner-exit-test.sh
bash load-test/tests/observable-outcome-flow-test.sh
git add load-test/run-load-test.sh load-test/rust-loadtool load-test/tests
git commit -m "refactor(load-test): run prepared workloads only"
```

### Task 6: Split the remaining Rust generator by responsibility

**Files:**
- Create: `load-test/rust-loadtool/crates/loadtool-generator/src/{lifecycle.rs,runtime.rs,notification_flow.rs}`
- Modify: `load-test/rust-loadtool/crates/loadtool-generator/src/{simulator.rs,original.rs,recorder.rs,lib.rs}`
- Test: existing Rust workspace tests

**Interfaces:**
- `simulator::run` remains the public composition function.
- `lifecycle` owns phase boundaries and shutdown.
- `original` owns PACS.008 preparation/admission/completion.
- `notification_flow` owns Pull, outcomes, PACS.002, and causal replay.
- `runtime` owns shared handles, the write-once active window, cancellation, and TaskTracker.

- [x] **Step 1: Establish the passing refactor baseline**

Preserve/extend `dependency_boundary.rs` so `loadtool-generator` cannot depend on `loadtool-report`, then run:

```bash
cd load-test/rust-loadtool
cargo test --workspace --locked
```

Expected: PASS before structural movement.

- [x] **Step 2: Move runtime ownership first**

Move runtime/topology/failure/cancellation state to `runtime.rs`. Expose behavior through methods rather than broad public fields. Replace `RwLock<Option<ActiveWindow>>` with `OnceLock<ActiveWindow>` because it is initialized once before active traffic.

- [x] **Step 3: Move notification and lifecycle flows**

Move Pull loop, notification handling, PACS.002 dispatch, and causal replay to `notification_flow.rs`. Move warmup/active/drain orchestration and shutdown to `lifecycle.rs`. Leave setup/composition in `simulator.rs`. Do not add actors, registries, event buses, worker pools, or queues.

- [x] **Step 4: Centralize event and JSON representation**

Co-locate CSV headers with contract row schemas and retain one atomic JSON writer for `sla-report.json`. Remove imports, locks, fields, and parameters with no consumers after the split.

- [x] **Step 5: Run full Rust verification and commit**

```bash
cargo fmt --check
cargo test --workspace --locked
cargo clippy --workspace --all-targets --locked -- -D warnings
cd ../..
git add load-test/rust-loadtool
git commit -m "refactor(load-test): clarify generator responsibilities"
```

### Task 7: Documentation, board status, and end-to-end verification

**Files:**
- Modify: `README.md`
- Modify: `load-test/README.md` if present
- Modify: `docs/board/Atividades/concluidas/separar-preparacao-do-runner-load-test.md`
- Modify/delete: stale active tests found by final reference scan

**Interfaces:**
- Documents the official fresh qualified flow and explicitly exploratory reused flow.

- [x] **Step 1: Update documentation and the board task**

Document:

```bash
cd load-test
./prepare-performance-environment.sh --profile mixed-outcomes-smoke
./run-load-test.sh --profile mixed-outcomes-smoke qualified-smoke
./run-load-test.sh --profile mixed-outcomes-smoke exploratory-repeat
```

State that the second run reuses state and does not qualify performance. Mark the board task complete only after verification.

- [x] **Step 2: Remove stale active references**

```bash
rg -n "run-window|generator-metrics|prepare-environment|validate_sla_report|LOADTOOL_BUILD_DIR|central-transfer-ca-cert|gateway-ca-cert" load-test README.md docs/board
```

Update/delete every active-code, test, and operational-document reference. Historical design specs may mention superseded behavior only when clearly historical.

- [x] **Step 3: Run automated verification**

```bash
cd load-test/rust-loadtool
cargo fmt --check
cargo test --workspace --locked
cargo clippy --workspace --all-targets --locked -- -D warnings
cd ../..
for test in load-test/tests/*-test.sh load-test/tests/*-tests.sh; do bash "$test"; done
bash -n load-test/run-load-test.sh load-test/prepare-performance-environment.sh load-test/scripts/*.sh
git diff --check
```

Expected: PASS.

- [x] **Step 4: Run the public short validation twice**

```bash
cd load-test
./prepare-performance-environment.sh --profile mixed-outcomes-smoke
./run-load-test.sh --profile mixed-outcomes-smoke orchestration-qualified-smoke
sleep 30
./run-load-test.sh --profile mixed-outcomes-smoke orchestration-exploratory-smoke
```

Expected: both produce current-format bundles; the first has `plannedOriginals == executedOriginals` and satisfies the rolling minimum; after the current 30-second Pull timeout closes the prior sessions, the second completes without another preparation. Do not run a 15-minute profile.

- [x] **Step 5: Commit documentation and confirm final state**

```bash
git add README.md load-test docs/board/Atividades/concluidas/separar-preparacao-do-runner-load-test.md
git commit -m "docs: document qualified and exploratory load runs"
git status --short
git log --oneline -8
```

Expected: clean working tree and the logical implementation commits above.
