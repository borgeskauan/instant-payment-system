package br.kauan.paymentserviceprovider.domain.dto;

import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentDirection;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentLifecycleStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentSummary(
        String paymentId,
        PaymentDirection direction,
        Counterparty counterparty,
        BigDecimal amount,
        String currency,
        PaymentLifecycleStatus status,
        Instant createdAt
) {
    public record Counterparty(String name, String pixKey) {
    }
}
