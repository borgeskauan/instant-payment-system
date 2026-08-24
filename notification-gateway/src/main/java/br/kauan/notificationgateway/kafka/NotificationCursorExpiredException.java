package br.kauan.notificationgateway.kafka;

public final class NotificationCursorExpiredException extends RuntimeException {

    public NotificationCursorExpiredException() {
        super("notification cursor expired");
    }
}
