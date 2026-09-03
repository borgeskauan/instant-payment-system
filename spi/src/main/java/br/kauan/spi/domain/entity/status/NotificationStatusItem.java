package br.kauan.spi.domain.entity.status;

import java.util.List;
import java.util.Objects;

public record NotificationStatusItem(
        String originalPaymentId,
        NotificationStatus status,
        List<StatusReasonCode> reasonCodes
) {
    public NotificationStatusItem {
        if (originalPaymentId == null || originalPaymentId.isBlank()) {
            throw new IllegalArgumentException("Payment ID is required");
        }
        Objects.requireNonNull(status, "Notification status is required");
        reasonCodes = StatusReasonCode.normalize(reasonCodes);
    }
}
