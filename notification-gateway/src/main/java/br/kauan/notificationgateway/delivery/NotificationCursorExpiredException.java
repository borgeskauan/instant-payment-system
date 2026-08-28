package br.kauan.notificationgateway.delivery;

public final class NotificationCursorExpiredException extends RuntimeException {

    public NotificationCursorExpiredException() {
        super("notification cursor expired");
    }
}
