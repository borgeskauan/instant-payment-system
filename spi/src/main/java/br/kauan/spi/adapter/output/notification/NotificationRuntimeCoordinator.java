package br.kauan.spi.adapter.output.notification;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "spi.notification-runtime",
        name = "enabled",
        havingValue = "true"
)
public class NotificationRuntimeCoordinator implements ApplicationRunner {

    private final NotificationOutboxPipeline pipeline;
    private final KafkaListenerEndpointRegistry listenerRegistry;

    public NotificationRuntimeCoordinator(
            NotificationOutboxPipeline pipeline,
            KafkaListenerEndpointRegistry listenerRegistry
    ) {
        this.pipeline = pipeline;
        this.listenerRegistry = listenerRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        pipeline.startAndRecover();
        listenerRegistry.start();
    }

    @PreDestroy
    public void stop() {
        listenerRegistry.stop();
        pipeline.stop();
    }
}
