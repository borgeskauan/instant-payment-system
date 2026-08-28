package br.kauan.spi.adapter.output.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class OutboundNotificationPublisher {

    private final NotificationOutboxPipeline pipeline;

    public OutboundNotificationPublisher(NotificationOutboxPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishCommitted(OutboundNotificationBatchReady batch) {
        try {
            pipeline.enqueue(batch);
        } catch (RuntimeException failure) {
            log.error(
                    "Committed notification batch could not enter the fast path; durable outbox recovery remains responsible. notifications={}",
                    batch.notifications().size(),
                    failure
            );
        }
    }
}
