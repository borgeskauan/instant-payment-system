package br.kauan.spi.adapter.output.outbox;

public record NotificationPublicationFailure(
        String communicationId,
        String error
) {
}
