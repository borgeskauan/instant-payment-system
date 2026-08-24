package br.kauan.spi.adapter.output.notification;

import br.kauan.spi.adapter.output.kafka.NotificationPublication;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

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

    private NotificationPublication notification(String id) {
        return NotificationPublication.create("20000001", id.getBytes(StandardCharsets.UTF_8), id);
    }
}
