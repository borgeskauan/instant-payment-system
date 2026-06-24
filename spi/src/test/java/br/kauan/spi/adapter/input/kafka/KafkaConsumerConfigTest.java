package br.kauan.spi.adapter.input.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KafkaConsumerConfigTest {

    @Test
    void kafkaListenerContainerFactoryUsesConfiguredConcurrency() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "listenerConcurrency", 4);

        var factory = config.kafkaListenerContainerFactory(mock(ConsumerFactory.class));
        factory.getContainerProperties().setMessageListener((MessageListener<String, byte[]>) record -> {
        });
        ConcurrentMessageListenerContainer<String, byte[]> container =
                (ConcurrentMessageListenerContainer<String, byte[]>) factory.createContainer("spi-payment-requests");

        assertThat(container.getConcurrency()).isEqualTo(4);
    }

    @Test
    void paymentRequestKafkaListenerContainerFactoryUsesBatchListener() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "listenerConcurrency", 4);

        var factory = config.paymentRequestKafkaListenerContainerFactory(mock(ConsumerFactory.class));

        assertThat(factory.isBatchListener()).isTrue();
        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.BATCH);
    }

    @Test
    void statusReportKafkaListenerContainerFactoryUsesBatchListener() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "listenerConcurrency", 4);

        var factory = config.statusReportKafkaListenerContainerFactory(mock(ConsumerFactory.class));

        assertThat(factory.isBatchListener()).isTrue();
        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL);
    }

    @Test
    void statusReportConsumerFactoryDisablesAutoCommit() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "autoOffsetReset", "earliest");

        var consumerFactory = config.statusReportConsumerFactory();

        assertThat(consumerFactory.getConfigurationProperties())
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
}
