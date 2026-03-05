package br.kauan.notificationgateway.grpc;

import br.kauan.notificationgateway.grpc.proto.Notification;
import br.kauan.notificationgateway.grpc.proto.NotificationGatewayGrpc;
import br.kauan.notificationgateway.grpc.proto.StreamRequest;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * gRPC server implementation — exposes server-side streaming to external consumers.
 *
 * <p>When a client calls {@code StreamNotifications}, this service:
 * <ol>
 *   <li>Registers the stream observer in {@link SubscriberRegistry} keyed by ISPB.
 *   <li>Keeps the stream open indefinitely (never calls {@code onCompleted}).
 *   <li>Cleans up the observer from the registry when the client cancels/disconnects.
 * </ol>
 *
 * <p>The Kafka consumer drives all outbound messages via
 * {@link SubscriberRegistry#dispatch(String, String)}.
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class NotificationGrpcService extends NotificationGatewayGrpc.NotificationGatewayImplBase {

    private final SubscriberRegistry subscriberRegistry;

    @Override
    public void streamNotifications(StreamRequest request, StreamObserver<Notification> responseObserver) {
        String ispb = request.getIspb();

        if (ispb == null || ispb.isBlank()) {
            responseObserver.onError(
                    io.grpc.Status.INVALID_ARGUMENT
                            .withDescription("ispb must not be blank")
                            .asRuntimeException()
            );
            return;
        }

        log.info("Client subscribed for notifications — ISPB: {}", ispb);

        // Cast to ServerCallStreamObserver to register a cancellation callback.
        // This ensures the registry is cleaned up even if the client disconnects
        // without sending an explicit cancel (e.g. process crash, network drop).
        if (responseObserver instanceof ServerCallStreamObserver<Notification> serverObserver) {
            serverObserver.setOnCancelHandler(() -> {
                log.info("Client cancelled stream — ISPB: {}", ispb);
                subscriberRegistry.unregister(ispb, responseObserver);
            });
        }

        subscriberRegistry.register(ispb, responseObserver);
        // Stream stays open — onCompleted is intentionally NOT called here.
        // The Kafka consumer pushes messages via SubscriberRegistry#dispatch.
    }
}
