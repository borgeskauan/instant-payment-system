package br.kauan.spi.application.notification;

import java.util.List;

public record OutboundNotificationBatchReady(List<OutboundNotification> notifications) {

    public OutboundNotificationBatchReady {
        notifications = List.copyOf(notifications);
    }
}
