package br.kauan.spi.domain.services.audit;

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
        Long receiverDeltaCents
) {
}
