package br.kauan.spi.adapter.input.kafka.infrastructure.error;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.CommonDelegatingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerContainerPauseService;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

@Configuration
public class KafkaErrorHandlingConfig {

    private static final Duration INFRASTRUCTURE_BACKOFF = Duration.ofSeconds(30);
    private static final long RETRY_BACKOFF_INTERVAL_MS = 1000L;
    private static final long RETRY_BACKOFF_ATTEMPTS = 2L;

    @Bean
    public DefaultErrorHandler dlqKafkaErrorHandler(
            @Qualifier("deadLetterPublishingRecoverer")
            DeadLetterPublishingRecoverer deadLetterPublishingRecoverer
    ) {
        FixedBackOff retryBackOff = new FixedBackOff(RETRY_BACKOFF_INTERVAL_MS, RETRY_BACKOFF_ATTEMPTS);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(deadLetterPublishingRecoverer, retryBackOff);
        errorHandler.setCommitRecovered(true);
        return errorHandler;
    }

    @Bean
    public TaskScheduler kafkaPauseTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("spi-kafka-pause-");
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    public ListenerContainerPauseService listenerContainerPauseService(
            KafkaListenerEndpointRegistry registry,
            @Qualifier("kafkaPauseTaskScheduler") TaskScheduler taskScheduler
    ) {
        return new ListenerContainerPauseService(registry, taskScheduler);
    }

    @Bean
    public CommonErrorHandler kafkaErrorHandler(
            @Qualifier("dlqKafkaErrorHandler") DefaultErrorHandler dlqKafkaErrorHandler,
            ListenerContainerPauseService pauseService
    ) {
        CommonErrorHandler infrastructureErrorHandler =
                new InfrastructurePausingErrorHandler(pauseService, INFRASTRUCTURE_BACKOFF);
        CommonDelegatingErrorHandler errorHandler = new CommonDelegatingErrorHandler(dlqKafkaErrorHandler);
        errorHandler.setCauseChainTraversing(true);
        errorHandler.addDelegate(InfrastructureUnavailableException.class, infrastructureErrorHandler);
        return errorHandler;
    }
}
