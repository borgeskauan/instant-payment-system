# Idempotency and Replay Policy

This document describes the current idempotency policy for Pix message replay and duplicate handling across SPI and PSP services.

The goal is safe at-least-once processing. Kafka redelivery, manual replay, duplicate payloads, and repeated PSP notifications must not duplicate settlement, status transitions, or local PSP balance effects.

This policy does not implement end-to-end exactly-once delivery. PSP notification delivery is tracked separately by the notification gateway with at-least-once retry and explicit PSP ACK.

## Principles

- `paymentId` / `EndToEndId` is the logical identity of a payment.
- Identical replay should reconstruct the required side effect when the persisted state still indicates pending work.
- Identical replay should become a no-op when the persisted state already advanced beyond the pending step.
- Divergent replay with the same identity is deterministic conflict and must be observable.
- Batch-local duplicates are classified before relying on persisted state.
- Final balance effects in PSPs are idempotent by payment and final status side.

## SPI `pacs.008`

Incoming payment requests are persisted through the idempotent `storeAndClassifyIncomingPaymentRequests(...)` flow.

Each incoming request receives a canonical request fingerprint. The current fingerprint identity is:

```text
request_fingerprint_version + request_fingerprint
```

Version and fingerprint must be compared together. A matching hash with a different version is not automatically comparable.

Rules:

| Case | Result |
| ---- | ------ |
| New payment | Insert as `WAITING_ACCEPTANCE` and emit `ACCEPTANCE_REQUEST`. |
| Existing identical payment in `WAITING_ACCEPTANCE` | Re-emit one `ACCEPTANCE_REQUEST`. |
| Existing identical payment in advanced status | No-op. |
| Existing payment without legacy fingerprint | Treat as non-comparable conflict. |
| Existing payment with divergent version/fingerprint | Classify as `DIVERGENT_DUPLICATE` and publish the original record to DLQ. |
| Same batch, same `paymentId`, same fingerprint identity | Keep the first logical record only. |
| Same batch, same `paymentId`, divergent fingerprint identity | Classify all records for that `paymentId` as `DIVERGENT_DUPLICATE`. |

The Kafka offset is acknowledged only after required acceptance notifications are confirmed by the broker and divergent conflicts are successfully published to DLQ.

## SPI `pacs.002`

Incoming status reports are applied conditionally against the current persisted payment state.

Rules:

| Incoming status | Current status | Result |
| --------------- | -------------- | ------ |
| `ACCEPTED_IN_PROCESS` | `WAITING_ACCEPTANCE` | Settle directly when possible and emit final settlement notifications for affected PSPs. |
| `ACCEPTED_IN_PROCESS` | `ACCEPTED_IN_PROCESS` or `ACCEPTED_AND_SETTLED` | No-op. |
| `REJECTED` | `WAITING_ACCEPTANCE` | Update to `REJECTED` and emit rejection notification. |
| `REJECTED` | `REJECTED` | No-op. |
| Any incompatible status transition | Classify as `DIVERGENT_STATUS_REPORT` and publish the original record to DLQ. |
| Missing payment | Classify as `DIVERGENT_STATUS_REPORT` and publish the original record to DLQ. |

Batch-local rules:

| Case | Result |
| ---- | ------ |
| Same batch, same `paymentId`, same status | Keep the first logical report and treat repeated records as batch-local no-ops. |
| Same batch, same `paymentId`, different statuses | Classify all records for that `paymentId` as `DIVERGENT_STATUS_REPORT`. |

Status report processing is still idempotent after settlement. Replaying a status for an already settled payment must not debit SPI funds again.

## PSP Incoming Requests

The PSP receiver keeps local in-memory classification for incoming payment requests received through notifications.

Rules:

| Case | Result |
| ---- | ------ |
| New incoming request | Store locally and send `ACCEPTED_IN_PROCESS` to SPI. |
| Identical incoming request replay | Re-emit `ACCEPTED_IN_PROCESS`. |
| Divergent incoming request with same `paymentId` | Do not overwrite local data and do not send acceptance. |
| Same batch, repeated identical request | Keep the first logical request. |
| Same batch, divergent request | Classify the affected records as divergent locally. |

This preserves recoverability: if the receiver PSP is asked again about a still-pending request, it can rebuild the acceptance response.

## PSP Final Status Notifications

PSP final balance updates are idempotent by:

```text
paymentId + final status
```

The two final sides are tracked independently:

- `ACCEPTED_AND_SETTLED_FOR_SENDER` / external `ACSC`: debit sender account.
- `ACCEPTED_AND_SETTLED_FOR_RECEIVER` / external `ACCC`: credit receiver account.

Rules:

| Case | Result |
| ---- | ------ |
| First final notification for a known payment and side | Apply the debit or credit and mark the final status as applied. |
| Duplicate final notification for the same payment and side | No-op. |
| Sender and receiver final notifications for the same payment | Treated independently. |
| Final notification for unknown local payment | Fail without changing balance. |
| Balance update fails after claim | Release the claim so retry can apply later. |

The PSP must know the original payment locally before applying a final settlement notification. For the receiver, this normally means the PSP previously received the incoming `pacs.008` notification. For the sender, this normally means the PSP initiated the transfer.

## DLQ Relationship

DLQ preserves invalid or deterministic-conflict records for diagnosis and controlled replay. Idempotency makes replay safe, but there is no operational DLQ replay tool in this version.

Current deterministic conflict types:

- `DIVERGENT_DUPLICATE` for `pacs.008`;
- `DIVERGENT_STATUS_REPORT` for `pacs.002`.

If DLQ publication for a deterministic conflict fails, the source batch is not acknowledged.

## Current Limits

- There is no transactional outbox inside SPI for PSP notifications.
- PSP notification delivery is tracked in `notification-gateway`; Kafka broker acknowledgement is not the same as PSP end-to-end acknowledgement.
- A PSP notification is considered `ACKED` only after the PSP processes it and sends an ACK through the gRPC stream.
- PSP local idempotency is in-memory in the current simulated PSP.
- Automated end-to-end replay scenarios in the load-tool are tracked separately in the backlog.
