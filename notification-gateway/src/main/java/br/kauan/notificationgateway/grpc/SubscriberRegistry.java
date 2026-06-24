package br.kauan.notificationgateway.grpc;

import br.kauan.notificationgateway.grpc.proto.NotificationBatch;
import com.google.protobuf.UnsafeByteOperations;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe registry that maps each ISPB to the list of active gRPC stream
 * observers subscribed to notifications for that ISPB.
 *
 * <p>The Kafka consumer calls {@link #dispatch} whenever a message arrives;
 * the gRPC service calls {@link #register}/{@link #unregister} as clients
 * connect and disconnect.
 */
@Slf4j
@Component
public class SubscriberRegistry {

    /**
     * ISPB → list of active stream observers for that ISPB.
     * CopyOnWriteArrayList ensures safe iteration while dispatch is in progress.
     */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<StreamObserver<NotificationBatch>>> subscribers =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BatchState> batches = new ConcurrentHashMap<>();
    private final int maxBatchSize;
    private final long maxDelayMillis;
    private final ScheduledExecutorService scheduler;

    @Autowired
    public SubscriberRegistry(
            @Value("${notification-gateway.grpc.batch.max-size:64}") Integer maxBatchSize,
            @Value("${notification-gateway.grpc.batch.max-delay-ms:5}") Long maxDelayMillis
    ) {
        this(maxBatchSize, maxDelayMillis, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "notification-batch-flusher");
            thread.setDaemon(true);
            return thread;
        }));
    }

    SubscriberRegistry(int maxBatchSize, long maxDelayMillis) {
        this(maxBatchSize, maxDelayMillis, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "notification-batch-flusher-test");
            thread.setDaemon(true);
            return thread;
        }));
    }

    private SubscriberRegistry(int maxBatchSize, long maxDelayMillis, ScheduledExecutorService scheduler) {
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive");
        }
        if (maxDelayMillis <= 0) {
            throw new IllegalArgumentException("maxDelayMillis must be positive");
        }
        this.maxBatchSize = maxBatchSize;
        this.maxDelayMillis = maxDelayMillis;
        this.scheduler = scheduler;
    }

    /**
     * Registers a new gRPC stream observer for the given ISPB.
     * Called when a client opens a {@code StreamNotifications} stream.
     */
    public void register(String ispb, StreamObserver<NotificationBatch> observer) {
        subscribers
                .computeIfAbsent(ispb, k -> new CopyOnWriteArrayList<>())
                .add(observer);
        log.info("Registered new subscriber for ISPB: {} (total: {})", ispb, subscribers.get(ispb).size());
    }

    /**
     * Removes a gRPC stream observer for the given ISPB.
     * Called when the client disconnects or an error occurs.
     */
    public void unregister(String ispb, StreamObserver<NotificationBatch> observer) {
        List<StreamObserver<NotificationBatch>> list = subscribers.get(ispb);
        if (list != null) {
            list.remove(observer);
            log.info("Unregistered subscriber for ISPB: {} (remaining: {})", ispb, list.size());
        }
    }

    /**
     * Forwards a notification to all subscribers of the given ISPB.
     * Called by the Kafka consumer on every incoming message.
     *
     * @param ispb    the destination bank code (Kafka record key)
     * @param payload the raw notification payload (pacs.008 or pacs.002)
     */
    public void dispatch(String ispb, byte[] payload) {
        List<StreamObserver<NotificationBatch>> list = subscribers.get(ispb);
        if (list == null || list.isEmpty()) {
            log.debug("No subscribers for ISPB: {} — notification dropped", ispb);
            return;
        }

        BatchState state = batches.computeIfAbsent(ispb, ignored -> new BatchState());
        NotificationBatch batch = null;
        synchronized (state) {
            if (state.payloads.isEmpty()) {
                state.flushTask = scheduler.schedule(() -> flush(ispb, state), maxDelayMillis, TimeUnit.MILLISECONDS);
            }
            // Kafka's byte[] is not mutated after deserialization; avoid copying it on the hot path.
            state.payloads.add(UnsafeByteOperations.unsafeWrap(payload));
            if (state.payloads.size() >= maxBatchSize) {
                batch = drainLocked(state);
            }
        }

        if (batch != null) {
            sendBatch(ispb, batch);
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private void flush(String ispb, BatchState state) {
        NotificationBatch batch;
        synchronized (state) {
            batch = drainLocked(state);
        }
        if (batch != null) {
            sendBatch(ispb, batch);
        }
    }

    private NotificationBatch drainLocked(BatchState state) {
        if (state.payloads.isEmpty()) {
            state.flushTask = null;
            return null;
        }
        if (state.flushTask != null) {
            state.flushTask.cancel(false);
            state.flushTask = null;
        }
        NotificationBatch batch = NotificationBatch.newBuilder()
                .addAllPayloads(state.payloads)
                .build();
        state.payloads.clear();
        return batch;
    }

    private void sendBatch(String ispb, NotificationBatch batch) {
        List<StreamObserver<NotificationBatch>> list = subscribers.get(ispb);
        if (list == null || list.isEmpty()) {
            log.debug("No subscribers for ISPB: {} — notification batch dropped", ispb);
            return;
        }

        log.debug("Dispatching notification batch with {} payload(s) to {} subscriber(s) for ISPB: {}",
                batch.getPayloadsCount(), list.size(), ispb);

        for (StreamObserver<NotificationBatch> observer : list) {
            try {
                if (observer instanceof ServerCallStreamObserver<?> scs) {
                    log.debug("Pre-dispatch state for ISPB: {} — isCancelled: {}, isReady: {}", ispb, scs.isCancelled(), scs.isReady());
                }
                observer.onNext(batch);
                log.debug("onNext completed successfully for ISPB: {}", ispb);
            } catch (Exception e) {
                log.warn("Failed to send notification batch to subscriber for ISPB: {} — removing", ispb, e);
                list.remove(observer);
            }
        }
    }

    private static final class BatchState {
        private final List<com.google.protobuf.ByteString> payloads = new ArrayList<>();
        private ScheduledFuture<?> flushTask;
    }
}
