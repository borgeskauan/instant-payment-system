package br.kauan.spi.adapter.output.notification;

import br.kauan.spi.application.notification.OutboundNotification;
import br.kauan.spi.application.notification.OutboundNotificationBatchReady;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OutboundNotificationPublisherTest {

    @Test
    void committedBatchIsAdmittedToTheReliablePipeline() {
        NotificationOutboxPipeline pipeline = mock(NotificationOutboxPipeline.class);
        OutboundNotificationPublisher listener = new OutboundNotificationPublisher(pipeline);
        OutboundNotificationBatchReady batch = new OutboundNotificationBatchReady(List.of(notification("one")));

        listener.publishCommitted(batch);

        verify(pipeline).enqueue(batch);
    }

    @Test
    void fastPathFailureDoesNotEscapeAfterTheOutboxCommit() {
        NotificationOutboxPipeline pipeline = mock(NotificationOutboxPipeline.class);
        OutboundNotificationPublisher listener = new OutboundNotificationPublisher(pipeline);
        OutboundNotificationBatchReady batch = new OutboundNotificationBatchReady(List.of(notification("one")));
        doThrow(new IllegalStateException("pipeline unavailable")).when(pipeline).enqueue(batch);

        assertThatCode(() -> listener.publishCommitted(batch)).doesNotThrowAnyException();

        verify(pipeline).enqueue(batch);
    }

    private OutboundNotification notification(String id) {
        return OutboundNotification.create("20000001", id.getBytes(StandardCharsets.UTF_8), id);
    }
}
