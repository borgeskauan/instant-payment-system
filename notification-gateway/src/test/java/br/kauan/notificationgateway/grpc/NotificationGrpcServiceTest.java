package br.kauan.notificationgateway.grpc;

import br.kauan.notificationgateway.delivery.NotificationDelivery;
import br.kauan.notificationgateway.delivery.NotificationDeliveryRepository;
import br.kauan.notificationgateway.grpc.security.AuthenticatedPspContext;
import br.kauan.notificationgateway.grpc.proto.Ack;
import br.kauan.notificationgateway.grpc.proto.ClientMessage;
import br.kauan.notificationgateway.grpc.proto.Notification;
import io.grpc.Context;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NotificationGrpcServiceTest {

    @Test
    void authenticatedIspbRegistersStreamAndAckMarksDeliveryAcked() throws Exception {
        SubscriberRegistry registry = new SubscriberRegistry();
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        NotificationGrpcService service = new NotificationGrpcService(registry, repository);
        CapturingObserver responseObserver = new CapturingObserver();

        StreamObserver<ClientMessage> requestObserver = Context.current()
                .withValue(AuthenticatedPspContext.AUTHENTICATED_ISPB, "20000001")
                .call(() -> service.streamNotifications(responseObserver));

        boolean sent = registry.dispatch(new NotificationDelivery(
                "v1:delivery",
                "20000001",
                "payload".getBytes()
        ));
        requestObserver.onNext(ClientMessage.newBuilder()
                .setAck(Ack.newBuilder().setDeliveryId("v1:delivery"))
                .build());

        assertThat(sent).isTrue();
        assertThat(responseObserver.notification.getDeliveryId()).isEqualTo("v1:delivery");
        assertThat(responseObserver.notification.getPayload().toByteArray()).isEqualTo("payload".getBytes());
        verify(repository).acknowledge("v1:delivery", "20000001");
    }

    @Test
    void missingAuthenticatedIspbRejectsStream() {
        SubscriberRegistry registry = new SubscriberRegistry();
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        NotificationGrpcService service = new NotificationGrpcService(registry, repository);

        assertThatThrownBy(() -> service.streamNotifications(new CapturingObserver()))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("authenticated PSP ISPB is required");
    }

    private static final class CapturingObserver implements StreamObserver<Notification> {

        private Notification notification;

        @Override
        public void onNext(Notification value) {
            this.notification = value;
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }
    }
}
