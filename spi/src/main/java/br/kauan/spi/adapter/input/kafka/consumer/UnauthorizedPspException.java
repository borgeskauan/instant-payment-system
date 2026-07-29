package br.kauan.spi.adapter.input.kafka.consumer;

public class UnauthorizedPspException extends RuntimeException {

    public UnauthorizedPspException(String paymentId, String authenticatedIspb) {
        super("PSP " + authenticatedIspb + " is not authorized for payment " + paymentId);
    }
}
