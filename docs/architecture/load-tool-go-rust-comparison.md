# Go and Rust load-tool comparison

## Executive conclusion

The Rust rewrite achieved the performance purpose that motivated it on the tested host. In the controlled 15-minute A/B, Rust started 1,889,945 of 1,890,000 planned active payments, sustained a minimum rolling one-second throughput of 2,058 TPS, used 34.1% less process CPU time, and reached a 27.2% lower maximum RSS than the last relevant Go implementation. Go completed the workload with correct business outcomes and a 2,092.327 TPS average, but its minimum rolling throughput fell to 1,784 TPS and therefore did not prove the required sustained 2,000 TPS floor.

The rewrite did not make the codebase smaller. Rust has more handwritten production lines, more source files, and a substantially larger dependency graph. Its simplicity improvement is architectural: pacing, generation, persisted contracts, and reporting have explicit ownership, and Cargo prevents reporting from depending on the measured generator. Go remains the smaller and easier implementation for functional tests or lower rates, but it did not demonstrate repeatable qualification margin on this shared host.

This is a comparison of two implementations in this repository under one controlled environment, not a general Go-versus-Rust language benchmark.

## Primary A/B result

Both primary runs used profile `mixed-outcomes-2k-15m`, profile SHA-256 `721561af3f241a25de2d5124d9625eb06543f5ad8aa7f31072a72dbe877e104a`, core revision `c68d74de28d65b5a223d2af42156d356431c6d87`, and Gateway normalization patch SHA-256 `fb94bea3736c532569ed130bc29de4993e71a2f1e4a6f139d1c222ede0eb75cc`.

| Measurement | Go | Rust | Interpretation |
| --- | ---: | ---: | --- |
| Operational completion | yes | yes | both produced a final report |
| Planned active originals | 1,890,000 | 1,890,000 | 2,100 offered TPS for 900 seconds |
| Executed active originals | 1,883,094 | 1,889,945 | Rust omitted 55; Go omitted 6,906 |
| Planned originals not executed | 0.3654% | 0.0029% | no later catch-up compensated the omissions |
| Average active TPS | 2,092.327 | 2,099.939 | Rust was 0.364% higher |
| Minimum rolling one-second TPS | 1,784 | 2,058 | only Rust proved the 2,000 TPS floor |
| Maximum rolling one-second TPS | 2,121 | 2,121 | neither result relied on a compensating spike |
| 10 ms windows below 21 starts | 564 / 90,000 | 4 / 90,000 | common post-processing of request start timestamps |
| Empty 10 ms windows | 119 | 1 | rare stalls, rather than central tendency, explain the Go rolling minimum |
| Standard deviation of 10 ms counts | 1.123 | 0.102 | workload regularity proxy; not native scheduler lateness |
| Process user + system CPU time | 875.82 s | 576.86 s | Rust used 34.1% less CPU time |
| Maximum process RSS | 4,829,856 KiB | 3,515,468 KiB | Rust peak was 27.2% lower |
| Process wall time | 1,085.94 s | 1,079.14 s | dominated by fixed warmup, active, and drain durations |
| Voluntary context switches | 10,444,737 | 7,567,381 | Rust had 27.5% fewer |
| Involuntary context switches | 2,325,345 | 1,436,714 | Rust had 38.2% fewer |
| Overall outcome latency p50 | 164.784 ms | 152.620 ms | system-level observation, not generator-only cost |
| Overall outcome latency p95 | 339.797 ms | 262.829 ms | same caveat |
| Overall outcome latency p99 | 513.530 ms | 409.163 ms | same caveat |
| Missing or contradictory outcomes | 0 | 0 | both preserved functional correctness |

The Go report is invalid only because `minimum_observed_tps` is below the required minimum. HTTP acceptance, happy-path `ACSC`, insufficient-funds `RJCT/AM04`, replay acceptance, and the configured latency threshold all passed. The Rust report likewise has no functional violations and satisfies both the rolling throughput floor and the latency threshold.

The latency difference is descriptive. Both generators exercised the same core and Rust imposed less host overhead while offering slightly more work, which is consistent with lower generator interference, but one sequential A/B cannot isolate generator CPU as the sole cause of the SPI latency difference.

## Historical repeatability context

The controlled A/B is the primary comparison, but the retained history adds an important qualification. An earlier Go run, `loadtool-bounded-state-15m/20260824_115328`, did cross the floor once: it averaged 2,097.301 TPS, reached a minimum rolling throughput of 2,003 TPS, kept p99 at 560.538 ms, and completed every expected outcome and replay. That run proves that Go was capable of a passing sample; it does not make the final controlled failure impossible or contradictory.

The issue was repeatability and margin. Other Go 15-minute runs with correct business outcomes produced rolling minima of 1,330, 1,844, 1,934, 1,966, and 1,986 TPS, while a six-minute diagnostic reached 2,009 TPS. Short or isolated passes therefore did not predict sustained 15-minute regularity reliably. By contrast, the two historical Rust qualifications both reached 2,079 TPS, and the controlled Rust run reached 2,058 TPS. The durable conclusion is not that Go could never meet 2,000 TPS; it is that the Rust architecture provided repeatable temporal margin for this shared-host qualification.

These historical runs used evolving core and generator revisions, so they are evidence of repeatability across the project history, not additional samples in the controlled A/B or a basis for a language-wide performance claim.

The retained Go runtime diagnostics help attribute that variability without turning them into another benchmark. In the 15-minute diagnostic, the process reached 3,000 goroutines, 657 GC cycles, 60.7 MiB of live heap, 57.5 seconds of aggregate GC CPU, and a sampled scheduler-latency p99 maximum of 83.9 ms. The mutex profile accumulated 1,173 seconds of wait across goroutines, with substantial cumulative attribution to original admission (`219.7 s`), HTTP posts (`217.9 s`), PACS.002 submission (`200.3 s`), and Pull consumption (`117.8 s`). These are overlapping aggregate wait totals, not elapsed wall time, and profiling accompanied a particularly poor `1,251 TPS` rolling minimum. They nevertheless show that the residual cost was distributed across shared coordination and runtime/network work rather than one removable GC or serialization hotspot.

### What the Rust design iterations established

The retained bundles show that the result did not come from the language switch alone. The first 1 ms prototype started only 215,123 of 246,000 planned originals in its final gated variant and missed 30,877 slots. Moving to absolute 10 ms buckets reduced the misses to 1,170, but still left generator p99 lateness at 5.997 ms. A bucket coordinator reduced misses again to 104.

The next experiments isolated the remaining misses. A larger pacer channel reproduced the same 21 misses as the preceding admission diagnostic. CPU pinning produced 29 misses. Extending the spin tail to 1 ms reduced misses to 12, but consumed 16.012 seconds of accumulated spin time instead of a few milliseconds. A wait diagnostic observed only one late sleep return while 26 requests missed before commit, showing that operating-system wake-up was not the dominant remaining cause.

The decisive change was ownership, not more aggressive timer tuning. With a shared planner preparing work before the pacing boundary, `rust-shared-planner-diagnostic/20260826_175105` executed all 246,000 planned originals, reduced pacer p99 to 0.244 ms and HTTP-start p99 to 0.228 ms, and reduced generator user CPU from roughly 211–220 seconds in the immediately preceding variants to 37.570 seconds. The later complete cutover preserved zero misses and ended at 0.322 ms pacer p99, 0.288 ms HTTP-start p99, 59.6 MiB maximum RSS, 84 maximum original requests in flight, and 291 maximum causal requests in flight.

These are sequential design experiments rather than a controlled language benchmark. Their durable result is narrower: deadline-sensitive admission became reliable only after request planning and HTTP/2 preparation had a single explicit owner before the temporal boundary. Increasing queues, pinning a CPU, or spending more CPU in spin did not solve that ownership problem.

### What profiling did not justify

The final CPU profile had no dominant application symbol: the largest individual samples were allocator and runtime/network functions, with `malloc` at 1.36%, `free` at 1.19%, and HTTP/2 HPACK table lookup at 0.64%. Heaptrack observed 47.59 million allocation calls, including repeated recorder formatting and HTTP/2 request preparation, but the profiler itself raised peak RSS to 491 MiB and the run's p99 outcome latency to 718.061 ms. It was therefore treated as attribution evidence, not a performance run.

The profile did not justify adding a buffer pool, custom allocator, manual JSON encoder, or another hot-path subsystem. The unprofiled diagnostic already met the workload with about 59.6 MiB RSS and low pacer overhead. This is a negative result worth preserving: allocation count alone was not sufficient reason to trade the simpler typed implementation for more memory-management machinery.

### Why Rust performed better in this comparison

The decisive advantage was temporal regularity rather than average throughput. Rust's average active rate was only 0.364% higher, but it reduced empty 10 ms intervals from 119 to one and planned active omissions from 6,906 to 55. The result is consistent with the architectural differences between the generators:

* Rust has one native pacer operating on absolute 10 ms buckets, with no catch-up. Tokio prepares HTTP/2 work before its temporal boundary, so the pacer performs admission rather than request construction.
* Go coordinates fixed pools of 2,100 original workers and 2,100 status workers through channels and shared simulator state. Rust has no equivalent fixed pool of thousands of workers and uses bounded async work instead.
* Rust keeps causal payment state in a preallocated atomic vector, while Go relies more heavily on shared maps, mutexes, and centralized lifecycle coordination.
* Rust used 27.5% fewer voluntary context switches and 38.2% fewer involuntary context switches, which supports the interpretation that less runtime coordination contributed to fewer short scheduling stalls.
* Rust used 34.1% less process CPU time and 27.2% less peak RSS. Because the generator shared the host with the measured stack, this also reduced the load tool's opportunity to interfere with the SPI, PostgreSQL, Kafka, and Gateway.

Rust's lack of garbage collection, its allocation discipline, and its async networking implementation may also contribute, but this experiment did not isolate the cost of GC, allocation, locking, scheduling, or HTTP/gRPC processing individually. The evidence therefore supports the greenfield architecture as a whole; it is not a general claim that Rust code is intrinsically faster than equivalent Go code.

## Temporal regularity

The native reports use the same two-pointer definition for minimum rolling throughput: every candidate continuous one-second interval inside the active window is considered, so a later peak cannot compensate for an earlier deficit. The Rust report intentionally publishes only the minimum; the common post-processing also reconstructed the maximum as 2,121 TPS for Rust.

Fine-grained pacer lateness is not directly comparable because the historical Go artifact did not persist the same native pacer metric. Instead, the comparison reconstructed actual HTTP starts in 90,000 fixed 10 ms intervals, matching the current pacing envelope. Go uses `run-window.json.window.active_started_at`; Rust uses the planned `created_at_ns` of the first active sequence, which the Rust recorder derives from `PlannedOriginal.bucket_start`. Only `request_started_at_ns` inside the following 900 seconds is counted.

For both implementations p01, p05, p50, p95, and p99 were 21 starts per 10 ms, and neither exceeded 21. The material difference is in rare stalls: Go produced 119 empty intervals and 564 intervals below 21, while Rust produced one empty interval and four below 21. This explains why averages look similar while the rolling minimum differs substantially.

## Functional smoke

Before the long campaign, both binaries passed a fresh-stack `mixed-outcomes-smoke` run. Each executed all 1,050 active originals, reached a rolling minimum of 103 TPS against a 100 TPS floor, accepted every payment, and matched every business outcome.

| Smoke process measurement | Go | Rust |
| --- | ---: | ---: |
| User + system CPU time | 2.86 s | 1.99 s |
| Maximum RSS | 85,944 KiB | 29,812 KiB |
| Wall time | 50.52 s | 50.37 s |

The smoke confirms functional compatibility, not high-rate performance. The exact PACS.002 replay members are derived differently: Go emitted 50 and Rust 52 in the short population. This is a small workload difference inherited from their selector implementations; payload and replay identity are not member-for-member equivalent across the two run identities.

## Protocol defect found during the campaign

The first two Rust 15-minute attempts were excluded from performance comparison because they ended operationally with `FAILED_PRECONDITION: only one pull may be active per PSP`. The first failed near the end of active load for PSP `20000027`; the clean repetition failed during warmup for PSP `20000002`.

Static inspection and the two reproductions identified a Gateway lifecycle race. `NotificationGrpcService` called `responseObserver.onCompleted()` while the PSP session was still registered, and the `try-with-resources` removed the session only after the callback returned. The faster Rust loop could observe completion and issue its next sequential Pull before removal, which the Gateway misclassified as a concurrent Pull. Go contains the same fatal handling for `FAILED_PRECONDITION`, but its slower turnaround did not reach the race in these runs.

The normalization is one lifecycle operation: release the session after `onNext` and before publishing completion. A regression test performs the next sequential Pull directly from the first observer's completion callback. All 41 Notification Gateway tests passed. Because this changed the measured core, both final Go and Rust runs were recreated from empty volumes and rerun against the identical patch. Each final bundle contains `inputs/core.patch` and its manifest records the patch digest; the correction was subsequently incorporated into Git together with the comparison documentation.

## Implementation inventory

Counts are physical lines and files, including comments and blanks. Go generated protobuf files are separated from handwritten production code. Rust checked-in benchmarks and dedicated integration tests are separated from production sources; two small inline unit-test modules remain inside Rust production files.

| Inventory | Go at `1d80cedf00e5905b24c515cd7d5dc12d2207cc22` | Rust at `c68d74de28d65b5a223d2af42156d356431c6d87` |
| --- | ---: | ---: |
| Handwritten production files | 22 | 33 |
| Handwritten production lines | 5,508 | 6,430 |
| Checked-in generated production files / lines | 2 / 361 | 0 / 0 |
| Dedicated test files / lines | 28 / 5,946 | 16 / 2,493 |
| Tests executed successfully | 192 | 83 |
| Checked-in benchmark files / lines | 0 / 0 | 1 / 180 |
| Direct production dependencies | 2 | 19 |
| Checksummed or locked external packages | 18 | 157 |
| Release binary size | 18,483,187 bytes | 7,824,448 bytes |

The Rust production implementation is approximately 16.7% larger than handwritten Go by this coarse measure and is spread across 50% more files. Its binary is 57.7% smaller, but its third-party dependency surface is much larger because async I/O, HTTP/2, TLS, gRPC, serialization, and time handling are supplied by crates that Go largely obtains from its standard library and two direct gRPC/protobuf modules.

Test-count differences do not imply coverage differences. Go accumulated many unit tests around mechanisms later removed, while Rust emphasizes integration contracts around pacing, HTTP/2 admission, bundle boundaries, deterministic workload, lifecycle, and report reconstruction.

### Complexity trade-off

The rewrite reduced one kind of complexity while increasing another:

* Go is smaller and locally easier to read, build, and onboard into. It has fewer files, far fewer dependencies, and uses a more familiar concurrency model.
* Go's accumulated complexity is concentrated and interaction-heavy. Its main simulator owns pacing, networking, Pull consumption, payment state, replay coordination, worker pools, lifecycle, and error propagation, with shared maps and mutexes connecting those responsibilities.
* Rust is larger and requires knowledge of Tokio, atomics, bounded channels, explicit task lifecycle, protobuf build generation, and a substantially broader dependency graph.
* Rust nevertheless has simpler responsibility-level reasoning: contract, measured generation, persisted evidence, and retrospective reporting have enforceable boundaries; pacing has one authority; queues expose their capacity; and payment-state ownership is explicit.

Consequently, Rust did not reduce total source or ecosystem complexity. It exchanged local and onboarding simplicity for lower accidental complexity in timing, concurrency, ownership, and lifecycle. Go remains the simpler choice for functional smokes or lower-rate diagnostics. Rust is the simpler architecture for this repository's stricter requirement: evolving a qualifying generator while preserving sustained temporal behavior.

## Architectural comparison

### Go

Go has the smaller ecosystem surface and a conventional package layout. Reporting is already invoked after simulation, and `internal/report` does not enter the simulator directly. The main maintenance problem is concentration: `internal/sim/simulator.go` has 1,224 lines and owns networking, Pull consumption, payment state, replay coordination, worker channels, lifecycle, and error propagation. The runtime keeps shared maps and several mutexes, and the 15-minute log shows fixed pools of 2,100 original workers and 2,100 status workers. Configuration and reporting are also concentrated in 729-line and 680-line files.

This implementation was reasonable while the workload contract was still being discovered. It remains easy to build, uses few dependencies, and passed every functional check. Its weakness is that scheduler, worker, allocation, and reporting evolution accumulated in the same process without a hard boundary protecting the measured generator.

### Rust

Rust makes the ownership boundaries explicit with four Cargo packages: the application composition root, `loadtool-generator`, `loadtool-contract`, and `loadtool-report`. A dependency test enforces that the generator cannot depend on the reporter. The only handoff from generation to interpretation is the persisted run bundle, and report aggregation starts after the generator and recorder have closed.

The hot path has a single native pacer using absolute 10 ms buckets and no catch-up. Tokio prepares HTTP/2 requests before their bucket, the pacer admits only prepared requests at the temporal boundary, payment causal state uses a preallocated atomic vector, and recorder and causal capacities are bounded. There is no fixed pool of thousands of idle workers. The largest checked-in production files are `profile.rs` at 644 lines, `model.rs` at 527, `notification_flow.rs` at 508, and `original.rs` at 475.

The cost is additional machinery: async Rust, explicit task lifecycle, atomics, bounded-channel failure semantics, build-time protobuf generation, and 19 direct production crates. A maintainer needs more specialized knowledge than for the Go implementation. The architecture is easier to reason about at the responsibility level, but it is not syntactically or operationally simpler in every local function.

## Separate conclusions

### Performance and predictability

Rust wins this dimension for the measured requirement. It met the sustained 2,000 TPS floor with 55 omitted slots, while Go omitted 6,906 and produced 119 empty 10 ms intervals. Rust also reduced total process CPU time, peak RSS, and context switching materially. The result supports keeping Rust as the qualifying generator.

### Simplicity

The result is mixed. Go is smaller, has fewer files, and has dramatically fewer dependencies. Rust has the simpler ownership model for the problem that matters: generation is isolated from reporting, pacing has one authority, queues are bounded, and retrospective validation cannot enter the measured path. The rewrite traded local and ecosystem simplicity for architectural and temporal clarity.

### Maintenance

For occasional functional evolution, Go would be cheaper to onboard and update. For future workload evolution under a strict timing contract, Rust provides safer boundaries and more focused tests. Rust also removes the need to reason about large fixed worker pools and shared mutable simulator state. Its dependency graph and async/toolchain expertise are continuing maintenance costs that should be acknowledged rather than hidden.

### When Go would still be sufficient

The Go implementation is sufficient for functional smokes, lower-rate diagnostics, and environments where the generator has ample isolated CPU and memory and sustained sub-second regularity is not itself a qualification requirement. It even produced one historical passing 15-minute sample. It was not retained for this repository's current shared-host proof because it failed the rolling floor in the controlled final A/B and did not demonstrate repeatable margin across the long-run history, despite passing averages and correct outcomes.

## Methodology and reproducibility

The host was Linux `6.12.86+deb13-amd64`, an Intel Core i7-11390H with four physical cores/eight logical CPUs and 15 GiB RAM. The load tool and stack shared this host. Compose limited Kafka, Kafka Producer, Notification Gateway, PostgreSQL, and SPI to one CPU each; their memory limits were 2 GiB, 384 MiB, 512 MiB, 512 MiB, and 768 MiB respectively. Diagnostics were enabled identically for both sides.

Every run used `prepare-performance-environment.sh`, which removed the preceding stack and volumes, rebuilt images, waited for readiness, provisioned balances, and generated fresh PSP certificates. The final order was Go first and Rust second. Both therefore began with empty data and cold service processes, but the order was not counterbalanced.

The historical Go source was extracted without restoring it into the current tree:

```bash
git archive 1d80cedf00e5905b24c515cd7d5dc12d2207cc22 load-test/go-loadtool load-test/profiles load-test/testdata | tar -x -C "$temporary_root"
cd "$temporary_root/load-test/go-loadtool"
GOCACHE="$temporary_root/go-build-cache" go test ./...
go build -trimpath -buildvcs=false -o "$temporary_root/go-loadtool" ./cmd/go-loadtool
```

The current Rust workspace was validated with:

```bash
cd load-test/rust-loadtool
cargo test --locked --workspace
cargo build --locked --release
```

The source inventory used `find` restricted to production or test suffixes, `wc -l` for physical lines, `go test -json` and `cargo test --workspace` for executed tests, `go.mod` and `cargo metadata --no-deps` for direct dependencies, unique module/package names in `go.sum` and `Cargo.lock` for the locked surface, and `stat -c %s` for binary bytes. The raw source trees are identified by the revisions in the inventory table, so the counts can be reconstructed without retaining the temporary extraction directory.

The final evidence is in:

```text
load-test/results/compare-go-15m-fixed/20260828_214501
load-test/results/compare-rust-15m-fixed/20260828_220502
```

Each `inputs/comparison-manifest.json` records engine, profile, core revision, patch digest, generator revision, binary digest, and start time. `/usr/bin/time -v` output is under `diagnostics/loadtool-process.txt`; workload and outcome values come from `sla-report.json`; common temporal reconstruction uses `events/pacs008-starts.csv`.

Supporting but non-primary evidence is retained in the two successful smokes, the first complete Go 15-minute run, and the two excluded Rust failures:

```text
load-test/results/compare-go-smoke/20260828_204740
load-test/results/compare-rust-smoke/20260828_205322
load-test/results/compare-go-15m/20260828_205556
load-test/results/compare-rust-15m/20260828_211542
load-test/results/compare-rust-15m-repeat/20260828_213542
```

## Limitations

* There is one primary long sample per implementation. The large direction of the result is consistent with the earlier Go run and historical Rust qualification runs, but this campaign does not estimate confidence intervals or a precise population effect size.
* The runs were sequential rather than alternated. Fresh stacks remove persisted workload state but cannot eliminate host temperature, CPU frequency, kernel scheduling, or unrelated background variation.
* The generator shared the host with the measured stack. This is the deployment the project currently needs to support, but a dedicated load-generator host would better isolate SPI capacity from generator overhead.
* Maximum RSS and CPU time cover the whole command, including post-run report reconstruction. They are valid total-tool costs but not pure hot-path generator measurements.
* JFR and PostgreSQL/container diagnostics impose non-zero overhead. The wrapper and settings were identical in both final runs.
* The exact payment identifiers and replay members differ between implementations. The profile, aggregate scenario shares, offered rates, replay shares, delays, participant topology, and expected outcomes are identical.
* The Gateway lifecycle patch is not part of the referenced commit. Its exact binary diff is preserved in both final bundles, and the report must not be used without that patch digest.
* The comparison proves behavior only for this profile, host, core revision, and resource envelope. It does not establish a universal advantage for Rust or guarantee the same deltas on another machine.
