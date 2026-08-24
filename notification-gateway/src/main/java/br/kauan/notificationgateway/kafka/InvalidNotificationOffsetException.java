package br.kauan.notificationgateway.kafka;

public final class InvalidNotificationOffsetException extends RuntimeException {

    public InvalidNotificationOffsetException() {
        super("notification cursor points beyond the log tail");
    }
}
