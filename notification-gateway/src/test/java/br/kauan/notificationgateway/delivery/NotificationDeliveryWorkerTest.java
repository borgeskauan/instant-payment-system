package br.kauan.notificationgateway.delivery;

import br.kauan.notificationgateway.grpc.SubscriberRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationDeliveryWorkerTest {

    @Test
    void claimsRecoveryWorkOnlyForLocalRecipientsAndUsesSharedDispatcher() {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        SubscriberRegistry registry = mock(SubscriberRegistry.class);
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        NotificationDeliveryWorker worker = new NotificationDeliveryWorker(
                repository,
                registry,
                dispatcher
        );
        List<NotificationDelivery> claimed = List.of(
                new NotificationDelivery("v1:recovery", "20000001", "payload".getBytes())
        );
        when(registry.connectedIspbs()).thenReturn(Set.of("20000001"));
        when(repository.claimForLocalIspbs(Set.of("20000001"), 25, Duration.ofMillis(5_000)))
                .thenReturn(claimed);
        ReflectionTestUtils.setField(worker, "batchSize", 25);
        ReflectionTestUtils.setField(worker, "leaseDurationMillis", 5_000L);

        worker.deliverPendingNotifications();

        verify(repository).claimForLocalIspbs(Set.of("20000001"), 25, Duration.ofMillis(5_000));
        verify(dispatcher).dispatch(claimed);
    }

    @Test
    void doesNotClaimWhenNoLocalRecipientIsConnected() {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        SubscriberRegistry registry = mock(SubscriberRegistry.class);
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        NotificationDeliveryWorker worker = new NotificationDeliveryWorker(
                repository,
                registry,
                dispatcher
        );
        when(registry.connectedIspbs()).thenReturn(Set.of());

        worker.deliverPendingNotifications();

        verify(repository, never()).claimForLocalIspbs(any(), anyInt(), any());
        verifyNoInteractions(dispatcher);
    }
}
