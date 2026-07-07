package br.kauan.spi.adapter.output.kafka;

public class RecoverableNotificationPublishException extends RuntimeException {

    public RecoverableNotificationPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
