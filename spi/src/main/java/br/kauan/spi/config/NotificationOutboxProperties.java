package br.kauan.spi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("spi.notification-outbox")
public record NotificationOutboxProperties(
        int queueCapacity,
        int recoveryBatchSize,
        Duration retryDelay
) {

    public NotificationOutboxProperties {
        if (queueCapacity < 1 || recoveryBatchSize < 1) {
            throw new IllegalArgumentException("Notification outbox capacities must be positive");
        }
        Objects.requireNonNull(retryDelay, "retryDelay");
        if (retryDelay.isNegative()) {
            throw new IllegalArgumentException("Notification outbox retry delay must not be negative");
        }
    }
}
