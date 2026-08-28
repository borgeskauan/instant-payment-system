package br.kauan.paymentserviceprovider.adapter.input.grpc;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "notification.gateway")
public record NotificationGatewayProperties(
        String host,
        int port,
        Duration reconnectDelay,
        Tls tls
) {

    public record Tls(
            String certificateChain,
            String privateKey,
            String trustCertCollection
    ) {
    }
}
