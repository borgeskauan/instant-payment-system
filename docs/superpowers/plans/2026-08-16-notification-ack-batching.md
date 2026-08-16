# Batched Notification Acknowledgements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist inbound notification ACKs in bounded asynchronous PostgreSQL batches while preserving authenticated ownership, at-least-once redelivery, and the existing external protocol.

**Architecture:** Replace the synchronous repository call in each gRPC `onNext` with a bounded global `AcknowledgementBatcher`. One lifecycle-managed worker forms batches of at most 500 identities or 20 ms, retries failed batches without dropping them, and calls one PostgreSQL `UPDATE ... FROM unnest(...)` transaction per batch. Queue saturation applies backpressure to inbound streams and is a capacity failure, not a healthy steady state.

**Tech Stack:** Java 21, Spring Boot 3.5, gRPC Java, Spring JDBC, PostgreSQL 17, JUnit 5, Mockito, AssertJ, Testcontainers, Docker Compose, JFR, existing load-test runner.

## Global Constraints

- An ACK becomes durable only when its `notification_delivery` row commits as `ACKED`.
- A gateway or PostgreSQL failure before commit may cause redelivery but must never silently complete the delivery obligation.
- Keep one global queue, one writer, maximum batch size `500`, maximum batch wait `20 ms`, queue capacity `10_000`, persistence retry delay `100 ms`, and normal-shutdown flush deadline `5 s`.
- Queue-full behavior is blocking backpressure. Do not drop ACKs, close healthy streams merely because the queue is full, or fall back to individual synchronous updates.
- Preserve the `(communication_id, authenticated recipient_ispb)` ownership predicate and `delivery_status <> 'ACKED'` idempotency predicate.
- Do not change the protobuf contract, notification schema, Kafka ingress, claim, dispatch ordering, lease, redelivery, SPI, buckets, outbox, load-tool, CPU, or memory limits.
- Runtime settings belong to `notification-gateway.delivery.ack`; they are not workload-profile fields.
- Treat queue saturation during B as a capacity failure even if redelivery eventually recovers every notification.
- Use `preparation-workflow-verification/20260816_004331` on commit `1a4f395` as A; do not rerun or rewrite A.
- Do not run `mixed-outcomes-2k-15m` in this slice. The only performance run is `mixed-outcomes-2k-diagnostic`.
- Do not create commits or stage files. The user reviews the complete implementation through `git diff` and creates commits separately.

---

### Task 1: Add the authenticated bulk-ACK repository primitive

**Files:**
- Create: `notification-gateway/src/main/java/br/kauan/notificationgateway/delivery/Acknowledgement.java`
- Modify: `notification-gateway/src/main/java/br/kauan/notificationgateway/delivery/NotificationDeliveryRepository.java`
- Modify: `notification-gateway/src/test/java/br/kauan/notificationgateway/delivery/NotificationDeliveryRepositoryTest.java`
- Modify: `notification-gateway/src/test/java/br/kauan/notificationgateway/delivery/NotificationDeliveryDeduplicationIntegrationTest.java`

**Interfaces:**
- Produces: `Acknowledgement(String communicationId, String recipientIspb)`.
- Produces: `NotificationDeliveryRepository.acknowledgeAll(List<Acknowledgement>) -> int`, returning the number of rows newly transitioned to `ACKED`.
- Preserves: `saveAllIfAbsent`, `claimForLocalIspbs`, and `markRetryableFailed` unchanged.
- Removes only after Task 3 is green: `acknowledge(String, String)`.

- [ ] **Step 1: Add failing repository-unit tests for one stable array statement**

In `NotificationDeliveryRepositoryTest`, add tests that invoke the wished-for API:

```java
@Test
void acknowledgeAllUsesOneAuthenticatedArrayUpdateInsideOneTransaction() throws Exception {
    NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    TransactionTemplate transaction = mock(TransactionTemplate.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    Array communicationIds = mock(Array.class);
    Array recipientIspbs = mock(Array.class);

    when(named.getJdbcTemplate()).thenReturn(jdbc);
    when(transaction.execute(any())).thenAnswer(invocation ->
            ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
    when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(invocation ->
            ((ConnectionCallback<?>) invocation.getArgument(0)).doInConnection(connection));
    when(connection.createArrayOf(eq("text"), any(Object[].class)))
            .thenReturn(communicationIds, recipientIspbs);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    when(rows.next()).thenReturn(true, true, false);

    NotificationDeliveryRepository repository =
            new NotificationDeliveryRepository(named, transaction, Clock.systemUTC());

    int updated = repository.acknowledgeAll(List.of(
            new Acknowledgement("v1:first", "20000001"),
            new Acknowledgement("v1:second", "20000002")
    ));

    assertThat(updated).isEqualTo(2);
    verify(transaction, times(1)).execute(any());
    verify(jdbc, times(1)).execute(any(ConnectionCallback.class));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(connection).prepareStatement(sql.capture());
    assertThat(sql.getValue())
            .contains("FROM unnest(?::text[], ?::text[])")
            .contains("delivery.communication_id = ack.communication_id")
            .contains("delivery.recipient_ispb = ack.recipient_ispb")
            .contains("delivery.delivery_status <> 'ACKED'")
            .contains("RETURNING delivery.communication_id");

    verify(connection, times(2)).createArrayOf(eq("text"), any(Object[].class));
    verify(communicationIds).free();
    verify(recipientIspbs).free();
}

@Test
void acknowledgeAllSkipsJdbcForAnEmptyBatch() {
    NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    TransactionTemplate transaction = mock(TransactionTemplate.class);
    NotificationDeliveryRepository repository =
            new NotificationDeliveryRepository(named, transaction, Clock.systemUTC());

    assertThat(repository.acknowledgeAll(List.of())).isZero();

    verifyNoInteractions(named, transaction);
}
```

Capture the two arrays separately and assert their values are exactly:

```text
communication IDs: [v1:first, v1:second]
recipient ISPBs:    [20000001, 20000002]
```

- [ ] **Step 2: Add failing PostgreSQL integration tests for ownership and idempotency**

In `NotificationDeliveryDeduplicationIntegrationTest`, add helpers that insert three distinct `notification_delivery` rows through `saveAllIfAbsent`, then cover:

```java
@Test
void bulkAcknowledgementUpdatesOnlyMatchingAuthenticatedPairs() {
    repository.saveAllIfAbsent(List.of(
            incomingDelivery("v1:first", "20000001"),
            incomingDelivery("v1:second", "20000002"),
            incomingDelivery("v1:third", "20000003")
    ));

    int updated = repository.acknowledgeAll(List.of(
            new Acknowledgement("v1:first", "20000001"),
            new Acknowledgement("v1:second", "99999999"),
            new Acknowledgement("v1:unknown", "20000003")
    ));

    assertThat(updated).isOne();
    assertThat(deliveryStatus("v1:first")).isEqualTo("ACKED");
    assertThat(deliveryStatus("v1:second")).isEqualTo("PENDING");
    assertThat(deliveryStatus("v1:third")).isEqualTo("PENDING");
}

@Test
void replayedBulkAcknowledgementDoesNotCreateAnotherTransition() {
    repository.saveAllIfAbsent(List.of(incomingDelivery("v1:first", "20000001")));
    List<Acknowledgement> batch =
            List.of(new Acknowledgement("v1:first", "20000001"));

    assertThat(repository.acknowledgeAll(batch)).isOne();
    assertThat(repository.acknowledgeAll(batch)).isZero();
    assertThat(deliveryStatus("v1:first")).isEqualTo("ACKED");
}
```

Add these exact helpers so the recipient is part of every test fixture:

```java
private IncomingNotification incomingDelivery(
        String communicationId,
        String recipientIspb
) {
    return incomingNotification(communicationId, recipientIspb, "v1");
}

private IncomingNotification incomingNotification(
        String communicationId,
        String recipientIspb,
        String schemaVersion
) {
    return new IncomingNotification(
            communicationId,
            recipientIspb,
            "SETTLED_NOTIFICATION",
            "E2E-1",
            "ACSC",
            schemaVersion,
            PAYLOAD
    );
}
```

Change the invalid-schema fixture to
`incomingNotification("v1:invalid", "20000001", null)` and the valid fixture
to `incomingNotification("v1:valid", "20000001", "v1")`; keep all
Kafka-ingress assertions unchanged.

- [ ] **Step 3: Run the focused tests and confirm RED**

Run:

```bash
cd notification-gateway
./mvnw -Dtest=NotificationDeliveryRepositoryTest,NotificationDeliveryDeduplicationIntegrationTest test
```

Expected: compilation fails because `Acknowledgement` and `acknowledgeAll` do not exist.

- [ ] **Step 4: Create the immutable acknowledgement identity**

Create `Acknowledgement.java`:

```java
package br.kauan.notificationgateway.delivery;

import java.util.Objects;

public record Acknowledgement(String communicationId, String recipientIspb) {

    public Acknowledgement {
        Objects.requireNonNull(communicationId, "communicationId");
        Objects.requireNonNull(recipientIspb, "recipientIspb");
        if (communicationId.isBlank()) {
            throw new IllegalArgumentException("communicationId must not be blank");
        }
        if (recipientIspb.isBlank()) {
            throw new IllegalArgumentException("recipientIspb must not be blank");
        }
    }
}
```

- [ ] **Step 5: Implement one bulk PostgreSQL update**

In `NotificationDeliveryRepository`, add this stable positional SQL:

```java
private static final String ACK_ALL_SQL = """
        UPDATE notification_delivery AS delivery
        SET delivery_status = 'ACKED',
            acknowledged_at = ?,
            lease_until = NULL,
            updated_at = ?
        FROM unnest(?::text[], ?::text[])
             AS ack(communication_id, recipient_ispb)
        WHERE delivery.communication_id = ack.communication_id
          AND delivery.recipient_ispb = ack.recipient_ispb
          AND delivery.delivery_status <> 'ACKED'
        RETURNING delivery.communication_id
        """;
```

Implement:

```java
public int acknowledgeAll(List<Acknowledgement> acknowledgements) {
    if (acknowledgements.isEmpty()) {
        return 0;
    }
    Integer updated = transactionTemplate.execute(ignored ->
            jdbcTemplate.getJdbcTemplate().execute((ConnectionCallback<Integer>) connection -> {
                String[] communicationIds = acknowledgements.stream()
                        .map(Acknowledgement::communicationId)
                        .toArray(String[]::new);
                String[] recipientIspbs = acknowledgements.stream()
                        .map(Acknowledgement::recipientIspb)
                        .toArray(String[]::new);
                OffsetDateTime now = timestamp(clock.instant());
                Array communicationIdArray = null;
                Array recipientIspbArray = null;
                try {
                    communicationIdArray = connection.createArrayOf("text", communicationIds);
                    recipientIspbArray = connection.createArrayOf("text", recipientIspbs);
                    try (PreparedStatement statement = connection.prepareStatement(ACK_ALL_SQL)) {
                        statement.setObject(1, now);
                        statement.setObject(2, now);
                        statement.setArray(3, communicationIdArray);
                        statement.setArray(4, recipientIspbArray);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            int count = 0;
                            while (resultSet.next()) {
                                count++;
                            }
                            return count;
                        }
                    }
                } finally {
                    free(communicationIdArray, recipientIspbArray);
                }
            }));
    return updated == null ? 0 : updated;
}
```

Add a null-safe private `free(Array...)` helper matching the SPI's existing JDBC-array pattern. Do not remove the old single-ACK method yet because the gRPC service still consumes it until Task 3.

- [ ] **Step 6: Run focused repository tests and verify GREEN**

Run:

```bash
cd notification-gateway
./mvnw -Dtest=NotificationDeliveryRepositoryTest,NotificationDeliveryDeduplicationIntegrationTest test
```

Expected: all focused tests pass against PostgreSQL Testcontainers.

- [ ] **Step 7: Review Task 1 without staging or committing**

Run:

```bash
git diff --check
git diff -- notification-gateway/src/main/java/br/kauan/notificationgateway/delivery notification-gateway/src/test/java/br/kauan/notificationgateway/delivery
```

Expected: only the authenticated bulk primitive and its tests changed.

---

### Task 2: Implement the bounded acknowledgement batcher

**Files:**
- Create: `notification-gateway/src/main/java/br/kauan/notificationgateway/delivery/AcknowledgementBatcher.java`
- Create: `notification-gateway/src/test/java/br/kauan/notificationgateway/delivery/AcknowledgementBatcherTest.java`

**Interfaces:**
- Consumes: `NotificationDeliveryRepository.acknowledgeAll(List<Acknowledgement>) -> int`.
- Produces: `AcknowledgementBatcher.start()`, `stop()`, `isRunning()`, and `enqueue(Acknowledgement) -> boolean throws InterruptedException`.
- Produces: one Spring `SmartLifecycle` component; `enqueue` returns `false` only when the lifecycle is not accepting new work.
- Lifecycle invariant: the batcher starts before the gRPC server and stops
  after it, so every callback admitted by gRPC sees an operational batcher.
- Admission invariant: once `enqueue` returns `true`, persistence failure is
  owned by the writer retry loop and does not close the stream. `UNAVAILABLE`
  is reserved for an ACK that was not admitted or whose blocked admission was
  interrupted.

- [ ] **Step 1: Write failing tests for size- and time-based flush**

Create `AcknowledgementBatcherTest` with small injected parameters. Use `CountDownLatch`, thread-safe captured batches, and polling assertions bounded by one second; do not use arbitrary multi-second sleeps.

Add:

```java
@Test
void flushesImmediatelyWhenBatchSizeIsReached() throws Exception {
    NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
    FlushProbe probe = new FlushProbe();
    when(repository.acknowledgeAll(anyList())).thenAnswer(probe);
    AcknowledgementBatcher batcher = batcher(repository, 2, Duration.ofSeconds(1), 4);
    batcher.start();
    try {
        assertThat(batcher.enqueue(ack("v1:first"))).isTrue();
        assertThat(batcher.enqueue(ack("v1:second"))).isTrue();

        assertThat(probe.awaitFlush(Duration.ofSeconds(1))).isTrue();
        assertThat(probe.batches()).containsExactly(List.of(
                ack("v1:first"), ack("v1:second")
        ));
    } finally {
        batcher.stop();
    }
}

@Test
void flushesPartialBatchWhenMaximumWaitExpires() throws Exception {
    NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
    FlushProbe probe = new FlushProbe();
    when(repository.acknowledgeAll(anyList())).thenAnswer(probe);
    AcknowledgementBatcher batcher = batcher(repository, 500, Duration.ofMillis(20), 10_000);
    batcher.start();
    try {
        assertThat(batcher.enqueue(ack("v1:first"))).isTrue();
        assertThat(probe.awaitFlush(Duration.ofSeconds(1))).isTrue();
        assertThat(probe.batches()).containsExactly(List.of(ack("v1:first")));
    } finally {
        batcher.stop();
    }
}
```

Define `FlushProbe implements Answer<Integer>` inside the test. It must copy
each received list, expose a `CountDownLatch`-based `awaitFlush`, and return the
batch size. The package-private test constructor must accept explicit
`batchSize`, `flushInterval`, `queueCapacity`, `retryDelay`, and
`shutdownTimeout` values while the public Spring constructor consumes
application properties.

Use this test helper for ordinary cases:

```java
private AcknowledgementBatcher batcher(
        NotificationDeliveryRepository repository,
        int batchSize,
        Duration flushInterval,
        int queueCapacity
) {
    return new AcknowledgementBatcher(
            repository,
            batchSize,
            flushInterval,
            queueCapacity,
            Duration.ofMillis(1),
            Duration.ofSeconds(1)
    );
}

private Acknowledgement ack(String communicationId) {
    return new Acknowledgement(communicationId, "20000001");
}
```

- [ ] **Step 2: Write failing tests for deduplication and single-writer behavior**

Add:

```java
@Test
void collapsesDuplicateAuthenticatedIdentitiesInsideOneBatch() throws Exception {
    NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
    FlushProbe probe = new FlushProbe();
    when(repository.acknowledgeAll(anyList())).thenAnswer(probe);
    AcknowledgementBatcher batcher = batcher(repository, 3, Duration.ofSeconds(1), 4);
    batcher.start();
    try {
        batcher.enqueue(ack("v1:first"));
        batcher.enqueue(ack("v1:first"));
        batcher.enqueue(ack("v1:second"));

        assertThat(probe.awaitFlush(Duration.ofSeconds(1))).isTrue();
        assertThat(probe.batches()).containsExactly(List.of(
                ack("v1:first"), ack("v1:second")
        ));
    } finally {
        batcher.stop();
    }
}
```

Add `BlockingFlushProbe implements Answer<Integer>` that blocks its first call
and tracks concurrent invocations. Enqueue enough ACKs for two batches, release
the first call, and assert `maximumConcurrentFlushes() == 1`.

- [ ] **Step 3: Write failing tests for queue-full backpressure**

Start a batcher with batch size `1` and queue capacity `1`. Configure the
repository mock to enter its first flush and wait on a latch. Enqueue the first
ACK and wait until the writer is blocked inside that flush; enqueue a second ACK
to fill the queue. Submit a third enqueue on another thread and assert it does
not complete within 50 ms. Release the repository latch, then assert the future
completes with `true` and all three ACKs eventually flush.

```java
assertThat(batcher.enqueue(ack("v1:first"))).isTrue();
assertThat(repositoryEntered.await(1, TimeUnit.SECONDS)).isTrue();
assertThat(batcher.enqueue(ack("v1:second"))).isTrue();
Future<Boolean> blocked = executor.submit(() -> batcher.enqueue(ack("v1:third")));
assertThatThrownBy(() -> blocked.get(50, TimeUnit.MILLISECONDS))
        .isInstanceOf(TimeoutException.class);

allowRepositoryToFinish.countDown();
assertThat(blocked.get(1, TimeUnit.SECONDS)).isTrue();
```

This test proves bounded blocking rather than dropping, disconnecting, or synchronous fallback.

- [ ] **Step 4: Write failing tests for database retry and bounded shutdown**

Add one probe that throws `DataAccessResourceFailureException` on its first call and succeeds on its second. Assert both calls receive the exact same deduplicated batch and that no later ACK overtakes the retained batch.

Add a shutdown test with a long flush interval: enqueue a partial batch, call `stop`, and assert it flushes before `stop` returns. Add a permanently failing probe with a 50 ms injected shutdown timeout and assert `stop` returns within one second while the repository never reports a successful update.

Add lifecycle rejection and interruption tests:

```text
enqueue before start       -> false
enqueue after stop         -> false
interrupted blocked thread -> InterruptedException; Task 3 proves the gRPC caller restores the flag
stop while enqueue waits   -> blocked enqueue wakes and returns false
```

- [ ] **Step 5: Run the batcher tests and confirm RED**

Run:

```bash
cd notification-gateway
./mvnw -Dtest=AcknowledgementBatcherTest test
```

Expected: compilation fails because `AcknowledgementBatcher` does not exist.

- [ ] **Step 6: Implement the lifecycle-managed bounded batcher**

Create `AcknowledgementBatcher` as a Spring `@Component` implementing
`SmartLifecycle`. Annotate the public production constructor with `@Autowired`
because the class also has a package-private constructor for tests.

Production properties:

```java
public AcknowledgementBatcher(
        NotificationDeliveryRepository repository,
        @Value("${notification-gateway.delivery.ack.batch-size:500}") int batchSize,
        @Value("${notification-gateway.delivery.ack.flush-interval-ms:20}") long flushIntervalMillis,
        @Value("${notification-gateway.delivery.ack.queue-capacity:10000}") int queueCapacity,
        @Value("${notification-gateway.delivery.ack.retry-delay-ms:100}") long retryDelayMillis,
        @Value("${notification-gateway.delivery.ack.shutdown-timeout-ms:5000}") long shutdownTimeoutMillis
)
```

Validate every numeric value as positive. Store durations as `Duration`, allocate one `ArrayBlockingQueue<Acknowledgement>`, and maintain atomic `running` and `accepting` lifecycle flags.

The public enqueue contract is:

```java
public boolean enqueue(Acknowledgement acknowledgement) throws InterruptedException {
    Objects.requireNonNull(acknowledgement, "acknowledgement");
    while (accepting.get()) {
        if (queue.offer(acknowledgement, 50, TimeUnit.MILLISECONDS)) {
            return true;
        }
    }
    return false;
}
```

The 50 ms timed-offer slice is an internal lifecycle check, not another tuning
parameter. It preserves blocking backpressure while allowing `stop()` to wake
already waiting callbacks without dropping an accepted queue entry.

Start one named daemon platform thread so a JDBC call that ignores interruption
cannot hold JVM termination beyond the bounded shutdown deadline. Use
`Thread.ofPlatform().daemon().name("notification-ack-batcher").start(this::runWriter)`.
Its loop must:

1. wait for the first acknowledgement;
2. calculate a deadline from `System.nanoTime()`;
3. collect until the configured size or deadline;
4. collapse identities through a `LinkedHashSet`;
5. call `repository.acknowledgeAll(List.copyOf(batch))`;
6. on `DataAccessException`, retain that exact batch and retry it after `retryDelay` before reading more queue entries;
7. log requested, updated, and ignored aggregate counts at debug level;
8. continue while running, or while shutdown still has queued/current work within its deadline.

`stop()` must set `accepting=false`, set `running=false`, establish the
five-second deadline from `System.nanoTime()`, call `writerThread.interrupt()`
to wake a queue wait or retry sleep, and join the thread only for the remaining
deadline. The writer treats interruption during shutdown as a wake-up: it
flushes any retained/queued work while time remains, but it does not extend the
deadline. Return even after repeated database failure. Do not clear the queue
as a success action; log the retained plus queued count and that uncommitted
ACKs will recover by redelivery.

- [ ] **Step 7: Run batcher tests and verify GREEN**

Run:

```bash
cd notification-gateway
./mvnw -Dtest=AcknowledgementBatcherTest test
```

Expected: every size, time, deduplication, backpressure, retry, single-writer, and shutdown case passes without leaked threads.

- [ ] **Step 8: Review Task 2 without staging or committing**

Run:

```bash
git diff --check
git diff -- notification-gateway/src/main/java/br/kauan/notificationgateway/delivery/AcknowledgementBatcher.java notification-gateway/src/test/java/br/kauan/notificationgateway/delivery/AcknowledgementBatcherTest.java
```

Expected: the batcher depends only on the repository and Java/Spring concurrency primitives; it contains no gRPC, Kafka, SPI, or load-test logic.

---

### Task 3: Route gRPC ACKs through the batcher and expose fixed B parameters

**Files:**
- Modify: `notification-gateway/src/main/java/br/kauan/notificationgateway/grpc/NotificationGrpcService.java`
- Modify: `notification-gateway/src/main/java/br/kauan/notificationgateway/delivery/NotificationDeliveryRepository.java`
- Modify: `notification-gateway/src/test/java/br/kauan/notificationgateway/grpc/NotificationGrpcServiceTest.java`
- Modify: `notification-gateway/src/test/java/br/kauan/notificationgateway/delivery/NotificationDeliveryRepositoryTest.java`
- Modify: `notification-gateway/src/main/resources/application.yml`
- Modify: `infra/docker-compose.yml`

**Interfaces:**
- Consumes: `AcknowledgementBatcher.enqueue(Acknowledgement) -> boolean throws InterruptedException`.
- Produces: the unchanged public bidirectional gRPC protocol.
- Removes: `NotificationDeliveryRepository.acknowledge(String, String)` and its direct unit tests.
- Produces: five `notification-gateway.delivery.ack.*` properties with exact Compose environment overrides.

- [ ] **Step 1: Rewrite the gRPC test to require asynchronous enqueue**

Replace the repository mock in `NotificationGrpcServiceTest` with an `AcknowledgementBatcher` mock and verify:

```java
when(batcher.enqueue(new Acknowledgement("v1:delivery", "20000001")))
        .thenReturn(true);

requestObserver.onNext(ClientMessage.newBuilder()
        .setAck(Ack.newBuilder().setDeliveryId("v1:delivery"))
        .build());

verify(batcher).enqueue(new Acknowledgement("v1:delivery", "20000001"));
```

Keep the existing authenticated registration/dispatch assertion. Add:

```text
blank delivery ID       -> no enqueue and stream remains open
batcher returns false   -> unregister and responseObserver receives UNAVAILABLE
enqueue interrupted     -> restore interrupt flag, unregister, respond UNAVAILABLE
message without ACK     -> existing INVALID_ARGUMENT behavior
dispatch vs terminal    -> serialized per observer; no onNext after onError
```

Use a response observer that captures `onError` so status codes are asserted through `Status.fromThrowable(error).getCode()`.

- [ ] **Step 2: Run the gRPC test and confirm RED**

Run:

```bash
cd notification-gateway
./mvnw -Dtest=NotificationGrpcServiceTest test
```

Expected: compilation fails because the service still depends on `NotificationDeliveryRepository`.

- [ ] **Step 3: Inject and call the batcher from the gRPC service**

Replace the repository field with `AcknowledgementBatcher`. For a non-blank ACK:

```java
try {
    boolean enqueued = acknowledgementBatcher.enqueue(
            new Acknowledgement(deliveryId, authenticatedIspb)
    );
    if (!enqueued) {
        fail(io.grpc.Status.UNAVAILABLE, "acknowledgement persistence is stopping");
    }
} catch (InterruptedException interrupted) {
    Thread.currentThread().interrupt();
    fail(io.grpc.Status.UNAVAILABLE, "acknowledgement enqueue interrupted");
}
```

Refactor the existing invalid-message helper to accept the gRPC `Status` while preserving `INVALID_ARGUMENT` for a message without ACK. Do not send an ACK-of-ACK response.

- [ ] **Step 4: Remove the obsolete single-update path**

Delete `ACK_SQL`, `acknowledge(String, String)`, and the three repository unit tests that exist only for the synchronous method. Retain and expand the Task 1 bulk tests so ownership and idempotency remain protected.

Run:

```bash
rg -n "\.acknowledge\(|ACK_SQL" notification-gateway/src
```

Expected: no synchronous single-ACK persistence remains.

- [ ] **Step 5: Add exact application and Compose configuration**

Under `notification-gateway.delivery` in `application.yml`, add:

```yaml
ack:
  batch-size: ${NOTIFICATION_GATEWAY_DELIVERY_ACK_BATCH_SIZE:500}
  flush-interval-ms: ${NOTIFICATION_GATEWAY_DELIVERY_ACK_FLUSH_INTERVAL_MS:20}
  queue-capacity: ${NOTIFICATION_GATEWAY_DELIVERY_ACK_QUEUE_CAPACITY:10000}
  retry-delay-ms: ${NOTIFICATION_GATEWAY_DELIVERY_ACK_RETRY_DELAY_MS:100}
  shutdown-timeout-ms: ${NOTIFICATION_GATEWAY_DELIVERY_ACK_SHUTDOWN_TIMEOUT_MS:5000}
```

Add the same five names to the `notification-gateway` service environment in `infra/docker-compose.yml`, each with the identical default. Do not change existing worker delay, delivery batch size, dispatch concurrency, resources, or `JAVA_TOOL_OPTIONS`.

- [ ] **Step 6: Run focused service, batcher, and repository tests**

Run:

```bash
cd notification-gateway
./mvnw -Dtest=NotificationGrpcServiceTest,AcknowledgementBatcherTest,NotificationDeliveryRepositoryTest,NotificationDeliveryDeduplicationIntegrationTest test
```

Expected: the complete ACK path is green and no direct synchronous repository call remains.

- [ ] **Step 7: Validate effective Compose configuration**

Run:

```bash
docker compose -f infra/docker-compose.yml config | rg -n "NOTIFICATION_GATEWAY_DELIVERY_ACK_(BATCH_SIZE|FLUSH_INTERVAL_MS|QUEUE_CAPACITY|RETRY_DELAY_MS|SHUTDOWN_TIMEOUT_MS)"
```

Expected values: `500`, `20`, `10000`, `100`, and `5000`, with no CPU/memory delta.

- [ ] **Step 8: Review Task 3 without staging or committing**

Run:

```bash
git diff --check
git diff -- notification-gateway/src/main/java/br/kauan/notificationgateway/grpc notification-gateway/src/main/resources/application.yml infra/docker-compose.yml
```

Expected: public protobuf files and generated stubs are unchanged.

---

### Task 4: Complete automated and functional verification

**Files:**
- Verify all notification-gateway and configuration files changed in Tasks 1–3.
- Do not change production behavior unless a failing test exposes a defect covered by the approved spec.

**Interfaces:**
- Verifies the complete module, Compose rendering, load-test preparation, and functional outcomes before the B diagnostic.

- [ ] **Step 1: Run the complete notification-gateway suite**

Run:

```bash
cd notification-gateway
./mvnw test
```

Expected: all unit and PostgreSQL Testcontainers tests pass with zero failures and no leaked batch-writer thread after the Spring context closes.

- [ ] **Step 2: Run adjacent load-test and Compose checks**

Run from the repository root:

```bash
for test_script in load-test/tests/*-test.sh; do
    bash "$test_script"
done

GOPATH=/tmp/go GOCACHE=/tmp/go-build-cache go -C load-test/go-loadtool test ./...
docker compose -f infra/docker-compose.yml config >/dev/null
git diff --check
```

Expected: every command exits zero; load-tool behavior and Compose resources remain unchanged.

- [ ] **Step 3: Recreate and functionally qualify the B stack**

Run:

```bash
cd load-test
./prepare-performance-environment.sh
```

Expected: cached build succeeds, readiness passes, one of at most three `mixed-outcomes-smoke` attempts qualifies exactly 1,250 originals with correct ACSC and RJCT/AM04 outcomes and zero replay violations, Kafka becomes quiescent, and the stack remains running.

If preparation exits nonzero, stop. Preserve the smoke bundle and diagnose the functional or operational failure before any performance run.

- [ ] **Step 4: Confirm diagnostics and ACK batching are active**

Inspect the qualified smoke bundle and running container:

```bash
docker inspect notification-gateway --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | rg 'NOTIFICATION_GATEWAY_DELIVERY_ACK_'

rg --files load-test/results/environment-setup-* \
  | rg 'diagnostics/(jfr/notification-gateway\.jfr|postgres-statements\.csv|postgres-activity\.csv|postgres-io\.csv)$' \
  | tail -4
```

Expected: all five B parameters are active and the latest qualified smoke contains gateway JFR plus PostgreSQL diagnostics.

- [ ] **Step 5: Review the verified implementation without staging or committing**

Run:

```bash
git status --short
git diff --stat
git diff --check
```

Expected: only the spec, plan, ACK batching implementation/tests/configuration, and no generated run artifacts or unrelated files appear.

---

### Task 5: Run and decide the ACK-batching A/B

**Files:**
- Read: `load-test/results/preparation-workflow-verification/20260816_004331/`
- Create at runtime only: ignored bundle under `load-test/results/notification-ack-batch-diagnostic/`
- Modify after measurement: `docs/board/Atividades/agora/estabilizar-teste-carga-budget-cpu.md`

**Interfaces:**
- Consumes: the prepared B stack from Task 4.
- Produces: one complete `mixed-outcomes-2k-diagnostic` B bundle.
- Produces: a documented keep/discard decision based on matched evidence, not on reaching the final SLA.

- [ ] **Step 1: Execute exactly one short B diagnostic without preparing again**

Run:

```bash
cd load-test
./run-load-test.sh --profile mixed-outcomes-2k-diagnostic notification-ack-batch-diagnostic
```

Expected: exit `0` or completed-invalid exit `1`, never operational exit `2`. The run plans 15,000 warmup plus 120,000 active original payments, uses 5% PACS.008 and PACS.002 replay, and writes the complete diagnostic bundle. Do not run the 15-minute profile.

- [ ] **Step 2: Resolve the one B bundle and confirm technical completeness**

Run:

```bash
B_RUN_DIR="$(find load-test/results/notification-ack-batch-diagnostic -mindepth 1 -maxdepth 1 -type d | sort | tail -1)"
test -n "$B_RUN_DIR"
test -f "$B_RUN_DIR/sla-report.json"
test -f "$B_RUN_DIR/diagnostics/postgres-statements.csv"
test -f "$B_RUN_DIR/diagnostics/postgres-activity.csv"
test -f "$B_RUN_DIR/diagnostics/postgres-io.csv"
test -f "$B_RUN_DIR/diagnostics/container-stats.csv"
test -f "$B_RUN_DIR/diagnostics/jfr/notification-gateway.jfr"
jq -e '.profile.name == "mixed-outcomes-2k-diagnostic" and .window.loadtool_finished_at != null' \
  "$B_RUN_DIR/run-window.json"
```

Expected: every check passes even when `.valid == false`.

- [ ] **Step 3: Extract comparable report outcomes**

For both A and B, record:

```bash
jq '{
  valid,
  generation,
  scenarios: [.scenarios[] | {
    name,
    payments: .traffic.payments,
    pacs002: .traffic.pacs002,
    outcome,
    violations
  }],
  replays,
  performance
}' "$RUN_DIR/sla-report.json"
```

Use:

```text
A = load-test/results/preparation-workflow-verification/20260816_004331
B = $B_RUN_DIR
```

Do not compare totals without distinguishing warmup from the 120,000 active originals recorded under `generation.started`.

- [ ] **Step 4: Compare PostgreSQL ACK work and transaction pressure**

From each `postgres-statements.csv`, select the query containing both:

```text
UPDATE notification_delivery
delivery_status = 'ACKED'
```

Do not require `SET` to immediately follow the table name: A uses
`UPDATE notification_delivery SET`, while B intentionally uses
`UPDATE notification_delivery AS delivery SET`.

Record `calls`, `rows`, `total_exec_time_ms`, `mean_exec_time_ms`, `max_exec_time_ms`, `wal_records`, and `wal_bytes`. B must show the stable `FROM unnest` form. Compute rows per call for A and B.

From `postgres-io.csv`, record before/after deltas for `xact_commit`, `xact_rollback`, WAL bytes/records where available, deadlocks, temporary files/bytes, and block reads/writes. From `postgres-activity.csv`, count `WALWrite` and `WalSync` samples attributed to the ACK query and retain the counts for other leading queries so a bottleneck shift is visible.

- [ ] **Step 5: Compare CPU, memory, throughput, and backpressure**

Use the authoritative active interval in each `run-window.json` to filter `container-stats.csv`. Record active average and maximum CPU plus average/maximum memory for PostgreSQL and notification-gateway.

Inspect the gateway JFR for queue-induced parking:

```bash
jfr print --events jdk.ThreadPark --stack-depth 64 \
  "$B_RUN_DIR/diagnostics/jfr/notification-gateway.jfr" \
  | rg -n 'AcknowledgementBatcher|ArrayBlockingQueue'
```

No match is the healthy B expectation. Any match whose stack is in `AcknowledgementBatcher.enqueue` must be recorded as observed backpressure; sustained or repeated queue-full blocking makes B a capacity failure even if outcomes later recover.

Compare original HTTP acceptance, PACS.002 throughput, payer notifications, drain completion, missing/contradictory outcomes, and replay violations. A decrease in database work does not justify a functional or throughput regression.

- [ ] **Step 6: Make the isolated keep/discard decision**

Keep B only when all are true:

```text
smoke functional contract            preserved
ACK statement calls/rows-per-call    materially improved
ACK transaction/WAL pressure         reduced
original workload admission          not reduced
notification/PACS.002 throughput     not reduced
missing/contradictory/replay outcomes not worse
ACK queue saturation                 absent
```

B does not need to reach the final 2,000 TPS SLA. If PostgreSQL remains at one core and another query becomes dominant, record that as the next hypothesis. Do not alter SPI, buckets, claim, dispatch, resources, or a second parameter in this implementation.

If B fails a keep criterion, leave the implementation diff intact for review and document the reason; do not silently revert files.

- [ ] **Step 7: Update the active stabilization task with measured evidence**

Append a concise `### Resultado do A/B de ACKs em batch` section to `docs/board/Atividades/agora/estabilizar-teste-carga-budget-cpu.md` containing:

- exact A and B bundle paths and code state;
- the five fixed B parameters;
- one table comparing ACK calls, rows/call, commits, ACK WAL/waits, PostgreSQL/gateway CPU and memory, generation, PACS.002/notification throughput, outcomes, and queue saturation;
- whether the change is kept or discarded and why;
- the newly observed leading bottleneck, if any;
- an explicit statement that no long run or final SLA approval occurred.

Do not rewrite prior experimental evidence.

- [ ] **Step 8: Run final verification and leave the tree reviewable**

Run:

```bash
cd notification-gateway
./mvnw test

cd ..
for test_script in load-test/tests/*-test.sh; do
    bash "$test_script"
done
GOPATH=/tmp/go GOCACHE=/tmp/go-build-cache go -C load-test/go-loadtool test ./...
docker compose -f infra/docker-compose.yml config >/dev/null
git diff --check
git status --short
```

Expected: all automated verification passes; the B bundle remains ignored under `load-test/results`; implementation, tests, configuration, spec, plan, and active-task evidence remain unstaged and uncommitted for user review.
