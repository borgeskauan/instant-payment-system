package br.kauan.notificationgateway.grpc;

import br.kauan.notificationgateway.grpc.proto.Notification;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<StreamObserver<Notification>>> subscribers =
            new ConcurrentHashMap<>();

    /**
     * Registers a new gRPC stream observer for the given ISPB.
     * Called when a client opens a {@code StreamNotifications} stream.
     */
    public void register(String ispb, StreamObserver<Notification> observer) {
        subscribers
                .computeIfAbsent(ispb, k -> new CopyOnWriteArrayList<>())
                .add(observer);
        log.info("Registered new subscriber for ISPB: {} (total: {})", ispb, subscribers.get(ispb).size());
    }

    /**
     * Removes a gRPC stream observer for the given ISPB.
     * Called when the client disconnects or an error occurs.
     */
    public void unregister(String ispb, StreamObserver<Notification> observer) {
        List<StreamObserver<Notification>> list = subscribers.get(ispb);
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
     * @param payload the raw JSON payload (pacs.008 or pacs.002)
     */
    public void dispatch(String ispb, String payload) {
        List<StreamObserver<Notification>> list = subscribers.get(ispb);
        if (list == null || list.isEmpty()) {
            log.debug("No subscribers for ISPB: {} — notification dropped", ispb);
            return;
        }

        Notification notification = Notification.newBuilder()
                .setIspb(ispb)
                .setPayload(payload)
                .build();

        log.debug("Dispatching notification to {} subscriber(s) for ISPB: {}", list.size(), ispb);

        for (StreamObserver<Notification> observer : list) {
            try {
                observer.onNext(notification);
            } catch (Exception e) {
                log.warn("Failed to send notification to subscriber for ISPB: {} — removing", ispb, e);
                list.remove(observer);
            }
        }
    }
}
