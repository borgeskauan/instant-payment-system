package br.kauan.spi.adapter.input.kafka.infrastructure.error;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonDelegatingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.ListenerContainerPauseService;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaErrorHandlingConfigTest {

    @Test
    void dlqKafkaErrorHandlerUsesDefaultErrorHandlerAndCommitsRecoveredOffset() {
        KafkaErrorHandlingConfig config = new KafkaErrorHandlingConfig();
        DeadLetterPublishingRecoverer recoverer = mock(DeadLetterPublishingRecoverer.class);

        DefaultErrorHandler errorHandler = config.dlqKafkaErrorHandler(recoverer);

        assertThat(errorHandler).isInstanceOf(DefaultErrorHandler.class);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(errorHandler, "isCommitRecovered")).isTrue();
    }

    @Test
    void infrastructurePausingErrorHandlerPausesContainerAndRethrowsWithoutDlqRecovery() {
        ListenerContainerPauseService pauseService = mock(ListenerContainerPauseService.class);
        CommonErrorHandler errorHandler =
                new InfrastructurePausingErrorHandler(pauseService, Duration.ofSeconds(30));
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        RuntimeException infrastructureFailure = new InfrastructureUnavailableException(
                "Database unavailable while processing SPI batch",
                new DataAccessResourceFailureException("db down"));

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> errorHandler.handleBatch(
                        infrastructureFailure,
                        ConsumerRecords.empty(),
                        mock(Consumer.class),
                        container,
                        () -> {
                        }));

        assertThat(exception).isSameAs(infrastructureFailure);
        verify(pauseService).pause(eq(container), eq(Duration.ofSeconds(30)));
    }

    @Test
    void kafkaErrorHandlerDelegatesInfrastructureUnavailableToPausingRetryHandler() {
        KafkaErrorHandlingConfig config = new KafkaErrorHandlingConfig();
        DefaultErrorHandler dlqErrorHandler = new DefaultErrorHandler();
        ListenerContainerPauseService pauseService = mock(ListenerContainerPauseService.class);

        CommonErrorHandler errorHandler = config.kafkaErrorHandler(dlqErrorHandler, pauseService);

        assertThat(errorHandler).isInstanceOf(CommonDelegatingErrorHandler.class);
        @SuppressWarnings("unchecked")
        Map<Class<? extends Throwable>, CommonErrorHandler> delegates =
                (Map<Class<? extends Throwable>, CommonErrorHandler>)
                        ReflectionTestUtils.getField(errorHandler, "delegates");
        assertThat(delegates)
                .containsKey(InfrastructureUnavailableException.class);
        assertThat(delegates.get(InfrastructureUnavailableException.class))
                .isInstanceOf(InfrastructurePausingErrorHandler.class);
    }

    @Test
    void kafkaPauseSupportBeansWirePauseService() {
        KafkaErrorHandlingConfig config = new KafkaErrorHandlingConfig();
        TaskScheduler taskScheduler = config.kafkaPauseTaskScheduler();
        ListenerContainerPauseService pauseService = config.listenerContainerPauseService(
                mock(KafkaListenerEndpointRegistry.class),
                taskScheduler);

        assertThat(taskScheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
        assertThat(pauseService).isInstanceOf(ListenerContainerPauseService.class);
    }

    @Test
    void kafkaErrorHandlerDoesNotRecoverWrappedInfrastructureUnavailableExceptionToDlq() {
        KafkaErrorHandlingConfig errorConfig = new KafkaErrorHandlingConfig();
        KafkaTemplate<String, byte[]> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        DeadLetterPublishingRecoverer recoverer = new br.kauan.spi.adapter.input.kafka.infrastructure.dlq.KafkaDlqConfig()
                .deadLetterPublishingRecoverer(kafkaTemplate);
        DefaultErrorHandler dlqErrorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 0L));
        ListenerContainerPauseService pauseService = mock(ListenerContainerPauseService.class);
        CommonErrorHandler errorHandler = errorConfig.kafkaErrorHandler(dlqErrorHandler, pauseService);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        ConsumerRecords<String, byte[]> records = new ConsumerRecords<>(Map.of(
                new TopicPartition("spi-payment-requests", 0),
                List.of(new ConsumerRecord<>(
                        "spi-payment-requests",
                        0,
                        0L,
                        "key",
                        "payload".getBytes(StandardCharsets.UTF_8)))));
        ListenerExecutionFailedException wrappedInfrastructureFailure =
                new ListenerExecutionFailedException(
                        "listener failed",
                        new InfrastructureUnavailableException("Database unavailable while processing SPI batch",
                                new DataAccessResourceFailureException("db down")));

        try {
            errorHandler.handleBatch(
                    wrappedInfrastructureFailure,
                    records,
                    mock(Consumer.class),
                    container,
                    () -> {
                        throw wrappedInfrastructureFailure;
                    });
        } catch (RuntimeException ignored) {
            // The infrastructure handler rethrows while leaving the offset uncommitted for a later retry.
        }

        verify(kafkaTemplate, org.mockito.Mockito.never()).send(any(ProducerRecord.class));
        verify(pauseService).pause(eq(container), eq(Duration.ofSeconds(30)));
    }
}
