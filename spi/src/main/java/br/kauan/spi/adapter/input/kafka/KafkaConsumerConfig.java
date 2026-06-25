package br.kauan.spi.adapter.input.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.auto-offset-reset}")
    private String autoOffsetReset;

    @Value("${spi.kafka.listener-concurrency:3}")
    private int listenerConcurrency;

    @Value("${spi.kafka.max-poll-records:500}")
    private int maxPollRecords;

    @Value("${spi.kafka.fetch-min-bytes:1024}")
    private int fetchMinBytes;

    @Value("${spi.kafka.fetch-max-wait-ms:10}")
    private int fetchMaxWaitMs;

    @Bean
    public ConsumerFactory<String, byte[]> consumerFactory() {
        Map<String, Object> config = baseConsumerConfig();

        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        config.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 1000);

        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConsumerFactory<String, byte[]> statusReportConsumerFactory() {
        Map<String, Object> config = baseConsumerConfig();

        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        return new DefaultKafkaConsumerFactory<>(config);
    }

    private Map<String, Object> baseConsumerConfig() {
        Map<String, Object> config = new HashMap<>();
        
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put("enable.metrics.push", false);
        
        // Performance tuning
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        config.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, fetchMinBytes);
        config.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, fetchMaxWaitMs);

        return config;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> kafkaListenerContainerFactory(
            @Qualifier("consumerFactory") ConsumerFactory<String, byte[]> consumerFactory
    ) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = 
                new ConcurrentKafkaListenerContainerFactory<>();
        
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(listenerConcurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> paymentRequestKafkaListenerContainerFactory(
            @Qualifier("consumerFactory") ConsumerFactory<String, byte[]> consumerFactory
    ) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(listenerConcurrency);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);

        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> statusReportKafkaListenerContainerFactory(
            @Qualifier("statusReportConsumerFactory") ConsumerFactory<String, byte[]> consumerFactory
    ) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(listenerConcurrency);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);

        return factory;
    }
}
