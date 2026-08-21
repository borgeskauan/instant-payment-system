package br.kauan.notificationgateway.grpc;

import br.kauan.notificationgateway.delivery.NotificationDelivery;
import br.kauan.notificationgateway.delivery.NotificationDeliveryRepository;
import br.kauan.notificationgateway.grpc.proto.Notification;
import br.kauan.notificationgateway.grpc.proto.NotificationGatewayGrpc;
import br.kauan.notificationgateway.grpc.proto.PullRequest;
import br.kauan.notificationgateway.grpc.proto.PullResponse;
import br.kauan.notificationgateway.grpc.security.AuthenticatedPspContext;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.util.List;

@GrpcService
public class NotificationGrpcService extends NotificationGatewayGrpc.NotificationGatewayImplBase {

    static final int PULL_BATCH_LIMIT = 10;

    private final NotificationDeliveryRepository repository;
    private final PullRequestCoordinator coordinator;
    private final DeliveryCursorCodec cursorCodec;
    private final Duration longPollTimeout;

    public NotificationGrpcService(
            NotificationDeliveryRepository repository,
            PullRequestCoordinator coordinator,
            DeliveryCursorCodec cursorCodec,
            @Value("${notification-gateway.pull.long-poll-timeout-ms:30000}") long longPollTimeoutMillis
    ) {
        this.repository = repository;
        this.coordinator = coordinator;
        this.cursorCodec = cursorCodec;
        this.longPollTimeout = Duration.ofMillis(longPollTimeoutMillis);
    }

    @Override
    public void pullNotifications(PullRequest request, StreamObserver<PullResponse> responseObserver) {
        String recipientIspb = AuthenticatedPspContext.requireAuthenticatedIspb();
        long position;
        try {
            position = cursorCodec.decodePosition(request.getCursor(), recipientIspb);
        } catch (InvalidDeliveryCursorException invalidCursor) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(invalidCursor.getMessage())
                    .asRuntimeException());
            return;
        }

        try (PullRequestCoordinator.Session session = coordinator.begin(recipientIspb)) {
            if (responseObserver instanceof ServerCallStreamObserver<PullResponse> serverObserver) {
                serverObserver.setOnCancelHandler(session::signal);
            }

            List<NotificationDelivery> deliveries = repository.findAfter(recipientIspb, position, PULL_BATCH_LIMIT);
            if (deliveries.isEmpty() && !isCancelled(responseObserver)) {
                session.await(longPollTimeout);
                if (!isCancelled(responseObserver)) {
                    deliveries = repository.findAfter(recipientIspb, position, PULL_BATCH_LIMIT);
                }
            }
            if (isCancelled(responseObserver)) {
                return;
            }

            PullResponse.Builder response = PullResponse.newBuilder();
            for (NotificationDelivery delivery : deliveries) {
                response.addNotifications(Notification.newBuilder()
                        .setPayload(ByteString.copyFrom(delivery.payload())));
            }
            if (deliveries.isEmpty()) {
                response.setNextCursor(request.getCursor());
            } else {
                response.setNextCursor(cursorCodec.encode(
                        recipientIspb,
                        deliveries.getLast().deliveryPosition()
                ));
            }
            // Admission belongs to the request, not to delivery of the response.
            // Release it before the client can observe the response and repull.
            session.close();
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (PullRequestCoordinator.ConcurrentPullException concurrentPull) {
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription("only one pull may be active per PSP")
                    .asRuntimeException());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription("notification pull interrupted")
                    .asRuntimeException());
        }
    }

    private boolean isCancelled(StreamObserver<PullResponse> observer) {
        return observer instanceof ServerCallStreamObserver<PullResponse> serverObserver
                && serverObserver.isCancelled();
    }
}
