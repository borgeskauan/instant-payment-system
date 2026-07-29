package br.kauan.kafkaproducer.security;

public class PspAuthenticationException extends RuntimeException {

    public PspAuthenticationException(String message) {
        super(message);
    }

    public PspAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
