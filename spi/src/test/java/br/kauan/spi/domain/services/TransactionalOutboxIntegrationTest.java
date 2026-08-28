package br.kauan.spi.domain.services;

import br.kauan.spi.adapter.output.notification.OutboundNotificationPublisher;
import br.kauan.spi.domain.entity.commons.Money;
import br.kauan.spi.domain.entity.security.AuthenticatedPaymentRequest;
import br.kauan.spi.domain.entity.security.AuthenticatedStatusReport;
import br.kauan.spi.domain.entity.status.IncomingStatusReportCommand;
import br.kauan.spi.domain.entity.status.PaymentState;
import br.kauan.spi.domain.entity.status.StatusReasonCode;
import br.kauan.spi.domain.entity.status.StatusReportOutcome;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.input.PaymentTransactionProcessorUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TransactionalOutboxIntegrationTest {

    private static final String SENDER_ISPB = "71111111";
    private static final String RECEIVER_ISPB = "72222222";

    @MockitoBean
    private OutboundNotificationPublisher outboundNotificationPublisher;

    @Autowired
    private PaymentTransactionProcessorUseCase processor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanFixtures() {
        jdbcTemplate.update("DELETE FROM payment_audit_event WHERE payment_id LIKE 'E2E-TX-OUTBOX-%'");
        jdbcTemplate.update(
                "DELETE FROM notification_outbox WHERE convert_from(payload, 'UTF8') LIKE '%E2E-TX-OUTBOX-%'"
        );
        jdbcTemplate.update("DELETE FROM payment_transaction_entity WHERE payment_id LIKE 'E2E-TX-OUTBOX-%'");
        jdbcTemplate.update(
                "DELETE FROM participant_balance_entity WHERE bank_code IN (?, ?)",
                SENDER_ISPB,
                RECEIVER_ISPB
        );
    }

    @Test
    void newPaymentAndAcceptanceObligationCommitTogether() {
        PaymentTransactionCommand payment = payment("E2E-TX-OUTBOX-NEW");
        insertFunds(SENDER_ISPB, "1000.00");
        insertFunds(RECEIVER_ISPB, "500.00");

        processor.processTransactions(authenticatedPayments(payment));

        assertThat(paymentState(payment.getPaymentId())).isEqualTo(PaymentState.WAITING_ACCEPTANCE.name());
        assertThat(auditRows(payment.getPaymentId())).containsExactly(new AuditRow(
                "PAYMENT_RESERVED",
                null,
                PaymentState.WAITING_ACCEPTANCE.name(),
                1_000L,
                SENDER_ISPB,
                RECEIVER_ISPB,
                -1_000L,
                null,
                null,
                List.of()
        ));
        assertThat(outboxRows(payment.getPaymentId()))
                .containsExactly(new OutboundNotificationRow("ACCEPTANCE_REQUEST", RECEIVER_ISPB, null));
        assertThat(balance(SENDER_ISPB)).isEqualByComparingTo("990.00");
    }

    @Test
    void ingressInsufficientFundsCommitRejectionAuditAndPayerObligationTogether() {
        PaymentTransactionCommand payment = payment("E2E-TX-OUTBOX-INGRESS-NO-FUNDS");
        insertFunds(SENDER_ISPB, "0.00");
        insertFunds(RECEIVER_ISPB, "500.00");

        processor.processTransactions(authenticatedPayments(payment));

        assertThat(paymentState(payment.getPaymentId())).isEqualTo(PaymentState.REJECTED.name());
        assertThat(paymentRejectionCause(payment.getPaymentId())).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(balance(SENDER_ISPB)).isEqualByComparingTo("0.00");
        assertThat(balance(RECEIVER_ISPB)).isEqualByComparingTo("500.00");
        assertThat(auditRows(payment.getPaymentId())).containsExactly(new AuditRow(
                "PAYMENT_REJECTED",
                null,
                PaymentState.REJECTED.name(),
                1_000L,
                SENDER_ISPB,
                RECEIVER_ISPB,
                null,
                null,
                "INSUFFICIENT_FUNDS",
                List.of()
        ));
        assertThat(outboxRows(payment.getPaymentId()))
                .containsExactly(new OutboundNotificationRow("REJECTED_NOTIFICATION", SENDER_ISPB, "RJCT"));
        assertThat(outboxPayload(payment.getPaymentId()))
                .contains("\"TxSts\":\"RJCT\"")
                .contains("\"Cd\":\"AM04\"");
    }

    @Test
    void rejectionAndItsObligationCommitTogether() {
        PaymentTransactionCommand payment = payment("E2E-TX-OUTBOX-REJECTED");
        insertFunds(SENDER_ISPB, "990.00");
        insertFunds(RECEIVER_ISPB, "500.00");
        insertPayment(payment, PaymentState.WAITING_ACCEPTANCE);

        processor.processStatusReports(authenticatedReports(
                payment.getPaymentId(),
                StatusReportOutcome.REJECTED
        ));

        assertThat(paymentState(payment.getPaymentId())).isEqualTo(PaymentState.REJECTED.name());
        assertThat(auditRows(payment.getPaymentId())).containsExactly(new AuditRow(
                "PAYMENT_REJECTED",
                PaymentState.WAITING_ACCEPTANCE.name(),
                PaymentState.REJECTED.name(),
                1_000L,
                SENDER_ISPB,
                RECEIVER_ISPB,
                1_000L,
                null,
                null,
                List.of("AB03")
        ));
        assertThat(outboxRows(payment.getPaymentId()))
                .containsExactly(new OutboundNotificationRow("REJECTED_NOTIFICATION", SENDER_ISPB, "RJCT"));
        assertThat(outboxPayload(payment.getPaymentId()))
                .contains("\"Cd\":\"AB03\"")
                .doesNotContain("AddtlInf");
        assertThat(balance(SENDER_ISPB)).isEqualByComparingTo("1000.00");
        assertThat(balance(RECEIVER_ISPB)).isEqualByComparingTo("500.00");
    }

    @Test
    void settlementBalancesStatusAndBothObligationsCommitTogether() {
        PaymentTransactionCommand payment = payment("E2E-TX-OUTBOX-SETTLED");
        insertFunds(SENDER_ISPB, "990.00");
        insertFunds(RECEIVER_ISPB, "500.00");
        insertPayment(payment, PaymentState.WAITING_ACCEPTANCE);

        processor.processStatusReports(authenticatedReports(
                payment.getPaymentId(),
                StatusReportOutcome.ACCEPTED
        ));

        assertThat(paymentState(payment.getPaymentId())).isEqualTo(PaymentState.SETTLED.name());
        assertThat(balance(SENDER_ISPB)).isEqualByComparingTo("990.00");
        assertThat(balance(RECEIVER_ISPB)).isEqualByComparingTo("510.00");
        assertThat(auditRows(payment.getPaymentId())).containsExactly(new AuditRow(
                "PAYMENT_SETTLED",
                PaymentState.WAITING_ACCEPTANCE.name(),
                PaymentState.SETTLED.name(),
                1_000L,
                SENDER_ISPB,
                RECEIVER_ISPB,
                null,
                1_000L,
                null,
                List.of()
        ));
        assertThat(outboxRows(payment.getPaymentId()))
                .containsExactlyInAnyOrder(
                        new OutboundNotificationRow("SETTLED_NOTIFICATION", SENDER_ISPB, "ACSC"),
                        new OutboundNotificationRow("SETTLED_NOTIFICATION", RECEIVER_ISPB, "ACCC")
                );
    }

    @Test
    void repeatedAcceptedStatusCreatesOneLogicalSettlement() {
        PaymentTransactionCommand payment = payment("E2E-TX-OUTBOX-SETTLED-REPEATED");
        insertFunds(SENDER_ISPB, "990.00");
        insertFunds(RECEIVER_ISPB, "500.00");
        insertPayment(payment, PaymentState.WAITING_ACCEPTANCE);

        processor.processStatusReports(List.of(
                authenticatedReport(0, payment.getPaymentId(), StatusReportOutcome.ACCEPTED),
                authenticatedReport(1, payment.getPaymentId(), StatusReportOutcome.ACCEPTED)
        ));

        assertThat(paymentState(payment.getPaymentId())).isEqualTo(PaymentState.SETTLED.name());
        assertThat(balance(SENDER_ISPB)).isEqualByComparingTo("990.00");
        assertThat(balance(RECEIVER_ISPB)).isEqualByComparingTo("510.00");
        assertThat(auditRows(payment.getPaymentId())).containsExactly(new AuditRow(
                "PAYMENT_SETTLED",
                PaymentState.WAITING_ACCEPTANCE.name(),
                PaymentState.SETTLED.name(),
                1_000L,
                SENDER_ISPB,
                RECEIVER_ISPB,
                null,
                1_000L,
                null,
                List.of()
        ));
        assertThat(outboxRows(payment.getPaymentId()))
                .containsExactlyInAnyOrder(
                        new OutboundNotificationRow("SETTLED_NOTIFICATION", SENDER_ISPB, "ACSC"),
                        new OutboundNotificationRow("SETTLED_NOTIFICATION", RECEIVER_ISPB, "ACCC")
                );
    }

    @Test
    void waitingAcceptanceReplayIsANoOpEvenWhenTheOriginalOutboxRowIsMissing() {
        PaymentTransactionCommand payment = payment("E2E-TX-OUTBOX-REPLAY");
        insertFunds(SENDER_ISPB, "1000.00");
        insertFunds(RECEIVER_ISPB, "500.00");
        List<AuthenticatedPaymentRequest> request = authenticatedPayments(payment);
        processor.processTransactions(request);
        processor.processTransactions(request);

        assertThat(outboxCount(payment.getPaymentId())).isEqualTo(1);
        assertThat(auditRows(payment.getPaymentId())).hasSize(1);

        jdbcTemplate.update(
                "DELETE FROM notification_outbox WHERE convert_from(payload, 'UTF8') LIKE ?",
                "%" + payment.getPaymentId() + "%"
        );
        processor.processTransactions(request);

        assertThat(outboxCount(payment.getPaymentId())).isZero();
        assertThat(auditRows(payment.getPaymentId())).hasSize(1);
    }

    private List<AuthenticatedPaymentRequest> authenticatedPayments(PaymentTransactionCommand payment) {
        return List.of(new AuthenticatedPaymentRequest(0, SENDER_ISPB, payment));
    }

    private List<AuthenticatedStatusReport> authenticatedReports(
            String paymentId,
            StatusReportOutcome outcome
    ) {
        return List.of(authenticatedReport(0, paymentId, outcome));
    }

    private AuthenticatedStatusReport authenticatedReport(
            int sourceOrdinal,
            String paymentId,
            StatusReportOutcome outcome
    ) {
        return new AuthenticatedStatusReport(
                sourceOrdinal,
                RECEIVER_ISPB,
                new IncomingStatusReportCommand(
                        paymentId,
                        outcome,
                        outcome == StatusReportOutcome.REJECTED
                                ? List.of(StatusReasonCode.of("AB03"))
                                : List.of()
                )
        );
    }

    private List<OutboundNotificationRow> outboxRows(String paymentId) {
        return jdbcTemplate.query(
                """
                        SELECT recipient_ispb, payload
                        FROM notification_outbox
                        WHERE convert_from(payload, 'UTF8') LIKE ?
                        ORDER BY recipient_ispb
                        """,
                (resultSet, rowNumber) -> outboundNotificationRow(
                        resultSet.getString("recipient_ispb"),
                        new String(resultSet.getBytes("payload"), java.nio.charset.StandardCharsets.UTF_8)
                ),
                "%" + paymentId + "%"
        );
    }

    private OutboundNotificationRow outboundNotificationRow(String recipientIspb, String payload) {
        if (!payload.contains("\"TxSts\"")) {
            return new OutboundNotificationRow("ACCEPTANCE_REQUEST", recipientIspb, null);
        }
        if (payload.contains("\"TxSts\":\"RJCT\"")) {
            return new OutboundNotificationRow("REJECTED_NOTIFICATION", recipientIspb, "RJCT");
        }
        String status = payload.contains("\"TxSts\":\"ACCC\"") ? "ACCC" : "ACSC";
        return new OutboundNotificationRow("SETTLED_NOTIFICATION", recipientIspb, status);
    }

    private List<AuditRow> auditRows(String paymentId) {
        return jdbcTemplate.query(
                """
                        SELECT
                            event_type,
                            previous_state,
                            resulting_state,
                            amount_cents,
                            sender_ispb,
                            receiver_ispb,
                            sender_delta_cents,
                            receiver_delta_cents,
                            rejection_cause,
                            external_reason_codes
                        FROM payment_audit_event
                        WHERE payment_id = ?
                        """,
                (resultSet, rowNumber) -> new AuditRow(
                        resultSet.getString("event_type"),
                        resultSet.getString("previous_state"),
                        resultSet.getString("resulting_state"),
                        resultSet.getObject("amount_cents", Long.class),
                        resultSet.getString("sender_ispb"),
                        resultSet.getString("receiver_ispb"),
                        resultSet.getObject("sender_delta_cents", Long.class),
                        resultSet.getObject("receiver_delta_cents", Long.class),
                        resultSet.getString("rejection_cause"),
                        resultSet.getArray("external_reason_codes") == null
                                ? List.of()
                                : List.of((String[]) resultSet.getArray("external_reason_codes").getArray())
                ),
                paymentId
        );
    }

    private int outboxCount(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE convert_from(payload, 'UTF8') LIKE ?",
                Integer.class,
                "%" + paymentId + "%"
        );
    }

    private String paymentState(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM payment_transaction_entity WHERE payment_id = ?",
                String.class,
                paymentId
        );
    }

    private String paymentRejectionCause(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT rejection_cause FROM payment_transaction_entity WHERE payment_id = ?",
                String.class,
                paymentId
        );
    }

    private String outboxPayload(String paymentId) {
        byte[] payload = jdbcTemplate.queryForObject(
                "SELECT payload FROM notification_outbox WHERE convert_from(payload, 'UTF8') LIKE ?",
                byte[].class,
                "%" + paymentId + "%"
        );
        return new String(payload, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void insertPayment(PaymentTransactionCommand payment, PaymentState state) {
        jdbcTemplate.update(
                """
                        INSERT INTO payment_transaction_entity (
                            payment_id,
                            amount_cents,
                            state,
                            sender_bank_code,
                            receiver_bank_code
                        ) VALUES (?, ?, ?::payment_state, ?, ?)
                        """,
                payment.getPaymentId(),
                payment.getAmountCents(),
                state.name(),
                SENDER_ISPB,
                RECEIVER_ISPB
        );
    }

    private void insertFunds(String bankCode, String balance) {
        long balanceCents = Money.toCents(new BigDecimal(balance));
        jdbcTemplate.update(
                """
                        INSERT INTO participant_balance_entity (bank_code, balance_cents)
                        VALUES (?, ?)
                        ON CONFLICT (bank_code) DO UPDATE SET balance_cents = EXCLUDED.balance_cents
                        """,
                bankCode,
                balanceCents
        );
    }

    private BigDecimal balance(String bankCode) {
        Long balanceCents = jdbcTemplate.queryForObject(
                "SELECT COALESCE(balance_cents, 0) FROM participant_balance_entity WHERE bank_code = ?",
                Long.class,
                bankCode
        );
        return Money.toDecimal(balanceCents == null ? 0L : balanceCents);
    }

    private PaymentTransactionCommand payment(String paymentId) {
        return PaymentTransactionCommand.builder()
                .paymentId(paymentId)
                .amountCents(1_000L)
                .currency("BRL")
                .description("transactional outbox test")
                .sender(party(SENDER_ISPB))
                .receiver(party(RECEIVER_ISPB))
                .build();
    }

    private Party party(String bankCode) {
        return Party.builder()
                .name("Name")
                .taxId("123")
                .pixKey("pix-" + bankCode)
                .account(BankAccount.builder()
                        .bankCode(bankCode)
                        .number("1")
                        .branch("1")
                        .type(BankAccountType.CHECKING)
                        .build())
                .build();
    }

    private record OutboundNotificationRow(
            String eventType,
            String recipientIspb,
            String notificationStatus
    ) {
    }

    private record AuditRow(
            String eventType,
            String previousStatus,
            String resultingStatus,
            Long amountCents,
            String senderIspb,
            String receiverIspb,
            Long senderDeltaCents,
            Long receiverDeltaCents,
            String rejectionCause,
            List<String> externalReasonCodes
    ) {
    }
}
