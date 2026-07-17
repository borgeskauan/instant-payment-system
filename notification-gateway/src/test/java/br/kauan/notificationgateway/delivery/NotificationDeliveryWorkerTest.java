package br.kauan.notificationgateway.delivery;

import br.kauan.notificationgateway.grpc.SubscriberRegistry;
import br.kauan.notificationgateway.grpc.proto.Notification;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDeliveryWorkerTest {

    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    @Test
    void claimsOnlyDeliveriesForLocallyConnectedIspbs() {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        SubscriberRegistry registry = new SubscriberRegistry();
        NotificationDeliveryWorker worker = new NotificationDeliveryWorker(repository, registry, DIRECT_EXECUTOR);
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
        NotificationDeliveryWorker worker = new NotificationDeliveryWorker(repository, new SubscriberRegistry(), DIRECT_EXECUTOR);

        worker.deliverPendingNotifications();

        verify(repository, never()).claimForLocalIspbs(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dispatchesDifferentIspbsInParallel() throws Exception {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        SubscriberRegistry registry = mock(SubscriberRegistry.class);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        NotificationDeliveryWorker worker = new NotificationDeliveryWorker(repository, registry, executor);
        ReflectionTestUtils.setField(worker, "batchSize", 25);
        ReflectionTestUtils.setField(worker, "leaseDurationMillis", 5_000L);
        ReflectionTestUtils.setField(worker, "retryDelayMillis", 1_000L);
        when(registry.connectedIspbs()).thenReturn(Set.of("20000001", "20000002"));
        when(repository.claimForLocalIspbs(Set.of("20000001", "20000002"), 25, Duration.ofMillis(5_000)))
                .thenReturn(List.of(
                        new NotificationDelivery("v1:one", "20000001", "one".getBytes()),
                        new NotificationDelivery("v1:two", "20000002", "two".getBytes())
                ));

        AtomicInteger activeDispatches = new AtomicInteger();
        AtomicInteger maxActiveDispatches = new AtomicInteger();
        when(registry.dispatch(any(NotificationDelivery.class))).thenAnswer(invocation -> {
            int active = activeDispatches.incrementAndGet();
            maxActiveDispatches.accumulateAndGet(active, Math::max);
            Thread.sleep(50);
            activeDispatches.decrementAndGet();
            return true;
        });

        try {
            worker.deliverPendingNotifications();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }

        assertThat(maxActiveDispatches.get()).isGreaterThan(1);
    }

    @Test
    void dispatchesSameIspbSequentially() throws Exception {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        SubscriberRegistry registry = mock(SubscriberRegistry.class);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        NotificationDeliveryWorker worker = new NotificationDeliveryWorker(repository, registry, executor);
        ReflectionTestUtils.setField(worker, "batchSize", 25);
        ReflectionTestUtils.setField(worker, "leaseDurationMillis", 5_000L);
        ReflectionTestUtils.setField(worker, "retryDelayMillis", 1_000L);
        when(registry.connectedIspbs()).thenReturn(Set.of("20000001"));
        when(repository.claimForLocalIspbs(Set.of("20000001"), 25, Duration.ofMillis(5_000)))
                .thenReturn(List.of(
                        new NotificationDelivery("v1:one", "20000001", "one".getBytes()),
                        new NotificationDelivery("v1:two", "20000001", "two".getBytes())
                ));

        AtomicInteger activeDispatches = new AtomicInteger();
        AtomicInteger maxActiveDispatches = new AtomicInteger();
        when(registry.dispatch(any(NotificationDelivery.class))).thenAnswer(invocation -> {
            int active = activeDispatches.incrementAndGet();
            maxActiveDispatches.accumulateAndGet(active, Math::max);
            Thread.sleep(50);
            activeDispatches.decrementAndGet();
            return true;
        });

        try {
            worker.deliverPendingNotifications();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }

        assertThat(maxActiveDispatches.get()).isEqualTo(1);
    }

    @Test
    void retryableFailureIsRecordedWhenDispatchReturnsFalse() {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        SubscriberRegistry registry = mock(SubscriberRegistry.class);
        NotificationDeliveryWorker worker = new NotificationDeliveryWorker(repository, registry, DIRECT_EXECUTOR);
        ReflectionTestUtils.setField(worker, "batchSize", 25);
        ReflectionTestUtils.setField(worker, "leaseDurationMillis", 5_000L);
        ReflectionTestUtils.setField(worker, "retryDelayMillis", 1_000L);
        NotificationDelivery delivery = new NotificationDelivery("v1:abc", "20000001", "payload".getBytes());
        when(registry.connectedIspbs()).thenReturn(Set.of("20000001"));
        when(repository.claimForLocalIspbs(Set.of("20000001"), 25, Duration.ofMillis(5_000)))
                .thenReturn(List.of(delivery));
        when(registry.dispatch(delivery)).thenReturn(false);

        worker.deliverPendingNotifications();

        verify(repository).markRetryableFailed(
                "v1:abc",
                "No local subscriber available for ISPB 20000001",
                Duration.ofMillis(1_000)
        );
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
