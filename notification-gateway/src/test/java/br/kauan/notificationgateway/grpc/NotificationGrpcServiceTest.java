package br.kauan.notificationgateway.grpc;

import br.kauan.notificationgateway.delivery.NotificationDelivery;
import br.kauan.notificationgateway.delivery.NotificationDeliveryReader;
import br.kauan.notificationgateway.grpc.proto.PullRequest;
import br.kauan.notificationgateway.grpc.proto.PullResponse;
import br.kauan.notificationgateway.grpc.security.AuthenticatedPspContext;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationGrpcServiceTest {

    private static final byte[] SECRET =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void returnsOnlyTheAuthenticatedPspPageAndIssuesCursorAtItsLastPosition() throws Exception {
        NotificationDeliveryReader reader = mock(NotificationDeliveryReader.class);
        DeliveryCursorCodec codec = new DeliveryCursorCodec(SECRET);
        NotificationGrpcService service = service(reader, codec, 1);
        when(reader.findAfter("20000001", 0, 15)).thenReturn(List.of(
                delivery(12, "v1:first", "first"),
                delivery(18, "v1:second", "second")
        ));
        CapturingObserver observer = new CapturingObserver();

        authenticatedCall(service, PullRequest.newBuilder().build(), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.completed).isTrue();
        assertThat(observer.response.getNotificationsList())
                .extracting(notification -> notification.getPayload().toStringUtf8())
                .containsExactly("first", "second");
        assertThat(codec.decodePosition(observer.response.getNextCursor(), "20000001"))
                .isEqualTo(18);
        verify(reader).findAfter("20000001", 0, 15);
    }

    @Test
    void emptyLongPollReturnsTheSameCursor() throws Exception {
        NotificationDeliveryReader reader = mock(NotificationDeliveryReader.class);
        DeliveryCursorCodec codec = new DeliveryCursorCodec(SECRET);
        NotificationGrpcService service = service(reader, codec, 1);
        String cursor = codec.encode("20000001", 20);
        when(reader.findAfter("20000001", 20, 15)).thenReturn(List.of());
        CapturingObserver observer = new CapturingObserver();

        authenticatedCall(service, PullRequest.newBuilder()
                .setCursor(cursor)
                .build(), observer);

        assertThat(observer.response.getNotificationsCount()).isZero();
        assertThat(observer.response.getNextCursor()).isEqualTo(cursor);
        verify(reader, org.mockito.Mockito.times(2)).findAfter("20000001", 20, 15);
    }

    @Test
    void rejectsInvalidCursor() throws Exception {
        NotificationDeliveryReader reader = mock(NotificationDeliveryReader.class);
        DeliveryCursorCodec codec = new DeliveryCursorCodec(SECRET);
        NotificationGrpcService service = service(reader, codec, 1);
        CapturingObserver invalidCursor = new CapturingObserver();

        authenticatedCall(service, PullRequest.newBuilder()
                .setCursor("tampered")
                .build(), invalidCursor);

        assertThat(Status.fromThrowable(invalidCursor.error).getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void missingAuthenticatedPspIsRejected() {
        NotificationGrpcService service = service(
                mock(NotificationDeliveryReader.class),
                new DeliveryCursorCodec(SECRET),
                1
        );

        assertThatThrownBy(() -> service.pullNotifications(
                PullRequest.newBuilder().build(),
                new CapturingObserver()
        )).isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("authenticated PSP ISPB is required");
    }

    @Test
    void releasesPullAdmissionBeforePublishingResponseToTheClient() throws Exception {
        NotificationDeliveryReader reader = mock(NotificationDeliveryReader.class);
        NotificationGrpcService service = service(reader, new DeliveryCursorCodec(SECRET), 1);
        when(reader.findAfter("20000001", 0, 15))
                .thenReturn(List.of(delivery(1, "v1:first", "first")));
        CapturingObserver nextPull = new CapturingObserver();
        StreamObserver<PullResponse> immediateRepull = new StreamObserver<>() {
            @Override
            public void onNext(PullResponse ignored) {
                service.pullNotifications(
                        PullRequest.newBuilder().build(),
                        nextPull
                );
            }

            @Override
            public void onError(Throwable throwable) {
                throw new AssertionError(throwable);
            }

            @Override
            public void onCompleted() {
            }
        };

        authenticatedCall(
                service,
                PullRequest.newBuilder().build(),
                immediateRepull
        );

        assertThat(nextPull.error).isNull();
        assertThat(nextPull.completed).isTrue();
    }

    private NotificationGrpcService service(
            NotificationDeliveryReader reader,
            DeliveryCursorCodec codec,
            long timeoutMillis
    ) {
        return new NotificationGrpcService(
                reader,
                new PullRequestCoordinator(),
                codec,
                timeoutMillis
        );
    }

    private void authenticatedCall(
            NotificationGrpcService service,
            PullRequest request,
            StreamObserver<PullResponse> observer
    ) throws Exception {
        Context.current()
                .withValue(AuthenticatedPspContext.AUTHENTICATED_ISPB, "20000001")
                .call(() -> {
                    service.pullNotifications(request, observer);
                    return null;
                });
    }

    private NotificationDelivery delivery(long position, String id, String payload) {
        return new NotificationDelivery(
                position,
                id,
                "20000001",
                payload.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static final class CapturingObserver implements StreamObserver<PullResponse> {
        private PullResponse response;
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(PullResponse value) {
            response = value;
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }
}
