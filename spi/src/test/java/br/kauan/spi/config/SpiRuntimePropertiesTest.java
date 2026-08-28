package br.kauan.spi.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpiRuntimePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "spi.kafka.payment-request-listener-concurrency=1",
                    "spi.kafka.status-report-listener-concurrency=1",
                    "spi.kafka.payment-request-group-id=spi-payment-request-consumer-group",
                    "spi.kafka.status-report-group-id=spi-status-report-consumer-group",
                    "spi.kafka.payment-request.max-poll-records=500",
                    "spi.kafka.payment-request.fetch-min-bytes=57344",
                    "spi.kafka.payment-request.fetch-max-wait-ms=100",
                    "spi.kafka.status-report.max-poll-records=500",
                    "spi.kafka.status-report.fetch-min-bytes=16384",
                    "spi.kafka.status-report.fetch-max-wait-ms=125",
                    "spi.notification-outbox.queue-capacity=1024",
                    "spi.notification-outbox.recovery-batch-size=256",
                    "spi.notification-outbox.retry-delay=100ms"
            );

    @Test
    void bindsTheHomologatedRuntimeConfiguration() {
        contextRunner.run(context -> {
            SpiKafkaProperties kafka = context.getBean(SpiKafkaProperties.class);
            NotificationOutboxProperties outbox = context.getBean(NotificationOutboxProperties.class);

            assertThat(kafka.paymentRequestListenerConcurrency()).isOne();
            assertThat(kafka.statusReportListenerConcurrency()).isOne();
            assertThat(kafka.paymentRequest().fetchMinBytes()).isEqualTo(57_344);
            assertThat(kafka.statusReport().fetchMinBytes()).isEqualTo(16_384);
            assertThat(outbox.queueCapacity()).isEqualTo(1_024);
            assertThat(outbox.retryDelay()).isEqualTo(Duration.ofMillis(100));
        });
    }

    @Test
    void anEnvironmentOverrideChangesTheEffectiveValueExplicitly() {
        contextRunner
                .withPropertyValues("spi.kafka.status-report-listener-concurrency=2")
                .run(context -> assertThat(
                        context.getBean(SpiKafkaProperties.class).statusReportListenerConcurrency()
                ).isEqualTo(2));
    }

    @Test
    void reportsTheEffectiveRuntimeConfigurationWithoutSecrets() {
        KafkaProperties springKafka = new KafkaProperties();
        springKafka.setBootstrapServers(List.of("broker:9092"));
        springKafka.getListener().setAutoStartup(false);
        SpiKafkaProperties spiKafka = new SpiKafkaProperties(
                1,
                1,
                "payment-group",
                "status-group",
                new SpiKafkaProperties.ConsumerBatch(500, 57_344, 100),
                new SpiKafkaProperties.ConsumerBatch(500, 16_384, 125)
        );
        NotificationOutboxProperties outbox =
                new NotificationOutboxProperties(1_024, 256, Duration.ofMillis(100));

        String summary = new SpiRuntimeConfigurationLog(springKafka, spiKafka, outbox).summary();

        assertThat(summary)
                .startsWith("event=spi_runtime_configuration ")
                .contains("payment_request_listener_concurrency=1")
                .contains("status_report_listener_concurrency=1")
                .contains("payment_request_fetch_min_bytes=57344")
                .contains("status_report_fetch_min_bytes=16384")
                .contains("notification_outbox_queue_capacity=1024")
                .contains("notification_outbox_retry_delay_ms=100")
                .doesNotContain("broker:9092");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({SpiKafkaProperties.class, NotificationOutboxProperties.class})
    static class PropertiesConfiguration {
    }
}
