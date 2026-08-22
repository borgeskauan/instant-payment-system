package br.kauan.notificationgateway.delivery;

public record IncomingNotification(
        String communicationId,
        String recipientIspb,
        byte[] payload
) {
}
