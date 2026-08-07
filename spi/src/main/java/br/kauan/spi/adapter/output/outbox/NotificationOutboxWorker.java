package br.kauan.spi.adapter.output.outbox;

import br.kauan.spi.adapter.output.kafka.NotificationPublication;
import br.kauan.spi.adapter.output.kafka.NotificationPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Slf4j
@Component
public class NotificationOutboxWorker {

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationPublisher notificationPublisher;
    private final int batchSize;
    private final Duration retryDelay;

    public NotificationOutboxWorker(
            NotificationOutboxRepository outboxRepository,
            NotificationPublisher notificationPublisher,
            @Value("${spi.notification-outbox.batch-size}") int batchSize,
            @Value("${spi.notification-outbox.retry-delay}") Duration retryDelay
    ) {
        this.outboxRepository = outboxRepository;
        this.notificationPublisher = notificationPublisher;
        this.batchSize = batchSize;
        this.retryDelay = retryDelay;
    }

    @Scheduled(fixedDelayString = "${spi.notification-outbox.fixed-delay}")
    public void publishPending() {
        List<NotificationPublication> notifications = outboxRepository.findPending(batchSize);
        if (notifications.isEmpty()) {
            return;
        }

        List<PublicationAttempt> attempts = new ArrayList<>(notifications.size());
        for (NotificationPublication notification : notifications) {
            attempts.add(new PublicationAttempt(notification, startSend(notification)));
        }

        CompletableFuture.allOf(attempts.stream()
                        .map(PublicationAttempt::future)
                        .toArray(CompletableFuture[]::new))
                .handle((ignored, failure) -> null)
                .join();

        List<String> publishedCommunicationIds = new ArrayList<>(attempts.size());
        List<NotificationPublicationFailure> failures = new ArrayList<>();
        for (PublicationAttempt attempt : attempts) {
            try {
                attempt.future().join();
                publishedCommunicationIds.add(attempt.notification().communicationId());
            } catch (RuntimeException exception) {
                Throwable cause = failureCause(exception);
                failures.add(new NotificationPublicationFailure(
                        attempt.notification().communicationId(),
                        errorMessage(cause)
                ));
                log.warn(
                        "Kafka publication failed; notification remains pending. communicationId={}",
                        attempt.notification().communicationId(),
                        cause
                );
            }
        }

        updatePublished(publishedCommunicationIds);
        updateFailures(failures);
    }

    private CompletableFuture<SendResult<String, byte[]>> startSend(NotificationPublication notification) {
        try {
            return notificationPublisher.publish(notification);
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private void updatePublished(List<String> communicationIds) {
        if (communicationIds.isEmpty()) {
            return;
        }
        try {
            outboxRepository.markPublished(communicationIds);
        } catch (RuntimeException exception) {
            log.error(
                    "Failed to mark successfully published outbox rows; they will be published again. rows={}",
                    communicationIds.size(),
                    exception
            );
        }
    }

    private void updateFailures(List<NotificationPublicationFailure> failures) {
        if (failures.isEmpty()) {
            return;
        }
        try {
            outboxRepository.scheduleRetry(failures, retryDelay);
        } catch (RuntimeException exception) {
            log.error(
                    "Failed to schedule notification outbox retries; rows remain pending. rows={}",
                    failures.size(),
                    exception
            );
        }
    }

    private Throwable failureCause(RuntimeException exception) {
        return exception instanceof CompletionException && exception.getCause() != null
                ? exception.getCause()
                : exception;
    }

    private String errorMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getName()
                : failure.getClass().getName() + ": " + message;
    }

    private record PublicationAttempt(
            NotificationPublication notification,
            CompletableFuture<SendResult<String, byte[]>> future
    ) {
    }
}
