package br.kauan.notificationgateway.delivery;

public record NotificationDelivery(
        String communicationId,
        String recipientIspb,
        byte[] payload
) {
}
