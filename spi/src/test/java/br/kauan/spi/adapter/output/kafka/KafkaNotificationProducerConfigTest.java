package br.kauan.spi.adapter.output.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaNotificationProducerConfigTest {

    @Test
    void notificationProducerRequiresAllBrokerAcknowledgments() throws Exception {
        KafkaNotificationProducerConfig config = new KafkaNotificationProducerConfig();
        Field bootstrapServers = KafkaNotificationProducerConfig.class.getDeclaredField("bootstrapServers");
        bootstrapServers.setAccessible(true);
        bootstrapServers.set(config, "localhost:9092");

        DefaultKafkaProducerFactory<String, String> producerFactory =
                (DefaultKafkaProducerFactory<String, String>) config.notificationProducerFactory();

        assertThat(producerFactory.getConfigurationProperties())
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all");
    }
}
