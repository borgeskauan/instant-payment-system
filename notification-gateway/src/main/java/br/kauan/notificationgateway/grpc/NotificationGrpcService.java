package br.kauan.notificationgateway.grpc;

import br.kauan.notificationgateway.delivery.NotificationDeliveryRepository;
import br.kauan.notificationgateway.grpc.security.AuthenticatedPspContext;
import br.kauan.notificationgateway.grpc.proto.ClientMessage;
import br.kauan.notificationgateway.grpc.proto.Notification;
import br.kauan.notificationgateway.grpc.proto.NotificationGatewayGrpc;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
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
    private final NotificationDeliveryRepository deliveryRepository;

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
                        boolean acknowledged = deliveryRepository.acknowledge(deliveryId, authenticatedIspb);
                        if (acknowledged) {
                            log.debug("ACK received for delivery {} from ISPB {}", deliveryId, authenticatedIspb);
                        } else {
                            log.info("ACK ignored for delivery {} from ISPB {}", deliveryId, authenticatedIspb);
                        }
                    }
                    return;
                }

                failInvalid("message must contain ack");
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

            private void unregister() {
                subscriberRegistry.unregister(authenticatedIspb, responseObserver);
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
