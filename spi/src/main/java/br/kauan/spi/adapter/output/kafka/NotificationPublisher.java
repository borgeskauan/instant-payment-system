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
            notificationsByIspb.forEach((ispb, notificationJson) ->
                    futures.add(kafkaTemplate.send(NOTIFICATION_TOPIC, ispb, notificationJson))
            );

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (Exception e) {
            Throwable cause = publishFailureCause(e, futures);
            log.error("Error publishing notifications to Kafka", cause);
            throw new RecoverableNotificationPublishException("Failed to publish notification", cause);
        }
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
