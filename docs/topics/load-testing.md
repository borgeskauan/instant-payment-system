# How the load test works

This document answers one question:

> How can we know that the system really sustained the load instead of only building up work and recovering the average later?

The results, environment, and benchmark criteria are in [Performance and evidence](../performance.md). Here, the focus is the method used to create and measure the load.

## The generator tracks the full payment

The load generator represents institutions on both sides of the flow:

```text
payer sends payment
        ↓
receiver gets it and responds
        ↓
final result returns to payer
```

A successful HTTP response only confirms that the message entered the system. For the benchmark, the payment finishes only when the matching final result returns to the payer through the Notification Gateway.

The generator also sends the planned duplicates and checks that they were accepted without producing a contradictory result.

The report uses work that the tool itself created, tracked, and observed. Internal financial invariants are checked separately by the system's transactional tests.

## The same configuration produces the same load

Before the run starts, the selected configuration is validated and turned into an execution plan.

The plan decides in advance:

* which payments should complete or be rejected for insufficient funds;
* which institutions take part in each payment;
* which pairs receive most of the traffic;
* which messages will be sent again;
* which result should return for each payment.

These choices come from each payment's sequence, not from the order in which concurrent tasks finish. Running the same plan again keeps the same load composition.

The original configuration and the plan that was actually run are stored with the result.

## The system under test does not control the offered rate

The generator runs in an **open loop**: payment start times are planned before any response exists.

If the system becomes slower, the generator does not reduce the rate automatically. If a payment misses its planned start time, it is not moved forward to repair the average.

```text
missed time
      ↓
payment not started
      ↓
gap stays visible
```

There is no queue of late payments followed by a recovery burst.

This rule prevents a run from staying below the target for part of the time and still looking healthy only because it caught up later.

## Planning the time is not enough; the request must start

Pacing divides time into absolute 10 ms windows. Every window is calculated from the start of the phase. A late window does not move the following windows.

Preparing a payment exactly at its start time would add message-building time and connection-capacity wait time to the result. The generator prepares the next group in advance instead.

Preparing does not mean starting. A payment counts only when:

1. its message is ready;
2. there is real capacity to open an HTTP/2 request;
3. its planned time has arrived;
4. the request starts inside the allowed window.

If any of these conditions is not met in time, the payment is recorded as not started. Once it starts correctly, the generator keeps tracking it until the response or the end of the experiment.

Reserving HTTP/2 capacity matters because an internal client queue could accept work without placing it on the connection right away. Without this protection, the report could mark a payment as started while it was still waiting locally.

Connections stay open during the test and are warmed up before load begins. The test does not include a new connection handshake for every payment.

## The clock does not compete with network work or reporting

One dedicated thread controls only the times when payments start. It does not wait for HTTP responses, process notifications, or calculate statistics.

Network work, receiver responses, and duplicates run asynchronously. A separate component records observed events. The report is built only after the run.

This keeps the timing path small and prevents percentiles, CSV files, or aggregations from delaying load generation itself.

Internal queues are bounded. If the tool runs out of capacity to track its own work, the run fails instead of silently reducing the load.

## Warm-up ends when its observable work ends

Before the main phase, the generator increases the rate in two steps. The goal is to warm connections, caches, and JVMs without mixing that startup work into the measured period.

When warm-up generation ends, the tool waits for everything it created and can observe:

* original requests;
* receiver responses;
* final results;
* selected duplicates.

The main phase starts only after these obligations finish or the wait limit is reached.

This rule observes the generator's work. It does not require every internal Kafka and PostgreSQL queue to be empty:

> The measured phase does not start while the generator is still tracking warm-up work.

## Duplicates add load, but not declared throughput

Some requests and responses are sent again ten seconds later.

Duplicates keep the identity and content of the original message. They test system idempotency, but they do not replace new payments and do not count toward the throughput floor.

The report compares how many were planned, sent, and accepted. If a planned duplicate is not sent or accepted at ingress, the report records a violation.

## How results are calculated

### Throughput

A payment counts only if its original request really started inside the measured phase.

After the run, the report checks every rolling one-second window inside that phase. The lowest count is the **minimum rolling TPS**.

This means:

* the average cannot hide a dip;
* a later burst cannot repair an earlier window;
* planned and started payments stay separate.

### Latency

Latency starts when the original request starts and ends at the first matching final confirmation observed by the payer.

Message preparation time and the ingress HTTP response do not end this measurement.

### Observable correctness

The generator knows the expected result of each scenario before the run starts. For every started payment, it checks whether the payer received a matching confirmation.

It records:

* matching results;
* missing results;
* incompatible states or reasons;
* rejected causal responses;
* duplicates that were not sent or accepted.

Matching duplicate confirmations are allowed because delivery is at-least-once. An incompatible confirmation is still a contradiction even if a correct one also arrived.

The report does not read every balance back from PostgreSQL. Financial invariants are checked directly by the system's transactional tests.

## Preparing the environment and generating load are different jobs

Preparation creates a new environment, waits for the services, provisions participants, and generates the required certificates.

Only after that does the run command start the load. It requires the same configuration used during preparation and stores the plan with the results.

Preparation handles infrastructure checks. The run command handles only the load and uses the environment that was prepared.

For the final runs, every round started with a complete and independent preparation.

## Method scope

The method used in this project assumes:

* generator and system on the same host;
* pacing thread with no real-time priority or fixed CPU affinity;
* 10 ms as the smallest time unit used by the test;
* warm-up completion based on work observable by the generator, not the internal state of every service;
* end-to-end correctness measured through results and duplicates, with balances covered by transactional tests;
* diagnostics used to explain the run, not replace report criteria.

With this method, the main property is simple:

> Throughput includes only payments that really started in their window, and latency tracks those same payments until the final result.

## Check the implementation

The generator and report are in [`load-test/rust-loadtool`](../../load-test/rust-loadtool/). The split between preparation and execution is in [`prepare-performance-environment.sh`](../../load-test/prepare-performance-environment.sh) and [`run-load-test.sh`](../../load-test/run-load-test.sh).
