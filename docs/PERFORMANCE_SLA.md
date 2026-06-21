# Performance SLA

This document defines the performance target used by the local Pix/SPI stack load tests.

## Scope

The measured flow starts when the PSP simulator creates a transaction request and ends when the PSP simulator receives the final confirmation notification.

Only accepted transaction requests are included in the latency and confirmation measurements. Business-rule rejections, if any, must be reported separately and must not be mixed with technical failures.

The active runtime stack is:

| Component            | CPU limit | Memory limit |
| -------------------- | --------: | -----------: |
| Kafka                |      0.80 |        1024m |
| SPI                  |      0.55 |         768m |
| Postgres             |      0.75 |         512m |
| Kafka producer       |      0.55 |         384m |
| Notification gateway |      0.25 |         320m |
| **Total**            |  **2.90** |    **3008m** |

The sustained budget stays below 3 CPU and 3 GB of memory for the services that remain active during the measured run.

Persistent volumes are excluded from the memory budget. Runtime memory used by persistence services, including Postgres buffers, Kafka memory, and operating process memory, remains included in the sustained memory budget.

Init containers, administrative tools, observability services, and `kafka-ui` are excluded from the sustained budget. `kafka-ui` must remain disabled unless it is explicitly started with its Compose profile.

The load generator must run outside the measured runtime budget.

Warmup traffic may be generated before the active test window. Warmup is used only to prime connections, JVMs, Kafka consumers, caches, and database state. It is not part of the SLA pass/fail window.

## Targets

| Target | Active duration | Accepted request rate | Latency target |
| ------ | --------------: | --------------------: | -------------- |
| Contractual | 15 minutes | 2000/s | p99 below 4.6s |
| Internal engineering | 15 minutes | 2000/s | p99 below 1s |

Additional notes:

* The internal engineering target is stricter than the contractual target. It is the acceptance bar for saying the stack has enough operational margin for the contractual SLA.
* Error/loss tolerance is zero for both targets: no technical failures, application errors, lost transactions, duplicate final confirmations, or inconclusive accepted transactions.
* Every accepted transaction must receive exactly one final confirmation.
* Runtime health requirements are the same for both targets: no container restart, OOM kill, swap usage, or unbounded Kafka/internal backlog growth.
* Kafka lag and internal backlogs must drain back to zero, or to a documented steady-state threshold, after the active load ends.
* Result collection is used to detect delayed, missing, duplicate, or inconclusive confirmations. It does not extend the SLA latency budget.

## Recommended Validation Sequence

1. Run warmup traffic at 2000 TPS.
2. Start the active measured window only after warmup is complete.
3. Run the official 2000 TPS / 15 minute test.
4. Repeat the official test without recreating the stack.
5. Optionally run an exploratory 2500 TPS test to measure margin, but do not use that result as the contractual pass/fail gate.

## Pass/Fail Summary

The stack passes the performance target only if it satisfies all contractual and internal engineering targets during the official test run.

The exploratory 2500 TPS run is a capacity-margin signal only. It must not redefine the contractual target or mask a failure at the official 2000 TPS target.
