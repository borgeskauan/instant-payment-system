package br.kauan.kafkaproducer.domain;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class QueueService {

    private static final String TOPIC = "high-load-binary-topic";

    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    public QueueService(KafkaTemplate<String, byte[]> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public Mono<Void> sendBytes(byte[] data) {
        log.info("Sending {} bytes to Kafka topic: {}", data.length, TOPIC);
        return Mono.fromFuture(() -> kafkaTemplate.send(TOPIC, data))
                .doOnSuccess(result -> log.info("Successfully sent message to Kafka, partition: {}, offset: {}", 
                        result.getRecordMetadata().partition(), 
                        result.getRecordMetadata().offset()))
                .doOnError(error -> log.error("Failed to send message to Kafka", error))
                .then();
    }
}
