package br.kauan.spi.domain.services;

import br.kauan.spi.adapter.output.audit.PaymentAuditRepository;
import br.kauan.spi.adapter.output.notification.OutboundNotificationPublisher;
import br.kauan.spi.adapter.output.notification.OutboundNotificationRepository;
import br.kauan.spi.domain.entity.commons.Money;
import br.kauan.spi.domain.entity.security.AuthenticatedPaymentRequest;
import br.kauan.spi.domain.entity.security.AuthenticatedStatusReport;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.domain.services.notification.NotificationContentSerializer;
import br.kauan.spi.domain.services.notification.NotificationException;
import br.kauan.spi.port.input.PaymentTransactionProcessorUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest
class TransactionalOutboxRollbackIntegrationTest {

    private static final String SENDER_ISPB = "81111111";
    private static final String RECEIVER_ISPB = "82222222";

    @MockitoBean
    private OutboundNotificationPublisher outboundNotificationPublisher;

    @Autowired
    private PaymentTransactionProcessorUseCase processor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private OutboundNotificationRepository outboundNotificationRepository;

    @MockitoBean
    private PaymentAuditRepository auditRepository;

    @MockitoBean
    private NotificationContentSerializer contentSerializer;

    @BeforeEach
    void prepareFixture() {
        cleanFixtures();
        reset(auditRepository, outboundNotificationRepository, contentSerializer);
        when(contentSerializer.serialize(any()))
                .thenReturn("{}".getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void cleanFixtures() {
        jdbcTemplate.update("DELETE FROM payment_audit_event WHERE payment_id LIKE 'E2E-TX-ROLLBACK-%'");
        jdbcTemplate.update("DELETE FROM payment_transaction_entity WHERE payment_id LIKE 'E2E-TX-ROLLBACK-%'");
        jdbcTemplate.update(
                "DELETE FROM participant_balance_entity WHERE bank_code IN (?, ?)",
                SENDER_ISPB,
                RECEIVER_ISPB
        );
    }

    @Test
    void auditInsertFailureRollsBackNewPaymentBeforeOutboxWork() {
        PaymentTransactionCommand payment = payment("E2E-TX-ROLLBACK-AUDIT-NEW");
        insertFunds(SENDER_ISPB, "1000.00");
        insertFunds(RECEIVER_ISPB, "500.00");
        doThrow(new DataIntegrityViolationException("audit rejected insert"))
                .when(auditRepository).insertAll(anyList());

        assertThatThrownBy(() -> processor.processTransactions(authenticatedPayments(payment)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(paymentCount(payment.getPaymentId())).isZero();
        assertThat(balance(SENDER_ISPB)).isEqualByComparingTo("1000.00");
        verifyNoInteractions(outboundNotificationRepository);
    }

    @Test
    void auditDatabaseOutagePropagatesForInfrastructureRetryAndRollsBackNewPayment() {
        PaymentTransactionCommand payment = payment("E2E-TX-ROLLBACK-AUDIT-DATABASE-OUTAGE");
        insertFunds(SENDER_ISPB, "1000.00");
        insertFunds(RECEIVER_ISPB, "500.00");
        DataAccessResourceFailureException databaseFailure =
                new DataAccessResourceFailureException("audit database unavailable");
        doThrow(databaseFailure).when(auditRepository).insertAll(anyList());

        assertThatThrownBy(() -> processor.processTransactions(authenticatedPayments(payment)))
                .isSameAs(databaseFailure);

        assertThat(paymentCount(payment.getPaymentId())).isZero();
        verifyNoInteractions(outboundNotificationRepository);
    }

    @Test
    void auditInsertFailureRollsBackRejectionBeforeOutboxWork() {
        PaymentTransactionCommand payment = payment("E2E-TX-ROLLBACK-AUDIT-REJECTION");
        insertFunds(SENDER_ISPB, "990.00");
        insertFunds(RECEIVER_ISPB, "500.00");
        insertPayment(payment, PaymentStatus.WAITING_ACCEPTANCE);
        doThrow(new DataIntegrityViolationException("audit rejected insert"))
                .when(auditRepository).insertAll(anyList());

        assertThatThrownBy(() -> processor.processStatusReports(authenticatedReports(
                payment.getPaymentId(),
                PaymentStatus.REJECTED
        ))).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(paymentStatus(payment.getPaymentId())).isEqualTo(PaymentStatus.WAITING_ACCEPTANCE.name());
        assertThat(balance(SENDER_ISPB)).isEqualByComparingTo("990.00");
        verifyNoInteractions(outboundNotificationRepository);
    }

    @Test
    void auditInsertFailureRollsBackSettlementStatusAndBalancesBeforeOutboxWork() {
        PaymentTransactionCommand payment = payment("E2E-TX-ROLLBACK-AUDIT-SETTLEMENT");
        insertFunds(SENDER_ISPB, "990.00");
        insertFunds(RECEIVER_ISPB, "500.00");
        insertPayment(payment, PaymentStatus.WAITING_ACCEPTANCE);
        doThrow(new DataIntegrityViolationException("audit rejected insert"))
                .when(auditRepository).insertAll(anyList());

        assertThatThrownBy(() -> processor.processStatusReports(authenticatedReports(
                payment.getPaymentId(),
                PaymentStatus.ACCEPTED_IN_PROCESS
        ))).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(paymentStatus(payment.getPaymentId())).isEqualTo(PaymentStatus.WAITING_ACCEPTANCE.name());
        assertThat(balance(SENDER_ISPB)).isEqualByComparingTo("990.00");
        assertThat(balance(RECEIVER_ISPB)).isEqualByComparingTo("500.00");
        verifyNoInteractions(outboundNotificationRepository);
    }

    @Test
    void outboxInsertFailureRollsBackNewPayment() {
        PaymentTransactionCommand payment = payment("E2E-TX-ROLLBACK-NEW");
        insertFunds(SENDER_ISPB, "1000.00");
        insertFunds(RECEIVER_ISPB, "500.00");
        doThrow(new DataIntegrityViolationException("outbox rejected insert"))
                .when(outboundNotificationRepository).insertAll(anyList());

        assertThatThrownBy(() -> processor.processTransactions(authenticatedPayments(payment)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(paymentCount(payment.getPaymentId())).isZero();
        assertThat(balance(SENDER_ISPB)).isEqualByComparingTo("1000.00");
    }

    @Test
    void outboxInsertFailureRollsBackIngressInsufficientRejection() {
        PaymentTransactionCommand payment = payment("E2E-TX-ROLLBACK-INGRESS-NO-FUNDS");
        insertFunds(SENDER_ISPB, "0.00");
        insertFunds(RECEIVER_ISPB, "500.00");
        doThrow(new DataIntegrityViolationException("outbox rejected insert"))
                .when(outboundNotificationRepository).insertAll(anyList());

        assertThatThrownBy(() -> processor.processTransactions(authenticatedPayments(payment)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(paymentCount(payment.getPaymentId())).isZero();
        assertThat(balance(SENDER_ISPB)).isEqualByComparingTo("0.00");
        assertThat(balance(RECEIVER_ISPB)).isEqualByComparingTo("500.00");
    }

    @Test
    void serializationFailureRollsBackNewPaymentBeforeOutboxInsert() {
        PaymentTransactionCommand payment = payment("E2E-TX-ROLLBACK-SERIALIZATION");
        insertFunds(SENDER_ISPB, "1000.00");
        insertFunds(RECEIVER_ISPB, "500.00");
        when(contentSerializer.serialize(any()))
                .thenThrow(new NotificationException("serialization failed", new IllegalStateException("boom")));

        assertThatThrownBy(() -> processor.processTransactions(authenticatedPayments(payment)))
                .isInstanceOf(NotificationException.class);

        assertThat(paymentCount(payment.getPaymentId())).isZero();
        verifyNoInteractions(outboundNotificationRepository);
    }

    @Test
    void outboxDatabaseOutagePropagatesForInfrastructureRetryAndRollsBackNewPayment() {
        PaymentTransactionCommand payment = payment("E2E-TX-ROLLBACK-DATABASE-OUTAGE");
        insertFunds(SENDER_ISPB, "1000.00");
        insertFunds(RECEIVER_ISPB, "500.00");
        DataAccessResourceFailureException databaseFailure =
                new DataAccessResourceFailureException("database unavailable");
        doThrow(databaseFailure).when(outboundNotificationRepository).insertAll(anyList());

        assertThatThrownBy(() -> processor.processTransactions(authenticatedPayments(payment)))
                .isSameAs(databaseFailure);

        assertThat(paymentCount(payment.getPaymentId())).isZero();
    }

    @Test
    void outboxInsertFailureRollsBackRejection() {
        PaymentTransactionCommand payment = payment("E2E-TX-ROLLBACK-REJECTION");
        insertFunds(SENDER_ISPB, "990.00");
        insertFunds(RECEIVER_ISPB, "500.00");
        insertPayment(payment, PaymentStatus.WAITING_ACCEPTANCE);
        doThrow(new DataIntegrityViolationException("outbox rejected insert"))
                .when(outboundNotificationRepository).insertAll(anyList());

        assertThatThrownBy(() -> processor.processStatusReports(authenticatedReports(
                payment.getPaymentId(),
                PaymentStatus.REJECTED
        ))).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(paymentStatus(payment.getPaymentId())).isEqualTo(PaymentStatus.WAITING_ACCEPTANCE.name());
        assertThat(balance(SENDER_ISPB)).isEqualByComparingTo("990.00");
    }

    @Test
    void outboxInsertFailureRollsBackSettlementStatusAndBalances() {
        PaymentTransactionCommand payment = payment("E2E-TX-ROLLBACK-SETTLEMENT");
        insertFunds(SENDER_ISPB, "990.00");
        insertFunds(RECEIVER_ISPB, "500.00");
        insertPayment(payment, PaymentStatus.WAITING_ACCEPTANCE);
        doThrow(new DataIntegrityViolationException("outbox rejected insert"))
                .when(outboundNotificationRepository).insertAll(anyList());

        assertThatThrownBy(() -> processor.processStatusReports(authenticatedReports(
                payment.getPaymentId(),
                PaymentStatus.ACCEPTED_IN_PROCESS
        ))).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(paymentStatus(payment.getPaymentId())).isEqualTo(PaymentStatus.WAITING_ACCEPTANCE.name());
        assertThat(balance(SENDER_ISPB)).isEqualByComparingTo("990.00");
        assertThat(balance(RECEIVER_ISPB)).isEqualByComparingTo("500.00");
    }

    private List<AuthenticatedPaymentRequest> authenticatedPayments(PaymentTransactionCommand payment) {
        return List.of(new AuthenticatedPaymentRequest(0, SENDER_ISPB, payment));
    }

    private List<AuthenticatedStatusReport> authenticatedReports(String paymentId, PaymentStatus status) {
        return List.of(new AuthenticatedStatusReport(
                0,
                RECEIVER_ISPB,
                StatusReportCommand.builder()
                        .originalPaymentId(paymentId)
                        .status(status)
                        .build()
        ));
    }

    private int paymentCount(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_transaction_entity WHERE payment_id = ?",
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

    private void insertPayment(PaymentTransactionCommand payment, PaymentStatus status) {
        jdbcTemplate.update(
                """
                        INSERT INTO payment_transaction_entity (
                            payment_id,
                            amount_cents,
                            status,
                            sender_bank_code,
                            receiver_bank_code
                        ) VALUES (?, ?, ?::payment_status, ?, ?)
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
                .description("transactional outbox rollback test")
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
}
