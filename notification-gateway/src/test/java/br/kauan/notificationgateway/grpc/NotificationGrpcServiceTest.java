package br.kauan.notificationgateway.grpc;

import br.kauan.notificationgateway.delivery.NotificationDeliveryReader;
import br.kauan.notificationgateway.grpc.proto.PullRequest;
import br.kauan.notificationgateway.grpc.proto.PullResponse;
import br.kauan.notificationgateway.grpc.security.AuthenticatedPspContext;
import br.kauan.notificationgateway.kafka.KafkaNotificationPage;
import br.kauan.notificationgateway.kafka.KafkaNotificationRecord;
import br.kauan.notificationgateway.kafka.NotificationCursorExpiredException;
import br.kauan.notificationgateway.kafka.NotificationPartitionResolver;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationGrpcServiceTest {

    private static final byte[] SECRET =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void returnsTheAuthenticatedPspPayloadsAndIssuesItsLastExaminedKafkaOffset() throws Exception {
        NotificationDeliveryReader reader = mock(NotificationDeliveryReader.class);
        DeliveryCursorCodec codec = new DeliveryCursorCodec(SECRET);
        NotificationGrpcService service = service(reader, codec, 1);
        int partition = new NotificationPartitionResolver(8).partition("20000001");
        when(reader.read("20000001", partition, -1, 15)).thenReturn(new KafkaNotificationPage(
                List.of(record(partition, 12, "first"), record(partition, 18, "second")),
                18,
                true
        ));
        CapturingObserver observer = new CapturingObserver();

        authenticatedCall(service, PullRequest.newBuilder().build(), observer);

        assertThat(observer.response.getNotificationsList())
                .extracting(notification -> notification.getPayload().toStringUtf8())
                .containsExactly("first", "second");
        assertThat(observer.response.getNotificationsList())
                .extracting(notification -> notification.getCommunicationId())
                .containsExactly("first", "second");
        assertThat(codec.decode(observer.response.getNextCursor(), "20000001", partition).lastExaminedOffset())
                .isEqualTo(18);
    }

    @Test
    void emptyPageThatExaminedUnrelatedRecordsAdvancesImmediatelyWithoutLongPolling() throws Exception {
        NotificationDeliveryReader reader = mock(NotificationDeliveryReader.class);
        DeliveryCursorCodec codec = new DeliveryCursorCodec(SECRET);
        NotificationGrpcService service = service(reader, codec, 1_000);
        int partition = new NotificationPartitionResolver(8).partition("20000001");
        when(reader.read("20000001", partition, -1, 15))
                .thenReturn(new KafkaNotificationPage(List.of(), 25, true));
        CapturingObserver observer = new CapturingObserver();

        authenticatedCall(service, PullRequest.newBuilder().build(), observer);

        assertThat(codec.decode(observer.response.getNextCursor(), "20000001", partition).lastExaminedOffset())
                .isEqualTo(25);
        verify(reader).read("20000001", partition, -1, 15);
    }

    @Test
    void knownTailLongPollsAndReturnsTheSameCursorWhenNoRecordArrives() throws Exception {
        NotificationDeliveryReader reader = mock(NotificationDeliveryReader.class);
        DeliveryCursorCodec codec = new DeliveryCursorCodec(SECRET);
        NotificationGrpcService service = service(reader, codec, 1);
        int partition = new NotificationPartitionResolver(8).partition("20000001");
        String cursor = codec.encode(new DeliveryCursor("20000001", DeliveryCursorCodec.TOPIC_GENERATION, partition, 20));
        when(reader.read("20000001", partition, 20, 15))
                .thenReturn(new KafkaNotificationPage(List.of(), 20, true));
        CapturingObserver observer = new CapturingObserver();

        authenticatedCall(service, PullRequest.newBuilder().setCursor(cursor).build(), observer);

        assertThat(observer.response.getNextCursor()).isEqualTo(cursor);
        verify(reader, times(2)).read("20000001", partition, 20, 15);
    }

    @Test
    void mapsExpiredCursorToFailedPrecondition() throws Exception {
        NotificationDeliveryReader reader = mock(NotificationDeliveryReader.class);
        DeliveryCursorCodec codec = new DeliveryCursorCodec(SECRET);
        NotificationGrpcService service = service(reader, codec, 1);
        int partition = new NotificationPartitionResolver(8).partition("20000001");
        String cursor = codec.encode(new DeliveryCursor("20000001", DeliveryCursorCodec.TOPIC_GENERATION, partition, 20));
        when(reader.read("20000001", partition, 20, 15)).thenThrow(new NotificationCursorExpiredException());
        CapturingObserver observer = new CapturingObserver();

        authenticatedCall(service, PullRequest.newBuilder().setCursor(cursor).build(), observer);

        assertThat(Status.fromThrowable(observer.error).getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(Status.fromThrowable(observer.error).getDescription()).isEqualTo("notification cursor expired");
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
                new NotificationPartitionResolver(8),
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

    private KafkaNotificationRecord record(int partition, long offset, String payload) {
        return new KafkaNotificationRecord(
                partition,
                offset,
                "20000001",
                payload,
                payload.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static final class CapturingObserver implements StreamObserver<PullResponse> {
        private PullResponse response;
        private Throwable error;

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
        }
    }
}
