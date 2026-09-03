package br.kauan.spi.adapter.output.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaNotificationProducerConfigTest {

    @Test
    void notificationProducerRequiresAllBrokerAcknowledgments() {
        KafkaProperties kafka = new KafkaProperties();
        kafka.setBootstrapServers(List.of("localhost:9092"));
        KafkaNotificationProducerConfig config = new KafkaNotificationProducerConfig(kafka);

        DefaultKafkaProducerFactory<String, byte[]> producerFactory =
                (DefaultKafkaProducerFactory<String, byte[]>) config.notificationProducerFactory();

        assertThat(producerFactory.getConfigurationProperties())
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4")
                .containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    }
}
