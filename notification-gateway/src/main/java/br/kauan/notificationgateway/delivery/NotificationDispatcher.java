package br.kauan.notificationgateway.delivery;

import br.kauan.notificationgateway.grpc.SubscriberRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

@Slf4j
@Component
public class NotificationDispatcher {

    private static final String NO_SUBSCRIBER_ERROR = "No local subscriber available for ISPB ";
    private static final String EXECUTOR_REJECTED_ERROR =
            "Notification dispatch executor rejected the delivery group";

    private final NotificationDeliveryRepository repository;
    private final SubscriberRegistry subscriberRegistry;
    private final Executor notificationDispatchExecutor;
    private final Duration retryDelay;

    @Autowired
    public NotificationDispatcher(
            NotificationDeliveryRepository repository,
            SubscriberRegistry subscriberRegistry,
            @Qualifier("notificationDispatchExecutor") Executor notificationDispatchExecutor,
            @Value("${notification-gateway.delivery.retry-delay-ms:1000}") long retryDelayMillis
    ) {
        this(
                repository,
                subscriberRegistry,
                notificationDispatchExecutor,
                Duration.ofMillis(retryDelayMillis)
        );
    }

    NotificationDispatcher(
            NotificationDeliveryRepository repository,
            SubscriberRegistry subscriberRegistry,
            Executor notificationDispatchExecutor,
            Duration retryDelay
    ) {
        this.repository = repository;
        this.subscriberRegistry = subscriberRegistry;
        this.notificationDispatchExecutor = notificationDispatchExecutor;
        this.retryDelay = retryDelay;
    }

    public void dispatch(List<NotificationDelivery> deliveries) {
        var deliveriesByIspb = deliveries.stream()
                .collect(Collectors.groupingBy(
                        NotificationDelivery::recipientIspb,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        List<CompletableFuture<Void>> dispatches = new ArrayList<>(deliveriesByIspb.size());
        for (List<NotificationDelivery> group : deliveriesByIspb.values()) {
            try {
                dispatches.add(CompletableFuture.runAsync(
                        () -> dispatchSequentially(group),
                        notificationDispatchExecutor
                ));
            } catch (RejectedExecutionException exception) {
                log.warn("Notification dispatch executor rejected {} delivery attempt(s)", group.size());
                markRetryable(group, EXECUTOR_REJECTED_ERROR);
            }
        }
        CompletableFuture.allOf(dispatches.toArray(CompletableFuture[]::new)).join();
    }

    private void dispatchSequentially(List<NotificationDelivery> deliveries) {
        for (NotificationDelivery delivery : deliveries) {
            if (!subscriberRegistry.dispatch(delivery)) {
                repository.markRetryableFailed(
                        delivery.communicationId(),
                        NO_SUBSCRIBER_ERROR + delivery.recipientIspb(),
                        retryDelay
                );
            }
        }
    }

    private void markRetryable(List<NotificationDelivery> deliveries, String error) {
        for (NotificationDelivery delivery : deliveries) {
            repository.markRetryableFailed(delivery.communicationId(), error, retryDelay);
        }
    }
}
