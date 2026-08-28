package br.kauan.paymentserviceprovider.adapter.input.grpc;

import br.kauan.notificationgateway.grpc.proto.Notification;
import br.kauan.notificationgateway.grpc.proto.NotificationGatewayGrpc;
import br.kauan.notificationgateway.grpc.proto.PullRequest;
import br.kauan.notificationgateway.grpc.proto.PullResponse;
import br.kauan.paymentserviceprovider.adapter.input.notification.NotificationProcessor;
import br.kauan.paymentserviceprovider.config.GlobalVariables;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NotificationGatewayGrpcClientTest {

    private io.grpc.Server server;
    private ManagedChannel channel;
    private ScheduledExecutorService executor;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
            server.awaitTermination(1, TimeUnit.SECONDS);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void processesTheWholeBatchBeforeAdvancingTheCursor() throws Exception {
        new GlobalVariables().setBankCode("12345678");
        CountDownLatch secondPull = new CountDownLatch(1);
        AtomicInteger pulls = new AtomicInteger();
        AtomicReference<String> secondCursor = new AtomicReference<>();
        NotificationProcessor processor = mock(NotificationProcessor.class);

        startServer(new NotificationGatewayGrpc.NotificationGatewayImplBase() {
            @Override
            public void pullNotifications(PullRequest request, StreamObserver<PullResponse> responseObserver) {
                if (pulls.incrementAndGet() == 1) {
                    responseObserver.onNext(PullResponse.newBuilder()
                            .addNotifications(notification("c1", "{\"CdtTrfTxInf\":[]}"))
                            .addNotifications(notification("c2", "{\"FIToFIPmtStsRpt\":[]}"))
                            .setNextCursor("cursor-2")
                            .build());
                    responseObserver.onCompleted();
                    return;
                }
                secondCursor.set(request.getCursor());
                secondPull.countDown();
            }
        });

        NotificationGatewayGrpcClient client = client(processor);
        client.start();

        assertThat(secondPull.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(secondCursor.get()).isEqualTo("cursor-2");
        verify(processor).process("12345678", "{\"CdtTrfTxInf\":[]}");
        verify(processor).process("12345678", "{\"FIToFIPmtStsRpt\":[]}");
    }

    @Test
    void retriesWithThePreviousCursorWhenBatchProcessingFails() throws Exception {
        new GlobalVariables().setBankCode("12345678");
        CountDownLatch retry = new CountDownLatch(1);
        AtomicInteger pulls = new AtomicInteger();
        AtomicReference<String> retryCursor = new AtomicReference<>();
        NotificationProcessor processor = mock(NotificationProcessor.class);
        doThrow(new IllegalStateException("boom")).when(processor)
                .process("12345678", "{\"CdtTrfTxInf\":[]}");

        startServer(new NotificationGatewayGrpc.NotificationGatewayImplBase() {
            @Override
            public void pullNotifications(PullRequest request, StreamObserver<PullResponse> responseObserver) {
                if (pulls.incrementAndGet() == 1) {
                    responseObserver.onNext(PullResponse.newBuilder()
                            .addNotifications(notification("c1", "{\"CdtTrfTxInf\":[]}"))
                            .setNextCursor("cursor-1")
                            .build());
                    responseObserver.onCompleted();
                    return;
                }
                retryCursor.set(request.getCursor());
                retry.countDown();
            }
        });

        NotificationGatewayGrpcClient client = client(processor);
        client.start();

        assertThat(retry.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(retryCursor.get()).isEmpty();
    }

    @Test
    void invalidTlsFilesFailWithoutPlaintextDowngrade() {
        NotificationGatewayProperties properties = new NotificationGatewayProperties(
                "unused",
                0,
                Duration.ofMillis(10),
                new NotificationGatewayProperties.Tls("/missing/client.crt", "/missing/client.key", "/missing/ca.crt")
        );

        assertThatThrownBy(() -> new NotificationGatewayGrpcClient(
                properties,
                new NotificationProcessor(null, null, null, null, null)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notification.gateway.tls.certificate-chain");
    }

    private NotificationGatewayGrpcClient client(NotificationProcessor processor) {
        return new NotificationGatewayGrpcClient(properties(), processor, channel, executor);
    }

    private Notification notification(String communicationId, String payload) {
        return Notification.newBuilder()
                .setCommunicationId(communicationId)
                .setPayload(ByteString.copyFromUtf8(payload))
                .build();
    }

    private void startServer(NotificationGatewayGrpc.NotificationGatewayImplBase service) throws IOException {
        String serverName = "notification-gateway-test-" + UUID.randomUUID();
        executor = Executors.newSingleThreadScheduledExecutor();
        server = InProcessServerBuilder.forName(serverName).directExecutor().addService(service).build().start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    }

    private static NotificationGatewayProperties properties() {
        return new NotificationGatewayProperties(
                "unused",
                0,
                Duration.ofMillis(10),
                new NotificationGatewayProperties.Tls("", "", "")
        );
    }
}
