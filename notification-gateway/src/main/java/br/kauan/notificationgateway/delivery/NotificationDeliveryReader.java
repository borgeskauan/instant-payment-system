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
        if (limit <= 0) {
            return List.of();
        }

        RecentNotificationBuffer.Lookup lookup = buffer.lookupAfter(recipientIspb, position, limit);
        if (lookup.state() == RecentNotificationBuffer.LookupState.DATA) {
            return lookup.deliveries();
        }
        if (lookup.state() == RecentNotificationBuffer.LookupState.KNOWN_TAIL) {
            return List.of();
        }

        List<NotificationDelivery> persisted = repository.findAfter(recipientIspb, position, limit);
        if (persisted.size() < limit) {
            long confirmedThrough = persisted.isEmpty()
                    ? position
                    : persisted.getLast().deliveryPosition();
            buffer.confirmThrough(recipientIspb, confirmedThrough);
        }
        return persisted;
    }
}
