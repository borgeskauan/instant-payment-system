package br.kauan.spi.port.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;

public record PaymentStatusTransition(
        String paymentId,
        PaymentStatus previousStatus,
        PaymentStatus resultingStatus
) {
    public PaymentStatusTransition {
        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException("Payment ID cannot be null or blank");
        }
        if (previousStatus == null || resultingStatus == null) {
            throw new IllegalArgumentException("Payment statuses cannot be null");
        }
        if (previousStatus == resultingStatus) {
            throw new IllegalArgumentException("Payment status transition must change the status");
        }
    }
}
