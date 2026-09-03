package br.kauan.spi.domain.entity.status;

import java.util.List;
import java.util.Objects;

public record IncomingStatusReportCommand(
        String originalPaymentId,
        StatusReportOutcome outcome,
        List<StatusReasonCode> reasonCodes
) {
    public IncomingStatusReportCommand {
        if (originalPaymentId == null || originalPaymentId.isBlank()) {
            throw new IllegalArgumentException("Payment ID is required");
        }
        Objects.requireNonNull(outcome, "Status report outcome is required");
        reasonCodes = StatusReasonCode.normalize(reasonCodes);
        if (outcome == StatusReportOutcome.REJECTED && reasonCodes.isEmpty()) {
            throw new IllegalArgumentException("Rejected status report requires at least one reason code");
        }
    }
}
