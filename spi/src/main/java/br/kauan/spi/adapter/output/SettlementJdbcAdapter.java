package br.kauan.spi.adapter.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.port.output.SettlementRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SettlementJdbcAdapter implements SettlementRepository {

    private static final String SETTLE_ACCEPTED_PAYMENT_SQL = """
            WITH tx AS (
                SELECT payment_id, amount, sender_bank_code, receiver_bank_code
                FROM payment_transaction_entity
                WHERE payment_id = ? AND status = ?
            ),
            debited AS (
                UPDATE funds_entity f
                SET balance = f.balance - tx.amount
                FROM tx
                WHERE f.bank_code = tx.sender_bank_code
                  AND f.balance >= tx.amount
                RETURNING f.bank_code
            ),
            credited AS (
                UPDATE funds_entity f
                SET balance = f.balance + tx.amount
                FROM tx, debited
                WHERE f.bank_code = tx.receiver_bank_code
                RETURNING f.bank_code
            )
            UPDATE payment_transaction_entity p
            SET status = ?
            FROM tx, credited
            WHERE p.payment_id = tx.payment_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public SettlementJdbcAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean settleAcceptedPayment(String paymentId, PaymentStatus currentStatus, PaymentStatus settledStatus) {
        int updatedRows = jdbcTemplate.update(
                SETTLE_ACCEPTED_PAYMENT_SQL,
                paymentId,
                currentStatus.name(),
                settledStatus.name()
        );
        return updatedRows == 1;
    }
}
