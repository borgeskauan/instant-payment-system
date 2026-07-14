package br.kauan.notificationgateway.delivery;

import br.kauan.notificationgateway.grpc.SubscriberRegistry;
import br.kauan.notificationgateway.grpc.proto.Notification;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDeliveryWorkerTest {

    @Test
    void claimsOnlyDeliveriesForLocallyConnectedIspbs() {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        SubscriberRegistry registry = new SubscriberRegistry();
        NotificationDeliveryWorker worker = new NotificationDeliveryWorker(repository, registry);
        CapturingObserver observer = new CapturingObserver();
        registry.register("20000001", observer);
        when(repository.claimForLocalIspbs(Set.of("20000001"), 25, Duration.ofMillis(5_000)))
                .thenReturn(List.of(new NotificationDelivery("v1:abc", "20000001", "payload".getBytes())));
        ReflectionTestUtils.setField(worker, "batchSize", 25);
        ReflectionTestUtils.setField(worker, "leaseDurationMillis", 5_000L);
        ReflectionTestUtils.setField(worker, "retryDelayMillis", 1_000L);

        worker.deliverPendingNotifications();

        verify(repository).claimForLocalIspbs(Set.of("20000001"), 25, Duration.ofMillis(5_000));
        assertThat(observer.notification.getDeliveryId()).isEqualTo("v1:abc");
    }

    @Test
    void doesNotClaimWhenNoLocalIspbIsConnected() {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        NotificationDeliveryWorker worker = new NotificationDeliveryWorker(repository, new SubscriberRegistry());

        worker.deliverPendingNotifications();

        verify(repository, never()).claimForLocalIspbs(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
    }

    private static final class CapturingObserver implements StreamObserver<Notification> {

        private Notification notification;

        @Override
        public void onNext(Notification value) {
            this.notification = value;
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }
    }
}
