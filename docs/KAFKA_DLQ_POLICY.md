# Kafka DLQ Policy

This document describes the DLQ policy used by the SPI Kafka consumers.

The first DLQ implementation is SPI-only. The `kafka-producer`, `notification-gateway`, and PSP services do not publish to DLQ in this version.

## Topics

| Source topic                         | DLQ topic                                | Consumer group                            |
| ------------------------------------ | ---------------------------------------- | ----------------------------------------- |
| `spi-payment-requests`               | `spi-payment-requests.dlq`               | `spi-payment-request-consumer-group`      |
| `spi-payment-status-reports`         | `spi-payment-status-reports.dlq`         | `spi-status-report-consumer-group`        |

DLQ topics have the same partition count as the source topics. The recoverer publishes failed records to the same partition number in the DLQ topic.

## Policy

### Invalid inbound payload

Invalid inbound payload means the SPI listener cannot recover the record because the Kafka value is null, empty, not valid protobuf, or incompatible with the internal inbound contract.

Behavior:

- publish the individual Kafka record to `<source-topic>.dlq`;
- preserve the original payload as the DLQ value;
- add DLQ metadata headers;
- continue processing the rest of the batch;
- acknowledge the batch only after all valid records are processed and invalid records are published to DLQ.

If publishing the invalid record to DLQ fails, the listener does not acknowledge the batch and propagates the error.

### Expected business rejection

Expected business rejections are part of the payment flow. They should produce the normal business status or rejection result.

Behavior:

- do not publish to DLQ;
- do not treat the message as infrastructure failure;
- follow the normal payment/status flow.

### Unknown processing error

Unknown processing errors are bugs or unexpected failures in mapper, trace recording, use case, repository logic, or other processing code when the failure is not clearly an infrastructure outage.

Behavior:

- retry through Spring Kafka `DefaultErrorHandler`;
- use `FixedBackOff(1000ms, 2 attempts)`;
- after retries are exhausted, publish failed records to DLQ through `DeadLetterPublishingRecoverer`;
- commit recovered offsets after successful DLQ publication.

These errors are not converted to invalid payload. Only protobuf parse failures and explicit payload emptiness are classified as invalid inbound payload.

### Infrastructure unavailable

Infrastructure unavailable means the SPI cannot process the batch because a required dependency is unavailable, for example PostgreSQL cannot provide a JDBC connection.

Currently classified as infrastructure outage:

- `CannotGetJdbcConnectionException`;
- `DataAccessResourceFailureException`.

Behavior:

- do not publish to DLQ;
- do not acknowledge the batch;
- pause/backoff the listener container;
- retry the same batch indefinitely with a long backoff;
- process and acknowledge the same batch when the dependency is available again.

The infrastructure handler uses a `DefaultErrorHandler` without a recoverer. This keeps valid Pix messages out of DLQ during database outages.

## DLQ Headers

Every DLQ record includes metadata headers for diagnosis and operational analysis:

- `dlq.source-topic`;
- `dlq.source-partition`;
- `dlq.source-offset`;
- `dlq.source-timestamp`;
- `dlq.consumer-group`;
- `dlq.service`;
- `dlq.error-type`;
- `dlq.exception-class`;
- `dlq.error-message`;
- `dlq.stacktrace-short`;
- `dlq.failed-at`.

The payload itself is not wrapped in a JSON or protobuf envelope. The original Kafka value is preserved.

## Acknowledgment And Commit Rules

SPI consumers use manual immediate acknowledgment.

Rules:

- valid batch fully processed: acknowledge at the end;
- invalid records successfully sent to DLQ and valid records processed: acknowledge at the end;
- invalid record DLQ publication fails: do not acknowledge;
- infrastructure unavailable: do not acknowledge;
- unknown processing error recovered to DLQ by the error handler: commit the recovered offset.

Kafka auto commit is disabled for these consumers.

## Operational Notes

Hikari connection timeouts are intentionally low for the SPI. The goal is to detect database outages quickly and enter Kafka pause/backoff, not to wait tens of seconds per retry attempt.

The SPI has a manual test hook:

```yaml
spi:
  kafka:
    force-unknown-processing-error: false
```

When enabled, this forces an unknown processing error after protobuf parsing. It is useful to verify that mapper/trace/use-case failures do not get classified as invalid payload and are recovered through the normal retry-to-DLQ path.

## Manual Validation Summary

The following manual scenarios were validated during the DLQ implementation:

- invalid bytes sent to `spi-payment-requests` were published to `spi-payment-requests.dlq`;
- database outage did not publish to DLQ and left the source record uncommitted until PostgreSQL returned;
- after PostgreSQL returned, the same source record was processed and the consumer lag returned to zero;
- forced unknown processing error retried through the short retry handler and then published to DLQ.

The following behavior is covered by automated tests because it is awkward to force manually:

- if publishing an invalid payload to DLQ fails, the listener does not acknowledge the batch.

## Current Limitations

This version does not include dashboards, alerts, Prometheus metrics, or a DLQ replay process.

Manual DLQ replay is outside the scope of this project. The related system-level requirement that remains in scope for future technical work is idempotency: Kafka redelivery or reprocessing must not duplicate settlement, status updates, or PSP balance effects. Current behavior is documented in [Idempotency and Replay Policy](IDEMPOTENCY_REPLAY_POLICY.md).
