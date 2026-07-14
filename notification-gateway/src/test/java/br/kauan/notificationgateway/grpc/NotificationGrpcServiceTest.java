package br.kauan.notificationgateway.grpc;

import br.kauan.notificationgateway.delivery.NotificationDelivery;
import br.kauan.notificationgateway.delivery.NotificationDeliveryRepository;
import br.kauan.notificationgateway.grpc.proto.Ack;
import br.kauan.notificationgateway.grpc.proto.ClientMessage;
import br.kauan.notificationgateway.grpc.proto.Notification;
import br.kauan.notificationgateway.grpc.proto.Subscribe;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NotificationGrpcServiceTest {

    @Test
    void subscribeRegistersStreamAndAckMarksDeliveryAcked() {
        SubscriberRegistry registry = new SubscriberRegistry();
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        NotificationGrpcService service = new NotificationGrpcService(registry, repository);
        CapturingObserver responseObserver = new CapturingObserver();

        StreamObserver<ClientMessage> requestObserver = service.streamNotifications(responseObserver);
        requestObserver.onNext(ClientMessage.newBuilder()
                .setSubscribe(Subscribe.newBuilder().setIspb("20000001"))
                .build());

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
        verify(repository).acknowledge("v1:delivery");
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
