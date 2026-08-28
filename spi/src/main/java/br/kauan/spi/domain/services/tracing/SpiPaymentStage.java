package br.kauan.spi.domain.services.tracing;

public enum SpiPaymentStage {
    REQUEST_CONSUMED("request_consumed"),
    REQUEST_SAVED("request_saved"),
    ACCEPTANCE_NOTIFICATION_ENQUEUED("acceptance_notification_enqueued"),
    STATUS_RECEIVED("status_received"),
    SETTLEMENT_COMPLETED("settlement_completed"),
    CONFIRMATION_NOTIFICATION_ENQUEUED("confirmation_notification_enqueued");

    private final String eventName;

    SpiPaymentStage(String eventName) {
        this.eventName = eventName;
    }

    String eventName() {
        return eventName;
    }
}
