package br.kauan.notificationgateway.kafka;

import br.kauan.notificationgateway.grpc.SubscriberRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes every message from {@code notifications-topic} and fans it out
 * to all registered gRPC subscribers whose ISPB matches the Kafka record key.
 *
 * <p>No business logic lives here — the gateway is intentionally transparent.
 * JSON parsing happens on the PSP side, just as it did when PSPs consumed
 * Kafka directly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationKafkaConsumer {

    private static final String NOTIFICATIONS_TOPIC = "notifications-topic";

    private final SubscriberRegistry subscriberRegistry;

    @KafkaListener(
            topics = NOTIFICATIONS_TOPIC,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "notificationKafkaListenerContainerFactory"
    )
    public void consume(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_KEY) String ispb,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        log.info("Received notification — ISPB: {}, partition: {}, offset: {}", ispb, partition, offset);
        subscriberRegistry.dispatch(ispb, payload);
    }
}
