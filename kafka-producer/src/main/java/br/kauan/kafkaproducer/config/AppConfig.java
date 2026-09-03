package br.kauan.kafkaproducer.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public record AppConfig(
        int port,
        String kafkaBootstrapServers,
        Path tlsCertificateChain,
        Path tlsPrivateKey,
        Path tlsTrustCertCollection
) {

    private static final int SERVER_PORT = 8001;
    private static final String DEFAULT_KAFKA_BOOTSTRAP_SERVERS = "localhost:9092";

    public static final String PAYMENT_REQUESTS_TOPIC = "spi-payment-requests";
    public static final String PAYMENT_STATUS_REPORTS_TOPIC = "spi-payment-status-reports";

    public static AppConfig fromEnv(Map<String, String> env) {
        String bootstrapServers = env.get("KAFKA_BOOTSTRAP_SERVERS");
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            bootstrapServers = DEFAULT_KAFKA_BOOTSTRAP_SERVERS;
        }
        return new AppConfig(
                SERVER_PORT,
                bootstrapServers,
                requiredPath(env, "KAFKA_PRODUCER_TLS_CERTIFICATE_CHAIN"),
                requiredPath(env, "KAFKA_PRODUCER_TLS_PRIVATE_KEY"),
                requiredPath(env, "KAFKA_PRODUCER_TLS_TRUST_CERT_COLLECTION"));
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

    private static Path requiredPath(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return Path.of(value);
    }
}
