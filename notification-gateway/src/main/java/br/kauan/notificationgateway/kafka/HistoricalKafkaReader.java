package br.kauan.notificationgateway.kafka;

import br.kauan.notificationgateway.config.NotificationGatewayProperties;
import br.kauan.notificationgateway.delivery.DeliveryNotification;
import br.kauan.notificationgateway.delivery.DeliveryPage;
import br.kauan.notificationgateway.delivery.InvalidNotificationOffsetException;
import br.kauan.notificationgateway.delivery.NotificationCursorExpiredException;
import br.kauan.notificationgateway.delivery.NotificationHistory;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

@Component
public final class HistoricalKafkaReader implements NotificationHistory {

    private final IntFunction<Consumer<String, byte[]>> consumerFactory;
    private final Duration pollTimeout;
    private final Map<Integer, Consumer<String, byte[]>> consumers = new HashMap<>();

    @Autowired
    public HistoricalKafkaReader(
            ConsumerFactory<String, byte[]> notificationConsumerFactory,
            NotificationGatewayProperties properties
    ) {
        this(
                partition -> notificationConsumerFactory.createConsumer(
                        null,
                        "historical-" + partition,
                        null
                ),
                properties.pull().kafkaPollTimeout()
        );
    }

    HistoricalKafkaReader(
            IntFunction<Consumer<String, byte[]>> consumerFactory,
            Duration pollTimeout
    ) {
        this.consumerFactory = consumerFactory;
        this.pollTimeout = pollTimeout;
    }

    @Override
    public DeliveryPage read(
            String recipientIspb,
            int partition,
            long afterOffset,
            int notificationLimit,
            int scanLimit
    ) {
        if (notificationLimit < 1 || scanLimit < notificationLimit) {
            throw new IllegalArgumentException("Kafka scan limits are invalid");
        }
        Consumer<String, byte[]> consumer;
        synchronized (consumers) {
            consumer = consumers.computeIfAbsent(partition, consumerFactory::apply);
        }
        synchronized (consumer) {
            return readWith(consumer, recipientIspb, partition, afterOffset, notificationLimit, scanLimit);
        }
    }

    private DeliveryPage readWith(
            Consumer<String, byte[]> consumer,
            String recipientIspb,
            int partition,
            long afterOffset,
            int notificationLimit,
            int scanLimit
    ) {
        TopicPartition topicPartition = new TopicPartition(NotificationLog.TOPIC, partition);
        Set<TopicPartition> singleton = Set.of(topicPartition);
        consumer.assign(singleton);
        long beginning = consumer.beginningOffsets(singleton).get(topicPartition);
        long endExclusive = consumer.endOffsets(singleton).get(topicPartition);
        if (afterOffset >= 0 && afterOffset + 1 < beginning) {
            throw new NotificationCursorExpiredException();
        }
        if (afterOffset >= endExclusive && afterOffset >= 0) {
            throw new InvalidNotificationOffsetException();
        }

        long nextOffset = afterOffset < 0 ? beginning : afterOffset + 1;
        if (nextOffset == endExclusive) {
            return new DeliveryPage(List.of(), afterOffset, true);
        }
        consumer.seek(topicPartition, nextOffset);
        List<DeliveryNotification> matches = new ArrayList<>(notificationLimit);
        long lastExamined = afterOffset;
        int examined = 0;

        while (nextOffset < endExclusive && examined < scanLimit && matches.size() < notificationLimit) {
            ConsumerRecords<String, byte[]> polled = consumer.poll(pollTimeout);
            List<ConsumerRecord<String, byte[]>> records = polled.records(topicPartition);
            if (records.isEmpty()) {
                break;
            }
            for (ConsumerRecord<String, byte[]> record : records) {
                if (record.offset() < nextOffset || record.offset() >= endExclusive) {
                    continue;
                }
                lastExamined = record.offset();
                nextOffset = record.offset() + 1;
                examined++;
                DeliveryNotification notification = KafkaNotificationRecordMapper.map(record);
                if (recipientIspb.equals(notification.recipientIspb())) {
                    matches.add(notification);
                }
                if (examined >= scanLimit || matches.size() >= notificationLimit) {
                    break;
                }
            }
        }
        return new DeliveryPage(
                matches,
                lastExamined,
                lastExamined >= endExclusive - 1
        );
    }

    @PreDestroy
    public void close() {
        synchronized (consumers) {
            consumers.values().forEach(Consumer::close);
            consumers.clear();
        }
    }
}
