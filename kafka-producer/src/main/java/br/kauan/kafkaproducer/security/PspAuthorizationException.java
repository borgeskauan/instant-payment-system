package br.kauan.kafkaproducer.security;

public class PspAuthorizationException extends RuntimeException {

    public PspAuthorizationException(String message) {
        super(message);
    }
}
