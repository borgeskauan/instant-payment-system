package br.kauan.spi.adapter.output.notification;

import br.kauan.spi.adapter.output.kafka.NotificationPublication;

import java.util.List;

public record OutboundNotificationBatchReady(List<NotificationPublication> notifications) {

    public OutboundNotificationBatchReady {
        notifications = List.copyOf(notifications);
    }
}
