package br.kauan.spi.adapter.output.notification;

import br.kauan.spi.adapter.output.kafka.NotificationPublication;
import br.kauan.spi.adapter.output.kafka.NotificationPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class OutboundNotificationPublisher {

    private final NotificationPublisher notificationPublisher;

    public OutboundNotificationPublisher(NotificationPublisher notificationPublisher) {
        this.notificationPublisher = notificationPublisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishCommitted(OutboundNotificationBatchReady batch) {
        for (NotificationPublication notification : batch.notifications()) {
            publish(notification);
        }
    }

    private void publish(NotificationPublication notification) {
        try {
            notificationPublisher.publish(notification).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    log.warn(
                            "Best-effort Kafka notification publication failed; reconciliation will recover it. "
                                    + "communicationId={} cause={}",
                            notification.communicationId(),
                            failure.toString()
                    );
                }
            });
        } catch (RuntimeException failure) {
            log.warn(
                    "Best-effort Kafka notification publication could not start; reconciliation will recover it. "
                            + "communicationId={} cause={}",
                    notification.communicationId(),
                    failure.toString()
            );
        }
    }
}
