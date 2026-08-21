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
    void returnsTheMemoryPrefixWithoutConsultingPostgres() {
        RecentNotificationBuffer buffer = new RecentNotificationBuffer();
        DeliveryIndexRepository repository = mock(DeliveryIndexRepository.class);
        NotificationDeliveryReader reader = new NotificationDeliveryReader(buffer, repository);
        buffer.addAll(List.of(delivery(1, "memory")));

        assertThat(reader.findAfter("20000001", 0, 15))
                .extracting(NotificationDelivery::communicationId)
                .containsExactly("memory");
        verifyNoInteractions(repository);
    }

    @Test
    void usesTheCanonicalDatabaseFallbackWhenMemoryCannotStartAtTheCursor() {
        RecentNotificationBuffer buffer = new RecentNotificationBuffer();
        DeliveryIndexRepository repository = mock(DeliveryIndexRepository.class);
        NotificationDeliveryReader reader = new NotificationDeliveryReader(buffer, repository);
        buffer.addAll(List.of(delivery(2, "later")));
        when(repository.findAfter("20000001", 0, 15))
                .thenReturn(List.of(delivery(1, "database")));

        assertThat(reader.findAfter("20000001", 0, 15))
                .extracting(NotificationDelivery::communicationId)
                .containsExactly("database");
        verify(repository).findAfter("20000001", 0, 15);
    }

    private NotificationDelivery delivery(long position, String communicationId) {
        return new NotificationDelivery(
                position,
                communicationId,
                "20000001",
                communicationId.getBytes(StandardCharsets.UTF_8)
        );
    }
}
