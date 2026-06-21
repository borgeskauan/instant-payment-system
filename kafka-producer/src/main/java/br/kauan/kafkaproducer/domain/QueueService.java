package br.kauan.kafkaproducer.domain;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class QueueService {

    private static final String PAYMENT_REQUESTS_TOPIC = "spi-payment-requests";
    private static final String PAYMENT_STATUS_REPORTS_TOPIC = "spi-payment-status-reports";

    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    public QueueService(KafkaTemplate<String, byte[]> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public Mono<Void> sendPaymentRequest(byte[] data) {
        return sendBytes(PAYMENT_REQUESTS_TOPIC, data);
    }

    public Mono<Void> sendStatusReport(byte[] data) {
        return sendBytes(PAYMENT_STATUS_REPORTS_TOPIC, data);
    }

    private Mono<Void> sendBytes(String topic, byte[] data) {
        log.debug("Sending {} bytes to Kafka topic: {}", data.length, topic);
        return Mono.fromFuture(() -> kafkaTemplate.send(topic, data))
                .doOnSuccess(result -> log.debug("Successfully sent message to Kafka, partition: {}, offset: {}",
                        result.getRecordMetadata().partition(), 
                        result.getRecordMetadata().offset()))
                .doOnError(error -> log.error("Failed to send message to Kafka", error))
                .then();
    }
}
