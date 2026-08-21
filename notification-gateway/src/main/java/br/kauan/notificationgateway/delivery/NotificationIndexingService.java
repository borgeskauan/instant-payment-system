package br.kauan.notificationgateway.delivery;

import br.kauan.notificationgateway.grpc.PullRequestCoordinator;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public final class NotificationIndexingService {

    private final DeliveryIndexRepository repository;
    private final RecentNotificationBuffer buffer;
    private final PullRequestCoordinator coordinator;

    public NotificationIndexingService(
            DeliveryIndexRepository repository,
            RecentNotificationBuffer buffer,
            PullRequestCoordinator coordinator
    ) {
        this.repository = repository;
        this.buffer = buffer;
        this.coordinator = coordinator;
    }

    public void ensureIndexed(List<IncomingNotification> notifications) {
        List<NotificationDelivery> indexed = repository.indexNew(notifications);
        if (indexed.isEmpty()) {
            return;
        }

        buffer.addAll(indexed);
        Set<String> recipients = new LinkedHashSet<>();
        for (NotificationDelivery delivery : indexed) {
            recipients.add(delivery.recipientIspb());
        }
        coordinator.signal(Set.copyOf(recipients));
    }
}
