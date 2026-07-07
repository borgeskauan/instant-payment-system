package br.kauan.spi.adapter.output.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    public void publishNotifications(Map<String, String> notificationsByIspb) {
        if (notificationsByIspb.isEmpty()) {
            return;
        }

        List<CompletableFuture<SendResult<String, String>>> futures =
                new ArrayList<>(notificationsByIspb.size());

        try {
            notificationsByIspb.forEach((ispb, notificationJson) -> {
                log.debug("Publishing notification for ISPB: {} to topic: {}", ispb, NOTIFICATION_TOPIC);

                CompletableFuture<SendResult<String, String>> future =
                        kafkaTemplate.send(NOTIFICATION_TOPIC, ispb, notificationJson);

                futures.add(future.whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.debug("Notification published successfully for ISPB: {}, partition: {}, offset: {}",
                                ispb,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    } else {
                        log.error("Failed to publish notification for ISPB: {}", ispb, ex);
                    }
                }));
            });

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (Exception e) {
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            log.error("Error publishing notifications to Kafka", cause);
            throw new RecoverableNotificationPublishException("Failed to publish notification", cause);
        }
    }

}
