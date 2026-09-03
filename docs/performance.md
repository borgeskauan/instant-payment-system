# Performance and evidence

The benchmark question was direct:

> Can the system sustain at least 2,000 payments per second, finish 99% of them in less than one second, and still return every correct result?

Across two consecutive 15-minute runs, the answer was yes.

This document shows what was run, the observed results, the machine used, and the scope of the measurement. The load generator itself is explained in [How the load test works](topics/load-testing.md).

## What had to be true

A high average was not enough. Each run had to meet all three criteria at the same time:

| Criterion | Requirement |
| --- | --- |
| throughput | at least 2,000 new payments in every rolling one-second window |
| latency | end-to-end p99 below 1 second |
| results | no expected confirmation missing or contradictory, and no failures in the selected duplicates |

During the measured phase, the generator tries to start 2,100 new payments per second. Receiver responses, confirmations, and repeated messages add work to the system, but do not count toward the target.

The source of these targets and their relation to public Pix references is in the [README](../README.md).

## What happened

Both runs used commit `1351ea564d0834a66e1b5d99a5e09a1a384cae1b`, the same configuration, and the same load plan. The repository had no local changes, and the full environment was recreated before each run.

| Result | Run A | Run B |
| --- | ---: | ---: |
| planned / started payments | 1,890,000 / 1,889,369 | 1,890,000 / 1,890,000 |
| average during measured phase | 2,099.299/s | 2,100.000/s |
| lowest rolling one-second window | 2,017/s | 2,079/s |
| end-to-end p50 | 188 ms | 142 ms |
| end-to-end p95 | 598 ms | 234 ms |
| end-to-end p99 | 855 ms | 265 ms |
| highest observed latency | 1,578 ms | 693 ms |
| payment duplicates sent / accepted | 100,422 / 100,422 | 100,472 / 100,472 |
| repeated responses sent / accepted | 80,326 / 80,326 | 80,373 / 80,373 |
| missing / contradictory confirmations | 0 / 0 | 0 / 0 |
| duplicate failures | 0 | 0 |

The percentiles above combine both scenarios. An insufficient-funds rejection finishes without waiting for the receiver's decision, so the reports also keep latency for each path separately:

### Run A

| Latency | All payments | Completed payment | Insufficient funds |
| --- | ---: | ---: | ---: |
| p50 | 188 ms | 203 ms | 107 ms |
| p95 | 598 ms | 624 ms | 457 ms |
| p99 | 855 ms | 879 ms | 698 ms |

### Run B

| Latency | All payments | Completed payment | Insufficient funds |
| --- | ---: | ---: | ---: |
| p50 | 142 ms | 158 ms | 77 ms |
| p95 | 234 ms | 237 ms | 124 ms |
| p99 | 265 ms | 272 ms | 151 ms |

The completed path took more work in both runs, as expected. Run A was slower in both scenarios. This means the difference between A and B did not come only from the extra receiver-decision step. It also did not come from the share of fast rejections.

Each run met all three criteria on its own.

Run A was the less favorable one: its worst window stayed only 17 payments above the floor, and p99 reached 855 ms. Run B finished with more margin.

For that reason, showing only the 2,100 payments-per-second average or the best p99 would give an incomplete picture. Together, the two runs show this:

> The system stayed above 2,000 payments per second in both runs. In one run, 99% of payments finished within 855 ms; in the other, within 265 ms. No expected confirmation was missing or contradictory.

## What the load looked like

Before the main 15 minutes, load grows in two steps. This warm-up reduces the effect of initial connection creation, caches, and JVM startup:

| Phase | Load | Duration |
| --- | ---: | ---: |
| initial warm-up | 500 payments/s | 60 s |
| steady warm-up | 1,500 payments/s | 60 s |
| wait for warm-up completion | — | up to 120 s |
| measured phase | 2,100 payments/s | 15 min |
| shutdown | — | 30 s |

Not every payment is expected to succeed:

| Scenario | Share | Expected result |
| --- | ---: | --- |
| completed payment | 80% | money reaches the receiver |
| insufficient funds | 20% | payment is rejected without a debit |

In each scenario, 80% of traffic is concentrated on a small set of institution pairs. This makes payments compete for the same balance instead of spreading all traffic uniformly.

In addition to new payments, the test sends 5% of eligible requests and 5% of eligible responses again ten seconds later. This checks that the same message can appear again without moving money again.

## When a payment counts

A successful HTTP response only means the ingress received the message. For the benchmark, a payment finishes only when the correct final confirmation returns to the payer.

Throughput is not calculated only from the average. The report checks every rolling one-second window and keeps the lowest count. A later burst cannot hide a period below the target.

In Run A, 631 payments missed their planned window and were not started. They stayed outside the total. Even so, the worst window still contained 2,017 payments.

The generator checks the confirmations it receives and the duplicates it sends. It does not read every final balance back from PostgreSQL. The deeper financial guarantees are checked by the tests described in [Payment correctness](topics/payment-correctness.md).

## Where the test ran

The generator and all services shared the same machine:

| Specification | Value |
| --- | --- |
| CPU | Intel Core i7-11390H, 4 cores / 8 threads, up to 5.0 GHz |
| memory | 16 GB installed, about 15.4 GiB usable |
| storage | ADATA IM2P33F3A NVMe SSD, 512 GB |
| system | Debian 13, kernel `6.12.86+deb13-amd64` |
| Docker / Compose | 29.4.3 / 5.1.3 |

The test used one instance each of PostgreSQL, Kafka, Payment Ingress, Payment Processor, and Notification Gateway.

| Resources during measured phase | Run A | Run B |
| --- | ---: | ---: |
| average aggregate CPU | 2.094 vCPU | 1.158 vCPU |
| highest complete CPU sample | 3.399 vCPU | 2.195 vCPU |
| average aggregate memory | 1,824.5 MiB | 1,813.4 MiB |
| highest memory sample | 1,994.6 MiB | 1,955.8 MiB |

One 15-minute run used about 2.63 GB in PostgreSQL and 1.99 GB in Kafka, for a total of 4.62 GB.

Dividing that total by the roughly 1.89 million payments planned for the measured phase gives about 2.4 KB stored per payment. With a linear extrapolation, that order of magnitude is close to 443 GB per day. For Kafka alone, seven days at the same volume would be about 1.3 TB.

The extrapolation is useful for comparing storage order of magnitude. The observed volume includes warm-up, responses, duplicates, and confirmations. Compression, segment cleanup, retention, and load composition also affect growth. The experiment showed that storage and retention start to matter before CPU becomes the limit.

These numbers describe the observed environment and are not part of the three benchmark criteria.

## Why both runs matter

Run A used more CPU and was slower even though code, configuration, and load were the same. PostgreSQL operations were also slower, but the available data did not point to one clear cause.

Showing only Run B would give more attractive numbers, but it would hide real variation. Keeping both runs shows that the result repeated and that even the less favorable condition stayed within the target.

## Where the evidence is

The files that preserve both runs are in:

```text
docs/performance/evidence/2026-08-29/
├── profile.json
├── execution-plan.json
├── qualification-run-a-sla-report.json
├── qualification-run-b-sla-report.json
└── checksums.sha256
```

The profile and execution plan record the load that was run. The reports preserve generation, latency, confirmations, and duplicates. The checksums make it possible to verify that these files are still identical to the ones selected as final evidence.

The Go-versus-Rust generator comparison reports in the same directory belong to a different study and are not part of the final runs.

To run the same profile again:

```bash
cd load-test
./prepare-performance-environment.sh --profile mixed-outcomes-2k-15m
./run-load-test.sh --profile mixed-outcomes-2k-15m <run-tag>
```

Preparation recreates the local environment. A new run does not inherit the result of earlier runs. It must meet all criteria again.

## Measurement scope

The benchmark measured one specific scenario:

* one instance of each service;
* one Kafka broker with replication factor 1;
* generator and system on the same host;
* 15-minute main phase;
* end-to-end correctness checked through observed confirmations and duplicates, with financial invariants covered by transactional tests.

In this scenario, both runs support the project result: at least 2,000 payments per second, p99 below 1 second, and no expected confirmation missing or contradictory.
