package br.kauan.spi.adapter.input.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KafkaConsumerConfigTest {

    @Test
    void paymentAndStatusFactoriesUseTheirOwnConfiguredConcurrency() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "paymentRequestListenerConcurrency", 8);
        ReflectionTestUtils.setField(config, "statusReportListenerConcurrency", 1);
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
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "paymentRequestListenerConcurrency", 4);
        CommonErrorHandler errorHandler = mock(CommonErrorHandler.class);

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                config.paymentRequestKafkaListenerContainerFactory(
                        mock(ConsumerFactory.class),
                        errorHandler);

        assertThat(ReflectionTestUtils.getField(factory, "commonErrorHandler")).isSameAs(errorHandler);
    }

    @Test
    void listenerFactoriesUseConfiguredAutoStartup() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "listenerAutoStartup", false);
        ReflectionTestUtils.setField(config, "statusReportListenerConcurrency", 1);

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                config.statusReportKafkaListenerContainerFactory(
                        mock(ConsumerFactory.class),
                        mock(CommonErrorHandler.class));

        assertThat(ReflectionTestUtils.getField(factory, "autoStartup")).isEqualTo(false);
    }

    @Test
    void consumerFactoriesShareTransportSafetySettings() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "autoOffsetReset", "earliest");

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
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "autoOffsetReset", "earliest");
        ReflectionTestUtils.setField(config, "paymentRequestMaxPollRecords", 500);
        ReflectionTestUtils.setField(config, "paymentRequestFetchMinBytes", 131_072);
        ReflectionTestUtils.setField(config, "paymentRequestFetchMaxWaitMs", 100);
        ReflectionTestUtils.setField(config, "statusReportMaxPollRecords", 300);
        ReflectionTestUtils.setField(config, "statusReportFetchMinBytes", 1_024);
        ReflectionTestUtils.setField(config, "statusReportFetchMaxWaitMs", 10);

        var consumerFactory = config.paymentRequestConsumerFactory();

        assertThat(consumerFactory.getConfigurationProperties())
                .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500)
                .containsEntry(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 131_072)
                .containsEntry(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 100);
    }

    @Test
    void statusReportConsumerFactoryUsesDedicatedBatchingSettings() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "autoOffsetReset", "earliest");
        ReflectionTestUtils.setField(config, "paymentRequestMaxPollRecords", 500);
        ReflectionTestUtils.setField(config, "paymentRequestFetchMinBytes", 131_072);
        ReflectionTestUtils.setField(config, "paymentRequestFetchMaxWaitMs", 100);
        ReflectionTestUtils.setField(config, "statusReportMaxPollRecords", 220);
        ReflectionTestUtils.setField(config, "statusReportFetchMinBytes", 16_384);
        ReflectionTestUtils.setField(config, "statusReportFetchMaxWaitMs", 125);

        var consumerFactory = config.statusReportConsumerFactory();

        assertThat(consumerFactory.getConfigurationProperties())
                .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 220)
                .containsEntry(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 16_384)
                .containsEntry(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 125);
    }

    @Test
    void performanceDefaultsTargetTwoHundredRecordStatusReportBatches() throws Exception {
        String application = Files.readString(Path.of("src", "main", "resources", "application.yml"));
        String compose = Files.readString(Path.of("..", "infra", "docker-compose.yml"));

        assertThat(application).contains(
                "    status-report:\n"
                        + "      max-poll-records: 220\n"
                        + "      fetch-min-bytes: 16384\n"
                        + "      fetch-max-wait-ms: 125\n"
        );
        assertThat(compose)
                .contains("SPI_KAFKA_STATUS_REPORT_MAX_POLL_RECORDS: 220")
                .contains("SPI_KAFKA_STATUS_REPORT_FETCH_MIN_BYTES: 16384")
                .contains("SPI_KAFKA_STATUS_REPORT_FETCH_MAX_WAIT_MS: 125");
    }
}
