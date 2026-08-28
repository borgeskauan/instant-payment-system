package br.kauan.spi.domain.entity.status;

import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;

import java.util.List;
import java.util.Objects;

public record PaymentSettlement(
        PaymentTransactionCommand payment,
        List<StatusReasonCode> reasonCodes
) {
    public PaymentSettlement {
        Objects.requireNonNull(payment, "Settled payment cannot be null");
        reasonCodes = StatusReasonCode.normalize(reasonCodes);
    }
}
