package br.kauan.notificationgateway.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AcknowledgementBatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AcknowledgementBatcher.class);
    private static final long ENQUEUE_OFFER_SLICE_MILLIS = 50;

    private final NotificationDeliveryRepository repository;
    private final int batchSize;
    private final Duration flushInterval;
    private final Duration retryDelay;
    private final Duration shutdownTimeout;
    private final ArrayBlockingQueue<Acknowledgement> queue;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean accepting = new AtomicBoolean();
    private final Object lifecycleMonitor = new Object();

    private volatile Thread writerThread;
    private volatile long shutdownDeadlineNanos = Long.MAX_VALUE;
    private int admissionsInProgress;

    @Autowired
    public AcknowledgementBatcher(
            NotificationDeliveryRepository repository,
            @Value("${notification-gateway.delivery.ack.batch-size:500}") int batchSize,
            @Value("${notification-gateway.delivery.ack.flush-interval-ms:20}") long flushIntervalMillis,
            @Value("${notification-gateway.delivery.ack.queue-capacity:10000}") int queueCapacity,
            @Value("${notification-gateway.delivery.ack.retry-delay-ms:100}") long retryDelayMillis,
            @Value("${notification-gateway.delivery.ack.shutdown-timeout-ms:5000}") long shutdownTimeoutMillis
    ) {
        this(
                repository,
                batchSize,
                positiveDuration(flushIntervalMillis, "flushIntervalMillis"),
                queueCapacity,
                positiveDuration(retryDelayMillis, "retryDelayMillis"),
                positiveDuration(shutdownTimeoutMillis, "shutdownTimeoutMillis")
        );
    }

    AcknowledgementBatcher(
            NotificationDeliveryRepository repository,
            int batchSize,
            Duration flushInterval,
            int queueCapacity,
            Duration retryDelay,
            Duration shutdownTimeout
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.batchSize = positive(batchSize, "batchSize");
        this.flushInterval = positive(flushInterval, "flushInterval");
        this.queue = new ArrayBlockingQueue<>(positive(queueCapacity, "queueCapacity"));
        this.retryDelay = positive(retryDelay, "retryDelay");
        this.shutdownTimeout = positive(shutdownTimeout, "shutdownTimeout");
    }

    public boolean enqueue(Acknowledgement acknowledgement) throws InterruptedException {
        Objects.requireNonNull(acknowledgement, "acknowledgement");
        synchronized (lifecycleMonitor) {
            if (!accepting.get()) {
                return false;
            }
            admissionsInProgress++;
        }
        try {
            while (accepting.get()) {
                if (queue.offer(acknowledgement, ENQUEUE_OFFER_SLICE_MILLIS, TimeUnit.MILLISECONDS)) {
                    return true;
                }
            }
            return false;
        } finally {
            synchronized (lifecycleMonitor) {
                admissionsInProgress--;
                lifecycleMonitor.notifyAll();
            }
        }
    }

    @Override
    public void start() {
        synchronized (lifecycleMonitor) {
            if (running.get()) {
                return;
            }
            if (writerThread != null && writerThread.isAlive()) {
                log.warn("Cannot restart acknowledgement batcher while its previous writer is still exiting");
                return;
            }

            shutdownDeadlineNanos = Long.MAX_VALUE;
            running.set(true);
            accepting.set(true);
            writerThread = Thread.ofPlatform()
                    .daemon()
                    .name("notification-ack-batcher")
                    .start(this::runWriter);
        }
    }

    @Override
    public void stop() {
        Thread writer;
        boolean interrupted = false;
        synchronized (lifecycleMonitor) {
            accepting.set(false);
            while (admissionsInProgress > 0) {
                try {
                    lifecycleMonitor.wait();
                } catch (InterruptedException stopInterrupted) {
                    interrupted = true;
                }
            }
            if (running.getAndSet(false)) {
                shutdownDeadlineNanos = deadlineAfter(shutdownTimeout);
            }
            writer = writerThread;
        }

        if (writer == null || writer == Thread.currentThread()) {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return;
        }

        writer.interrupt();
        interrupted |= joinUntilShutdownDeadline(writer);
        synchronized (lifecycleMonitor) {
            if (!writer.isAlive() && writerThread == writer) {
                writerThread = null;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }

    private void runWriter() {
        List<Acknowledgement> retainedBatch = null;
        try {
            while (shouldContinue(retainedBatch)) {
                if (retainedBatch == null) {
                    retainedBatch = nextBatch();
                    if (retainedBatch == null) {
                        continue;
                    }
                }

                try {
                    int updated = repository.acknowledgeAll(retainedBatch);
                    int requested = retainedBatch.size();
                    log.debug(
                            "Persisted acknowledgement batch: requested={}, updated={}, ignored={}",
                            requested,
                            updated,
                            requested - updated
                    );
                    retainedBatch = null;
                } catch (DataAccessException | TransactionException failure) {
                    log.warn(
                            "Failed to persist acknowledgement batch of {} item(s); retaining it for retry: {}",
                            retainedBatch.size(),
                            failure.getMessage()
                    );
                    waitBeforeRetry();
                }
            }
        } finally {
            accepting.set(false);
            running.set(false);
            int retainedCount = retainedBatch == null ? 0 : retainedBatch.size();
            int uncommittedCount = retainedCount + queue.size();
            if (uncommittedCount > 0) {
                log.warn(
                        "Acknowledgement batcher stopped with {} uncommitted ACK(s); they will recover by redelivery",
                        uncommittedCount
                );
            }
            synchronized (lifecycleMonitor) {
                if (writerThread == Thread.currentThread()) {
                    writerThread = null;
                }
            }
        }
    }

    private List<Acknowledgement> nextBatch() {
        List<Acknowledgement> acknowledgements = new ArrayList<>(batchSize);
        try {
            Acknowledgement first = running.get() ? queue.take() : queue.poll();
            if (first == null) {
                return null;
            }
            acknowledgements.add(first);

            long flushDeadlineNanos = deadlineAfter(flushInterval);
            while (acknowledgements.size() < batchSize) {
                if (!running.get()) {
                    drainAvailable(acknowledgements);
                    break;
                }
                long remainingNanos = flushDeadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    break;
                }
                Acknowledgement acknowledgement = queue.poll(remainingNanos, TimeUnit.NANOSECONDS);
                if (acknowledgement == null) {
                    break;
                }
                acknowledgements.add(acknowledgement);
            }
        } catch (InterruptedException shutdownWakeUp) {
            // stop() interrupts queue waits so accepted work can flush immediately.
        }

        if (!running.get()) {
            drainAvailable(acknowledgements);
        }

        if (acknowledgements.isEmpty()) {
            return null;
        }
        return List.copyOf(new LinkedHashSet<>(acknowledgements));
    }

    private void drainAvailable(List<Acknowledgement> acknowledgements) {
        queue.drainTo(acknowledgements, batchSize - acknowledgements.size());
    }

    private boolean shouldContinue(List<Acknowledgement> retainedBatch) {
        if (running.get()) {
            return true;
        }
        return beforeShutdownDeadline() && (retainedBatch != null || !queue.isEmpty());
    }

    private void waitBeforeRetry() {
        long waitNanos = retryDelay.toNanos();
        if (!running.get()) {
            waitNanos = Math.min(waitNanos, remainingShutdownNanos());
        }
        if (waitNanos <= 0) {
            return;
        }
        try {
            TimeUnit.NANOSECONDS.sleep(waitNanos);
        } catch (InterruptedException shutdownWakeUp) {
            // A shutdown interrupt wakes retry delay without extending the fixed deadline.
        }
    }

    private boolean joinUntilShutdownDeadline(Thread writer) {
        while (writer.isAlive()) {
            long remainingNanos = remainingShutdownNanos();
            if (remainingNanos <= 0) {
                return false;
            }
            try {
                TimeUnit.NANOSECONDS.timedJoin(writer, remainingNanos);
            } catch (InterruptedException callerInterrupted) {
                return true;
            }
        }
        return false;
    }

    private boolean beforeShutdownDeadline() {
        return remainingShutdownNanos() > 0;
    }

    private long remainingShutdownNanos() {
        return shutdownDeadlineNanos - System.nanoTime();
    }

    private static long deadlineAfter(Duration duration) {
        return System.nanoTime() + duration.toNanos();
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        try {
            value.toNanos();
        } catch (ArithmeticException tooLarge) {
            throw new IllegalArgumentException(name + " is too large", tooLarge);
        }
        return value;
    }

    private static Duration positiveDuration(long millis, String name) {
        if (millis <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return Duration.ofMillis(millis);
    }
}
