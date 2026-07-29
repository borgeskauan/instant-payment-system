package br.kauan.kafkaproducer.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppConfigTest {

    @Test
    void usesDirectKafkaBootstrapServersWhenPresent() {
        Map<String, String> env = tlsEnv();
        env.putAll(Map.of(
                "SERVER_PORT", "9000",
                "KAFKA_BOOTSTRAP_SERVERS", "kafka:9092",
                "SPRING_KAFKA_BOOTSTRAP_SERVERS", "ignored:9092"
        ));
        AppConfig config = AppConfig.fromEnv(env);

        assertEquals(9000, config.port());
        assertEquals("kafka:9092", config.kafkaBootstrapServers());
    }

    @Test
    void keepsSpringKafkaBootstrapFallbackForComposeCompatibility() {
        Map<String, String> env = tlsEnv();
        env.put("SPRING_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092");
        AppConfig config = AppConfig.fromEnv(env);

        assertEquals(8001, config.port());
        assertEquals("kafka:9092", config.kafkaBootstrapServers());
    }

    @Test
    void usesLocalKafkaDefaultsWhenTlsEnvironmentIsPresent() {
        AppConfig config = AppConfig.fromEnv(tlsEnv());

        assertEquals(8001, config.port());
        assertEquals("localhost:9092", config.kafkaBootstrapServers());
        assertEquals(Path.of("/certs/server.crt"), config.tlsCertificateChain());
        assertEquals(Path.of("/certs/server.key"), config.tlsPrivateKey());
        assertEquals(Path.of("/certs/ca.crt"), config.tlsTrustCertCollection());
    }

    @Test
    void rejectsMissingTlsEnvironment() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> AppConfig.fromEnv(Map.of()));

        assertEquals(
                "Missing required environment variable: KAFKA_PRODUCER_TLS_CERTIFICATE_CHAIN",
                exception.getMessage());
    }

    @Test
    void disablesKafkaClientTelemetryPush() {
        AppConfig config = AppConfig.fromEnv(tlsEnv());

        assertEquals("false", config.producerProperties().getProperty("enable.metrics.push"));
    }

    private Map<String, String> tlsEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("KAFKA_PRODUCER_TLS_CERTIFICATE_CHAIN", "/certs/server.crt");
        env.put("KAFKA_PRODUCER_TLS_PRIVATE_KEY", "/certs/server.key");
        env.put("KAFKA_PRODUCER_TLS_TRUST_CERT_COLLECTION", "/certs/ca.crt");
        return env;
    }
}
