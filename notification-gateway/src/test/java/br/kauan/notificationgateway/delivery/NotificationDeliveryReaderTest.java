package br.kauan.notificationgateway.delivery;

import br.kauan.notificationgateway.kafka.HistoricalKafkaReader;
import br.kauan.notificationgateway.kafka.KafkaNotificationPage;
import br.kauan.notificationgateway.kafka.KafkaNotificationRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationDeliveryReaderTest {

    @Test
    void servesContiguousRecentOffsetsFromMemoryWithoutKafkaSeek() {
        RecentNotificationBuffer buffer = new RecentNotificationBuffer(10);
        HistoricalKafkaReader history = mock(HistoricalKafkaReader.class);
        NotificationDeliveryReader reader = new NotificationDeliveryReader(buffer, history, 100);
        buffer.addAll(List.of(record(0, "memory")));

        KafkaNotificationPage page = reader.read("20000001", 3, -1, 15);

        assertThat(page.notifications()).extracting(KafkaNotificationRecord::communicationId)
                .containsExactly("memory");
        verifyNoInteractions(history);
    }

    @Test
    void readsKafkaDirectlyWhenTheCursorFallsOutsideMemoryCoverage() {
        RecentNotificationBuffer buffer = new RecentNotificationBuffer(10);
        HistoricalKafkaReader history = mock(HistoricalKafkaReader.class);
        NotificationDeliveryReader reader = new NotificationDeliveryReader(buffer, history, 100);
        KafkaNotificationPage historical = new KafkaNotificationPage(List.of(record(4, "historical")), 4, true);
        when(history.read("20000001", 3, 3, 15, 100)).thenReturn(historical);

        assertThat(reader.read("20000001", 3, 3, 15)).isEqualTo(historical);
        verify(history).read("20000001", 3, 3, 15, 100);
    }

    private KafkaNotificationRecord record(long offset, String id) {
        return new KafkaNotificationRecord(
                3,
                offset,
                "20000001",
                id,
                id.getBytes(StandardCharsets.UTF_8)
        );
    }
}
