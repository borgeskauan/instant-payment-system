# Rust Load-tool Simulator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan.

**Goal:** Replace the active Go simulator with a greenfield Rust simulator that preserves the current prepared-run bundle, observable workload, CSV evidence, run window, and Go-generated SLA report while providing predictable 1 ms pacing with no catch-up and low generator overhead.

**Architecture:** Keep `run-load-test.sh` and `go-loadtool run` as the public orchestration boundary during this migration. The existing Bash/Go path remains temporarily responsible for profile validation, execution-plan generation, environment preparation/funding, and report rendering; `go-loadtool run` invokes a sibling `rust-loadtool simulate --run-dir ...` process for simulation and reads the Rust generator metrics before rendering the existing report. The Rust simulator owns pacing, HTTP/2 PACS.008/PACS.002 traffic, gRPC Pull consumption, deterministic replays, payment state, lifecycle, and evidence production. There is no permanent engine selector: record the Go baseline before switching the active path, then replace it.

**Tech Stack:** Rust 1.89 / edition 2024, Tokio, Hyper 1 + Hyper-util, Rustls/Tokio-rustls, Tonic/Prost, Serde, Bytes, csv, hdrhistogram, Tokio-util `TaskTracker`; existing Go profile/report packages and Bash runner remain temporarily.

**Spec:** [`docs/superpowers/specs/2026-08-25-rust-load-tool-greenfield-design.md`](../specs/2026-08-25-rust-load-tool-greenfield-design.md)

## Global Constraints

- Preserve unrelated working-tree changes. Do not commit `README.md`, the active board task, profile experiments, or Go config-test edits unless a task below explicitly names them.
- Use TDD for every behavioral change: add the failing test, run it and observe the intended failure, implement the minimum behavior, then rerun the focused test.
- The public command remains `./run-load-test.sh --profile NAME TAG`. Do not add an engine selector, `--config`, standalone report command, or support for replaying historical runs.
- The intermediate Rust CLI has one command, `simulate`, and one public locator, `--run-dir`. Connection/TLS values forwarded by the temporary Go adapter are internal migration arguments, not profile or business settings.
- The normalized `inputs/execution-plan.json` remains authoritative for workload values. Rust decodes it strictly but does not reimplement Go's profile semantic-validation contract.
- Preserve the exact four CSV names, headers, field meanings, and absolute Unix-nanosecond timestamps consumed by the Go report.
- Preserve `run-window.json` schema version 2 until the final Rust reporting cutover.
- All runtime queues are bounded and use non-blocking admission where specified. A full recorder queue aborts the run; exhausted causal HTTP capacity or missed PACS.008 admission invalidates it.
- Initial internal qualification constants are: 1 ms buckets, 50 us spin tail, PACS.002 HTTP capacity 16,384, recorder queue capacity 65,536, Pull protocol maximum 15. They are code constants, not profile knobs.
- Do not introduce a payload pool, hand-written JSON encoder, fixed worker pool, actor/lane model, custom replay scheduler, binary evidence format, adaptive throttling, or runtime-specific tuning without a benchmark that demonstrates need.
- Make one focused commit per task. Do not squash until the whole slice is accepted.

---

## Task 1: Capture the Go baseline and scaffold the Rust crate

**Files:**

- Create: `load-test/rust-loadtool/Cargo.toml`
- Create: `load-test/rust-loadtool/rust-toolchain.toml`
- Create: `load-test/rust-loadtool/build.rs`
- Create: `load-test/rust-loadtool/src/main.rs`
- Create: `load-test/rust-loadtool/src/lib.rs`
- Create: `load-test/rust-loadtool/tests/cli_contract.rs`
- Modify: `.gitignore`

- [ ] **Step 1: Record the unchanged Go baseline**

Run the current short capacity profile before changing the active simulator:

```bash
cd load-test
./run-load-test.sh --profile mixed-outcomes-2k-diagnostic rust-migration-go-baseline
```

Record the result directory and extract these values into the implementation notes for the later A/B:

```bash
jq '{valid, generation, notification_pull, performance}' \
  results/rust-migration-go-baseline/*/sla-report.json
```

Also preserve the existing `diagnostics/container-stats.csv` and `diagnostics/loadtool/` artifacts. Do not commit the result directory.

- [ ] **Step 2: Add a failing CLI contract test**

The test must assert:

- no arguments returns exit code 2 with concise usage;
- `simulate` requires `--run-dir`;
- positional extras and unknown flags are rejected;
- no `report`, `validate-profile`, or engine-selection command exists in Rust yet.

The temporary adapter-only flags accepted by `simulate` are exactly the six current mTLS overrides:

```text
--central-transfer-ca-cert
--central-transfer-client-cert-root
--central-transfer-server-name
--gateway-ca-cert
--gateway-client-cert-root
--gateway-server-name
```

They are hidden from Rust help output and disappear in the final cutover when the runner owns one fixed certificate layout.

Run:

```bash
cd load-test/rust-loadtool
cargo test --test cli_contract
```

Expected: FAIL because the crate and binary do not exist.

- [ ] **Step 3: Create the minimal crate and pinned toolchain**

Use Rust 1.89, edition 2024. Add only dependencies required by this plan:

```text
anyhow, bytes, clap/derive, csv, hdrhistogram, http-body-util,
hyper/http2, hyper-util/tokio, libc,
prost, rustls, rustls-pemfile, serde/derive, serde_json, thiserror,
tokio/macros+rt-multi-thread+sync+time+net, tokio-rustls, tokio-util/rt,
tonic/transport+tls, tonic-build (build dependency)
```

`build.rs` compiles the existing canonical Gateway contract at:

```text
../../notification-gateway/src/main/proto/notification.proto
```

The initial binary parses only the command shape and returns an explicit “not implemented” operational error after valid parsing.

If the host has no Rust toolchain, install the pinned minimal toolchain once before the first test:

```bash
rustup toolchain install 1.89.0 --profile minimal
```

This is an environment prerequisite, not a repository setup script.

- [ ] **Step 4: Run the scaffold checks**

```bash
cd load-test/rust-loadtool
cargo fmt --check
cargo test --test cli_contract
cargo clippy --all-targets -- -D warnings
```

Expected: PASS.

- [ ] **Step 5: Ignore only Rust build output**

Add `load-test/rust-loadtool/target/` to `.gitignore`. Commit `Cargo.lock`; this is an application binary, so its dependency graph must be reproducible.

- [ ] **Step 6: Commit**

```bash
git add .gitignore load-test/rust-loadtool
git commit -m "build: scaffold the Rust load tool"
```

---

## Task 2: Decode the prepared bundle without duplicating profile semantics

**Files:**

- Create: `load-test/rust-loadtool/src/bundle.rs`
- Create: `load-test/rust-loadtool/src/model.rs`
- Create: `load-test/rust-loadtool/tests/bundle_contract.rs`
- Create: `load-test/rust-loadtool/tests/fixtures/execution-plan.json`
- Create: `load-test/rust-loadtool/tests/fixtures/profile.json`
- Modify: `load-test/rust-loadtool/src/lib.rs`
- Modify: `load-test/rust-loadtool/src/main.rs`

- [ ] **Step 1: Add failing bundle tests**

Cover:

- `--run-dir` resolves an absolute root and fixed `inputs`, `events`, `diagnostics`, `run-window.json`, and metrics paths;
- missing, non-regular, or malformed `profile.json`/`execution-plan.json` fails before output creation;
- existing `events`, `run-window.json`, or `sla-report.json` is rejected;
- profile name must match execution-plan profile;
- unknown execution-plan fields are rejected;
- the profile snapshot is read only for `name` and connection endpoints/server names; Go remains authoritative for semantic profile validation;
- the current bootstrap/steady warmup fields, replay settings, scenarios, participants, amounts, provisioning, and expectations are decoded exactly.

Use a fixture copied from current `validate-profile` output, including both `happy-path` and `insufficient-funds`.

Run:

```bash
cd load-test/rust-loadtool
cargo test --test bundle_contract
```

Expected: FAIL because `Bundle` and typed models do not exist.

- [ ] **Step 2: Implement fixed layout and typed normalized input**

Implement:

```rust
pub struct Bundle { /* fixed absolute paths */ }
impl Bundle {
    pub fn resolve(run_dir: &Path) -> Result<Self>;
    pub fn validate_prepared(&self) -> Result<()>;
    pub fn prepare_outputs(&self) -> Result<()>;
}
```

Use `#[serde(deny_unknown_fields)]` on the normalized execution-plan types. Use a deliberately shallow profile envelope for name and connections; do not reproduce funding/range/business validation already performed by Go.

Convert integer seconds to checked `Duration`s and compute the checked maximum planned sequence count before allocating any state.

- [ ] **Step 3: Wire `simulate --run-dir` through bundle validation**

The command still stops before network startup, but malformed bundles now fail with contextual paths and no generated outputs.

- [ ] **Step 4: Run focused tests**

```bash
cd load-test/rust-loadtool
cargo test --test bundle_contract
cargo test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add load-test/rust-loadtool
git commit -m "feat: load prepared runs in the Rust simulator"
```

---

## Task 3: Implement deterministic planning, replay selection, and payment state

**Files:**

- Create: `load-test/rust-loadtool/src/planner.rs`
- Create: `load-test/rust-loadtool/src/replay.rs`
- Create: `load-test/rust-loadtool/src/payment_state.rs`
- Create: `load-test/rust-loadtool/tests/deterministic_workload.rs`
- Modify: `load-test/rust-loadtool/src/lib.rs`

- [ ] **Step 1: Add failing deterministic-planning tests**

Protect:

- cumulative 1 ms arithmetic produces exactly 2,100 requests per second as 900 buckets of 2 and 100 buckets of 3;
- scenario selection preserves exact 80/20 quotas in each complete block of 100;
- participant hot/cold selection, pair allocation, amount, phase, timeout, and expected outcome derive only from sequence and immutable plan;
- PACS.008 and PACS.002 replay populations use separate domains and deterministic ordinals;
- the current-run EndToEndId prefix and sequence parser reject malformed and foreign-run identifiers without allocating per-payment strings;
- each complete eligible block selects exactly `share * 100` positions;
- shares outside `(0, 1]` or not expressible in 1% increments are rejected;
- the concrete hash vectors below never drift:

```text
rotation(block 0..2):
scenario = [20, 71, 26]
PACS008  = [38, 33, 10]
PACS002  = [49, 24, 40]

5% selected positions in block 0:
PACS008 = [18, 26, 45, 72, 99]
PACS002 = [15, 23, 42, 69, 96]
```

Use these fixed 64-bit domains:

```text
SCENARIO = 0x5343454e4152494f
PACS008  = 0x5041435330303800
PACS002  = 0x5041435330303200
```

and the SplitMix64 finalizer described by the architecture spec.

- [ ] **Step 2: Add failing `PaymentState` tests**

Cover:

- uncommitted slots are ignored by notification processing;
- `commit` is one-way;
- exactly one concurrent `claim_pacs002` succeeds;
- repeated correct outcomes are accepted at-least-once;
- wrong PSP, status, or reasons sets contradiction;
- atomics, masks, and `Ordering` are not exposed outside `payment_state.rs`.

The private byte flags are exactly `COMMITTED`, `PACS002_CLAIMED`,
`EXPECTED_OUTCOME_SEEN`, and `CONTRADICTION_SEEN`; `STARTED` and
`PACS002_SCHEDULED` must not appear in the new implementation.

Run:

```bash
cd load-test/rust-loadtool
cargo test --test deterministic_workload
```

Expected: FAIL.

- [ ] **Step 3: Implement the pure planner and replay selector**

Use checked integer arithmetic only. Do not retain mutable RNG, seed, runtime-assigned PACS.002 ordinal, or heap-allocated per-payment business data.

- [ ] **Step 4: Implement contiguous atomic state**

Allocate `Vec<AtomicU8>` once from the maximum planned sequence count. Expose semantic methods only:

```rust
commit(sequence)
claim_pacs002(sequence) -> bool
observe_outcome(sequence, observed) -> OutcomeObservation
is_committed(sequence) -> bool
```

- [ ] **Step 5: Run tests and a memory-size assertion**

```bash
cd load-test/rust-loadtool
cargo test deterministic_workload payment_state
```

Assert one byte per slot and approximately 1.92 MiB for the current 15-minute plan.

- [ ] **Step 6: Commit**

```bash
git add load-test/rust-loadtool
git commit -m "feat: derive the Rust workload from payment sequence"
```

---

## Task 4: Implement the no-catch-up native pacer

**Files:**

- Create: `load-test/rust-loadtool/src/clock.rs`
- Create: `load-test/rust-loadtool/src/pacer.rs`
- Create: `load-test/rust-loadtool/src/generator_metrics.rs`
- Create: `load-test/rust-loadtool/tests/pacer_contract.rs`
- Modify: `load-test/rust-loadtool/src/lib.rs`

- [ ] **Step 1: Add failing pure timing tests**

Test:

- absolute `bucket_start`/`bucket_deadline` calculations;
- a late cursor jumps directly to the currently valid bucket;
- expired buckets increment missed slots without emitting descriptors;
- a full one-element channel marks the next bucket missed and never blocks;
- no request count is moved into a later bucket;
- bootstrap and steady remain contiguous while active receives a later explicit phase start;
- wall-clock projection uses one immutable monotonic/wall origin pair.

- [ ] **Step 2: Add a short real-clock pacing test**

Run a 100 ms phase at 2,100 TPS and assert:

- at most one descriptor is pending;
- descriptors preserve absolute deadlines;
- no catch-up descriptor exceeds its planned request count;
- measured lateness and spin wall-time are emitted.

Use tolerant timing assertions suitable for CI; correctness assertions must not require sub-millisecond scheduler precision.

Run:

```bash
cd load-test/rust-loadtool
cargo test --test pacer_contract -- --nocapture
```

Expected: FAIL.

- [ ] **Step 3: Implement the pacer thread**

Implement one native thread with:

- absolute `Instant` deadlines;
- sleep while farther than 50 us;
- short final spin;
- O(1) jump over expired buckets;
- `try_send` to a Tokio MPSC channel of capacity 1;
- one compact `BucketDescriptor` per current bucket.

It must not build payloads, touch payment state, perform network I/O, or write files.

- [ ] **Step 4: Aggregate bounded metrics**

Use fixed counters plus HDR histograms for lateness. Do not retain one sample per slot. Include planned, dispatched, missed, spin wall-time, and maximum observed in-flight fields.

- [ ] **Step 5: Run focused and sanitizer-friendly checks**

```bash
cd load-test/rust-loadtool
cargo test --test pacer_contract
cargo clippy --all-targets -- -D warnings
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add load-test/rust-loadtool
git commit -m "feat: pace original payments without catch-up"
```

---

## Task 5: Produce compatible evidence with a single recorder

**Files:**

- Create: `load-test/rust-loadtool/src/event.rs`
- Create: `load-test/rust-loadtool/src/recorder.rs`
- Create: `load-test/rust-loadtool/src/run_window.rs`
- Create: `load-test/rust-loadtool/tests/evidence_contract.rs`
- Create: `load-test/rust-loadtool/tests/fixtures/events/*.csv`
- Create: `load-test/go-loadtool/internal/report/rust_evidence_compatibility_test.go`
- Modify: `load-test/rust-loadtool/src/generator_metrics.rs`
- Modify: `load-test/rust-loadtool/src/lib.rs`

- [ ] **Step 1: Add failing golden-file tests**

Assert exact compatibility with the Go report inputs:

```text
events/pacs008-starts.csv
events/pacs002-starts.csv
events/notifications.csv
events/replays.csv
```

Protect the exact existing headers and encodings, including `reason_codes` JSON arrays and lowercase booleans. Assert timestamps are projected Unix nanoseconds from offsets relative to `runMonoOrigin`.

Also assert `run-window.json` contains schema version 2, the profile name, and the five current window timestamps with active starting only after the warmup gate.

- [ ] **Step 2: Add failure tests**

Cover recorder queue full, writer failure, flush failure, and pre-existing output. Every case must fail the run; no event may be silently dropped.

- [ ] **Step 3: Implement compact events and the recorder thread**

Use one preallocated bounded MPSC queue of 65,536 entries. Producers send indices/enums/integers with `try_send`; one native thread derives strings and writes four large buffered CSV writers. It flushes and syncs only during orderly close.

- [ ] **Step 4: Write generator metrics atomically**

Create:

```text
diagnostics/loadtool/generator-metrics.json
```

It must include:

- pacer/dispatch/HTTP histograms summarized as count, p50, p95, p99, max;
- planned/dispatched/started/completed/missed slots;
- late semantic admissions, which must remain zero;
- generator-capacity violations;
- current and maximum in-flight;
- process user/system CPU time and maximum RSS on Linux;
- spin wall-time;
- Pull empty responses and exact batch-size counts 1..15;
- a top-level `valid` boolean and concise violations array.

Do not put generator diagnostics into `sla-report.json` in this slice.

- [ ] **Step 5: Prove Go can read the golden CSVs**

Copy the Rust golden files into a Go report test fixture and call the existing `report.Print`. The resulting report must parse and preserve current business/replay semantics.

Run:

```bash
cd load-test/rust-loadtool && cargo test --test evidence_contract
cd ../go-loadtool && GOPATH=/tmp/go GOCACHE=/tmp/go-build-cache go test ./internal/report/...
```

- [ ] **Step 6: Commit**

```bash
git add load-test/rust-loadtool load-test/go-loadtool/internal/report
git commit -m "feat: record Rust load evidence for the existing report"
```

---

## Task 6: Add persistent HTTP/2 mTLS transport and PACS payloads

**Files:**

- Create: `load-test/rust-loadtool/src/payload.rs`
- Create: `load-test/rust-loadtool/src/http2.rs`
- Create: `load-test/rust-loadtool/src/original.rs`
- Create: `load-test/rust-loadtool/tests/http2_admission.rs`
- Create: `load-test/rust-loadtool/tests/payload_compatibility.rs`
- Modify: `load-test/rust-loadtool/src/lib.rs`

- [ ] **Step 1: Add failing payload compatibility tests**

For fixed IDs, ISPBs, amounts, and timestamps, assert PACS.008/PACS.002 parse to the same semantic JSON as current Go payloads. Assert replay clones share the same immutable `Bytes` contents byte-for-byte.

- [ ] **Step 2: Add failing admission-boundary tests**

With a fake HTTP/2 sender, prove:

- initial deadline failure creates no payment state or CSV row;
- payload construction followed by unavailable stream capacity until bucket deadline remains a missed slot;
- readiness acquisition reserves capacity for immediate `send_request`;
- final deadline failure creates no observable payment;
- `commit` happens before `send_request` and registers deterministic replay obligation first;
- after commit, HTTP error/timeout is recorded and never converted back into a missed slot;
- response protocol other than HTTP/2 is an operational violation.

- [ ] **Step 3: Implement simple typed serialization**

Serialize typed PACS structures directly into a pre-sized `Vec<u8>`/`Bytes` without an intermediate `String`. Do not add manual JSON fragments or a buffer pool.

- [ ] **Step 4: Implement one persistent mTLS HTTP/2 client per PSP**

Use Rustls with ALPN fixed to `h2`. Load `psp-<ISPB>/client.crt` and `client.key`, use the configured CA/server name, and prewarm each connection through authenticated `GET /health` before `runMonoOrigin` is captured.

Use the direct Hyper HTTP/2 sender readiness contract so capacity is awaited only until `bucketDeadline` and immediately consumed by `send_request`. Do not place an admitted request in a second internal queue.

- [ ] **Step 5: Implement PACS.008 task admission**

Tokio receives a descriptor and spawns one task per planned request. Apply the two deadline checks, derive immutable payment data, commit state/causal obligations, send `/transfer`, and emit a completion event.

- [ ] **Step 6: Run focused tests**

```bash
cd load-test/rust-loadtool
cargo test --test payload_compatibility --test http2_admission
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add load-test/rust-loadtool
git commit -m "feat: submit admitted payments over persistent HTTP2"
```

---

## Task 7: Consume Pull notifications and send bounded causal PACS.002 traffic

**Files:**

- Create: `load-test/rust-loadtool/src/notification.rs`
- Create: `load-test/rust-loadtool/src/pull.rs`
- Create: `load-test/rust-loadtool/src/causal.rs`
- Create: `load-test/rust-loadtool/src/replay_task.rs`
- Create: `load-test/rust-loadtool/tests/pull_contract.rs`
- Create: `load-test/rust-loadtool/tests/causal_capacity.rs`
- Modify: `load-test/rust-loadtool/src/lib.rs`

- [ ] **Step 1: Add failing notification parser tests**

Cover PACS.008 and PACS.002 envelopes with multiple entries, alternative `Id`/`ID` spellings already accepted by Go, empty/unknown payloads, expected ACSC, expected RJCT/AM04, wrong PSP, contradictory status/reasons, and notifications from another run.

- [ ] **Step 2: Add failing Pull batch tests**

Prove:

- cursor starts empty and advances only after the entire batch is validated, correlated, recorded, and causal work is admitted;
- a batch larger than 15 is a protocol violation;
- partial failure keeps the prior cursor;
- repeated batches are at-least-once safe;
- only one Pull is in flight per PSP;
- empty and non-empty batch counts are captured exactly.

- [ ] **Step 3: Add failing causal-capacity tests**

Use an injectable small capacity to prove:

- original and replay PACS.002 share the same semaphore;
- acquisition is non-blocking;
- exhaustion records one generator-capacity violation and invalidates the run;
- replay sleepers do not hold permits;
- a PACS.008 redelivery cannot claim a second original PACS.002;
- a PACS.002 started in drain does not create a replay;
- no rollback/retry state machine is created after a failed claim/admission.

- [ ] **Step 4: Implement persistent Tonic mTLS clients**

Create one client/loop per PSP using the current unary `PullNotifications` contract and no batch-size request field. Keep Pulls active through warmup, active, and drain.

- [ ] **Step 5: Implement causal HTTP and replay tasks**

Production capacity is 16,384 permits. Spawn one Tokio task per selected replay using `sleep_until`; PACS.008 replay goes directly to its persistent sender, while PACS.002 original/replay uses non-blocking shared causal capacity. Reuse the exact `Bytes` body for original and replay.

- [ ] **Step 6: Run focused tests**

```bash
cd load-test/rust-loadtool
cargo test --test pull_contract --test causal_capacity
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add load-test/rust-loadtool
git commit -m "feat: drive status traffic from pulled notifications"
```

---

## Task 8: Assemble warmup, active, drain, and shutdown lifecycle

**Files:**

- Create: `load-test/rust-loadtool/src/phase_tracker.rs`
- Create: `load-test/rust-loadtool/src/simulator.rs`
- Create: `load-test/rust-loadtool/tests/lifecycle_contract.rs`
- Modify: `load-test/rust-loadtool/src/main.rs`
- Modify: `load-test/rust-loadtool/src/lib.rs`

- [ ] **Step 1: Add failing lifecycle tests with paused Tokio time**

Protect:

- bootstrap and steady use their own rates/timeouts and contiguous planned time;
- warmup generation closes before waiting;
- active starts only after all load-tool-observable warmup work reaches zero;
- continuations are registered before parents complete, preventing transient zero;
- warmup timeout prevents active from starting;
- active start/generation end/replay deadline are fixed before active generation;
- original generation stops at `generationEnd`;
- drain is fixed and does not terminate early;
- PACS.002/outcomes and previously selected replays continue during drain;
- hard deadlines apply as `min(requestStart + causalTimeout, phaseHardDeadline)`;
- shutdown order is semantic close, cancellation, Pull close, `TaskTracker.close`, `TaskTracker.wait`, recorder close/flush, metrics/report handoff;
- `TaskTracker` is used only for technical lifecycle, not semantic completion.

Internally expose the phase value through one `hardDeadline` concept; do not
duplicate timeout rules across warmup, active, replay, and causal send paths.

- [ ] **Step 2: Implement a small warmup `PhaseTracker`**

Expose only `add`, `done`, `fail`, `close_generation`, and bounded `wait`. Do not build an obligation tree or generic workflow framework.

- [ ] **Step 3: Implement the simulator orchestrator**

Startup order:

```text
validate inputs
→ size/allocate payment state
→ start recorder
→ create and prewarm HTTP/2 + gRPC clients
→ capture runMonoOrigin + wallOrigin
→ warmup pacer/gate
→ active pacer
→ fixed drain
→ ordered shutdown
```

The simulator returns nonzero only for an operational failure that prevents a complete run bundle. A completed experiment with missed slots, causal-capacity exhaustion, or another generator violation still exits zero after writing complete evidence with `generator-metrics.json.valid:false`; the temporary Go report then marks the completed run invalid.

- [ ] **Step 4: Write run-window and metrics only after producer shutdown**

Use temporary file + atomic publish for both JSON artifacts. Ensure Go never reads partial files.

- [ ] **Step 5: Run the complete Rust suite**

```bash
cd load-test/rust-loadtool
cargo fmt --check
cargo test
cargo clippy --all-targets -- -D warnings
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add load-test/rust-loadtool
git commit -m "feat: run complete load phases in Rust"
```

---

## Task 9: Replace the active Go simulator with the temporary Rust adapter

**Files:**

- Create: `load-test/go-loadtool/internal/rustsim/runner.go`
- Create: `load-test/go-loadtool/internal/rustsim/runner_test.go`
- Create: `load-test/go-loadtool/internal/rustsim/metrics.go`
- Create: `load-test/go-loadtool/internal/rustsim/metrics_test.go`
- Modify: `load-test/go-loadtool/cmd/go-loadtool/run.go`
- Modify: `load-test/go-loadtool/cmd/go-loadtool/run_test.go`
- Modify: `load-test/go-loadtool/internal/report/report.go`
- Modify: `load-test/go-loadtool/internal/report/report_test.go`
- Modify: `load-test/run-load-test.sh`
- Modify: `load-test/tests/observable-outcome-flow-test.sh`
- Modify: `load-test/tests/profile-contract-test.sh`
- Modify: `load-test/tests/profile-selection-test.sh`
- Modify: `load-test/tests/runner-exit-test.sh`

- [ ] **Step 1: Add failing adapter tests**

Assert the adapter:

- locates `rust-loadtool` beside the running `go-loadtool` executable;
- invokes only `simulate --run-dir` plus the current internal connection/TLS overrides;
- inherits stdout/stderr for `logs/loadtool.log`;
- preserves the Rust exit status and does not render a report after simulator failure;
- strictly reads `diagnostics/loadtool/generator-metrics.json` after success;
- maps Pull batch counts into the existing `pullmetrics.Snapshot`;
- rejects missing/malformed metrics as an operational failure;
- returns the generator-violation count when `valid:false` without turning a completed experiment into an operational failure.

- [ ] **Step 2: Change `go-loadtool run` production dependency**

Replace `sim.Run` in the active command with `rustsim.Run`. Keep the Go `internal/sim` package untouched as the temporary compatibility oracle and for provisioning derivation, but it must no longer execute from the public run path. Pass the returned Pull snapshot and generator-violation count to report rendering.

Remove the active command's pre-simulation `layout.PrepareOutputs()` call. Go continues to validate the prepared bundle, but Rust atomically takes ownership of creating `events/`, `run-window.json`, and generator metrics. This avoids presenting Rust with a half-created output bundle and ensures one component owns simulator outputs.

Do not add a public engine flag. Unit tests inject the simulator process boundary exactly as they currently inject `simulate`.

- [ ] **Step 3: Preserve report ordering and exit behavior**

The active sequence remains:

```text
Rust simulate
→ read generator/Pull metrics
→ Go report
→ atomic sla-report.json
```

Add an internal `GeneratorViolations` report option whose zero value preserves all existing Go tests. `Summary.Valid` must also require that count to be zero, without copying the detailed generator taxonomy into `sla-report.json`. A completed generator or SLA violation still produces `sla-report.json` and Go exits zero; `run-load-test.sh` remains responsible for returning public exit code 1 when `valid:false`. Operational Rust/adapter failures remain exit code 2 through the runner after diagnostics collection.

- [ ] **Step 4: Build sibling binaries in one temporary directory**

Extend `build_loadtool` to produce:

```text
<temporary>/go-loadtool
<temporary>/rust-loadtool
```

Use a persistent Cargo target cache outside the result directory and copy only the release binary into the temporary directory. Preserve shell-test stubbing by setting both binary variables in test drivers; do not require Cargo in tests that mock the build phase.

- [ ] **Step 5: Run Go and shell tests**

```bash
cd load-test/go-loadtool
GOPATH=/tmp/go GOCACHE=/tmp/go-build-cache go test ./...

cd ..
for test in tests/*-test.sh; do bash "$test"; done
bash -n run-load-test.sh prepare-performance-environment.sh scripts/*.sh
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add load-test/go-loadtool load-test/run-load-test.sh load-test/tests
git commit -m "feat: run load simulations with the Rust engine"
```

---

## Task 10: Prove functional equivalence and generator qualification

**Files:**

- Modify only if evidence reveals a defect: files introduced by Tasks 1–9
- Do not commit generated result directories

- [ ] **Step 1: Run the short mixed-outcome smoke through the final public path**

```bash
cd load-test
./run-load-test.sh --profile mixed-outcomes-smoke rust-functional-smoke
```

Acceptance:

- runner exit 0 and `sla-report.json.valid == true`;
- both scenario outcomes match;
- PACS.008/PACS.002 replay counts are separately valid;
- Pull batches never exceed 15;
- four CSVs, run window, generator metrics, and report exist;
- no Rust generator violation is present.

- [ ] **Step 2: Run the one-minute capacity A/B counterpart**

```bash
./run-load-test.sh --profile mixed-outcomes-2k-diagnostic rust-migration-rust-candidate
```

Compare with Task 1 using a checked-in analysis command or one-off `jq`, not a new reporting layer. Acceptance:

- report valid;
- active `minimum_observed_tps >= 2000`;
- zero active PACS.008 missed slots and zero late semantic admissions;
- no catch-up: a missed slot is never emitted in a later bucket;
- `http_start_lateness` p99 remains below the 1 ms admission envelope;
- no hidden stream-capacity backlog or causal-capacity violation;
- process CPU time and maximum RSS are recorded and do not regress by more than 10% from the Go baseline; at least one improves materially;
- functional counts and at-least-once outcomes remain equivalent, allowing different concrete replayed EndToEndIds as specified.

- [ ] **Step 3: Qualify the 50 us spin tail**

If Step 2 misses slots solely because the pacer wakes late while CPU is otherwise available, test one smaller and one larger internal spin value in separate candidate commits/runs. Keep the simplest value that yields zero misses without material generator CPU increase. Revert experimental values; do not expose a profile knob.

If the initial 50 us value passes, make no tuning change.

- [ ] **Step 4: Run the 15-minute profile once**

Only after the one-minute qualification passes:

```bash
./run-load-test.sh --profile mixed-outcomes-2k-15m rust-loadtool-15m
```

Acceptance is the same as Step 2 for the full active window. This run proves sustained pacing; it does not migrate reporting or remove Go yet.

- [ ] **Step 5: Fix only evidence-backed defects, then rerun the smallest relevant test**

Do not add worker pools, buffer pools, manual JSON, queue-size knobs, or runtime tuning in response to a single noisy host run. First distinguish generator violation, SPI SLA violation, and host interference using the new metrics.

- [ ] **Step 6: Run final automated verification**

```bash
cd load-test/rust-loadtool
cargo fmt --check
cargo test
cargo clippy --all-targets -- -D warnings

cd ../go-loadtool
GOPATH=/tmp/go GOCACHE=/tmp/go-build-cache go test ./...

cd ..
for test in tests/*-test.sh; do bash "$test"; done
bash -n run-load-test.sh prepare-performance-environment.sh scripts/*.sh

cd ..
git diff --check
git status --short
```

Expected: every automated check passes; status contains only intended implementation files plus the user's preserved pre-existing changes.

- [ ] **Step 7: Commit any final evidence-backed corrections separately**

Use a message describing the concrete correction; do not create a generic “cleanup” commit.

---

## Task 11: Handoff to the final Rust cutover plan

**Files:**

- Create: `docs/superpowers/plans/2026-08-25-rust-load-tool-final-cutover.md`
- Modify: `docs/superpowers/specs/2026-08-25-rust-load-tool-greenfield-design.md`

- [ ] **Step 1: Record qualified decisions in the architecture spec**

Replace only the now-resolved pending values:

- official qualification environment;
- accepted spin tail;
- causal-capacity observed peak/headroom;
- one-minute and 15-minute A/B evidence;
- any portability limitation discovered.

Keep raw result paths out of the architectural contract; link concise experiment evidence if it is retained elsewhere.

- [ ] **Step 2: Write the separate final-cutover plan**

The next plan covers only:

- Rust `validate-profile` and execution-plan generation;
- Rust report parity;
- direct runner invocation of Rust;
- deletion of the temporary Go adapter and the complete Go load-tool;
- removal of temporary connection-override duplication;
- final CLI/documentation cleanup.

It must not reopen the simulator architecture or add permanent support for two engines/historical reporting.

- [ ] **Step 3: Verify documentation**

```bash
git diff --check -- \
  docs/superpowers/specs/2026-08-25-rust-load-tool-greenfield-design.md \
  docs/superpowers/plans/2026-08-25-rust-load-tool-final-cutover.md
```

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-08-25-rust-load-tool-greenfield-design.md \
  docs/superpowers/plans/2026-08-25-rust-load-tool-final-cutover.md
git commit -m "docs: plan the final Rust load tool cutover"
```
