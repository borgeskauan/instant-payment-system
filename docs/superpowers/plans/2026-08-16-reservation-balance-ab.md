# Reservation-Based Participant Balance A/B Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace bucketed settlement with one available-balance row per participant, reserve funds during new `pacs.008` processing, and execute one controlled short B run against the immutable bucket baseline.

**Architecture:** A reset-only Flyway migration replaces both legacy balance tables with `participant_balance_entity`. New payments are first claimed by their successful insert, then only those claimed rows may reserve payer funds; status processing locks payments, acquires guarded transitions from `WAITING_ACCEPTANCE`, and derives receiver credits or payer releases only from those acquired transitions. The existing Spring transaction continues to atomically cover payment state, balance, audit, and outbox.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring JDBC transactions, PostgreSQL 17/Testcontainers, Flyway, JUnit 5/AssertJ, Docker Compose, Bash, Go load-tool.

## Global Constraints

- Work only on branch `reservation-balance-ab`, whose A parent is `d1483be95fafac23dfd8c631e33b141249c5047b`; do not merge or modify `estabilizing-performance`.
- Keep A immutable at `load-test/results/postgres-lock-wait-attribution/20260816_201657`; do not rerun it.
- Use reset-only migration: no backfill and no coexistence or feature flag for the bucket model.
- Preserve SPI listener concurrency `3`, PostgreSQL at 1 vCPU, Kafka topology, HTTP settings, replay settings, workload profiles, diagnostics, and deadlines.
- `pacs.008` balance deltas may contain only payments inserted by the current transaction; a conflict loser contributes no reserve.
- `pacs.002` balance deltas may contain only rows whose guarded transition from `WAITING_ACCEPTANCE` was acquired by the current transaction.
- Acquire payment locks by payment ID and participant-balance locks by ISPB in deterministic order.
- Keep identical replay a no-op and divergent duplicate/status behavior explicit.
- Keep `SETTLEMENT_APPLIED` as the logical accepted settlement audit; the payer is not physically debited during `pacs.002`.
- Execute exactly one B `mixed-outcomes-2k-diagnostic` run after automated checks; do not execute the 15-minute profile.

---

### Task 1: Replace the bucket schema and administrative repository

**Files:**
- Create: `spi/src/main/resources/db/migration/V10__Replace_funds_buckets_with_participant_balance.sql`
- Modify: `spi/src/main/java/br/kauan/spi/port/output/FundsRepository.java`
- Modify: `spi/src/main/java/br/kauan/spi/port/output/FundsJpaAdapter.java`
- Delete: `spi/src/main/java/br/kauan/spi/port/output/FundsEntity.java`
- Delete: `spi/src/main/java/br/kauan/spi/port/output/FundsJpaClient.java`
- Modify: `spi/src/test/java/br/kauan/spi/port/output/FundsJpaAdapterTest.java`
- Create: `spi/src/test/java/br/kauan/spi/port/output/ParticipantBalanceSchemaIntegrationTest.java`

**Interfaces:**
- Consumes: the unchanged `FundsRepository.provisionAccount(String, long, boolean)` and `getAvailableFundsCents(String)` administrative contract.
- Produces: table `participant_balance_entity(bank_code TEXT PRIMARY KEY, balance_cents BIGINT NOT NULL CHECK balance_cents >= 0)` for both administrative and payment persistence.

- [x] **Step 1: Write failing one-row repository tests**

Replace bucket-oriented assertions in `FundsJpaAdapterTest` with exact one-statement behavior:

```java
@Test
void provisionAccountCreatesOrResetsOneParticipantBalance() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    FundsJpaAdapter adapter = new FundsJpaAdapter(jdbc);

    adapter.provisionAccount("10000001", 16_000L, true);

    verify(jdbc).update(contains("DO UPDATE"), eq("10000001"), eq(16_000L));
}

@Test
void provisionAccountPreservesOneParticipantBalanceWhenResetIsDisabled() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    FundsJpaAdapter adapter = new FundsJpaAdapter(jdbc);

    adapter.provisionAccount("10000001", 16_000L, false);

    verify(jdbc).update(contains("DO NOTHING"), eq("10000001"), eq(16_000L));
}
```

Add `ParticipantBalanceSchemaIntegrationTest` asserting one row per ISPB, reset/preserve behavior, non-negative enforcement, and absence of both legacy tables through `to_regclass`.

- [x] **Step 2: Run the focused tests and verify RED**

Run:

```bash
cd spi
./mvnw -Dtest=FundsJpaAdapterTest,ParticipantBalanceSchemaIntegrationTest test
```

Expected: failure because provisioning still writes 16 buckets and `participant_balance_entity` does not exist.

- [x] **Step 3: Add the reset-only migration and single-row adapter**

The migration must contain the concrete reset model:

```sql
DROP TABLE funds_bucket_entity;
DROP TABLE funds_entity;

CREATE TABLE participant_balance_entity (
    bank_code TEXT PRIMARY KEY,
    balance_cents BIGINT NOT NULL,
    CONSTRAINT participant_balance_non_negative_ck CHECK (balance_cents >= 0)
);
```

Replace the adapter SQL with one upsert and one direct lookup. Remove unused `deductFunds` and `addFunds` methods from `FundsRepository`; financial hot-path mutations remain inside the bulk payment persistence classes.

- [x] **Step 4: Run focused tests and verify GREEN**

Run the command from Step 2. Expected: all selected tests pass and Flyway reaches version 10.

- [x] **Step 5: Commit the schema boundary**

```bash
git add spi/src/main/resources/db/migration/V10__Replace_funds_buckets_with_participant_balance.sql \
  spi/src/main/java/br/kauan/spi/port/output/FundsRepository.java \
  spi/src/main/java/br/kauan/spi/port/output/FundsJpaAdapter.java \
  spi/src/main/java/br/kauan/spi/port/output/FundsEntity.java \
  spi/src/main/java/br/kauan/spi/port/output/FundsJpaClient.java \
  spi/src/test/java/br/kauan/spi/port/output/FundsJpaAdapterTest.java \
  spi/src/test/java/br/kauan/spi/port/output/ParticipantBalanceSchemaIntegrationTest.java
git commit -m "refactor: replace funds buckets with participant balances"
```

### Task 2: Reserve only newly claimed `pacs.008` payments

**Files:**
- Modify: `spi/src/main/java/br/kauan/spi/adapter/output/paymenttransaction/IncomingPaymentRequestPersistence.java`
- Modify: `spi/src/main/java/br/kauan/spi/port/output/PaymentTransactionPersistenceResult.java`
- Modify: `spi/src/main/java/br/kauan/spi/domain/services/PaymentTransactionProcessorService.java`
- Modify: `spi/src/main/java/br/kauan/spi/domain/services/audit/PaymentAuditEvent.java`
- Modify: `spi/src/main/java/br/kauan/spi/domain/services/audit/PaymentAuditService.java`
- Modify: `spi/src/main/resources/db/migration/V10__Replace_funds_buckets_with_participant_balance.sql`
- Modify: `spi/src/test/java/br/kauan/spi/adapter/output/paymenttransaction/JpaAdapterIntegrationTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/domain/services/audit/PaymentAuditServiceTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/domain/services/PaymentTransactionProcessorServiceTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/adapter/input/kafka/consumer/PaymentMessageConsumerTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/domain/services/TransactionalOutboxIntegrationTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/domain/services/TransactionalOutboxRollbackIntegrationTest.java`
- Create: `spi/src/test/java/br/kauan/spi/domain/services/ConcurrentParticipantBalanceIntegrationTest.java`

**Interfaces:**
- Consumes: `participant_balance_entity` from Task 1 and the existing `@Transactional processTransactions(...)` service boundary.
- Produces: `PaymentTransactionPersistenceResult` with `rejectedPayments()` in addition to acceptance, creation, divergent, and unauthorized populations; every rejected item is also present in `createdPayments()`.

- [x] **Step 1: Write failing ingress reservation tests**

In `JpaAdapterIntegrationTest`, provision participant rows and add tests proving:

```java
@Test
void newPaymentsReserveInSourceOrderWithoutPrefixFairness() {
    insertBalance("11111111", 10_000L);
    PaymentTransactionCommand first = payment("...-80", 8_000L, "11111111", "22222222");
    PaymentTransactionCommand tooLarge = payment("...-50", 5_000L, "11111111", "22222222");
    PaymentTransactionCommand laterSmall = payment("...-10", 1_000L, "11111111", "22222222");

    PaymentTransactionPersistenceResult result = store(first, tooLarge, laterSmall);

    assertThat(result.acceptanceRequests()).containsExactly(first, laterSmall);
    assertThat(result.rejectedPayments())
            .extracting(r -> r.payment().getPaymentId(), PaymentRejection::reason)
            .containsExactly(tuple(tooLarge.getPaymentId(), PaymentRejectionReason.INSUFFICIENT_FUNDS));
    assertThat(balanceCents("11111111")).isEqualTo(1_000L);
}
```

Also assert zero balance gives persisted `REJECTED / INSUFFICIENT_FUNDS`, no acceptance request, and an identical sequential replay changes neither balance nor output populations.

In `ConcurrentParticipantBalanceIntegrationTest`, use the proxied `PaymentTransactionProcessorUseCase`, a `CountDownLatch`, and two executor tasks to submit one identical `pacs.008`. Assert one payment row, one `PAYMENT_CREATED` audit row, one acceptance outbox row, and exactly one amount deducted.

Create the shared concurrency helper with a bounded rendezvous and bounded
completion, so a deadlock fails the test instead of hanging the suite:

```java
private void invokeConcurrently(Runnable first, Runnable second) throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
        Callable<Void> firstCall = concurrentCall(first, ready, start);
        Callable<Void> secondCall = concurrentCall(second, ready, start);
        Future<Void> firstResult = executor.submit(firstCall);
        Future<Void> secondResult = executor.submit(secondCall);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        firstResult.get(30, TimeUnit.SECONDS);
        secondResult.get(30, TimeUnit.SECONDS);
    }
}

private Callable<Void> concurrentCall(
        Runnable action,
        CountDownLatch ready,
        CountDownLatch start
) {
    return () -> {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent test start timed out");
        }
        action.run();
        return null;
    };
}
```

The test fixture also provides concrete helpers
`reservePayment(String paymentId)`, `balanceCents(String ispb)`,
`auditCount(String paymentId, String eventType)`,
`statusChangeAuditCount(String paymentId, PaymentStatus status)`, and
`statusOutboxCount(String paymentId)`. `reservePayment` provisions payer and
receiver rows, invokes `processTransactions`, asserts `WAITING_ACCEPTANCE`, and
returns the submitted command; the count helpers execute direct aggregate SQL
against the audit and outbox tables.

- [x] **Step 2: Run ingress tests and verify RED**

Run:

```bash
cd spi
./mvnw -Dtest=JpaAdapterIntegrationTest,ConcurrentParticipantBalanceIntegrationTest test
```

Expected: failure because ingress does not reserve, cannot expose `rejectedPayments()`, and still republishes acceptance for an existing waiting replay.

- [x] **Step 3: Make successful insert the creation claim**

Keep same-batch authentication/fingerprint classification. Change the insert/classification SQL so `INSERT ... ON CONFLICT DO NOTHING RETURNING payment_id` is the only source of `PAYMENT_CREATED`. Remove `existing_waiting_acceptance_actions`; identical existing payments return no action.

Return a `RECHECK_EXISTING` action when a row was absent from the statement snapshot but lost `ON CONFLICT`. Re-read only those conflict losers in a subsequent statement and classify them as identical no-op, unauthorized, or divergent from the now-visible row. This preserves explicit divergent behavior during a concurrent insert race.

- [x] **Step 4: Reserve the claimed set in the same Spring transaction**

Pass only `PAYMENT_CREATED` rows to a bulk reservation statement. Its dataflow must be:

```text
claimed rows
  -> lock distinct payer balances ORDER BY bank_code FOR UPDATE
  -> evaluate each payer by sourceOrdinal, carrying remaining balance
  -> reserved rows / insufficient rows
  -> one aggregate debit per payer
  -> guarded REJECTED / INSUFFICIENT_FUNDS update for insufficient rows
```

Use a deterministic in-memory fold over the locked payer rows, partitioned by payer and ordered by `sourceOrdinal`, so an insufficient payment does not consume logical remaining balance and a later smaller payment can reserve. Keep the database boundary bulk: one insert claim, one ordered lock query, one aggregate debit update, and one rejection update. Return one `ReservationOutcome(ordinal, reserved)` per claimed row, and build acceptance/rejection lists only from those outcomes.

- [x] **Step 5: Persist ingress rejection audit and AM04 outbox atomically**

Extend the result record exactly as:

```java
public record PaymentTransactionPersistenceResult(
        List<PaymentTransactionCommand> acceptanceRequests,
        List<PaymentTransactionCommand> createdPayments,
        List<PaymentRejection> rejectedPayments,
        List<AuthenticatedPaymentRequest> divergentDuplicates,
        List<AuthenticatedPaymentRequest> unauthorizedRequests
) {}
```

Change `PaymentAuditService.storeCreationEvents` to accept both created and ingress-rejected lists. A reserved creation records `WAITING_ACCEPTANCE`; an insufficient creation records `REJECTED` plus `INSUFFICIENT_FUNDS`. Update the V10 audit shape/reason constraints and `PaymentAuditEvent` validation to permit that exact rejected-creation shape. In `PaymentTransactionProcessorService`, store status obligations for ingress rejections so the payer receives `RJCT / AM04`; never create an acceptance obligation for them.

- [x] **Step 6: Prove transactional rollback for reservation and rejection**

Update rollback tests to provision balances before processing. For audit and outbox failure during a reserved creation, assert payment count remains zero and payer balance returns to its original value. For an insufficient creation, assert no payment, audit, outbox, or balance mutation survives rollback.

- [x] **Step 7: Run focused ingress/service tests and verify GREEN**

Run:

```bash
cd spi
./mvnw -Dtest=JpaAdapterIntegrationTest,ConcurrentParticipantBalanceIntegrationTest,PaymentAuditServiceTest,TransactionalOutboxIntegrationTest,TransactionalOutboxRollbackIntegrationTest test
```

Expected: selected tests pass, including exactly-one concurrent reservation.

- [x] **Step 8: Commit ingress reservation**

```bash
git add spi/src/main spi/src/test spi/src/main/resources/db/migration/V10__Replace_funds_buckets_with_participant_balance.sql
git commit -m "feat: reserve participant funds on payment ingress"
```

### Task 3: Credit or release only acquired `pacs.002` transitions

**Files:**
- Modify: `spi/src/main/java/br/kauan/spi/adapter/output/paymenttransaction/IncomingStatusReportPersistence.java`
- Modify: `spi/src/test/java/br/kauan/spi/adapter/output/paymenttransaction/JpaAdapterIntegrationTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/domain/services/ConcurrentParticipantBalanceIntegrationTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/domain/services/TransactionalOutboxIntegrationTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/domain/services/TransactionalOutboxRollbackIntegrationTest.java`

**Interfaces:**
- Consumes: reserved payments created by Task 2 and existing `StatusReportPersistenceResult`/service APIs.
- Produces: accepted transitions that credit receiver only and rejected transitions that release payer only; returned action rows are exactly the acquired transition rows.

- [x] **Step 1: Write failing accepted/rejected/mixed tests**

Replace bucket-settlement assertions with these balance sequences:

```text
accepted: payer 10000 --reserve 1000--> 9000; receiver 5000 --accept--> 6000
rejected: payer 10000 --reserve 1000--> 9000 --reject--> 10000; receiver stays 5000
mixed: accepted deltas group by receiver; rejected deltas group by payer in one transaction
```

Assert accepted processing never changes payer balance after reservation, rejected processing releases exactly the reserved amount, repeated terminal statuses are no-ops, and conflicting terminal statuses remain divergent.

- [x] **Step 2: Write failing concurrent status tests**

Add two methods to `ConcurrentParticipantBalanceIntegrationTest`, using the
same `invokeConcurrently(Runnable, Runnable)` latch/executor helper created for
the ingress race:

```java
@Test
void concurrentIdenticalAcceptedStatusesCreditReceiverExactlyOnce() throws Exception {
    PaymentTransactionCommand payment = reservePayment("E2E-CONCURRENT-ACCEPTED");
    long receiverBefore = balanceCents(RECEIVER_ISPB);
    Runnable accept = () -> processor.processStatusReports(authenticatedReports(
            payment.getPaymentId(), PaymentStatus.ACCEPTED_IN_PROCESS));

    invokeConcurrently(accept, accept);

    assertThat(balanceCents(RECEIVER_ISPB)).isEqualTo(receiverBefore + payment.getAmountCents());
    assertThat(auditCount(payment.getPaymentId(), "SETTLEMENT_APPLIED")).isOne();
    assertThat(statusOutboxCount(payment.getPaymentId())).isEqualTo(2);
}

@Test
void concurrentIdenticalRejectedStatusesReleasePayerExactlyOnce() throws Exception {
    PaymentTransactionCommand payment = reservePayment("E2E-CONCURRENT-REJECTED");
    long payerAfterReserve = balanceCents(SENDER_ISPB);
    Runnable reject = () -> processor.processStatusReports(authenticatedReports(
            payment.getPaymentId(), PaymentStatus.REJECTED));

    invokeConcurrently(reject, reject);

    assertThat(balanceCents(SENDER_ISPB)).isEqualTo(payerAfterReserve + payment.getAmountCents());
    assertThat(statusChangeAuditCount(payment.getPaymentId(), PaymentStatus.REJECTED)).isOne();
    assertThat(statusOutboxCount(payment.getPaymentId())).isOne();
}
```

For accepted, assert one `SETTLEMENT_APPLIED`, one logical status transition, and one pair of status outbox obligations. For rejected, assert one status transition and one payer rejection obligation.

- [x] **Step 3: Run status tests and verify RED**

Run:

```bash
cd spi
./mvnw -Dtest=JpaAdapterIntegrationTest,ConcurrentParticipantBalanceIntegrationTest test
```

Expected: failure because the current SQL still hashes payment IDs, accesses `funds_bucket_entity`, and debits the payer during acceptance.

- [x] **Step 4: Rewrite status persistence around acquired transitions**

Preserve authentication, same-batch divergence, and unknown-payment classification. Replace bucket CTEs with bulk JDBC statements on the same transactional connection, in this mandatory dependency order:

```text
logical status rows
  -> payment rows locked ORDER BY payment_id FOR UPDATE
  -> current WAITING_ACCEPTANCE candidates
  -> required participant rows locked ORDER BY bank_code FOR UPDATE
  -> guarded accepted/rejected UPDATE ... RETURNING
  -> deltas derived only from those RETURNING rows
  -> net delta per participant
  -> one participant_balance_entity UPDATE per participant
  -> action rows derived only from guarded transition results
```

Accepted `RETURNING` rows contribute `receiver_bank_code, +amount_cents`; rejected rows contribute `sender_bank_code, +amount_cents`. If any required participant row is absent, raise a persistence error so the whole Kafka batch retries; never acknowledge a final status without its corresponding financial mutation.

- [x] **Step 5: Preserve logical audit semantics and rollback**

Keep `SETTLEMENT_APPLIED` for acquired accepts with logical sender/receiver deltas `-amount/+amount`, while the physical SQL touches only the receiver. Update integration fixtures so every synthetic `WAITING_ACCEPTANCE` is created through ingress or paired with an already-deducted payer balance. Assert audit/outbox failure rolls back receiver credit or payer release together with the status.

- [x] **Step 6: Run focused status/service tests and verify GREEN**

Run the focused command from Task 2 Step 7. Expected: all selected tests pass, including exactly-once concurrent credit and release.

- [x] **Step 7: Commit status settlement**

```bash
git add spi/src/main/java/br/kauan/spi/adapter/output/paymenttransaction/IncomingStatusReportPersistence.java \
  spi/src/test/java/br/kauan/spi/adapter/output/paymenttransaction/JpaAdapterIntegrationTest.java \
  spi/src/test/java/br/kauan/spi/domain/services/ConcurrentParticipantBalanceIntegrationTest.java \
  spi/src/test/java/br/kauan/spi/domain/services/TransactionalOutboxIntegrationTest.java \
  spi/src/test/java/br/kauan/spi/domain/services/TransactionalOutboxRollbackIntegrationTest.java
git commit -m "feat: settle acquired statuses by participant balance"
```

### Task 4: Remove bucket-only behavior and verify the complete SPI

**Files:**
- Modify: `spi/src/test/java/br/kauan/spi/adapter/output/paymenttransaction/JpaAdapterIntegrationTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/domain/services/TransactionalOutboxIntegrationTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/domain/services/TransactionalOutboxRollbackIntegrationTest.java`
- Modify: `docs/board/Atividades/Backlog/produto-dominio/substituir-buckets-por-reserva-no-saldo.md`
- Modify: `docs/architecture/reservation-based-participant-balance.md`

**Interfaces:**
- Consumes: Tasks 1–3 completed behavior.
- Produces: a bucket-free SPI source/test tree and documentation matching the executable B.

- [x] **Step 1: Remove obsolete test cases and helpers**

Delete tests whose contract existed only because insufficiency was discovered during accepted settlement: partial same-bucket prefix settlement, hash/bucket fixture selection, sender-bucket absence, receiver-bucket absence, and `ACCEPTED_IN_PROCESS` caused by missing buckets. Replace every fixture query with `participant_balance_entity` and direct cent balances.

- [x] **Step 2: Scan for forbidden production remnants**

Run:

```bash
rg -n 'funds_bucket_entity|bucket_id|BUCKET_COUNT|FundsJpaClient|FundsEntity' spi/src/main spi/src/test
```

Expected: no production matches; the schema integration test may mention the old table only to prove it is absent. Historical architecture/spec/task descriptions may still mention buckets as the removed A model.

- [x] **Step 3: Run the full SPI suite**

Run:

```bash
cd spi
./mvnw test
```

Expected: all unit and Testcontainers integration tests pass with zero failures, errors, or skips.

- [x] **Step 4: Run repository-level static checks**

Run:

```bash
git diff --check
bash -n load-test/run-load-test.sh load-test/prepare-performance-environment.sh
```

No load-tool behavior is changed, but the runner scripts used by the experiment must remain syntactically valid.

- [x] **Step 5: Update architecture-task implementation status and commit**

Record the implemented tables, reserve/credit/release ownership rule, concurrent tests, and remaining experimental decision in the backlog task. Do not mark the architecture adopted before the B result.

```bash
git add spi docs/architecture/reservation-based-participant-balance.md \
  docs/board/Atividades/Backlog/produto-dominio/substituir-buckets-por-reserva-no-saldo.md
git commit -m "test: verify reservation balance invariants"
```

### Task 5: Qualify and execute the single short B experiment

**Files:**
- Modify: `docs/board/Atividades/agora/estabilizar-teste-carga-budget-cpu.md`
- Runtime output: `load-test/results/reservation-balance-diagnostic/<timestamp>/`

**Interfaces:**
- Consumes: immutable A bundle and the fully verified B commit.
- Produces: one complete B bundle, phase-aligned comparison, and predeclared `KEEP`, `DISCARD`, or `INCONCLUSIVE` decision.

- [x] **Step 1: Prepare the disposable B environment once**

Run from the experimental worktree:

```bash
cd load-test
./prepare-performance-environment.sh
```

The preparer may use its existing maximum of three qualifying smoke attempts. Preserve build caches; volume reset is required by V10.

- [x] **Step 2: Verify the functional smoke before performance traffic**

Accept the environment only if `mixed-outcomes-smoke` proves HTTP 2xx, payer `ACSC`, payer `RJCT / AM04`, replay invariants, and quiescence. If preparation fails operationally, record the failure and do not start B.

- [x] **Step 3: Execute B exactly once**

Run:

```bash
cd load-test
./run-load-test.sh --profile mixed-outcomes-2k-diagnostic reservation-balance-diagnostic
```

Accept runner exit `0` or `1` only when the bundle is complete; exit `2` or an incomplete bundle is an operational failure and is not rerun automatically.

- [x] **Step 4: Verify quiescence with the existing heuristic**

Read Kafka lag immediately after the run and, only if nonzero, once more after natural drain without generating new traffic. Do not reinterpret later drain as active-window success.

- [x] **Step 5: Compare A and B on their own half-open active windows**

Record exact A/B values for original starts/2xx/timeouts/rolling throughput, PACS.002 starts/accepted, outcomes, latency percentiles, replay violations, PostgreSQL CPU, financial-query calls/rows/time, cost per accepted PACS.002, wait types/relations, and lag. Attribute new financial SQL by normalized statement text/query ID rather than old bucket query IDs.

- [x] **Step 6: Apply the predeclared decision**

- `KEEP`: functional correctness is intact and end-to-end useful work, latency, or normalized financial cost improves coherently without ingress regression.
- `DISCARD`: any contradiction, replay violation, wrong balance, ingress regression, or useful-work reduction occurs.
- `INCONCLUSIVE`: evidence is small, noisy, or contradictory.

Never treat disappearance of `funds_bucket_entity` alone as improvement and never run the 15-minute profile from this plan.

- [x] **Step 7: Write evidence, update the active task, and commit only the experimental branch**

```bash
git add docs/board/Atividades/agora/estabilizar-teste-carga-budget-cpu.md
git commit -m "docs: record reservation balance experiment"
```

Finish with `git status --short --branch`, `git log -5 --oneline`, and a fresh `git diff --check`. Do not merge, push, or alter `estabilizing-performance`.
