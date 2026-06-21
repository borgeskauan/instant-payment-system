package br.kauan.spi.port.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;

import java.util.List;
import java.util.Optional;

public interface PaymentTransactionRepository {

    void saveTransaction(PaymentTransaction paymentTransaction, PaymentStatus paymentStatus);

    void saveTransactions(List<PaymentTransaction> paymentTransactions, PaymentStatus paymentStatus);

    void updateStatus(String paymentId, PaymentStatus paymentStatus);

    Optional<PaymentTransaction> findById(String originalPaymentId);
}
