package br.kauan.spi.port.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;

import java.util.List;
import java.util.Optional;

public interface PaymentTransactionRepository {

    PaymentTransactionPersistenceResult storeAndClassifyIncomingPaymentRequests(
            List<PaymentTransactionCommand> paymentTransactions
    );

    void updateStatuses(List<String> paymentIds, PaymentStatus paymentStatus);

    Optional<PaymentTransactionCommand> findById(String originalPaymentId);
}
