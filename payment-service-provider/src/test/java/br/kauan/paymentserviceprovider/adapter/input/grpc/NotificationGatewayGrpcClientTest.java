package br.kauan.paymentserviceprovider.adapter.input.grpc;

import br.kauan.notificationgateway.grpc.proto.ClientMessage;
import br.kauan.notificationgateway.grpc.proto.Notification;
import br.kauan.notificationgateway.grpc.proto.NotificationGatewayGrpc;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
    void opensStreamAndForwardsReceivedNotifications() throws Exception {
        new GlobalVariables().setBankCode("12345678");
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch processed = new CountDownLatch(2);
        CountDownLatch acked = new CountDownLatch(2);
        NotificationProcessor processor = mock(NotificationProcessor.class);
        doAnswer(invocation -> {
            processed.countDown();
            return null;
        }).when(processor).process(eq("12345678"), eq("{\"CdtTrfTxInf\":[]}"));
        doAnswer(invocation -> {
            processed.countDown();
            return null;
        }).when(processor).process(eq("12345678"), eq("{\"FIToFIPmtStsRpt\":[]}"));

        startServer(new NotificationGatewayGrpc.NotificationGatewayImplBase() {
            @Override
            public StreamObserver<ClientMessage> streamNotifications(StreamObserver<Notification> responseObserver) {
                CompletableFuture.runAsync(() -> {
                    connected.countDown();
                    responseObserver.onNext(Notification.newBuilder()
                            .setDeliveryId("delivery-1")
                            .setPayload(ByteString.copyFromUtf8("{\"CdtTrfTxInf\":[]}"))
                            .build());
                    responseObserver.onNext(Notification.newBuilder()
                            .setDeliveryId("delivery-2")
                            .setPayload(ByteString.copyFromUtf8("{\"FIToFIPmtStsRpt\":[]}"))
                            .build());
                });
                return new StreamObserver<>() {
                    @Override
                    public void onNext(ClientMessage value) {
                        if (value.hasAck()) {
                            acked.countDown();
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                };
            }
        });

        NotificationGatewayGrpcClient client = new NotificationGatewayGrpcClient(
                properties(),
                processor,
                channel,
                executor
        );

        client.start();

        assertThat(connected.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(processed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(acked.await(1, TimeUnit.SECONDS)).isTrue();
        verify(processor).process("12345678", "{\"CdtTrfTxInf\":[]}");
        verify(processor).process("12345678", "{\"FIToFIPmtStsRpt\":[]}");
    }

    @Test
    void reconnectsWhenStreamFails() throws Exception {
        new GlobalVariables().setBankCode("12345678");
        CountDownLatch secondSubscription = new CountDownLatch(1);
        AtomicInteger subscriptions = new AtomicInteger();

        startServer(new NotificationGatewayGrpc.NotificationGatewayImplBase() {
            @Override
            public StreamObserver<ClientMessage> streamNotifications(StreamObserver<Notification> responseObserver) {
                int current = subscriptions.incrementAndGet();
                if (current == 2) {
                    secondSubscription.countDown();
                }
                responseObserver.onError(new RuntimeException("boom"));
                return new StreamObserver<>() {
                    @Override
                    public void onNext(ClientMessage value) {
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                };
            }
        });

        NotificationGatewayGrpcClient client = new NotificationGatewayGrpcClient(
                properties(),
                mock(NotificationProcessor.class),
                channel,
                executor
        );

        client.start();

        assertThat(secondSubscription.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(subscriptions.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void invalidTlsFilesFailWithoutPlaintextDowngrade() {
        NotificationGatewayProperties properties = new NotificationGatewayProperties(
                "unused",
                0,
                Duration.ofMillis(10),
                new NotificationGatewayProperties.Tls(
                        "/missing/client.crt",
                        "/missing/client.key",
                        "/missing/ca.crt"
                )
        );

        assertThatThrownBy(() -> new NotificationGatewayGrpcClient(
                properties,
                new NotificationProcessor(null, null, null, null, null)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notification.gateway.tls.certificate-chain");
    }

    private void startServer(NotificationGatewayGrpc.NotificationGatewayImplBase service) throws IOException {
        String serverName = "notification-gateway-test-" + UUID.randomUUID();
        executor = Executors.newSingleThreadScheduledExecutor();
        server = InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(service)
                .build()
                .start();
        channel = InProcessChannelBuilder
                .forName(serverName)
                .directExecutor()
                .build();
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
