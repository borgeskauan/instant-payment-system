package br.kauan.spi.adapter.output;

import br.kauan.spi.Utils;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.output.PaymentTransactionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class PaymentTransactionJpaAdapter implements PaymentTransactionRepository {

    private static final String INSERT_PAYMENT_TRANSACTION_SQL = """
            INSERT INTO payment_transaction_entity (
                payment_id,
                amount_cents,
                status,
                sender_bank_code,
                receiver_bank_code
            ) VALUES (?, ?, ?, ?, ?)
            """;

    private static final String UPDATE_PAYMENT_STATUS_SQL = """
            UPDATE payment_transaction_entity
            SET status = ?
            WHERE payment_id = ?
            """;

    private final PaymentTransactionJpaClient paymentTransactionJpaClient;
    private final PaymentTransactionRepositoryMapper repositoryMapper;
    private final JdbcTemplate jdbcTemplate;

    public PaymentTransactionJpaAdapter(
            PaymentTransactionJpaClient paymentTransactionJpaClient,
            PaymentTransactionRepositoryMapper repositoryMapper,
            JdbcTemplate jdbcTemplate
    ) {
        this.paymentTransactionJpaClient = paymentTransactionJpaClient;
        this.repositoryMapper = repositoryMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void saveTransactions(List<PaymentTransactionCommand> paymentTransactions, PaymentStatus paymentStatus) {
        if (paymentTransactions.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate(
                INSERT_PAYMENT_TRANSACTION_SQL,
                paymentTransactions,
                paymentTransactions.size(),
                (statement, paymentTransaction) -> {
                    statement.setString(1, paymentTransaction.getPaymentId());
                    statement.setLong(2, paymentTransaction.getAmountCents());
                    statement.setString(3, paymentStatus.name());
                    statement.setString(4, Utils.getBankCode(paymentTransaction.getSender()));
                    statement.setString(5, Utils.getBankCode(paymentTransaction.getReceiver()));
                }
        );
    }

    @Override
    public void updateStatuses(List<String> paymentIds, PaymentStatus paymentStatus) {
        if (paymentIds.isEmpty()) {
            return;
        }

        int[][] updatedRows = jdbcTemplate.batchUpdate(
                UPDATE_PAYMENT_STATUS_SQL,
                paymentIds,
                paymentIds.size(),
                (statement, paymentId) -> {
                    statement.setString(1, paymentStatus.name());
                    statement.setString(2, paymentId);
                }
        );

        for (int batchIndex = 0; batchIndex < updatedRows.length; batchIndex++) {
            for (int updateCount : updatedRows[batchIndex]) {
                if (updateCount == 0) {
                    throw new IllegalStateException("Payment transaction not found while updating status");
                }
            }
        }
    }

    @Override
    public Optional<PaymentTransactionCommand> findById(String originalPaymentId) {
        return paymentTransactionJpaClient.findById(originalPaymentId).map(repositoryMapper::toDomain);
    }
}
