package br.kauan.spi.port.output;

import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;

import java.util.List;

public record PaymentTransactionPersistenceResult(
        List<PaymentTransactionCommand> acceptanceRequests,
        List<PaymentTransactionCommand> divergentDuplicates
) {
}
