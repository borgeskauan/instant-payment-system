package br.kauan.notificationgateway.delivery;

import br.kauan.notificationgateway.grpc.SubscriberRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDeliveryWorker {

    private final NotificationDeliveryRepository repository;
    private final SubscriberRegistry subscriberRegistry;

    @Value("${notification-gateway.delivery.batch-size:100}")
    private int batchSize;

    @Value("${notification-gateway.delivery.lease-duration-ms:30000}")
    private long leaseDurationMillis;

    @Value("${notification-gateway.delivery.retry-delay-ms:1000}")
    private long retryDelayMillis;

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

        if (!deliveries.isEmpty()) {
            log.debug("Claimed and dispatched {} notification delivery attempt(s)", deliveries.size());
        }
    }
}
