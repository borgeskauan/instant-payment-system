package br.kauan.spi.adapter.output.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Slf4j
@Service
public class NotificationPublisher {

    private static final String NOTIFICATION_TOPIC = "psp-notifications";
    
    private final KafkaTemplate<String, String> kafkaTemplate;

    public NotificationPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishNotifications(List<NotificationPublication> notifications) {
        if (notifications.isEmpty()) {
            return;
        }

        List<CompletableFuture<SendResult<String, String>>> futures =
                new ArrayList<>(notifications.size());

        try {
            notifications.forEach(notification ->
                    futures.add(kafkaTemplate.send(producerRecord(notification)))
            );

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (Exception e) {
            Throwable cause = publishFailureCause(e, futures);
            log.error("Error publishing notifications to Kafka", cause);
            throw new RecoverableNotificationPublishException("Failed to publish notification", cause);
        }
    }

    private ProducerRecord<String, String> producerRecord(NotificationPublication notification) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(NOTIFICATION_TOPIC, notification.ispb(), notification.payload());
        addHeader(record, "notification.communication-id", notification.communicationId());
        addHeader(record, "notification.event-type", notification.eventType());
        addHeader(record, "notification.payment-id", notification.paymentId());
        addHeader(record, "notification.schema-version", notification.schemaVersion());
        if (notification.status() != null && !notification.status().isBlank()) {
            addHeader(record, "notification.status", notification.status());
        }
        return record;
    }

    private void addHeader(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private Throwable publishFailureCause(
            Exception exception,
            List<CompletableFuture<SendResult<String, String>>> futures
    ) {
        for (CompletableFuture<SendResult<String, String>> future : futures) {
            if (future.isCompletedExceptionally()) {
                try {
                    future.join();
                } catch (CompletionException futureException) {
                    return futureException.getCause() != null ? futureException.getCause() : futureException;
                }
            }
        }

        return exception instanceof CompletionException && exception.getCause() != null
                ? exception.getCause()
                : exception;
    }

}
