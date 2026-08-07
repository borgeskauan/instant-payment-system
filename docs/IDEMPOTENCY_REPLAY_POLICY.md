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
- The notification gateway deduplicates physical Kafka duplicates by `communicationId`.
- Final PSP balance effects are idempotent by payment and final-status side.

## SPI `pacs.008`

Incoming payment requests are persisted through `storeAndClassifyIncomingPaymentRequests(...)`. Each request receives a canonical fingerprint identified by:

```text
request_fingerprint_version + request_fingerprint
```

Version and fingerprint must be compared together. A matching hash with a different version is not automatically comparable.

| Case | Financial classification | Outbox result |
| ---- | ------------------------ | ------------- |
| New payment | Insert as `WAITING_ACCEPTANCE`. | Insert one `ACCEPTANCE_REQUEST` for the receiver. |
| Existing identical payment in `WAITING_ACCEPTANCE` | Classify as acceptance replay. | Try the same `communicationId`; keep an existing row or recreate a missing row. |
| Existing identical payment in advanced status | No-op. | No outbox insert. |
| Existing payment without comparable fingerprint | `DIVERGENT_DUPLICATE`. | No outbox insert; publish the original input to DLQ. |
| Existing payment with divergent version/fingerprint | `DIVERGENT_DUPLICATE`. | No outbox insert; publish the original input to DLQ. |
| Same batch, same `paymentId`, same fingerprint identity | Keep only the first logical record. | At most one obligation. |
| Same batch, same `paymentId`, divergent fingerprint identity | Classify every record as `DIVERGENT_DUPLICATE`. | No outbox insert. |

The financial statement and bulk outbox insert run in one PostgreSQL transaction. Validation, serialization, or outbox persistence failure rolls back the new payment. PostgreSQL resource failures during the outbox insert propagate unchanged to the consumer, remain on the infrastructure retry path, and do not acknowledge the source batch. The source Kafka input is acknowledged only after the database commit and any required DLQ publication; processing does not wait for the outbox worker to publish to Kafka.

Replay never publishes directly. A recreated or still-pending obligation is handled by the scheduled outbox worker.

## SPI `pacs.002`

Incoming status reports are applied conditionally against the current persisted payment state.

| Incoming status | Current status | Financial result | Outbox result |
| --------------- | -------------- | ---------------- | ------------- |
| `ACCEPTED_IN_PROCESS` | `WAITING_ACCEPTANCE` | Settle directly when possible. | On settlement, insert `ACSC` for the payer and `ACCC` for the receiver. |
| `ACCEPTED_IN_PROCESS` | `ACCEPTED_IN_PROCESS` or `ACCEPTED_AND_SETTLED` | No-op. | No outbox insert. |
| `REJECTED` | `WAITING_ACCEPTANCE` | Transition to `REJECTED`. | Insert `REJECTED_NOTIFICATION/RJCT` for the payer. |
| `REJECTED` | `REJECTED` | No-op. | No outbox insert. |
| Any incompatible transition | `DIVERGENT_STATUS_REPORT`. | No outbox insert; publish original input to DLQ. |
| Missing payment | `DIVERGENT_STATUS_REPORT`. | No outbox insert; publish original input to DLQ. |

Batch-local rules:

| Case | Result |
| ---- | ------ |
| Same batch, same `paymentId`, same status | Keep the first logical report; repeated records are batch-local no-ops. |
| Same batch, same `paymentId`, different statuses | Classify every record for that `paymentId` as `DIVERGENT_STATUS_REPORT`. |

Settlement, bucket balances, payment status, and both notification obligations commit or roll back together. Replaying a report that produces no new transition or settlement does not insert an obligation and cannot debit funds again.

## SPI Notification Outbox

The outbox stores one immutable payload per logical notification and recipient. The business payload is built once, serialized with `ObjectMapper.writeValueAsBytes(...)`, and stored as `BYTEA`. The worker sends those exact bytes and does not rebuild them from the current payment state.

`communicationId` retains the existing deterministic algorithm over schema version, event type, recipient, payment, and optional notification status. The row is inserted as `PENDING`; `PUBLISHED` is terminal.

Every SPI instance runs the scheduler. Workers deliberately use no ownership, claim, lease, lock, token, fencing, or coordination. Multiple instances may read and publish the same `PENDING` row concurrently. Each physical message reconstructs the same topic (`psp-notifications`), key (`recipient_ispb`), and headers from immutable columns.

The Kafka producer uses `acks=all`. A successful broker future allows a guarded bulk transition from `PENDING` to `PUBLISHED`. A send failure keeps the row pending and schedules a fixed one-second retry. A process crash or database update failure after a successful send also keeps the row pending, so it may be published again.

All success and failure updates include `WHERE publication_status = 'PENDING'`. A delayed failure therefore cannot reopen a row another worker already published. Concurrent operational fields such as `attempt_count` and `last_error` are intentionally approximate.

This provides durable at-least-once Kafka publication, not exactly-once publication.

## Notification Gateway Deduplication and Delivery

The `notification-gateway` consumes every physical `psp-notifications` message and inserts delivery state with `ON CONFLICT (communication_id) DO NOTHING`. Concurrent or repeated outbox publications therefore create one logical delivery.

The gateway, independently from the SPI outbox, owns delivery retry to the PSP and waits for an explicit PSP ACK. SPI `PUBLISHED` means broker confirmation only; gateway `ACKED` means end-to-end PSP confirmation.

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
- fixed retry causes a publication storm during outages;
- a terminal failure state, operational intervention, or `DEAD` handling becomes necessary.
