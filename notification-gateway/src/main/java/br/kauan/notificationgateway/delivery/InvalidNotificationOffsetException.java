package br.kauan.notificationgateway.delivery;

public final class InvalidNotificationOffsetException extends RuntimeException {

    public InvalidNotificationOffsetException() {
        super("notification cursor points beyond the log tail");
    }
}
