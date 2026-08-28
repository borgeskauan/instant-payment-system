package br.kauan.notificationgateway.kafka;

import br.kauan.notificationgateway.config.NotificationGatewayProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConsumerConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestPropertiesConfiguration.class, KafkaConsumerConfig.class)
            .withPropertyValues(
                    "spring.kafka.bootstrap-servers=localhost:9092",
                    "spring.kafka.consumer.group-id=notification-gateway-group",
                    "spring.kafka.consumer.auto-offset-reset=earliest",
                    "notification-gateway.kafka.listener-concurrency=1",
                    "notification-gateway.kafka.recent-window-capacity-per-partition=4096",
                    "notification-gateway.pull.cursor-secret=0123456789abcdef0123456789abcdef",
                    "notification-gateway.pull.long-poll-timeout=30s",
                    "notification-gateway.pull.kafka-scan-limit=4096",
                    "notification-gateway.pull.kafka-poll-timeout=100ms"
            );

    @Test
    void defaultsNotificationListenerConcurrencyToOne() {
        contextRunner.run(context -> {
            var factory = context.getBean(
                    "notificationKafkaListenerContainerFactory",
                    ConcurrentKafkaListenerContainerFactory.class
            );

            assertThat(ReflectionTestUtils.getField(factory, "concurrency")).isEqualTo(1);
        });
    }

    @Test
    void deliversEachKafkaPollToTheListenerAsOneBatch() {
        contextRunner.run(context -> {
            var factory = context.getBean(
                    "notificationKafkaListenerContainerFactory",
                    ConcurrentKafkaListenerContainerFactory.class
            );

            assertThat(factory.isBatchListener()).isTrue();
        });
    }

    @Test
    void allowsNotificationListenerConcurrencyOverride() {
        contextRunner
                .withPropertyValues("notification-gateway.kafka.listener-concurrency=4")
                .run(context -> {
                    var factory = context.getBean(
                            "notificationKafkaListenerContainerFactory",
                            ConcurrentKafkaListenerContainerFactory.class
                    );

                    assertThat(ReflectionTestUtils.getField(factory, "concurrency")).isEqualTo(4);
                });
    }

    @Test
    void consumesNotificationPayloadsAsBytes() {
        contextRunner.run(context -> {
            var consumerFactory = context.getBean("notificationConsumerFactory", ConsumerFactory.class);

            assertThat(consumerFactory.getConfigurationProperties())
                    .containsEntry(
                            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                            ByteArrayDeserializer.class
                    );
        });
    }

    @Test
    void usesTheConfiguredKafkaConnectionAndConsumerContract() {
        contextRunner.run(context -> {
            var consumerFactory = context.getBean("notificationConsumerFactory", ConsumerFactory.class);

            assertThat(consumerFactory.getConfigurationProperties())
                    .containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, java.util.List.of("localhost:9092"))
                    .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "notification-gateway-group")
                    .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        });
    }

    @Test
    void historicalSeekConsumersCannotCommitOverTheLiveTailerOffset() {
        contextRunner.run(context -> {
            var consumerFactory = context.getBean("notificationConsumerFactory", ConsumerFactory.class);

            assertThat(consumerFactory.getConfigurationProperties())
                    .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        });
    }

    @Test
    void disablesKafkaClientTelemetryPush() {
        contextRunner.run(context -> {
            var consumerFactory = context.getBean("notificationConsumerFactory", ConsumerFactory.class);

            assertThat(consumerFactory.getConfigurationProperties())
                    .containsEntry("enable.metrics.push", false);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({KafkaProperties.class, NotificationGatewayProperties.class})
    static class TestPropertiesConfiguration {
    }
}
