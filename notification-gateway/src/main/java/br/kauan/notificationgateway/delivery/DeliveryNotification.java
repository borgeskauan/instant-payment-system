package br.kauan.notificationgateway.delivery;

public record DeliveryNotification(
        int partition,
        long offset,
        String recipientIspb,
        String communicationId,
        byte[] payload
) {
}
