package br.kauan.notificationgateway.delivery;

import br.kauan.notificationgateway.config.NotificationGatewayProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A bounded acceleration window over each Kafka partition. The window keeps
 * every record, including records addressed to other PSPs, so a cursor can
 * advance over the exact offsets that were examined.
 */
@Component
public final class RecentNotificationWindow {

    private final int capacityPerPartition;
    private final ConcurrentHashMap<Integer, PartitionWindow> windows = new ConcurrentHashMap<>();

    @Autowired
    public RecentNotificationWindow(NotificationGatewayProperties properties) {
        this(properties.kafka().recentWindowCapacityPerPartition());
    }

    public RecentNotificationWindow(int capacityPerPartition) {
        this.capacityPerPartition = capacityPerPartition;
    }

    public enum LookupState {
        HIT,
        MISS
    }

    public record Lookup(
            LookupState state,
            List<DeliveryNotification> notifications,
            long lastExaminedOffset,
            boolean atTail
    ) {
        private static Lookup hit(
                List<DeliveryNotification> notifications,
                long lastExaminedOffset,
                boolean atTail
        ) {
            return new Lookup(LookupState.HIT, notifications, lastExaminedOffset, atTail);
        }

        private static Lookup miss(long offset) {
            return new Lookup(LookupState.MISS, List.of(), offset, false);
        }
    }

    public void add(DeliveryNotification record) {
        windows.computeIfAbsent(record.partition(), ignored -> new PartitionWindow(capacityPerPartition))
                .add(record);
    }

    public Lookup lookup(
            int partition,
            String recipientIspb,
            long afterOffset,
            int notificationLimit,
            int scanLimit
    ) {
        if (notificationLimit < 1 || scanLimit < notificationLimit) {
            return Lookup.miss(afterOffset);
        }
        PartitionWindow window = windows.get(partition);
        return window == null
                ? Lookup.miss(afterOffset)
                : window.lookup(recipientIspb, afterOffset, notificationLimit, scanLimit);
    }

    private static final class PartitionWindow {

        private final int capacity;
        private final NavigableMap<Long, DeliveryNotification> records = new TreeMap<>();

        private PartitionWindow(int capacity) {
            this.capacity = capacity;
        }

        synchronized void add(DeliveryNotification record) {
            if (records.isEmpty()) {
                records.put(record.offset(), record);
            } else if (record.offset() >= records.firstKey() && record.offset() <= records.lastKey()) {
                records.put(record.offset(), record);
            } else if (record.offset() == records.lastKey() + 1) {
                records.put(record.offset(), record);
            } else if (record.offset() > records.lastKey() + 1) {
                records.clear();
                records.put(record.offset(), record);
            }
            while (records.size() > capacity) {
                records.pollFirstEntry();
            }
        }

        synchronized Lookup lookup(
                String recipientIspb,
                long afterOffset,
                int notificationLimit,
                int scanLimit
        ) {
            long firstOffset = records.firstKey();
            long tailOffset = records.lastKey();
            if (afterOffset < firstOffset - 1 || afterOffset > tailOffset) {
                return Lookup.miss(afterOffset);
            }
            if (afterOffset == tailOffset) {
                return Lookup.hit(List.of(), afterOffset, true);
            }

            List<DeliveryNotification> matches = new ArrayList<>(notificationLimit);
            long lastExamined = afterOffset;
            int examined = 0;
            for (DeliveryNotification record : records.tailMap(afterOffset, false).values()) {
                if (record.offset() != lastExamined + 1) {
                    return Lookup.miss(afterOffset);
                }
                lastExamined = record.offset();
                examined++;
                if (recipientIspb.equals(record.recipientIspb())) {
                    matches.add(record);
                }
                if (matches.size() == notificationLimit || examined == scanLimit) {
                    break;
                }
            }
            return Lookup.hit(matches, lastExamined, lastExamined == tailOffset);
        }
    }
}
