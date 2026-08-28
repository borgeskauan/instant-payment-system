# Idempotency and Replay Policy

This document describes the idempotency and replay policy for Pix messages across the SPI, Kafka, the notification gateway, and PSP services.

The goal is safe at-least-once processing. Kafka redelivery, manual replay,
duplicate payloads, producer retries, and repeated PSP notifications must
not duplicate logical obligations, settlement, status transitions, or PSP
balance effects.

There is no end-to-end exactly-once guarantee. Physical Kafka publication and
PSP delivery can repeat. Stable identities at each boundary keep those
repetitions from duplicating logical business effects.

## Principles

- `paymentId` / `EndToEndId` is the logical identity of a payment.
- `communicationId` is the logical identity of one notification and recipient.
- An identical payment replay is a no-op and never reconstructs a second
  notification obligation.
- A divergent replay with the same identity is a deterministic conflict and must be observable.
- Batch-local duplicates are classified before relying on persisted state.
- SPI outbound-notification inserts are strict and are derived only from
  financial transitions actually acquired by the current transaction.
- SPI business audit records only facts effectively applied; `NOOP` creates no audit event.
- The PSP deduplicates physical notification duplicates by `communicationId`.
- Final PSP balance effects are idempotent by payment and final-status side.

## SPI `pacs.008`

Incoming payment requests are persisted through `storeAndClassifyIncomingPaymentRequests(...)`. Each request receives a canonical fingerprint identified by:

```text
request_fingerprint_version + request_fingerprint
```

Version and fingerprint must be compared together. A matching hash with a different version is not automatically comparable.

| Case | Financial classification | Audit result | Notification result |
| ---- | ------------------------ | ------------ | ------------- |
| New payment with available funds | Reserve payer funds and insert as `WAITING_ACCEPTANCE`. | Insert `PAYMENT_RESERVED`. | Insert one `ACCEPTANCE_REQUEST` for the receiver. |
| New payment without available funds | Insert as `REJECTED / INSUFFICIENT_FUNDS` without changing balances. | Insert `PAYMENT_REJECTED` without a financial delta. | Insert one `REJECTED_NOTIFICATION/RJCT` with `AM04` for the payer. |
| Existing identical payment in `WAITING_ACCEPTANCE` | No-op. | No event: the payment was not created again. | No notification insert. The original outbox/Kafka delivery remains authoritative. |
| Existing identical payment in advanced status | No-op. | No event. | No notification insert. |
| Existing payment without comparable fingerprint | `DIVERGENT_DUPLICATE`. | No event in the business audit. | No notification insert; publish the original input to DLQ. |
| Existing payment with divergent version/fingerprint | `DIVERGENT_DUPLICATE`. | No event in the business audit. | No notification insert; publish the original input to DLQ. |
| Same batch, same `paymentId`, same fingerprint identity | Keep only the first logical record. | At most one effective event. | At most one obligation. |
| Same batch, same `paymentId`, divergent fingerprint identity | Classify every record as `DIVERGENT_DUPLICATE`. | No event. | No notification insert. |

The financial statement, business-audit insert, and outbound-notification
insert run as separate bulk statements in one PostgreSQL transaction.
Validation or persistence failure in audit or notification storage rolls back
the new payment. PostgreSQL resource failures during either insert propagate
unchanged to the consumer, remain on the infrastructure retry path, and do not
acknowledge the source batch. The source Kafka input is acknowledged only after
the database commit and any required DLQ publication; processing does not wait
for Kafka notification publication.

Replay never bypasses the durable obligation. If it creates an effective
notification, that notification follows the same transactional outbox and
Kafka log as every other notification.

## SPI `pacs.002`

Incoming status reports are applied conditionally against the current persisted payment state.

| Incoming status | Current status | Financial result | Audit result | Notification result |
| --------------- | -------------- | ---------------- | ------------ | ------------- |
| `ACCEPTED_IN_PROCESS` | `WAITING_ACCEPTANCE` | Credit the receiver and settle directly; the payer was already debited by the reservation. | `PAYMENT_SETTLED`. | Insert `ACSC` for the payer and `ACCC` for the receiver. |
| `ACCEPTED_IN_PROCESS` | `ACCEPTED_IN_PROCESS` or `ACCEPTED_AND_SETTLED` | No-op. | No event. | No notification insert. |
| `REJECTED` | `WAITING_ACCEPTANCE` | Release the payer reservation and transition to `REJECTED`. | `PAYMENT_REJECTED` with the release delta. | Insert `REJECTED_NOTIFICATION/RJCT` for the payer. |
| `REJECTED` | `REJECTED` | No-op. | No event. | No notification insert. |
| Any incompatible transition | Any incompatible current state | `STATUS_REPORT_CONFLICT`. | No event in the business audit. | No notification insert; publish original input to DLQ. |
| Any status | Missing payment | `STATUS_REPORT_CONFLICT`. | No event in the business audit. | No notification insert; publish original input to DLQ. |

Batch-local rules:

| Case | Result |
| ---- | ------ |
| Same batch, same `paymentId`, same status | Keep the first logical report; repeated records are batch-local no-ops. |
| Same batch, same `paymentId`, different statuses | Classify every record for that `paymentId` as `STATUS_REPORT_CONFLICT`. |

Settlement, participant balances, the consolidated audit event, and both notification
obligations commit or roll back together. Replaying a report that produces no
new transition or settlement inserts neither audit nor notification rows and
cannot debit funds again.

## SPI Business Audit

`payment_audit_event` is append-only by application behavior and stores only normalized business facts. It does not store the original PACS payload, diagnostic attempts, input rejections, or `NOOP` replay events.

The current model stores only `PAYMENT_RESERVED`, `PAYMENT_SETTLED`, and
`PAYMENT_REJECTED`. Atomic business effects are consolidated: creation plus
reservation is one fact, acceptance plus receiver credit is one fact, and a
post-reservation rejection includes the release delta. `event_id` remains a
technical identity and does not define causal order. Partial unique indexes
allow at most one admission result and one terminal result per payment.

There is no audit backfill. A replay that applies a real creation, transition, or settlement produces the normal events for that effect; a replay that applies nothing produces no event.

## SPI Outbound Notifications

`notification_outbox` stores one immutable payload per logical notification
and recipient. The business payload is built once, serialized with
`ObjectMapper.writeValueAsBytes(...)`, and stored as `BYTEA` in the same
transaction as the financial effect.

`communicationId` retains the deterministic algorithm over the notification
type, recipient, payment, and outcome. The outbox row means the corresponding
Kafka publication has not yet been confirmed.

After commit, a bounded in-memory queue feeds one publisher. It sends the exact
stored bytes to `psp-notifications-v1`, keyed by `recipient_ispb`, with
`notification.communication-id` as header. The producer uses idempotence and
`acks=all`.

The SPI deletes a batch only after every broker future succeeds. Partial,
inconclusive, or delete failure repeats the entire batch. Startup drains all
surviving rows before payment consumers start. Consequently, a notification
can appear physically more than once in Kafka but cannot be omitted after its
financial transaction committed, provided Kafka remains recoverable.

## Notification Gateway and PSP Delivery

Kafka is the durable delivery log. `psp-notifications-v1` has eight fixed
partitions and seven-day retention. `recipient_ispb` is the Kafka key, so one
PSP remains in one partition for this topic generation.

The authenticated PSP calls unary `PullNotifications(cursor)` with at most one
request in flight and receives at most 15 notifications. The cursor is opaque,
HMAC-authenticated, and bound to PSP, topic generation, partition, and the last
Kafka offset examined. It may advance over records for other PSPs sharing the
partition, while the response includes only the authenticated recipient.

The PSP advances its durable cursor only after durably processing the whole
response. Reusing an older cursor returns messages again, providing
at-least-once delivery without ACK persistence, `IN_FLIGHT`, leases,
`delivery_index`, or a PostgreSQL reconciler.

The Gateway tails all partitions into bounded contiguous memory windows. Pull
uses memory while it covers the cursor; on restart, eviction, or gap it reads
the Kafka partition directly. A cursor older than retained Kafka history fails
explicitly and requires operational recovery.

Physical duplicates keep the same `communicationId`. The Gateway does not
collapse them into a second logical index; PSP processing uses
`communicationId` idempotently.

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

Current deterministic conflict types are `DIVERGENT_DUPLICATE` for `pacs.008` and `STATUS_REPORT_CONFLICT` for `pacs.002`. If required DLQ publication fails, the source batch is not acknowledged.

## Limitações Conscientes

- There is no exactly-once guarantee; producer retries and replay from an older
  cursor can physically repeat a message.
- The local MVP uses one Kafka broker and replication factor 1; it does not
  prove broker, host, or volume HA.
- The notification log is retained for seven days. Older recovery belongs to
  disaster recovery and is outside the normal Pull protocol.
- Topic topology is fixed at eight partitions for this generation.
- The SPI has no ingress admission control tied to notification transport
  health.
- There is no backfill for payments created before the notification-store migration.
- There is no business-audit backfill, retention, cleanup, partitioning, or causal event ordering.
- Business audit excludes `NOOP` replay, retries, redelivery, input rejection, and original PACS payloads.
- PSP local idempotency remains in memory in the current simulated PSP.
- These simplifications are suitable for the MVP, but do not necessarily represent the final production design.

## Sinais para Evolução

Evolve Kafka HA, retention, admission control, or recovery when one or more of
these signals appears:

- physical duplicate publication or duplicate-caused Kafka traffic grows materially;
- CPU, database, or notification-gateway load caused by duplicates becomes relevant;
- broker/host failure must be tolerated without an availability gap;
- seven-day retention is insufficient for the operational recovery target;
- notification transport outages must stop financial admission explicitly;
- durable publication-attempt diagnostics become necessary;
- Kafka storage growth requires tiering, archival, or a different retention policy;
- audit-table or WAL growth requires retention, partitioning, archival, or dedicated performance work;
- a terminal failure state or operational intervention for irrecoverable
  notification data becomes necessary.
