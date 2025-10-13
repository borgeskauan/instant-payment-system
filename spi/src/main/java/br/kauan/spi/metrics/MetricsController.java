package br.kauan.spi.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final MeterRegistry meterRegistry;

    @GetMapping("/payment/detailed")
    public ResponseEntity<Map<String, Object>> getDetailedPaymentMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        // Transfer metrics with detailed breakdown
        metrics.put("transfer", Map.of(
                "success", getDetailedTimerMetrics(PaymentMetrics.MetricNames.TRANSFER_SUCCESS_DURATION),
                "error", getDetailedTimerMetrics(PaymentMetrics.MetricNames.TRANSFER_ERROR_DURATION)
        ));

        // Transfer status metrics with detailed breakdown
        metrics.put("transfer_status", Map.of(
                "success", getDetailedTimerMetrics(PaymentMetrics.MetricNames.TRANSFER_STATUS_SUCCESS_DURATION),
                "error", getDetailedTimerMetrics(PaymentMetrics.MetricNames.TRANSFER_STATUS_ERROR_DURATION)
        ));

        // Get messages metrics with detailed breakdown
        metrics.put("get_messages", Map.of(
                "success", getDetailedTimerMetrics(PaymentMetrics.MetricNames.GET_MESSAGES_SUCCESS_DURATION),
                "error", getDetailedTimerMetrics(PaymentMetrics.MetricNames.GET_MESSAGES_ERROR_DURATION)
        ));

        return ResponseEntity.ok(metrics);
    }

    private Map<String, Object> getDetailedTimerMetrics(String timerName) {
        Map<String, Object> detailedMetrics = new HashMap<>();

        Timer timer = meterRegistry.find(timerName).timer();
        if (timer != null) {
            detailedMetrics.put("count", timer.count());
            detailedMetrics.put("total_time_seconds", timer.totalTime(TimeUnit.SECONDS));
            detailedMetrics.put("mean_seconds", timer.mean(TimeUnit.SECONDS));
            detailedMetrics.put("max_seconds", timer.max(TimeUnit.SECONDS));

            // Add percentile information if available
            Map<String, Double> percentiles = new HashMap<>();
            percentiles.put("p50", timer.percentile(0.5, TimeUnit.SECONDS));
            percentiles.put("p95", timer.percentile(0.95, TimeUnit.SECONDS));
            percentiles.put("p99", timer.percentile(0.99, TimeUnit.SECONDS));
            detailedMetrics.put("percentiles", percentiles);
        } else {
            detailedMetrics.put("count", 0L);
            detailedMetrics.put("total_time_seconds", 0.0);
            detailedMetrics.put("mean_seconds", 0.0);
            detailedMetrics.put("max_seconds", 0.0);
            detailedMetrics.put("percentiles", Map.of(
                    "p50", 0.0,
                    "p95", 0.0,
                    "p99", 0.0
            ));
        }

        return detailedMetrics;
    }

    @GetMapping("/payment/summary")
    public ResponseEntity<Map<String, Object>> getPaymentSummary() {
        Map<String, Object> summary = new HashMap<>();

        long totalRequests = getTotalCount();
        long successfulRequests = getSuccessfulCount();
        long failedRequests = getFailedCount();

        summary.put("total_requests", totalRequests);
        summary.put("successful_requests", successfulRequests);
        summary.put("failed_requests", failedRequests);
        summary.put("success_rate", totalRequests > 0 ? (double) successfulRequests / totalRequests * 100 : 0.0);
        summary.put("error_rate", totalRequests > 0 ? (double) failedRequests / totalRequests * 100 : 0.0);

        // Average response times
        summary.put("avg_success_time_seconds", getAverageSuccessTime());
        summary.put("avg_error_time_seconds", getAverageErrorTime());

        return ResponseEntity.ok(summary);
    }

    private long getTotalCount() {
        return getTimerCount(PaymentMetrics.MetricNames.TRANSFER_SUCCESS_DURATION) +
               getTimerCount(PaymentMetrics.MetricNames.TRANSFER_ERROR_DURATION) +
               getTimerCount(PaymentMetrics.MetricNames.TRANSFER_STATUS_SUCCESS_DURATION) +
               getTimerCount(PaymentMetrics.MetricNames.TRANSFER_STATUS_ERROR_DURATION) +
               getTimerCount(PaymentMetrics.MetricNames.GET_MESSAGES_SUCCESS_DURATION) +
               getTimerCount(PaymentMetrics.MetricNames.GET_MESSAGES_ERROR_DURATION);
    }

    private long getSuccessfulCount() {
        return getTimerCount(PaymentMetrics.MetricNames.TRANSFER_SUCCESS_DURATION) +
               getTimerCount(PaymentMetrics.MetricNames.TRANSFER_STATUS_SUCCESS_DURATION) +
               getTimerCount(PaymentMetrics.MetricNames.GET_MESSAGES_SUCCESS_DURATION);
    }

    private long getFailedCount() {
        return getTimerCount(PaymentMetrics.MetricNames.TRANSFER_ERROR_DURATION) +
               getTimerCount(PaymentMetrics.MetricNames.TRANSFER_STATUS_ERROR_DURATION) +
               getTimerCount(PaymentMetrics.MetricNames.GET_MESSAGES_ERROR_DURATION);
    }

    private double getAverageSuccessTime() {
        long count = getSuccessfulCount();
        if (count == 0) return 0.0;

        double totalTime = getTotalTime(PaymentMetrics.MetricNames.TRANSFER_SUCCESS_DURATION) +
                           getTotalTime(PaymentMetrics.MetricNames.TRANSFER_STATUS_SUCCESS_DURATION) +
                           getTotalTime(PaymentMetrics.MetricNames.GET_MESSAGES_SUCCESS_DURATION);

        return totalTime / count;
    }

    private double getAverageErrorTime() {
        long count = getFailedCount();
        if (count == 0) return 0.0;

        double totalTime = getTotalTime(PaymentMetrics.MetricNames.TRANSFER_ERROR_DURATION) +
                           getTotalTime(PaymentMetrics.MetricNames.TRANSFER_STATUS_ERROR_DURATION) +
                           getTotalTime(PaymentMetrics.MetricNames.GET_MESSAGES_ERROR_DURATION);

        return totalTime / count;
    }

    private long getTimerCount(String timerName) {
        Timer timer = meterRegistry.find(timerName).timer();
        return timer != null ? timer.count() : 0L;
    }

    private double getTotalTime(String timerName) {
        Timer timer = meterRegistry.find(timerName).timer();
        return timer != null ? timer.totalTime(TimeUnit.SECONDS) : 0.0;
    }
}