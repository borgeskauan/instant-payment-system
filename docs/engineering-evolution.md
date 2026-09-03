# How evidence changed the design

The [current design](design.md) explains how the system works. This document answers a different question:

> Which problems made the system end up with this design instead of the alternatives explored during the project?

It collects the investigations that changed a responsibility, an architectural boundary, or the way the problem was represented.

Load, criteria, and implementation changed over time, so results from different phases do not form one benchmark. The final measurement is in [Performance and evidence](performance.md).

## The evolution in one page

| Period | Problem that became visible | Change that stayed |
| --- | --- | --- |
| Aug–Sep 2025 | complete the first payment end to end | payment, receiver decision, and movement between PSPs |
| Oct 2025 | HTTP connections and financial processing ran at different speeds | asynchronous ingress through Kafka |
| Jan–Mar 2026 | polling and delivery inside the processor mixed responsibilities | Notification Gateway and gRPC |
| Jun 2026 | speed could be measured, but its cost could not be explained | instrumentation, PostgreSQL, batch processing, and Go generator |
| Jul–Aug 12, 2026 | a benchmark without failures and duplicates tested a problem that was too easy | idempotency, DLQ, mTLS, outbox, audit, and duplicates |
| Aug 14–24, 2026 | final load exposed costs in ingress, database work, and delivery | persistent HTTP/2, one balance per participant, Pull, and durable Kafka |
| Aug 25–29, 2026 | the generator itself introduced timing variation | Rust generator with explicit pacing and admission |

## The first flow revealed three different jobs

The project started in August 2025 with a direct goal: make one payment cross the full system, let the receiver decide, and reflect the result in the accounts.

The first version put HTTP ingress, financial processing, and notification delivery in the same component. It proved the flow, but it also showed that these jobs run at different speeds:

* accept a connection;
* decide what happens to the money;
* wait for the participant to fetch the result.

The first tests explored HTTP servers, threads, long polling, and reactive implementations. No single configuration became the main decision. The change that stayed was separating message acceptance from payment processing:

```text
Institution
 ↓ HTTP
Payment Ingress
 ↓ Kafka
Payment Processor
```

The Ingress started doing only cheap validations and publishing to Kafka. The Processor stayed responsible for identity, rules, and money.

This boundary also made the meaning of the HTTP response clear: the message was accepted for processing; the payment was not finished yet.

## Delivery stopped being part of the financial processor

When Kafka entered the main path, the old HTTP polling model no longer matched the system. The Notification Gateway was created to own the delivery protocol:

```text
Payment Processor
 ↓ Kafka
Notification Gateway
 ↓ gRPC
Institution
```

In the delivery flow, the Processor began ending its work when it created the notification. The Gateway became responsible for how the participant receives it.

The load test changed with this boundary. Payment measurement stopped ending at HTTP ingress and started ending only when the confirmation returned to the payer.

## Measuring speed was no longer enough

By June 2026, reaching a high rate was not enough. I needed to explain where the system spent time and CPU.

The test environment started measuring these signals together:

* container CPU and memory usage;
* Kafka lag;
* end-to-end latency;
* PostgreSQL statistics;
* JFR profiles from the Java applications.

This changed the way performance work was done. Each optimization followed the same cycle: find a cost, change the related mechanism, and repeat the full test.

Instrumentation itself could also change the result. `log_executor_stats` produced 41 MB of logs. `heaptrack` increased the generator RSS from about 59.6 MiB to 491 MiB and its p99 from about 254 ms to 718 ms. These tools stayed useful for diagnosis, but were not used in the final runs.

k6 found the first limits, but its rate followed response speed. A Go generator added an independent offered rate, different traffic concentrations, a final window for receiving results, and tracking for complete payments.

At the same time, the measured system moved closer to the final problem:

* PostgreSQL replaced H2;
* Kafka consumers started receiving groups of messages;
* persistence and financial completion started working in batches;
* monetary values got a compact representation;
* PACS message conversion moved to the ingress;
* the Processor started consuming a smaller internal message.

This was the first architecture designed to reduce work per payment, not only accept more connections.

## Buckets reduced contention, but changed the meaning of money

One balance design split each participant's available balance across 16 records called buckets. The payment ID selected one bucket. This meant concurrent batches for the same participant often locked different records.

The mechanism reduced contention, but created a business problem: a participant could have enough money across all buckets and still reject a payment because the selected record was empty. Payment completion also had to calculate, lock, and update buckets on both sides.

The next design returned to one available balance per participant and moved reservation earlier:

```text
payment admitted
→ amount leaves payer availability

receiver accepts
→ amount reaches receiver

receiver rejects
→ reservation returns to payer
```

At first, going back to one record seems like it should increase contention. The gain came from changing the unit of work: each batch locks its participants once, evaluates their payments in memory, adds the effects, and makes one update per participant.

This removes contention **inside** the same batch while keeping the required order **between** transactions. Payments in the same commit do not need to compete across 16 buckets. Two concurrent transactions that use the same money still have to wait for each other.

The final design keeps only the contention caused by a real conflict over the same balance.

> Useful parallelism separates independent work. It does not need to split business state or make operations in the same transaction compete with each other.

## Correctness increased both the cost and the value of the benchmark

Between July and August, the system gained properties that made the load harder:

* invalid messages got an explicit path to a DLQ;
* repeated requests and responses became idempotent even under concurrency;
* institution identity started coming from the mTLS connection;
* financial completion started creating its notification in the same transaction;
* audit facts started sharing the business transaction;
* insufficient funds became an expected result;
* replays became part of normal load.

These mechanisms were not extra features. They defined the minimum work required for a payment to be considered correct.

The benchmark question changed from “how many requests can the system accept?” to:

> How many complete payments can it sustain without moving money twice, contradicting the result, or losing the obligation to notify?

## The generator stopped hiding delays

The first reference run of the final campaign showed that an average close to the target could hide delayed work that was recovered in later bursts.

The benchmark rules were corrected before further performance work:

* every payment gets an absolute time boundary;
* late work is not carried into the next window;
* throughput is checked across every rolling one-second window;
* duplicates add load but do not replace original payments;
* latency ends only when the result returns;
* performance and correctness are checked together.

The generator started recording what actually began at the planned time, instead of only counting how much work it managed to finish later.

## Connections became part of the infrastructure

After that correction, the first limit appeared before financial processing. The simulated institution created TLS connections repeatedly and used up ingress capacity before the load reached the rest of the system.

Reusing connections with HTTP/1.1 confirmed the cause. The final design became:

* HTTP/2 required;
* persistent connections per participant;
* authenticated warm-up;
* capacity reserved for a new HTTP/2 request before payment admission.

A free slot in the HTTP client's internal queue does not prove that the connection has capacity. The admission path reserves space for one HTTP/2 request before the time boundary. Until that point, the payment can still be recorded as not started. After that, the request continues until it completes.

This model also avoids opening a new connection for every payment and keeps participant connections active during the load.

## PostgreSQL improved when the work around it became smaller

Once ingress stopped being the main limit, PostgreSQL became the most pressured resource. The investigation covered consumer concurrency, batch classification, response updates, outbox, audit, indexes, physical layout, and the real size of groups delivered by Kafka.

The final design kept a set of simpler choices:

* one serial flow per consumer in the measured environment;
* batch classification and authorization in Java before writes;
* batched updates and inserts;
* arrays + `unnest` for large inserts;
* no `RETURNING` when the response is already in memory;
* compact representations for states, reasons, and values;
* only indexes used by real queries or business facts.

Not every faster query improved the whole system. Some changes only moved the cost to another stage. Microbenchmarks were useful for checking a local mechanism, but only the end-to-end result decided whether a change stayed.

It was also necessary to measure the batches that actually reached the application. In the response flow, `max.poll.records=500` produced an average of about 163 records and a maximum of 339. Limits such as `max.poll.records`, `fetch.min.bytes`, and maximum Pull size affect batch formation, but none of them alone guarantees how many messages the application receives at once.

Physical layout showed another trade-off. Reserving half of each page for future updates (`fillfactor=50`) raised HOT updates from 22.86% to 100%. But table and index space grew by 46.98% without increasing completed payments. Compact representations and removing unused indexes reduced SQL work and PostgreSQL write-ahead log (WAL) volume in repeatable tests, so those changes stayed.

The method that remained was:

```text
measure the real load
→ remove the first dominant accidental cost
→ observe where the limit moves
→ validate the local mechanism
→ repeat the end-to-end flow
```

An exploratory test at 4,000 TPS marked the limit of this campaign. The lowest one-second window stayed between 3,920 and 3,960 payments, with p99 between 1.36 and 2.45 seconds. Allowing up to 1,000 messages per read in the ingress flow instead of 500 did not close the gap and made batch-processing tail latency worse.

The system stayed correct, but did not meet the criteria at 4,000 TPS. The experiment located the next limit in the payment consumer.

## Reliable delivery became simpler in four steps

The final notification architecture did not appear all at once. Each step solved one problem and exposed another:

| Step | What it solved | Cost that remained |
| --- | --- | --- |
| processor memory + HTTP polling | first working flow | connections, temporary storage, and money in the same component |
| Gateway with reliable push | removed sessions from the Processor | ACK, lease, retry, and a second persisted copy |
| Pull with cursor | removed individual ACK and active redelivery | PostgreSQL index and reconciliation |
| Kafka as a durable log | removed the second source of truth | limited retention and operational dependency on the broker |

With reliable Push, every notification went through its own sequence of states:

```text
delivery
→ claim
→ lease
→ IN_FLIGHT
→ ACK
→ retry or completion
```

With Pull, the institution started controlling its own progress. It asks for everything after its last durable cursor and only moves forward after it processes the batch. To receive something again, it can send an older cursor. No redelivery scheduler is needed.

The first Pull version still materialized an index in PostgreSQL and had to reconcile it with the history. The final design removed that intermediate state:

* PostgreSQL creates the obligation inside the financial transaction;
* the publisher removes the outbox record only after Kafka confirms publication;
* Kafka keeps the operational window;
* the institution owns its durable progress;
* the Gateway provides Pull and uses memory only to speed up the recent case.

PostgreSQL stopped tracking every delivery, and the Gateway stopped keeping a second source of truth.

## In the Rust generator, pacing got one owner

The Go generator grew while the test contract itself was still being discovered. Pacing, network work, duplicates, results, and reporting started sharing state and fixed pools. During long runs, generator pauses could break the timing requirement even when the average looked correct.

The Rust version was designed again from the final goal:

* one native thread controls pacing only;
* buckets have absolute deadlines;
* work is prepared before the time boundary;
* HTTP/2 capacity is explicit;
* bounded queues do not hide late recovery;
* generation and reporting have physical boundaries;
* reporting runs after the measured path.

Attempts such as larger queues, longer spinning, or fixed CPU placement did not solve the shared responsibility. The architecture change made one component responsible for deciding whether a payment started on time, without overloading the pacer.

A controlled A/B test with the same core and profile made the difference visible:

| Measure | Go | Rust |
| --- | ---: | ---: |
| omitted payments out of 1,890,000 | 6,906 | 55 |
| lowest rolling one-second window | 1,784 TPS | 2,058 TPS |
| process CPU | 875.82 s | 576.86 s |
| missing or contradictory results | 0 | 0 |

Go stayed correct, but did not sustain the timing floor in that run. Rust introduced an async runtime, atomic operations, and more internal boundaries, but it reduced shared state and made admission predictable.

This comparison applies to these two generator implementations under this load.

## The system ended up smaller than some intermediate versions

After balances, audit, states, and delivery became stable, the experimental migrations were replaced with a new compact initial schema. The history remains in this document and in Git. The running system does not need to carry structures that were abandoned.

Several final decisions removed mechanisms:

* Kafka separated ingress from processing;
* the Gateway moved connections out of the financial processor;
* one balance per participant replaced artificial buckets;
* Pull removed ACK, lease, and active redelivery;
* durable Kafka removed the Gateway index and reconciler;
* grouping moved out of the domain and stayed at transport and persistence boundaries;
* the generator got one owner for pacing.

The common pattern was to remove overlapping authorities and hidden queues while making the business guarantees stronger.

The final results of this path are in [Performance and evidence](performance.md).
