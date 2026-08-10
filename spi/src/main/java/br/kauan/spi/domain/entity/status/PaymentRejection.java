package br.kauan.spi.domain.entity.status;

import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;

public record PaymentRejection(
        PaymentTransactionCommand payment,
        PaymentRejectionReason reason
) {
    public PaymentRejection {
        if (payment == null) {
            throw new IllegalArgumentException("Rejected payment cannot be null");
        }
    }
}
