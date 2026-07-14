package br.kauan.notificationgateway.grpc;

import br.kauan.notificationgateway.delivery.NotificationDeliveryRepository;
import br.kauan.notificationgateway.grpc.proto.ClientMessage;
import br.kauan.notificationgateway.grpc.proto.Notification;
import br.kauan.notificationgateway.grpc.proto.NotificationGatewayGrpc;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.concurrent.atomic.AtomicReference;

/**
 * gRPC server implementation — exposes a bidirectional stream to external consumers.
 *
 * <p>When a client calls {@code StreamNotifications}, this service:
 * <ol>
 *   <li>Waits for the first {@code Subscribe} message and registers the response stream by ISPB.
 *   <li>Keeps the stream open indefinitely (never calls {@code onCompleted}).
 *   <li>Marks deliveries ACKED when the client sends {@code Ack}.
 *   <li>Cleans up the observer from the registry when the client cancels/disconnects.
 * </ol>
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class NotificationGrpcService extends NotificationGatewayGrpc.NotificationGatewayImplBase {

    private final SubscriberRegistry subscriberRegistry;
    private final NotificationDeliveryRepository deliveryRepository;

    @Override
    public StreamObserver<ClientMessage> streamNotifications(StreamObserver<Notification> responseObserver) {
        AtomicReference<String> subscribedIspb = new AtomicReference<>();

        if (responseObserver instanceof ServerCallStreamObserver<Notification> serverObserver) {
            serverObserver.setOnCancelHandler(() -> {
                String ispb = subscribedIspb.get();
                if (ispb != null) {
                    log.info("Client cancelled stream — ISPB: {} (isCancelled: {})", ispb, serverObserver.isCancelled());
                    subscriberRegistry.unregister(ispb, responseObserver);
                }
            });
        }

        return new StreamObserver<>() {
            @Override
            public void onNext(ClientMessage message) {
                if (message.hasSubscribe()) {
                    subscribe(message.getSubscribe().getIspb());
                    return;
                }

                if (message.hasAck()) {
                    String deliveryId = message.getAck().getDeliveryId();
                    if (!deliveryId.isBlank()) {
                        deliveryRepository.acknowledge(deliveryId);
                        log.debug("ACK received for delivery {}", deliveryId);
                    }
                    return;
                }

                failInvalid("message must contain subscribe or ack");
            }

            @Override
            public void onError(Throwable throwable) {
                unregister();
            }

            @Override
            public void onCompleted() {
                unregister();
                responseObserver.onCompleted();
            }

            private void subscribe(String ispb) {
                if (ispb == null || ispb.isBlank()) {
                    failInvalid("ispb must not be blank");
                    return;
                }
                if (!subscribedIspb.compareAndSet(null, ispb)) {
                    failInvalid("subscribe must be sent only once");
                    return;
                }

                log.info("Client subscribed for notifications — ISPB: {}", ispb);
                subscriberRegistry.register(ispb, responseObserver);
            }

            private void unregister() {
                String ispb = subscribedIspb.get();
                if (ispb != null) {
                    subscriberRegistry.unregister(ispb, responseObserver);
                }
            }

            private void failInvalid(String description) {
                unregister();
                responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                        .withDescription(description)
                        .asRuntimeException());
            }
        };
    }
}
