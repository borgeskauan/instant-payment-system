package br.kauan.spi.domain.entity.status;

import br.kauan.spi.domain.entity.transfer.PaymentReference;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;

import java.util.List;

public record PaymentRejection(
        PaymentReference payment,
        PaymentRejectionCause cause,
        List<StatusReasonCode> externalReasonCodes
) {
    public PaymentRejection {
        if (payment == null) {
            throw new IllegalArgumentException("Rejected payment cannot be null");
        }
        externalReasonCodes = StatusReasonCode.normalize(externalReasonCodes);
        boolean internalRejection = cause != null && externalReasonCodes.isEmpty();
        boolean externalRejection = cause == null && !externalReasonCodes.isEmpty();
        if (!internalRejection && !externalRejection) {
            throw new IllegalArgumentException(
                    "A rejected payment must have exactly one internal or external rejection origin"
            );
        }
    }

    public static PaymentRejection insufficientFunds(PaymentTransactionCommand payment) {
        return new PaymentRejection(
                PaymentReference.from(payment),
                PaymentRejectionCause.INSUFFICIENT_FUNDS,
                List.of()
        );
    }

    public static PaymentRejection receiverRejected(
            PaymentReference payment,
            List<StatusReasonCode> reasonCodes
    ) {
        return new PaymentRejection(payment, null, reasonCodes);
    }
}
