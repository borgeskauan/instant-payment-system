# Batch Payment Request Ingestion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce `spi-payment-requests` queue wait by turning Kafka request batches into one domain batch and saving request transactions with JDBC batch insert.

**Architecture:** The Kafka listener will parse a `List<byte[]>` into one merged `PaymentBatch` and call the domain processor once per Kafka batch. The processor will persist all incoming transactions in one repository batch operation, then keep acceptance notifications per transaction so the external flow stays the same.

**Tech Stack:** Java 21, Spring Kafka, Spring JDBC `JdbcTemplate.batchUpdate`, JUnit 5, Mockito.

---

### Task 1: Aggregate Kafka Payment Request Batches

**Files:**
- Modify: `spi/src/main/java/br/kauan/spi/adapter/input/kafka/PaymentMessageConsumer.java`
- Modify: `spi/src/test/java/br/kauan/spi/adapter/input/kafka/PaymentMessageConsumerTest.java`

- [ ] **Step 1: Replace the existing batch listener test expectation**

In `PaymentMessageConsumerTest`, update `consumePaymentRequestsProcessesEachKafkaMessageInBatch` so it expects one merged domain batch instead of two service calls:

```java
@Test
void consumePaymentRequestsMergesKafkaMessagesIntoOneDomainBatch() throws Exception {
    PaymentTransactionMapper paymentTransactionMapper = mock(PaymentTransactionMapper.class);
    StatusReportMapper statusReportMapper = mock(StatusReportMapper.class);
    PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
    SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
    ObjectMapper objectMapper = spy(new ObjectMapper());
    PaymentMessageConsumer consumer = new PaymentMessageConsumer(
            paymentTransactionMapper,
            statusReportMapper,
            processor,
            traceRecorder
    );
    ReflectionTestUtils.setField(consumer, "objectMapper", objectMapper);
    PaymentTransaction firstPayment = PaymentTransaction.builder().paymentId("E2E-1").build();
    PaymentTransaction secondPayment = PaymentTransaction.builder().paymentId("E2E-2").build();
    PaymentBatch firstBatch = PaymentBatch.builder()
            .transactions(List.of(firstPayment))
            .build();
    PaymentBatch secondBatch = PaymentBatch.builder()
            .transactions(List.of(secondPayment))
            .build();
    when(paymentTransactionMapper.fromRegulatoryRequest(any(FIToFICustomerCreditTransfer.class)))
            .thenReturn(firstBatch, secondBatch);

    consumer.consumePaymentRequests(List.of(
            """
                    {"CdtTrfTxInf":[]}
                    """.getBytes(StandardCharsets.UTF_8),
            """
                    {"CdtTrfTxInf":[]}
                    """.getBytes(StandardCharsets.UTF_8)
    ));

    verify(objectMapper, times(2)).readTree(any(byte[].class));
    verify(objectMapper, times(2)).treeToValue(any(JsonNode.class), eq(FIToFICustomerCreditTransfer.class));
    verify(paymentTransactionMapper, times(2)).fromRegulatoryRequest(any(FIToFICustomerCreditTransfer.class));
    verify(traceRecorder).record("E2E-1", SpiTraceEvent.REQUEST_CONSUMED);
    verify(traceRecorder).record("E2E-2", SpiTraceEvent.REQUEST_CONSUMED);

    var paymentBatchCaptor = forClass(PaymentBatch.class);
    verify(processor).processTransactionBatch(eq("00000000"), paymentBatchCaptor.capture());
    assertEquals(List.of("E2E-1", "E2E-2"), paymentBatchCaptor.getValue().getTransactions().stream()
            .map(PaymentTransaction::getPaymentId)
            .toList());
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
cd spi
./mvnw test -Dtest=PaymentMessageConsumerTest
```

Expected: failure because `consumePaymentRequests` currently calls `processor.processTransactionBatch(...)` once per payload.

- [ ] **Step 3: Add a helper to parse one payment request without processing it**

In `PaymentMessageConsumer.java`, replace the body of `processPaymentTransaction(JsonNode jsonNode)` with a helper-oriented flow:

```java
private PaymentBatch toPaymentBatch(JsonNode jsonNode) {
    try {
        FIToFICustomerCreditTransfer request = objectMapper.treeToValue(
                jsonNode,
                FIToFICustomerCreditTransfer.class
        );

        PaymentBatch paymentBatch = paymentTransactionMapper.fromRegulatoryRequest(request);
        paymentBatch.getTransactions().forEach(payment ->
                traceRecorder.record(payment.getPaymentId(), SpiTraceEvent.REQUEST_CONSUMED));
        return paymentBatch;
    } catch (Exception e) {
        log.error("Error parsing payment transaction from Kafka", e);
        throw new RuntimeException("Failed to parse payment transaction", e);
    }
}
```

- [ ] **Step 4: Make single-message processing use the helper**

In `PaymentMessageConsumer.java`, update `processPaymentTransaction`:

```java
private void processPaymentTransaction(JsonNode jsonNode) {
    try {
        PaymentBatch paymentBatch = toPaymentBatch(jsonNode);

        log.debug("Processing payment transaction batch for ISPB: {}, transactions: {}",
                "00000000", paymentBatch.getTransactions().size());

        paymentTransactionProcessorUseCase.processTransactionBatch("00000000", paymentBatch);
    } catch (Exception e) {
        log.error("Error processing payment transaction from Kafka", e);
        throw new RuntimeException("Failed to process payment transaction", e);
    }
}
```

- [ ] **Step 5: Make batch-message processing merge payloads**

In `PaymentMessageConsumer.java`, replace `consumePaymentRequests`:

```java
public void consumePaymentRequests(List<byte[]> payloads) {
    try {
        log.debug("Received batch from Kafka topic '{}', records: {}", PAYMENT_REQUESTS_TOPIC, payloads.size());
        var payments = new ArrayList<PaymentTransaction>();

        for (byte[] payload : payloads) {
            JsonNode jsonNode = objectMapper.readTree(payload);
            PaymentBatch paymentBatch = toPaymentBatch(jsonNode);
            payments.addAll(paymentBatch.getTransactions());
        }

        if (payments.isEmpty()) {
            return;
        }

        paymentTransactionProcessorUseCase.processTransactionBatch(
                "00000000",
                PaymentBatch.builder()
                        .transactions(payments)
                        .build()
        );
    } catch (Exception e) {
        log.error("Error processing payment transaction batch from Kafka", e);
    }
}
```

Add import if missing:

```java
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
```

- [ ] **Step 6: Run focused tests**

Run:

```bash
cd spi
./mvnw test -Dtest=PaymentMessageConsumerTest,KafkaConsumerConfigTest
```

Expected: all focused tests pass.

### Task 2: Add Repository Batch Save Contract

**Files:**
- Modify: `spi/src/main/java/br/kauan/spi/port/output/PaymentTransactionRepository.java`
- Modify: `spi/src/main/java/br/kauan/spi/adapter/output/PaymentTransactionJpaAdapter.java`
- Modify: `spi/src/test/java/br/kauan/spi/adapter/output/PaymentTransactionJpaAdapterTest.java`

- [ ] **Step 1: Write the failing adapter test**

Add this test to `PaymentTransactionJpaAdapterTest`:

```java
@Test
void saveTransactionsPersistsSettlementFieldsUsingJdbcBatchInsert() {
    PaymentTransactionJpaClient paymentTransactionJpaClient = mock(PaymentTransactionJpaClient.class);
    PaymentTransactionRepositoryMapper repositoryMapper = new PaymentTransactionRepositoryMapper();
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    PaymentTransactionJpaAdapter adapter = new PaymentTransactionJpaAdapter(
            paymentTransactionJpaClient,
            repositoryMapper,
            jdbcTemplate
    );
    PaymentTransaction first = paymentTransaction("E2E-1", "11111111", "22222222");
    PaymentTransaction second = paymentTransaction("E2E-2", "33333333", "44444444");

    adapter.saveTransactions(List.of(first, second), PaymentStatus.WAITING_ACCEPTANCE);

    verify(jdbcTemplate).batchUpdate(
            org.mockito.ArgumentMatchers.contains("sender_bank_code"),
            org.mockito.ArgumentMatchers.eq(List.of(first, second)),
            org.mockito.ArgumentMatchers.eq(2),
            org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.ParameterizedPreparedStatementSetter.class)
    );
    verify(paymentTransactionJpaClient, never()).save(any());
}
```

Replace the existing `paymentTransaction()` helper with overloads:

```java
private static PaymentTransaction paymentTransaction() {
    return paymentTransaction("E2E-1", "11111111", "22222222");
}

private static PaymentTransaction paymentTransaction(String paymentId, String senderBankCode, String receiverBankCode) {
    return PaymentTransaction.builder()
            .paymentId(paymentId)
            .amount(BigDecimal.TEN)
            .currency("BRL")
            .description("test")
            .sender(party(senderBankCode))
            .receiver(party(receiverBankCode))
            .build();
}
```

- [ ] **Step 2: Run the adapter test and verify it fails**

Run:

```bash
cd spi
./mvnw test -Dtest=PaymentTransactionJpaAdapterTest
```

Expected: compilation failure or test failure because `saveTransactions(...)` does not exist.

- [ ] **Step 3: Add the repository method**

In `PaymentTransactionRepository.java`, add:

```java
void saveTransactions(java.util.List<PaymentTransaction> paymentTransactions, PaymentStatus paymentStatus);
```

Add import:

```java
import java.util.List;
```

- [ ] **Step 4: Implement JDBC batch insert**

In `PaymentTransactionJpaAdapter.java`, add:

```java
@Override
public void saveTransactions(List<PaymentTransaction> paymentTransactions, PaymentStatus paymentStatus) {
    if (paymentTransactions.isEmpty()) {
        return;
    }

    jdbcTemplate.batchUpdate(
            INSERT_PAYMENT_TRANSACTION_SQL,
            paymentTransactions,
            paymentTransactions.size(),
            (statement, paymentTransaction) -> {
                statement.setString(1, paymentTransaction.getPaymentId());
                statement.setBigDecimal(2, paymentTransaction.getAmount());
                statement.setString(3, paymentStatus.name());
                statement.setString(4, Utils.getBankCode(paymentTransaction.getSender()));
                statement.setString(5, Utils.getBankCode(paymentTransaction.getReceiver()));
            }
    );
}
```

Add import:

```java
import java.util.List;
```

- [ ] **Step 5: Make single save delegate to batch save**

In `PaymentTransactionJpaAdapter.java`, simplify `saveTransaction`:

```java
@Override
public void saveTransaction(PaymentTransaction paymentTransaction, PaymentStatus paymentStatus) {
    saveTransactions(List.of(paymentTransaction), paymentStatus);
}
```

- [ ] **Step 6: Run adapter tests**

Run:

```bash
cd spi
./mvnw test -Dtest=PaymentTransactionJpaAdapterTest
```

Expected: all adapter tests pass.

### Task 3: Batch Persist Incoming Transactions in the Processor

**Files:**
- Modify: `spi/src/main/java/br/kauan/spi/domain/services/PaymentTransactionProcessorService.java`
- Modify: `spi/src/test/java/br/kauan/spi/domain/services/PaymentTransactionProcessorServiceTest.java`

- [ ] **Step 1: Write failing processor test**

Add or update the existing `processTransactionBatch` test in `PaymentTransactionProcessorServiceTest`:

```java
@Test
void processTransactionBatchSavesTransactionsInBatchBeforeSendingAcceptanceRequests() {
    PaymentTransactionRepository paymentTransactionRepository = mock(PaymentTransactionRepository.class);
    NotificationService notificationService = mock(NotificationService.class);
    SettlementService settlementService = mock(SettlementService.class);
    SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
    PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
            paymentTransactionRepository,
            notificationService,
            settlementService,
            traceRecorder
    );
    PaymentTransaction first = payment("E2E-1", "10000001", "20000001");
    PaymentTransaction second = payment("E2E-2", "10000002", "20000002");
    PaymentBatch batch = PaymentBatch.builder()
            .transactions(List.of(first, second))
            .build();

    service.processTransactionBatch("00000000", batch);

    verify(paymentTransactionRepository).saveTransactions(
            List.of(first, second),
            PaymentStatus.WAITING_ACCEPTANCE
    );
    verify(paymentTransactionRepository, never()).saveTransaction(any(PaymentTransaction.class), any(PaymentStatus.class));
    verify(traceRecorder).record("E2E-1", SpiTraceEvent.REQUEST_SAVED);
    verify(traceRecorder).record("E2E-2", SpiTraceEvent.REQUEST_SAVED);
    verify(notificationService).sendAcceptanceRequest("20000001", first);
    verify(notificationService).sendAcceptanceRequest("20000002", second);
    verify(traceRecorder).record("E2E-1", SpiTraceEvent.ACCEPTANCE_NOTIFICATION_ENQUEUED);
    verify(traceRecorder).record("E2E-2", SpiTraceEvent.ACCEPTANCE_NOTIFICATION_ENQUEUED);
}
```

- [ ] **Step 2: Run processor test and verify it fails**

Run:

```bash
cd spi
./mvnw test -Dtest=PaymentTransactionProcessorServiceTest
```

Expected: failure because the processor still calls `saveTransaction(...)` per transaction.

- [ ] **Step 3: Implement batch persistence in processTransactionBatch**

In `PaymentTransactionProcessorService.java`, replace `processTransactionBatch`:

```java
@Override
public void processTransactionBatch(String ispb, PaymentBatch transactionBatch) {
    List<PaymentTransaction> transactions = transactionBatch.getTransactions();
    if (transactions.isEmpty()) {
        return;
    }

    paymentTransactionRepository.saveTransactions(transactions, PaymentStatus.WAITING_ACCEPTANCE);

    for (var payment : transactions) {
        traceRecorder.record(payment.getPaymentId(), SpiTraceEvent.REQUEST_SAVED);
        sendAcceptanceRequest(payment);
    }
}
```

- [ ] **Step 4: Extract acceptance notification helper**

In `PaymentTransactionProcessorService.java`, add:

```java
private void sendAcceptanceRequest(PaymentTransaction paymentTransaction) {
    String receiverBank = Utils.getBankCode(paymentTransaction.getReceiver());
    log.debug("[PIX FLOW - Step 4] SPI forwarding acceptance request to PSP Recebedor (Bank: {})", receiverBank);
    notificationService.sendAcceptanceRequest(receiverBank, paymentTransaction);
    traceRecorder.record(paymentTransaction.getPaymentId(), SpiTraceEvent.ACCEPTANCE_NOTIFICATION_ENQUEUED);
}
```

- [ ] **Step 5: Keep single transaction helper aligned**

In `PaymentTransactionProcessorService.java`, update `processTransaction`:

```java
private void processTransaction(PaymentTransaction paymentTransaction) {
    log.debug("[PIX FLOW - Step 3] SPI received transaction request. Payment ID: {}, Amount: {}",
            paymentTransaction.getPaymentId(), paymentTransaction.getAmount());

    paymentTransactionRepository.saveTransaction(paymentTransaction, PaymentStatus.WAITING_ACCEPTANCE);
    traceRecorder.record(paymentTransaction.getPaymentId(), SpiTraceEvent.REQUEST_SAVED);
    log.debug("[PIX FLOW - Step 3] Transaction saved with status WAITING_ACCEPTANCE");

    sendAcceptanceRequest(paymentTransaction);
}
```

- [ ] **Step 6: Run processor tests**

Run:

```bash
cd spi
./mvnw test -Dtest=PaymentTransactionProcessorServiceTest
```

Expected: all processor tests pass.

### Task 4: Full Verification and Load Test Handoff

**Files:**
- No production files beyond previous tasks.

- [ ] **Step 1: Run focused SPI tests**

Run:

```bash
cd spi
./mvnw test -Dtest=PaymentMessageConsumerTest,KafkaConsumerConfigTest,PaymentTransactionJpaAdapterTest,PaymentTransactionProcessorServiceTest
```

Expected: all focused tests pass.

- [ ] **Step 2: Run full SPI test suite**

Run:

```bash
cd spi
./mvnw test
```

Expected: build success with zero test failures.

- [ ] **Step 3: Check diff formatting**

Run:

```bash
git diff --check
```

Expected: no output and exit code 0.

- [ ] **Step 4: Rebuild SPI container**

Run:

```bash
docker compose -f infra/docker-compose.yml up -d --build --force-recreate spi
```

Expected: `spi` container recreated and healthy enough for the load runner.

- [ ] **Step 5: Run the load test under a new tag**

Run:

```bash
cd load-test
./run-load-test.sh batch-domain-and-db-payment-ingestion
```

Expected: results under `load-test/results/batch-domain-and-db-payment-ingestion/<timestamp>/`.

- [ ] **Step 6: Evaluate success criteria**

Compare against `load-test/results/consuming-payments-from-kafka-in-batch`:

```bash
for d in load-test/results/batch-domain-and-db-payment-ingestion/2026*; do
  [ -f "$d/sla-report.json" ] || continue
  echo "$(basename "$d")"
  jq -r '[.transactions.started,.transactions.completion.completed,.transactions.completed_by_sla.within_sla,.throughput_per_second.completed_during_active,.throughput_per_second.completed_including_drain,.latency_ms.p50,.latency_ms.p95,.latency_ms.p99] | @tsv' "$d/sla-report.json"
done
```

Expected directional improvement:
- `request_consumed_active/s` closer to `2000/s`;
- `http_done -> request_consumed` p95 lower than the previous typical `7s-20s`;
- `spi-payment-requests` max lag lower and less variable;
- `request_consumed -> request_saved` should not regress materially.

---

## Self-Review

Spec coverage:
- Phase A is covered by Task 1.
- Phase B is covered by Tasks 2 and 3.
- Verification and load-test handoff are covered by Task 4.

Placeholder scan:
- No placeholders remain.

Type consistency:
- `PaymentTransactionRepository.saveTransactions(List<PaymentTransaction>, PaymentStatus)` is introduced in Task 2 and consumed in Task 3.
- `PaymentMessageConsumer.consumePaymentRequests(List<byte[]>)` remains the Kafka batch entry point.
- `PaymentBatch.builder().transactions(...)` matches the existing domain type.
