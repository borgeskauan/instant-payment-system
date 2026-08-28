package br.kauan.spi.adapter.input.kafka.consumer;

public class StatusReportConflictException extends RuntimeException {

    public StatusReportConflictException(String paymentId) {
        super("Status report conflicts with the known payment state: " + paymentId);
    }
}
