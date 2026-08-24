package br.kauan.spi.adapter.output.notification;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class NotificationRuntimeCoordinatorTest {

    @Test
    void startsInboundKafkaOnlyAfterTheCommittedOutboxHasBeenRecovered() {
        NotificationOutboxPipeline pipeline = mock(NotificationOutboxPipeline.class);
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        NotificationRuntimeCoordinator coordinator = new NotificationRuntimeCoordinator(pipeline, registry);

        coordinator.run(new DefaultApplicationArguments());

        var ordered = inOrder(pipeline, registry);
        ordered.verify(pipeline).startAndRecover();
        ordered.verify(registry).start();
    }

    @Test
    void stopsInboundKafkaBeforeStoppingTheOutboxPipeline() {
        NotificationOutboxPipeline pipeline = mock(NotificationOutboxPipeline.class);
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        NotificationRuntimeCoordinator coordinator = new NotificationRuntimeCoordinator(pipeline, registry);

        coordinator.stop();

        var ordered = inOrder(registry, pipeline);
        ordered.verify(registry).stop();
        ordered.verify(pipeline).stop();
    }
}
