package br.kauan.notificationgateway.grpc;

public record DeliveryCursor(
        String recipientIspb,
        String topicGeneration,
        int partition,
        long lastExaminedOffset
) {
}
