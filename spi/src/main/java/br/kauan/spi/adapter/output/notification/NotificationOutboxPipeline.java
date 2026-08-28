package br.kauan.spi.adapter.output.notification;

import br.kauan.spi.adapter.output.kafka.NotificationPublisher;
import br.kauan.spi.application.notification.OutboundNotification;
import br.kauan.spi.application.notification.OutboundNotificationBatchReady;
import br.kauan.spi.config.NotificationOutboxProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class NotificationOutboxPipeline {

    private final OutboundNotificationRepository repository;
    private final NotificationPublisher publisher;
    private final BlockingQueue<OutboundNotificationBatchReady> queue;
    private final int recoveryBatchSize;
    private final Duration retryDelay;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean healthy = new AtomicBoolean();
    private volatile Thread worker;

    public NotificationOutboxPipeline(
            OutboundNotificationRepository repository,
            NotificationPublisher publisher,
            NotificationOutboxProperties properties
    ) {
        this.repository = repository;
        this.publisher = publisher;
        this.queue = new ArrayBlockingQueue<>(properties.queueCapacity());
        this.recoveryBatchSize = properties.recoveryBatchSize();
        this.retryDelay = properties.retryDelay();
    }

    public synchronized void startAndRecover() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        healthy.set(true);
        try {
            recoverCommittedRows();
        } catch (RuntimeException failure) {
            running.set(false);
            healthy.set(false);
            throw failure;
        }
        worker = Thread.ofPlatform()
                .name("notification-outbox-publisher")
                .daemon(false)
                .start(this::runWorker);
    }

    public void enqueue(OutboundNotificationBatchReady batch) {
        if (!isHealthy()) {
            throw new IllegalStateException("Notification outbox pipeline is not available");
        }
        try {
            queue.put(batch);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while applying notification publication backpressure", interrupted);
        }
    }

    public boolean isHealthy() {
        return running.get() && healthy.get();
    }

    public synchronized void stop() {
        running.set(false);
        Thread activeWorker = worker;
        if (activeWorker != null) {
            activeWorker.interrupt();
            try {
                activeWorker.join(5_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
        healthy.set(false);
    }

    private void recoverCommittedRows() {
        while (running.get()) {
            List<OutboundNotification> recovered = repository.findOldest(recoveryBatchSize);
            if (recovered.isEmpty()) {
                return;
            }
            publishRetainingUntilSuccess(new OutboundNotificationBatchReady(recovered));
        }
        throw new IllegalStateException("Notification outbox recovery stopped before completion");
    }

    private void runWorker() {
        try {
            while (running.get()) {
                OutboundNotificationBatchReady batch = queue.take();
                publishRetainingUntilSuccess(batch);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException unexpected) {
            healthy.set(false);
            log.error("Notification outbox publisher stopped unexpectedly", unexpected);
        } finally {
            if (running.get()) {
                healthy.set(false);
            }
        }
    }

    private void publishRetainingUntilSuccess(OutboundNotificationBatchReady batch) {
        List<String> communicationIds = batch.notifications().stream()
                .map(OutboundNotification::communicationId)
                .toList();
        while (running.get()) {
            try {
                publisher.publishAll(batch.notifications()).get();
                repository.deleteAll(communicationIds);
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Notification outbox publication interrupted", interrupted);
            } catch (ExecutionException | RuntimeException failure) {
                log.warn(
                        "Notification outbox batch publication failed; retaining and retrying whole batch. "
                                + "notifications={} cause={}",
                        batch.notifications().size(),
                        rootCause(failure).toString()
                );
                pauseBeforeRetry();
            }
        }
        throw new IllegalStateException("Notification outbox pipeline stopped with a retained batch");
    }

    private void pauseBeforeRetry() {
        try {
            Thread.sleep(retryDelay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Notification outbox retry interrupted", interrupted);
        }
    }

    private Throwable rootCause(Throwable failure) {
        return failure instanceof ExecutionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }
}
