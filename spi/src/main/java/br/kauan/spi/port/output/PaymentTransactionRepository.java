package br.kauan.spi.port.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;

import java.util.Optional;

public interface PaymentTransactionRepository {

    void saveTransaction(PaymentTransaction paymentTransaction, PaymentStatus paymentStatus);

    Optional<PaymentTransaction> findById(String originalPaymentId);
}
