package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.commons.Money;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.output.PaymentTransactionPersistenceResult;
import br.kauan.spi.port.output.StatusReportPersistenceResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Transactional
class JpaAdapterIntegrationTest {

    @Autowired
    private JpaAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanFixtureRows() {
        jdbcTemplate.update(
                "DELETE FROM payment_transaction_entity WHERE payment_id >= 'E2E-IDEMP-' AND payment_id < 'E2E-IDEMP.'"
        );
        jdbcTemplate.update(
                "DELETE FROM funds_bucket_entity WHERE bank_code IN ('11111111', '22222222', '33333333')"
        );
    }

    @Test
    void newPaymentReturnsAcceptanceRequestAndPersistsFingerprintVersion() {
        PaymentTransactionCommand payment = paymentTransaction("E2E-IDEMP-NEW", "11111111", "22222222");

        PaymentTransactionPersistenceResult result =
                adapter.storeAndClassifyIncomingPaymentRequests(List.of(payment));

        assertThat(result.acceptanceRequests()).containsExactly(payment);
        assertThat(result.divergentDuplicates()).isEmpty();
        assertThat(status(payment.getPaymentId())).isEqualTo(PaymentStatus.WAITING_ACCEPTANCE.name());
        assertThat(fingerprint(payment.getPaymentId())).isEqualTo(RequestFingerprint.from(payment));
        assertThat(fingerprintVersion(payment.getPaymentId())).isEqualTo("v1");
    }

    @Test
    void repeatedIdenticalNewPaymentReturnsOneAcceptanceRequestForFirstOrdinalOnly() {
        PaymentTransactionCommand first = paymentTransaction("E2E-IDEMP-SAME-BATCH", "11111111", "22222222");
        PaymentTransactionCommand repeated = paymentTransaction("E2E-IDEMP-SAME-BATCH", "11111111", "22222222");

        PaymentTransactionPersistenceResult result =
                adapter.storeAndClassifyIncomingPaymentRequests(List.of(first, repeated));

        assertThat(result.acceptanceRequests()).containsExactly(first);
        assertThat(result.divergentDuplicates()).isEmpty();
        assertThat(rowCount(first.getPaymentId())).isEqualTo(1);
    }

    @Test
    void repeatedIdenticalExistingWaitingPaymentReturnsOneAcceptanceRequestForFirstOrdinalOnly() {
        PaymentTransactionCommand first = paymentTransaction("E2E-IDEMP-EXISTING-WAITING", "11111111", "22222222");
        PaymentTransactionCommand repeated = paymentTransaction("E2E-IDEMP-EXISTING-WAITING", "11111111", "22222222");
        adapter.storeAndClassifyIncomingPaymentRequests(List.of(first));

        PaymentTransactionPersistenceResult result =
                adapter.storeAndClassifyIncomingPaymentRequests(List.of(first, repeated));

        assertThat(result.acceptanceRequests()).containsExactly(first);
        assertThat(result.divergentDuplicates()).isEmpty();
    }

    @Test
    void identicalExistingAdvancedPaymentIsNoOp() {
        PaymentTransactionCommand payment = paymentTransaction("E2E-IDEMP-ADVANCED", "11111111", "22222222");
        adapter.storeAndClassifyIncomingPaymentRequests(List.of(payment));
        jdbcTemplate.update(
                "UPDATE payment_transaction_entity SET status = ? WHERE payment_id = ?",
                PaymentStatus.ACCEPTED_IN_PROCESS.name(),
                payment.getPaymentId()
        );

        PaymentTransactionPersistenceResult result =
                adapter.storeAndClassifyIncomingPaymentRequests(List.of(payment));

        assertThat(result.acceptanceRequests()).isEmpty();
        assertThat(result.divergentDuplicates()).isEmpty();
    }

    @Test
    void existingPaymentWithSameFingerprintButDifferentVersionIsDivergent() {
        PaymentTransactionCommand payment = paymentTransaction("E2E-IDEMP-VERSION", "11111111", "22222222");
        insertPayment(
                payment,
                PaymentStatus.WAITING_ACCEPTANCE,
                RequestFingerprint.from(payment),
                "v0"
        );

        PaymentTransactionPersistenceResult result =
                adapter.storeAndClassifyIncomingPaymentRequests(List.of(payment));

        assertThat(result.acceptanceRequests()).isEmpty();
        assertThat(result.divergentDuplicates()).containsExactly(payment);
    }

    @Test
    void existingLegacyPaymentWithoutComparableFingerprintVersionIsDivergent() {
        PaymentTransactionCommand payment = paymentTransaction("E2E-IDEMP-LEGACY", "11111111", "22222222");
        insertPayment(payment, PaymentStatus.WAITING_ACCEPTANCE, null, null);

        PaymentTransactionPersistenceResult result =
                adapter.storeAndClassifyIncomingPaymentRequests(List.of(payment));

        assertThat(result.acceptanceRequests()).isEmpty();
        assertThat(result.divergentDuplicates()).containsExactly(payment);
    }

    @Test
    void repeatedIdenticalExistingDivergentPaymentReturnsEveryRecordAsDivergent() {
        PaymentTransactionCommand existing = paymentTransaction(
                "E2E-IDEMP-EXISTING-DIVERGENT-BATCH",
                "11111111",
                "22222222"
        );
        PaymentTransactionCommand first = paymentTransaction(
                "E2E-IDEMP-EXISTING-DIVERGENT-BATCH",
                "33333333",
                "22222222"
        );
        PaymentTransactionCommand repeated = paymentTransaction(
                "E2E-IDEMP-EXISTING-DIVERGENT-BATCH",
                "33333333",
                "22222222"
        );
        insertPayment(
                existing,
                PaymentStatus.WAITING_ACCEPTANCE,
                RequestFingerprint.from(existing),
                RequestFingerprint.VERSION
        );

        PaymentTransactionPersistenceResult result =
                adapter.storeAndClassifyIncomingPaymentRequests(List.of(first, repeated));

        assertThat(result.acceptanceRequests()).isEmpty();
        assertThat(result.divergentDuplicates()).containsExactly(first, repeated);
    }

    @Test
    void sameBatchDivergentPaymentIdReturnsEveryRecordAsDivergentAndInsertsNone() {
        PaymentTransactionCommand first = paymentTransaction("E2E-IDEMP-DIVERGENT-BATCH", "11111111", "22222222");
        PaymentTransactionCommand second = paymentTransaction("E2E-IDEMP-DIVERGENT-BATCH", "11111111", "33333333");

        PaymentTransactionPersistenceResult result =
                adapter.storeAndClassifyIncomingPaymentRequests(List.of(first, second));

        assertThat(result.acceptanceRequests()).isEmpty();
        assertThat(result.divergentDuplicates()).containsExactly(first, second);
        assertThat(rowCount(first.getPaymentId())).isZero();
    }

    @Test
    void acceptedStatusReportSettlesWaitingPaymentAndReturnsPaymentForConfirmation() {
        PaymentTransactionCommand payment = paymentTransaction("E2E-IDEMP-STATUS-ACCEPTED", "11111111", "22222222");
        insertFunds("11111111", "1000.00");
        insertFunds("22222222", "500.00");
        insertPayment(payment, PaymentStatus.WAITING_ACCEPTANCE, null, null);

        StatusReportPersistenceResult result = adapter.classifyAndApplyIncomingStatusReports(List.of(
                statusReport(payment.getPaymentId(), PaymentStatus.ACCEPTED_IN_PROCESS)));

        assertThat(result.settledPayments())
                .extracting(PaymentTransactionCommand::getPaymentId)
                .containsExactly(payment.getPaymentId());
        assertThat(result.rejectedPayments()).isEmpty();
        assertThat(result.divergentStatusReports()).isEmpty();
        assertThat(status(payment.getPaymentId())).isEqualTo(PaymentStatus.ACCEPTED_AND_SETTLED.name());
        assertThat(balance("11111111")).isEqualByComparingTo(decimal("990.00"));
        assertThat(balance("22222222")).isEqualByComparingTo(decimal("510.00"));
    }

    @Test
    void repeatedIdenticalAcceptedStatusReportReturnsOneSettledPayment() {
        PaymentTransactionCommand payment = paymentTransaction(
                "E2E-IDEMP-STATUS-ACCEPTED-REPEATED",
                "11111111",
                "22222222"
        );
        insertFunds("11111111", "1000.00");
        insertFunds("22222222", "500.00");
        insertPayment(payment, PaymentStatus.WAITING_ACCEPTANCE, null, null);
        StatusReportCommand first = statusReport(payment.getPaymentId(), PaymentStatus.ACCEPTED_IN_PROCESS);
        StatusReportCommand repeated = statusReport(payment.getPaymentId(), PaymentStatus.ACCEPTED_IN_PROCESS);

        StatusReportPersistenceResult result = adapter.classifyAndApplyIncomingStatusReports(List.of(first, repeated));

        assertThat(result.settledPayments())
                .extracting(PaymentTransactionCommand::getPaymentId)
                .containsExactly(payment.getPaymentId());
        assertThat(result.rejectedPayments()).isEmpty();
        assertThat(result.divergentStatusReports()).isEmpty();
        assertThat(status(payment.getPaymentId())).isEqualTo(PaymentStatus.ACCEPTED_AND_SETTLED.name());
    }

    @Test
    void sameBatchConflictingStatusReportsReturnEveryRecordAsDivergentAndDoNotChangeStatus() {
        PaymentTransactionCommand payment = paymentTransaction(
                "E2E-IDEMP-STATUS-CONFLICTING-BATCH",
                "11111111",
                "22222222"
        );
        insertPayment(payment, PaymentStatus.WAITING_ACCEPTANCE, null, null);
        StatusReportCommand accepted = statusReport(payment.getPaymentId(), PaymentStatus.ACCEPTED_IN_PROCESS);
        StatusReportCommand rejected = statusReport(payment.getPaymentId(), PaymentStatus.REJECTED);

        StatusReportPersistenceResult result = adapter.classifyAndApplyIncomingStatusReports(List.of(accepted, rejected));

        assertThat(result.settledPayments()).isEmpty();
        assertThat(result.rejectedPayments()).isEmpty();
        assertThat(result.divergentStatusReports()).containsExactly(accepted, rejected);
        assertThat(status(payment.getPaymentId())).isEqualTo(PaymentStatus.WAITING_ACCEPTANCE.name());
    }

    @Test
    void repeatedIdenticalRejectedStatusReportForSettledPaymentReturnsEveryRecordAsDivergent() {
        PaymentTransactionCommand payment = paymentTransaction(
                "E2E-IDEMP-STATUS-REJECTED-SETTLED-REPEATED",
                "11111111",
                "22222222"
        );
        insertPayment(payment, PaymentStatus.ACCEPTED_AND_SETTLED, null, null);
        StatusReportCommand first = statusReport(payment.getPaymentId(), PaymentStatus.REJECTED);
        StatusReportCommand repeated = statusReport(payment.getPaymentId(), PaymentStatus.REJECTED);

        StatusReportPersistenceResult result = adapter.classifyAndApplyIncomingStatusReports(List.of(first, repeated));

        assertThat(result.settledPayments()).isEmpty();
        assertThat(result.rejectedPayments()).isEmpty();
        assertThat(result.divergentStatusReports()).containsExactly(first, repeated);
        assertThat(status(payment.getPaymentId())).isEqualTo(PaymentStatus.ACCEPTED_AND_SETTLED.name());
    }

    @Test
    void acceptedStatusReportMarksWaitingPaymentInProcessWhenSettlementCannotBeApplied() {
        PaymentTransactionCommand payment = paymentTransaction(
                "E2E-IDEMP-STATUS-ACCEPTED-NO-FUNDS",
                "11111111",
                "22222222"
        );
        insertFunds("11111111", "0.00");
        insertFunds("22222222", "50.00");
        insertPayment(payment, PaymentStatus.WAITING_ACCEPTANCE, null, null);

        StatusReportPersistenceResult result = adapter.classifyAndApplyIncomingStatusReports(List.of(
                statusReport(payment.getPaymentId(), PaymentStatus.ACCEPTED_IN_PROCESS)));

        assertThat(result.settledPayments()).isEmpty();
        assertThat(result.rejectedPayments()).isEmpty();
        assertThat(result.divergentStatusReports()).isEmpty();
        assertThat(status(payment.getPaymentId())).isEqualTo(PaymentStatus.ACCEPTED_IN_PROCESS.name());
    }

    @Test
    void acceptedStatusReportsSettleOnlyFirstPaymentsThatFitWhenSenderBucketHasPartialFunds() {
        List<String> paymentIds = paymentIdsInSameBucket("E2E-IDEMP-STATUS-PARTIAL-FUNDS-", 2);
        int senderBucketId = bucketId(paymentIds.get(0));
        PaymentTransactionCommand first = paymentTransaction(paymentIds.get(0), "11111111", "22222222");
        PaymentTransactionCommand second = paymentTransaction(paymentIds.get(1), "11111111", "22222222");
        insertFunds("11111111", "0.00");
        insertFunds("22222222", "0.00");
        jdbcTemplate.update(
                "UPDATE funds_bucket_entity SET balance_cents = ? WHERE bank_code = ? AND bucket_id = ?",
                first.getAmountCents(),
                "11111111",
                senderBucketId
        );
        insertPayment(first, PaymentStatus.WAITING_ACCEPTANCE, null, null);
        insertPayment(second, PaymentStatus.WAITING_ACCEPTANCE, null, null);

        StatusReportPersistenceResult result = adapter.classifyAndApplyIncomingStatusReports(List.of(
                statusReport(first.getPaymentId(), PaymentStatus.ACCEPTED_IN_PROCESS),
                statusReport(second.getPaymentId(), PaymentStatus.ACCEPTED_IN_PROCESS)
        ));

        assertThat(result.settledPayments())
                .extracting(PaymentTransactionCommand::getPaymentId)
                .containsExactly(first.getPaymentId());
        assertThat(result.rejectedPayments()).isEmpty();
        assertThat(result.divergentStatusReports()).isEmpty();
        assertThat(status(first.getPaymentId())).isEqualTo(PaymentStatus.ACCEPTED_AND_SETTLED.name());
        assertThat(status(second.getPaymentId())).isEqualTo(PaymentStatus.ACCEPTED_IN_PROCESS.name());
        assertThat(balance("11111111")).isEqualByComparingTo(decimal("0.00"));
        assertThat(balance("22222222")).isEqualByComparingTo(decimal("10.00"));
    }

    @Test
    void acceptedStatusReportForAlreadyAcceptedInProcessPaymentIsNoOp() {
        PaymentTransactionCommand payment = paymentTransaction("E2E-IDEMP-STATUS-IN-PROCESS", "11111111", "22222222");
        insertPayment(payment, PaymentStatus.ACCEPTED_IN_PROCESS, null, null);

        StatusReportPersistenceResult result = adapter.classifyAndApplyIncomingStatusReports(List.of(
                statusReport(payment.getPaymentId(), PaymentStatus.ACCEPTED_IN_PROCESS)));

        assertThat(result.settledPayments()).isEmpty();
        assertThat(result.rejectedPayments()).isEmpty();
        assertThat(result.divergentStatusReports()).isEmpty();
        assertThat(status(payment.getPaymentId())).isEqualTo(PaymentStatus.ACCEPTED_IN_PROCESS.name());
    }

    @Test
    void acceptedStatusReportForAlreadySettledPaymentIsNoOp() {
        PaymentTransactionCommand payment = paymentTransaction("E2E-IDEMP-STATUS-SETTLED", "11111111", "22222222");
        insertPayment(payment, PaymentStatus.ACCEPTED_AND_SETTLED, null, null);

        StatusReportPersistenceResult result = adapter.classifyAndApplyIncomingStatusReports(List.of(
                statusReport(payment.getPaymentId(), PaymentStatus.ACCEPTED_IN_PROCESS)));

        assertThat(result.settledPayments()).isEmpty();
        assertThat(result.rejectedPayments()).isEmpty();
        assertThat(result.divergentStatusReports()).isEmpty();
        assertThat(status(payment.getPaymentId())).isEqualTo(PaymentStatus.ACCEPTED_AND_SETTLED.name());
    }

    @Test
    void rejectedStatusReportTransitionsWaitingPaymentAndReturnsPaymentForNotification() {
        PaymentTransactionCommand payment = paymentTransaction("E2E-IDEMP-STATUS-REJECTED", "11111111", "22222222");
        insertPayment(payment, PaymentStatus.WAITING_ACCEPTANCE, null, null);

        StatusReportPersistenceResult result = adapter.classifyAndApplyIncomingStatusReports(List.of(
                statusReport(payment.getPaymentId(), PaymentStatus.REJECTED)));

        assertThat(result.settledPayments()).isEmpty();
        assertThat(result.rejectedPayments())
                .extracting(PaymentTransactionCommand::getPaymentId)
                .containsExactly(payment.getPaymentId());
        assertThat(result.divergentStatusReports()).isEmpty();
        assertThat(status(payment.getPaymentId())).isEqualTo(PaymentStatus.REJECTED.name());
    }

    @Test
    void rejectedStatusReportForAlreadyRejectedPaymentIsNoOp() {
        PaymentTransactionCommand payment = paymentTransaction("E2E-IDEMP-STATUS-REJECTED-NOOP", "11111111", "22222222");
        insertPayment(payment, PaymentStatus.REJECTED, null, null);

        StatusReportPersistenceResult result = adapter.classifyAndApplyIncomingStatusReports(List.of(
                statusReport(payment.getPaymentId(), PaymentStatus.REJECTED)));

        assertThat(result.settledPayments()).isEmpty();
        assertThat(result.rejectedPayments()).isEmpty();
        assertThat(result.divergentStatusReports()).isEmpty();
        assertThat(status(payment.getPaymentId())).isEqualTo(PaymentStatus.REJECTED.name());
    }

    @Test
    void rejectedStatusReportForSettledPaymentIsDivergentAndDoesNotOverwrite() {
        PaymentTransactionCommand payment = paymentTransaction("E2E-IDEMP-STATUS-REJECTED-SETTLED", "11111111", "22222222");
        StatusReportCommand statusReport = statusReport(payment.getPaymentId(), PaymentStatus.REJECTED);
        insertPayment(payment, PaymentStatus.ACCEPTED_AND_SETTLED, null, null);

        StatusReportPersistenceResult result = adapter.classifyAndApplyIncomingStatusReports(List.of(statusReport));

        assertThat(result.settledPayments()).isEmpty();
        assertThat(result.rejectedPayments()).isEmpty();
        assertThat(result.divergentStatusReports()).containsExactly(statusReport);
        assertThat(status(payment.getPaymentId())).isEqualTo(PaymentStatus.ACCEPTED_AND_SETTLED.name());
    }

    @Test
    void missingPaymentStatusReportIsDivergent() {
        StatusReportCommand statusReport = statusReport("E2E-IDEMP-STATUS-MISSING", PaymentStatus.REJECTED);

        StatusReportPersistenceResult result = adapter.classifyAndApplyIncomingStatusReports(List.of(statusReport));

        assertThat(result.settledPayments()).isEmpty();
        assertThat(result.rejectedPayments()).isEmpty();
        assertThat(result.divergentStatusReports()).containsExactly(statusReport);
    }

    @Test
    void unsupportedStatusReportStatusIsDivergent() {
        PaymentTransactionCommand payment = paymentTransaction("E2E-IDEMP-STATUS-UNSUPPORTED", "11111111", "22222222");
        StatusReportCommand statusReport = statusReport(payment.getPaymentId(), PaymentStatus.ACCEPTED_AND_SETTLED);
        insertPayment(payment, PaymentStatus.WAITING_ACCEPTANCE, null, null);

        StatusReportPersistenceResult result = adapter.classifyAndApplyIncomingStatusReports(List.of(statusReport));

        assertThat(result.settledPayments()).isEmpty();
        assertThat(result.rejectedPayments()).isEmpty();
        assertThat(result.divergentStatusReports()).containsExactly(statusReport);
        assertThat(status(payment.getPaymentId())).isEqualTo(PaymentStatus.WAITING_ACCEPTANCE.name());
    }

    private void insertPayment(
            PaymentTransactionCommand payment,
            PaymentStatus status,
            String requestFingerprint,
            String requestFingerprintVersion
    ) {
        jdbcTemplate.update(
                """
                        INSERT INTO payment_transaction_entity (
                            payment_id,
                            amount_cents,
                            status,
                            sender_bank_code,
                            receiver_bank_code,
                            request_fingerprint,
                            request_fingerprint_version
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                payment.getPaymentId(),
                payment.getAmountCents(),
                status.name(),
                payment.getSender().getAccount().getBankCode(),
                payment.getReceiver().getAccount().getBankCode(),
                requestFingerprint,
                requestFingerprintVersion
        );
    }

    private String status(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM payment_transaction_entity WHERE payment_id = ?",
                String.class,
                paymentId
        );
    }

    private void insertFunds(String bankCode, String balance) {
        long balanceCents = Money.toCents(decimal(balance));
        long bucketBalance = balanceCents / 16;
        long remainder = balanceCents % 16;
        for (int bucketId = 0; bucketId < 16; bucketId++) {
            jdbcTemplate.update(
                    "INSERT INTO funds_bucket_entity (bank_code, bucket_id, balance_cents) VALUES (?, ?, ?)",
                    bankCode,
                    bucketId,
                    bucketId == 0 ? bucketBalance + remainder : bucketBalance
            );
        }
    }

    private List<String> paymentIdsInSameBucket(String prefix, int count) {
        for (int bucketId = 0; bucketId < 16; bucketId++) {
            List<String> paymentIds = new java.util.ArrayList<>();
            for (int candidate = 0; candidate < 256 && paymentIds.size() < count; candidate++) {
                String paymentId = prefix + candidate;
                if (bucketId(paymentId) == bucketId) {
                    paymentIds.add(paymentId);
                }
            }
            if (paymentIds.size() == count) {
                return paymentIds;
            }
        }
        throw new IllegalStateException("Could not find payment ids in the same bucket");
    }

    private int bucketId(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT ABS(hashtext(?)) % 16",
                Integer.class,
                paymentId
        );
    }

    private BigDecimal balance(String bankCode) {
        Long balanceCents = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(balance_cents), 0) FROM funds_bucket_entity WHERE bank_code = ?",
                Long.class,
                bankCode
        );
        return Money.toDecimal(balanceCents == null ? 0L : balanceCents);
    }

    private BigDecimal decimal(String amount) {
        return new BigDecimal(amount);
    }

    private String fingerprint(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT request_fingerprint FROM payment_transaction_entity WHERE payment_id = ?",
                String.class,
                paymentId
        );
    }

    private String fingerprintVersion(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT request_fingerprint_version FROM payment_transaction_entity WHERE payment_id = ?",
                String.class,
                paymentId
        );
    }

    private Integer rowCount(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_transaction_entity WHERE payment_id = ?",
                Integer.class,
                paymentId
        );
    }

    private static StatusReportCommand statusReport(String paymentId, PaymentStatus status) {
        return StatusReportCommand.builder()
                .originalPaymentId(paymentId)
                .status(status)
                .build();
    }

    private static PaymentTransactionCommand paymentTransaction(String paymentId, String senderBankCode, String receiverBankCode) {
        return PaymentTransactionCommand.builder()
                .paymentId(paymentId)
                .amountCents(1000L)
                .currency("BRL")
                .description("test")
                .sender(party(senderBankCode))
                .receiver(party(receiverBankCode))
                .build();
    }

    private static Party party(String bankCode) {
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
