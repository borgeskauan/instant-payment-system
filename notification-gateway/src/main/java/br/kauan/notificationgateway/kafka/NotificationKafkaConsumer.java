package br.kauan.notificationgateway.kafka;

import br.kauan.notificationgateway.delivery.IncomingNotification;
import br.kauan.notificationgateway.delivery.NotificationDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Consumes every message from {@code psp-notifications} and records it as a
 * durable delivery. A separate worker sends pending deliveries to connected
 * gRPC subscribers and waits for ACKs.
 *
 * <p>The payload remains opaque. Routing and idempotency metadata come from
 * Kafka key/headers produced by the SPI.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationKafkaConsumer {

    private static final String NOTIFICATIONS_TOPIC = "psp-notifications";

    private final NotificationDeliveryRepository deliveryRepository;

    @KafkaListener(
            topics = NOTIFICATIONS_TOPIC,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "notificationKafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, byte[]> record) {
        String ispb = record.key();
        IncomingNotification notification = new IncomingNotification(
                requiredHeader(record, "notification.communication-id"),
                ispb,
                requiredHeader(record, "notification.event-type"),
                requiredHeader(record, "notification.payment-id"),
                optionalHeader(record, "notification.status"),
                requiredHeader(record, "notification.schema-version"),
                record.value()
        );

        log.debug(
                "Persisting notification delivery. communicationId={}, ispb={}, partition={}, offset={}",
                notification.communicationId(),
                ispb,
                record.partition(),
                record.offset()
        );
        deliveryRepository.saveIfAbsent(notification);
    }

    private String requiredHeader(ConsumerRecord<String, byte[]> record, String name) {
        String value = optionalHeader(record, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing Kafka header: " + name);
        }
        return value;
    }

    private String optionalHeader(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
