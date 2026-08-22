package br.kauan.spi.adapter.output.kafka;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.springframework.kafka.support.SendResult;

@Service
public class NotificationPublisher {

    private static final String NOTIFICATION_TOPIC = "psp-notifications";
    
    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    public NotificationPublisher(
            @Qualifier("notificationKafkaTemplate") KafkaTemplate<String, byte[]> kafkaTemplate
    ) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public CompletableFuture<SendResult<String, byte[]>> publish(NotificationPublication notification) {
        return kafkaTemplate.send(producerRecord(notification));
    }

    private ProducerRecord<String, byte[]> producerRecord(NotificationPublication notification) {
        ProducerRecord<String, byte[]> record =
                new ProducerRecord<>(NOTIFICATION_TOPIC, notification.recipientIspb(), notification.payload());
        addHeader(record, "notification.communication-id", notification.communicationId());
        return record;
    }

    private void addHeader(ProducerRecord<String, byte[]> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
