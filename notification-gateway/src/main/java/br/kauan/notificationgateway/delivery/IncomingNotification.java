package br.kauan.notificationgateway.delivery;

public record IncomingNotification(
        String communicationId,
        String recipientIspb,
        String eventType,
        String paymentId,
        String status,
        String schemaVersion,
        byte[] payload
) {
}
