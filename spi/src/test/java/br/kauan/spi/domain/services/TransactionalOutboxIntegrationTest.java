package br.kauan.spi.domain.services;

import br.kauan.spi.adapter.output.notification.OutboundNotificationPublisher;
import br.kauan.spi.domain.entity.commons.Money;
import br.kauan.spi.domain.entity.security.AuthenticatedPaymentRequest;
import br.kauan.spi.domain.entity.security.AuthenticatedStatusReport;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
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
        jdbcTemplate.update("DELETE FROM outbound_notification WHERE payment_id LIKE 'E2E-TX-OUTBOX-%'");
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

        assertThat(paymentStatus(payment.getPaymentId())).isEqualTo(PaymentStatus.WAITING_ACCEPTANCE.name());
        assertThat(auditRows(payment.getPaymentId())).containsExactly(new AuditRow(
                "PAYMENT_CREATED",
                null,
                PaymentStatus.WAITING_ACCEPTANCE.name(),
                1_000L,
                SENDER_ISPB,
                RECEIVER_ISPB,
                null,
                null,
                null
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

        assertThat(paymentStatus(payment.getPaymentId())).isEqualTo(PaymentStatus.REJECTED.name());
        assertThat(paymentRejectionReason(payment.getPaymentId())).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(balance(SENDER_ISPB)).isEqualByComparingTo("0.00");
        assertThat(balance(RECEIVER_ISPB)).isEqualByComparingTo("500.00");
        assertThat(auditRows(payment.getPaymentId())).containsExactly(new AuditRow(
                "PAYMENT_CREATED",
                null,
                PaymentStatus.REJECTED.name(),
                1_000L,
                SENDER_ISPB,
                RECEIVER_ISPB,
                null,
                null,
                "INSUFFICIENT_FUNDS"
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
        insertPayment(payment, PaymentStatus.WAITING_ACCEPTANCE);

        processor.processStatusReports(authenticatedReports(
                payment.getPaymentId(),
                PaymentStatus.REJECTED
        ));

        assertThat(paymentStatus(payment.getPaymentId())).isEqualTo(PaymentStatus.REJECTED.name());
        assertThat(auditRows(payment.getPaymentId())).containsExactly(new AuditRow(
                "PAYMENT_STATUS_CHANGED",
                PaymentStatus.WAITING_ACCEPTANCE.name(),
                PaymentStatus.REJECTED.name(),
                null,
                null,
                null,
                null,
                null,
                null
        ));
        assertThat(outboxRows(payment.getPaymentId()))
                .containsExactly(new OutboundNotificationRow("REJECTED_NOTIFICATION", SENDER_ISPB, "RJCT"));
        assertThat(balance(SENDER_ISPB)).isEqualByComparingTo("1000.00");
        assertThat(balance(RECEIVER_ISPB)).isEqualByComparingTo("500.00");
    }

    @Test
    void settlementBalancesStatusAndBothObligationsCommitTogether() {
        PaymentTransactionCommand payment = payment("E2E-TX-OUTBOX-SETTLED");
        insertFunds(SENDER_ISPB, "990.00");
        insertFunds(RECEIVER_ISPB, "500.00");
        insertPayment(payment, PaymentStatus.WAITING_ACCEPTANCE);

        processor.processStatusReports(authenticatedReports(
                payment.getPaymentId(),
                PaymentStatus.ACCEPTED_IN_PROCESS
        ));

        assertThat(paymentStatus(payment.getPaymentId())).isEqualTo(PaymentStatus.ACCEPTED_AND_SETTLED.name());
        assertThat(balance(SENDER_ISPB)).isEqualByComparingTo("990.00");
        assertThat(balance(RECEIVER_ISPB)).isEqualByComparingTo("510.00");
        assertThat(auditRows(payment.getPaymentId())).containsExactlyInAnyOrder(
                new AuditRow(
                        "PAYMENT_STATUS_CHANGED",
                        PaymentStatus.WAITING_ACCEPTANCE.name(),
                        PaymentStatus.ACCEPTED_AND_SETTLED.name(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                ),
                new AuditRow(
                        "SETTLEMENT_APPLIED",
                        null,
                        null,
                        1_000L,
                        SENDER_ISPB,
                        RECEIVER_ISPB,
                        -1_000L,
                        1_000L,
                        null
                )
        );
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
        insertPayment(payment, PaymentStatus.WAITING_ACCEPTANCE);

        processor.processStatusReports(List.of(
                authenticatedReport(0, payment.getPaymentId(), PaymentStatus.ACCEPTED_IN_PROCESS),
                authenticatedReport(1, payment.getPaymentId(), PaymentStatus.ACCEPTED_IN_PROCESS)
        ));

        assertThat(paymentStatus(payment.getPaymentId())).isEqualTo(PaymentStatus.ACCEPTED_AND_SETTLED.name());
        assertThat(balance(SENDER_ISPB)).isEqualByComparingTo("990.00");
        assertThat(balance(RECEIVER_ISPB)).isEqualByComparingTo("510.00");
        assertThat(auditRows(payment.getPaymentId())).containsExactlyInAnyOrder(
                new AuditRow(
                        "PAYMENT_STATUS_CHANGED",
                        PaymentStatus.WAITING_ACCEPTANCE.name(),
                        PaymentStatus.ACCEPTED_AND_SETTLED.name(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                ),
                new AuditRow(
                        "SETTLEMENT_APPLIED",
                        null,
                        null,
                        1_000L,
                        SENDER_ISPB,
                        RECEIVER_ISPB,
                        -1_000L,
                        1_000L,
                        null
                )
        );
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

        jdbcTemplate.update("DELETE FROM outbound_notification WHERE payment_id = ?", payment.getPaymentId());
        processor.processTransactions(request);

        assertThat(outboxCount(payment.getPaymentId())).isZero();
        assertThat(auditRows(payment.getPaymentId())).hasSize(1);
    }

    private List<AuthenticatedPaymentRequest> authenticatedPayments(PaymentTransactionCommand payment) {
        return List.of(new AuthenticatedPaymentRequest(0, SENDER_ISPB, payment));
    }

    private List<AuthenticatedStatusReport> authenticatedReports(String paymentId, PaymentStatus status) {
        return List.of(authenticatedReport(0, paymentId, status));
    }

    private AuthenticatedStatusReport authenticatedReport(int sourceOrdinal, String paymentId, PaymentStatus status) {
        return new AuthenticatedStatusReport(
                sourceOrdinal,
                RECEIVER_ISPB,
                StatusReportCommand.builder()
                        .originalPaymentId(paymentId)
                        .status(status)
                        .build()
        );
    }

    private List<OutboundNotificationRow> outboxRows(String paymentId) {
        return jdbcTemplate.query(
                """
                        SELECT event_type, recipient_ispb, notification_status
                        FROM outbound_notification
                        WHERE payment_id = ?
                        ORDER BY recipient_ispb
                        """,
                (resultSet, rowNumber) -> new OutboundNotificationRow(
                        resultSet.getString(1),
                        resultSet.getString(2),
                        resultSet.getString(3)
                ),
                paymentId
        );
    }

    private List<AuditRow> auditRows(String paymentId) {
        return jdbcTemplate.query(
                """
                        SELECT
                            event_type,
                            previous_status,
                            resulting_status,
                            amount_cents,
                            sender_ispb,
                            receiver_ispb,
                            sender_delta_cents,
                            receiver_delta_cents,
                            reason
                        FROM payment_audit_event
                        WHERE payment_id = ?
                        """,
                (resultSet, rowNumber) -> new AuditRow(
                        resultSet.getString("event_type"),
                        resultSet.getString("previous_status"),
                        resultSet.getString("resulting_status"),
                        resultSet.getObject("amount_cents", Long.class),
                        resultSet.getString("sender_ispb"),
                        resultSet.getString("receiver_ispb"),
                        resultSet.getObject("sender_delta_cents", Long.class),
                        resultSet.getObject("receiver_delta_cents", Long.class),
                        resultSet.getString("reason")
                ),
                paymentId
        );
    }

    private int outboxCount(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbound_notification WHERE payment_id = ?",
                Integer.class,
                paymentId
        );
    }

    private String paymentStatus(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM payment_transaction_entity WHERE payment_id = ?",
                String.class,
                paymentId
        );
    }

    private String paymentRejectionReason(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT rejection_reason FROM payment_transaction_entity WHERE payment_id = ?",
                String.class,
                paymentId
        );
    }

    private String outboxPayload(String paymentId) {
        byte[] payload = jdbcTemplate.queryForObject(
                "SELECT payload FROM outbound_notification WHERE payment_id = ?",
                byte[].class,
                paymentId
        );
        return new String(payload, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void insertPayment(PaymentTransactionCommand payment, PaymentStatus status) {
        jdbcTemplate.update(
                """
                        INSERT INTO payment_transaction_entity (
                            payment_id,
                            amount_cents,
                            status,
                            sender_bank_code,
                            receiver_bank_code
                        ) VALUES (?, ?, ?, ?, ?)
                        """,
                payment.getPaymentId(),
                payment.getAmountCents(),
                status.name(),
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
            String reason
    ) {
    }
}
