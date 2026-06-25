package br.kauan.spi.port.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;

import java.util.List;
import java.util.Optional;

public interface PaymentTransactionRepository {

    void saveTransactions(List<PaymentTransactionCommand> paymentTransactions, PaymentStatus paymentStatus);

    void updateStatuses(List<String> paymentIds, PaymentStatus paymentStatus);

    Optional<PaymentTransactionCommand> findById(String originalPaymentId);
}
