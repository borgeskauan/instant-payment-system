package br.kauan.spi.adapter.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.output.SettlementRepository;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Repository
public class SettlementJdbcAdapter implements SettlementRepository {

    private static final int BUCKET_COUNT = 16;

    private final JdbcTemplate jdbcTemplate;
    private final PaymentTransactionRepositoryMapper repositoryMapper;

    public SettlementJdbcAdapter(JdbcTemplate jdbcTemplate, PaymentTransactionRepositoryMapper repositoryMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.repositoryMapper = repositoryMapper;
    }

    @Override
    public List<PaymentTransactionCommand> settleAcceptedPaymentsIdempotently(
            List<String> paymentIds,
            PaymentStatus currentStatus,
            PaymentStatus settledStatus
    ) {
        if (paymentIds.isEmpty()) {
            return List.of();
        }

        String sql = """
                WITH requested(payment_id, ordinal) AS (
                    SELECT payment_id, MIN(ordinal)::int
                    FROM unnest(?::text[]) WITH ORDINALITY AS request(payment_id, ordinal)
                    GROUP BY payment_id
                ),
                candidates AS MATERIALIZED (
                    SELECT p.payment_id,
                           p.amount_cents,
                           p.sender_bank_code,
                           p.receiver_bank_code,
                           p.status,
                           (ABS(hashtext(p.payment_id)) % ?) AS bucket_id,
                           r.ordinal
                    FROM payment_transaction_entity p
                    JOIN requested r ON r.payment_id = p.payment_id
                    WHERE p.status IN (?, ?)
                    ORDER BY r.ordinal
                    FOR UPDATE OF p
                ),
                waiting AS (
                    SELECT *
                    FROM candidates
                    WHERE status = ?
                ),
                already_settled AS (
                    SELECT *
                    FROM candidates
                    WHERE status = ?
                ),
                required_buckets AS (
                    SELECT sender_bank_code AS bank_code, bucket_id
                    FROM waiting
                    UNION
                    SELECT receiver_bank_code AS bank_code, bucket_id
                    FROM waiting
                ),
                locked_buckets AS MATERIALIZED (
                    SELECT f.bank_code, f.bucket_id, f.balance_cents
                    FROM funds_bucket_entity f
                    JOIN required_buckets b
                      ON b.bank_code = f.bank_code
                     AND b.bucket_id = f.bucket_id
                    ORDER BY f.bank_code, f.bucket_id
                    FOR UPDATE OF f
                ),
                ranked AS (
                    SELECT w.*,
                           SUM(w.amount_cents) OVER (
                               PARTITION BY w.sender_bank_code, w.bucket_id
                               ORDER BY w.ordinal
                           ) AS cumulative_debit_cents
                    FROM waiting w
                ),
                settleable AS (
                    SELECT r.*
                    FROM ranked r
                    JOIN locked_buckets sender_bucket
                      ON sender_bucket.bank_code = r.sender_bank_code
                     AND sender_bucket.bucket_id = r.bucket_id
                    WHERE sender_bucket.balance_cents >= r.cumulative_debit_cents
                      AND EXISTS (
                          SELECT 1
                          FROM locked_buckets receiver_bucket
                          WHERE receiver_bucket.bank_code = r.receiver_bank_code
                            AND receiver_bucket.bucket_id = r.bucket_id
                      )
                ),
                deltas AS (
                    SELECT sender_bank_code AS bank_code,
                           bucket_id,
                           -SUM(amount_cents) AS delta_cents
                    FROM settleable
                    GROUP BY sender_bank_code, bucket_id
                    UNION ALL
                    SELECT receiver_bank_code AS bank_code,
                           bucket_id,
                           SUM(amount_cents) AS delta_cents
                    FROM settleable
                    GROUP BY receiver_bank_code, bucket_id
                ),
                net_deltas AS (
                    SELECT bank_code, bucket_id, SUM(delta_cents) AS delta_cents
                    FROM deltas
                    GROUP BY bank_code, bucket_id
                ),
                updated_funds AS (
                    UPDATE funds_bucket_entity f
                    SET balance_cents = f.balance_cents + d.delta_cents
                    FROM net_deltas d
                    WHERE f.bank_code = d.bank_code
                      AND f.bucket_id = d.bucket_id
                    RETURNING f.bank_code
                ),
                updated_payments AS (
                    UPDATE payment_transaction_entity p
                    SET status = ?
                    FROM settleable s
                    WHERE p.payment_id = s.payment_id
                      AND p.status = ?
                    RETURNING p.payment_id,
                              p.amount_cents,
                              p.sender_bank_code,
                              p.receiver_bank_code,
                              s.ordinal
                )
                SELECT payment_id, amount_cents, sender_bank_code, receiver_bank_code, ordinal
                FROM already_settled
                UNION ALL
                SELECT payment_id, amount_cents, sender_bank_code, receiver_bank_code, ordinal
                FROM updated_payments
                ORDER BY ordinal
                """;

        return jdbcTemplate.execute((ConnectionCallback<List<PaymentTransactionCommand>>) connection -> {
            Array paymentIdArray = connection.createArrayOf("text", paymentIds.toArray(String[]::new));
            try (var statement = connection.prepareStatement(sql)) {
                statement.setArray(1, paymentIdArray);
                statement.setInt(2, BUCKET_COUNT);
                statement.setString(3, currentStatus.name());
                statement.setString(4, settledStatus.name());
                statement.setString(5, currentStatus.name());
                statement.setString(6, settledStatus.name());
                statement.setString(7, settledStatus.name());
                statement.setString(8, currentStatus.name());

                try (ResultSet resultSet = statement.executeQuery()) {
                    List<PaymentTransactionCommand> settledOrAlreadySettledPayments = new ArrayList<>();
                    while (resultSet.next()) {
                        settledOrAlreadySettledPayments.add(toPaymentTransaction(resultSet));
                    }
                    return settledOrAlreadySettledPayments;
                }
            } finally {
                paymentIdArray.free();
            }
        });
    }

    private PaymentTransactionCommand toPaymentTransactionFromResultSet(ResultSet resultSet, int rowNumber) throws SQLException {
        return toPaymentTransaction(resultSet);
    }

    private PaymentTransactionCommand toPaymentTransaction(ResultSet resultSet) throws SQLException {
        PaymentTransactionEntity entity = new PaymentTransactionEntity();
        entity.setPaymentId(resultSet.getString("payment_id"));
        entity.setAmountCents(resultSet.getLong("amount_cents"));
        entity.setSenderBankCode(resultSet.getString("sender_bank_code"));
        entity.setReceiverBankCode(resultSet.getString("receiver_bank_code"));
        return repositoryMapper.toDomain(entity);
    }
}
