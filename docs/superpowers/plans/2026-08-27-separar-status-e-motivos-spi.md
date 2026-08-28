# Separar status e motivos do SPI — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Separar estado persistido, outcome PACS.002 recebido e status de notificação, preservando reason codes externos e mantendo idempotência, autorização e atomicidade financeira.

**Architecture:** O domínio passa a ter tipos pequenos e fechados para cada fronteira: `PaymentState` no banco, `StatusReportOutcome` na entrada e `NotificationStatus` na saída. `StatusReasonCode` normaliza a identidade semântica dos códigos; descrições livres são descartadas na entrada. A persistência guarda separadamente a causa interna de rejeição e os códigos externos, permitindo comparar replays depois de restart sem transformar um motivo externo em causa interna.

**Tech Stack:** Java 21, Spring Boot, Spring Kafka, Spring JDBC, PostgreSQL/Flyway, Protobuf, JUnit 5, AssertJ, Testcontainers.

**Spec:** `docs/board/Atividades/agora/simplificar-arquitetura-spi.md` — Fase 1.

## Global Constraints

- Um pagamento reservado pode receber PACS.002 `RJCT`; o saldo do pagador é liberado exatamente uma vez.
- Estado persistido, outcome recebido e status de notificação não compartilham enum.
- O estado persistido contém somente `WAITING_ACCEPTANCE`, `SETTLED` e `REJECTED`.
- Notificações de settlement continuam sendo `ACSC` para o pagador e `ACCC` para o recebedor.
- Reason code externo é semântico; descrição livre não é persistida, propagada nem comparada na identidade de replay.
- Códigos são normalizados com `trim`, uppercase `Locale.ROOT`, remoção de duplicatas e ordenação lexicográfica; cada código deve casar com `[A-Z0-9]{1,4}`.
- PACS.002 `RJCT` sem ao menos um código válido é payload inválido e vai para DLQ antes de locks, saldo, auditoria ou outbox.
- PACS.002 aceito pode carregar zero ou mais códigos válidos; se existirem, eles são preservados e participam da identidade do replay.
- `INSUFFICIENT_FUNDS` é causa interna e produz `AM04`; nunca é inferida de um reason code externo.
- Replay idêntico compara payment ID, PSP autenticado, outcome e lista normalizada de códigos.
- Mudança de status ou de códigos é divergente; mudança somente de descrição é idêntica.
- Pagamento internamente rejeitado por fundos insuficientes não possui um PACS.002 original; qualquer PACS.002 posterior para ele é divergente.
- Nenhuma mudança desta fase pode separar estado, saldo, auditoria e outbox em transações diferentes.
- Não criar hierarquia genérica de status/reasons, registry, fallback de código ou nova camada de persistência.

## Translation Table

| Origem | Entrada de domínio | Estado persistido | Notificação | Reasons |
| --- | --- | --- | --- | --- |
| PACS.008 com saldo | — | `WAITING_ACCEPTANCE` | PACS.008 ao recebedor | nenhum |
| PACS.008 sem saldo | — | `REJECTED` + `INSUFFICIENT_FUNDS` | `RJCT` ao pagador | `AM04` |
| PACS.002 `ACCEPTED_IN_PROCESS` | `ACCEPTED` | `SETTLED` | `ACSC` pagador + `ACCC` recebedor | códigos externos normalizados, se houver |
| PACS.002 `REJECTED` | `REJECTED` | `REJECTED` | `RJCT` pagador | mesmos códigos externos normalizados |

---

### Task 1: Add the new closed vocabulary without changing runtime behavior

**Files:**
- Create: `spi/src/main/java/br/kauan/spi/domain/entity/status/PaymentState.java`
- Create: `spi/src/main/java/br/kauan/spi/domain/entity/status/StatusReportOutcome.java`
- Create: `spi/src/main/java/br/kauan/spi/domain/entity/status/StatusReasonCode.java`
- Create: `spi/src/main/java/br/kauan/spi/domain/entity/status/IncomingStatusReportCommand.java`
- Create: `spi/src/main/java/br/kauan/spi/domain/entity/status/NotificationStatus.java`
- Create: `spi/src/main/java/br/kauan/spi/domain/entity/status/NotificationStatusItem.java`
- Create: `spi/src/main/java/br/kauan/spi/domain/entity/status/PaymentSettlement.java`
- Create: `spi/src/test/java/br/kauan/spi/domain/entity/status/StatusReasonCodeTest.java`
- Create: `spi/src/test/java/br/kauan/spi/domain/entity/status/IncomingStatusReportCommandTest.java`

**Interfaces:**
- Produces: `enum PaymentState { WAITING_ACCEPTANCE, SETTLED, REJECTED }`.
- Produces: `enum StatusReportOutcome { ACCEPTED, REJECTED }`.
- Produces: `record StatusReasonCode(String value)` with `of(String)` and `normalize(List<StatusReasonCode>)`.
- Produces: `record IncomingStatusReportCommand(String originalPaymentId, StatusReportOutcome outcome, List<StatusReasonCode> reasonCodes)`.
- Produces: `enum NotificationStatus { ACCC, ACSC, RJCT }`.
- Produces: `record NotificationStatusItem(String originalPaymentId, NotificationStatus status, List<StatusReasonCode> reasonCodes)`.
- Produces: `record PaymentSettlement(PaymentTransactionCommand payment, List<StatusReasonCode> reasonCodes)`.
- Leaves existing `PaymentStatus`, `Reason` and `StatusReportCommand` untouched until their consumers migrate, so this checkpoint compiles independently.

- [x] **Step 1: Write failing value-object tests**

```java
@Test
void normalizesReasonCodesAsAStableSemanticSet() {
    assertThat(StatusReasonCode.normalize(List.of(
            StatusReasonCode.of(" am04 "),
            StatusReasonCode.of("AB03"),
            StatusReasonCode.of("AM04")
    ))).extracting(StatusReasonCode::value).containsExactly("AB03", "AM04");
}

@Test
void rejectsBlankOversizedOrNonStandardReasonCodes() {
    assertThatThrownBy(() -> StatusReasonCode.of(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> StatusReasonCode.of("ABCDE")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> StatusReasonCode.of("A-1")).isInstanceOf(IllegalArgumentException.class);
}

@Test
void rejectedOutcomeRequiresAReasonCode() {
    assertThatThrownBy(() -> new IncomingStatusReportCommand(
            "E2E-1", StatusReportOutcome.REJECTED, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reason code");
}

@Test
void acceptedOutcomeMayCarryNormalizedCodes() {
    var command = new IncomingStatusReportCommand(
            "E2E-1",
            StatusReportOutcome.ACCEPTED,
            List.of(StatusReasonCode.of("ab03"))
    );
    assertThat(command.reasonCodes()).extracting(StatusReasonCode::value).containsExactly("AB03");
}
```

- [x] **Step 2: Run the new tests and confirm compilation failure**

Run: `cd spi && ./mvnw -q -Dtest=StatusReasonCodeTest,IncomingStatusReportCommandTest test`

Expected: test compilation fails because the new types do not exist.

- [x] **Step 3: Implement the closed types**

```java
public record StatusReasonCode(String value) {
    private static final Pattern VALID = Pattern.compile("[A-Z0-9]{1,4}");

    public StatusReasonCode {
        value = value == null ? null : value.trim().toUpperCase(Locale.ROOT);
        if (value == null || !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid status reason code: " + value);
        }
    }

    public static StatusReasonCode of(String value) {
        return new StatusReasonCode(value);
    }

    public static List<StatusReasonCode> normalize(List<StatusReasonCode> codes) {
        if (codes == null || codes.isEmpty()) return List.of();
        return codes.stream()
                .distinct()
                .sorted(Comparator.comparing(StatusReasonCode::value))
                .toList();
    }
}

public record IncomingStatusReportCommand(
        String originalPaymentId,
        StatusReportOutcome outcome,
        List<StatusReasonCode> reasonCodes
) {
    public IncomingStatusReportCommand {
        if (originalPaymentId == null || originalPaymentId.isBlank()) {
            throw new IllegalArgumentException("Payment ID is required");
        }
        Objects.requireNonNull(outcome, "Status report outcome is required");
        reasonCodes = StatusReasonCode.normalize(reasonCodes);
        if (outcome == StatusReportOutcome.REJECTED && reasonCodes.isEmpty()) {
            throw new IllegalArgumentException("Rejected status report requires at least one reason code");
        }
    }
}
```

`NotificationStatusItem` and `PaymentSettlement` use compact constructors that require non-null identity/payment and assign `reasonCodes = StatusReasonCode.normalize(reasonCodes)`. Do not add builders or nullable collections.

- [x] **Step 4: Run the value-object tests**

Run: `cd spi && ./mvnw -q -Dtest=StatusReasonCodeTest,IncomingStatusReportCommandTest test`

Expected: PASS.

- [x] **Step 5: Commit the additive vocabulary**

```bash
git add spi/src/main/java/br/kauan/spi/domain/entity/status spi/src/test/java/br/kauan/spi/domain/entity/status
git commit -m "refactor(spi): add explicit payment status vocabulary"
```

### Task 2: Migrate inbound PACS.002, state persistence and replay identity atomically

**Files:**
- Create: `spi/src/main/resources/db/migration/V18__Separate_payment_state_and_status_reasons.sql`
- Modify: `spi/src/main/java/br/kauan/spi/domain/entity/security/AuthenticatedStatusReport.java`
- Modify: `spi/src/main/java/br/kauan/spi/adapter/input/kafka/internal/InternalPaymentMessageMapper.java`
- Modify: `spi/src/main/java/br/kauan/spi/adapter/input/kafka/consumer/InboundPaymentMessageDecoder.java`
- Modify: `spi/src/main/java/br/kauan/spi/adapter/input/kafka/consumer/PaymentMessageConsumer.java`
- Modify: `spi/src/main/java/br/kauan/spi/adapter/output/paymenttransaction/Entity.java`
- Modify: `spi/src/main/java/br/kauan/spi/adapter/output/paymenttransaction/IncomingPaymentRequestPersistence.java`
- Modify: `spi/src/main/java/br/kauan/spi/adapter/output/paymenttransaction/IncomingStatusReportPersistence.java`
- Modify: `spi/src/main/java/br/kauan/spi/adapter/output/paymenttransaction/Mapper.java`
- Modify: `spi/src/main/java/br/kauan/spi/domain/entity/status/PaymentRejection.java`
- Rename: `spi/src/main/java/br/kauan/spi/domain/entity/status/PaymentRejectionReason.java` -> `spi/src/main/java/br/kauan/spi/domain/entity/status/PaymentRejectionCause.java`
- Modify: `spi/src/main/java/br/kauan/spi/port/output/StatusReportPersistenceResult.java`
- Modify: `spi/src/main/java/br/kauan/spi/domain/services/audit/PaymentAuditEvent.java`
- Modify: `spi/src/main/java/br/kauan/spi/domain/services/audit/PaymentAuditService.java`
- Modify: `spi/src/main/java/br/kauan/spi/adapter/output/audit/PaymentAuditRepository.java`
- Modify: `spi/src/main/java/br/kauan/spi/domain/services/notification/NotificationObligationService.java`
- Modify: `spi/src/main/java/br/kauan/spi/domain/services/notification/payload/NotificationPayloadFactory.java`
- Modify: `spi/src/main/java/br/kauan/spi/domain/services/PaymentTransactionProcessorService.java`
- Delete: `spi/src/main/java/br/kauan/spi/domain/entity/status/PaymentStatus.java`
- Delete: `spi/src/main/java/br/kauan/spi/domain/entity/status/Reason.java`
- Delete: `spi/src/main/java/br/kauan/spi/domain/entity/status/StatusReportCommand.java`
- Modify: `spi/src/test/java/br/kauan/spi/adapter/input/kafka/consumer/PaymentMessageConsumerTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/adapter/output/paymenttransaction/PaymentTransactionStorageSchemaIntegrationTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/adapter/output/paymenttransaction/JpaAdapterIntegrationTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/adapter/output/paymenttransaction/JpaAdapterTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/domain/services/ConcurrentParticipantBalanceIntegrationTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/domain/services/audit/PaymentAuditServiceTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/adapter/output/audit/PaymentAuditRepositoryIntegrationTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/adapter/output/audit/PaymentAuditBusinessFactsMigrationIntegrationTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/domain/services/notification/NotificationObligationServiceTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/domain/services/notification/payload/NotificationPayloadFactoryTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/domain/services/PaymentTransactionProcessorServiceTest.java`

**Interfaces:**
- Changes `AuthenticatedStatusReport.command()` to `IncomingStatusReportCommand`.
- Produces: `PaymentRejection.insufficientFunds(payment)` or `PaymentRejection.receiverRejected(payment, codes)`; the record constructor permits exactly one origin.
- Produces: `StatusReportPersistenceResult(List<PaymentSettlement> settlements, List<PaymentRejection> rejectedPayments, List<AuthenticatedStatusReport> divergentStatusReports, List<AuthenticatedStatusReport> unauthorizedStatusReports)`.
- Persists: `state payment_state`, `rejection_cause payment_rejection_cause`, `external_reason_codes TEXT[]`.
- Changes audit and notification services in the same checkpoint so `PaymentSettlement` and reason codes are never temporarily discarded.

- [x] **Step 1: Write failing inbound, schema, replay and concurrency tests**

In `PaymentMessageConsumerTest`, add concrete protobuf cases and assert:

```java
assertThat(captured.command().outcome()).isEqualTo(StatusReportOutcome.ACCEPTED);
assertThat(captured.command().reasonCodes())
        .extracting(StatusReasonCode::value)
        .containsExactly("AB03", "AM04");
verify(processor, never()).processStatusReports(anyList());
verify(invalidPayloadRecoverer).accept(eq(record), isNull(), any(InvalidInboundPayloadException.class));
```

Build the normalization case with `AM04`, `am04` and `AB03`, each carrying different descriptions. Build invalid records with `REJECTED` and no reasons, and with code `A-1`.

In `PaymentTransactionStorageSchemaIntegrationTest`, replace old column expectations with:

```java
assertThat(columnTypes("payment_transaction_entity")).containsAllEntriesOf(Map.of(
        "state", "payment_state",
        "rejection_cause", "payment_rejection_cause",
        "external_reason_codes", "text[]"
));
```

In `JpaAdapterIntegrationTest` and `ConcurrentParticipantBalanceIntegrationTest`, add cases proving:

```text
receiver RJCT AB03 releases payer once and stores [AB03]
same RJCT/codes is a no-op after reading terminal state from PostgreSQL
AB03,AM04 equals reordered/duplicated ab03,AM04
same outcome with a changed code is divergent
ACCEPTED versus REJECTED is divergent
SETTLED plus identical ACCEPTED/codes is a no-op
internal INSUFFICIENT_FUNDS plus any PACS.002 is divergent
two concurrent identical RJCT reports produce one release
```

For every divergent/concurrent case assert exact state, both balances and the sizes of `settlements`, `rejectedPayments` and `divergentStatusReports`.

Add focused audit and notification assertions:

```java
assertThat(settlementAudit.externalReasonCodes())
        .extracting(StatusReasonCode::value)
        .containsExactly("AB03");
assertThat(receiverRejectionAudit.rejectionCause()).isNull();
assertThat(receiverRejectionPayload)
        .contains("\"TxSts\":\"RJCT\"")
        .contains("\"Cd\":\"AB03\"")
        .doesNotContain("AddtlInf");
assertThat(insufficientFundsPayload)
        .contains("\"TxSts\":\"RJCT\"")
        .contains("\"Cd\":\"AM04\"");
```

- [x] **Step 2: Run the focused tests and confirm failure**

Run: `cd spi && ./mvnw -q -Dtest=PaymentMessageConsumerTest,PaymentTransactionStorageSchemaIntegrationTest,JpaAdapterIntegrationTest,ConcurrentParticipantBalanceIntegrationTest test`

Expected: FAIL because the current mapper accepts reasonless rejection, the schema has `status`, and persistence discards codes.

- [x] **Step 3: Add the Flyway migration using separate PostgreSQL statements**

```sql
CREATE TYPE payment_state AS ENUM ('WAITING_ACCEPTANCE', 'SETTLED', 'REJECTED');
CREATE TYPE payment_rejection_cause AS ENUM ('INSUFFICIENT_FUNDS');

DROP VIEW payment_audit_event_history;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM payment_transaction_entity
        WHERE status::TEXT NOT IN ('WAITING_ACCEPTANCE', 'ACCEPTED_AND_SETTLED', 'REJECTED')
    ) OR EXISTS (
        SELECT 1 FROM payment_audit_event
        WHERE (previous_status IS NOT NULL AND previous_status::TEXT NOT IN ('WAITING_ACCEPTANCE', 'ACCEPTED_AND_SETTLED', 'REJECTED'))
           OR (resulting_status IS NOT NULL AND resulting_status::TEXT NOT IN ('WAITING_ACCEPTANCE', 'ACCEPTED_AND_SETTLED', 'REJECTED'))
    ) THEN
        RAISE EXCEPTION 'Cannot migrate obsolete runtime payment statuses to payment_state';
    END IF;
END
$$;

ALTER TABLE payment_transaction_entity
    DROP CONSTRAINT payment_transaction_rejection_reason_ck,
    ADD COLUMN external_reason_codes TEXT[];

ALTER TABLE payment_transaction_entity
    ALTER COLUMN status TYPE payment_state USING (
        CASE status::TEXT
            WHEN 'WAITING_ACCEPTANCE' THEN 'WAITING_ACCEPTANCE'
            WHEN 'ACCEPTED_AND_SETTLED' THEN 'SETTLED'
            WHEN 'REJECTED' THEN 'REJECTED'
        END
    )::payment_state,
    ALTER COLUMN rejection_reason TYPE payment_rejection_cause
        USING rejection_reason::TEXT::payment_rejection_cause;

ALTER TABLE payment_transaction_entity RENAME COLUMN status TO state;
ALTER TABLE payment_transaction_entity RENAME COLUMN rejection_reason TO rejection_cause;
```

Apply the same mapping to current V17 audit columns, rename them to `previous_state`, `resulting_state`, `rejection_cause`, and add `external_reason_codes TEXT[]`.

Add these invariants:

```sql
CHECK (
    (state = 'WAITING_ACCEPTANCE' AND rejection_cause IS NULL AND external_reason_codes IS NULL)
 OR (state = 'SETTLED' AND rejection_cause IS NULL)
 OR (state = 'REJECTED' AND (
        (rejection_cause = 'INSUFFICIENT_FUNDS' AND external_reason_codes IS NULL)
     OR (rejection_cause IS NULL AND cardinality(external_reason_codes) > 0)
    ))
)
```

The audit shape permits external codes on `PAYMENT_SETTLED`, requires them for receiver-originated `PAYMENT_REJECTED`, and forbids them on reservation or insufficient-funds rejection. Recreate `payment_audit_event_history` with current and legacy state/cause columns cast to `TEXT`. Keep legacy `payment_status` and `payment_rejection_reason` only because `payment_audit_event_legacy_v16` still uses them until Phase 3.

- [x] **Step 4: Map and validate the inbound command before repository access**

```java
public IncomingStatusReportCommand toStatusReport(PaymentStatusReport report) {
    StatusReportOutcome outcome = switch (report.getStatus()) {
        case ACCEPTED_IN_PROCESS -> StatusReportOutcome.ACCEPTED;
        case REJECTED -> StatusReportOutcome.REJECTED;
        case PAYMENT_STATUS_UNSPECIFIED, UNRECOGNIZED ->
                throw new IllegalArgumentException("Unsupported internal payment status: " + report.getStatus());
    };
    return new IncomingStatusReportCommand(
            report.getPaymentId(),
            outcome,
            report.getReasonsList().stream()
                    .map(reason -> StatusReasonCode.of(reason.getCode()))
                    .toList()
    );
}
```

`InboundPaymentMessageDecoder.toStatusReport` catches mapper `IllegalArgumentException` and wraps it in `InvalidInboundPayloadException("Invalid payment status report", cause)`. It does not catch other runtime failures. This preserves existing DLQ-before-ACK behavior and prevents invalid RJCT from reaching the repository.

- [x] **Step 5: Refactor state/rejection records and transition acquisition**

```java
public enum PaymentRejectionCause { INSUFFICIENT_FUNDS }

public record PaymentRejection(
        PaymentTransactionCommand payment,
        PaymentRejectionCause internalCause,
        List<StatusReasonCode> externalReasonCodes
) {
    public PaymentRejection {
        Objects.requireNonNull(payment, "Rejected payment is required");
        externalReasonCodes = StatusReasonCode.normalize(externalReasonCodes);
        if ((internalCause == null) == externalReasonCodes.isEmpty()) {
            throw new IllegalArgumentException("Rejection requires exactly one internal or external reason origin");
        }
    }

    public static PaymentRejection insufficientFunds(PaymentTransactionCommand payment) {
        return new PaymentRejection(payment, PaymentRejectionCause.INSUFFICIENT_FUNDS, List.of());
    }

    public static PaymentRejection receiverRejected(
            PaymentTransactionCommand payment,
            List<StatusReasonCode> codes
    ) {
        return new PaymentRejection(payment, null, codes);
    }
}
```

Refactor `IncomingStatusReportPersistence` to apply exactly this matrix:

```text
WAITING_ACCEPTANCE + ACCEPTED -> SETTLED, credit receiver
WAITING_ACCEPTANCE + REJECTED -> REJECTED external, release payer
SETTLED + identical ACCEPTED/codes -> no-op
external REJECTED + identical REJECTED/codes -> no-op
every other terminal combination -> divergent
```

`LOCK_PAYMENTS_SQL` returns `state`, `rejection_cause` and `external_reason_codes`. Batch-local homogeneity compares authenticated PSP, outcome and normalized codes. Group candidates by a record containing `(PaymentState resultingState, PaymentRejectionCause internalCause, List<StatusReasonCode> externalReasonCodes)` and execute:

```sql
UPDATE payment_transaction_entity
SET state = ?::payment_state,
    rejection_cause = ?::payment_rejection_cause,
    external_reason_codes = ?::text[]
WHERE payment_id = ANY (?::text[])
  AND state = 'WAITING_ACCEPTANCE'::payment_state;
```

Only rows acquired by that update contribute to aggregate balance deltas and returned settlements/rejections. Keep current deterministic participant lock ordering.

- [x] **Step 6: Change audit facts to the new vocabulary**

```java
public record PaymentAuditEvent(
        String paymentId,
        PaymentAuditEventType eventType,
        PaymentState previousState,
        PaymentState resultingState,
        Long amountCents,
        String senderIspb,
        String receiverIspb,
        Long senderDeltaCents,
        Long receiverDeltaCents,
        PaymentRejectionCause rejectionCause,
        List<StatusReasonCode> externalReasonCodes
) {
    public PaymentAuditEvent {
        externalReasonCodes = StatusReasonCode.normalize(externalReasonCodes);
        if (rejectionCause != null && !externalReasonCodes.isEmpty()) {
            throw new IllegalArgumentException("Audit fact cannot mix internal and external rejection reasons");
        }
    }
}
```

`PaymentAuditService.storeOutcomeEvents` accepts `List<PaymentSettlement>`. Settlement facts carry the settlement codes; receiver rejection facts carry external codes; ingress insufficient-funds facts carry only the internal cause.

Group events by `List<StatusReasonCode>`. For each group, keep the existing scalar `unnest` arrays and bind the group's codes once:

```sql
INSERT INTO payment_audit_event (
    payment_id, event_type, previous_state, resulting_state,
    amount_cents, sender_ispb, receiver_ispb,
    sender_delta_cents, receiver_delta_cents,
    rejection_cause, external_reason_codes
)
SELECT
    event.payment_id, event.event_type, event.previous_state, event.resulting_state,
    event.amount_cents, event.sender_ispb, event.receiver_ispb,
    event.sender_delta_cents, event.receiver_delta_cents,
    event.rejection_cause, ?::text[]
FROM unnest(
    ?::text[], ?::payment_audit_event_type[], ?::payment_state[], ?::payment_state[],
    ?::bigint[], ?::text[], ?::text[], ?::bigint[], ?::bigint[], ?::payment_rejection_cause[]
) AS event(
    payment_id, event_type, previous_state, resulting_state,
    amount_cents, sender_ispb, receiver_ispb,
    sender_delta_cents, receiver_delta_cents, rejection_cause
)
```

Bind SQL `NULL` for an empty code list and a PostgreSQL `text[]` otherwise. Do not add JSON, a child table or one insert per event.

- [x] **Step 7: Replace output reuse with notification-specific items**

```java
private void addStatus(
        Map<String, List<NotificationStatusItem>> byRecipient,
        String recipientIspb,
        String paymentId,
        NotificationStatus status,
        List<StatusReasonCode> reasonCodes
) {
    byRecipient.computeIfAbsent(recipientIspb, ignored -> new ArrayList<>())
            .add(new NotificationStatusItem(paymentId, status, reasonCodes));
}
```

For each settlement add `ACCC` to receiver and `ACSC` to sender with the same external codes. For rejection add `RJCT` to sender with external codes, or `[StatusReasonCode.of("AM04")]` when `internalCause == INSUFFICIENT_FUNDS`. `NotificationPayloadFactory` writes `status.name()` to `TxSts`, emits one `StsRsnInf/Rsn/Cd` per code and never emits `AddtlInf`.

`PaymentTransactionProcessorService` passes `settlements` unchanged to audit and notification services. JFR records use `settlement.payment().getPaymentId()`. Do not reconstruct codes or query persistence again.

- [x] **Step 8: Delete mixed models and run the complete focused slice**

Delete `PaymentStatus`, `Reason` and `StatusReportCommand` after all consumers use the new types.

Run: `cd spi && ./mvnw -q -Dtest=PaymentMessageConsumerTest,PaymentTransactionStorageSchemaIntegrationTest,JpaAdapterIntegrationTest,JpaAdapterTest,ConcurrentParticipantBalanceIntegrationTest,PaymentAuditServiceTest,PaymentAuditRepositoryIntegrationTest,PaymentAuditBusinessFactsMigrationIntegrationTest,NotificationObligationServiceTest,NotificationPayloadFactoryTest,PaymentTransactionProcessorServiceTest test`

Expected: PASS.

Run: `rg -n "br\.kauan\.spi\.domain\.entity\.status\.(PaymentStatus|Reason|StatusReportCommand)|ACCEPTED_AND_SETTLED_FOR|ACCEPTED_IN_PROCESS" spi/src/main`

Expected: no matches. Protobuf `br.kauan.pix.internal.v1.PaymentStatus` remains only at the input boundary.

- [x] **Step 9: Commit the vertical status slice**

```bash
git add spi/src/main spi/src/test
git commit -m "refactor(spi): separate payment status semantics"
```

### Task 3: Prove atomicity, replay and rollback end to end

**Files:**
- Modify: `spi/src/test/java/br/kauan/spi/domain/services/TransactionalOutboxIntegrationTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/domain/services/TransactionalOutboxRollbackIntegrationTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/domain/services/ConcurrentParticipantBalanceIntegrationTest.java`
- Modify: `spi/src/test/java/br/kauan/spi/adapter/input/kafka/consumer/PaymentMessageConsumerTest.java`
- Modify: `docs/board/Atividades/agora/simplificar-arquitetura-spi.md`

**Interfaces:**
- Consumes: final contracts from Tasks 1–2.
- Produces: executable evidence for every Fase 1 gate while leaving Fases 2–7 open.

- [x] **Step 1: Complete the semantic integration matrix**

Ensure tests assert all rows, balances and outbox payloads for:

```text
happy path: WAITING_ACCEPTANCE -> SETTLED, one receiver credit, one settlement audit, ACSC + ACCC
receiver RJCT: WAITING_ACCEPTANCE -> REJECTED, one payer release, one rejection audit, payer RJCT with original codes
insufficient funds: internal REJECTED, no acceptance request, payer RJCT AM04
identical accepted replay: no state/balance/audit/outbox change
identical rejected replay: no state/balance/audit/outbox change
description-only replay: identical
status/code divergence: divergent result and no state/balance/audit/outbox change
unauthorized PSP: unauthorized result and no state/balance/audit/outbox change
invalid RJCT without code: invalid-payload DLQ before processor call
concurrent identical PACS.002: exactly one terminal financial transition
audit or outbox failure: state and balance roll back
```

- [x] **Step 2: Run the semantic integration suite**

Run: `cd spi && ./mvnw -q -Dtest=TransactionalOutboxIntegrationTest,TransactionalOutboxRollbackIntegrationTest,ConcurrentParticipantBalanceIntegrationTest,PaymentMessageConsumerTest test`

Expected: PASS.

- [x] **Step 3: Run the complete SPI suite**

Run: `cd spi && ./mvnw test`

Expected: `BUILD SUCCESS`, including Flyway and PostgreSQL integration tests.

- [x] **Step 4: Run structural checks**

Run: `git diff --check`

Run: `rg -n "ACCEPTED_AND_SETTLED|ACCEPTED_IN_PROCESS|ACCEPTED_AND_SETTLED_FOR|br\.kauan\.spi\.domain\.entity\.status\.PaymentStatus|class Reason" spi/src/main`

Expected: no whitespace errors; the protobuf constant `ACCEPTED_IN_PROCESS` may occur only inside `InternalPaymentMessageMapper`.

- [x] **Step 5: Update only Fase 1 in the roadmap**

Mark Fase 1 complete in `docs/board/Atividades/agora/simplificar-arquitetura-spi.md`. Record the implementation commits, translation table, invalid-RJCT rule and replay identity. Keep Fases 2–7 open.

- [x] **Step 6: Commit verification and roadmap**

```bash
git add spi/src/test docs/board/Atividades/agora/simplificar-arquitetura-spi.md
git commit -m "test(spi): prove explicit status semantics"
```

### Task 4: Run the external functional gate

**Files:**
- No production changes expected.
- Generated evidence remains ignored under `load-test/results/`.

**Interfaces:**
- Consumes: `mixed-outcomes-smoke`.
- Produces: external confirmation that happy path remains `ACSC` and insufficient funds remains `RJCT/AM04`.

- [x] **Step 1: Recreate the environment through the supported preparer**

Run: `cd load-test && ./prepare-environment.sh`

Expected: stack recreated and readiness succeeds; do not prepare services ad hoc.

- [x] **Step 2: Run the short functional workload**

Run: `cd load-test && ./run-load-test.sh --profile mixed-outcomes-smoke phase1-status-contract`

Expected: report observes HTTP 2xx, payer `ACSC` for happy path, payer `RJCT` with `AM04` for insufficient funds, and no contradictory outcomes.

- [x] **Step 3: Inspect final repository state**

Run: `git status --short && git diff --check`

Expected: only intentional Fase 1 and pre-existing user changes; no generated load-test evidence staged.
