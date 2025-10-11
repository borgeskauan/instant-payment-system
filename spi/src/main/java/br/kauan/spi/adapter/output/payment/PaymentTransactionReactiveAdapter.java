package br.kauan.spi.adapter.output.payment;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.port.output.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Slf4j
@Repository
@RequiredArgsConstructor
public class PaymentTransactionReactiveAdapter implements PaymentTransactionRepository {

    private final R2dbcEntityTemplate r2dbcEntityTemplate;
    private final PaymentTransactionReactiveRepository reactiveRepository;
    private final PaymentTransactionRepositoryMapper repositoryMapper;

    @Override
    public Mono<Void> createTransaction(PaymentTransaction paymentTransaction, PaymentStatus paymentStatus) {
        return Mono.defer(() -> {
                    var paymentTransactionJpaEntity = repositoryMapper.toEntity(paymentTransaction, paymentStatus);
                    return r2dbcEntityTemplate.insert(paymentTransactionJpaEntity);
                })
                .doOnError(error ->
                        log.error("Failed to save payment transaction: {}", paymentTransaction, error)
                )
                .then();
    }

    @Override
    public Mono<Void> updateTransaction(PaymentTransaction paymentTransaction, PaymentStatus paymentStatus) {
        return Mono.defer(() -> {
                    var paymentTransactionJpaEntity = repositoryMapper.toEntity(paymentTransaction, paymentStatus);
                    return r2dbcEntityTemplate.update(paymentTransactionJpaEntity);
                })
                .doOnError(error ->
                        log.error("Failed to update payment transaction: {}", paymentTransaction, error)
                )
                .then();
    }

    @Override
    public Mono<PaymentTransaction> findById(String originalPaymentId) {
        return reactiveRepository.findByPaymentId(originalPaymentId)
                .map(repositoryMapper::toDomain)
                .doOnError(error ->
                        log.error("Error finding payment transaction: {}", originalPaymentId, error)
                )
                .switchIfEmpty(
                        Mono.defer(() -> {
                            log.debug("No payment transaction found with ID: {}", originalPaymentId);
                            return Mono.empty();
                        })
                );
    }
}