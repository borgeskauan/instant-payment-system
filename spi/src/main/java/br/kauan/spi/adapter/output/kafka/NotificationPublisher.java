package br.kauan.spi.adapter.output.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class NotificationPublisher {

    private static final String NOTIFICATION_TOPIC = "notifications-topic";
    
    private final KafkaTemplate<String, String> kafkaTemplate;

    public NotificationPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publishes a notification to Kafka topic with ISPB as the message key.
     * This ensures all notifications for the same ISPB go to the same partition,
     * maintaining ordering per ISPB.
     *
     * @param ispb The ISPB (bank code) - used as message key
     * @param notificationJson The notification content as JSON string
     */
    public void publishNotification(String ispb, String notificationJson) {
        try {
            log.debug("Publishing notification for ISPB: {} to topic: {}", ispb, NOTIFICATION_TOPIC);
            
            CompletableFuture<SendResult<String, String>> future = 
                    kafkaTemplate.send(NOTIFICATION_TOPIC, ispb, notificationJson);
            
            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.debug("Notification published successfully for ISPB: {}, partition: {}, offset: {}",
                            ispb, 
                            result.getRecordMetadata().partition(), 
                            result.getRecordMetadata().offset());
                } else {
                    log.error("Failed to publish notification for ISPB: {}", ispb, ex);
                }
            });
            
        } catch (Exception e) {
            log.error("Error publishing notification to Kafka for ISPB: {}", ispb, e);
            throw new RuntimeException("Failed to publish notification", e);
        }
    }

    /**
     * Synchronous version - waits for acknowledgment.
     * Use for critical notifications where you need confirmation.
     */
    public void publishNotificationSync(String ispb, String notificationJson) {
        try {
            log.debug("Publishing notification (sync) for ISPB: {}", ispb);
            
            SendResult<String, String> result = 
                    kafkaTemplate.send(NOTIFICATION_TOPIC, ispb, notificationJson).get();
            
            log.info("Notification published successfully (sync) for ISPB: {}, partition: {}, offset: {}",
                    ispb, 
                    result.getRecordMetadata().partition(), 
                    result.getRecordMetadata().offset());
            
        } catch (Exception e) {
            log.error("Error publishing notification (sync) to Kafka for ISPB: {}", ispb, e);
            throw new RuntimeException("Failed to publish notification synchronously", e);
        }
    }
}
