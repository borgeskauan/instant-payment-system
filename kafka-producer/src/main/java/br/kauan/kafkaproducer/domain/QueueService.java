package br.kauan.kafkaproducer.domain;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class QueueService {

    private static final String TOPIC = "high-load-binary-topic";

    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    public QueueService(KafkaTemplate<String, byte[]> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public Mono<Void> sendBytes(byte[] data) {
        return Mono.fromFuture(() -> kafkaTemplate.send(TOPIC, data))
                .then();
    }
}
