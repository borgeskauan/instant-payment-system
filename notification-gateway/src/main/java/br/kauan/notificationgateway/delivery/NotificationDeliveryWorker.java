package br.kauan.notificationgateway.delivery;

import br.kauan.notificationgateway.grpc.SubscriberRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

@Slf4j
@Component
public class NotificationDeliveryWorker {

    private final NotificationDeliveryRepository repository;
    private final SubscriberRegistry subscriberRegistry;
    private final NotificationDispatcher notificationDispatcher;

    @Value("${notification-gateway.delivery.batch-size:100}")
    private int batchSize;

    @Value("${notification-gateway.delivery.lease-duration-ms:30000}")
    private long leaseDurationMillis;

    public NotificationDeliveryWorker(
            NotificationDeliveryRepository repository,
            SubscriberRegistry subscriberRegistry,
            NotificationDispatcher notificationDispatcher
    ) {
        this.repository = repository;
        this.subscriberRegistry = subscriberRegistry;
        this.notificationDispatcher = notificationDispatcher;
    }

    @Scheduled(fixedDelayString = "${notification-gateway.delivery.worker-delay-ms:1000}")
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

        if (!deliveries.isEmpty()) {
            notificationDispatcher.dispatch(deliveries);
            log.debug("Recovered and dispatched {} notification delivery attempt(s)", deliveries.size());
        }
    }
}
