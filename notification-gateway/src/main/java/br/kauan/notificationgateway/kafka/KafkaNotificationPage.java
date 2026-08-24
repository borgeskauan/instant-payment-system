package br.kauan.notificationgateway.kafka;

import java.util.List;

public record KafkaNotificationPage(
        List<KafkaNotificationRecord> notifications,
        long lastExaminedOffset,
        boolean atTail
) {
    public KafkaNotificationPage {
        notifications = List.copyOf(notifications);
    }
}
