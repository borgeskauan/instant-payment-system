# Performance and evidence

## Claim

On the documented local environment, the single-instance core sustained at least 2,000 original payments per second throughout a 15-minute active window while preserving the expected payment outcomes and replay invariants.

Two consecutive qualifying runs exercised the same clean revision, profile, normalized plan, resources, and instrumentation. Each run started from a newly prepared environment.

| Result | Run A | Run B |
| --- | ---: | ---: |
| Planned / executed originals | 1,890,000 / 1,889,369 | 1,890,000 / 1,890,000 |
| Active average | 2,099.299 TPS | 2,100.000 TPS |
| Minimum continuous rolling second | 2,017 TPS | 2,079 TPS |
| End-to-end p50 / p95 | 188 / 598 ms | 142 / 234 ms |
| End-to-end p99 / maximum | 855 / 1,578 ms | 265 / 693 ms |
| PACS.008 replays sent / accepted | 100,422 / 100,422 | 100,472 / 100,472 |
| PACS.002 replays sent / accepted | 80,326 / 80,326 | 80,373 / 80,373 |
| Functional / replay violations | 0 / 0 | 0 / 0 |

The qualification threshold was a minimum rolling 2,000 TPS and end-to-end p99 below one second. Both runs passed independently. The result is not “2,100 TPS on average”: a later peak cannot compensate for a deficient earlier window.

## What was tested?

The qualifying profile is [mixed-outcomes-2k-15m](../load-test/profiles/mixed-outcomes-2k-15m.json):

| Phase or behavior | Contract |
| --- | --- |
| Warmup bootstrap | 500 TPS for 60 seconds; 30-second causal request timeout |
| Warmup steady | 1,500 TPS for 60 seconds; 5-second causal request timeout |
| Warmup completion gate | up to 120 seconds for obligations observed by the generator |
| Active | 2,100 offered originals/s; 2,000 required rolling floor; 15 minutes |
| Drain | fixed 30 seconds |
| PACS.008 replay | 5% additional load; 10-second delay |
| PACS.002 replay | 5% additional load; 10-second delay |

The business mix is:

| Scenario | Share | Externally observed result |
| --- | ---: | --- |
| Successful payment | 80% | HTTP 2xx followed by payer PACS.002 ACSC |
| Insufficient funds | 20% | HTTP 2xx followed by payer PACS.002 RJCT with AM04 |

Traffic is skewed: 80% of each scenario targets its configured hot pairs. Replays are additional messages; they never replace originals counted toward the throughput floor. The generator expects at-least-once notification delivery, so one or more matching outcomes are valid, while absence, contradictory status, or an incompatible rejection reason is a correctness violation.

The workload therefore exercises admission, reservation, receiver decision, settlement or rejection, audit creation, outbound notification publication, Kafka delivery, Gateway pull, and final PSP observation. HTTP 2xx proves only that ingress accepted the request; it does not count as the payment outcome.

## Measurement boundary

### Sustained load

The Rust generator follows an open-loop schedule built from absolute 10-millisecond buckets. It does not carry missed work into a later bucket. This matters because catch-up would allow a system to fall below the intended workload and later create an artificial burst while preserving only the average.

A payment is admitted only if its payload and an actual HTTP/2 stream permit are ready before the bucket deadline. The permit remains associated with the request until response, error, or timeout. This prevents an internal client queue from making a request appear started before transport capacity exists.

The generator records the actual HTTP start of admitted originals. After the experiment, the report sorts those timestamps and computes the smallest number present in every continuous one-second window fully contained in the active phase. This post-processing keeps rolling-window calculation out of the generation hot path.

The difference between planned and executed originals in Run A represents work that was not admitted within its temporal boundary. It is not hidden or shifted. Run A still qualified because its worst continuous rolling second contained 2,017 admitted originals.

### End-to-end latency

Latency begins when the simulated PSP starts the original HTTP request and ends when it observes a compatible final payer notification:

~~~text
happy path         → ACSC
insufficient funds → RJCT / AM04
~~~

Performance percentiles use originals initiated in the active phase. Correctness covers the complete run so that warmup, replay, and drain behavior cannot silently contradict active outcomes.

### Warmup

The active phase does not start merely because a fixed sleep elapsed. The generator stops creating warmup roots and waits until the work it can observe for that phase is complete, bounded by the configured gate. It does not claim to prove complete internal Kafka/SPI quiescence; doing so would require implementation-specific observability rather than deterministic generator knowledge.

The two-stage warmup exists because a cold JVM and a fully active stack are different experimental conditions. It supplies enough work for runtime compilation and connection establishment before the measured phase without allowing cold-start backlog to define the benchmark.

## Why the generator is part of the evidence

A throughput claim is invalid if the load generator changes the workload. The earlier Go tool occasionally reached the target but exhibited insufficient temporal margin and higher self-interference under the final workload.

The Rust generator was designed around one responsibility: admit original payments at valid times with low overhead. A native pacing thread produces absolute buckets; Tokio owns asynchronous HTTP/2 and gRPC I/O; reporting consumes recorded evidence after generation rather than sharing the hot path. Bounded channels and explicit causal capacity expose overload instead of accumulating hidden backlog.

The relevant result is not that Rust is universally faster than Go. It is that this architecture produced the intended workload more predictably on the available shared host. The controlled comparison is preserved in the versioned evidence alongside the final qualification, but it is separate from the capacity claim.

## Environment and resources

The load generator ran on the host and was excluded from the stack resource totals. The measured services were PostgreSQL, Kafka, Payment Ingress, SPI, and Notification Gateway.

Individual Compose limits were:

| Component | CPU limit | Memory limit |
| --- | ---: | ---: |
| PostgreSQL | 1.00 vCPU | 512 MiB |
| Kafka | 1.00 vCPU | 2,048 MiB |
| Payment Ingress | 1.00 vCPU | 384 MiB |
| SPI | 1.00 vCPU | 768 MiB |
| Notification Gateway | 1.00 vCPU | 512 MiB |

The project target of 3 vCPUs and 3 GiB was evaluated from observed active-phase consumption, not enforced through one aggregate cgroup. This distinction limits the claim: the experiment measured the budget but did not prove behavior under a hard aggregate cap.

| Observed active resource signal | Run A | Run B |
| --- | ---: | ---: |
| Aggregate average CPU | 2.094 vCPU | 1.158 vCPU |
| Largest complete CPU sample | 3.399 vCPU | 2.195 vCPU |
| Aggregate average memory | 1,824.5 MiB | 1,813.4 MiB |
| Largest complete memory sample | 1,994.6 MiB | 1,955.8 MiB |

Each run contained 699 complete docker-stats samples during active. Sampling does not establish a continuous maximum between observations.

## Interpreting the two runs

Run A was materially slower than Run B without a workload or code change. It also showed greater host and stack CPU pressure: JFR observed 60.21% average machine CPU in A versus 33.84% in B, while aggregate stack CPU averaged 2.094 versus 1.158 vCPU.

The additional pressure appeared throughout the path, especially in PostgreSQL. Similar query counts and written rows took more wall time in A, while observed locks and I/O did not explain the difference. Available telemetry could not isolate external host activity, CPU frequency, per-core scheduling, or the effect of more simultaneous work. The evidence therefore records correlation with computational pressure without asserting an unsupported root cause.

Keeping Run A increases credibility: the capacity floor and correctness held under the least favorable observed run. It does not make 265 ms a representative p99, and it does not demonstrate broad headroom. The supported p99 range is 265–855 ms, and the smallest observed throughput margin was only 17 TPS above the requirement.

## How the system reached the target

The final result came from removing accidental work while retaining the payment guarantees:

- persistent HTTP/2 connections replaced repeated TLS connection setup;
- participant-level reservations replaced synthetic liquidity buckets and two-account settlement work;
- larger Kafka and database batches reduced transaction overhead;
- persisted per-notification ACK, lease, and redelivery state was replaced by Kafka retention and PSP-owned pull cursors;
- PostgreSQL remained responsible for atomic financial state and outbox creation, while Kafka became the retained delivery path;
- the Rust load generator separated pacing, networking, recording, and reporting so the experiment itself stopped dominating temporal regularity.

These are final design reasons, not a requirement to replay every intermediate experiment. Local query microbenchmarks were used to understand mechanisms, but promotion required end-to-end evidence because reducing one query does not necessarily increase completed payment outcomes.

## Versioned evidence

The canonical evidence is under [docs/performance/evidence/2026-08-29](performance/evidence/2026-08-29/manifest.md):

- the exact profile common to both runs;
- the normalized execution plan;
- the qualifying reports for Runs A and B;
- checksums for every compact artifact;
- the clean runtime revision exercised by both runs.

The reports contain generation, scenario outcomes, latency, replay, message, and resource summaries. They are sufficient to verify the promoted claim without access to ignored load-test/results directories. Large CSVs, JFR recordings, logs, ephemeral certificates, and credentials are intentionally not part of the canonical evidence.

Executable generator contracts include:

- [temporal pacing](../load-test/rust-loadtool/tests/pacer_contract.rs);
- [real HTTP/2 admission capacity](../load-test/rust-loadtool/tests/http2_admission.rs);
- [deterministic workload and replay selection](../load-test/rust-loadtool/tests/deterministic_workload.rs);
- [report and rolling-throughput behavior](../load-test/rust-loadtool/tests/report_contract.rs);
- [end-to-end run artifact contract](../load-test/rust-loadtool/tests/run_contract.rs).

## Reproduce the experiment

Run each candidate from a newly prepared environment:

~~~bash
cd load-test
./prepare-performance-environment.sh --profile mixed-outcomes-2k-15m
./run-load-test.sh --profile mixed-outcomes-2k-15m <run-tag>
~~~

Preparation removes PostgreSQL and Kafka volumes, rebuilds the stack as needed, waits for service readiness, generates certificates, and provisions participants. It does not generate payment traffic.

The command's technical success does not itself assert qualification. Read the resulting report's executed originals, minimum rolling TPS, latency percentiles, and correctness violations against the contract above.

## What this does not prove

- The experiment ran locally with the generator and stack sharing a host.
- It qualifies one instance of each core application, not horizontal scaling or Kafka rebalancing across application replicas.
- Kafka used one broker and replication factor 1; broker, host, and volume high availability were not tested.
- The result does not establish production Brazilian Pix capacity or equivalence with its internal architecture.
- The resource budget was observed by sampling rather than enforced as one aggregate hard limit.
- Fifteen minutes demonstrates sustained behavior for the qualifying window, not one-hour or 24-hour endurance.
- Seven days is the notification log's normal recovery boundary; longer outages require a separate disaster-recovery design.
- The reference demo is not covered by the performance, durability, or availability claim.
