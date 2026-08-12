package br.kauan.spi.domain.services.audit;

import br.kauan.spi.domain.entity.status.PaymentRejectionReason;
import br.kauan.spi.domain.entity.status.PaymentStatus;

public record PaymentAuditEvent(
        String paymentId,
        PaymentAuditEventType eventType,
        PaymentStatus previousStatus,
        PaymentStatus resultingStatus,
        Long amountCents,
        String senderIspb,
        String receiverIspb,
        Long senderDeltaCents,
        Long receiverDeltaCents,
        PaymentRejectionReason reason
) {
    public PaymentAuditEvent(
            String paymentId,
            PaymentAuditEventType eventType,
            PaymentStatus previousStatus,
            PaymentStatus resultingStatus,
            Long amountCents,
            String senderIspb,
            String receiverIspb,
            Long senderDeltaCents,
            Long receiverDeltaCents
    ) {
        this(
                paymentId,
                eventType,
                previousStatus,
                resultingStatus,
                amountCents,
                senderIspb,
                receiverIspb,
                senderDeltaCents,
                receiverDeltaCents,
                null
        );
    }

    public PaymentAuditEvent {
        if (reason != null && (
                eventType != PaymentAuditEventType.PAYMENT_STATUS_CHANGED
                        || resultingStatus != PaymentStatus.REJECTED
        )) {
            throw new IllegalArgumentException("Rejection reason is only valid for rejected status-change events");
        }
    }
}
