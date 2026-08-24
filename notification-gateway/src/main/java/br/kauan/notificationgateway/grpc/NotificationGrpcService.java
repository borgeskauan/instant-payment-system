package br.kauan.notificationgateway.grpc;

import br.kauan.notificationgateway.delivery.NotificationDeliveryReader;
import br.kauan.notificationgateway.grpc.proto.Notification;
import br.kauan.notificationgateway.grpc.proto.NotificationGatewayGrpc;
import br.kauan.notificationgateway.grpc.proto.PullRequest;
import br.kauan.notificationgateway.grpc.proto.PullResponse;
import br.kauan.notificationgateway.grpc.security.AuthenticatedPspContext;
import br.kauan.notificationgateway.kafka.InvalidNotificationOffsetException;
import br.kauan.notificationgateway.kafka.KafkaNotificationPage;
import br.kauan.notificationgateway.kafka.KafkaNotificationRecord;
import br.kauan.notificationgateway.kafka.NotificationCursorExpiredException;
import br.kauan.notificationgateway.kafka.NotificationPartitionResolver;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;

@GrpcService
public class NotificationGrpcService extends NotificationGatewayGrpc.NotificationGatewayImplBase {

    static final int PULL_BATCH_LIMIT = 15;

    private final NotificationDeliveryReader reader;
    private final PullRequestCoordinator coordinator;
    private final DeliveryCursorCodec cursorCodec;
    private final NotificationPartitionResolver partitionResolver;
    private final Duration longPollTimeout;

    public NotificationGrpcService(
            NotificationDeliveryReader reader,
            PullRequestCoordinator coordinator,
            DeliveryCursorCodec cursorCodec,
            NotificationPartitionResolver partitionResolver,
            @Value("${notification-gateway.pull.long-poll-timeout-ms:30000}") long longPollTimeoutMillis
    ) {
        this.reader = reader;
        this.coordinator = coordinator;
        this.cursorCodec = cursorCodec;
        this.partitionResolver = partitionResolver;
        this.longPollTimeout = Duration.ofMillis(longPollTimeoutMillis);
    }

    @Override
    public void pullNotifications(PullRequest request, StreamObserver<PullResponse> responseObserver) {
        String recipientIspb = AuthenticatedPspContext.requireAuthenticatedIspb();
        int partition = partitionResolver.partition(recipientIspb);
        DeliveryCursor cursor;
        try {
            cursor = cursorCodec.decode(request.getCursor(), recipientIspb, partition);
        } catch (InvalidDeliveryCursorException invalidCursor) {
            fail(responseObserver, Status.INVALID_ARGUMENT, invalidCursor.getMessage());
            return;
        }

        try (PullRequestCoordinator.Session session = coordinator.begin(recipientIspb)) {
            if (responseObserver instanceof ServerCallStreamObserver<PullResponse> serverObserver) {
                serverObserver.setOnCancelHandler(session::signal);
            }

            KafkaNotificationPage page = reader.read(
                    recipientIspb,
                    partition,
                    cursor.lastExaminedOffset(),
                    PULL_BATCH_LIMIT
            );
            if (shouldLongPoll(page, cursor) && !isCancelled(responseObserver)) {
                session.await(longPollTimeout);
                if (!isCancelled(responseObserver)) {
                    page = reader.read(
                            recipientIspb,
                            partition,
                            cursor.lastExaminedOffset(),
                            PULL_BATCH_LIMIT
                    );
                }
            }
            if (isCancelled(responseObserver)) {
                return;
            }

            PullResponse.Builder response = PullResponse.newBuilder();
            for (KafkaNotificationRecord notification : page.notifications()) {
                response.addNotifications(Notification.newBuilder()
                        .setPayload(ByteString.copyFrom(notification.payload()))
                        .setCommunicationId(notification.communicationId()));
            }
            if (page.lastExaminedOffset() > cursor.lastExaminedOffset()) {
                response.setNextCursor(cursorCodec.encode(new DeliveryCursor(
                        recipientIspb,
                        DeliveryCursorCodec.TOPIC_GENERATION,
                        partition,
                        page.lastExaminedOffset()
                )));
            } else {
                response.setNextCursor(request.getCursor());
            }

            session.close();
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (NotificationCursorExpiredException expiredCursor) {
            fail(responseObserver, Status.FAILED_PRECONDITION, expiredCursor.getMessage());
        } catch (InvalidNotificationOffsetException invalidOffset) {
            fail(responseObserver, Status.INVALID_ARGUMENT, invalidOffset.getMessage());
        } catch (PullRequestCoordinator.ConcurrentPullException concurrentPull) {
            fail(responseObserver, Status.FAILED_PRECONDITION, "only one pull may be active per PSP");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(responseObserver, Status.UNAVAILABLE, "notification pull interrupted");
        }
    }

    private boolean shouldLongPoll(KafkaNotificationPage page, DeliveryCursor cursor) {
        return page.notifications().isEmpty()
                && page.atTail()
                && page.lastExaminedOffset() == cursor.lastExaminedOffset();
    }

    private void fail(StreamObserver<PullResponse> observer, Status status, String description) {
        observer.onError(status.withDescription(description).asRuntimeException());
    }

    private boolean isCancelled(StreamObserver<PullResponse> observer) {
        return observer instanceof ServerCallStreamObserver<PullResponse> serverObserver
                && serverObserver.isCancelled();
    }
}
