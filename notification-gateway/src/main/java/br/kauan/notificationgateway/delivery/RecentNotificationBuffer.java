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

    enum LookupState {
        DATA,
        KNOWN_TAIL,
        MISS
    }

    record Lookup(LookupState state, List<NotificationDelivery> deliveries) {

        Lookup {
            deliveries = List.copyOf(deliveries);
        }

        private static Lookup data(List<NotificationDelivery> deliveries) {
            return new Lookup(LookupState.DATA, deliveries);
        }

        private static Lookup knownTail() {
            return new Lookup(LookupState.KNOWN_TAIL, List.of());
        }

        private static Lookup miss() {
            return new Lookup(LookupState.MISS, List.of());
        }
    }

    public void addAll(List<NotificationDelivery> deliveries) {
        for (NotificationDelivery delivery : deliveries) {
            windows.computeIfAbsent(delivery.recipientIspb(), ignored -> new RecipientWindow())
                    .add(delivery);
        }
    }

    Lookup lookupAfter(
            String recipientIspb,
            long position,
            int limit
    ) {
        if (limit <= 0) {
            return Lookup.miss();
        }
        RecipientWindow window = windows.get(recipientIspb);
        return window == null ? Lookup.miss() : window.lookupAfter(position, limit);
    }

    void confirmThrough(String recipientIspb, long position) {
        if (position < 0) {
            throw new IllegalArgumentException("confirmed delivery position must not be negative");
        }
        windows.computeIfAbsent(recipientIspb, ignored -> new RecipientWindow())
                .confirmThrough(position);
    }

    private static final class RecipientWindow {

        private final NavigableMap<Long, NotificationDelivery> deliveries = new TreeMap<>();
        private Long confirmedThrough;

        synchronized void add(NotificationDelivery delivery) {
            deliveries.put(delivery.deliveryPosition(), delivery);
            advanceConfirmedThrough();
            while (deliveries.size() > CAPACITY_PER_RECIPIENT) {
                deliveries.pollFirstEntry();
            }
        }

        synchronized void confirmThrough(long position) {
            if (confirmedThrough == null || position > confirmedThrough) {
                confirmedThrough = position;
            }
            advanceConfirmedThrough();
        }

        synchronized Lookup lookupAfter(long position, int limit) {
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
            if (!result.isEmpty()) {
                return Lookup.data(result);
            }
            if (confirmedThrough != null
                    && position == confirmedThrough
                    && deliveries.higherKey(position) == null) {
                return Lookup.knownTail();
            }
            return Lookup.miss();
        }

        private void advanceConfirmedThrough() {
            while (confirmedThrough != null && confirmedThrough < Long.MAX_VALUE) {
                long nextPosition = confirmedThrough + 1;
                if (!deliveries.containsKey(nextPosition)) {
                    return;
                }
                confirmedThrough = nextPosition;
            }
        }
    }
}
