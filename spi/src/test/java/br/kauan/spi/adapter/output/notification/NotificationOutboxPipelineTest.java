package br.kauan.spi.adapter.output.notification;

import br.kauan.spi.application.notification.OutboundNotification;
import br.kauan.spi.application.notification.OutboundNotificationBatchReady;
import br.kauan.spi.adapter.output.kafka.NotificationPublisher;
import br.kauan.spi.config.NotificationOutboxProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationOutboxPipelineTest {

    private NotificationOutboxPipeline pipeline;

    @AfterEach
    void stopPipeline() {
        if (pipeline != null) {
            pipeline.stop();
        }
    }

    @Test
    void drainsCommittedRowsBeforeStartupCompletes() {
        OutboundNotificationRepository repository = mock(OutboundNotificationRepository.class);
        NotificationPublisher publisher = mock(NotificationPublisher.class);
        List<OutboundNotification> recovered = List.of(notification("recovered"));
        when(repository.findOldest(20)).thenReturn(recovered, List.of());
        when(publisher.publishAll(recovered)).thenReturn(CompletableFuture.completedFuture(null));
        pipeline = pipeline(repository, publisher);

        pipeline.startAndRecover();

        var ordered = inOrder(repository, publisher);
        ordered.verify(repository).findOldest(20);
        ordered.verify(publisher).publishAll(recovered);
        ordered.verify(repository).deleteAll(List.of("recovered"));
        ordered.verify(repository).findOldest(20);
        assertThat(pipeline.isHealthy()).isTrue();
    }

    @Test
    void failedBatchIsRetriedIdenticallyAndDeletedOnlyAfterEveryAcknowledgement() {
        OutboundNotificationRepository repository = mock(OutboundNotificationRepository.class);
        NotificationPublisher publisher = mock(NotificationPublisher.class);
        when(repository.findOldest(20)).thenReturn(List.of());
        List<OutboundNotification> notifications = List.of(notification("one"), notification("two"));
        CompletableFuture<Void> firstAttempt = new CompletableFuture<>();
        CompletableFuture<Void> secondAttempt = new CompletableFuture<>();
        when(publisher.publishAll(notifications)).thenReturn(firstAttempt, secondAttempt);
        pipeline = pipeline(repository, publisher);
        pipeline.startAndRecover();

        pipeline.enqueue(new OutboundNotificationBatchReady(notifications));
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> verify(publisher).publishAll(notifications));
        verify(repository, never()).deleteAll(List.of("one", "two"));

        firstAttempt.completeExceptionally(new IllegalStateException("broker unavailable"));
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                verify(publisher, org.mockito.Mockito.times(2)).publishAll(notifications));
        verify(repository, never()).deleteAll(List.of("one", "two"));

        secondAttempt.complete(null);
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                verify(repository).deleteAll(List.of("one", "two")));
    }

    @Test
    void deleteFailureCausesTheWholeBatchToBeRepublished() {
        OutboundNotificationRepository repository = mock(OutboundNotificationRepository.class);
        NotificationPublisher publisher = mock(NotificationPublisher.class);
        when(repository.findOldest(20)).thenReturn(List.of());
        List<OutboundNotification> notifications = List.of(notification("one"), notification("two"));
        when(publisher.publishAll(notifications)).thenReturn(CompletableFuture.completedFuture(null));
        org.mockito.Mockito.doThrow(new IllegalStateException("delete failed"))
                .doNothing()
                .when(repository).deleteAll(List.of("one", "two"));
        pipeline = pipeline(repository, publisher);
        pipeline.startAndRecover();

        pipeline.enqueue(new OutboundNotificationBatchReady(notifications));

        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
            verify(publisher, org.mockito.Mockito.times(2)).publishAll(notifications);
            verify(repository, org.mockito.Mockito.times(2)).deleteAll(List.of("one", "two"));
        });
    }

    private NotificationOutboxPipeline pipeline(
            OutboundNotificationRepository repository,
            NotificationPublisher publisher
    ) {
        return new NotificationOutboxPipeline(
                repository,
                publisher,
                new NotificationOutboxProperties(2, 20, Duration.ofMillis(1))
        );
    }

    private OutboundNotification notification(String id) {
        return OutboundNotification.create("20000001", id.getBytes(StandardCharsets.UTF_8), id);
    }
}
