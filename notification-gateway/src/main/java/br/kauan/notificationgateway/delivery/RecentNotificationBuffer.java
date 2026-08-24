package br.kauan.notificationgateway.delivery;

import br.kauan.notificationgateway.kafka.KafkaNotificationRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A bounded acceleration window over each Kafka partition. The window keeps
 * every record, including records addressed to other PSPs, so a cursor can
 * advance over the exact offsets that were examined.
 */
@Component
public final class RecentNotificationBuffer {

    private final int capacityPerPartition;
    private final ConcurrentHashMap<Integer, PartitionWindow> windows = new ConcurrentHashMap<>();

    public RecentNotificationBuffer(
            @Value("${notification-gateway.kafka.ring-capacity-per-partition:4096}")
            int capacityPerPartition
    ) {
        if (capacityPerPartition < 1) {
            throw new IllegalArgumentException("partition buffer capacity must be positive");
        }
        this.capacityPerPartition = capacityPerPartition;
    }

    public enum LookupState {
        DATA,
        KNOWN_TAIL,
        MISS
    }

    public record Lookup(
            LookupState state,
            List<KafkaNotificationRecord> notifications,
            long lastExaminedOffset,
            boolean atTail
    ) {
        public Lookup {
            notifications = List.copyOf(notifications);
        }

        private static Lookup data(
                List<KafkaNotificationRecord> notifications,
                long lastExaminedOffset,
                boolean atTail
        ) {
            return new Lookup(LookupState.DATA, notifications, lastExaminedOffset, atTail);
        }

        private static Lookup knownTail(long offset) {
            return new Lookup(LookupState.KNOWN_TAIL, List.of(), offset, true);
        }

        private static Lookup miss(long offset) {
            return new Lookup(LookupState.MISS, List.of(), offset, false);
        }
    }

    public void addAll(List<KafkaNotificationRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        for (KafkaNotificationRecord record : records) {
            if (record.partition() < 0 || record.offset() < 0) {
                throw new IllegalArgumentException("Kafka partition and offset must not be negative");
            }
            if (record.recipientIspb() == null || record.recipientIspb().isBlank()) {
                throw new IllegalArgumentException("notification recipient must not be blank");
            }
            if (record.communicationId() == null || record.communicationId().isBlank()) {
                throw new IllegalArgumentException("notification communication id must not be blank");
            }
            if (record.payload() == null) {
                throw new IllegalArgumentException("notification payload must not be null");
            }
        }

        Map<Integer, List<KafkaNotificationRecord>> byPartition = new HashMap<>();
        for (KafkaNotificationRecord record : records) {
            byPartition.computeIfAbsent(record.partition(), ignored -> new ArrayList<>()).add(record);
        }
        byPartition.forEach((partition, partitionRecords) -> {
            partitionRecords.sort(Comparator.comparingLong(KafkaNotificationRecord::offset));
            windows.computeIfAbsent(partition, ignored -> new PartitionWindow(capacityPerPartition))
                    .addAll(partitionRecords);
        });
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
        private final NavigableMap<Long, KafkaNotificationRecord> records = new TreeMap<>();

        private PartitionWindow(int capacity) {
            this.capacity = capacity;
        }

        synchronized void addAll(List<KafkaNotificationRecord> incoming) {
            for (KafkaNotificationRecord record : incoming) {
                add(record);
            }
        }

        private void add(KafkaNotificationRecord record) {
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
                return Lookup.knownTail(afterOffset);
            }

            List<KafkaNotificationRecord> matches = new ArrayList<>(notificationLimit);
            long lastExamined = afterOffset;
            int examined = 0;
            for (KafkaNotificationRecord record : records.tailMap(afterOffset, false).values()) {
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
            return Lookup.data(matches, lastExamined, lastExamined == tailOffset);
        }
    }
}
