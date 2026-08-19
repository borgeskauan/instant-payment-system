package br.kauan.notificationgateway.kafka;

import br.kauan.notificationgateway.delivery.IncomingNotification;
import br.kauan.notificationgateway.delivery.NotificationDispatcher;
import br.kauan.notificationgateway.delivery.NotificationDeliveryRepository;
import br.kauan.notificationgateway.grpc.SubscriberRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Consumes every message from {@code psp-notifications} and records it as a
 * durable delivery. Newly inserted deliveries for locally connected PSPs are
 * sent after the persistence transaction commits. A recovery worker handles
 * disconnected recipients, send failures and expired leases.
 *
 * <p>The payload remains opaque. Routing and idempotency metadata come from
 * Kafka key/headers produced by the SPI.
 */
@Slf4j
@Component
public class NotificationKafkaConsumer {

    private static final String NOTIFICATIONS_TOPIC = "psp-notifications";

    private final NotificationDeliveryRepository deliveryRepository;
    private final SubscriberRegistry subscriberRegistry;
    private final NotificationDispatcher notificationDispatcher;
    private final Duration leaseDuration;

    public NotificationKafkaConsumer(
            NotificationDeliveryRepository deliveryRepository,
            SubscriberRegistry subscriberRegistry,
            NotificationDispatcher notificationDispatcher,
            @Value("${notification-gateway.delivery.lease-duration-ms:30000}") long leaseDurationMillis
    ) {
        this.deliveryRepository = deliveryRepository;
        this.subscriberRegistry = subscriberRegistry;
        this.notificationDispatcher = notificationDispatcher;
        this.leaseDuration = Duration.ofMillis(leaseDurationMillis);
    }

    @KafkaListener(
            topics = NOTIFICATIONS_TOPIC,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "notificationKafkaListenerContainerFactory"
    )
    public void consume(List<ConsumerRecord<String, byte[]>> records) {
        if (records.isEmpty()) {
            return;
        }

        List<IncomingNotification> notifications = new ArrayList<>(records.size());
        for (ConsumerRecord<String, byte[]> record : records) {
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
            notifications.add(notification);
        }

        var directDeliveries = deliveryRepository.saveAllIfAbsent(
                notifications,
                subscriberRegistry.connectedIspbs(),
                leaseDuration
        );
        if (!directDeliveries.isEmpty()) {
            notificationDispatcher.dispatch(directDeliveries);
        }
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
