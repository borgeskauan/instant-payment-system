package br.kauan.notificationgateway.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.RoundRobinAssignor;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConsumerConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(KafkaConsumerConfig.class)
            .withPropertyValues(
                    "spring.kafka.bootstrap-servers=localhost:9092",
                    "spring.kafka.consumer.group-id=notification-gateway-group"
            );

    @Test
    void defaultsNotificationListenerConcurrencyToTwo() {
        contextRunner.run(context -> {
            var factory = context.getBean(
                    "notificationKafkaListenerContainerFactory",
                    ConcurrentKafkaListenerContainerFactory.class
            );

            assertThat(ReflectionTestUtils.getField(factory, "concurrency")).isEqualTo(2);
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
    void usesRoundRobinPartitionAssignment() {
        contextRunner.run(context -> {
            var consumerFactory = context.getBean("notificationConsumerFactory", ConsumerFactory.class);

            assertThat(consumerFactory.getConfigurationProperties())
                    .containsEntry(
                            ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                            RoundRobinAssignor.class.getName()
                    );
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
}
