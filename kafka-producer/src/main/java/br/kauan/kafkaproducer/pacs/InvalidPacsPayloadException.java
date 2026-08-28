package br.kauan.kafkaproducer.pacs;

public class InvalidPacsPayloadException extends IllegalArgumentException {

    public InvalidPacsPayloadException(String message) {
        super(message);
    }

    public InvalidPacsPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
