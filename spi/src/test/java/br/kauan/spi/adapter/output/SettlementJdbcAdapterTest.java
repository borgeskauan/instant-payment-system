package br.kauan.spi.adapter.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
@Transactional
class SettlementJdbcAdapterTest {

    @Autowired
    private SettlementJdbcAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void settleAcceptedPaymentDebitsCreditsAndMarksTransactionAsSettledInOneOperation() {
        insertFunds("11111111", "100.00");
        insertFunds("22222222", "50.00");
        insertPayment("E2E-SUCCESS", "1.00", "11111111", "22222222", PaymentStatus.WAITING_ACCEPTANCE);

        Optional<PaymentTransaction> settled = adapter.settleAcceptedPayment(
                "E2E-SUCCESS",
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.ACCEPTED_AND_SETTLED
        );

        PaymentTransaction transaction = settled.orElseThrow();
        assertEquals("E2E-SUCCESS", transaction.getPaymentId());
        assertEquals(decimal("1.00"), transaction.getAmount());
        assertEquals("11111111", transaction.getSender().getAccount().getBankCode());
        assertEquals("22222222", transaction.getReceiver().getAccount().getBankCode());
        assertEquals(decimal("99.00"), balance("11111111"));
        assertEquals(decimal("51.00"), balance("22222222"));
        assertEquals(PaymentStatus.ACCEPTED_AND_SETTLED.name(), status("E2E-SUCCESS"));
    }

    @Test
    void settleAcceptedPaymentDoesNotCreditOrSettleWhenDebitCannotBeApplied() {
        insertFunds("33333333", "10.00");
        insertFunds("44444444", "50.00");
        insertPayment("E2E-FAILURE", "25.00", "33333333", "44444444", PaymentStatus.WAITING_ACCEPTANCE);

        Optional<PaymentTransaction> settled = adapter.settleAcceptedPayment(
                "E2E-FAILURE",
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.ACCEPTED_AND_SETTLED
        );

        assertFalse(settled.isPresent());
        assertEquals(decimal("10.00"), balance("33333333"));
        assertEquals(decimal("50.00"), balance("44444444"));
        assertEquals(PaymentStatus.WAITING_ACCEPTANCE.name(), status("E2E-FAILURE"));
    }

    @Test
    void settleAcceptedPaymentsLocksBucketsOnceAndSettlesOnlyWaitingTransactions() {
        insertFunds("11111111", "100.00");
        insertFunds("22222222", "50.00");
        insertFunds("33333333", "75.00");
        insertPayment("E2E-BATCH-1", "1.00", "11111111", "22222222", PaymentStatus.WAITING_ACCEPTANCE);
        insertPayment("E2E-BATCH-2", "2.00", "11111111", "33333333", PaymentStatus.WAITING_ACCEPTANCE);
        insertPayment("E2E-BATCH-ALREADY", "4.00", "11111111", "22222222", PaymentStatus.ACCEPTED_AND_SETTLED);

        List<PaymentTransaction> settled = adapter.settleAcceptedPayments(
                List.of("E2E-BATCH-2", "E2E-BATCH-ALREADY", "E2E-BATCH-1"),
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.ACCEPTED_AND_SETTLED
        );

        assertThat(settled)
                .extracting(PaymentTransaction::getPaymentId)
                .containsExactly("E2E-BATCH-1", "E2E-BATCH-2", "E2E-BATCH-ALREADY");
        assertEquals(decimal("97.00"), balance("11111111"));
        assertEquals(decimal("51.00"), balance("22222222"));
        assertEquals(decimal("77.00"), balance("33333333"));
        assertEquals(PaymentStatus.ACCEPTED_AND_SETTLED.name(), status("E2E-BATCH-1"));
        assertEquals(PaymentStatus.ACCEPTED_AND_SETTLED.name(), status("E2E-BATCH-2"));
        assertEquals(PaymentStatus.ACCEPTED_AND_SETTLED.name(), status("E2E-BATCH-ALREADY"));
    }

    @Test
    void settleAcceptedPaymentsReturnsAlreadySettledTransactionsWithoutMovingFundsAgain() {
        insertFunds("77777777", "100.00");
        insertFunds("88888888", "50.00");
        insertPayment("E2E-BATCH-IDEMPOTENT", "10.00", "77777777", "88888888", PaymentStatus.ACCEPTED_AND_SETTLED);

        List<PaymentTransaction> settled = adapter.settleAcceptedPayments(
                List.of("E2E-BATCH-IDEMPOTENT"),
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.ACCEPTED_AND_SETTLED
        );

        assertThat(settled)
                .extracting(PaymentTransaction::getPaymentId)
                .containsExactly("E2E-BATCH-IDEMPOTENT");
        assertEquals(decimal("100.00"), balance("77777777"));
        assertEquals(decimal("50.00"), balance("88888888"));
        assertEquals(PaymentStatus.ACCEPTED_AND_SETTLED.name(), status("E2E-BATCH-IDEMPOTENT"));
    }

    @Test
    void settleAcceptedPaymentsDoesNotDebitCreditOrSettleWhenBatchDebitCannotBeApplied() {
        insertFunds("55555555", "0.00");
        insertFunds("66666666", "50.00");
        insertPayment("E2E-BATCH-FAILURE-1", "1.00", "55555555", "66666666", PaymentStatus.WAITING_ACCEPTANCE);
        insertPayment("E2E-BATCH-FAILURE-2", "2.00", "55555555", "66666666", PaymentStatus.WAITING_ACCEPTANCE);

        List<PaymentTransaction> settled = adapter.settleAcceptedPayments(
                List.of("E2E-BATCH-FAILURE-1", "E2E-BATCH-FAILURE-2"),
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.ACCEPTED_AND_SETTLED
        );

        assertThat(settled).isEmpty();
        assertEquals(decimal("0.00"), balance("55555555"));
        assertEquals(decimal("50.00"), balance("66666666"));
        assertEquals(PaymentStatus.WAITING_ACCEPTANCE.name(), status("E2E-BATCH-FAILURE-1"));
        assertEquals(PaymentStatus.WAITING_ACCEPTANCE.name(), status("E2E-BATCH-FAILURE-2"));
    }

    private void insertFunds(String bankCode, String balance) {
        BigDecimal bucketBalance = decimal(balance).divide(decimal("16"), 2, java.math.RoundingMode.DOWN);
        BigDecimal remainder = decimal(balance).subtract(bucketBalance.multiply(decimal("16")));

        for (int bucketId = 0; bucketId < 16; bucketId++) {
            jdbcTemplate.update(
                    "INSERT INTO funds_bucket_entity (bank_code, bucket_id, balance) VALUES (?, ?, ?)",
                    bankCode,
                    bucketId,
                    bucketId == 0 ? bucketBalance.add(remainder) : bucketBalance
            );
        }
    }

    private void insertPayment(
            String paymentId,
            String amount,
            String senderBankCode,
            String receiverBankCode,
            PaymentStatus status
    ) {
        jdbcTemplate.update(
                """
                        INSERT INTO payment_transaction_entity (
                            payment_id,
                            amount,
                            status,
                            sender_bank_code,
                            receiver_bank_code
                        ) VALUES (?, ?, ?, ?, ?)
                        """,
                paymentId,
                decimal(amount),
                status.name(),
                senderBankCode,
                receiverBankCode
        );
    }

    private BigDecimal balance(String bankCode) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(balance), 0) FROM funds_bucket_entity WHERE bank_code = ?",
                BigDecimal.class,
                bankCode
        );
    }

    private String status(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM payment_transaction_entity WHERE payment_id = ?",
                String.class,
                paymentId
        );
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
