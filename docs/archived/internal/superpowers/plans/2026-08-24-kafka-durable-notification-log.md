# Kafka Durable Notification Log Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the PostgreSQL-backed Gateway delivery projection and reconciler with a reliable transactional outbox handoff to a seven-day Kafka notification log that the Gateway serves through opaque offset cursors and a disposable in-memory partition cache.

**Architecture:** The SPI writes a minimal outbox row in the financial transaction, then one bounded publisher transfers committed batches to Kafka and deletes a whole batch only after every broker acknowledgement. Kafka topic generation `psp-notifications-v1` is the delivery source of truth; the Gateway tails its eight fixed partitions into shared ring buffers and reads Kafka directly on historical cache misses. PSP progress remains client-owned through an HMAC cursor bound to recipient, topic generation, partition, and last examined offset.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Kafka, Kafka 7.6/KRaft, PostgreSQL 17, gRPC unary Pull, JUnit 5, Mockito, Testcontainers, Go load-tool, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-08-24-kafka-durable-notification-log-design.md`

## Global Constraints

- Keep gRPC unary Pull, a maximum of 15 notifications per response, one concurrent Pull per PSP, mTLS authentication, and at-least-once delivery.
- Use `psp-notifications-v1`, exactly eight partitions, key `recipient_ispb`, and seven-day time retention.
- The local MVP uses one broker and replication factor 1; do not claim broker/host/volume fault tolerance.
- Do not add CDC, RocksDB, Kafka Streams, a logical delivery index, periodic reconciliation, runtime outbox polling, or admission control at the HTTP ingress.
- Kafka duplicates are delivered physically with the same payload and `communication_id`; no Gateway deduplication is persisted.
- The SPI has one publisher. A full bounded queue applies blocking backpressure after the financial commit.
- The whole publisher batch is the deletion unit: every send acknowledged permits one batched delete; any failed or inconclusive send permits no delete and retries the whole batch.
- Preserve unrelated dirty-worktree files and the archived load-test result.
- Use TDD for each behavior change and do not remove a working implementation until its replacement tests are green.

---

### Task 1: Restore a Minimal Transactional Outbox Repository

**Files:**
- Replace: `spi/src/main/resources/db/migration/V16__Add_ordered_outbound_position.sql`
- Rename/Modify: `spi/src/main/java/br/kauan/spi/adapter/output/notification/OutboundNotificationRepository.java`
- Modify: `spi/src/main/java/br/kauan/spi/domain/services/notification/NotificationObligationService.java`
- Modify: `spi/src/test/java/br/kauan/spi/adapter/output/notification/OutboundNotificationSchemaIntegrationTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/adapter/output/notification/OutboundNotificationRepositoryIntegrationTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/adapter/output/notification/OutboundNotificationRepositoryTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/adapter/output/notification/OutboundNotificationBatchingMigrationIntegrationTest.java`

**Interfaces:**
- Produces: `NotificationOutboxRepository.insertAll(List<NotificationPublication>)`, `findOldest(int)`, and `deleteAll(List<String>)`.
- Produces: a minimal `notification_outbox` table containing `communication_id`, `recipient_ispb`, `payload`, and `created_at`.

- [ ] **Step 1: Write failing schema and repository tests**

Assert that `notification_outbox` exists, `outbound_notification` and the global counter do not exist, the table has only the four required columns, startup pages are ordered by `created_at, communication_id`, and a batched delete removes exactly the requested identities.

```java
assertThat(columns("notification_outbox"))
        .containsExactlyInAnyOrder("communication_id", "recipient_ispb", "payload", "created_at");
assertThat(regclass("outbound_notification_position_counter")).isNull();
assertThat(repository.findOldest(2)).extracting(NotificationPublication::communicationId)
        .containsExactly("v1:first", "v1:second");
repository.deleteAll(List.of("v1:first", "v1:second"));
```

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./mvnw -f spi/pom.xml -Dtest=OutboundNotificationSchemaIntegrationTest,OutboundNotificationRepositoryIntegrationTest,OutboundNotificationRepositoryTest test`

Expected: failures mention the current `outbound_notification` name, `outbound_position`, and missing recovery/delete methods.

- [ ] **Step 3: Implement the minimal table and repository**

Replace V16 with a migration that renames `outbound_notification` back to `notification_outbox` and its primary key constraint. Replace the repository SQL with one array-based insert, one bounded oldest-page select, and one strict array-based delete. Remove every counter and position parameter.

```sql
SELECT communication_id, recipient_ispb, payload
FROM notification_outbox
ORDER BY created_at, communication_id
LIMIT ?;

DELETE FROM notification_outbox
WHERE communication_id = ANY (?::text[]);
```

- [ ] **Step 4: Update the obligation service to use the renamed repository without changing payload grouping**

Keep the existing one-transaction combined acceptance/rejection obligation insert and the maximum 15 items per outbound notification.

- [ ] **Step 5: Run focused SPI tests**

Run: `./mvnw -f spi/pom.xml -Dtest=OutboundNotificationSchemaIntegrationTest,OutboundNotificationRepositoryIntegrationTest,OutboundNotificationRepositoryTest,OutboundNotificationBatchingMigrationIntegrationTest,NotificationObligationServiceTest,PaymentTransactionProcessorServiceTest test`

Expected: PASS.

- [ ] **Step 6: Commit the outbox repository slice**

```bash
git add spi/src/main spi/src/test
git commit -m "refactor(spi): restore minimal notification outbox"
```

### Task 2: Add the Single Reliable Outbox Publisher

**Files:**
- Replace: `spi/src/main/java/br/kauan/spi/adapter/output/notification/OutboundNotificationPublisher.java`
- Rename/Modify: `spi/src/main/java/br/kauan/spi/adapter/output/notification/OutboundNotificationBatchReady.java`
- Modify: `spi/src/main/java/br/kauan/spi/adapter/output/kafka/NotificationPublisher.java`
- Modify: `spi/src/main/java/br/kauan/spi/adapter/output/kafka/KafkaNotificationProducerConfig.java`
- Create: `spi/src/main/java/br/kauan/spi/adapter/output/notification/NotificationOutboxPipeline.java`
- Modify: `spi/src/main/java/br/kauan/spi/adapter/input/kafka/KafkaConsumerConfig.java`
- Modify: `spi/src/main/resources/application.yml`
- Replace tests: `spi/src/test/java/br/kauan/spi/adapter/output/notification/OutboundNotificationPublisherTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/adapter/output/notification/OutboundNotificationFastPathIntegrationTest.java`
- Create: `spi/src/test/java/br/kauan/spi/adapter/output/notification/NotificationOutboxPipelineTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/adapter/output/kafka/KafkaNotificationProducerConfigTest.java`

**Interfaces:**
- Consumes: `NotificationOutboxRepository` from Task 1.
- Produces: `NotificationOutboxPipeline.start()`, `enqueue(NotificationOutboxBatchReady)`, `stop()`, and `isHealthy()`.
- Produces: `NotificationPublisher.publishAll(List<NotificationPublication>): CompletableFuture<Void>`.

- [ ] **Step 1: Write publisher behavior tests**

Cover startup drain before listener start, after-commit enqueue without repository select, blocking queue admission, all-ACK delete, partial failure deleting nothing, identical retry, delete failure republishing the whole batch, and shutdown recovery through retained rows.

```java
when(kafka.publishAll(batch.notifications()))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("partial")))
        .thenReturn(CompletableFuture.completedFuture(null));

pipeline.enqueue(batch);

verify(repository, timeout(2000).times(1)).deleteAll(ids(batch));
verify(kafka, timeout(2000).times(2)).publishAll(batch.notifications());
```

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./mvnw -f spi/pom.xml -Dtest=OutboundNotificationPublisherTest,OutboundNotificationFastPathIntegrationTest,NotificationOutboxPipelineTest,KafkaNotificationProducerConfigTest test`

Expected: failures mention best-effort publication, missing queue lifecycle, and absent batched delete.

- [ ] **Step 3: Implement batch publication and producer guarantees**

Publish to `psp-notifications-v1`, retain `recipient_ispb` as key and `notification.communication-id` as header, enable producer idempotence, keep `acks=all`, and return one future completed only when every send completes successfully.

- [ ] **Step 4: Implement the bounded single-worker pipeline**

Use a configurable bounded `BlockingQueue<NotificationOutboxBatchReady>`. Startup drains `findOldest(recoveryBatchSize)` until empty, retrying a retained batch with bounded backoff. Runtime `enqueue` uses blocking `put`; the worker processes one immutable batch at a time. A batch is removed from the database only after `publishAll` succeeds. Unexpected worker termination marks the pipeline unhealthy and stops new listener progress.

- [ ] **Step 5: Gate Kafka input listeners on startup recovery**

Configure SPI listener containers with auto-startup disabled. Add one lifecycle coordinator that starts the outbox pipeline, waits for startup drain, then starts `KafkaListenerEndpointRegistry`. Shutdown stops listener admission before the publisher.

- [ ] **Step 6: Run focused and complete SPI suites**

Run: `./mvnw -f spi/pom.xml test`

Expected: PASS.

- [ ] **Step 7: Commit the reliable publisher slice**

```bash
git add spi
git commit -m "feat(spi): hand notifications reliably to Kafka"
```

### Task 3: Version and Retain the Kafka Notification Log

**Files:**
- Modify: `infra/docker-compose.yml`
- Modify: `spi/src/test/java/br/kauan/spi/adapter/input/kafka/KafkaInitTopicsTest.java`
- Modify: `load-test/tests/performance-stack-readiness-test.sh`

**Interfaces:**
- Produces: topic `psp-notifications-v1` with exactly eight partitions, `cleanup.policy=delete`, and `retention.ms=604800000`.

- [ ] **Step 1: Write failing topic-contract tests**

Assert the versioned topic name, fixed partition validation, seven-day retention, and absence of silent partition alteration for this topic.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./mvnw -f spi/pom.xml -Dtest=KafkaInitTopicsTest test && bash load-test/tests/performance-stack-readiness-test.sh`

Expected: failures reference the old `psp-notifications` topic and generic alter behavior.

- [ ] **Step 3: Implement topic initialization**

Keep the existing generic helper for ingress topics. Add a versioned-topic helper that creates `psp-notifications-v1` with eight partitions and the retention config, then fails when the existing partition count is not exactly eight.

- [ ] **Step 4: Verify Compose and focused tests**

Run: `docker compose -f infra/docker-compose.yml config >/dev/null`, `./mvnw -f spi/pom.xml -Dtest=KafkaInitTopicsTest test`, and `bash load-test/tests/performance-stack-readiness-test.sh`.

Expected: PASS.

- [ ] **Step 5: Commit the Kafka topic slice**

```bash
git add infra/docker-compose.yml spi/src/test load-test/tests/performance-stack-readiness-test.sh
git commit -m "feat(kafka): add durable notification log generation"
```

### Task 4: Introduce Kafka Offset Cursors and Partition Reads

**Files:**
- Replace: `notification-gateway/src/main/java/br/kauan/notificationgateway/grpc/DeliveryCursorCodec.java`
- Create: `notification-gateway/src/main/java/br/kauan/notificationgateway/grpc/DeliveryCursor.java`
- Create: `notification-gateway/src/main/java/br/kauan/notificationgateway/kafka/NotificationPartitionResolver.java`
- Create: `notification-gateway/src/main/java/br/kauan/notificationgateway/kafka/KafkaNotificationRecord.java`
- Create: `notification-gateway/src/main/java/br/kauan/notificationgateway/kafka/KafkaNotificationRecordMapper.java`
- Create: `notification-gateway/src/main/java/br/kauan/notificationgateway/kafka/KafkaNotificationPage.java`
- Create: `notification-gateway/src/main/java/br/kauan/notificationgateway/kafka/HistoricalKafkaReader.java`
- Modify: `notification-gateway/src/main/java/br/kauan/notificationgateway/kafka/KafkaConsumerConfig.java`
- Replace tests: `notification-gateway/src/test/java/br/kauan/notificationgateway/grpc/DeliveryCursorCodecTest.java`
- Create: `notification-gateway/src/test/java/br/kauan/notificationgateway/kafka/NotificationPartitionResolverTest.java`
- Create: `notification-gateway/src/test/java/br/kauan/notificationgateway/kafka/HistoricalKafkaReaderTest.java`

**Interfaces:**
- Produces: `DeliveryCursor(recipientIspb, topicGeneration, partition, lastExaminedOffset)`.
- Produces: `HistoricalKafkaReader.read(recipientIspb, partition, afterOffset, notificationLimit, scanLimit)`.

- [ ] **Step 1: Write cursor, partition, and historical-read tests**

Cover stable HMAC round-trip, cross-PSP/topic/partition rejection, empty cursor, Kafka default key-to-partition mapping, filtering shared partitions, scan limit, stop at the fifteenth matching record, last-examined advancement, expired offset, and future offset.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./mvnw -f notification-gateway/pom.xml -Dtest=DeliveryCursorCodecTest,NotificationPartitionResolverTest,HistoricalKafkaReaderTest test`

Expected: failures mention the position-only cursor and missing Kafka log reader.

- [ ] **Step 3: Implement the opaque offset cursor**

Sign a payload containing recipient, literal topic generation `psp-notifications-v1`, partition, and last examined offset. Empty input has no issued position. Validate HMAC, authenticated recipient, expected generation, and partition derived from Kafka's default Murmur2 mapping over `recipient_ispb` and eight partitions.

- [ ] **Step 4: Implement one synchronized historical consumer per partition**

Create manually assigned consumers that seek to `afterOffset + 1`, compare the requested offset with broker beginning/end offsets, poll records in order, filter by recipient, stop at 15 matching notifications or the configured scan budget, and return the last examined offset even for an empty result. Map cursor-before-beginning to a dedicated expired-cursor exception.

- [ ] **Step 5: Run focused tests**

Run: `./mvnw -f notification-gateway/pom.xml -Dtest=DeliveryCursorCodecTest,NotificationPartitionResolverTest,HistoricalKafkaReaderTest test`

Expected: PASS.

- [ ] **Step 6: Commit the cursor/read foundation**

```bash
git add notification-gateway
git commit -m "feat(notification-gateway): read notification log by offset"
```

### Task 5: Replace the Gateway Delivery Projection with Partition Ring Buffers

**Files:**
- Replace: `notification-gateway/src/main/java/br/kauan/notificationgateway/delivery/RecentNotificationBuffer.java`
- Replace: `notification-gateway/src/main/java/br/kauan/notificationgateway/delivery/NotificationDeliveryReader.java`
- Replace: `notification-gateway/src/main/java/br/kauan/notificationgateway/kafka/NotificationKafkaConsumer.java`
- Modify: `notification-gateway/src/main/java/br/kauan/notificationgateway/grpc/NotificationGrpcService.java`
- Modify: `notification-gateway/src/main/java/br/kauan/notificationgateway/grpc/PullRequestCoordinator.java`
- Replace tests: `notification-gateway/src/test/java/br/kauan/notificationgateway/delivery/RecentNotificationBufferTest.java`
- Replace tests: `notification-gateway/src/test/java/br/kauan/notificationgateway/delivery/NotificationDeliveryReaderTest.java`
- Replace tests: `notification-gateway/src/test/java/br/kauan/notificationgateway/kafka/NotificationKafkaConsumerTest.java`
- Replace tests: `notification-gateway/src/test/java/br/kauan/notificationgateway/grpc/NotificationGrpcServiceTest.java`

**Interfaces:**
- Consumes: cursor and historical reader from Task 4.
- Produces: partition-oriented `RecentNotificationBuffer.lookup(partition, recipient, afterOffset, notificationLimit, scanLimit)`.
- Produces: `NotificationDeliveryReader.read(recipient, cursor, notificationLimit)` returning payloads plus last examined offset and tail state.

- [ ] **Step 1: Write failing shared-buffer and Pull tests**

Prove one partition batch feeds one buffer, multiple PSPs filter the same records without Kafka reread, last examined offset advances across unrelated records, max 15 stops without skipping the sixteenth match, eviction causes historical fallback, known tail long-polls, empty advancement returns a new cursor, one concurrent Pull per PSP remains enforced, and duplicates at separate offsets are both returned.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./mvnw -f notification-gateway/pom.xml -Dtest=RecentNotificationBufferTest,NotificationDeliveryReaderTest,NotificationKafkaConsumerTest,NotificationGrpcServiceTest,PullRequestCoordinatorTest test`

Expected: failures reference recipient-local delivery positions and the PostgreSQL repository fallback.

- [ ] **Step 3: Implement the partition ring buffer**

Store bounded Kafka records per partition and track its covered offset interval. Return `DATA`, `KNOWN_TAIL`, or `MISS` with the last examined offset. Reset coverage on assignment/discontinuity. Signal pending long-polls only for recipients present in newly tailed records.

- [ ] **Step 4: Implement the combined reader and gRPC flow**

Derive the PSP partition, decode/validate the cursor, try the ring, use historical Kafka on MISS, long-poll only when at a known tail with no notifications, and issue a cursor for the last examined offset even when no notification matched. Convert expired cursors to `FAILED_PRECONDITION` with description `notification cursor expired`; keep malformed cursors as `INVALID_ARGUMENT`.

- [ ] **Step 5: Run focused tests**

Run: `./mvnw -f notification-gateway/pom.xml -Dtest=RecentNotificationBufferTest,NotificationDeliveryReaderTest,NotificationKafkaConsumerTest,NotificationGrpcServiceTest,PullRequestCoordinatorTest test`

Expected: PASS.

- [ ] **Step 6: Commit the Gateway Pull path**

```bash
git add notification-gateway
git commit -m "feat(notification-gateway): serve Pull from Kafka log"
```

### Task 6: Remove Gateway PostgreSQL and Reconciliation

**Files:**
- Delete: `notification-gateway/src/main/java/br/kauan/notificationgateway/delivery/DeliveryIndexRepository.java`
- Delete: `notification-gateway/src/main/java/br/kauan/notificationgateway/delivery/IncomingNotification.java`
- Delete: `notification-gateway/src/main/java/br/kauan/notificationgateway/delivery/NotificationIndexingService.java`
- Delete: `notification-gateway/src/main/java/br/kauan/notificationgateway/delivery/NotificationReconciler.java`
- Delete: `notification-gateway/src/main/java/br/kauan/notificationgateway/delivery/NotificationReconciliationRepository.java`
- Delete: `notification-gateway/src/main/java/br/kauan/notificationgateway/delivery/ReconciliationCandidate.java`
- Delete: `notification-gateway/src/main/resources/db/migration/V1__Create_notification_delivery.sql`
- Delete: `notification-gateway/src/main/resources/db/migration/V2__Optimize_notification_delivery_claim.sql`
- Delete: `notification-gateway/src/main/resources/db/migration/V3__Replace_push_delivery_lifecycle_with_pull_position.sql`
- Delete: `notification-gateway/src/main/resources/db/migration/V4__Replace_delivery_payload_with_minimal_index.sql`
- Delete: `notification-gateway/src/main/resources/db/migration/V5__Add_reconciliation_checkpoint.sql`
- Delete corresponding delivery/reconciliation integration tests.
- Modify: `notification-gateway/pom.xml`
- Modify: `notification-gateway/src/main/resources/application.yml`
- Modify: `infra/docker-compose.yml`

**Interfaces:**
- Consumes: Kafka-only Gateway from Tasks 4-5.
- Produces: a Notification Gateway with no JDBC datasource, Flyway, PostgreSQL driver, or PostgreSQL dependency.

- [ ] **Step 1: Add a context test proving the Gateway starts without a datasource**

Use `ApplicationContextRunner` or a focused Spring test to assert no `DataSource`, `JdbcTemplate`, or Flyway bean is required while Kafka reader configuration remains present.

- [ ] **Step 2: Run the context test and verify failure**

Run: `./mvnw -f notification-gateway/pom.xml -Dtest=NotificationGatewayApplicationTests test`

Expected: failure or missing test while JDBC/Flyway remain configured.

- [ ] **Step 3: Remove the obsolete implementation and dependencies**

Delete the PostgreSQL projection/reconciler source, migrations, and tests. Remove JDBC, Flyway, PostgreSQL, and PostgreSQL Testcontainers dependencies. Remove datasource and reconciliation settings plus Compose database dependency/environment variables.

- [ ] **Step 4: Run the complete Gateway suite**

Run: `./mvnw -f notification-gateway/pom.xml test`

Expected: PASS without PostgreSQL or Docker-dependent database tests.

- [ ] **Step 5: Commit the cleanup slice**

```bash
git add notification-gateway infra/docker-compose.yml
git commit -m "refactor(notification-gateway): remove PostgreSQL delivery projection"
```

### Task 7: Update Documentation and Load-Test Expectations

**Files:**
- Modify: `docs/KAFKA_MESSAGE_FLOW.md`
- Modify: `docs/IDEMPOTENCY_REPLAY_POLICY.md`
- Replace/retire: `docs/architecture/hybrid-notification-delivery.md`
- Modify: `docs/board/Atividades/agora/estabilizar-teste-carga-budget-cpu.md`
- Modify if required: `load-test/go-loadtool/internal/sim/simulator_test.go`
- Modify if required: `load-test/go-loadtool/internal/report/notification_pull_test.go`

**Interfaces:**
- Documents: ownership, seven-day recovery window, cursor expiry, offset coupling, eight fixed partitions, one-broker MVP limitation, outbox duplicates, shared-partition reads, and future HA/admission-control scope.

- [ ] **Step 1: Update architecture and policy documents**

Remove claims that PostgreSQL and `delivery_index` are the delivery source, record Kafka ownership after broker ACK, and preserve the distinction between notification at-least-once and payment replay no-op.

- [ ] **Step 2: Update the active stabilization task**

Record the architectural decision, rationale, rejected alternatives, accepted disadvantages, implementation slices, and the requirement to run the 15-minute `mixed-outcomes-2k-15m` A/B.

- [ ] **Step 3: Verify load-tool cursor opacity**

Run: `cd load-test/go-loadtool && go test ./...`

Expected: PASS without parsing cursor internals. Add only tests needed to prove empty responses may advance `next_cursor` and physical duplicate notifications remain at-least-once.

- [ ] **Step 4: Commit documentation and compatibility updates**

```bash
git add docs load-test/go-loadtool
git commit -m "docs: record Kafka notification log migration"
```

### Task 8: Automated Verification, Functional Smoke, and 15-Minute Benchmark

**Files:**
- Generated only: `load-test/results/<tag>/<timestamp>/...`

**Interfaces:**
- Verifies the complete architecture and measures the result against the current baseline without changing CPU/memory budgets.

- [ ] **Step 1: Run static and unit/integration checks**

Run:

```bash
./mvnw -f spi/pom.xml test
./mvnw -f notification-gateway/pom.xml test
(cd load-test/go-loadtool && go test ./...)
bash -n load-test/run-load-test.sh load-test/prepare-performance-environment.sh load-test/scripts/*.sh
for test in load-test/tests/*-test.sh; do bash "$test"; done
docker compose -f infra/docker-compose.yml config >/dev/null
git diff --check
```

Expected: every command exits zero.

- [ ] **Step 2: Recreate and qualify the environment**

Run: `cd load-test && ./prepare-performance-environment.sh`

Expected: stack is recreated without preserving PostgreSQL/Kafka data, readiness passes, and no build cache is deleted.

- [ ] **Step 3: Run the short functional smoke**

Run: `cd load-test && ./run-load-test.sh --profile mixed-outcomes-smoke kafka-durable-log-smoke`

Expected: valid SLA report; HTTP 2xx; ACSC; RJCT/AM04; PACS.008 and PACS.002 replay invariants; no missing outcomes; no cursor errors.

- [ ] **Step 4: Inspect smoke persistence and logs**

Confirm the outbox is empty after broker ACK, the Gateway has no delivery tables/database access, the versioned topic has eight partitions and configured retention, and no publisher/Gateway errors occurred.

- [ ] **Step 5: Run the required 15-minute benchmark**

Run: `cd load-test && ./run-load-test.sh --profile mixed-outcomes-2k-15m kafka-durable-log-2k-15m`

Expected: the run completes and produces a full diagnostic bundle. A performance violation is reported as a result, not hidden or relabeled.

- [ ] **Step 6: Compare against the latest valid baseline**

Report original offered TPS and rolling minimum, completed outcomes, latency percentiles/max, PostgreSQL CPU/WAL/SQL, Kafka CPU/disk/lag, publisher queue blocking, Pull batch distribution, cache hit/miss, scanned records, duplicates, and correctness violations.

- [ ] **Step 7: Final verification and commit**

Run `git diff --check`, inspect `git status --short`, commit only source/docs/test changes, and leave generated benchmark artifacts untracked or archived according to the existing repository policy.
