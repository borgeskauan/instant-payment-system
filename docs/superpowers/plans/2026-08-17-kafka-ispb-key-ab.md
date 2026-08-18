# Kafka ISPB Key A/B Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give PACS.008 and PACS.002 records stable Kafka partition affinity by authenticated ISPB and execute one controlled short comparison against the unkeyed participant-balance run.

**Architecture:** `KafkaPaymentPublisher` will encode the already validated authenticated ISPB as the UTF-8 Kafka record key while preserving payloads and headers. Kafka partitions become durable per-topic execution lanes; the SPI consumer, transaction model, and participant balance implementation remain unchanged.

**Tech Stack:** Java 21, Reactor, Apache Kafka producer API, Maven Wrapper, JUnit 5, Docker Compose, Bash, Go load-tool, PostgreSQL diagnostics.

## Global Constraints

- Work only in `/tmp/instant-payment-system-reservation-balance-ab` on branch `reservation-balance-ab`; do not merge or modify `estabilizing-performance`.
- Use `load-test/results/reservation-balance-diagnostic/20260816_221950` as the immutable unkeyed single-balance control; do not rerun it.
- Change only Kafka record keys: PACS.008 key is authenticated payer ISPB and PACS.002 key is authenticated reporting PSP ISPB, encoded as UTF-8 bytes.
- Preserve topics, eight partitions, consumer groups, listener concurrency `3`, poll settings, acknowledgements, SPI SQL and transactions, resources, profiles, replays, diagnostics, and deadlines.
- Do not add application lanes, microbatching, payload fields, topics, or cross-topic coordination.
- Execute at most the preparer's qualifying functional smoke attempts and exactly one new `mixed-outcomes-2k-diagnostic` run. Do not execute the 15-minute profile.
- Treat a receiver-originated rejected PACS.002 as outside the affinity guarantee because its internal payload does not expose the payer whose balance is released; PostgreSQL continues to protect correctness.

---

### Task 1: Key every Kafka ingress record by authenticated ISPB

**Files:**
- Modify: `kafka-producer/src/test/java/br/kauan/kafkaproducer/kafka/KafkaPaymentPublisherTest.java`
- Modify: `kafka-producer/src/main/java/br/kauan/kafkaproducer/kafka/KafkaPaymentPublisher.java`

**Interfaces:**
- Consumes: `publishPaymentRequest(String authenticatedIspb, byte[] payload)` and `publishStatusReport(String authenticatedIspb, byte[] payload)`.
- Produces: `ProducerRecord<byte[], byte[]>` whose key is `authenticatedIspb.getBytes(StandardCharsets.UTF_8)`, with the existing topic, payload, and `authenticated-ispb` header unchanged.

- [x] **Step 1: Add failing key assertions**

Add `assertRecordKey` and call it from the existing single- and multi-record PACS.008/PACS.002 tests:

```java
private static void assertRecordKey(
        ProducerRecord<byte[], byte[]> record,
        String expectedIspb
) {
    assertArrayEquals(
            expectedIspb.getBytes(StandardCharsets.UTF_8),
            record.key()
    );
}
```

Publish two status requests authenticated as different ISPBs and assert their exact keys differ while their decoded payloads remain valid. Keep the existing send-failure assertion unchanged.

- [x] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd kafka-producer
./mvnw -Dtest=KafkaPaymentPublisherTest test
```

Expected: test failures because `ProducerRecord.key()` is currently null.

- [x] **Step 3: Add the minimal producer key**

Construct records with the existing three-argument overload:

```java
byte[] ispbKey = authenticatedIspb.getBytes(StandardCharsets.UTF_8);
ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(topic, ispbKey, payload);
```

Do not change headers, payload mapping, authorization, callback handling, or producer configuration.

- [x] **Step 4: Run focused and module tests and verify GREEN**

Run:

```bash
cd kafka-producer
./mvnw -Dtest=KafkaPaymentPublisherTest test
./mvnw test
```

Expected: all focused and kafka-producer tests pass with zero failures and errors.

- [x] **Step 5: Commit the keyed producer**

```bash
git add kafka-producer/src/main/java/br/kauan/kafkaproducer/kafka/KafkaPaymentPublisher.java \
  kafka-producer/src/test/java/br/kauan/kafkaproducer/kafka/KafkaPaymentPublisherTest.java
git commit -m "perf: partition SPI ingress by ISPB"
```

### Task 2: Verify and execute the single keyed A/B run

**Files:**
- Modify: `docs/board/Atividades/agora/estabilizar-teste-carga-budget-cpu.md`
- Modify: `docs/board/Atividades/Backlog/produto-dominio/substituir-buckets-por-reserva-no-saldo.md`
- Modify: `docs/superpowers/plans/2026-08-17-kafka-ispb-key-ab.md`

**Interfaces:**
- Consumes: committed keyed producer and immutable unkeyed bundle `reservation-balance-diagnostic/20260816_221950`.
- Produces: one qualifying smoke, one complete keyed diagnostic bundle, phase-aligned A/B evidence, and a predeclared `KEEP for further evaluation`, `DISCARD`, or `INCONCLUSIVE` decision.

- [x] **Step 1: Run automated verification before Docker traffic**

Run:

```bash
cd spi && ./mvnw test
cd ../load-test/go-loadtool && go test ./...
cd ../..
for test_script in load-test/tests/*.sh; do bash "$test_script"; done
docker compose -f infra/docker-compose.yml config --quiet
git diff --check
```

Expected: all Java, Go, and shell suites pass; Compose configuration and diff checks exit zero.

- [x] **Step 2: Rebuild and qualify the environment once**

Run:

```bash
cd load-test
./prepare-performance-environment.sh
```

This intentionally removes the disposable stack volumes, preserves build cache, rebuilds the keyed kafka-producer, waits for readiness, and runs only the preparer's bounded functional smoke attempts. Stop before measured traffic if qualification fails.

- [x] **Step 3: Execute the keyed diagnostic exactly once**

Run once:

```bash
./run-load-test.sh \
  --profile mixed-outcomes-2k-diagnostic \
  reservation-balance-kafka-key-diagnostic
```

Accept runner exit `0` or `1` only when the generated bundle is complete. Do not automatically rerun any operational failure and do not run another performance workload.

- [x] **Step 4: Check quiescence without generating traffic**

Run immediately:

```bash
./scripts/check-kafka-quiescence.sh
```

If lag is nonzero, make exactly one later read-only invocation after natural drain. Do not reinterpret later quiescence as active-window success.

- [x] **Step 5: Compare the keyed run to the unkeyed control**

Use each bundle's own half-open active window and record:

- active original starts, 2xx, timeouts, and rolling floor;
- active and total accepted happy-path PACS.002;
- matched, missing, and contradictory scenario outcomes;
- PACS.008 and PACS.002 replay violations;
- PostgreSQL average/maximum CPU and immediate Kafka lag;
- participant-balance native waits above one second, including count and maximum;
- normalized observed financial SQL cost per accepted PACS.002.

Apply the spec's decision exactly:

- `KEEP for further evaluation`: correctness intact; active PACS.002 accepted `>= 28,393`; rolling floor `>= 459/s`; participant-balance wait count `< 10`; maximum wait `< 28.904898 s`.
- `DISCARD`: functional/replay violation, useful-work or rolling-floor regression, or equal/worse participant-balance contention.
- `INCONCLUSIVE`: lock and useful-work evidence disagree or bundles are not comparable.

- [x] **Step 6: Record evidence and commit only the experimental branch**

Update both task documents with bundle paths, exact metrics, attribution limits, decision, and the fact that no 15-minute run occurred. Check every completed checkbox in this plan, then run:

```bash
git diff --check
git status --short --branch
git add docs/board/Atividades/agora/estabilizar-teste-carga-budget-cpu.md \
  docs/board/Atividades/Backlog/produto-dominio/substituir-buckets-por-reserva-no-saldo.md \
  docs/superpowers/plans/2026-08-17-kafka-ispb-key-ab.md
git commit -m "docs: record Kafka ISPB key experiment"
```

Do not merge, push, delete either experimental bundle, or alter the original worktree.
