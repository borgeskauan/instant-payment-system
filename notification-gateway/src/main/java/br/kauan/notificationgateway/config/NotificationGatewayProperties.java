package br.kauan.notificationgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("notification-gateway")
public record NotificationGatewayProperties(
        Kafka kafka,
        Pull pull
) {

    public record Kafka(
            int listenerConcurrency,
            int recentWindowCapacityPerPartition
    ) {
        public Kafka {
            if (listenerConcurrency < 1) {
                throw new IllegalArgumentException("Kafka listener concurrency must be positive");
            }
            if (recentWindowCapacityPerPartition < 1) {
                throw new IllegalArgumentException("partition buffer capacity must be positive");
            }
        }
    }

    public record Pull(
            String cursorSecret,
            Duration longPollTimeout,
            int kafkaScanLimit,
            Duration kafkaPollTimeout
    ) {
        public Pull {
            if (cursorSecret == null || cursorSecret.isBlank()) {
                throw new IllegalArgumentException("cursor HMAC secret must be configured");
            }
            if (longPollTimeout == null || longPollTimeout.isZero() || longPollTimeout.isNegative()) {
                throw new IllegalArgumentException("long-poll timeout must be positive");
            }
            if (kafkaScanLimit < 15) {
                throw new IllegalArgumentException("Kafka scan limit must be at least 15");
            }
            if (kafkaPollTimeout == null || kafkaPollTimeout.isZero() || kafkaPollTimeout.isNegative()) {
                throw new IllegalArgumentException("Kafka poll timeout must be positive");
            }
        }

    }
}
