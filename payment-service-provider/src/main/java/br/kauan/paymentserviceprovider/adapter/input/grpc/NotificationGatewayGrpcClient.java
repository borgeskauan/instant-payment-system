package br.kauan.paymentserviceprovider.adapter.input.grpc;

import br.kauan.notificationgateway.grpc.proto.Ack;
import br.kauan.notificationgateway.grpc.proto.ClientMessage;
import br.kauan.notificationgateway.grpc.proto.Notification;
import br.kauan.notificationgateway.grpc.proto.NotificationGatewayGrpc;
import br.kauan.notificationgateway.grpc.proto.Subscribe;
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
import java.util.concurrent.atomic.AtomicReference;

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

        AtomicReference<StreamObserver<ClientMessage>> requestObserverRef = new AtomicReference<>();
        StreamObserver<ClientMessage> requestObserver = stub.streamNotifications(new StreamObserver<>() {
            @Override
            public void onNext(Notification notification) {
                String localIspb = GlobalVariables.getBankCode();
                try {
                    notificationProcessor.process(localIspb, notification.getPayload().toStringUtf8());
                    ack(notification.getDeliveryId());
                } catch (Exception e) {
                    log.warn("Notification processing failed; delivery will not be ACKed. deliveryId={}",
                            notification.getDeliveryId(), e);
                }
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

            private void ack(String deliveryId) {
                if (deliveryId == null || deliveryId.isBlank()) {
                    log.warn("Processed notification without delivery_id; ACK skipped");
                    return;
                }

                ClientMessage ack = ClientMessage.newBuilder()
                        .setAck(Ack.newBuilder().setDeliveryId(deliveryId))
                        .build();
                StreamObserver<ClientMessage> requestObserver = requestObserverRef.get();
                if (requestObserver == null) {
                    log.warn("ACK skipped because request stream is not ready. deliveryId={}", deliveryId);
                    return;
                }
                requestObserver.onNext(ack);
            }
        });
        requestObserverRef.set(requestObserver);

        requestObserver.onNext(ClientMessage.newBuilder()
                .setSubscribe(Subscribe.newBuilder().setIspb(ispb))
                .build());
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
