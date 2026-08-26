# Rust load-tool boundaries

## Purpose

Complete the load-tool migration to Rust while making the load generator smaller
and protecting its hot path from reporting concerns.

The guiding principle is simplicity through adherence to purpose:

> The generator produces the configured workload at a temporally valid rate,
> drives the causal protocol required by that workload, and records facts. The
> reporter interprets those facts only after generation has stopped.

The migration is primarily a maintenance and cognitive-complexity change. Profile
validation and report rendering happen outside the measured window, so moving
them to Rust is not expected to improve SPI performance directly.

## Chosen boundary

Use Cargo crate boundaries rather than relying only on source modules:

```text
rust-loadtool CLI
        |
        +-- loadtool-generator
        |        `-- loadtool-contract
        |
        +-- loadtool-report
        |        `-- loadtool-contract
        |
        `-- profile/bundle orchestration
                 `-- loadtool-contract
```

The dependency graph must not contain an edge from `loadtool-generator` to
`loadtool-report`. The CLI is the composition root and is the only component
that knows both implementations.

`loadtool-contract` contains only persisted contracts and neutral value types:

- profile and normalized execution-plan documents;
- run bundle layout;
- event row schemas;
- run-window document;
- generator-metrics document;
- SLA report document where a shared serialized type is useful.

It must not contain pacing, networking, report aggregation, environment
orchestration, or business validation algorithms. It must not become a generic
utility package.

The root application keeps profile resolution, strict profile validation,
execution-plan creation, and command orchestration. These responsibilities may
remain ordinary modules in the CLI package; they do not need another crate
unless reuse later demonstrates that need.

## Runtime lifecycle

The final public command remains a single load-tool operation, but generation
and reporting are separate sequential stages:

```text
resolve and validate profile
prepare the run bundle
run generator
stop accepting work
cancel remaining technical tasks
close Pulls
close and wait for TaskTracker
close recorder and flush artifacts
drop generator runtime
run reporter from persisted artifacts
atomically publish sla-report.json
```

The reporter never runs concurrently with warmup, active load, or drain. It
does not receive callbacks, channels, counters, references, or an in-memory
result graph from the generator. Its input is the completed run bundle on disk.

An operational generator failure prevents report generation. A completed run
with generator-capacity violations writes generator metrics and proceeds to the
report, which marks the result invalid. A completed report with violations is
not an operational command failure; the public shell runner preserves its
current responsibility for mapping `sla-report.json.valid` to its public exit
code.

## Generator responsibility

`loadtool-generator` owns only behavior required to generate and observe the
workload:

- absolute, no-catch-up pacing of original PACS.008 payments;
- HTTP/2 readiness, admission deadlines, and request execution;
- deterministic scenario, participant, amount, and replay selection;
- Pull consumption required to observe receiver PACS.008 and create causal
  PACS.002 messages;
- bounded causal HTTP capacity;
- payment state required for `COMMITTED` and `PACS002_CLAIMED`;
- the warmup completion gate;
- fixed drain and technical shutdown;
- single-writer recording of raw evidence;
- generator self-observation needed to qualify the offered workload.

Generator self-observation is limited to signals about the generator itself:

- planned, admitted, started, completed, and missed original slots;
- reasons for pacing or semantic admission misses;
- pacer and dispatch lateness;
- capacity violations;
- process CPU and maximum RSS;
- spin wall time;
- bounded-protocol observations such as Pull batch-size counts.

These measurements must remain cheap and must not create another queue or
periodic workload in the hot path.

The generator does not own:

- SLA thresholds or final run validity;
- rolling-throughput reconstruction;
- SPI latency percentiles;
- final counts by scenario;
- active-window outcome validation;
- missing or contradictory outcome detection for the completed run;
- replay correctness assertions;
- report rendering or visualization.

## Warmup exception

The warmup gate is generation control, not reporting. The generator must still
recognize whether a warmup payment reached its expected terminal payer outcome,
because active generation cannot start while an observable warmup obligation is
pending or contradictory.

This semantic work is restricted to warmup sequences. Active and drain
notifications are recorded without comparing their status or reason codes with
scenario expectations.

Global `PaymentState` retains only the two causal bits:

```text
COMMITTED
PACS002_CLAIMED
```

Warmup outcome deduplication and contradiction detection belong inside the
warmup gate and are allocated only for the warmup population. They must not
become a general payment lifecycle or reporting state machine.

All future warmup obligations selected deterministically for a payment remain
registered before that payment can complete its parent action. This preserves
the existing protection against a momentary zero-pending race without creating
a generic obligation tree.

## Evidence and aggregation

The generator records facts once through the existing single-writer recorder.
The individual evidence remains necessary for audit, timestamp comparison, and
later visualization.

The reporter derives from those facts:

- HTTP completion distributions;
- rolling throughput;
- business outcomes and contradictions;
- replay counts and violations;
- scenario summaries;
- final SLA validity.

The generator must stop maintaining an HTTP-duration histogram because request
start and completion timestamps already exist in evidence. HTTP start lateness
may also be reconstructed after the run when the persisted plan and timestamps
provide the required planned instant. Pacer and dispatch lateness remain
generator metrics because their underlying scheduling observations are not
otherwise persisted.

No new binary evidence format, report callback, in-memory report accumulator,
worker pool, actor system, or generic event framework is introduced.

## Environment boundary

The Rust migration applies to the load-tool domain, not to all test automation.
The shell layer continues to own:

- stack preparation;
- certificate generation;
- funds provisioning;
- JFR, PostgreSQL, container, and SPI diagnostics;
- invocation ordering and public exit-code mapping.

Docker Compose and diagnostic scripts are not ported to Rust. The normalized
`execution-plan.json` remains a persisted reproducibility artifact and the
input used by environment preparation.

## Migration strategy

Keep Go intact as an oracle until the Rust path is complete. Avoid a permanent
mixed implementation.

1. Establish the Cargo workspace boundaries without changing runtime behavior.
2. Restrict generator semantic work to warmup and remove duplicate post-run
   aggregation from the generator.
3. Port report parsing, validation, and rendering to `loadtool-report`.
4. Port profile validation and execution-plan generation to the Rust
   application boundary.
5. Switch the public runner once to the complete Rust command.
6. Verify the public smoke and a representative diagnostic workload.
7. Remove `go-loadtool`, its duplicate proto, Go-specific tests, and Go build
   integration.

The Go and Rust reporters must be compared against the same checked-in fixture
bundles before cutover. Compare parsed JSON documents rather than formatting
incidentalities. Profile contract tests must preserve rejection of malformed,
unknown, or unsafe profiles and preserve normalized provisioning values.

After cutover, the short mixed-outcomes smoke must prove:

- public runner exit behavior is unchanged;
- both business scenarios produce the expected outcomes;
- replay and Pull invariants remain valid;
- generator qualification is incorporated into final validity;
- the expected bundle artifacts are present;
- no Go binary is built or invoked.

The representative diagnostic run must additionally prove no regression in
missed slots, pacing lateness, workload correctness, or generator CPU/RSS.

## Deliberate exclusions

This work does not:

- change workload profiles or scenario semantics;
- change the bundle or SLA-report public contract unless required to remove a
  provably duplicate generator metric;
- add historical standalone report support;
- tune SPI, PostgreSQL, Kafka, or Notification Gateway;
- redesign the recorder or introduce a buffer pool without profiling evidence;
- split files merely to reduce line counts;
- add runtime-selectable Go/Rust engines.

The current planner, bounded queues, `Vec<AtomicU8>`, single native pacer,
single-writer recorder, phase tracker, and fixed drain remain unless the
migration exposes a concrete correctness problem.

## Decision summary

The final architecture has one Rust load-tool and a thin shell runner. Cargo
enforces that reporting cannot enter the generator dependency graph, and the
persisted run bundle is the only handoff between generation and reporting.
Generator cleanup removes retrospective interpretation from the measured path
without removing causal workload behavior or evidence required to validate the
experiment.
