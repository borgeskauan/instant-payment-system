package br.kauan.notificationgateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationGatewayPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "notification-gateway.kafka.listener-concurrency=2",
                    "notification-gateway.kafka.recent-window-capacity-per-partition=2048",
                    "notification-gateway.pull.cursor-secret=0123456789abcdef0123456789abcdef",
                    "notification-gateway.pull.long-poll-timeout=15s",
                    "notification-gateway.pull.kafka-scan-limit=1024",
                    "notification-gateway.pull.kafka-poll-timeout=75ms"
            );

    @Test
    void bindsTheGatewayRuntimeConfiguration() {
        contextRunner.run(context -> {
            NotificationGatewayProperties properties = context.getBean(NotificationGatewayProperties.class);

            assertThat(properties.kafka().listenerConcurrency()).isEqualTo(2);
            assertThat(properties.kafka().recentWindowCapacityPerPartition()).isEqualTo(2048);
            assertThat(properties.pull().longPollTimeout()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.pull().kafkaScanLimit()).isEqualTo(1024);
            assertThat(properties.pull().kafkaPollTimeout()).isEqualTo(Duration.ofMillis(75));
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationGatewayProperties.class)
    static class TestConfiguration {
    }
}
