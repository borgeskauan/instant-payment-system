package br.kauan.spi.adapter.output.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaNotificationProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, String> notificationProducerFactory() {
        Map<String, Object> config = new HashMap<>();
        
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        
        // Performance tuning for notifications
        config.put(ProducerConfig.ACKS_CONFIG, "1"); // Leader acknowledgment only for lower latency
        config.put(ProducerConfig.LINGER_MS_CONFIG, 5); // Small linger for batching
        config.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384); // 16KB batch size
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy"); // Enable compression
        config.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432); // 32MB buffer
        
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> notificationKafkaTemplate() {
        return new KafkaTemplate<>(notificationProducerFactory());
    }
}
