package br.kauan.notificationgateway.delivery;

import br.kauan.notificationgateway.grpc.SubscriberRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDispatcherTest {

    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    @Test
    void dispatchesEachRecipientSequentiallyWhileDifferentRecipientsRunInParallel() throws Exception {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        SubscriberRegistry registry = mock(SubscriberRegistry.class);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        NotificationDispatcher dispatcher = new NotificationDispatcher(
                repository,
                registry,
                executor,
                RETRY_DELAY
        );
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        when(registry.dispatch(any(NotificationDelivery.class))).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            Thread.sleep(50);
            active.decrementAndGet();
            return true;
        });

        try {
            dispatcher.dispatch(List.of(
                    delivery("v1:first-a", "20000001"),
                    delivery("v1:second-a", "20000001"),
                    delivery("v1:first-b", "20000002")
            ));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }

        assertThat(maxActive.get()).isEqualTo(2);
    }

    @Test
    void neverDispatchesTwoNotificationsForTheSameRecipientConcurrently() throws Exception {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        SubscriberRegistry registry = mock(SubscriberRegistry.class);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        NotificationDispatcher dispatcher = new NotificationDispatcher(
                repository,
                registry,
                executor,
                RETRY_DELAY
        );
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        when(registry.dispatch(any(NotificationDelivery.class))).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            Thread.sleep(30);
            active.decrementAndGet();
            return true;
        });

        try {
            dispatcher.dispatch(List.of(
                    delivery("v1:first", "20000001"),
                    delivery("v1:second", "20000001")
            ));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }

        assertThat(maxActive.get()).isOne();
    }

    @Test
    void failedSendBecomesRetryable() {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        SubscriberRegistry registry = mock(SubscriberRegistry.class);
        NotificationDispatcher dispatcher = new NotificationDispatcher(
                repository,
                registry,
                Runnable::run,
                RETRY_DELAY
        );
        NotificationDelivery delivery = delivery("v1:failed", "20000001");
        when(registry.dispatch(delivery)).thenReturn(false);

        dispatcher.dispatch(List.of(delivery));

        verify(repository).markRetryableFailed(
                "v1:failed",
                "No local subscriber available for ISPB 20000001",
                RETRY_DELAY
        );
    }

    @Test
    void rejectedSubmissionMakesEveryDeliveryInTheGroupRetryable() {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        SubscriberRegistry registry = mock(SubscriberRegistry.class);
        Executor rejectingExecutor = ignored -> {
            throw new RejectedExecutionException("queue full");
        };
        NotificationDispatcher dispatcher = new NotificationDispatcher(
                repository,
                registry,
                rejectingExecutor,
                RETRY_DELAY
        );

        dispatcher.dispatch(List.of(
                delivery("v1:first", "20000001"),
                delivery("v1:second", "20000001")
        ));

        verify(repository).markRetryableFailed(
                "v1:first",
                "Notification dispatch executor rejected the delivery group",
                RETRY_DELAY
        );
        verify(repository).markRetryableFailed(
                "v1:second",
                "Notification dispatch executor rejected the delivery group",
                RETRY_DELAY
        );
    }

    private NotificationDelivery delivery(String communicationId, String recipientIspb) {
        return new NotificationDelivery(communicationId, recipientIspb, communicationId.getBytes());
    }
}
