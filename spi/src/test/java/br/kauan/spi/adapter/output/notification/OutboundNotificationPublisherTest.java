package br.kauan.spi.adapter.output.notification;

import br.kauan.spi.adapter.output.kafka.NotificationPublication;
import br.kauan.spi.adapter.output.kafka.NotificationPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboundNotificationPublisherTest {

    @Test
    void startsEveryKafkaSendWithoutWaitingForBrokerAcknowledgement() {
        NotificationPublisher kafkaPublisher = mock(NotificationPublisher.class);
        NotificationPublication first = notification("E2E-FIRST");
        NotificationPublication second = notification("E2E-SECOND");
        when(kafkaPublisher.publish(first)).thenReturn(new CompletableFuture<>());
        when(kafkaPublisher.publish(second)).thenReturn(new CompletableFuture<>());
        OutboundNotificationPublisher publisher = new OutboundNotificationPublisher(kafkaPublisher);

        assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> publisher.publishCommitted(new OutboundNotificationBatchReady(List.of(first, second)))
        );

        verify(kafkaPublisher).publish(first);
        verify(kafkaPublisher).publish(second);
    }

    @Test
    void synchronousFailureDoesNotPreventTheRemainingKafkaSends() {
        NotificationPublisher kafkaPublisher = mock(NotificationPublisher.class);
        NotificationPublication failed = notification("E2E-SYNC-FAILURE");
        NotificationPublication succeeded = notification("E2E-SUCCESS");
        when(kafkaPublisher.publish(failed)).thenThrow(new IllegalStateException("producer closed"));
        when(kafkaPublisher.publish(succeeded)).thenReturn(new CompletableFuture<>());

        assertDoesNotThrow(() -> new OutboundNotificationPublisher(kafkaPublisher)
                .publishCommitted(new OutboundNotificationBatchReady(List.of(failed, succeeded))));

        verify(kafkaPublisher).publish(succeeded);
    }

    @Test
    void asynchronousFailureDoesNotEscapeTheBestEffortFastPath() {
        NotificationPublisher kafkaPublisher = mock(NotificationPublisher.class);
        NotificationPublication notification = notification("E2E-ASYNC-FAILURE");
        CompletableFuture<SendResult<String, byte[]>> failed = new CompletableFuture<>();
        when(kafkaPublisher.publish(notification)).thenReturn(failed);
        OutboundNotificationPublisher publisher = new OutboundNotificationPublisher(kafkaPublisher);

        assertDoesNotThrow(() -> publisher.publishCommitted(
                new OutboundNotificationBatchReady(List.of(notification))
        ));
        assertDoesNotThrow(() -> failed.completeExceptionally(new IllegalStateException("broker unavailable")));
    }

    private NotificationPublication notification(String paymentId) {
        return NotificationPublication.create(
                "20000001",
                paymentId.getBytes(StandardCharsets.UTF_8),
                "ACCEPTANCE_REQUEST",
                paymentId,
                null
        );
    }
}
