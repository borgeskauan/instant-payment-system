package br.kauan.spi.adapter.input.kafka;

import br.kauan.spi.config.SpiKafkaProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KafkaConsumerConfigTest {

    @Test
    void paymentAndStatusFactoriesUseTheirOwnConfiguredConcurrency() {
        KafkaConsumerConfig config = config(8, 1, true, defaultPaymentBatch(), defaultStatusBatch());
        ConsumerFactory<String, byte[]> paymentConsumerFactory = mock(ConsumerFactory.class);
        ConsumerFactory<String, byte[]> statusConsumerFactory = mock(ConsumerFactory.class);
        CommonErrorHandler errorHandler = mock(CommonErrorHandler.class);

        ConcurrentKafkaListenerContainerFactory<String, byte[]> paymentFactory =
                config.paymentRequestKafkaListenerContainerFactory(paymentConsumerFactory, errorHandler);
        ConcurrentKafkaListenerContainerFactory<String, byte[]> statusFactory =
                config.statusReportKafkaListenerContainerFactory(statusConsumerFactory, errorHandler);

        assertThat(ReflectionTestUtils.getField(paymentFactory, "concurrency")).isEqualTo(8);
        assertThat(ReflectionTestUtils.getField(statusFactory, "concurrency")).isEqualTo(1);
        assertThat(paymentFactory.isBatchListener()).isTrue();
        assertThat(statusFactory.isBatchListener()).isTrue();
        assertThat(paymentFactory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        assertThat(statusFactory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    }

    @Test
    void listenerFactoriesUseKafkaErrorHandler() {
        KafkaConsumerConfig config = config(4, 1, true, defaultPaymentBatch(), defaultStatusBatch());
        CommonErrorHandler errorHandler = mock(CommonErrorHandler.class);

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                config.paymentRequestKafkaListenerContainerFactory(
                        mock(ConsumerFactory.class),
                        errorHandler);

        assertThat(ReflectionTestUtils.getField(factory, "commonErrorHandler")).isSameAs(errorHandler);
    }

    @Test
    void listenerFactoriesUseConfiguredAutoStartup() {
        KafkaConsumerConfig config = config(1, 1, false, defaultPaymentBatch(), defaultStatusBatch());

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                config.statusReportKafkaListenerContainerFactory(
                        mock(ConsumerFactory.class),
                        mock(CommonErrorHandler.class));

        assertThat(ReflectionTestUtils.getField(factory, "autoStartup")).isEqualTo(false);
    }

    @Test
    void consumerFactoriesShareTransportSafetySettings() {
        KafkaConsumerConfig config = config(1, 1, true, defaultPaymentBatch(), defaultStatusBatch());

        var paymentRequestConsumerFactory = config.paymentRequestConsumerFactory();
        var statusReportConsumerFactory = config.statusReportConsumerFactory();

        assertThat(paymentRequestConsumerFactory.getConfigurationProperties())
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .containsEntry("enable.metrics.push", false)
                .doesNotContainKey(ConsumerConfig.GROUP_ID_CONFIG);
        assertThat(statusReportConsumerFactory.getConfigurationProperties())
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .containsEntry("enable.metrics.push", false)
                .doesNotContainKey(ConsumerConfig.GROUP_ID_CONFIG);
    }

    @Test
    void paymentRequestConsumerFactoryUsesDedicatedBatchingSettings() {
        KafkaConsumerConfig config = config(
                1,
                1,
                true,
                new SpiKafkaProperties.ConsumerBatch(500, 131_072, 100),
                new SpiKafkaProperties.ConsumerBatch(300, 1_024, 10)
        );

        var consumerFactory = config.paymentRequestConsumerFactory();

        assertThat(consumerFactory.getConfigurationProperties())
                .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500)
                .containsEntry(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 131_072)
                .containsEntry(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 100);
    }

    @Test
    void statusReportConsumerFactoryUsesDedicatedBatchingSettings() {
        KafkaConsumerConfig config = config(
                1,
                1,
                true,
                new SpiKafkaProperties.ConsumerBatch(500, 131_072, 100),
                new SpiKafkaProperties.ConsumerBatch(220, 16_384, 125)
        );

        var consumerFactory = config.statusReportConsumerFactory();

        assertThat(consumerFactory.getConfigurationProperties())
                .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 220)
                .containsEntry(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 16_384)
                .containsEntry(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 125);
    }

    @Test
    void performanceDefaultsDoNotFragmentAvailableStatusReportBatches() throws Exception {
        String application = Files.readString(Path.of("src", "main", "resources", "application.yml"));

        assertThat(application)
                .contains("    payment-request-listener-concurrency: 1")
                .contains("    status-report-listener-concurrency: 1")
                .contains(
                "    status-report:\n"
                        + "      max-poll-records: 500\n"
                        + "      fetch-min-bytes: 16384\n"
                        + "      fetch-max-wait-ms: 125\n"
        );
    }

    private KafkaConsumerConfig config(
            int paymentConcurrency,
            int statusConcurrency,
            boolean autoStartup,
            SpiKafkaProperties.ConsumerBatch paymentBatch,
            SpiKafkaProperties.ConsumerBatch statusBatch
    ) {
        KafkaProperties kafka = new KafkaProperties();
        kafka.setBootstrapServers(List.of("localhost:9092"));
        kafka.getConsumer().setAutoOffsetReset("earliest");
        kafka.getListener().setAutoStartup(autoStartup);
        SpiKafkaProperties spi = new SpiKafkaProperties(
                paymentConcurrency,
                statusConcurrency,
                "spi-payment-request-consumer-group",
                "spi-status-report-consumer-group",
                paymentBatch,
                statusBatch
        );
        return new KafkaConsumerConfig(kafka, spi);
    }

    private SpiKafkaProperties.ConsumerBatch defaultPaymentBatch() {
        return new SpiKafkaProperties.ConsumerBatch(500, 57_344, 100);
    }

    private SpiKafkaProperties.ConsumerBatch defaultStatusBatch() {
        return new SpiKafkaProperties.ConsumerBatch(500, 16_384, 125);
    }
}
