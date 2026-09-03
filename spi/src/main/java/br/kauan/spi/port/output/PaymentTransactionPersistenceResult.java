package br.kauan.spi.port.output;

import br.kauan.spi.domain.entity.security.AuthenticatedPaymentRequest;
import br.kauan.spi.domain.entity.status.PaymentRejection;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;

import java.util.List;

public record PaymentTransactionPersistenceResult(
        List<PaymentTransactionCommand> acceptanceRequests,
        List<PaymentTransactionCommand> createdPayments,
        List<PaymentRejection> rejectedPayments,
        List<AuthenticatedPaymentRequest> divergentDuplicates,
        List<AuthenticatedPaymentRequest> unauthorizedRequests
) {
}
