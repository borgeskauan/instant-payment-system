package br.kauan.spi.adapter.input.kafka.infrastructure.dlq;

import br.kauan.spi.adapter.input.kafka.consumer.DivergentDuplicatePaymentException;
import br.kauan.spi.adapter.input.kafka.consumer.InvalidInboundPayloadException;
import br.kauan.spi.adapter.input.kafka.consumer.StatusReportConflictException;
import br.kauan.spi.adapter.input.kafka.consumer.NotAuthenticatedException;
import br.kauan.spi.adapter.input.kafka.consumer.UnauthorizedPspException;
import br.kauan.spi.config.SpiKafkaProperties;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
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
    private static final String STATUS_REPORT_CONFLICT = "STATUS_REPORT_CONFLICT";
    private static final String NOT_AUTHENTICATED = "NOT_AUTHENTICATED";
    private static final String UNAUTHORIZED_PSP = "UNAUTHORIZED_PSP";
    private static final Duration DLQ_SEND_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaProperties kafkaProperties;
    private final SpiKafkaProperties spiKafkaProperties;

    public KafkaDlqConfig(KafkaProperties kafkaProperties, SpiKafkaProperties spiKafkaProperties) {
        this.kafkaProperties = kafkaProperties;
        this.spiKafkaProperties = spiKafkaProperties;
    }

    @Bean
    public ProducerFactory<String, byte[]> dlqProducerFactory() {
        Map<String, Object> config = new HashMap<>();

        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
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
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + ".dlq", record.partition()));
        recoverer.setHeadersFunction((record, exception) -> DlqHeaders.from(
                record,
                consumerGroupForTopic(record.topic()),
                errorType(exception),
                exception));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(DLQ_SEND_TIMEOUT);
        return recoverer;
    }

    private String errorType(Exception exception) {
        if (exception instanceof InvalidInboundPayloadException) {
            return INVALID_PAYLOAD;
        }
        if (exception instanceof DivergentDuplicatePaymentException) {
            return DIVERGENT_DUPLICATE;
        }
        if (exception instanceof StatusReportConflictException) {
            return STATUS_REPORT_CONFLICT;
        }
        if (exception instanceof NotAuthenticatedException) {
            return NOT_AUTHENTICATED;
        }
        if (exception instanceof UnauthorizedPspException) {
            return UNAUTHORIZED_PSP;
        }
        return BATCH_PROCESSING_ERROR;
    }

    private String consumerGroupForTopic(String topic) {
        return switch (topic) {
            case "spi-payment-requests" -> spiKafkaProperties.paymentRequestGroupId();
            case "spi-payment-status-reports" -> spiKafkaProperties.statusReportGroupId();
            default -> "unknown";
        };
    }
}
