package br.kauan.notificationgateway.delivery;

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
        RecentNotificationWindow window = new RecentNotificationWindow(10);
        NotificationHistory history = mock(NotificationHistory.class);
        NotificationDeliveryReader reader = new NotificationDeliveryReader(window, history, 100);
        window.add(record(0, "memory"));

        DeliveryPage page = reader.read("20000001", 3, -1, 15);

        assertThat(page.notifications()).extracting(DeliveryNotification::communicationId)
                .containsExactly("memory");
        verifyNoInteractions(history);
    }

    @Test
    void readsKafkaDirectlyWhenTheCursorFallsOutsideMemoryCoverage() {
        RecentNotificationWindow window = new RecentNotificationWindow(10);
        NotificationHistory history = mock(NotificationHistory.class);
        NotificationDeliveryReader reader = new NotificationDeliveryReader(window, history, 100);
        DeliveryPage historical = new DeliveryPage(List.of(record(4, "historical")), 4, true);
        when(history.read("20000001", 3, 3, 15, 100)).thenReturn(historical);

        assertThat(reader.read("20000001", 3, 3, 15)).isEqualTo(historical);
        verify(history).read("20000001", 3, 3, 15, 100);
    }

    private DeliveryNotification record(long offset, String id) {
        return new DeliveryNotification(
                3,
                offset,
                "20000001",
                id,
                id.getBytes(StandardCharsets.UTF_8)
        );
    }
}
