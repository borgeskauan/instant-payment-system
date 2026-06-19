package br.kauan.spi.adapter.output;

import br.kauan.spi.Utils;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.port.output.PaymentTransactionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
        jdbcTemplate.update(
                INSERT_PAYMENT_TRANSACTION_SQL,
                paymentTransaction.getPaymentId(),
                paymentTransaction.getAmount(),
                paymentStatus.name(),
                Utils.getBankCode(paymentTransaction.getSender()),
                Utils.getBankCode(paymentTransaction.getReceiver())
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
