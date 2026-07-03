package br.kauan.spi.adapter.input.kafka;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaInitTopicsTest {

    @Test
    void kafkaInitCreatesSpiDlqTopicsWithEightPartitions() throws Exception {
        String compose = Files.readString(Path.of("..", "infra", "docker-compose.yml"));

        assertThat(compose).contains("ensure_topic spi-payment-requests.dlq 8");
        assertThat(compose).contains("ensure_topic spi-payment-status-reports.dlq 8");
    }
}
