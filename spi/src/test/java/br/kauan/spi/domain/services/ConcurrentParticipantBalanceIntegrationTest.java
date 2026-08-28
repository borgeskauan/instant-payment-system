package br.kauan.spi.domain.services;

import br.kauan.spi.adapter.output.notification.OutboundNotificationPublisher;
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
import br.kauan.spi.port.output.PaymentTransactionPersistenceResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ConcurrentParticipantBalanceIntegrationTest {

    private static final String SENDER_ISPB = "61111111";
    private static final String RECEIVER_ISPB = "62222222";
    private static final String PAYMENT_ID_PREFIX = "E2E-CONCURRENT-BALANCE-";

    @MockitoBean
    private OutboundNotificationPublisher outboundNotificationPublisher;

    @Autowired
    private PaymentTransactionProcessorUseCase processor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanFixtures() {
        jdbcTemplate.update("DELETE FROM payment_audit_event WHERE payment_id LIKE ?", PAYMENT_ID_PREFIX + "%");
        jdbcTemplate.update(
                "DELETE FROM notification_outbox WHERE convert_from(payload, 'UTF8') LIKE ?",
                "%" + PAYMENT_ID_PREFIX + "%"
        );
        jdbcTemplate.update("DELETE FROM payment_transaction_entity WHERE payment_id LIKE ?", PAYMENT_ID_PREFIX + "%");
        jdbcTemplate.update(
                "DELETE FROM participant_balance_entity WHERE bank_code IN (?, ?)",
                SENDER_ISPB,
                RECEIVER_ISPB
        );
    }

    @Test
    void concurrentIdenticalPaymentRequestsReserveExactlyOnce() throws Exception {
        insertBalance(SENDER_ISPB, 10_000L);
        insertBalance(RECEIVER_ISPB, 5_000L);
        PaymentTransactionCommand payment = payment(PAYMENT_ID_PREFIX + "REQUEST");
        Runnable submit = () -> processor.processTransactions(authenticatedPayments(payment));

        invokeConcurrently(submit, submit);

        assertThat(balanceCents(SENDER_ISPB)).isEqualTo(9_000L);
        assertThat(paymentCount(payment.getPaymentId())).isOne();
        assertThat(auditCount(payment.getPaymentId(), "PAYMENT_RESERVED")).isOne();
        assertThat(outboxCount(payment.getPaymentId(), "ACCEPTANCE_REQUEST")).isOne();
    }

    @Test
    void concurrentDivergentPaymentRequestsCreateAndReserveExactlyOnce() throws Exception {
        insertBalance(SENDER_ISPB, 10_000L);
        PaymentTransactionCommand first = payment(PAYMENT_ID_PREFIX + "DIVERGENT", RECEIVER_ISPB);
        PaymentTransactionCommand second = payment(PAYMENT_ID_PREFIX + "DIVERGENT", "63333333");
        AtomicReference<PaymentTransactionPersistenceResult> firstResult = new AtomicReference<>();
        AtomicReference<PaymentTransactionPersistenceResult> secondResult = new AtomicReference<>();

        invokeConcurrently(
                () -> firstResult.set(processor.processTransactions(authenticatedPayments(first))),
                () -> secondResult.set(processor.processTransactions(authenticatedPayments(second)))
        );

        assertThat(balanceCents(SENDER_ISPB)).isEqualTo(9_000L);
        assertThat(paymentCount(first.getPaymentId())).isOne();
        assertThat(auditCount(first.getPaymentId(), "PAYMENT_RESERVED")).isOne();
        assertThat(outboxCount(first.getPaymentId(), "ACCEPTANCE_REQUEST")).isOne();
        assertThat(firstResult.get().divergentDuplicates().size()
                + secondResult.get().divergentDuplicates().size()).isOne();
    }

    @Test
    void concurrentIdenticalAcceptedStatusesCreditReceiverExactlyOnce() throws Exception {
        PaymentTransactionCommand payment = reservePayment(PAYMENT_ID_PREFIX + "ACCEPTED");
        long payerAfterReserve = balanceCents(SENDER_ISPB);
        long receiverBefore = balanceCents(RECEIVER_ISPB);
        Runnable accept = () -> processor.processStatusReports(authenticatedReports(
                payment.getPaymentId(),
                StatusReportOutcome.ACCEPTED
        ));

        invokeConcurrently(accept, accept);

        assertThat(balanceCents(SENDER_ISPB)).isEqualTo(payerAfterReserve);
        assertThat(balanceCents(RECEIVER_ISPB)).isEqualTo(receiverBefore + payment.getAmountCents());
        assertThat(state(payment.getPaymentId())).isEqualTo(PaymentState.SETTLED.name());
        assertThat(auditCount(payment.getPaymentId(), "PAYMENT_SETTLED")).isOne();
        assertThat(statusOutboxCount(payment.getPaymentId())).isEqualTo(2);
    }

    @Test
    void concurrentIdenticalRejectedStatusesReleasePayerExactlyOnce() throws Exception {
        PaymentTransactionCommand payment = reservePayment(PAYMENT_ID_PREFIX + "REJECTED");
        long payerAfterReserve = balanceCents(SENDER_ISPB);
        long receiverBefore = balanceCents(RECEIVER_ISPB);
        Runnable reject = () -> processor.processStatusReports(authenticatedReports(
                payment.getPaymentId(),
                StatusReportOutcome.REJECTED
        ));

        invokeConcurrently(reject, reject);

        assertThat(balanceCents(SENDER_ISPB)).isEqualTo(payerAfterReserve + payment.getAmountCents());
        assertThat(balanceCents(RECEIVER_ISPB)).isEqualTo(receiverBefore);
        assertThat(state(payment.getPaymentId())).isEqualTo(PaymentState.REJECTED.name());
        assertThat(auditCount(payment.getPaymentId(), "PAYMENT_REJECTED")).isOne();
        assertThat(auditCount(payment.getPaymentId(), "PAYMENT_SETTLED")).isZero();
        assertThat(statusOutboxCount(payment.getPaymentId())).isOne();
    }

    private void invokeConcurrently(Runnable first, Runnable second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Void> firstResult = executor.submit(concurrentCall(first, ready, start));
            Future<Void> secondResult = executor.submit(concurrentCall(second, ready, start));
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

    private List<AuthenticatedPaymentRequest> authenticatedPayments(PaymentTransactionCommand payment) {
        return List.of(new AuthenticatedPaymentRequest(0, SENDER_ISPB, payment));
    }

    private List<AuthenticatedStatusReport> authenticatedReports(
            String paymentId,
            StatusReportOutcome outcome
    ) {
        return List.of(new AuthenticatedStatusReport(
                0,
                RECEIVER_ISPB,
                new IncomingStatusReportCommand(
                        paymentId,
                        outcome,
                        outcome == StatusReportOutcome.REJECTED
                                ? List.of(StatusReasonCode.of("AB03"))
                                : List.of()
                )
        ));
    }

    private PaymentTransactionCommand reservePayment(String paymentId) {
        insertBalance(SENDER_ISPB, 10_000L);
        insertBalance(RECEIVER_ISPB, 5_000L);
        PaymentTransactionCommand payment = payment(paymentId);
        processor.processTransactions(authenticatedPayments(payment));
        assertThat(state(paymentId)).isEqualTo(PaymentState.WAITING_ACCEPTANCE.name());
        assertThat(balanceCents(SENDER_ISPB)).isEqualTo(9_000L);
        return payment;
    }

    private void insertBalance(String ispb, long balanceCents) {
        jdbcTemplate.update(
                "INSERT INTO participant_balance_entity (bank_code, balance_cents) VALUES (?, ?)",
                ispb,
                balanceCents
        );
    }

    private long balanceCents(String ispb) {
        Long balance = jdbcTemplate.queryForObject(
                "SELECT balance_cents FROM participant_balance_entity WHERE bank_code = ?",
                Long.class,
                ispb
        );
        return balance == null ? 0L : balance;
    }

    private int paymentCount(String paymentId) {
        return count("SELECT COUNT(*) FROM payment_transaction_entity WHERE payment_id = ?", paymentId);
    }

    private int auditCount(String paymentId, String eventType) {
        return count(
                "SELECT COUNT(*) FROM payment_audit_event WHERE payment_id = ? AND event_type = ?::payment_audit_event_type",
                paymentId,
                eventType
        );
    }

    private int outboxCount(String paymentId, String eventType) {
        String payloadMarker = switch (eventType) {
            case "ACCEPTANCE_REQUEST" -> "CdtTrfTxInf";
            default -> throw new IllegalArgumentException("Unsupported event type: " + eventType);
        };
        return count("""
                SELECT COUNT(*)
                FROM notification_outbox
                WHERE convert_from(payload, 'UTF8') LIKE ?
                  AND convert_from(payload, 'UTF8') LIKE ?
                """, "%" + paymentId + "%", "%" + payloadMarker + "%");
    }

    private int statusOutboxCount(String paymentId) {
        return count(
                """
                        SELECT COUNT(*)
                        FROM notification_outbox
                        WHERE convert_from(payload, 'UTF8') LIKE ?
                          AND convert_from(payload, 'UTF8') LIKE '%TxSts%'
                        """,
                "%" + paymentId + "%"
        );
    }

    private String state(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM payment_transaction_entity WHERE payment_id = ?",
                String.class,
                paymentId
        );
    }

    private int count(String sql, Object... arguments) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        return count == null ? 0 : count;
    }

    private PaymentTransactionCommand payment(String paymentId) {
        return payment(paymentId, RECEIVER_ISPB);
    }

    private PaymentTransactionCommand payment(String paymentId, String receiverIspb) {
        return PaymentTransactionCommand.builder()
                .paymentId(paymentId)
                .amountCents(1_000L)
                .currency("BRL")
                .description("concurrent reservation test")
                .sender(party(SENDER_ISPB))
                .receiver(party(receiverIspb))
                .build();
    }

    private Party party(String ispb) {
        return Party.builder()
                .name("Name")
                .taxId("123")
                .pixKey("pix-" + ispb)
                .account(BankAccount.builder()
                        .bankCode(ispb)
                        .number("1")
                        .branch("1")
                        .type(BankAccountType.CHECKING)
                        .build())
                .build();
    }
}
