package br.kauan.notificationgateway.grpc;

import br.kauan.notificationgateway.grpc.proto.Notification;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriberRegistryTest {

    @Test
    void dispatchSendsOneNotificationImmediately() {
        var registry = new SubscriberRegistry();
        var observer = new CapturingObserver();
        byte[] payload = "payload".getBytes();

        registry.register("20000001", observer);
        registry.dispatch("20000001", payload);

        assertThat(observer.notifications).hasSize(1);
        assertThat(observer.notifications.getFirst().getDeliveryId()).isEmpty();
        assertThat(observer.notifications.getFirst().getPayload().toByteArray()).isEqualTo(payload);
    }

    @Test
    void dispatchSendsOneNotificationPerPayload() {
        var registry = new SubscriberRegistry();
        var observer = new CapturingObserver();

        registry.register("20000001", observer);
        for (int i = 0; i < 3; i++) {
            registry.dispatch("20000001", new byte[]{(byte) i});
        }

        assertThat(observer.notifications).hasSize(3);
        assertThat(observer.notifications.get(0).getPayload().toByteArray()).isEqualTo(new byte[]{0});
        assertThat(observer.notifications.get(1).getPayload().toByteArray()).isEqualTo(new byte[]{1});
        assertThat(observer.notifications.get(2).getPayload().toByteArray()).isEqualTo(new byte[]{2});
    }

    @Test
    void emittedNotificationContainsOriginalPayloadBytesWithoutCopying() {
        var registry = new SubscriberRegistry();
        var observer = new CapturingObserver();
        byte[] payload = "payload".getBytes();

        registry.register("20000001", observer);
        registry.dispatch("20000001", payload);

        payload[0] = 'P';

        assertThat(observer.notifications).hasSize(1);
        assertThat(observer.notifications.getFirst().getPayload().byteAt(0)).isEqualTo((byte) 'P');
    }

    @Test
    void multipleSubscribersForSameIspbReceiveSameNotification() {
        var registry = new SubscriberRegistry();
        var first = new CapturingObserver();
        var second = new CapturingObserver();

        registry.register("20000001", first);
        registry.register("20000001", second);
        registry.dispatch("20000001", "one".getBytes());

        assertThat(first.notifications).hasSize(1);
        assertThat(second.notifications).hasSize(1);
        assertThat(first.notifications.getFirst()).isSameAs(second.notifications.getFirst());
    }

    @Test
    void dispatchWithoutSubscribersDropsPayload() {
        var registry = new SubscriberRegistry();
        var observer = new CapturingObserver();

        registry.dispatch("20000001", "dropped".getBytes());
        registry.register("20000001", observer);

        assertThat(observer.notifications).isEmpty();
    }

    private static final class CapturingObserver implements StreamObserver<Notification> {

        private final java.util.List<Notification> notifications = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void onNext(Notification value) {
            this.notifications.add(value);
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }
    }
}
