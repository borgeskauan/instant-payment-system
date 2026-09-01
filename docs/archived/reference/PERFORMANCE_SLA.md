# Performance SLA

This document defines the performance target used by the local Pix/SPI stack load tests.

## Scope

The measured flow starts when the PSP simulator creates a transaction request and ends when the PSP simulator receives the final confirmation notification.

The contractual rate counts original payment submissions, independent of their expected business outcome. The configured rate is a sustained minimum: every continuous one-second window fully contained in the active period must contain at least that many original HTTP attempts. Expected business-rule rejections are reported separately from happy-path outcomes and from technical failures.

The local qualification objective is to keep the observed active workload below
3 vCPUs and 3 GiB for the measured services. The current Compose file uses the
following per-container guardrails:

| Component            | Compose CPU limit | Compose memory limit |
| -------------------- | ----------------: | -------------------: |
| Kafka                |              1.00 |                2048m |
| SPI                  |              1.00 |                 768m |
| Postgres             |              1.00 |                 512m |
| Kafka producer       |              1.00 |                 384m |
| Notification gateway |              1.00 |                 512m |
| **Total**            |          **5.00** |            **4224m** |

Compose does not impose one aggregate 3-vCPU cgroup. The qualification therefore
proves observed consumption under the target, not behavior under a physically
enforced aggregate ceiling. In the two final runs, average active CPU was
1.178/1.161 vCPU, the largest complete samples were 2.174/2.222 vCPU, and the
largest complete memory samples were 2134/2014 MiB. See the
[stabilization report](performance/2k-tps-stabilization.md) for methodology and
limitations.

Persistent volumes are excluded from the memory budget. Runtime memory used by persistence services, including Postgres buffers, Kafka memory, and operating process memory, remains included in the sustained memory budget.

Init containers, administrative tools, and `kafka-ui` are excluded from the sustained budget. `kafka-ui` must remain disabled unless it is explicitly started with its Compose profile.

The load generator must run outside the measured runtime budget.

Warmup traffic may be generated before the active test window. Warmup is used only to prime connections, JVMs, Kafka consumers, caches, and database state. It is not part of the SLA pass/fail window.

## Targets

| Target | Active duration | Minimum original payment rate | Latency target |
| ------ | --------------: | --------------------: | -------------- |
| Contractual | 15 minutes | 2000/s | p99 below 4.6s |
| Internal engineering | 15 minutes | 2000/s | p99 below 1s |

Additional notes:

* The internal engineering target is stricter than the contractual target. It is the acceptance bar for saying the stack has enough operational margin for the contractual SLA.
* Throughput is reconstructed after the run from `RequestStartedAtNS` and evaluated over every rolling one-second window inside the active period. Average throughput and total submissions are diagnostic only.
* The generator does not carry temporal debt into later buckets. Ordinary jitter may produce samples above the required minimum, but no later peak compensates for an earlier rolling window below it.
* Error/loss tolerance is zero for both targets: no technical failures, application errors, lost transactions, contradictory final outcomes, or inconclusive transactions.
* Every original payment must receive at least one compatible final payer notification. Repeated compatible deliveries represent the same logical outcome under the at-least-once delivery contract.
* Runtime health requirements are the same for both targets: no container restart, OOM kill, swap usage, or externally observed work that grows without bound.
* The load tool waits for every obligation it created and can observe, within the experiment deadline. Kafka lag is diagnostic and is not treated as proof of internal end-to-end quiescence.
* Result collection is used to detect delayed, missing, duplicate, or inconclusive confirmations. It does not extend the SLA latency budget.

## Recommended Validation Sequence

1. Run `prepare-performance-environment.sh --profile mixed-outcomes-2k-15m` to recreate volumes, start the stack, wait for readiness, and provision the test inputs.
2. Run `run-load-test.sh --profile mixed-outcomes-2k-15m <run-tag>`. The profile owns the warmup and opens the active window only after the load tool's observable warmup obligations are complete.
3. Evaluate the configured replays as additional load, never as part of the 2000-originals/s floor.
4. Recreate the environment with the preparer and repeat the official test with the same code, profile, resources, and instrumentation.

## Pass/Fail Summary

The stack passes the performance target only if every rolling one-second window
sustains the configured minimum and all latency and correctness targets hold in
two comparable official runs. The command exit code does not encode this
decision; the persisted generation, latency, outcome, and replay facts do.
