package br.kauan.spi.domain.services.audit;

import br.kauan.spi.domain.entity.status.PaymentRejectionCause;
import br.kauan.spi.domain.entity.status.PaymentState;
import br.kauan.spi.domain.entity.status.StatusReasonCode;

import java.util.List;

public record PaymentAuditEvent(
        String paymentId,
        PaymentAuditEventType eventType,
        PaymentState previousState,
        PaymentState resultingState,
        Long amountCents,
        String senderIspb,
        String receiverIspb,
        Long senderDeltaCents,
        Long receiverDeltaCents,
        PaymentRejectionCause rejectionCause,
        List<StatusReasonCode> externalReasonCodes
) {
    public PaymentAuditEvent {
        externalReasonCodes = StatusReasonCode.normalize(externalReasonCodes);
        if (rejectionCause != null
                && (eventType != PaymentAuditEventType.PAYMENT_REJECTED
                || resultingState != PaymentState.REJECTED)) {
            throw new IllegalArgumentException("Rejection cause is only valid for rejected payment events");
        }
        if (!externalReasonCodes.isEmpty()
                && eventType == PaymentAuditEventType.PAYMENT_RESERVED) {
            throw new IllegalArgumentException("External reason codes are not valid for payment reservation events");
        }
    }
}
