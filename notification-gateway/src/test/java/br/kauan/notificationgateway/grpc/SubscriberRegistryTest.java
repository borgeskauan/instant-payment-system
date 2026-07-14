package br.kauan.notificationgateway.grpc;

import br.kauan.notificationgateway.delivery.NotificationDelivery;
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
        boolean sent = registry.dispatch(delivery("delivery-1", "20000001", payload));

        assertThat(sent).isTrue();
        assertThat(observer.notifications).hasSize(1);
        assertThat(observer.notifications.getFirst().getDeliveryId()).isEqualTo("delivery-1");
        assertThat(observer.notifications.getFirst().getPayload().toByteArray()).isEqualTo(payload);
    }

    @Test
    void dispatchSendsOneNotificationPerPayload() {
        var registry = new SubscriberRegistry();
        var observer = new CapturingObserver();

        registry.register("20000001", observer);
        for (int i = 0; i < 3; i++) {
            registry.dispatch(delivery("delivery-" + i, "20000001", new byte[]{(byte) i}));
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
        registry.dispatch(delivery("delivery-1", "20000001", payload));

        payload[0] = 'P';

        assertThat(observer.notifications).hasSize(1);
        assertThat(observer.notifications.getFirst().getPayload().byteAt(0)).isEqualTo((byte) 'P');
    }

    @Test
    void multipleSubscribersForSameIspbReceiveOnlyOneDeliveryAttempt() {
        var registry = new SubscriberRegistry();
        var first = new CapturingObserver();
        var second = new CapturingObserver();

        registry.register("20000001", first);
        registry.register("20000001", second);
        registry.dispatch(delivery("delivery-1", "20000001", "one".getBytes()));

        assertThat(first.notifications).hasSize(1);
        assertThat(second.notifications).isEmpty();
    }

    @Test
    void dispatchWithoutSubscribersReturnsFalse() {
        var registry = new SubscriberRegistry();
        var observer = new CapturingObserver();

        boolean sent = registry.dispatch(delivery("delivery-1", "20000001", "dropped".getBytes()));
        registry.register("20000001", observer);

        assertThat(sent).isFalse();
        assertThat(observer.notifications).isEmpty();
    }

    @Test
    void connectedIspbsIncludesOnlyCurrentlyRegisteredIspbs() {
        var registry = new SubscriberRegistry();
        var observer = new CapturingObserver();

        registry.register("20000001", observer);

        assertThat(registry.connectedIspbs()).containsExactly("20000001");

        registry.unregister("20000001", observer);

        assertThat(registry.connectedIspbs()).isEmpty();
    }

    private static NotificationDelivery delivery(String communicationId, String ispb, byte[] payload) {
        return new NotificationDelivery(communicationId, ispb, payload);
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
