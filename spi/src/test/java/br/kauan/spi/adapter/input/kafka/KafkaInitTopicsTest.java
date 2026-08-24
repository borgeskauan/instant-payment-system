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

    @Test
    void kafkaInitCreatesTheVersionedNotificationLogWithAFixedTopologyAndSevenDayRetention()
            throws Exception {
        String compose = Files.readString(Path.of("..", "infra", "docker-compose.yml"));

        assertThat(compose)
                .contains("ensure_notification_log psp-notifications-v1 8")
                .contains("cleanup.policy=delete")
                .contains("retention.ms=604800000")
                .contains("retention.bytes=-1")
                .contains("notification topic $$topic must have exactly $$partitions partitions");
        assertThat(compose).doesNotContain("ensure_topic psp-notifications 8");
    }
}
