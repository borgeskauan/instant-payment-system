package br.kauan.notificationgateway.delivery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public final class NotificationReconciler {

    private final NotificationReconciliationRepository repository;
    private final NotificationIndexingService indexingService;
    private final int batchSize;

    public NotificationReconciler(
            NotificationReconciliationRepository repository,
            NotificationIndexingService indexingService,
            @Value("${notification-gateway.reconciliation.batch-size:1000}") int batchSize
    ) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("reconciliation batch size must be positive");
        }
        this.repository = repository;
        this.indexingService = indexingService;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${notification-gateway.reconciliation.fixed-delay:1m}")
    public void reconcile() {
        String cursor = "";
        int candidates = 0;
        try {
            while (true) {
                List<IncomingNotification> page = repository.findUnindexedAfter(cursor, batchSize);
                if (page.isEmpty()) {
                    break;
                }

                candidates += page.size();
                cursor = page.getLast().communicationId();
                reconcileByRecipient(page);
                if (page.size() < batchSize) {
                    break;
                }
            }
        } catch (RuntimeException failure) {
            log.error("Notification reconciliation cycle failed; the next cycle will restart the scan", failure);
            return;
        }

        if (candidates > 0) {
            log.warn("Notification reconciliation scanned unindexed candidates. rows={}", candidates);
        }
    }

    private void reconcileByRecipient(List<IncomingNotification> page) {
        Map<String, List<IncomingNotification>> byRecipient = new LinkedHashMap<>();
        for (IncomingNotification notification : page) {
            byRecipient.computeIfAbsent(notification.recipientIspb(), ignored -> new ArrayList<>())
                    .add(notification);
        }

        for (Map.Entry<String, List<IncomingNotification>> recipient : byRecipient.entrySet()) {
            try {
                indexingService.ensureIndexed(List.copyOf(recipient.getValue()));
            } catch (RuntimeException failure) {
                log.error(
                        "Notification reconciliation failed for recipient; the next cycle will retry it. "
                                + "recipientIspb={}, rows={}",
                        recipient.getKey(),
                        recipient.getValue().size(),
                        failure
                );
            }
        }
    }
}
