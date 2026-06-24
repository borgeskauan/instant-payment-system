package br.kauan.kafkaproducer.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public record AppConfig(int port, String kafkaBootstrapServers) {

    public static final String PAYMENT_REQUESTS_TOPIC = "spi-payment-requests";
    public static final String PAYMENT_STATUS_REPORTS_TOPIC = "spi-payment-status-reports";

    public static AppConfig fromEnv(Map<String, String> env) {
        int port = Integer.parseInt(env.getOrDefault("SERVER_PORT", "8001"));
        String bootstrapServers = firstPresent(env,
                "KAFKA_BOOTSTRAP_SERVERS",
                "SPRING_KAFKA_BOOTSTRAP_SERVERS",
                "localhost:9092");
        return new AppConfig(port, bootstrapServers);
    }

    public Properties producerProperties() {
        Map<String, Object> values = new HashMap<>();
        values.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        values.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        values.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        values.put("enable.metrics.push", "false");
        values.put(ProducerConfig.ACKS_CONFIG, "all");
        values.put(ProducerConfig.LINGER_MS_CONFIG, "10");
        values.put(ProducerConfig.BATCH_SIZE_CONFIG, "32768");
        values.put(ProducerConfig.BUFFER_MEMORY_CONFIG, "67108864");
        values.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "none");
        Properties properties = new Properties();
        properties.putAll(values);
        return properties;
    }

    private static String firstPresent(Map<String, String> env, String primary, String fallback, String defaultValue) {
        String primaryValue = env.get(primary);
        if (primaryValue != null && !primaryValue.isBlank()) {
            return primaryValue;
        }
        String fallbackValue = env.get(fallback);
        if (fallbackValue != null && !fallbackValue.isBlank()) {
            return fallbackValue;
        }
        return defaultValue;
    }
}
