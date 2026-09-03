# How a confirmation stays recoverable

This document answers one question:

> After the money has moved, what prevents the confirmation from disappearing before it reaches the institution?

The path starts when the Payment Processor stores the obligation to notify together with the payment. It ends when the institution processes the confirmation and advances its own progress. The financial rules before this stage are in [How payment correctness is preserved](payment-correctness.md).

## What delivery promises

Delivery preserves these properties:

1. a completed financial transaction leaves a durable obligation to report its result;
2. a failure between PostgreSQL and Kafka does not silently remove that obligation;
3. after confirmed publication, the history stays recoverable during the operational retention period;
4. the Gateway may deliver the same confirmation again, but it cannot claim that the institution already processed it;
5. memory makes recent reads faster, but does not take part in correctness.

The system provides **at-least-once** delivery, not exactly-once delivery. A confirmation may appear again. When a boundary has an uncertain result, the system repeats the operation instead of assuming delivery happened.

## The first risk is between PostgreSQL and Kafka

PostgreSQL and Kafka do not share a transaction. Publishing directly after changing the payment would leave this window:

```text
payment COMMIT
        ↓
process fails before publication
        ↓
result exists, but nobody can recover it
```

To close this window, the confirmation is first stored in PostgreSQL itself, inside a **transactional outbox**:

```text
PostgreSQL transaction

├── payment and balances
├── audit
└── notification_outbox
          │
          │ after commit
          ▼
        Kafka
```

Each outbox record contains only:

| Field | Purpose |
| --- | --- |
| `communication_id` | stable identity of the notification envelope |
| `recipient_ispb` | destination institution and partition key |
| `payload` | exact bytes that will be published |
| `created_at` | recovery order on the next startup |

The table has no publication status, retry counter, or per-message ACK state. A record in the table means only: **publication of this obligation has not been confirmed and the record has not been removed yet**.

### The normal path does not need to query the table again

The transaction that writes the outbox also schedules an internal event. The event is delivered only after commit and sends the batch to the publisher.

This avoids querying the outbox on the normal path:

```text
COMMIT
  ↓
AFTER_COMMIT event
  ↓
bounded queue
  ↓
single publisher
  ↓
Kafka
```

This in-memory event is only the fast path. If the transaction rolls back, neither the record nor the event survives. If handoff fails after commit, the persisted obligation is already the source of truth.

### The outbox is removed only after confirmation

The publisher sends the exact bytes stored in the outbox. The destination institution is used as the Kafka key, and the communication identity travels with the message. The publisher waits for confirmation with `acks=all` and uses Kafka producer idempotency.

The outbox is deleted only after every publication in the batch is confirmed by the broker.

If publication fails, has an uncertain result, or outbox deletion fails, the full batch is sent again. Some messages may already have reached Kafka. This rule avoids loss at the cost of possible duplicates.

Kafka producer idempotency reduces duplicates from the producer's own internal retries. It does not make the full PostgreSQL-to-Kafka boundary exactly-once. The application can still send something again if the broker received it but the response was lost.

### What happens after a restart

Before accepting new payment batches, the Payment Processor reads the oldest obligations from the outbox and tries to publish them. This prevents old confirmations from staying forever behind new traffic after a restart.

While the application is running, the publisher keeps the current batch and retries until it can confirm publication and remove the records.

Today, if an obligation was committed to the database but its post-commit event did not reach the publisher, it returns to the flow on the next startup. There is no periodic scan during the same process run. Until then, the obligation stays in PostgreSQL.

## After publication, Kafka stores the history

After the broker confirms publication and the outbox record is removed, Kafka becomes the authority for confirmations that are available for recovery.

In the current environment, topic `psp-notifications-v1` has:

* eight partitions;
* seven-day retention;
* `recipient_ispb` as the key;
* one broker and replication factor 1 in the local environment.

Using the recipient as the key keeps all notifications for one institution in the same partition while the partition count stays fixed. Different institutions can share a partition. The Gateway still separates their messages, but the cursor must represent this shared read.

For this topic generation, the eight partitions are fixed. Changing that number requires a new topic generation and cursor migration.

Kafka does not decide financial state, does not know what the institution already processed, and does not take part in the payment transaction. Its authority starts with published history.

## The institution decides how far it has processed

To fetch notifications, the institution sends the Gateway its last processed cursor. The response contains up to 15 messages and a new cursor.

```text
Pull(previous cursor)
        ↓
up to 15 notifications
+
nextCursor
```

### The first Pull

No cursor is created during onboarding. On the first call, the institution sends an empty field:

```text
Pull(empty cursor)
        ↓
starts at the earliest retained offset
in the institution's partition
```

The Gateway does not start at “now.” It scans the available history from the oldest point Kafka still keeps and returns notifications for that institution. An institution starting consumption also receives its backlog inside the retention window.

If no record is available, the call follows the normal long-polling behavior and may finish with an empty batch and an empty cursor. The first signed cursor appears after the Gateway scans at least one record in the partition. That progress can include messages for other institutions.

This rule cannot recover messages older than the topic retention period. The first Pull starts at the beginning of the history **that is still available**, not at the absolute beginning of the institution's history.

Inside a partition, Kafka identifies each message with a numeric position called an **offset**. The cursor is an opaque token signed by the Gateway and binds these values:

```text
format version
topic generation
authenticated ISPB
partition
last scanned offset
```

An institution cannot use another institution's cursor or move its position forward on its own without making the signature invalid.

The Gateway accepts only a cursor that it created and signed. The institution decides when it can say: **I have durably processed everything up to this point**.

The institution persists the new cursor only after it durably processes the full batch. The Gateway provides this progress point; cursor storage belongs to the institution.

If the institution fails before it persists the new cursor, it sends the previous one again and may receive the same envelope again. `communication_id` stays the same when the same obligation is republished, so the consumer can recognize the duplicate.

### Why the cursor also moves across messages for other institutions

One partition can contain records for several institutions:

```text
offset 100 → institution A
offset 101 → institution B
offset 102 → institution A
```

When serving institution A, the Gateway returns notifications from offsets 100 and 102, but it also knows that it scanned offset 101. So the next cursor can point to 102.

If the Gateway stored only the last message for institution A, it would scan the other participants' messages again and again. On the other hand, moving forward without reading intermediate offsets could skip a valid message.

The scan stops when it finds the fifteenth notification for the institution or reaches the scan limit. The cursor never moves past the last record that was actually scanned.

## Memory makes it faster; Kafka provides recovery

The Gateway follows the partitions and keeps a bounded window of the newest messages in memory. It includes messages from all institutions that share the partition and keeps offset order.

When the window contains a continuous sequence after the cursor, Pull can answer without a historical Kafka read.

```text
continuous Kafka read
    ↓
recent window per partition
    ↓
Pull
```

If the cursor points to older data or memory does not contain a complete sequence, the Gateway reads directly from Kafka:

```text
message outside the in-memory window
    ↓
read from the position after the cursor
    ↓
filter for the institution inside the partition
    ↓
answer Pull
```

The historical read serves that call. At the same time, the normal flow continues feeding recent messages into memory.

When the cursor is already at the newest known point and no notification is available, the Gateway keeps the call open for up to 30 seconds. A new message for that institution releases the response. A timeout returns an empty batch with the same cursor.

Only one Pull can stay active per institution. A second concurrent call is rejected so that two flows do not compete for the same logical progress.

## What happens when a boundary fails

| Situation | Result |
| --- | --- |
| financial transaction rolls back | the notification obligation also rolls back |
| commit succeeds, then process fails before publication | the record stays in the outbox and is recovered on the next startup |
| Kafka confirmation is partial or uncertain | the full batch may be published again |
| Kafka confirms, but outbox deletion fails | the full batch may be published again |
| Gateway restarts or loses its in-memory window | Pull reads history directly from Kafka |
| institution fails before persisting the cursor | the previous cursor may cause another delivery |
| cursor points to data removed by retention | Pull fails explicitly; recovery leaves the normal operational flow |
| cursor was changed or belongs to another institution | Pull is rejected |

When a boundary has an uncertain result, the system prefers to repeat the confirmation. It never decides on its own that the institution processed it.

## How each promise is protected

| Property | Authority or mechanism |
| --- | --- |
| obligation is created with the financial fact | PostgreSQL transaction and outbox |
| bytes and identity stay stable across retries | immutable outbox record |
| publication releases the outbox only after confirmation | Payment Processor publisher |
| recoverable operational history | Kafka |
| progress that was actually processed | participating institution |
| cursor cannot be invented or transferred | HMAC bound to ISPB/partition |
| recent path without historical reads | bounded Gateway window |
| old message or restart | direct Kafka read |

## Contract scope

This version uses these rules:

* seven days of retention; older messages leave this flow's operational history;
* eight fixed partitions, with no transparent repartitioning;
* one logical flow and at most one active Pull per institution;
* no per-delivery ACK, cursor, or state persisted by the Gateway;
* an obligation that misses the post-commit handoff returns to the flow on the next startup.

Within this scope, the main property is: **after a transaction creates a confirmation, the system keeps a recoverable path for delivering it and allows duplicate delivery when a boundary has an uncertain result**.

## Check it in the code

The PostgreSQL-to-Kafka boundary can be inspected in:

* [`NotificationObligationService`](../../spi/src/main/java/br/kauan/spi/application/notification/NotificationObligationService.java);
* [`NotificationOutboxPipeline`](../../spi/src/main/java/br/kauan/spi/adapter/output/notification/NotificationOutboxPipeline.java);
* [`OutboundNotificationFastPathIntegrationTest`](../../spi/src/test/java/br/kauan/spi/adapter/output/notification/OutboundNotificationFastPathIntegrationTest.java);
* [`NotificationOutboxPipelineTest`](../../spi/src/test/java/br/kauan/spi/adapter/output/notification/NotificationOutboxPipelineTest.java).

The Pull protocol, cursor, and historical reads can be checked in:

* [`NotificationGrpcService`](../../notification-gateway/src/main/java/br/kauan/notificationgateway/grpc/NotificationGrpcService.java);
* [`DeliveryCursorCodec`](../../notification-gateway/src/main/java/br/kauan/notificationgateway/grpc/DeliveryCursorCodec.java);
* [`RecentNotificationWindow`](../../notification-gateway/src/main/java/br/kauan/notificationgateway/delivery/RecentNotificationWindow.java);
* [`HistoricalKafkaReader`](../../notification-gateway/src/main/java/br/kauan/notificationgateway/kafka/HistoricalKafkaReader.java);
* [`HistoricalKafkaReaderTest`](../../notification-gateway/src/test/java/br/kauan/notificationgateway/kafka/HistoricalKafkaReaderTest.java).
