package br.kauan.spi.adapter.output;

import br.kauan.spi.Utils;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
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
                amount,
                status,
                sender_bank_code,
                receiver_bank_code
            ) VALUES (?, ?, ?, ?, ?)
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
    public void saveTransaction(PaymentTransaction paymentTransaction, PaymentStatus paymentStatus) {
        saveTransactions(List.of(paymentTransaction), paymentStatus);
    }

    @Override
    public void saveTransactions(List<PaymentTransaction> paymentTransactions, PaymentStatus paymentStatus) {
        if (paymentTransactions.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate(
                INSERT_PAYMENT_TRANSACTION_SQL,
                paymentTransactions,
                paymentTransactions.size(),
                (statement, paymentTransaction) -> {
                    statement.setString(1, paymentTransaction.getPaymentId());
                    statement.setBigDecimal(2, paymentTransaction.getAmount());
                    statement.setString(3, paymentStatus.name());
                    statement.setString(4, Utils.getBankCode(paymentTransaction.getSender()));
                    statement.setString(5, Utils.getBankCode(paymentTransaction.getReceiver()));
                }
        );
    }

    @Override
    public void updateStatus(String paymentId, PaymentStatus paymentStatus) {
        int updatedRows = paymentTransactionJpaClient.updateStatus(paymentId, paymentStatus.name());
        if (updatedRows == 0) {
            throw new IllegalStateException("Payment transaction not found: " + paymentId);
        }
    }

    @Override
    public Optional<PaymentTransaction> findById(String originalPaymentId) {
        return paymentTransactionJpaClient.findById(originalPaymentId).map(repositoryMapper::toDomain);
    }
}
