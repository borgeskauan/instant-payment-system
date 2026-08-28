package br.kauan.paymentserviceprovider.adapter.input.grpc;

import br.kauan.notificationgateway.grpc.proto.NotificationGatewayGrpc;
import br.kauan.notificationgateway.grpc.proto.PullRequest;
import br.kauan.notificationgateway.grpc.proto.PullResponse;
import br.kauan.paymentserviceprovider.adapter.input.notification.NotificationProcessor;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "notification.gateway", name = "client-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationGatewayGrpcClient implements SmartLifecycle {

    private final NotificationGatewayProperties properties;
    private final NotificationProcessor notificationProcessor;
    private final ManagedChannel channel;
    private final ScheduledExecutorService reconnectExecutor;
    private final NotificationGatewayGrpc.NotificationGatewayStub stub;

    private volatile boolean running;
    private volatile String cursor = "";

    @Autowired
    public NotificationGatewayGrpcClient(
            NotificationGatewayProperties properties,
            NotificationProcessor notificationProcessor
    ) {
        this(properties, notificationProcessor, createChannel(properties), Executors.newSingleThreadScheduledExecutor());
    }

    NotificationGatewayGrpcClient(
            NotificationGatewayProperties properties,
            NotificationProcessor notificationProcessor,
            ManagedChannel channel,
            ScheduledExecutorService reconnectExecutor
    ) {
        this.properties = properties;
        this.notificationProcessor = notificationProcessor;
        this.channel = channel;
        this.reconnectExecutor = reconnectExecutor;
        this.stub = NotificationGatewayGrpc.newStub(channel);
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        pull();
    }

    @Override
    public void stop() {
        running = false;
        shutdown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pull() {
        if (!running) {
            return;
        }

        PullRequest request = PullRequest.newBuilder().setCursor(cursor).build();
        stub.pullNotifications(request, new StreamObserver<>() {
            private boolean processed;

            @Override
            public void onNext(PullResponse response) {
                try {
                    for (var notification : response.getNotificationsList()) {
                        notificationProcessor.process(notification.getPayload().toStringUtf8());
                    }
                    cursor = response.getNextCursor();
                    processed = true;
                } catch (Exception processingFailure) {
                    log.warn("Notification batch processing failed; keeping the previous cursor", processingFailure);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                if (!running || isCancelled(throwable)) {
                    return;
                }
                log.warn("notification-gateway Pull failed; scheduling retry", throwable);
                scheduleRetry();
            }

            @Override
            public void onCompleted() {
                if (!running) {
                    return;
                }
                if (processed) {
                    pull();
                } else {
                    scheduleRetry();
                }
            }
        });
    }

    private static ManagedChannel createChannel(NotificationGatewayProperties properties) {
        NotificationGatewayProperties.Tls tls = properties.tls();
        if (tls == null) {
            throw new IllegalStateException("notification.gateway.tls must be configured");
        }

        File certificateChain = requiredFile(tls.certificateChain(), "notification.gateway.tls.certificate-chain");
        File privateKey = requiredFile(tls.privateKey(), "notification.gateway.tls.private-key");
        File trustCertCollection = requiredFile(tls.trustCertCollection(), "notification.gateway.tls.trust-cert-collection");

        try {
            return NettyChannelBuilder
                    .forAddress(properties.host(), properties.port())
                    .sslContext(GrpcSslContexts.forClient()
                            .keyManager(certificateChain, privateKey)
                            .trustManager(trustCertCollection)
                            .build())
                    .build();
        } catch (SSLException e) {
            throw new IllegalStateException("Failed to configure notification-gateway mTLS channel", e);
        }
    }

    private static File requiredFile(String path, String propertyName) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException(propertyName + " must be configured");
        }

        File file = new File(path);
        if (!file.isFile() || !file.canRead()) {
            throw new IllegalStateException(propertyName + " does not point to a readable file: " + path);
        }
        return file;
    }

    private void scheduleRetry() {
        if (!running || reconnectExecutor.isShutdown()) {
            return;
        }
        reconnectExecutor.schedule(this::pull, properties.reconnectDelay().toMillis(), TimeUnit.MILLISECONDS);
    }

    private boolean isCancelled(Throwable throwable) {
        if (throwable instanceof StatusRuntimeException statusRuntimeException) {
            return Status.CANCELLED.getCode().equals(statusRuntimeException.getStatus().getCode());
        }
        return false;
    }

    @PreDestroy
    public void shutdown() {
        reconnectExecutor.shutdownNow();
        channel.shutdownNow();
    }
}
