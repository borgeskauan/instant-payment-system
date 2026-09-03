package br.kauan.notificationgateway.delivery;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecentNotificationWindowTest {

    private final RecentNotificationWindow window = new RecentNotificationWindow(6);

    @Test
    void onePartitionWindowServesMultiplePspsAndAdvancesAcrossUnrelatedRecords() {
        addAll(
                record(0, "20000001"),
                record(1, "20000002"),
                record(2, "20000001"),
                record(3, "20000002")
        );

        RecentNotificationWindow.Lookup first = window.lookup(3, "20000001", -1, 15, 100);
        RecentNotificationWindow.Lookup second = window.lookup(3, "20000002", -1, 15, 100);

        assertThat(first.notifications()).extracting(DeliveryNotification::offset).containsExactly(0L, 2L);
        assertThat(second.notifications()).extracting(DeliveryNotification::offset).containsExactly(1L, 3L);
        assertThat(first.lastExaminedOffset()).isEqualTo(3);
        assertThat(second.lastExaminedOffset()).isEqualTo(3);
        assertThat(first.atTail()).isTrue();
    }

    @Test
    void stopsAtTheNotificationLimitWithoutSkippingTheNextMatchingOffset() {
        List<DeliveryNotification> records = new ArrayList<>();
        for (int offset = 0; offset < 6; offset++) {
            records.add(record(offset, "20000001"));
        }
        records.forEach(window::add);

        RecentNotificationWindow.Lookup page = window.lookup(3, "20000001", -1, 3, 100);

        assertThat(page.notifications()).extracting(DeliveryNotification::offset)
                .containsExactly(0L, 1L, 2L);
        assertThat(page.lastExaminedOffset()).isEqualTo(2);
        assertThat(page.atTail()).isFalse();
    }

    @Test
    void evictionAndDiscontinuousCoverageProduceACacheMiss() {
        for (int offset = 0; offset < 8; offset++) {
            window.add(record(offset, "20000001"));
        }

        assertThat(window.lookup(3, "20000001", -1, 15, 100).state())
                .isEqualTo(RecentNotificationWindow.LookupState.MISS);

        window.add(record(20, "20000001"));

        assertThat(window.lookup(3, "20000001", 7, 15, 100).state())
                .isEqualTo(RecentNotificationWindow.LookupState.MISS);
    }

    @Test
    void cursorAtTheObservedPartitionTailDoesNotHitKafkaAgain() {
        window.add(record(10, "20000001"));

        RecentNotificationWindow.Lookup lookup = window.lookup(3, "20000001", 10, 15, 100);

        assertThat(lookup.state()).isEqualTo(RecentNotificationWindow.LookupState.HIT);
        assertThat(lookup.notifications()).isEmpty();
    }

    private void addAll(DeliveryNotification... records) {
        for (DeliveryNotification record : records) {
            window.add(record);
        }
    }

    private DeliveryNotification record(long offset, String recipient) {
        return new DeliveryNotification(
                3,
                offset,
                recipient,
                recipient + ":" + offset,
                Long.toString(offset).getBytes(StandardCharsets.UTF_8)
        );
    }
}
