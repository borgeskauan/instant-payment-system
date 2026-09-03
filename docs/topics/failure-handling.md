# How the system handles failures

This document answers one question:

> When something cannot be processed, should we reject it, retry it, isolate the message, or finish normally?

The focus here is the operational response. The rules that protect the money are in [How payment correctness is preserved](payment-correctness.md), and delivery-specific failures are in [How a confirmation stays recoverable](notification-delivery.md).

## First: can retrying work?

Not every negative result is a technical failure:

| Situation | Classification | Response |
| --- | --- | --- |
| insufficient funds | business result | persist `REJECTED` and notify the payer |
| receiver rejected | business result | return the reservation, persist `REJECTED`, and notify |
| same message appeared again | idempotent duplicate | produce no new effect |
| external message cannot be converted | invalid input at the HTTP boundary | return `400`; do not publish to Kafka |
| mTLS identity cannot be read | invalid authentication | return `401`; do not publish to Kafka |
| Kafka message breaks the internal contract | invalid input in the Payment Processor | isolate the message in the DLQ |
| institution is not allowed to perform the operation | authorization violation | do not change business state and send the message to the DLQ |
| database is temporarily unavailable | infrastructure failure | keep the batch pending and retry |
| internal failure with no safe classification | internal defect | retry, then isolate the batch in the DLQ |

The main decision is:

```text
can the same work succeed later?
        │
        ├── yes, infrastructure unavailable → keep and retry
        │
        └── no with the same input
                ├── identifiable message → isolate that message
                └── uncertain source in batch → handle the whole batch
```

A **dead-letter queue (DLQ)** is not a business rejection and is not a place to store work during a temporary outage. It isolates a message that would keep failing with the same input and would block later messages.

## The HTTP response only describes ingress

The Payment Ingress authenticates the institution from its mTLS certificate, converts the external message to the internal format, and waits for Kafka to confirm publication before replying.

| Response | Meaning |
| --- | --- |
| `200` | every message produced from the input was confirmed by Kafka |
| `400` | the external payment message (PACS) does not have the minimum required structure or values |
| `401` | the institution identity could not be extracted from the certificate |
| `500` | Kafka publication or internal ingress processing failed |

`200` does not mean that the Payment Processor accepted or completed the payment. It only means the input was authenticated, converted, and published.

The Ingress does not decide whether the authenticated institution is the correct payer or whether a response came from the correct receiver. Those rules depend on payment state and stay in the Payment Processor.

### What if only part of the message reaches Kafka?

One external message can produce several Kafka records. If only some of them are confirmed, the HTTP response is `500`.

The client can then send the full message again, including the part that already arrived. The Payment Processor absorbs those duplicates through idempotency. The Ingress does not try to create a distributed transaction across several publications.

## One invalid message does not need to fail the whole batch

Consumers receive groups of messages, but keep the link between each command and its source Kafka record.

Before financial processing, every message is checked on its own:

* `authenticated-ispb` must exist and contain one valid identity;
* payload must not be empty;
* the internal message must be decodable;
* required fields from the internal contract must exist;
* currency, amount, ISPB, and types must be supported.

An invalid record goes to the DLQ while the valid records in the same batch continue.

After payment classification, the Payment Processor can also identify these cases per message:

* a reused identity with different content;
* a decision that conflicts with payment state;
* a decision for a payment that does not exist;
* an attempt by an institution without authority.

These records do not create financial effects and go to the related DLQ. An equivalent duplicate only preserves the existing result and does not go to the DLQ.

The Kafka batch is marked as processed only after:

1. valid messages finish their transaction;
2. invalid messages are confirmed in the DLQ;
3. the full batch reaches a known result.

If DLQ publication fails, the batch stays pending. The publisher waits up to ten seconds for Kafka confirmation and propagates the failure instead of moving forward silently.

## The DLQ keeps context, but does not fix anything

Each input topic has a DLQ in the corresponding logical partition:

```text
spi-payment-requests
→ spi-payment-requests.dlq

spi-payment-status-reports
→ spi-payment-status-reports.dlq
```

The message keeps its original key and payload. Extra metadata records:

* source topic, partition, offset, and timestamp;
* consumer group;
* service that classified the failure;
* error type;
* exception class, message, and stack summary;
* classification timestamp.

Current types include `INVALID_PAYLOAD`, `DIVERGENT_DUPLICATE`, `STATUS_REPORT_CONFLICT`, `NOT_AUTHENTICATED`, `UNAUTHORIZED_PSP`, and `BATCH_PROCESSING_ERROR`.

The DLQ does not reprocess messages automatically. A message returns to the flow only when it is reintroduced after its cause has been reviewed.

## Temporary unavailability keeps the work

When a PostgreSQL failure is classified as transient, the Payment Processor treats the infrastructure as unavailable.

The consumer then:

```text
does not advance its Kafka position
        ↓
pauses the container for 30 s
        ↓
retries the same batch
        ↓
repeats while infrastructure stays unavailable
```

There is no retry limit for this class. The message is still valid. Moving it to the DLQ would lose work that can become processable again without any change to the input.

The PostgreSQL transaction is rolled back before the retry. State, balance, audit, and outbox are not left partially applied.

## A failure with no known source affects the batch

An unexpected exception can happen at a point where the consumer cannot safely connect the cause to one specific message.

In that case, the consumer retries twice with a one-second interval. If the problem remains, every message in the batch goes to the DLQ as `BATCH_PROCESSING_ERROR`, and the batch stops blocking the partition.

This prevents one bad batch from blocking the partition forever. Messages that would be valid on their own can also go to the DLQ in this case because the consumer cannot safely identify the source of the failure.

The system does not guess which message caused an arbitrary exception, and it does not run part of the batch again outside the transaction to find out.

## After the payment, notification failure does not change the result

After commit, a failure to publish a confirmation cannot turn a completed transaction into a false rejection.

The persisted outbox remains the source of truth. The publisher keeps and retries the batch. Remaining records are also published before new payments are consumed after the next startup.

A partial Kafka confirmation or an uncertain outbox deletion can cause the full batch to be sent again. This can create duplicates, but it avoids losing the confirmation.

If an obligation does not reach the publisher right after commit, it is not searched for periodically during the same process run. It returns to the flow on the next startup, as described in [How a confirmation stays recoverable](notification-delivery.md).

## With Pull, the error tells the client what to do

The Notification Gateway does not move a notification to a DLQ when Pull fails. It returns a status that lets the client choose the right response:

| Situation | gRPC status | Expected response |
| --- | --- | --- |
| cursor was changed, belongs to another institution, or has an impossible position | `INVALID_ARGUMENT` | fix local state; retrying without a change will not help |
| cursor expired because of retention | `FAILED_PRECONDITION` | leave the normal operational flow and start recovery |
| second concurrent Pull from the same institution | `FAILED_PRECONDITION` | keep only one logical flow |
| Pull interrupted on the server | `UNAVAILABLE` | retry with the last durable cursor |
| Pull reaches timeout with no data | empty response | send another Pull with the same cursor |

The Gateway never claims on its own that the institution processed a batch. If the connection drops or the response is lost, the client sends its last durable cursor again and may receive duplicate messages.

## Who makes each decision

| Failure | Who classifies it | State preserved | Output |
| --- | --- | --- | --- |
| PACS cannot be converted | Payment Ingress | no message published | HTTP `400` |
| certificate has no valid identity | Payment Ingress | no message published | HTTP `401` |
| Kafka does not confirm ingress | Payment Ingress | client still has the original message | HTTP `500` |
| invalid or unauthenticated internal message | Payment Processor | original payload and Kafka source | topic DLQ |
| conflict or missing authority | Payment Processor | financial state unchanged | topic DLQ |
| equivalent duplicate | Payment Processor | original result | no new effect and normal confirmation |
| valid financial rejection | Payment Processor domain | `REJECTED` fact and notification | business result |
| PostgreSQL unavailable | Payment Processor infrastructure | position and batch not committed | pause and retry |
| persistent internal defect | Payment Processor error handling | original messages | batch to DLQ after short retries |
| uncertain confirmation publication | Payment Processor outbox | obligation in PostgreSQL | retry batch |
| invalid or expired cursor | Notification Gateway | progress remains with the institution | explicit gRPC error |

## Failure-handling scope

Some behaviors stay outside the automatic flow:

* messages in the DLQ return to the flow only after an explicit decision;
* an arbitrary exception during batch processing can send the full batch to the DLQ;
* infrastructure failures classified as transient have no retry limit;
* an obligation that misses the post-commit handoff returns to the flow on the next startup.

The main rule stays simple: **messages that will keep failing without a change leave the main path; temporary infrastructure outages keep the work; business results stay business results, not technical failures**.

## Check it in the code

The HTTP boundary can be inspected in:

* [`ReactorNettyPaymentServer`](../../kafka-producer/src/main/java/br/kauan/kafkaproducer/http/ReactorNettyPaymentServer.java);
* [`KafkaPaymentPublisher`](../../kafka-producer/src/main/java/br/kauan/kafkaproducer/kafka/KafkaPaymentPublisher.java);
* [`ReactorNettyPaymentServerTest`](../../kafka-producer/src/test/java/br/kauan/kafkaproducer/http/ReactorNettyPaymentServerTest.java).

Payment Processor classification and DLQs are in:

* [`PaymentMessageConsumer`](../../spi/src/main/java/br/kauan/spi/adapter/input/kafka/consumer/PaymentMessageConsumer.java);
* [`KafkaErrorHandlingConfig`](../../spi/src/main/java/br/kauan/spi/adapter/input/kafka/infrastructure/error/KafkaErrorHandlingConfig.java);
* [`KafkaDlqConfig`](../../spi/src/main/java/br/kauan/spi/adapter/input/kafka/infrastructure/dlq/KafkaDlqConfig.java);
* [`PaymentMessageConsumerTest`](../../spi/src/test/java/br/kauan/spi/adapter/input/kafka/consumer/PaymentMessageConsumerTest.java);
* [`KafkaErrorHandlingConfigTest`](../../spi/src/test/java/br/kauan/spi/adapter/input/kafka/infrastructure/error/KafkaErrorHandlingConfigTest.java);
* [`KafkaDlqConfigTest`](../../spi/src/test/java/br/kauan/spi/adapter/input/kafka/infrastructure/dlq/KafkaDlqConfigTest.java).

Delivery-protocol responses are in [`NotificationGrpcService`](../../notification-gateway/src/main/java/br/kauan/notificationgateway/grpc/NotificationGrpcService.java) and [`NotificationGrpcServiceTest`](../../notification-gateway/src/test/java/br/kauan/notificationgateway/grpc/NotificationGrpcServiceTest.java).
