package br.kauan.spi.adapter.input.kafka.infrastructure.error;

import br.kauan.spi.adapter.output.kafka.RecoverableNotificationPublishException;
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
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    void infrastructureKafkaErrorHandlerUsesDefaultErrorHandlerWithoutRecoveredCommits() {
        KafkaErrorHandlingConfig config = new KafkaErrorHandlingConfig();
        DefaultErrorHandler errorHandler = config.infrastructureKafkaErrorHandler(
                mock(KafkaListenerEndpointRegistry.class),
                config.kafkaPauseTaskScheduler());

        assertThat(errorHandler).isInstanceOf(DefaultErrorHandler.class);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(errorHandler, "isCommitRecovered")).isFalse();
    }

    @Test
    void kafkaErrorHandlerDelegatesInfrastructureUnavailableToPausingRetryHandler() {
        KafkaErrorHandlingConfig config = new KafkaErrorHandlingConfig();
        DefaultErrorHandler dlqErrorHandler = new DefaultErrorHandler();
        DefaultErrorHandler infrastructureErrorHandler = new DefaultErrorHandler();

        CommonErrorHandler errorHandler = config.kafkaErrorHandler(dlqErrorHandler, infrastructureErrorHandler);

        assertThat(errorHandler).isInstanceOf(CommonDelegatingErrorHandler.class);
        @SuppressWarnings("unchecked")
        Map<Class<? extends Throwable>, CommonErrorHandler> delegates =
                (Map<Class<? extends Throwable>, CommonErrorHandler>)
                        ReflectionTestUtils.getField(errorHandler, "delegates");
        assertThat(delegates)
                .containsKey(InfrastructureUnavailableException.class)
                .containsKey(RecoverableNotificationPublishException.class);
        assertThat(delegates.get(InfrastructureUnavailableException.class)).isSameAs(infrastructureErrorHandler);
        assertThat(delegates.get(RecoverableNotificationPublishException.class)).isSameAs(infrastructureErrorHandler);
    }

    @Test
    void kafkaPauseTaskSchedulerUsesDedicatedThreadPool() {
        KafkaErrorHandlingConfig config = new KafkaErrorHandlingConfig();
        TaskScheduler taskScheduler = config.kafkaPauseTaskScheduler();

        assertThat(taskScheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
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
        DefaultErrorHandler infrastructureErrorHandler = errorConfig.infrastructureKafkaErrorHandler(
                mock(KafkaListenerEndpointRegistry.class),
                errorConfig.kafkaPauseTaskScheduler());
        CommonErrorHandler errorHandler = errorConfig.kafkaErrorHandler(dlqErrorHandler, infrastructureErrorHandler);
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
    }

    @Test
    void kafkaErrorHandlerDoesNotRecoverWrappedNotificationPublishFailureToDlq() {
        KafkaErrorHandlingConfig errorConfig = new KafkaErrorHandlingConfig();
        KafkaTemplate<String, byte[]> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        DeadLetterPublishingRecoverer recoverer = new br.kauan.spi.adapter.input.kafka.infrastructure.dlq.KafkaDlqConfig()
                .deadLetterPublishingRecoverer(kafkaTemplate);
        DefaultErrorHandler dlqErrorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 0L));
        DefaultErrorHandler infrastructureErrorHandler = errorConfig.infrastructureKafkaErrorHandler(
                mock(KafkaListenerEndpointRegistry.class),
                errorConfig.kafkaPauseTaskScheduler());
        CommonErrorHandler errorHandler = errorConfig.kafkaErrorHandler(dlqErrorHandler, infrastructureErrorHandler);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        ConsumerRecords<String, byte[]> records = new ConsumerRecords<>(Map.of(
                new TopicPartition("spi-payment-requests", 0),
                List.of(new ConsumerRecord<>(
                        "spi-payment-requests",
                        0,
                        0L,
                        "key",
                        "payload".getBytes(StandardCharsets.UTF_8)))));
        ListenerExecutionFailedException wrappedNotificationFailure =
                new ListenerExecutionFailedException(
                        "listener failed",
                        new RuntimeException(new RecoverableNotificationPublishException(
                                "Failed to publish notification",
                                new IllegalStateException("broker rejected"))));

        try {
            errorHandler.handleBatch(
                    wrappedNotificationFailure,
                    records,
                    mock(Consumer.class),
                    container,
                    () -> {
                        throw wrappedNotificationFailure;
                    });
        } catch (RuntimeException ignored) {
            // The infrastructure handler rethrows while leaving the offset uncommitted for a later retry.
        }

        verify(kafkaTemplate, org.mockito.Mockito.never()).send(any(ProducerRecord.class));
    }
}
