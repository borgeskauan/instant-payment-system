package br.kauan.spi.adapter.input.kafka.infrastructure.error;

public class InfrastructureUnavailableException extends RuntimeException {

    public InfrastructureUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
