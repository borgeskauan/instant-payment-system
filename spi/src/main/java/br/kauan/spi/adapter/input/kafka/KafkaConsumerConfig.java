package br.kauan.spi.adapter.input.kafka;

import br.kauan.spi.config.SpiKafkaProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    private static final String CLIENT_TELEMETRY_PUSH_ENABLED = "enable.metrics.push";

    private final KafkaProperties kafkaProperties;
    private final SpiKafkaProperties spiKafkaProperties;

    public KafkaConsumerConfig(
            KafkaProperties kafkaProperties,
            SpiKafkaProperties spiKafkaProperties
    ) {
        this.kafkaProperties = kafkaProperties;
        this.spiKafkaProperties = spiKafkaProperties;
    }

    @Bean
    public ConsumerFactory<String, byte[]> paymentRequestConsumerFactory() {
        return consumerFactory(spiKafkaProperties.paymentRequest());
    }

    @Bean
    public ConsumerFactory<String, byte[]> statusReportConsumerFactory() {
        return consumerFactory(spiKafkaProperties.statusReport());
    }

    private ConsumerFactory<String, byte[]> consumerFactory(SpiKafkaProperties.ConsumerBatch batch) {
        Map<String, Object> config = new HashMap<>();

        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, kafkaProperties.getConsumer().getAutoOffsetReset());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(CLIENT_TELEMETRY_PUSH_ENABLED, false);

        // Performance tuning
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, batch.maxPollRecords());
        config.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, batch.fetchMinBytes());
        config.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, batch.fetchMaxWaitMs());

        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> paymentRequestKafkaListenerContainerFactory(
            @Qualifier("paymentRequestConsumerFactory") ConsumerFactory<String, byte[]> consumerFactory,
            @Qualifier("kafkaErrorHandler") CommonErrorHandler kafkaErrorHandler
    ) {
        return listenerContainerFactory(
                consumerFactory,
                kafkaErrorHandler,
                spiKafkaProperties.paymentRequestListenerConcurrency()
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> statusReportKafkaListenerContainerFactory(
            @Qualifier("statusReportConsumerFactory") ConsumerFactory<String, byte[]> consumerFactory,
            @Qualifier("kafkaErrorHandler") CommonErrorHandler kafkaErrorHandler
    ) {
        return listenerContainerFactory(
                consumerFactory,
                kafkaErrorHandler,
                spiKafkaProperties.statusReportListenerConcurrency()
        );
    }

    private ConcurrentKafkaListenerContainerFactory<String, byte[]> listenerContainerFactory(
            ConsumerFactory<String, byte[]> consumerFactory,
            CommonErrorHandler kafkaErrorHandler,
            int concurrency
    ) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(concurrency);
        factory.setBatchListener(true);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.setAutoStartup(kafkaProperties.getListener().isAutoStartup());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        return factory;
    }
}
