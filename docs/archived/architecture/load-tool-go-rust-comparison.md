# Go and Rust load-tool comparison

## Question

The Go generator was functionally correct and had produced an occasional passing long run. The unresolved question was narrower:

> Which implementation could preserve the required temporal workload more predictably on the shared benchmark host?

This is a repository-specific comparison of two architectures. It is not a general Go-versus-Rust language benchmark.

## Controlled result

Both 15-minute runs used `mixed-outcomes-2k-15m`, profile SHA-256 `721561af3f241a25de2d5124d9625eb06543f5ad8aa7f31072a72dbe877e104a`, core revision `c68d74de28d65b5a223d2af42156d356431c6d87`, and Gateway normalization patch SHA-256 `fb94bea3736c532569ed130bc29de4993e71a2f1e4a6f139d1c222ede0eb75cc`.

| Measurement | Go | Rust |
| --- | ---: | ---: |
| Planned active originals | 1,890,000 | 1,890,000 |
| Executed active originals | 1,883,094 | 1,889,945 |
| Planned originals omitted | 6,906 | 55 |
| Average active TPS | 2,092.327 | 2,099.939 |
| Minimum rolling one-second TPS | 1,784 | 2,058 |
| Empty 10 ms windows | 119 | 1 |
| 10 ms windows below 21 starts | 564 | 4 |
| Process CPU time | 875.82 s | 576.86 s |
| Maximum RSS | 4,829,856 KiB | 3,515,468 KiB |
| Voluntary context switches | 10,444,737 | 7,567,381 |
| Involuntary context switches | 2,325,345 | 1,436,714 |
| End-to-end p99 | 513.530 ms | 409.163 ms |
| Missing or contradictory outcomes | 0 | 0 |

Both completed the business workload correctly. Go failed only the sustained throughput floor: its average exceeded 2,000 TPS, but rare stalls created one-second windows below the requirement. Rust met throughput, latency, and correctness together.

The strongest difference was temporal regularity, not average rate. Both had a maximum rolling throughput of 2,121 TPS and the same p01, p05, p50, p95, and p99 of 21 starts per fixed 10 ms interval. The distributions diverged only in the rare gaps that determine the rolling minimum.

## Why architecture mattered

### Go

The Go implementation evolved while the workload contract was still being discovered. It remained smaller, used fewer dependencies and was easier to build and onboard into. Its cost was concentrated ownership:

- one main simulator coordinated pacing, networking, Pull, payment state, replay, lifecycle and errors;
- fixed pools created 2,100 original workers and 2,100 status workers;
- shared maps, channels and mutexes connected those responsibilities;
- reporting and configuration also accumulated in large files.

The historical runtime profile showed that residual wait was distributed across admission, HTTP submission, `pacs.002` and Pull rather than one removable lock. Local allocation and recorder improvements reduced cost but did not make the long rolling floor repeatable.

### Rust

Rust was treated as a greenfield architecture:

- one native pacer owns absolute 10 ms buckets and never catches up;
- Tokio prepares HTTP/2 work before the temporal boundary;
- admission reserves explicit HTTP/2 capacity before the payment is committed;
- queues and causal capacities are bounded;
- payment state lives in a preallocated atomic vector;
- generation, persisted contract and retrospective reporting are separate Cargo packages with enforced dependency direction.

There is no equivalent fixed pool of thousands of idle workers. The pacer admits prepared work instead of constructing requests at the deadline.

## What the Rust iterations established

The first Rust prototype did not qualify merely because it used Rust. A 1 ms design missed 30,877 of 246,000 planned originals. Absolute 10 ms buckets reduced that to 1,170; a bucket coordinator reduced it to 104.

Increasing the pacer channel reproduced 21 misses. CPU pinning produced 29. A 1 ms spin tail reduced misses to 12 but consumed 16.012 seconds of accumulated spin time. A wait diagnostic found only one late sleep return while 26 requests missed before commit.

The decisive change was ownership. A shared planner prepared work before the bucket and allowed all 246,000 planned originals to execute, with pacer p99 of 0.244 ms and HTTP-start p99 of 0.228 ms. The final cutover preserved zero misses in that diagnostic family with approximately 59.6 MiB maximum RSS.

The conclusion is therefore architectural: deadline-sensitive admission became reliable when planning and HTTP/2 preparation had one explicit owner before the boundary. Larger queues, affinity and additional spin did not solve that problem.

## Complexity trade-off

| Dimension | Go | Rust |
| --- | --- | --- |
| local implementation | fewer files, dependencies and specialized concepts | more files, crates, async and atomic machinery |
| ownership | responsibilities concentrated and joined by shared mutable state | pacing, generation, evidence and reporting have explicit boundaries |
| onboarding | simpler toolchain and concurrency model | requires Tokio, bounded channels, atomics and Cargo workspace knowledge |
| temporal behavior | occasional passing run, but weak repeatable margin | repeated rolling margin on this host |
| future workload changes | inexpensive for functional or lower-rate work | safer when temporal validity is itself part of qualification |

Rust did not reduce total source or ecosystem complexity. It exchanged local simplicity for lower accidental complexity in timing, coordination and lifecycle. That trade was justified by this repository's qualifying requirement; it would not automatically be justified for a functional smoke tool.

## Negative results retained

Profiling did not justify a custom allocator, buffer pool or manual JSON encoder. Heaptrack observed 47.59 million allocation calls, but raised peak RSS to 491 MiB and p99 to 718.061 ms. The normal diagnostic already met the workload with much lower memory and no dominant application CPU symbol.

Allocation count alone was therefore insufficient reason to add memory-management machinery. Profiling was used to attribute cost, not as a qualifying run.

## Protocol defect exposed by the comparison

The first two Rust long attempts failed with `FAILED_PRECONDITION: only one pull may be active per PSP`. The Gateway completed the gRPC callback before releasing the PSP session. Rust's faster sequential Pull turnaround reached that race; Go did not in the observed runs.

The fix releases the session after `onNext` and before publishing completion. Both final A/B runs were recreated from empty environments against the same patch, whose digest is recorded above. The correction was subsequently incorporated into Git with the comparison documentation.

This failure was not counted as evidence against either generator. It identified a core protocol defect, corrected it and forced both sides of the comparison to be rerun under the same state.

## Historical context

Go had one earlier passing 15-minute sample at 2,003 minimum rolling TPS and several functionally correct runs at 1,330, 1,844, 1,934, 1,966 and 1,986 TPS. Rust produced two earlier qualifications at 2,079 TPS and the controlled result at 2,058 TPS.

Those samples used evolving core and generator revisions. They support the interpretation about repeatability, but are not extra observations in the controlled A/B.

## Evidence and limits

The versioned [Go report](../../performance/evidence/2026-08-29/go-comparison-sla-report.json), [Rust report](../../performance/evidence/2026-08-29/rust-comparison-sla-report.json), common profile, execution plan and checksums are described in the canonical [performance documentation](../../performance.md).

Important limits:

- there is one primary long run per implementation, not a distribution or confidence interval;
- Go ran first and Rust second; the order was not counterbalanced;
- both shared the host with the stack, so lower generator overhead may also reduce interference with the measured system;
- process CPU and RSS include the whole command, including post-run reporting;
- exact payment IDs and replay members differ, although rates, scenario shares, topology and expected outcomes are equivalent;
- the comparison supports this architecture on this host and profile, not an intrinsic language-wide performance ranking.

The final capacity claim is independent. It is supported by two consecutive Rust qualification runs on commit `1351ea5`, as documented in [performance and evidence](../../performance.md).
