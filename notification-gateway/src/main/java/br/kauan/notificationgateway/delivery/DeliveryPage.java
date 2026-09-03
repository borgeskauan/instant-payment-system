package br.kauan.notificationgateway.delivery;

import java.util.List;

public record DeliveryPage(
        List<DeliveryNotification> notifications,
        long lastExaminedOffset,
        boolean atTail
) {
    public DeliveryPage {
        notifications = List.copyOf(notifications);
    }
}
