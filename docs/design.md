# System design

The system is designed around a small set of observable promises: a payment must not move money twice, reserved money must not remain available for another payment, the final status must agree with the financial result, and a committed outcome must survive failures in the delivery path.

The architecture exists to preserve those properties. Component boundaries and technologies are consequences of that goal.

## Scope and boundaries

The benchmarked core contains:

- **Payment Ingress** (kafka-producer): authenticated HTTP/2 entry point for payment requests and receiver status reports;
- **Payment Processor** (spi): authority over payment state, participant balances, audit facts, idempotency, and outbound notification creation;
- **PostgreSQL**: transactional authority for financial state and creation of notification obligations;
- **Kafka**: ordered input transport and the retained notification-delivery log;
- **PSP Notification Gateway** (notification-gateway): authenticated pull protocol over the notification log;
- **Load Test Harness** (load-test): independent workload generation and external outcome observation.

The reference PSPs, key directory, and Angular application under demo/ make the flow visible to a person. They are not part of the qualified core.

## Payment lifecycle

### 1. Admission reserves the payer's money

A PSP submits a payment request through mTLS HTTP/2. The certificate identifies the authenticated participant; that identity accompanies the internal Kafka record. The SPI remains the authority for the important authorization rule: the authenticated participant must be the payer represented by the payment.

For a genuinely new payment, the SPI evaluates the payer's available balance:

~~~text
enough funds
    → subtract amount from payer's available balance
    → WAITING_ACCEPTANCE
    → notify the receiving PSP that a decision is required

insufficient funds
    → keep balances unchanged
    → REJECTED / INSUFFICIENT_FUNDS
    → notify the payer with RJCT / AM04
~~~

There is no separate reservation table or reserved-balance column. The durable invariant is:

~~~text
payment is WAITING_ACCEPTANCE
⇔
its amount has already left the payer's available balance
~~~

This makes available balance mean exactly “money that can fund a new outgoing payment.” It also prevents an in-flight amount from being spent a second time.

Payments from the same payer are evaluated in source order. A payment that does not fit is rejected, but it does not block a smaller later payment from using the remaining balance. Physical balance mutations are aggregated per participant while outcomes remain individual.

### 2. The receiver decides the outcome

The receiving PSP returns a status report:

~~~text
accepted
    → credit the receiver
    → SETTLED
    → notify payer and receiver

rejected
    → return the reserved amount to the payer
    → REJECTED
    → notify the payer with the external reason
~~~

The payer is not debited again during settlement. Its money was already removed from availability at admission. Each phase therefore mutates one participant balance: reservation and release touch the payer; settlement credit touches the receiver.

This avoids a two-account settlement lock while preserving the financial result. PostgreSQL serializes operations that concurrently target the same participant row.

## One transaction defines one business fact

Every effective transition commits the following changes together:

~~~text
payment state
+ balance mutation
+ business audit fact
+ outbound notification obligation
~~~

If any part fails, all of it rolls back. The database cannot legitimately contain a settled payment without its receiver credit, a waiting payment without reserved funds, or a committed outcome whose notification obligation was never created.

The audit records business facts rather than technical processing attempts:

| Fact | Financial meaning |
| --- | --- |
| PAYMENT_RESERVED | payer availability decreased and the payment is waiting |
| PAYMENT_SETTLED | receiver balance increased and the payment is final |
| PAYMENT_REJECTED at admission | no reservation occurred |
| PAYMENT_REJECTED after a receiver decision | the payer's reservation was released |

A replay that performs no new transition produces no new audit fact and no new logical notification.

The database schema reinforces the model with non-negative participant balances, state/reason shape constraints, and at most one admission fact and one terminal fact per payment.

## Repeated and conflicting messages

At-least-once transport means the same physical message may appear more than once. The system therefore reasons about logical identity rather than delivery count.

For payment requests:

- a new payment identity can reserve funds and create effects;
- an identical replay is a no-op;
- reuse of the identity with different payment content is a conflict and is rejected;
- only the transaction that establishes the payment as new may contribute to the balance delta.

For status reports:

- only a payment still in WAITING_ACCEPTANCE can acquire the transition;
- an accepted replay cannot credit the receiver again;
- a rejected replay cannot release the payer's funds again;
- contradictory reuse is rejected rather than interpreted as another outcome.

The guarded database transition is important even in a single application instance because Kafka listener threads can execute concurrently. Calculating money from records merely received from Kafka would be unsafe; deltas are calculated only from rows whose transition the current transaction actually acquired.

Invalid or conflicting records go to the corresponding input DLQ without invalidating unrelated records in the same batch. Expected business rejection, such as insufficient funds, remains a normal outcome and never becomes a DLQ record. Unknown internal processing failures are retried before DLQ; infrastructure unavailability such as a database outage remains retryable and is not converted into invalid business data.

## A committed outcome must remain deliverable

The financial transaction inserts the final serialized payload into notification_outbox. PostgreSQL therefore protects both the business fact and the existence of its outbound obligation.

After commit, a bounded in-memory queue hands the stored bytes to a single publisher. The publisher sends the complete batch to Kafka with producer idempotence and acks=all. It deletes outbox rows only after every record in the batch is confirmed. Partial or inconclusive publication repeats the whole batch, which can create physical duplicates but cannot silently lose an obligation.

On restart, the SPI drains surviving outbox rows before enabling payment consumers. There is no periodic outbox scan in the healthy path:

~~~text
row exists  = Kafka publication is not confirmed
row absent  = Kafka accepted the notification
~~~

Absence does not mean the PSP has processed it.

## Kafka is the retained delivery log

The psp-notifications-v1 topic holds complete notification payloads for seven days. It has eight partitions and uses recipient ISPB as its key, keeping a PSP on one partition for this topic generation.

The Notification Gateway follows the partitions and stores a bounded contiguous recent window in memory. A healthy pull is served from that window. After restart, eviction, or a gap, the Gateway reads directly from Kafka at the requested offset instead of maintaining another delivery database.

The PSP presents an opaque HMAC-authenticated cursor. Internally it binds:

~~~text
PSP identity
+ topic generation
+ partition
+ last examined offset
~~~

The Gateway returns at most 15 matching notifications and the next cursor. The PSP advances its durable cursor only after durably processing the entire response. If it fails first, it presents the old cursor and receives the data again.

This deliberately provides at-least-once delivery:

- duplicates are allowed and carry a stable communication ID;
- loss within the retained log is not allowed;
- an altered cursor or a cursor from another PSP is rejected;
- a cursor older than Kafka retention fails explicitly and requires operational recovery.

There is no individual ACK write, delivery lease, IN_FLIGHT lifecycle, retry scheduler, delivery index, or Gateway-owned progress checkpoint.

## Why these choices?

### Reservation instead of liquidity buckets

The earlier bucket model fragmented one participant's money across synthetic rows. A participant could have enough total balance and still fail because one selected bucket was empty; settlement also had to lock payer and receiver buckets together. A single available balance with reservation at admission removed artificial liquidity fragmentation and reduced each financial phase to one participant mutation.

### Transactional outbox before Kafka

Publishing directly after a financial commit leaves a crash window in which the payment exists but its outcome does not. Publishing before commit can expose an outcome for a transaction that later rolls back. Persisting the final payload in the financial transaction closes both gaps.

### Kafka log and pull cursor instead of push with ACK state

The previous reliable-push lifecycle required persisted ACKs, leases, retries, delivery status, and a second copy of notification data. It amplified PostgreSQL writes and split delivery authority across systems. The current model makes PostgreSQL authoritative for creating the obligation, Kafka authoritative for the retained delivery history, and the PSP authoritative for its completed progress.

### Open transport duplicates instead of exactly-once claims

Exactly-once delivery is not promised. The system instead makes financial transitions and PSP consumption idempotent. This is a smaller and more explicit contract: physical delivery can repeat while the logical effect occurs once.

## Failure behavior

| Failure | Preserved behavior |
| --- | --- |
| PostgreSQL transaction fails | payment, money, audit, and outbox all roll back |
| SPI fails after commit but before publishing | startup recovery republishes the surviving outbox row |
| Kafka confirmation is partial or inconclusive | the complete outbox batch is repeated |
| SPI deletes the outbox after an uncertain outcome | it does not; deletion requires all confirmations |
| Gateway restarts | the PSP cursor resumes reading from Kafka |
| PSP fails before saving its cursor | the same logical notifications may be returned again |
| cursor is invalid or belongs to another PSP | the request is rejected |
| cursor falls outside seven-day retention | delivery fails explicitly and requires recovery outside the normal protocol |

## Evidence in the repository

The design is enforced by executable tests rather than documentation alone:

- [payment admission, reservation, replay, and conflicts](../spi/src/test/java/br/kauan/spi/adapter/output/paymenttransaction/JdbcPaymentTransactionRepositoryIntegrationTest.java);
- [atomic payment, audit, balance, and outbox outcomes](../spi/src/test/java/br/kauan/spi/domain/services/TransactionalOutboxIntegrationTest.java);
- [rollback when audit or outbox persistence fails](../spi/src/test/java/br/kauan/spi/domain/services/TransactionalOutboxRollbackIntegrationTest.java);
- [concurrent participant balance correctness](../spi/src/test/java/br/kauan/spi/domain/services/ConcurrentParticipantBalanceIntegrationTest.java);
- [cursor authentication and isolation](../notification-gateway/src/test/java/br/kauan/notificationgateway/grpc/DeliveryCursorCodecTest.java);
- [historical Kafka reads after a cache miss](../notification-gateway/src/test/java/br/kauan/notificationgateway/kafka/HistoricalKafkaReaderTest.java).

## Deliberate limits

The qualified deployment uses one instance of each core application and one Kafka broker with replication factor 1. It validates transactional and protocol behavior but not broker, host, or volume high availability. Multi-instance contention, rebalancing across replicas, multi-region recovery, retention beyond seven days, and transport-aware payment admission are future work rather than implicit guarantees.
