package br.kauan.spi.adapter.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.port.output.PaymentTransactionRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class PaymentTransactionJpaAdapter implements PaymentTransactionRepository {

    private final PaymentTransactionJpaClient paymentTransactionJpaClient;
    private final PaymentTransactionRepositoryMapper repositoryMapper;

    public PaymentTransactionJpaAdapter(PaymentTransactionJpaClient paymentTransactionJpaClient, PaymentTransactionRepositoryMapper repositoryMapper) {
        this.paymentTransactionJpaClient = paymentTransactionJpaClient;
        this.repositoryMapper = repositoryMapper;
    }

    @Override
    public void saveTransaction(PaymentTransaction paymentTransaction, PaymentStatus paymentStatus) {
        var paymentTransactionJpaEntity = repositoryMapper.toEntity(paymentTransaction, paymentStatus);
        paymentTransactionJpaClient.save(paymentTransactionJpaEntity);
    }

    @Override
    public Optional<PaymentTransaction> findById(String originalPaymentId) {
        return paymentTransactionJpaClient.findById(originalPaymentId).map(repositoryMapper::toDomain);
    }
}
