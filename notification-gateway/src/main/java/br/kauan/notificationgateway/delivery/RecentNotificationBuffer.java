package br.kauan.notificationgateway.delivery;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

@Component
public final class RecentNotificationBuffer {

    static final int CAPACITY_PER_RECIPIENT = 150;

    private final ConcurrentHashMap<String, RecipientWindow> windows = new ConcurrentHashMap<>();

    public void addAll(List<NotificationDelivery> deliveries) {
        for (NotificationDelivery delivery : deliveries) {
            windows.computeIfAbsent(delivery.recipientIspb(), ignored -> new RecipientWindow())
                    .add(delivery);
        }
    }

    public List<NotificationDelivery> findContiguousAfter(
            String recipientIspb,
            long position,
            int limit
    ) {
        if (limit <= 0) {
            return List.of();
        }
        RecipientWindow window = windows.get(recipientIspb);
        return window == null ? List.of() : window.findContiguousAfter(position, limit);
    }

    private static final class RecipientWindow {

        private final NavigableMap<Long, NotificationDelivery> deliveries = new TreeMap<>();

        synchronized void add(NotificationDelivery delivery) {
            deliveries.put(delivery.deliveryPosition(), delivery);
            while (deliveries.size() > CAPACITY_PER_RECIPIENT) {
                deliveries.pollFirstEntry();
            }
        }

        synchronized List<NotificationDelivery> findContiguousAfter(long position, int limit) {
            long expectedPosition = position + 1;
            List<NotificationDelivery> result = new ArrayList<>(Math.min(limit, deliveries.size()));
            while (result.size() < limit) {
                NotificationDelivery delivery = deliveries.get(expectedPosition);
                if (delivery == null) {
                    break;
                }
                result.add(delivery);
                expectedPosition++;
            }
            return List.copyOf(result);
        }
    }
}
