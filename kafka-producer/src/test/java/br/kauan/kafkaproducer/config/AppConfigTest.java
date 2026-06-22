package br.kauan.kafkaproducer.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppConfigTest {

    @Test
    void usesDirectKafkaBootstrapServersWhenPresent() {
        AppConfig config = AppConfig.fromEnv(Map.of(
                "SERVER_PORT", "9000",
                "KAFKA_BOOTSTRAP_SERVERS", "kafka:9092",
                "SPRING_KAFKA_BOOTSTRAP_SERVERS", "ignored:9092"
        ));

        assertEquals(9000, config.port());
        assertEquals("kafka:9092", config.kafkaBootstrapServers());
    }

    @Test
    void keepsSpringKafkaBootstrapFallbackForComposeCompatibility() {
        AppConfig config = AppConfig.fromEnv(Map.of(
                "SPRING_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092"
        ));

        assertEquals(8001, config.port());
        assertEquals("kafka:9092", config.kafkaBootstrapServers());
    }

    @Test
    void usesLocalDefaultsWhenEnvironmentIsEmpty() {
        AppConfig config = AppConfig.fromEnv(Map.of());

        assertEquals(8001, config.port());
        assertEquals("localhost:9092", config.kafkaBootstrapServers());
    }
}
