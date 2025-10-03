package br.kauan.spi.domain.services.notification;

public class NotificationException extends RuntimeException {

    public NotificationException(String message, Throwable cause) {
        super(message, cause);
    }
}