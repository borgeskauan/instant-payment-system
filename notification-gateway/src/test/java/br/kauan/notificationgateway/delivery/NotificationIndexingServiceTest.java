package br.kauan.notificationgateway.delivery;

import br.kauan.notificationgateway.grpc.PullRequestCoordinator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationIndexingServiceTest {

    @Test
    void indexesThenBuffersThenSignalsRecipients() {
        DeliveryIndexRepository repository = mock(DeliveryIndexRepository.class);
        RecentNotificationBuffer buffer = mock(RecentNotificationBuffer.class);
        PullRequestCoordinator coordinator = mock(PullRequestCoordinator.class);
        NotificationIndexingService service = new NotificationIndexingService(repository, buffer, coordinator);
        List<IncomingNotification> incoming = List.of(incoming("v1:first", "20000001"));
        List<NotificationDelivery> indexed = List.of(delivery(1, "v1:first", "20000001"));
        when(repository.indexNew(incoming)).thenReturn(indexed);

        service.ensureIndexed(incoming);

        var order = inOrder(repository, buffer, coordinator);
        order.verify(repository).indexNew(incoming);
        order.verify(buffer).addAll(indexed);
        order.verify(coordinator).signal(Set.of("20000001"));
    }

    @Test
    void replayWithoutANewIndexHasNoMemoryOrWakeupEffects() {
        DeliveryIndexRepository repository = mock(DeliveryIndexRepository.class);
        RecentNotificationBuffer buffer = mock(RecentNotificationBuffer.class);
        PullRequestCoordinator coordinator = mock(PullRequestCoordinator.class);
        NotificationIndexingService service = new NotificationIndexingService(repository, buffer, coordinator);
        List<IncomingNotification> incoming = List.of(incoming("v1:first", "20000001"));
        when(repository.indexNew(incoming)).thenReturn(List.of());

        service.ensureIndexed(incoming);

        verifyNoInteractions(buffer, coordinator);
    }

    private IncomingNotification incoming(String communicationId, String recipientIspb) {
        return new IncomingNotification(
                communicationId,
                recipientIspb,
                "SETTLED_NOTIFICATION",
                "E2E-1",
                "ACSC",
                "v1",
                communicationId.getBytes(StandardCharsets.UTF_8)
        );
    }

    private NotificationDelivery delivery(long position, String communicationId, String recipientIspb) {
        return new NotificationDelivery(
                position,
                communicationId,
                recipientIspb,
                communicationId.getBytes(StandardCharsets.UTF_8)
        );
    }
}
