package br.kauan.spi.adapter.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.port.output.SettlementRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

@Repository
public class SettlementJdbcAdapter implements SettlementRepository {

    private static final String SETTLE_ACCEPTED_PAYMENT_SQL = """
            WITH tx AS (
                SELECT *
                FROM payment_transaction_entity
                WHERE payment_id = ? AND status = ?
            ),
            debited AS (
                UPDATE funds_bucket_entity f
                SET balance = f.balance - tx.amount
                FROM tx
                WHERE f.bank_code = tx.sender_bank_code
                  AND f.bucket_id = (ABS(hashtext(tx.payment_id)) % 16)
                  AND f.balance >= tx.amount
                RETURNING f.bank_code
            ),
            credited AS (
                UPDATE funds_bucket_entity f
                SET balance = f.balance + tx.amount
                FROM tx, debited
                WHERE f.bank_code = tx.receiver_bank_code
                  AND f.bucket_id = (ABS(hashtext(tx.payment_id)) % 16)
                RETURNING f.bank_code
            ),
            settled AS (
                UPDATE payment_transaction_entity p
                SET status = ?
                FROM tx, credited
                WHERE p.payment_id = tx.payment_id
                RETURNING p.payment_id, p.amount, p.sender_bank_code, p.receiver_bank_code
            )
            SELECT payment_id, amount, sender_bank_code, receiver_bank_code
            FROM settled
            """;

    private final JdbcTemplate jdbcTemplate;
    private final PaymentTransactionRepositoryMapper repositoryMapper;

    public SettlementJdbcAdapter(JdbcTemplate jdbcTemplate, PaymentTransactionRepositoryMapper repositoryMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.repositoryMapper = repositoryMapper;
    }

    @Override
    public Optional<PaymentTransaction> settleAcceptedPayment(
            String paymentId,
            PaymentStatus currentStatus,
            PaymentStatus settledStatus
    ) {
        var settledPayments = jdbcTemplate.query(
                SETTLE_ACCEPTED_PAYMENT_SQL,
                this::toPaymentTransaction,
                paymentId,
                currentStatus.name(),
                settledStatus.name()
        );

        if (settledPayments.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(settledPayments.getFirst());
    }

    private PaymentTransaction toPaymentTransaction(ResultSet resultSet, int rowNumber) throws SQLException {
        PaymentTransactionEntity entity = new PaymentTransactionEntity();
        entity.setPaymentId(resultSet.getString("payment_id"));
        entity.setAmount(resultSet.getBigDecimal("amount"));
        entity.setSenderBankCode(resultSet.getString("sender_bank_code"));
        entity.setReceiverBankCode(resultSet.getString("receiver_bank_code"));
        return repositoryMapper.toDomain(entity);
    }
}
