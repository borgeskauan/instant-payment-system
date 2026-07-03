package br.kauan.spi.adapter.input.kafka.infrastructure.error;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ListenerContainerPauseService;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public class InfrastructurePausingErrorHandler implements CommonErrorHandler {

    private final ListenerContainerPauseService pauseService;
    private final Duration backOff;

    public InfrastructurePausingErrorHandler(ListenerContainerPauseService pauseService, Duration backOff) {
        this.pauseService = Objects.requireNonNull(pauseService, "pauseService must not be null");
        this.backOff = Objects.requireNonNull(backOff, "backOff must not be null");
    }

    @Override
    public void handleBatch(
            Exception thrownException,
            ConsumerRecords<?, ?> data,
            Consumer<?, ?> consumer,
            MessageListenerContainer container,
            Runnable invokeListener
    ) {
        pauseAndRethrow(thrownException, container);
    }

    @Override
    public void handleRemaining(
            Exception thrownException,
            List<ConsumerRecord<?, ?>> records,
            Consumer<?, ?> consumer,
            MessageListenerContainer container
    ) {
        pauseAndRethrow(thrownException, container);
    }

    @Override
    public boolean handleOne(
            Exception thrownException,
            ConsumerRecord<?, ?> record,
            Consumer<?, ?> consumer,
            MessageListenerContainer container
    ) {
        pauseAndRethrow(thrownException, container);
        return false;
    }

    @Override
    public void handleOtherException(
            Exception thrownException,
            Consumer<?, ?> consumer,
            MessageListenerContainer container,
            boolean batchListener
    ) {
        pauseAndRethrow(thrownException, container);
    }

    @Override
    public boolean isAckAfterHandle() {
        return true;
    }

    @Override
    public boolean seeksAfterHandling() {
        return true;
    }

    private void pauseAndRethrow(Exception thrownException, MessageListenerContainer container) {
        pauseService.pause(container, backOff);
        if (thrownException instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new KafkaException("Infrastructure unavailable while processing Kafka records", thrownException);
    }
}
