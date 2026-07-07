package br.kauan.spi.adapter.input.kafka.infrastructure.dlq;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaDlqConfig {

    private static final String CLIENT_TELEMETRY_PUSH_ENABLED = "enable.metrics.push";
    private static final String BATCH_PROCESSING_ERROR = "BATCH_PROCESSING_ERROR";
    private static final String INVALID_PAYLOAD = "INVALID_PAYLOAD";
    private static final String DIVERGENT_DUPLICATE = "DIVERGENT_DUPLICATE";
    private static final Duration DLQ_SEND_TIMEOUT = Duration.ofSeconds(10);

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spi.kafka.payment-request-group-id:spi-payment-request-consumer-group}")
    private String paymentRequestGroupId = "spi-payment-request-consumer-group";

    @Value("${spi.kafka.status-report-group-id:spi-status-report-consumer-group}")
    private String statusReportGroupId = "spi-status-report-consumer-group";

    @Bean
    public ProducerFactory<String, byte[]> dlqProducerFactory() {
        Map<String, Object> config = new HashMap<>();

        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(CLIENT_TELEMETRY_PUSH_ENABLED, false);

        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, byte[]> dlqKafkaTemplate(
            @Qualifier("dlqProducerFactory") ProducerFactory<String, byte[]> producerFactory
    ) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
            @Qualifier("dlqKafkaTemplate") KafkaTemplate<String, byte[]> kafkaTemplate
    ) {
        return createDeadLetterPublishingRecoverer(kafkaTemplate, BATCH_PROCESSING_ERROR);
    }

    @Bean
    public DeadLetterPublishingRecoverer invalidPayloadDeadLetterPublishingRecoverer(
            @Qualifier("dlqKafkaTemplate") KafkaTemplate<String, byte[]> kafkaTemplate
    ) {
        return createDeadLetterPublishingRecoverer(kafkaTemplate, INVALID_PAYLOAD);
    }

    @Bean
    public DeadLetterPublishingRecoverer divergentDuplicateDeadLetterPublishingRecoverer(
            @Qualifier("dlqKafkaTemplate") KafkaTemplate<String, byte[]> kafkaTemplate
    ) {
        return createDeadLetterPublishingRecoverer(kafkaTemplate, DIVERGENT_DUPLICATE);
    }

    private DeadLetterPublishingRecoverer createDeadLetterPublishingRecoverer(
            KafkaTemplate<String, byte[]> kafkaTemplate,
            String errorType
    ) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + ".dlq", record.partition()));
        recoverer.setHeadersFunction((record, exception) -> DlqHeaders.from(
                record,
                consumerGroupForTopic(record.topic()),
                errorType,
                exception));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(DLQ_SEND_TIMEOUT);
        return recoverer;
    }

    private String consumerGroupForTopic(String topic) {
        return switch (topic) {
            case "spi-payment-requests" -> paymentRequestGroupId;
            case "spi-payment-status-reports" -> statusReportGroupId;
            default -> "unknown";
        };
    }
}
