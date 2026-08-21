package br.kauan.notificationgateway.delivery;

public record NotificationDelivery(
        long deliveryPosition,
        String communicationId,
        String recipientIspb,
        byte[] payload
) {
}
