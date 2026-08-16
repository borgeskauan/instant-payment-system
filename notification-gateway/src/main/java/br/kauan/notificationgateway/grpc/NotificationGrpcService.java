package br.kauan.notificationgateway.grpc;

import br.kauan.notificationgateway.delivery.Acknowledgement;
import br.kauan.notificationgateway.delivery.AcknowledgementBatcher;
import br.kauan.notificationgateway.grpc.security.AuthenticatedPspContext;
import br.kauan.notificationgateway.grpc.proto.ClientMessage;
import br.kauan.notificationgateway.grpc.proto.Notification;
import br.kauan.notificationgateway.grpc.proto.NotificationGatewayGrpc;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.grpc.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * gRPC server implementation — exposes a bidirectional stream to external consumers.
 *
 * <p>When a client calls {@code StreamNotifications}, this service:
 * <ol>
 *   <li>Registers the response stream by the ISPB authenticated from the client certificate.
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
    private final AcknowledgementBatcher acknowledgementBatcher;

    @Override
    public StreamObserver<ClientMessage> streamNotifications(StreamObserver<Notification> responseObserver) {
        String authenticatedIspb = AuthenticatedPspContext.requireAuthenticatedIspb();
        log.info("Client connected for notifications — ISPB: {}", authenticatedIspb);
        subscriberRegistry.register(authenticatedIspb, responseObserver);

        if (responseObserver instanceof ServerCallStreamObserver<Notification> serverObserver) {
            serverObserver.setOnCancelHandler(() -> {
                log.info("Client cancelled stream — ISPB: {} (isCancelled: {})",
                        authenticatedIspb, serverObserver.isCancelled());
                subscriberRegistry.unregister(authenticatedIspb, responseObserver);
            });
        }

        return new StreamObserver<>() {
            @Override
            public void onNext(ClientMessage message) {
                if (message.hasAck()) {
                    String deliveryId = message.getAck().getDeliveryId();
                    if (!deliveryId.isBlank()) {
                        try {
                            boolean enqueued = acknowledgementBatcher.enqueue(
                                    new Acknowledgement(deliveryId, authenticatedIspb)
                            );
                            if (!enqueued) {
                                fail(Status.UNAVAILABLE, "acknowledgement persistence is stopping");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            fail(Status.UNAVAILABLE, "acknowledgement enqueue interrupted");
                        }
                    }
                    return;
                }

                fail(Status.INVALID_ARGUMENT, "message must contain ack");
            }

            @Override
            public void onError(Throwable throwable) {
                unregister();
            }

            @Override
            public void onCompleted() {
                if (unregister()) {
                    responseObserver.onCompleted();
                }
            }

            private boolean unregister() {
                return subscriberRegistry.unregister(authenticatedIspb, responseObserver);
            }

            private void fail(Status status, String description) {
                if (unregister()) {
                    responseObserver.onError(status
                            .withDescription(description)
                            .asRuntimeException());
                }
            }
        };
    }
}
