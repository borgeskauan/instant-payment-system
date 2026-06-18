package br.kauan.spi.adapter.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        insertPayment("E2E-SUCCESS", "25.00", "11111111", "22222222", PaymentStatus.WAITING_ACCEPTANCE);

        boolean settled = adapter.settleAcceptedPayment(
                "E2E-SUCCESS",
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.ACCEPTED_AND_SETTLED
        );

        assertTrue(settled);
        assertEquals(decimal("75.00"), balance("11111111"));
        assertEquals(decimal("75.00"), balance("22222222"));
        assertEquals(PaymentStatus.ACCEPTED_AND_SETTLED.name(), status("E2E-SUCCESS"));
    }

    @Test
    void settleAcceptedPaymentDoesNotCreditOrSettleWhenDebitCannotBeApplied() {
        insertFunds("33333333", "10.00");
        insertFunds("44444444", "50.00");
        insertPayment("E2E-FAILURE", "25.00", "33333333", "44444444", PaymentStatus.WAITING_ACCEPTANCE);

        boolean settled = adapter.settleAcceptedPayment(
                "E2E-FAILURE",
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.ACCEPTED_AND_SETTLED
        );

        assertFalse(settled);
        assertEquals(decimal("10.00"), balance("33333333"));
        assertEquals(decimal("50.00"), balance("44444444"));
        assertEquals(PaymentStatus.WAITING_ACCEPTANCE.name(), status("E2E-FAILURE"));
    }

    private void insertFunds(String bankCode, String balance) {
        jdbcTemplate.update(
                "INSERT INTO funds_entity (bank_code, balance) VALUES (?, ?)",
                bankCode,
                decimal(balance)
        );
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
                            currency,
                            status,
                            sender_bank_code,
                            receiver_bank_code
                        ) VALUES (?, ?, 'BRL', ?, ?, ?)
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
                "SELECT balance FROM funds_entity WHERE bank_code = ?",
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
