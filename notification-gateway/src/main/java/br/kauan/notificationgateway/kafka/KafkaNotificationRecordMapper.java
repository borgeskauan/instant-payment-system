package br.kauan.notificationgateway.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

import java.nio.charset.StandardCharsets;

public final class KafkaNotificationRecordMapper {

    private KafkaNotificationRecordMapper() {
    }

    public static KafkaNotificationRecord map(ConsumerRecord<String, byte[]> record) {
        if (record.partition() < 0 || record.offset() < 0) {
            throw new IllegalArgumentException("Kafka partition and offset must not be negative");
        }
        if (record.key() == null || record.key().isBlank()) {
            throw new IllegalArgumentException("Missing notification recipient Kafka key");
        }
        if (record.value() == null) {
            throw new IllegalArgumentException("Missing notification payload");
        }
        return new KafkaNotificationRecord(
                record.partition(),
                record.offset(),
                record.key(),
                requiredHeader(record, "notification.communication-id"),
                record.value()
        );
    }

    private static String requiredHeader(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) {
            throw new IllegalArgumentException("Missing Kafka header: " + name);
        }
        String value = new String(header.value(), StandardCharsets.UTF_8);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing Kafka header: " + name);
        }
        return value;
    }
}
