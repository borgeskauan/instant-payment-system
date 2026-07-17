package br.kauan.notificationgateway.delivery;

import br.kauan.notificationgateway.grpc.SubscriberRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Slf4j
@Component
public class NotificationDeliveryWorker {

    private final NotificationDeliveryRepository repository;
    private final SubscriberRegistry subscriberRegistry;
    private final Executor notificationDispatchExecutor;

    @Value("${notification-gateway.delivery.batch-size:100}")
    private int batchSize;

    @Value("${notification-gateway.delivery.lease-duration-ms:30000}")
    private long leaseDurationMillis;

    @Value("${notification-gateway.delivery.retry-delay-ms:1000}")
    private long retryDelayMillis;

    public NotificationDeliveryWorker(
            NotificationDeliveryRepository repository,
            SubscriberRegistry subscriberRegistry,
            @Qualifier("notificationDispatchExecutor") Executor notificationDispatchExecutor
    ) {
        this.repository = repository;
        this.subscriberRegistry = subscriberRegistry;
        this.notificationDispatchExecutor = notificationDispatchExecutor;
    }

    @Scheduled(fixedDelayString = "${notification-gateway.delivery.worker-delay-ms:250}")
    public void deliverPendingNotifications() {
        Set<String> localIspbs = subscriberRegistry.connectedIspbs();
        if (localIspbs.isEmpty()) {
            return;
        }

        var deliveries = repository.claimForLocalIspbs(
                localIspbs,
                batchSize,
                Duration.ofMillis(leaseDurationMillis)
        );

        Map<String, List<NotificationDelivery>> deliveriesByIspb = deliveries.stream()
                .collect(Collectors.groupingBy(
                        NotificationDelivery::recipientIspb,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        CompletableFuture<?>[] dispatches = deliveriesByIspb.values().stream()
                .map(group -> CompletableFuture.runAsync(
                        () -> dispatchSequentially(group),
                        notificationDispatchExecutor
                ))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(dispatches).join();

        if (!deliveries.isEmpty()) {
            log.debug("Claimed and dispatched {} notification delivery attempt(s)", deliveries.size());
        }
    }

    private void dispatchSequentially(List<NotificationDelivery> deliveries) {
        for (NotificationDelivery delivery : deliveries) {
            boolean sent = subscriberRegistry.dispatch(delivery);
            if (!sent) {
                repository.markRetryableFailed(
                        delivery.communicationId(),
                        "No local subscriber available for ISPB " + delivery.recipientIspb(),
                        Duration.ofMillis(retryDelayMillis)
                );
            }
        }
    }
}
