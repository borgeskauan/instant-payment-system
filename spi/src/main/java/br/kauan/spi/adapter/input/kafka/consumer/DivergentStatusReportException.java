package br.kauan.spi.adapter.input.kafka.consumer;

public class DivergentStatusReportException extends RuntimeException {

    public DivergentStatusReportException(String paymentId) {
        super("Divergent status report: " + paymentId);
    }
}
