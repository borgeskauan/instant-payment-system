package br.kauan.notificationgateway.delivery;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public final class NotificationDeliveryReader {

    private final RecentNotificationBuffer buffer;
    private final DeliveryIndexRepository repository;

    public NotificationDeliveryReader(
            RecentNotificationBuffer buffer,
            DeliveryIndexRepository repository
    ) {
        this.buffer = buffer;
        this.repository = repository;
    }

    public List<NotificationDelivery> findAfter(String recipientIspb, long position, int limit) {
        List<NotificationDelivery> buffered = buffer.findContiguousAfter(recipientIspb, position, limit);
        return buffered.isEmpty()
                ? repository.findAfter(recipientIspb, position, limit)
                : buffered;
    }
}
