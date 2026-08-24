package br.kauan.notificationgateway.kafka;

public record KafkaNotificationRecord(
        int partition,
        long offset,
        String recipientIspb,
        String communicationId,
        byte[] payload
) {
}
