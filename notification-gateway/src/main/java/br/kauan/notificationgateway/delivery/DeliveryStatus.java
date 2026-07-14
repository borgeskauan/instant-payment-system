package br.kauan.notificationgateway.delivery;

public enum DeliveryStatus {
    PENDING,
    IN_FLIGHT,
    RETRYABLE_FAILED,
    ACKED
}
