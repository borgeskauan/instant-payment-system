package br.kauan.notificationgateway.delivery;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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

    @Test
    void confirmsAnEmptyDatabaseTailAndSkipsRepeatedFallbacks() {
        RecentNotificationBuffer buffer = new RecentNotificationBuffer();
        DeliveryIndexRepository repository = mock(DeliveryIndexRepository.class);
        NotificationDeliveryReader reader = new NotificationDeliveryReader(buffer, repository);
        when(repository.findAfter("20000001", 7, 15)).thenReturn(List.of());

        assertThat(reader.findAfter("20000001", 7, 15)).isEmpty();
        assertThat(reader.findAfter("20000001", 7, 15)).isEmpty();

        verify(repository, times(1)).findAfter("20000001", 7, 15);
    }

    @Test
    void confirmsTheTailReturnedByAPartialDatabasePage() {
        RecentNotificationBuffer buffer = new RecentNotificationBuffer();
        DeliveryIndexRepository repository = mock(DeliveryIndexRepository.class);
        NotificationDeliveryReader reader = new NotificationDeliveryReader(buffer, repository);
        when(repository.findAfter("20000001", 0, 15))
                .thenReturn(List.of(delivery(1, "database")));

        assertThat(reader.findAfter("20000001", 0, 15))
                .extracting(NotificationDelivery::deliveryPosition)
                .containsExactly(1L);
        assertThat(reader.findAfter("20000001", 1, 15)).isEmpty();

        verify(repository).findAfter("20000001", 0, 15);
        verify(repository, times(0)).findAfter("20000001", 1, 15);
    }

    @Test
    void keepsUsingDatabasePagesUntilAResultProvesTheTail() {
        RecentNotificationBuffer buffer = new RecentNotificationBuffer();
        DeliveryIndexRepository repository = mock(DeliveryIndexRepository.class);
        NotificationDeliveryReader reader = new NotificationDeliveryReader(buffer, repository);
        when(repository.findAfter("20000001", 0, 2))
                .thenReturn(List.of(delivery(1, "first"), delivery(2, "second")));
        when(repository.findAfter("20000001", 2, 2)).thenReturn(List.of());

        assertThat(reader.findAfter("20000001", 0, 2)).hasSize(2);
        assertThat(reader.findAfter("20000001", 2, 2)).isEmpty();
        assertThat(reader.findAfter("20000001", 2, 2)).isEmpty();

        verify(repository).findAfter("20000001", 0, 2);
        verify(repository, times(1)).findAfter("20000001", 2, 2);
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
