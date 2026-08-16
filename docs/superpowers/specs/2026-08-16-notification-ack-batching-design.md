# Batched notification acknowledgements

## Purpose

Reduce PostgreSQL transaction and WAL pressure caused by persisting every
notification ACK in an independent synchronous update. This is an isolated
performance experiment in the `notification-gateway`; it must not change the
delivery contract or claim that the stack is stabilized by itself.

The current implementation handles an inbound ACK in the gRPC `onNext`
callback, calls `NotificationDeliveryRepository.acknowledge`, and waits for one
`jdbcTemplate.update` to complete before returning. The diagnostic
`preparation-workflow-verification/20260816_004331` recorded 36,042 executions
of that update, 31.7 seconds of aggregate execution time, and 44.8 MB of WAL
while PostgreSQL averaged 102.69% CPU during the active window.

## Delivery invariant

An ACK received from the PSP is not durable merely because the gateway has
accepted it into memory. The delivery obligation ends only when the matching
`notification_delivery` row is committed as `ACKED`.

Therefore:

```text
ACK received -> queued -> batch committed -> durable ACK
```

If the gateway or PostgreSQL fails before the commit, the delivery remains
unacknowledged in persistent state and can be sent again after its lease
expires. This preserves at-least-once delivery: a failure may cause redelivery,
but cannot silently complete an uncommitted ACK.

## Scope

This intervention changes only inbound ACK handling and its persistence in the
`notification-gateway`.

It does not change:

- the gRPC protocol or client-visible messages;
- notification claim, dispatch ordering, lease duration, retry, or redelivery;
- Kafka ingress or its bulk delivery persistence;
- SPI processing, buckets, outbox publication, or the load-tool;
- the `notification_delivery` schema;
- CPU or memory limits.

## Alternatives considered

### Global bounded queue with one batch writer

All streams enqueue authenticated ACK identities into one bounded queue. One
worker forms batches and writes them. This produces the largest useful batches,
centralizes failure handling, and adds only one lifecycle-managed component.

This is the selected design.

### Periodic scheduled drain

A fixed-rate scheduler could drain a shared queue. It is mechanically simple,
but imposes the complete scheduling interval even when a batch is already full
and requires overlap protection. It provides no advantage over a dedicated
writer for this workload.

### Per-ISPB or per-stream buffers

Separate buffers localize pressure but fragment the workload into smaller
transactions and multiply queues, workers, and lifecycle state. ACK persistence
does not require per-ISPB ordering, so that complexity is unnecessary.

## Component design

Introduce an immutable acknowledgement value containing:

```text
communicationId
authenticatedRecipientIspb
```

Introduce a Spring-managed `AcknowledgementBatcher` with:

- one bounded queue shared by all gRPC streams;
- one writer worker, so database flushes never overlap;
- a maximum batch size of 500 ACKs;
- a maximum wait of 20 ms from the first ACK in a non-empty batch;
- a queue capacity of 10,000 ACKs;
- a retry delay of 100 ms after a failed database flush.
- a normal-shutdown flush deadline of 5 seconds.

These are gateway runtime settings, exposed through the existing
`notification-gateway` application configuration and environment-variable
pattern. They are not load-test profile fields.

The initial values characterize the B side of this experiment. Changing them
later is a separate tuning decision and requires another comparison.

## Runtime flow

`NotificationGrpcService` continues to validate that the client message
contains a non-blank delivery ID and continues to derive the recipient ISPB
from the authenticated mTLS context. It then passes the acknowledgement to the
batcher instead of accessing the repository directly.

```text
gRPC onNext
    |
    +-- validate ACK and authenticated ISPB
    |
    +-- enqueue acknowledgement
            |
            +-- space available -> return immediately
            |
            +-- queue full -> wait for capacity
```

Waiting on a full queue is intentional backpressure. The gateway must not drop
an ACK, close a healthy stream merely because persistence is behind, or fall
back to individual synchronous updates under load.

This wait does not directly stop the delivery worker, which runs on a separate
executor and may continue calling the outbound stream observer. It can still
propagate to outbound delivery through the bidirectional stream. The load-tool
receives a notification and sends its ACK from the same goroutine; if that send
blocks because the gateway is not consuming inbound ACKs, the goroutine stops
calling `Recv`. HTTP/2 flow control then eventually limits the gateway's
effective outbound delivery for that PSP. Other PSP streams continue initially,
but sustained saturation of the shared queue can spread the same pressure to
them.

Queue saturation is therefore safe for correctness but unsuccessful for
capacity. It bounds memory and preserves redelivery, but it is not a healthy
steady-state behavior for the 2,000 TPS workload.

The writer waits for the first item, then drains until either the configured
batch size is reached or the 20 ms batch deadline expires. Duplicate
`communicationId`/recipient pairs within the same batch are collapsed before
persistence. Ordering between independent ACKs is not part of the contract.

## Bulk persistence

Replace the single-item repository operation with a bulk operation that sends
two equally sized PostgreSQL arrays and performs one update statement in one
transaction:

```sql
UPDATE notification_delivery AS delivery
SET delivery_status = 'ACKED',
    acknowledged_at = :now,
    lease_until = NULL,
    updated_at = :now
FROM unnest(?::text[], ?::text[])
     AS ack(communication_id, recipient_ispb)
WHERE delivery.communication_id = ack.communication_id
  AND delivery.recipient_ispb = ack.recipient_ispb
  AND delivery.delivery_status <> 'ACKED'
RETURNING delivery.communication_id
```

The repository uses the existing PostgreSQL/JDBC array pattern already present
in the SPI. All rows in a batch share one timestamp and one commit.

The recipient predicate remains mandatory: a PSP cannot acknowledge another
participant's delivery. An already ACKED delivery or an unknown/divergent pair
updates no row. The batcher may log aggregate requested, updated, and ignored
counts; it does not send an ACK response to the PSP because the protocol has no
such response today.

## Failure and lifecycle behavior

The batcher starts before the gRPC server accepts streams and stops only after
the server has stopped admitting callbacks. While it is operational, an ACK
accepted into the queue belongs to the batcher. When persistence fails, the
writer retains that exact in-memory batch and retries it after the configured
delay without closing the stream. New ACKs may continue filling the bounded
queue; once full, inbound callbacks wait. This bounds memory while preserving
backpressure.

An ACK is rejected with `UNAVAILABLE` only when it was not admitted because the
batcher is no longer operational, or when a callback blocked on admission is
interrupted. In both cases the stream is closed so the existing delivery
remains non-ACKED and can be redelivered. Queue saturation by itself is not a
rejection condition and a transient database failure does not terminate an
admitted stream.

If the process crashes, both the retained batch and queued ACKs disappear from
memory. Their database rows remain non-ACKED and become eligible for redelivery
through the existing lease mechanism.

During a normal shutdown, the batcher stops accepting new work and attempts a
bounded final flush. Failure to flush must not prevent process termination
indefinitely; persistent non-ACKED rows remain the recovery source.

If a callback waiting for queue capacity is interrupted, it restores the
thread interrupt status and terminates that stream with `UNAVAILABLE`. Because
the ACK was not enqueued or committed, the delivery remains eligible for
redelivery.

Notification dispatch and terminal callbacks for the same observer are
serialized. Dispatch revalidates registration while holding the observer lock,
so an `onNext` captured before unregister cannot race with or follow the
terminal `UNAVAILABLE` signal.

## Correctness tests

Tests must prove:

- the gRPC service enqueues the delivery ID together with the authenticated
  recipient ISPB and no longer writes the repository directly;
- invalid or blank ACK messages retain the existing validation behavior;
- the writer flushes when the batch reaches 500 items;
- a non-empty partial batch flushes after 20 ms;
- a full queue blocks producers until the writer frees capacity;
- the batcher lifecycle surrounds the gRPC server lifecycle;
- only one database flush runs at a time;
- a database failure retains and retries the same batch rather than dropping
  ACKs or switching to individual writes;
- duplicate identities inside one batch create at most one update obligation;
- one bulk statement and one transaction persist an entire batch;
- the bulk predicate requires both communication ID and recipient ISPB;
- an ACK for another ISPB, an unknown delivery, an already ACKED delivery, or a
  replayed ACK produces no new state transition;
- normal shutdown attempts a bounded flush, while an uncommitted ACK remains
  recoverable through redelivery;
- dispatch captured before unregister cannot send after the stream is closed,
  and only one terminal callback is emitted;
- the existing notification delivery, retry, and deduplication integration
  tests remain green.

Run a complete `mixed-outcomes-smoke` after the automated tests. It must
preserve HTTP acceptance, ACSC and RJCT/AM04 outcomes, PACS.002 delivery, and
both replay invariants.

## Performance experiment

Use `preparation-workflow-verification/20260816_004331` on commit `1a4f395` as
the A baseline. It was produced after a qualified environment preparation and
contains the full diagnostic bundle.

For B:

1. implement only ACK batching;
2. run `prepare-performance-environment.sh` to recreate and qualify the stack;
3. run `mixed-outcomes-2k-diagnostic` once without changing resources or other
   gateway/SPI settings;
4. preserve the complete bundle even if `valid` remains false.

Compare at least:

- ACK statement calls and rows per call;
- PostgreSQL transaction commits, ACK WAL bytes, and ACK-attributed
  `WALWrite`/`WalSync` samples;
- PostgreSQL and notification-gateway CPU and memory;
- original payments started/accepted and rolling throughput;
- PACS.002 and payer-notification throughput;
- evidence of ACK-queue saturation or blocked enqueue operations;
- drain/deadline completion, missing outcomes, contradictions, and replays.

Keep the change only if it measurably reduces ACK transaction/WAL pressure,
preserves functional behavior, and does not reduce workload admission or
notification throughput. Reaching the final 2,000 TPS SLA is not a success
condition for this isolated intervention. If PostgreSQL remains saturated, the
next experiment must use the new evidence to select the next query or stage;
this slice does not pre-authorize SPI, bucket, claim, dispatch, or resource
changes.

If the ACK queue reaches capacity during B, record that as a capacity failure
even when all affected deliveries eventually recover through redelivery. The
experiment must not describe queue-induced throttling as successful batching.
