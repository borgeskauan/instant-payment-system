package br.kauan.notificationgateway.kafka;

import br.kauan.notificationgateway.delivery.IncomingNotification;
import br.kauan.notificationgateway.delivery.NotificationIndexingService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Consumes every message from {@code psp-notifications} and records its durable
 * position in the recipient's delivery flow. After the index transaction
 * commits, newly indexed payloads are buffered and any pending long-poll for
 * an affected recipient is signalled.
 *
 * <p>The payload remains opaque. Routing and idempotency metadata come from
 * Kafka key/headers produced by the SPI.
 */
@Slf4j
@Component
public class NotificationKafkaConsumer {

    private static final String NOTIFICATIONS_TOPIC = "psp-notifications";

    private final NotificationIndexingService indexingService;

    public NotificationKafkaConsumer(NotificationIndexingService indexingService) {
        this.indexingService = indexingService;
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
                    record.value()
            );
            log.debug(
                    "Indexing notification delivery. communicationId={}, ispb={}, partition={}, offset={}",
                    notification.communicationId(),
                    ispb,
                    record.partition(),
                    record.offset()
            );
            notifications.add(notification);
        }

        indexingService.ensureIndexed(notifications);
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
