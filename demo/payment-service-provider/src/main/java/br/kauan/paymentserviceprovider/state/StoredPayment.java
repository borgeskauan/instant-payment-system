package br.kauan.paymentserviceprovider.state;

import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentLifecycleStatus;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;

import java.time.Instant;

public record StoredPayment(
        PaymentTransaction payment,
        PaymentLifecycleStatus status,
        Instant createdAt
) {
    StoredPayment withStatus(PaymentLifecycleStatus newStatus) {
        return new StoredPayment(payment, newStatus, createdAt);
    }
}
