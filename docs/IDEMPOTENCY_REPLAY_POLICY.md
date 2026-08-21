# Idempotency and Replay Policy

This document describes the idempotency and replay policy for Pix messages across the SPI, Kafka, the notification gateway, and PSP services.

The goal is safe at-least-once processing. Kafka redelivery, manual replay, concurrent outbox workers, duplicate payloads, and repeated PSP notifications must not duplicate logical obligations, settlement, status transitions, or PSP balance effects.

There is no end-to-end exactly-once guarantee. Physical Kafka publication can be repeated. Logical deduplication uses stable identities at each boundary.

## Principles

- `paymentId` / `EndToEndId` is the logical identity of a payment.
- `communicationId` is the logical identity of one notification and recipient.
- An identical replay reconstructs a missing acceptance obligation only while the payment remains `WAITING_ACCEPTANCE`.
- An identical replay is a no-op after the persisted payment state advances.
- A divergent replay with the same identity is a deterministic conflict and must be observable.
- Batch-local duplicates are classified before relying on persisted state.
- SPI outbox inserts use `ON CONFLICT (communication_id) DO NOTHING`.
- SPI business audit records only facts effectively applied; `NOOP` creates no audit event.
- The notification gateway deduplicates physical Kafka duplicates by `communicationId`.
- Final PSP balance effects are idempotent by payment and final-status side.

## SPI `pacs.008`

Incoming payment requests are persisted through `storeAndClassifyIncomingPaymentRequests(...)`. Each request receives a canonical fingerprint identified by:

```text
request_fingerprint_version + request_fingerprint
```

Version and fingerprint must be compared together. A matching hash with a different version is not automatically comparable.

| Case | Financial classification | Audit result | Outbox result |
| ---- | ------------------------ | ------------ | ------------- |
| New payment | Insert as `WAITING_ACCEPTANCE`. | Insert `PAYMENT_CREATED`. | Insert one `ACCEPTANCE_REQUEST` for the receiver. |
| Existing identical payment in `WAITING_ACCEPTANCE` | Classify as acceptance replay. | No event: the payment was not created again. | Try the same `communicationId`; keep an existing row or recreate a missing row. |
| Existing identical payment in advanced status | No-op. | No event. | No outbox insert. |
| Existing payment without comparable fingerprint | `DIVERGENT_DUPLICATE`. | No event in the business audit. | No outbox insert; publish the original input to DLQ. |
| Existing payment with divergent version/fingerprint | `DIVERGENT_DUPLICATE`. | No event in the business audit. | No outbox insert; publish the original input to DLQ. |
| Same batch, same `paymentId`, same fingerprint identity | Keep only the first logical record. | At most one effective event. | At most one obligation. |
| Same batch, same `paymentId`, divergent fingerprint identity | Classify every record as `DIVERGENT_DUPLICATE`. | No event. | No outbox insert. |

The financial statement, business-audit insert, and outbox insert run as separate bulk statements in one PostgreSQL transaction. Validation or persistence failure in audit or outbox rolls back the new payment. PostgreSQL resource failures during either insert propagate unchanged to the consumer, remain on the infrastructure retry path, and do not acknowledge the source batch. The source Kafka input is acknowledged only after the database commit and any required DLQ publication; processing does not wait for the outbox worker to publish to Kafka.

Replay never publishes directly. A recreated or still-pending obligation is handled by the scheduled outbox worker.

## SPI `pacs.002`

Incoming status reports are applied conditionally against the current persisted payment state.

| Incoming status | Current status | Financial result | Audit result | Outbox result |
| --------------- | -------------- | ---------------- | ------------ | ------------- |
| `ACCEPTED_IN_PROCESS` | `WAITING_ACCEPTANCE`, sufficient funds | Settle directly. | `PAYMENT_STATUS_CHANGED` + `SETTLEMENT_APPLIED`. | Insert `ACSC` for the payer and `ACCC` for the receiver. |
| `ACCEPTED_IN_PROCESS` | `WAITING_ACCEPTANCE`, insufficient funds | Transition to `ACCEPTED_IN_PROCESS`. | `PAYMENT_STATUS_CHANGED`. | No outbox insert. |
| `ACCEPTED_IN_PROCESS` | `ACCEPTED_IN_PROCESS` or `ACCEPTED_AND_SETTLED` | No-op. | No event. | No outbox insert. |
| `REJECTED` | `WAITING_ACCEPTANCE` | Transition to `REJECTED`. | `PAYMENT_STATUS_CHANGED`. | Insert `REJECTED_NOTIFICATION/RJCT` for the payer. |
| `REJECTED` | `REJECTED` | No-op. | No event. | No outbox insert. |
| Any incompatible transition | Any incompatible current state | `DIVERGENT_STATUS_REPORT`. | No event in the business audit. | No outbox insert; publish original input to DLQ. |
| Any status | Missing payment | `DIVERGENT_STATUS_REPORT`. | No event in the business audit. | No outbox insert; publish original input to DLQ. |

Batch-local rules:

| Case | Result |
| ---- | ------ |
| Same batch, same `paymentId`, same status | Keep the first logical report; repeated records are batch-local no-ops. |
| Same batch, same `paymentId`, different statuses | Classify every record for that `paymentId` as `DIVERGENT_STATUS_REPORT`. |

Settlement, bucket balances, both audit events, and both notification obligations commit or roll back together. Replaying a report that produces no new transition or settlement inserts neither audit nor outbox rows and cannot debit funds again.

## SPI Business Audit

`payment_audit_event` is append-only by application behavior and stores only normalized business facts. It does not store the original PACS payload, diagnostic attempts, input rejections, or `NOOP` replay events.

`PAYMENT_STATUS_CHANGED` and `SETTLEMENT_APPLIED` from one settlement are inserted in the same bulk and transaction. Their `event_id` values are technical identities only; no relative or causal ordering is guaranteed. Creation and settlement are unique per payment in the current model, while repeated identical status transitions are deliberately allowed if the lifecycle evolves.

There is no audit backfill. A replay that applies a real creation, transition, or settlement produces the normal events for that effect; a replay that applies nothing produces no event.

## SPI Notification Outbox

The outbox stores one immutable payload per logical notification and recipient. The business payload is built once, serialized with `ObjectMapper.writeValueAsBytes(...)`, and stored as `BYTEA`. The worker sends those exact bytes and does not rebuild them from the current payment state.

`communicationId` retains the existing deterministic algorithm over schema version, event type, recipient, payment, and optional notification status. The row is inserted as `PENDING`; `PUBLISHED` is terminal.

Every SPI instance runs the scheduler. Workers deliberately use no ownership, claim, lease, lock, token, fencing, or coordination. Multiple instances may read and publish the same `PENDING` row concurrently. Each physical message reconstructs the same topic (`psp-notifications`), key (`recipient_ispb`), and headers from immutable columns.

The Kafka producer uses `acks=all`. A successful broker future allows a guarded bulk transition from `PENDING` to `PUBLISHED`. A send failure keeps the row pending and schedules a fixed one-second retry. A process crash or database update failure after a successful send also keeps the row pending, so it may be published again.

All success and failure updates include `WHERE publication_status = 'PENDING'`. A delayed failure therefore cannot reopen a row another worker already published. Concurrent operational fields such as `attempt_count` and `last_error` are intentionally approximate.

This provides durable at-least-once Kafka publication, not exactly-once publication.

## Notification Gateway Deduplication and Delivery

The `notification-gateway` consumes every physical `psp-notifications` message and indexes each `communicationId` once. Concurrent or repeated outbox publications therefore create one logical delivery and consume no additional position.

The minimal `delivery_index` stores only `communication_id`, `recipient_ispb`, and a Gateway-owned `delivery_position` local to that PSP. Concurrent batches that contain the same recipient serialize position allocation with a transaction-scoped advisory lock; unrelated PSPs do not share that serialization. Kafka partition and offset remain ingestion details rather than a durable external cursor.

The authenticated PSP calls unary `PullNotifications(cursor)` with at most one request in flight and receives at most 15 notifications. The cursor is opaque, HMAC-authenticated, and bound to the PSP identity. The PSP advances it only after durably processing the whole response. Reusing an older cursor returns the rows again, which provides at-least-once delivery without per-notification ACK persistence, `IN_FLIGHT` state, leases, or an active retry scheduler. SPI `PUBLISHED` still means broker confirmation only; completed PSP processing is represented by the cursor held durably by the PSP, not by a Gateway row state.

After index commit, the Gateway places recent payloads in a bounded per-PSP memory window. Pull uses RAM only for a contiguous sequence beginning at the next cursor position. If memory cannot answer, it reads the canonical payload directly with `delivery_index JOIN notification_outbox`; it does not maintain a separate rehydration process. The reliable SPI outbox publisher remains unchanged in this phase, but a once-per-minute reconciliation also indexes canonical outbox rows older than one minute and missing from `delivery_index`. The age boundary leaves transient Kafka work on the fast path. Kafka remains the fast path rather than a requirement for eventual indexing.

The MVP intentionally performs this historical anti-join infrequently instead of persisting another worklist or watermark. Each cycle is paged in batches of 1,000 using an in-memory cursor that is discarded at the end; the next cycle restarts from the beginning. This accepts greater latency in the rare recovery path and keeps the durable model small. If historical growth makes the scan material, a worklist or batch watermark is a later optimization.

The Gateway does not yet delete acknowledged history or persist a retention watermark. Retention/GC and parallel pull streams per PSP are explicitly outside this MVP.

## PSP Incoming Requests

The receiver PSP keeps local classification for incoming payment requests.

| Case | Result |
| ---- | ------ |
| New incoming request | Store locally and send `ACCEPTED_IN_PROCESS` to SPI. |
| Identical request replay | Re-emit `ACCEPTED_IN_PROCESS`. |
| Divergent request with the same `paymentId` | Do not overwrite local data and do not send acceptance. |
| Same batch, repeated identical request | Keep the first logical request. |
| Same batch, divergent request | Classify affected records as divergent locally. |

This preserves recoverability when the receiver is asked again about a pending request.

## PSP Final Status Notifications

PSP final balance updates are idempotent by:

```text
paymentId + final status
```

The two sides are independent:

- `ACCEPTED_AND_SETTLED_FOR_SENDER` / `ACSC`: debit the sender account;
- `ACCEPTED_AND_SETTLED_FOR_RECEIVER` / `ACCC`: credit the receiver account.

| Case | Result |
| ---- | ------ |
| First final notification for a known payment and side | Apply debit or credit and mark that final status as applied. |
| Duplicate final notification for the same payment and side | No-op. |
| Sender and receiver final notifications for the same payment | Treat independently. |
| Final notification for an unknown local payment | Fail without changing balance. |
| Balance update fails after claim | Release the claim so a retry can apply later. |

## DLQ Relationship

DLQ preserves invalid or deterministic-conflict inputs for diagnosis and controlled replay. Idempotency makes controlled replay safe, but there is no automated operational replay tool in this version.

Current deterministic conflict types are `DIVERGENT_DUPLICATE` for `pacs.008` and `DIVERGENT_STATUS_REPORT` for `pacs.002`. If required DLQ publication fails, the source batch is not acknowledged.

## Limitações Conscientes

- There is no exactly-once guarantee; multiple SPI instances can physically publish the same message.
- Duplicate publication can increase Kafka traffic, CPU use, and notification-gateway load.
- SPI workers have no ownership, claim, lease, fencing, lock, or coordination.
- `attempt_count` is not an exact count of all concurrent physical sends.
- `last_error` can be overwritten by another concurrent failure and need not be the globally latest physical attempt.
- `PUBLISHED` is terminal and a delayed failure cannot reopen it.
- `PUBLISHED` rows are retained indefinitely; the table grows continuously without cleanup.
- Retry is fixed and indefinite; long outages can cause sustained publication pressure.
- There is no `DEAD` state, attempt limit, or automatic handling for permanently invalid messages.
- Outbox observability is limited to logs and manual SQL queries.
- There is no backfill for payments created before the outbox migration.
- There is no business-audit backfill, retention, cleanup, partitioning, or causal event ordering.
- Business audit excludes `NOOP` replay, retries, redelivery, input rejection, and original PACS payloads.
- PSP local idempotency remains in memory in the current simulated PSP.
- These simplifications are suitable for the MVP, but do not necessarily represent the final production design.

## Sinais para Evolução

Evolve to coordination, claim/lease, `SKIP LOCKED`, `claim_token`, richer retry, retention, or cleanup when one or more of these signals appears:

- physical duplicate publication or duplicate-caused Kafka traffic grows materially;
- CPU, database, or notification-gateway load caused by duplicates becomes relevant;
- workers frequently contend for the same rows;
- backlog grows while Kafka is healthy, or the oldest `PENDING` row age grows continuously;
- one active attempt per row becomes a requirement;
- automated worker failover, coordination, or duplicate-free rolling deploy becomes necessary;
- exact attempt diagnostics become necessary, or approximate `attempt_count` / `last_error` is insufficient;
- table growth requires retention, partitioning, or cleanup;
- audit-table or WAL growth requires retention, partitioning, archival, or dedicated performance work;
- fixed retry causes a publication storm during outages;
- a terminal failure state, operational intervention, or `DEAD` handling becomes necessary.
