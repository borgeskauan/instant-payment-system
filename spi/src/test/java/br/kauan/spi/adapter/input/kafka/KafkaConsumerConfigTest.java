package br.kauan.spi.adapter.input.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KafkaConsumerConfigTest {

    @Test
    void spiKafkaListenerContainerFactoryUsesBatchManualAckAndConfiguredConcurrency() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "listenerConcurrency", 4);

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                config.spiKafkaListenerContainerFactory(
                        mock(ConsumerFactory.class),
                        mock(CommonErrorHandler.class));

        assertThat(factory.isBatchListener()).isTrue();
        assertThat(ReflectionTestUtils.getField(factory, "concurrency")).isEqualTo(4);
        assertThat(factory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    }

    @Test
    void spiKafkaListenerContainerFactoryUsesKafkaErrorHandler() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "listenerConcurrency", 4);
        CommonErrorHandler errorHandler = mock(CommonErrorHandler.class);

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                config.spiKafkaListenerContainerFactory(
                        mock(ConsumerFactory.class),
                        errorHandler);

        assertThat(ReflectionTestUtils.getField(factory, "commonErrorHandler")).isSameAs(errorHandler);
    }

    @Test
    void spiKafkaListenerContainerFactoryUsesConfiguredAutoStartup() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "listenerAutoStartup", false);

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                config.spiKafkaListenerContainerFactory(
                        mock(ConsumerFactory.class),
                        mock(CommonErrorHandler.class));

        assertThat(ReflectionTestUtils.getField(factory, "autoStartup")).isEqualTo(false);
    }

    @Test
    void consumerFactoriesDisableAutoCommit() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "autoOffsetReset", "earliest");

        var paymentRequestConsumerFactory = config.consumerFactory();

        assertThat(paymentRequestConsumerFactory.getConfigurationProperties())
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    }

    @Test
    void consumerFactoryUsesConfiguredPollAndFetchSettings() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "autoOffsetReset", "earliest");
        ReflectionTestUtils.setField(config, "maxPollRecords", 500);
        ReflectionTestUtils.setField(config, "fetchMinBytes", 1);
        ReflectionTestUtils.setField(config, "fetchMaxWaitMs", 5);

        var consumerFactory = config.consumerFactory();

        assertThat(consumerFactory.getConfigurationProperties())
                .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500)
                .containsEntry(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1)
                .containsEntry(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 5);
    }

    @Test
    void consumerFactoryDoesNotSetSharedGroupId() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "autoOffsetReset", "earliest");

        var consumerFactory = config.consumerFactory();

        assertThat(consumerFactory.getConfigurationProperties())
                .doesNotContainKey(ConsumerConfig.GROUP_ID_CONFIG);
    }

    @Test
    void consumerFactoryDisablesKafkaClientTelemetryPush() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "autoOffsetReset", "earliest");

        var consumerFactory = config.consumerFactory();

        assertThat(consumerFactory.getConfigurationProperties())
                .containsEntry("enable.metrics.push", false);
    }
}
