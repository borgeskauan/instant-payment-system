package br.kauan.spi.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class PaymentMetrics {

    private final MeterRegistry meterRegistry;

    // Centralized metric names
    public static class MetricNames {
        public static final String TRANSFER_SUCCESS_DURATION = "payment.transfer.success.duration";
        public static final String TRANSFER_ERROR_DURATION = "payment.transfer.error.duration";
        public static final String TRANSFER_STATUS_SUCCESS_DURATION = "payment.transfer.status.success.duration";
        public static final String TRANSFER_STATUS_ERROR_DURATION = "payment.transfer.status.error.duration";
        public static final String GET_MESSAGES_SUCCESS_DURATION = "payment.messages.get.success.duration";
        public static final String GET_MESSAGES_ERROR_DURATION = "payment.messages.get.error.duration";
    }

    // Pre-created timer instances
    private Timer transferSuccessTimer;
    private Timer transferErrorTimer;
    private Timer transferStatusSuccessTimer;
    private Timer transferStatusErrorTimer;
    private Timer getMessagesSuccessTimer;
    private Timer getMessagesErrorTimer;

    @PostConstruct
    public void init() {
        // Initialize all timers once during bean creation
        this.transferSuccessTimer = Timer.builder(MetricNames.TRANSFER_SUCCESS_DURATION)
                .description("Time taken to successfully process transfer requests")
                .register(meterRegistry);

        this.transferErrorTimer = Timer.builder(MetricNames.TRANSFER_ERROR_DURATION)
                .description("Time taken for failed transfer requests")
                .register(meterRegistry);

        this.transferStatusSuccessTimer = Timer.builder(MetricNames.TRANSFER_STATUS_SUCCESS_DURATION)
                .description("Time taken to successfully process transfer status requests")
                .register(meterRegistry);

        this.transferStatusErrorTimer = Timer.builder(MetricNames.TRANSFER_STATUS_ERROR_DURATION)
                .description("Time taken for failed transfer status requests")
                .register(meterRegistry);

        this.getMessagesSuccessTimer = Timer.builder(MetricNames.GET_MESSAGES_SUCCESS_DURATION)
                .description("Time taken to successfully retrieve messages")
                .register(meterRegistry);

        this.getMessagesErrorTimer = Timer.builder(MetricNames.GET_MESSAGES_ERROR_DURATION)
                .description("Time taken for failed message retrieval requests")
                .register(meterRegistry);
    }

    public void recordTransferSuccess(long duration, TimeUnit unit) {
        transferSuccessTimer.record(duration, unit);
    }

    public void recordTransferError(long duration, TimeUnit unit) {
        transferErrorTimer.record(duration, unit);
    }

    public void recordTransferStatusSuccess(long duration, TimeUnit unit) {
        transferStatusSuccessTimer.record(duration, unit);
    }

    public void recordTransferStatusError(long duration, TimeUnit unit) {
        transferStatusErrorTimer.record(duration, unit);
    }

    public void recordGetMessagesSuccess(long duration, TimeUnit unit) {
        getMessagesSuccessTimer.record(duration, unit);
    }

    public void recordGetMessagesError(long duration, TimeUnit unit) {
        getMessagesErrorTimer.record(duration, unit);
    }
}