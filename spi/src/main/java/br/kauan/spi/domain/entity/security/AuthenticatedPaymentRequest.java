package br.kauan.spi.domain.entity.security;

import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;

public record AuthenticatedPaymentRequest(
        int sourceOrdinal,
        String authenticatedIspb,
        PaymentTransactionCommand command
) {
}
