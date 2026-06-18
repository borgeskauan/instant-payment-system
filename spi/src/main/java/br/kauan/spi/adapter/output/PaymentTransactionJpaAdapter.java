package br.kauan.spi.adapter.output;

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
                currency,
                description,
                status,
                sender_name,
                sender_tax_id,
                sender_pix_key,
                sender_account_number,
                sender_account_branch,
                sender_account_type,
                sender_bank_code,
                receiver_name,
                receiver_tax_id,
                receiver_pix_key,
                receiver_account_number,
                receiver_account_branch,
                receiver_account_type,
                receiver_bank_code
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
        var entity = repositoryMapper.toEntity(paymentTransaction, paymentStatus);
        jdbcTemplate.update(
                INSERT_PAYMENT_TRANSACTION_SQL,
                entity.getPaymentId(),
                entity.getAmount(),
                entity.getCurrency(),
                entity.getDescription(),
                entity.getStatus(),
                entity.getSenderName(),
                entity.getSenderTaxId(),
                entity.getSenderPixKey(),
                entity.getSenderAccountNumber(),
                entity.getSenderAccountBranch(),
                entity.getSenderAccountType(),
                entity.getSenderBankCode(),
                entity.getReceiverName(),
                entity.getReceiverTaxId(),
                entity.getReceiverPixKey(),
                entity.getReceiverAccountNumber(),
                entity.getReceiverAccountBranch(),
                entity.getReceiverAccountType(),
                entity.getReceiverBankCode()
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
