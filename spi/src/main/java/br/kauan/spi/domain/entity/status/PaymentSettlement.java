package br.kauan.spi.domain.entity.status;

import br.kauan.spi.domain.entity.transfer.PaymentReference;

import java.util.List;
import java.util.Objects;

public record PaymentSettlement(
        PaymentReference payment,
        List<StatusReasonCode> reasonCodes
) {
    public PaymentSettlement {
        Objects.requireNonNull(payment, "Settled payment cannot be null");
        reasonCodes = StatusReasonCode.normalize(reasonCodes);
    }
}
