package br.kauan.spi.adapter.output.outbox;

import br.kauan.spi.adapter.output.kafka.NotificationPublication;

import java.util.List;

public record NotificationOutboxBatchReady(List<NotificationPublication> notifications) {

    public NotificationOutboxBatchReady {
        notifications = List.copyOf(notifications);
        if (notifications.isEmpty()) {
            throw new IllegalArgumentException("Notification outbox batch cannot be empty");
        }
    }
}
