package br.kauan.notificationgateway.grpc;

import br.kauan.notificationgateway.delivery.NotificationDelivery;
import br.kauan.notificationgateway.grpc.proto.Notification;
import com.google.protobuf.UnsafeByteOperations;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

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

    public Set<String> connectedIspbs() {
        return subscribers.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(java.util.Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean dispatch(NotificationDelivery delivery) {
        String ispb = delivery.recipientIspb();
        StreamObserver<Notification> observer = firstSubscriber(ispb);
        if (observer == null) {
            log.debug("No subscribers for ISPB: {} — delivery remains pending", ispb);
            return false;
        }

        Notification notification = Notification.newBuilder()
                .setDeliveryId(delivery.communicationId())
                .setPayload(UnsafeByteOperations.unsafeWrap(delivery.payload()))
                .build();

        try {
            synchronized (observer) {
                observer.onNext(notification);
            }
            log.debug("Dispatched delivery {} to ISPB {}", delivery.communicationId(), ispb);
            return true;
        } catch (Exception e) {
            log.warn("Failed to send notification to subscriber for ISPB: {} — removing", ispb, e);
            unregister(ispb, observer);
            return false;
        }
    }

    private StreamObserver<Notification> firstSubscriber(String ispb) {
        List<StreamObserver<Notification>> list = subscribers.get(ispb);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.getFirst();
    }
}
