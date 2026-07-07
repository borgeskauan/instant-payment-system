package br.kauan.spi.adapter.input.kafka.consumer;

public class DivergentDuplicatePaymentException extends RuntimeException {

    public DivergentDuplicatePaymentException(String paymentId) {
        super("Divergent duplicate payment request: " + paymentId);
    }
}
