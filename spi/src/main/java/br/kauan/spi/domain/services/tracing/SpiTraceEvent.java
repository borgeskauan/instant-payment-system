package br.kauan.spi.domain.services.tracing;

public enum SpiTraceEvent {
    REQUEST_RECEIVED("request_received"),
    REQUEST_CONSUMED("request_consumed"),
    REQUEST_SAVED("request_saved"),
    ACCEPTANCE_NOTIFICATION_ENQUEUED("acceptance_notification_enqueued"),
    STATUS_RECEIVED("status_received"),
    STATUS_CONSUMED("status_consumed"),
    SETTLEMENT_COMPLETED("settlement_completed"),
    CONFIRMATION_NOTIFICATION_ENQUEUED("confirmation_notification_enqueued");

    private final String eventName;

    SpiTraceEvent(String eventName) {
        this.eventName = eventName;
    }

    public String eventName() {
        return eventName;
    }
}
