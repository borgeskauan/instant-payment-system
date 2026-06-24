package br.kauan.notificationgateway.grpc;

import br.kauan.notificationgateway.grpc.proto.NotificationBatch;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriberRegistryTest {

    @Test
    void dispatchingFewerThanMaxBatchSizeWaitsForTimedFlush() throws Exception {
        var registry = new SubscriberRegistry(64, 1_000);
        var observer = new CapturingObserver();
        byte[] payload = "payload".getBytes();

        registry.register("20000001", observer);
        registry.dispatch("20000001", payload);

        assertThat(observer.batches).isEmpty();

        Thread.sleep(1_200);

        assertThat(observer.batches).hasSize(1);
        assertThat(observer.batches.getFirst().getPayloadsCount()).isEqualTo(1);
        registry.shutdown();
    }

    @Test
    void dispatchingMaxBatchSizeFlushesOneBatchImmediately() {
        var registry = new SubscriberRegistry(64, 5_000);
        var observer = new CapturingObserver();

        registry.register("20000001", observer);
        for (int i = 0; i < 64; i++) {
            registry.dispatch("20000001", new byte[]{(byte) i});
        }

        assertThat(observer.batches).hasSize(1);
        assertThat(observer.batches.getFirst().getPayloadsCount()).isEqualTo(64);
        registry.shutdown();
    }

    @Test
    void emittedBatchContainsOriginalPayloadBytesWithoutCopying() {
        var registry = new SubscriberRegistry(2, 5_000);
        var observer = new CapturingObserver();
        byte[] first = "first".getBytes();
        byte[] second = "second".getBytes();

        registry.register("20000001", observer);
        registry.dispatch("20000001", first);
        registry.dispatch("20000001", second);

        first[0] = 'F';

        assertThat(observer.batches).hasSize(1);
        assertThat(observer.batches.getFirst().getPayloads(0).byteAt(0)).isEqualTo((byte) 'F');
        assertThat(observer.batches.getFirst().getPayloads(1).toByteArray()).isEqualTo(second);
        registry.shutdown();
    }

    @Test
    void multipleSubscribersForSameIspbReceiveSameBatch() {
        var registry = new SubscriberRegistry(2, 5_000);
        var first = new CapturingObserver();
        var second = new CapturingObserver();

        registry.register("20000001", first);
        registry.register("20000001", second);
        registry.dispatch("20000001", "one".getBytes());
        registry.dispatch("20000001", "two".getBytes());

        assertThat(first.batches).hasSize(1);
        assertThat(second.batches).hasSize(1);
        assertThat(first.batches.getFirst()).isSameAs(second.batches.getFirst());
        registry.shutdown();
    }

    @Test
    void dispatchWithoutSubscribersDropsPayload() throws Exception {
        var registry = new SubscriberRegistry(2, 25);
        var observer = new CapturingObserver();

        registry.dispatch("20000001", "dropped".getBytes());
        registry.register("20000001", observer);
        Thread.sleep(100);

        assertThat(observer.batches).isEmpty();
        registry.shutdown();
    }

    private static final class CapturingObserver implements StreamObserver<NotificationBatch> {

        private final java.util.List<NotificationBatch> batches = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void onNext(NotificationBatch value) {
            this.batches.add(value);
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }
    }
}
