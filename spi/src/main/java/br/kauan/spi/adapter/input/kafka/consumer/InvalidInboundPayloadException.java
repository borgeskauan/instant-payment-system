package br.kauan.spi.adapter.input.kafka.consumer;

public class InvalidInboundPayloadException extends RuntimeException {

    public InvalidInboundPayloadException(String message) {
        super(message);
    }

    public InvalidInboundPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
