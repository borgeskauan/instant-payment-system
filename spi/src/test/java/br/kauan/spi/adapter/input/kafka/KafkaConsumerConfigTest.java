package br.kauan.spi.adapter.input.kafka;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KafkaConsumerConfigTest {

    @Test
    void kafkaListenerContainerFactoryUsesConfiguredConcurrency() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "listenerConcurrency", 8);

        var factory = config.kafkaListenerContainerFactory(mock(ConsumerFactory.class));
        factory.getContainerProperties().setMessageListener((MessageListener<String, byte[]>) record -> {
        });
        ConcurrentMessageListenerContainer<String, byte[]> container =
                (ConcurrentMessageListenerContainer<String, byte[]>) factory.createContainer("spi-payment-requests");

        assertThat(container.getConcurrency()).isEqualTo(8);
    }
}
