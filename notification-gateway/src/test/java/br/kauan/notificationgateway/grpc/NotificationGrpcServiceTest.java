package br.kauan.notificationgateway.grpc;

import br.kauan.notificationgateway.delivery.Acknowledgement;
import br.kauan.notificationgateway.delivery.AcknowledgementBatcher;
import br.kauan.notificationgateway.delivery.NotificationDelivery;
import br.kauan.notificationgateway.grpc.proto.Ack;
import br.kauan.notificationgateway.grpc.proto.ClientMessage;
import br.kauan.notificationgateway.grpc.proto.Notification;
import br.kauan.notificationgateway.grpc.security.AuthenticatedPspContext;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationGrpcServiceTest {

    @Test
    void authenticatedIspbRegistersStreamDispatchesNotificationAndEnqueuesAck() throws Exception {
        SubscriberRegistry registry = new SubscriberRegistry();
        AcknowledgementBatcher batcher = mock(AcknowledgementBatcher.class);
        NotificationGrpcService service = new NotificationGrpcService(registry, batcher);
        CapturingObserver responseObserver = new CapturingObserver();
        when(batcher.enqueue(new Acknowledgement("v1:delivery", "20000001"))).thenReturn(true);

        StreamObserver<ClientMessage> requestObserver = authenticatedStream(service, responseObserver);

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
        assertThat(responseObserver.error).isNull();
        verify(batcher).enqueue(new Acknowledgement("v1:delivery", "20000001"));
    }

    @Test
    void blankDeliveryIdKeepsStreamOpenWithoutEnqueueing() throws Exception {
        SubscriberRegistry registry = new SubscriberRegistry();
        AcknowledgementBatcher batcher = mock(AcknowledgementBatcher.class);
        NotificationGrpcService service = new NotificationGrpcService(registry, batcher);
        CapturingObserver responseObserver = new CapturingObserver();

        StreamObserver<ClientMessage> requestObserver = authenticatedStream(service, responseObserver);
        requestObserver.onNext(ClientMessage.newBuilder()
                .setAck(Ack.newBuilder().setDeliveryId("   "))
                .build());

        boolean sent = registry.dispatch(new NotificationDelivery(
                "v1:delivery",
                "20000001",
                "payload".getBytes()
        ));

        assertThat(sent).isTrue();
        assertThat(responseObserver.error).isNull();
        assertThat(responseObserver.completed).isFalse();
        verifyNoInteractions(batcher);
    }

    @Test
    void batcherRejectingAckUnregistersStreamAndRespondsUnavailable() throws Exception {
        SubscriberRegistry registry = new SubscriberRegistry();
        AcknowledgementBatcher batcher = mock(AcknowledgementBatcher.class);
        NotificationGrpcService service = new NotificationGrpcService(registry, batcher);
        CapturingObserver responseObserver = new CapturingObserver();
        when(batcher.enqueue(new Acknowledgement("v1:delivery", "20000001"))).thenReturn(false);

        StreamObserver<ClientMessage> requestObserver = authenticatedStream(service, responseObserver);
        requestObserver.onNext(ack("v1:delivery"));

        assertThat(Status.fromThrowable(responseObserver.error).getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(registry.dispatch(new NotificationDelivery("v1:delivery", "20000001", "payload".getBytes())))
                .isFalse();
        verify(batcher).enqueue(new Acknowledgement("v1:delivery", "20000001"));
    }

    @Test
    void interruptedEnqueueRestoresInterruptUnregistersStreamAndRespondsUnavailable() throws Exception {
        SubscriberRegistry registry = new SubscriberRegistry();
        AcknowledgementBatcher batcher = mock(AcknowledgementBatcher.class);
        NotificationGrpcService service = new NotificationGrpcService(registry, batcher);
        CapturingObserver responseObserver = new CapturingObserver();
        doThrow(new InterruptedException("shutdown"))
                .when(batcher).enqueue(new Acknowledgement("v1:delivery", "20000001"));

        StreamObserver<ClientMessage> requestObserver = authenticatedStream(service, responseObserver);
        try {
            requestObserver.onNext(ack("v1:delivery"));

            assertThat(Status.fromThrowable(responseObserver.error).getCode()).isEqualTo(Status.Code.UNAVAILABLE);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(registry.dispatch(new NotificationDelivery("v1:delivery", "20000001", "payload".getBytes())))
                    .isFalse();
            verify(batcher).enqueue(new Acknowledgement("v1:delivery", "20000001"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void messageWithoutAckUnregistersStreamAndRespondsInvalidArgument() throws Exception {
        SubscriberRegistry registry = new SubscriberRegistry();
        AcknowledgementBatcher batcher = mock(AcknowledgementBatcher.class);
        NotificationGrpcService service = new NotificationGrpcService(registry, batcher);
        CapturingObserver responseObserver = new CapturingObserver();

        StreamObserver<ClientMessage> requestObserver = authenticatedStream(service, responseObserver);
        requestObserver.onNext(ClientMessage.getDefaultInstance());

        assertThat(Status.fromThrowable(responseObserver.error).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(registry.dispatch(new NotificationDelivery("v1:delivery", "20000001", "payload".getBytes())))
                .isFalse();
        verifyNoInteractions(batcher);
    }

    @Test
    void terminalErrorWaitsForAnInProgressNotificationAndOccursOnlyOnce() throws Exception {
        SubscriberRegistry registry = new SubscriberRegistry();
        AcknowledgementBatcher batcher = mock(AcknowledgementBatcher.class);
        NotificationGrpcService service = new NotificationGrpcService(registry, batcher);
        BlockingTerminalObserver responseObserver = new BlockingTerminalObserver();
        StreamObserver<ClientMessage> requestObserver = authenticatedStream(service, responseObserver);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var dispatch = executor.submit(() -> registry.dispatch(
                    new NotificationDelivery("v1:delivery", "20000001", "payload".getBytes())
            ));
            assertThat(responseObserver.onNextStarted.await(1, TimeUnit.SECONDS)).isTrue();

            var invalidMessage = executor.submit(() -> requestObserver.onNext(ClientMessage.getDefaultInstance()));
            assertThat(responseObserver.terminal.await(50, TimeUnit.MILLISECONDS)).isFalse();

            responseObserver.releaseOnNext.countDown();
            assertThat(dispatch.get(1, TimeUnit.SECONDS)).isTrue();
            invalidMessage.get(1, TimeUnit.SECONDS);
            requestObserver.onNext(ClientMessage.getDefaultInstance());

            assertThat(responseObserver.terminal.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(responseObserver.terminalCalls.get()).isEqualTo(1);
            assertThat(responseObserver.onNextAfterTerminal.get()).isZero();
        } finally {
            responseObserver.releaseOnNext.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void missingAuthenticatedIspbRejectsStream() {
        SubscriberRegistry registry = new SubscriberRegistry();
        AcknowledgementBatcher batcher = mock(AcknowledgementBatcher.class);
        NotificationGrpcService service = new NotificationGrpcService(registry, batcher);

        assertThatThrownBy(() -> service.streamNotifications(new CapturingObserver()))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("authenticated PSP ISPB is required");
    }

    private StreamObserver<ClientMessage> authenticatedStream(
            NotificationGrpcService service,
            StreamObserver<Notification> responseObserver
    ) throws Exception {
        return Context.current()
                .withValue(AuthenticatedPspContext.AUTHENTICATED_ISPB, "20000001")
                .call(() -> service.streamNotifications(responseObserver));
    }

    private ClientMessage ack(String deliveryId) {
        return ClientMessage.newBuilder()
                .setAck(Ack.newBuilder().setDeliveryId(deliveryId))
                .build();
    }

    private static final class CapturingObserver implements StreamObserver<Notification> {

        private Notification notification;
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(Notification value) {
            this.notification = value;
        }

        @Override
        public void onError(Throwable throwable) {
            this.error = throwable;
        }

        @Override
        public void onCompleted() {
            this.completed = true;
        }
    }

    private static final class BlockingTerminalObserver implements StreamObserver<Notification> {

        private final CountDownLatch onNextStarted = new CountDownLatch(1);
        private final CountDownLatch releaseOnNext = new CountDownLatch(1);
        private final CountDownLatch terminal = new CountDownLatch(1);
        private final AtomicInteger terminalCalls = new AtomicInteger();
        private final AtomicInteger onNextAfterTerminal = new AtomicInteger();

        @Override
        public void onNext(Notification value) {
            if (terminalCalls.get() > 0) {
                onNextAfterTerminal.incrementAndGet();
            }
            onNextStarted.countDown();
            try {
                releaseOnNext.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            terminalCalls.incrementAndGet();
            terminal.countDown();
        }

        @Override
        public void onCompleted() {
            terminalCalls.incrementAndGet();
            terminal.countDown();
        }
    }
}
