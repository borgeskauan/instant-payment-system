package br.kauan.spi.port.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import reactor.core.publisher.Mono;

public interface PaymentTransactionRepository {

    Mono<Void> createTransaction(PaymentTransaction paymentTransaction, PaymentStatus paymentStatus);

    Mono<Void> updateTransaction(PaymentTransaction paymentTransaction, PaymentStatus paymentStatus);

    Mono<PaymentTransaction> findById(String originalPaymentId);
}
