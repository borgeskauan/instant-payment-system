package br.kauan.spi.adapter.output.kafka;

import br.kauan.spi.application.notification.OutboundNotification;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class NotificationPublisher {

    public static final String NOTIFICATION_TOPIC = "psp-notifications-v1";
    
    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    public NotificationPublisher(
            @Qualifier("notificationKafkaTemplate") KafkaTemplate<String, byte[]> kafkaTemplate
    ) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public CompletableFuture<Void> publishAll(List<OutboundNotification> notifications) {
        List<CompletableFuture<?>> confirmations = new ArrayList<>(notifications.size());
        for (OutboundNotification notification : notifications) {
            try {
                confirmations.add(kafkaTemplate.send(producerRecord(notification)));
            } catch (RuntimeException failure) {
                confirmations.add(CompletableFuture.failedFuture(failure));
            }
        }
        return CompletableFuture.allOf(confirmations.toArray(CompletableFuture[]::new));
    }

    private ProducerRecord<String, byte[]> producerRecord(OutboundNotification notification) {
        ProducerRecord<String, byte[]> record =
                new ProducerRecord<>(NOTIFICATION_TOPIC, notification.recipientIspb(), notification.payload());
        addHeader(record, "notification.communication-id", notification.communicationId());
        return record;
    }

    private void addHeader(ProducerRecord<String, byte[]> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
