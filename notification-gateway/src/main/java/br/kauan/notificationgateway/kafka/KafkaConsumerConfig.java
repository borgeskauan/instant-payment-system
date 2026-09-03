package br.kauan.notificationgateway.kafka;

import br.kauan.notificationgateway.config.NotificationGatewayProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.Map;

@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    private final KafkaProperties kafkaProperties;
    private final NotificationGatewayProperties gatewayProperties;

    public KafkaConsumerConfig(
            KafkaProperties kafkaProperties,
            NotificationGatewayProperties gatewayProperties
    ) {
        this.kafkaProperties = kafkaProperties;
        this.gatewayProperties = gatewayProperties;
    }

    @Bean
    public ConsumerFactory<String, byte[]> notificationConsumerFactory() {
        Map<String, Object> config = kafkaProperties.buildConsumerProperties();
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put("enable.metrics.push", false);

        // Performance: pull up to 500 records per poll to keep the gateway responsive under load
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        config.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1);        // no min-fetch delay
        config.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 100);    // max 100ms wait

        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> notificationKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(notificationConsumerFactory());
        factory.setConcurrency(gatewayProperties.kafka().listenerConcurrency());
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);

        return factory;
    }
}
