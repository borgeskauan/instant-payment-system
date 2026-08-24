package br.kauan.notificationgateway.delivery;

import br.kauan.notificationgateway.kafka.KafkaNotificationRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecentNotificationBufferTest {

    private final RecentNotificationBuffer buffer = new RecentNotificationBuffer(6);

    @Test
    void onePartitionWindowServesMultiplePspsAndAdvancesAcrossUnrelatedRecords() {
        buffer.addAll(List.of(
                record(0, "20000001"),
                record(1, "20000002"),
                record(2, "20000001"),
                record(3, "20000002")
        ));

        RecentNotificationBuffer.Lookup first = buffer.lookup(3, "20000001", -1, 15, 100);
        RecentNotificationBuffer.Lookup second = buffer.lookup(3, "20000002", -1, 15, 100);

        assertThat(first.notifications()).extracting(KafkaNotificationRecord::offset).containsExactly(0L, 2L);
        assertThat(second.notifications()).extracting(KafkaNotificationRecord::offset).containsExactly(1L, 3L);
        assertThat(first.lastExaminedOffset()).isEqualTo(3);
        assertThat(second.lastExaminedOffset()).isEqualTo(3);
        assertThat(first.atTail()).isTrue();
    }

    @Test
    void stopsAtTheNotificationLimitWithoutSkippingTheNextMatchingOffset() {
        List<KafkaNotificationRecord> records = new ArrayList<>();
        for (int offset = 0; offset < 6; offset++) {
            records.add(record(offset, "20000001"));
        }
        buffer.addAll(records);

        RecentNotificationBuffer.Lookup page = buffer.lookup(3, "20000001", -1, 3, 100);

        assertThat(page.notifications()).extracting(KafkaNotificationRecord::offset)
                .containsExactly(0L, 1L, 2L);
        assertThat(page.lastExaminedOffset()).isEqualTo(2);
        assertThat(page.atTail()).isFalse();
    }

    @Test
    void evictionAndDiscontinuousCoverageProduceACacheMiss() {
        for (int offset = 0; offset < 8; offset++) {
            buffer.addAll(List.of(record(offset, "20000001")));
        }

        assertThat(buffer.lookup(3, "20000001", -1, 15, 100).state())
                .isEqualTo(RecentNotificationBuffer.LookupState.MISS);

        buffer.addAll(List.of(record(20, "20000001")));

        assertThat(buffer.lookup(3, "20000001", 7, 15, 100).state())
                .isEqualTo(RecentNotificationBuffer.LookupState.MISS);
    }

    @Test
    void cursorAtTheObservedPartitionTailDoesNotHitKafkaAgain() {
        buffer.addAll(List.of(record(10, "20000001")));

        RecentNotificationBuffer.Lookup lookup = buffer.lookup(3, "20000001", 10, 15, 100);

        assertThat(lookup.state()).isEqualTo(RecentNotificationBuffer.LookupState.KNOWN_TAIL);
        assertThat(lookup.notifications()).isEmpty();
    }

    private KafkaNotificationRecord record(long offset, String recipient) {
        return new KafkaNotificationRecord(
                3,
                offset,
                recipient,
                recipient + ":" + offset,
                Long.toString(offset).getBytes(StandardCharsets.UTF_8)
        );
    }
}
