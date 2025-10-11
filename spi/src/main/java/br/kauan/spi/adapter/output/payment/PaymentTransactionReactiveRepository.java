package br.kauan.spi.adapter.output.payment;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface PaymentTransactionReactiveRepository extends ReactiveCrudRepository<PaymentTransactionEntity, String> {
    Mono<PaymentTransactionEntity> findByPaymentId(String paymentId);
}
