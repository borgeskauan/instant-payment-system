package br.kauan.paymentserviceprovider.adapter.input.grpc;

import br.kauan.notificationgateway.grpc.proto.NotificationBatch;
import br.kauan.notificationgateway.grpc.proto.NotificationGatewayGrpc;
import br.kauan.notificationgateway.grpc.proto.StreamRequest;
import br.kauan.paymentserviceprovider.adapter.input.notification.NotificationProcessor;
import br.kauan.paymentserviceprovider.config.GlobalVariables;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class NotificationGatewayGrpcClient implements SmartLifecycle {

    private final NotificationGatewayProperties properties;
    private final NotificationProcessor notificationProcessor;
    private final ManagedChannel channel;
    private final ScheduledExecutorService reconnectExecutor;
    private final NotificationGatewayGrpc.NotificationGatewayStub stub;

    private volatile boolean running;

    @Autowired
    public NotificationGatewayGrpcClient(
            NotificationGatewayProperties properties,
            NotificationProcessor notificationProcessor
    ) {
        this(
                properties,
                notificationProcessor,
                ManagedChannelBuilder
                        .forAddress(properties.host(), properties.port())
                        .usePlaintext()
                        .build(),
                Executors.newSingleThreadScheduledExecutor()
        );
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
        connect();
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

    private void connect() {
        if (!running) {
            return;
        }

        String ispb = GlobalVariables.getBankCode();
        log.info("Opening notification-gateway gRPC stream for ISPB: {}", ispb);

        StreamRequest request = StreamRequest.newBuilder()
                .setIspb(ispb)
                .build();

        stub.streamNotifications(request, new StreamObserver<>() {
            @Override
            public void onNext(NotificationBatch notificationBatch) {
                String localIspb = GlobalVariables.getBankCode();
                notificationBatch.getPayloadsList().forEach(payload ->
                        notificationProcessor.process(localIspb, payload.toStringUtf8())
                );
            }

            @Override
            public void onError(Throwable throwable) {
                if (!running || isCancelled(throwable)) {
                    return;
                }

                log.warn("notification-gateway stream failed; scheduling reconnect", throwable);
                scheduleReconnect();
            }

            @Override
            public void onCompleted() {
                if (!running) {
                    return;
                }

                log.warn("notification-gateway stream completed unexpectedly; scheduling reconnect");
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        if (!running || reconnectExecutor.isShutdown()) {
            return;
        }

        reconnectExecutor.schedule(
                this::connect,
                properties.reconnectDelay().toMillis(),
                TimeUnit.MILLISECONDS
        );
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
