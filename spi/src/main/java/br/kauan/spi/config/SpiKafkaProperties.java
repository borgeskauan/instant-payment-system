package br.kauan.spi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

@ConfigurationProperties("spi.kafka")
public record SpiKafkaProperties(
        int paymentRequestListenerConcurrency,
        int statusReportListenerConcurrency,
        String paymentRequestGroupId,
        String statusReportGroupId,
        ConsumerBatch paymentRequest,
        ConsumerBatch statusReport
) {

    public SpiKafkaProperties {
        requirePositive("payment request listener concurrency", paymentRequestListenerConcurrency);
        requirePositive("status report listener concurrency", statusReportListenerConcurrency);
        requireText("payment request group ID", paymentRequestGroupId);
        requireText("status report group ID", statusReportGroupId);
        Objects.requireNonNull(paymentRequest, "paymentRequest");
        Objects.requireNonNull(statusReport, "statusReport");
    }

    public record ConsumerBatch(
            int maxPollRecords,
            int fetchMinBytes,
            int fetchMaxWaitMs
    ) {
        public ConsumerBatch {
            requirePositive("max poll records", maxPollRecords);
            requirePositive("fetch min bytes", fetchMinBytes);
            requirePositive("fetch max wait", fetchMaxWaitMs);
        }
    }

    private static void requirePositive(String property, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(property + " must be positive");
        }
    }

    private static void requireText(String property, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " must not be blank");
        }
    }
}
