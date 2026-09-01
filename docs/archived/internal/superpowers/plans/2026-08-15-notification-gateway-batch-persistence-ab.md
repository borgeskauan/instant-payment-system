# Notification-Gateway Batch Persistence A/B Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist each Kafka notification poll in one PostgreSQL transaction and prove, through a controlled A/B, that this reduces transaction/WAL pressure while preserving durable at-least-once delivery.

**Architecture:** Keep the existing Kafka poll size, listener concurrency and batch offset acknowledgment, but switch the gateway listener from record mode to batch mode. Decode the complete poll before persistence, then execute the existing idempotent insert as a JDBC batch inside one `TransactionTemplate` transaction. Add transaction counters to the existing PostgreSQL diagnostic artifact so the before/after comparison measures commits instead of inferring them.

**Tech Stack:** Java 21, Spring Boot 3.5.5, Spring Kafka 3.3, Spring JDBC, PostgreSQL 17, Testcontainers, Bash diagnostics, Docker Compose.

## Global Constraints

- Keep PostgreSQL limited to `cpus: 1.00` and preserve every other container resource limit.
- Keep `max.poll.records=500`, gateway listener concurrency `2`, `AckMode.BATCH`, the current topic/group and Kafka retry behavior.
- A Kafka offset may be committed only after the corresponding PostgreSQL transaction succeeds.
- Preserve `ON CONFLICT (communication_id) DO NOTHING`; identical Kafka replay remains idempotent.
- Do not change claim, dispatch, ACK persistence, retry, gRPC, SPI, PACS.008, funds buckets, profiles or load-tool behavior in this intervention.
- Add no public flag, profile field or runtime artifact. Extend the existing long-form `postgres-io.csv` with transaction counters.
- Use the same `mixed-outcomes-2k-diagnostic`, diagnostic flags and environment for A and B.
- For each variant, start from clean Compose volumes, wait for consumer readiness,
  run one functional smoke to warm JVM/TLS/database paths, and then run the
  diagnostic without restarting the stack. Preserve images and build cache.
- Keep all changes uncommitted and unstaged for review through `git diff`.

---

### Task 1: Measure PostgreSQL transaction counts and record warmed A

**Files:**
- Modify: `load-test/scripts/postgres-runtime.sh:114-139`
- Modify: `load-test/tests/postgres-diagnostics-test.sh:12-76`
- Runtime output: `load-test/results/notification-per-record-final-warmup/20260815_224336/`
- Runtime output: `load-test/results/notification-per-record-final-diagnostic/20260815_224502/`

**Interfaces:**
- Extends `diagnostics/postgres-io.csv` without changing its header.
- Produces rows with `source=pg_stat_database`, `scope=postgres`, and metrics `xact_commit` and `xact_rollback` in both `before` and `after` snapshots.

- [x] **Step 1: Extend the shell fixture and assertions first**

Make the fake `FROM pg_stat_io` response include:

```text
pg_stat_database,postgres,xact_commit,100
pg_stat_database,postgres,xact_rollback,2
```

Change the expected number of `before` and `after` rows from two to four. Assert that both transaction metric names occur in the captured SQL and in each snapshot phase.

- [x] **Step 2: Run the focused test and verify the red state**

Run:

```bash
bash load-test/tests/postgres-diagnostics-test.sh
```

Expected: FAIL because `snapshot_io` does not select `xact_commit` or `xact_rollback`.

- [x] **Step 3: Add the two existing PostgreSQL counters**

Extend the `pg_stat_database` lateral values in `snapshot_io` with:

```sql
('xact_commit', xact_commit::numeric),
('xact_rollback', xact_rollback::numeric),
```

Do not reset `pg_stat_database`: the experiment uses `after - before`, so the counters remain safe for a shared long-lived container.

- [x] **Step 4: Verify the diagnostic change**

Run:

```bash
bash load-test/tests/postgres-diagnostics-test.sh
bash load-test/tests/diagnostics-layout-test.sh
bash -n load-test/scripts/postgres-runtime.sh load-test/tests/postgres-diagnostics-test.sh
git diff --check
```

Expected: all commands exit zero and `postgres-io.csv` keeps its existing header.

- [x] **Step 5: Build and warm the unchanged gateway**

Run from the repository root:

```bash
LOCAL_UID=$(id -u) LOCAL_GID=$(id -g) \
docker compose -f infra/docker-compose.yml up -d --build

cd load-test
./run-load-test.sh \
  --profile mixed-outcomes-smoke \
  notification-per-record-warmup
```

The warmup must complete before A. This keeps JVM/TLS startup cost from
dominating the front door and mirrors the smoke that precedes B.

- [x] **Step 6: Record the warmed per-record A**

Run without restarting the stack:

```bash
./run-load-test.sh \
  --profile mixed-outcomes-2k-diagnostic \
  --jfr \
  --spi-trace \
  --postgres-statements \
  notification-per-record-diagnostic
```

Preserve the result even when `sla-report.json` is invalid. Record its exact path before changing gateway code. Confirm that `postgres-io.csv` contains both phases for both transaction counters.

---

### Task 2: Persist one Kafka poll in one database transaction

**Files:**
- Modify: `notification-gateway/src/main/java/br/kauan/notificationgateway/kafka/KafkaConsumerConfig.java:55-64`
- Modify: `notification-gateway/src/main/java/br/kauan/notificationgateway/kafka/NotificationKafkaConsumer.java:31-56`
- Modify: `notification-gateway/src/main/java/br/kauan/notificationgateway/delivery/NotificationDeliveryRepository.java:120-132`
- Modify: `notification-gateway/src/test/java/br/kauan/notificationgateway/kafka/KafkaConsumerConfigTest.java`
- Modify: `notification-gateway/src/test/java/br/kauan/notificationgateway/kafka/NotificationKafkaConsumerTest.java`
- Modify: `notification-gateway/src/test/java/br/kauan/notificationgateway/delivery/NotificationDeliveryDeduplicationIntegrationTest.java`

**Interfaces:**
- `NotificationKafkaConsumer.consume(List<ConsumerRecord<String, byte[]>> records)` consumes one complete poll in order.
- `NotificationDeliveryRepository.saveAllIfAbsent(List<IncomingNotification> notifications)` persists zero or more deliveries atomically.
- `notificationKafkaListenerContainerFactory.isBatchListener()` returns `true`.

- [x] **Step 1: Characterize the batch-listener contract**

Add this assertion to `KafkaConsumerConfigTest`:

```java
@Test
void deliversEachKafkaPollToTheListenerAsOneBatch() {
    contextRunner.run(context -> {
        var factory = context.getBean(
                "notificationKafkaListenerContainerFactory",
                ConcurrentKafkaListenerContainerFactory.class
        );

        assertThat(factory.isBatchListener()).isTrue();
    });
}
```

Update `NotificationKafkaConsumerTest` to pass two records in one `List`, capture the single list supplied to `saveAllIfAbsent`, and assert order, communication ID, ISPB, event type, payment ID, status, schema version and payload for both notifications. Add a malformed-second-record test asserting that the repository receives no call when decoding the poll fails.

- [x] **Step 2: Characterize idempotency and atomic rollback in PostgreSQL**

Update `NotificationDeliveryDeduplicationIntegrationTest` to call:

```java
consumer.consume(List.of(notificationRecord(10L), notificationRecord(11L)));
```

and keep the assertion that one repeated `communication_id` creates one row. Add a distinct-notification case that persists two rows from one poll.

Add a repository-level case with one valid notification followed by one whose required `schemaVersion` is `null`:

```java
assertThatThrownBy(() -> repository.saveAllIfAbsent(List.of(valid, invalid)))
        .isInstanceOf(DataAccessException.class);

assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM notification_delivery WHERE communication_id = ?",
        Integer.class,
        valid.communicationId()
)).isZero();
```

Truncate `notification_delivery` before each integration test so tests remain isolated despite `Propagation.NOT_SUPPORTED`.

- [x] **Step 3: Run the focused tests and verify the red state**

Run:

```bash
cd notification-gateway
./mvnw -q \
  -Dtest=KafkaConsumerConfigTest,NotificationKafkaConsumerTest,NotificationDeliveryDeduplicationIntegrationTest \
  test
```

Expected: FAIL because the factory is not a batch listener and the batch consumer/repository methods do not exist.

- [x] **Step 4: Enable batch delivery and decode before persistence**

In `KafkaConsumerConfig`, retain the existing consumer factory, concurrency and `AckMode.BATCH`, then add:

```java
factory.setBatchListener(true);
```

Change the listener signature to `List<ConsumerRecord<String, byte[]>>`. Decode every record into an `ArrayList<IncomingNotification>` before invoking the repository once. Return immediately for an empty list. Do not catch decoding or database exceptions: failure must prevent listener completion and therefore prevent the poll offset from being committed.

- [x] **Step 5: Execute the existing insert as one JDBC batch transaction**

Replace `saveIfAbsent` with `saveAllIfAbsent`. Construct one `MapSqlParameterSource` per notification using a single `Instant now` for the complete poll, then execute the current `INSERT_SQL` through the existing `TransactionTemplate`:

```java
public void saveAllIfAbsent(List<IncomingNotification> notifications) {
    if (notifications.isEmpty()) {
        return;
    }

    Instant now = clock.instant();
    SqlParameterSource[] batch = new SqlParameterSource[notifications.size()];
    for (int index = 0; index < notifications.size(); index++) {
        IncomingNotification notification = notifications.get(index);
        batch[index] = new MapSqlParameterSource()
                .addValue("communicationId", notification.communicationId())
                .addValue("recipientIspb", notification.recipientIspb())
                .addValue("eventType", notification.eventType())
                .addValue("paymentId", notification.paymentId())
                .addValue("status", notification.status())
                .addValue("schemaVersion", notification.schemaVersion())
                .addValue("payload", notification.payload())
                .addValue("deliveryStatus", DeliveryStatus.PENDING.name())
                .addValue("nextAttemptAt", timestamp(now));
    }

    transactionTemplate.executeWithoutResult(
            ignored -> jdbcTemplate.batchUpdate(INSERT_SQL, batch)
    );
}
```

Import `SqlParameterSource`. Do not keep an independently callable per-record persistence path.

- [x] **Step 6: Run focused and complete gateway tests**

Run:

```bash
./mvnw -q \
  -Dtest=KafkaConsumerConfigTest,NotificationKafkaConsumerTest,NotificationDeliveryDeduplicationIntegrationTest \
  test
./mvnw test
```

Expected: PASS, including atomic rollback, duplicate idempotency and existing delivery/ACK behavior.

---

### Task 3: Run B, compare it with A and record the decision

**Files:**
- Modify after comparison: `docs/board/Atividades/agora/estabilizar-teste-carga-budget-cpu.md`
- Runtime output: `load-test/results/notification-ingress-batch-final-smoke/20260815_225026/`
- Runtime output: `load-test/results/notification-ingress-batch-final-diagnostic/20260815_225155/`

**Interfaces:**
- Consumes the A result from Task 1 and batch gateway from Task 2.
- Produces an evidence-backed keep, discard or inconclusive decision without authorizing ACK batching or SPI query changes.

- [x] **Step 1: Run repository-wide automated verification**

Run:

```bash
cd notification-gateway && ./mvnw test
cd ../spi && ./mvnw test
cd ../kafka-producer && ./mvnw test
cd ../load-test/go-loadtool && GOPATH=/tmp/go GOCACHE=/tmp/go-build-cache go test ./...
cd ..
for test_script in tests/*.sh; do bash "$test_script"; done
cd ..
bash -n load-test/run-load-test.sh load-test/scripts/*.sh load-test/tests/*.sh
git diff --check
```

Expected: every command exits zero.

- [x] **Step 2: Rebuild and run the functional smoke**

Run from the repository root:

```bash
LOCAL_UID=$(id -u) LOCAL_GID=$(id -g) \
docker compose -f infra/docker-compose.yml up -d --build

cd load-test
./run-load-test.sh \
  --profile mixed-outcomes-smoke \
  --postgres-statements \
  notification-ingress-batch-smoke
```

Expected: the run preserves happy-path `ACSC`, insufficient-funds `RJCT/AM04`, replay counts, settlements and absence of contradictory outcomes. Inspect gateway logs for batch-listener or database errors.

- [x] **Step 3: Run the short B diagnostic**

Run:

```bash
./run-load-test.sh \
  --profile mixed-outcomes-2k-diagnostic \
  --jfr \
  --spi-trace \
  --postgres-statements \
  notification-ingress-batch-diagnostic
```

Preserve evidence even if the run remains invalid.

- [x] **Step 4: Compare A and B under the same active window**

For each result, subtract `before` from `after` for `xact_commit` and `xact_rollback`. Compare:

- total transaction delta;
- calls, execution time, rows and WAL bytes for `INSERT INTO notification_delivery`;
- active `WALWrite` and `WalSync` observations, mapped by query ID;
- PostgreSQL CPU/memory and the final stable ingress interval;
- payment starts/writes, 2xx/status-zero, rolling throughput, latency and outcomes;
- Kafka lag after drain and any database/listener error.

Keep the change only if the smoke remains functionally valid, transaction/WAL pressure is measurably lower and admitted workload does not regress. Record an inconclusive result when changes remain within run-to-run noise; do not tune ACKs, concurrency, PostgreSQL or SPI in the same comparison.

- [x] **Step 5: Update the active task and perform final verification**

Record exact A/B paths, metrics and the keep/discard decision in the active task. If inserts cease to be a leading WAL source but ACK updates remain dominant, identify ACK batching as the next isolated intervention; do not implement it in this plan.

Stop the stack without deleting volumes or build cache:

```bash
docker compose -f infra/docker-compose.yml down
```

Repeat the complete automated verification, then run:

```bash
git diff --check
git status --short
git diff --cached --quiet
```

Expected: tests pass, intended changes remain unstaged, and the Git index is empty.
